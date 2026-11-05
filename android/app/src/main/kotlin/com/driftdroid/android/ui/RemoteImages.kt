package com.driftdroid.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.driftdroid.android.mii.MiiStudio
import com.driftdroid.android.mods.GameBanana
import com.driftdroid.android.net.Http
import java.io.File
import java.security.MessageDigest
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * Pictures from the internet for the launcher's lists: GameBanana screenshots and Mii faces.
 * A small memory cache in front of a disk cache (cacheDir, so Android can reclaim it), four
 * download threads, and at most one request per picture however many rows ask for it. Rows that
 * get recycled while their picture is loading never receive someone else's.
 */
internal object RemoteImages {
    private val HOSTS = GameBanana.HOSTS + MiiStudio.HOST
    private const val DISK_LIMIT_BYTES = 48L shl 20
    private const val MAX_IMAGE_BYTES = 6L shl 20
    private const val RETRY_AFTER_MS = 120_000L

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "remote-images").apply { isDaemon = true } }
    private val memory =
        object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 10 / 1024).toInt()) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
        }
    private val waiting = HashMap<String, MutableList<(Bitmap?) -> Unit>>()
    private val failedAt = HashMap<String, Long>()
    private val bound = WeakHashMap<ImageView, String>()
    @Volatile private var diskDir: File? = null

    fun init(context: Context) {
        if (diskDir != null) return
        val dir = File(context.cacheDir, "remote-images")
        dir.mkdirs()
        diskDir = dir
        pool.execute { trim(dir) }
    }

    /** Shows [url] in [view] (after [placeholder] while it loads). Main thread only. */
    fun bind(view: ImageView, url: String?, targetPx: Int, placeholder: Drawable?) {
        if (url == null) {
            bound.remove(view)
            view.setImageDrawable(placeholder)
            return
        }
        val key = "$targetPx|$url"
        if (bound[view] == key && view.drawable != null && view.drawable !== placeholder) return
        bound[view] = key
        val cached = memory.get(key)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(placeholder)
        load(url, targetPx) { bitmap ->
            if (bitmap != null && bound[view] == key) {
                view.alpha = 0f
                view.setImageBitmap(bitmap)
                view.animate().alpha(1f).setDuration(160).start()
            }
        }
    }

    /** Forgets what [view] was showing, so a late picture can't land in it. */
    fun clear(view: ImageView) {
        bound.remove(view)
    }

    /** Fetches [url] scaled down to about [targetPx]; [done] runs on the main thread. */
    fun load(url: String, targetPx: Int, done: (Bitmap?) -> Unit) {
        val key = "$targetPx|$url"
        memory.get(key)?.let {
            done(it)
            return
        }
        val failed = failedAt[key]
        if (failed != null && System.currentTimeMillis() - failed < RETRY_AFTER_MS) {
            done(null)
            return
        }
        val list = waiting[key]
        if (list != null) {
            list.add(done)
            return
        }
        waiting[key] = mutableListOf(done)
        pool.execute {
            val bitmap = runCatching { fetch(url, targetPx) }.getOrNull()
            main.post {
                if (bitmap != null) memory.put(key, bitmap) else failedAt[key] = System.currentTimeMillis()
                waiting.remove(key)?.forEach { it(bitmap) }
            }
        }
    }

    private fun fetch(url: String, targetPx: Int): Bitmap? {
        val dir = diskDir
        val file = dir?.let { File(it, sha1(url)) }
        val bytes =
            if (file != null && file.isFile) {
                file.setLastModified(System.currentTimeMillis())
                file.readBytes()
            } else {
                val downloaded = Http.getBytes(url, HOSTS, MAX_IMAGE_BYTES, "image/*")
                if (file != null) runCatching { file.writeBytes(downloaded) }
                downloaded
            }
        return decode(bytes, targetPx)
    }

    private fun decode(bytes: ByteArray, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx / 2) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun trim(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (file in files) {
            total += file.length()
            if (total > DISK_LIMIT_BYTES) file.delete()
        }
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
