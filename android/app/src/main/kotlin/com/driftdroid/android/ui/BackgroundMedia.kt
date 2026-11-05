package com.driftdroid.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.PowerManager
import java.io.File
import java.io.IOException
import kotlin.math.max

/**
 * The launcher's moving or still background, picked by the player: a picture, an animated GIF or
 * WebP, or a short video (MP4 or WebM, played muted and looped). The chosen file is copied into the
 * app's own storage, so it keeps working if the original is moved. One kind at a time.
 *
 * Animation and video stop when the phone is in battery saver, when the launcher is not on screen,
 * and when the player turns "Animate the background" off; the first frame is shown instead.
 */
internal object BackgroundMedia {
    enum class Kind { NONE, IMAGE, ANIM, VIDEO }

    private const val PREFS = "launcher"
    private const val KEY_ANIMATE = "bg_animate"
    private const val MAX_ANIM_BYTES = 40L shl 20
    private const val MAX_VIDEO_BYTES = 150L shl 20
    private const val MAX_SIDE = 1080

    private var animation: AnimatedImageDrawable? = null
    private var animationLoaded = false
    private var preview: Bitmap? = null
    private var previewLoaded = false

    fun animFile(context: Context) = File(context.filesDir, "launcher_background.anim")

    fun videoFile(context: Context) = File(context.filesDir, "launcher_background.mp4")

    fun kind(context: Context): Kind =
        when {
            videoFile(context).isFile -> Kind.VIDEO
            animFile(context).isFile -> Kind.ANIM
            Theme.imageFile(context).isFile -> Kind.IMAGE
            else -> Kind.NONE
        }

    fun hasAny(context: Context): Boolean = kind(context) != Kind.NONE

    fun animateEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ANIMATE, true)

    fun setAnimateEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ANIMATE, enabled).apply()
    }

    /** True when moving backgrounds may play right now. */
    fun mayAnimate(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return animateEnabled(context) && power?.isPowerSaveMode != true
    }

    /** Average brightness (0 dark - 1 light) of the background's still, kept so text colour can follow it. */
    fun updateBrightness(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val still = still(context)
        if (still == null) {
            prefs.edit().remove("bg_luma").apply()
            Theme.setMediaLuma(null)
            return
        }
        val small = Bitmap.createScaledBitmap(still, 24, 24, true)
        var sum = 0.0
        for (y in 0 until 24) for (x in 0 until 24) {
            val c = small.getPixel(x, y)
            sum += (0.2126 * android.graphics.Color.red(c) + 0.7152 * android.graphics.Color.green(c) + 0.0722 * android.graphics.Color.blue(c)) / 255.0
        }
        val luma = (sum / (24 * 24)).toFloat()
        prefs.edit().putFloat("bg_luma", luma).apply()
        Theme.setMediaLuma(luma)
    }

    @Synchronized
    fun remove(context: Context) {
        for (f in listOf(videoFile(context), animFile(context), Theme.imageFile(context))) f.delete()
        Theme.forgetImage()
        animation?.stop()
        animation = null
        animationLoaded = false
        preview = null
        previewLoaded = false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("bg_luma").apply()
        Theme.setMediaLuma(null)
    }

    /**
     * Copies a picked file in as the background and returns what it turned out to be. A still picture
     * goes through [Theme.importImage]; GIF and animated WebP are kept as they are; MP4 and WebM are
     * kept as they are too. Throws [IOException] with a plain message when it is not usable.
     */
    @Synchronized
    fun import(context: Context, uri: Uri): Kind {
        val head = ByteArray(16)
        val read = context.contentResolver.openInputStream(uri)?.use { it.read(head) } ?: throw IOException("couldn't open the file")
        if (read < 8) throw IOException("that file is empty")
        val isGif = head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte()
        val isVideo =
            (head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() && head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()) ||
                (head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte())
        if (isVideo) {
            copyChecked(context, uri, videoFile(context), MAX_VIDEO_BYTES, "video")
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(videoFile(context).path)
                if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") throw IOException("that file has no video")
            } catch (e: IOException) {
                videoFile(context).delete()
                throw e
            } catch (e: RuntimeException) {
                videoFile(context).delete()
                throw IOException("this phone can't play that video")
            } finally {
                runCatching { retriever.release() }
            }
            removeOthers(context, Kind.VIDEO)
            preview = null
            previewLoaded = false
            updateBrightness(context)
            return Kind.VIDEO
        }
        // GIF, animated WebP/PNG, or a still picture: let the image decoder say which.
        val animated =
            isGif || runCatching {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, uri)) { d, _, _ -> d.setTargetSampleSize(8) } is AnimatedImageDrawable
            }.getOrDefault(false)
        if (animated) {
            copyChecked(context, uri, animFile(context), MAX_ANIM_BYTES, "animation")
            val ok = runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(animFile(context))) is AnimatedImageDrawable }.getOrDefault(false)
            if (!ok) {
                animFile(context).delete()
                throw IOException("this phone can't read that animation")
            }
            removeOthers(context, Kind.ANIM)
            animation = null
            animationLoaded = false
            preview = null
            previewLoaded = false
            updateBrightness(context)
            return Kind.ANIM
        }
        Theme.importImage(context, uri)
        removeOthers(context, Kind.IMAGE)
        preview = null
        previewLoaded = false
        updateBrightness(context)
        return Kind.IMAGE
    }

    /** Installs picture bytes (from an imported theme) as the background picture, after checking they decode. */
    @Synchronized
    fun installImage(context: Context, bytes: ByteArray) {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("the picture in that theme can't be read")
        val temporary = File(context.filesDir, "launcher_background.jpg.saving")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(Theme.imageFile(context))) {
            temporary.delete()
            throw IOException("couldn't save the picture")
        }
        removeOthers(context, Kind.IMAGE)
        Theme.forgetImage()
        preview = null
        previewLoaded = false
        updateBrightness(context)
    }

    private fun removeOthers(context: Context, keep: Kind) {
        if (keep != Kind.VIDEO) videoFile(context).delete()
        if (keep != Kind.ANIM) animFile(context).delete()
        if (keep != Kind.IMAGE) {
            Theme.imageFile(context).delete()
            Theme.forgetImage()
        }
    }

    private fun copyChecked(context: Context, uri: Uri, target: File, limit: Long, what: String) {
        val temporary = File(target.path + ".saving")
        try {
            var total = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > limit) throw IOException("that $what is too big (the limit is ${limit shr 20} MB)")
                        output.write(buffer, 0, n)
                    }
                }
            } ?: throw IOException("couldn't open the file")
            if (!temporary.renameTo(target)) throw IOException("couldn't save the $what")
        } finally {
            temporary.delete()
        }
    }

    /** The animated background, decoded once at a screen-friendly size. Null when there is none. */
    @Synchronized
    fun animation(context: Context): AnimatedImageDrawable? {
        if (!animationLoaded) {
            animationLoaded = true
            val file = animFile(context)
            animation =
                if (!file.isFile) {
                    null
                } else {
                    runCatching {
                        ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                            val long = max(info.size.width, info.size.height)
                            if (long > MAX_SIDE) {
                                val f = MAX_SIDE.toFloat() / long
                                decoder.setTargetSize((info.size.width * f).toInt().coerceAtLeast(1), (info.size.height * f).toInt().coerceAtLeast(1))
                            }
                        } as? AnimatedImageDrawable
                    }.getOrNull()?.also { it.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE }
                }
        }
        return animation
    }

    /** A still of the background: the picture itself, or the first frame of an animation or video. */
    @Synchronized
    fun still(context: Context): Bitmap? {
        when (kind(context)) {
            Kind.IMAGE -> return Theme.image(context)
            Kind.NONE -> return null
            else -> {}
        }
        if (!previewLoaded) {
            previewLoaded = true
            preview =
                runCatching {
                    if (kind(context) == Kind.ANIM) {
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(animFile(context))) { decoder, info, _ ->
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            val long = max(info.size.width, info.size.height)
                            if (long > MAX_SIDE) {
                                val f = MAX_SIDE.toFloat() / long
                                decoder.setTargetSize((info.size.width * f).toInt().coerceAtLeast(1), (info.size.height * f).toInt().coerceAtLeast(1))
                            }
                        }
                    } else {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(videoFile(context).path)
                            val frame = retriever.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: retriever.getFrameAtTime(0)
                            frame?.let { f ->
                                val long = max(f.width, f.height)
                                if (long > MAX_SIDE) {
                                    val s = MAX_SIDE.toFloat() / long
                                    Bitmap.createScaledBitmap(f, (f.width * s).toInt().coerceAtLeast(1), (f.height * s).toInt().coerceAtLeast(1), true)
                                } else {
                                    f
                                }
                            }
                        } finally {
                            runCatching { retriever.release() }
                        }
                    }
                }.getOrNull()
        }
        return preview
    }

    /** True while the launcher is on screen: only then may an animation run. */
    @Volatile var resumed = false
        private set

    fun setResumed(on: Boolean) {
        resumed = on
        if (on) animation?.start() else animation?.stop()
    }
}
