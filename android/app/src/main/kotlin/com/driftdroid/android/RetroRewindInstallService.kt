package com.driftdroid.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Runs the ~2GB Retro Rewind copy/extract as a foreground service instead of a plain Thread owned
 * by ModePickerActivity (see git history/session notes: a bare background Thread died the instant
 * Android reclaimed the backgrounded activity's process for memory - a real risk for an operation
 * that realistically takes several minutes and invites the user to go do something else while it
 * runs). A foreground service with an active notification tells Android to treat this process as
 * foreground-priority for the duration, so it survives the Activity being destroyed/recreated and
 * ordinary background memory reclaim. It does NOT survive a force-stop or reboot - that would need
 * WorkManager's durable, persisted work requests, which is a bigger lift than this problem
 * currently warrants (queued as follow-up if it turns out to matter in practice).
 *
 * Progress is published to [RetroRewindInstallStatus] rather than any direct callback, since the
 * whole point is that no particular Activity instance can be assumed to be alive to receive one.
 */
class RetroRewindInstallService : Service() {

    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || worker != null) {
            // Already running (or a stray restart with no intent) - nothing new to start.
            return START_NOT_STICKY
        }

        ensureNotificationChannel()
        startForegroundCompat(buildNotification("Preparing Retro Rewind install..."))

        val thread =
            Thread {
                val error =
                    when (intent.action) {
                        ACTION_INSTALL_FOLDER -> runFolderInstall(intent)
                        ACTION_INSTALL_ZIP -> runZipInstall(intent)
                        ACTION_INSTALL_DOWNLOAD -> runDownloadInstall()
                        else -> "unknown install action"
                    }
                RetroRewindInstallStatus.complete(error)
                stopForegroundCompat()
                stopSelf(startId)
            }
        worker = thread
        thread.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Deliberately does NOT interrupt worker - if Android is stopping this service instance
        // for its own reasons while the copy is still going, letting the copy finish (or fail on
        // its own terms, e.g. a real I/O error) is safer than tearing it down mid-write. The
        // service being foreground-priority is what's supposed to prevent this from happening
        // under ordinary memory pressure in the first place.
        super.onDestroy()
    }

    private fun runFolderInstall(intent: Intent): String? {
        val sourceUri = intent.getParcelableUriExtra(EXTRA_SOURCE_URI) ?: return "missing source"
        val source = DocumentFile.fromSingleUri(this, sourceUri) ?: return "couldn't open the selected folder"
        RetroRewindInstallStatus.start("Copying Retro Rewind files")
        val destRoot = retroRewindRoot()

        val requiredBytes = sumTreeSize(source)
        checkAvailableSpace((requiredBytes.toDouble() * FOLDER_COPY_SPACE_FACTOR).toLong())?.let { return it }

        val filesCopied = intArrayOf(0)
        return copyTree(source, destRoot, filesCopied)
    }

    private fun runZipInstall(intent: Intent): String? {
        val zipUri = intent.getParcelableUriExtra(EXTRA_SOURCE_URI) ?: return "missing source"
        val name = intent.getStringExtra(EXTRA_ZIP_NAME) ?: "the archive"
        RetroRewindInstallStatus.start("Extracting \"$name\"")

        val zipSize = queryFileSize(zipUri)
        if (zipSize > 0) {
            checkAvailableSpace((zipSize.toDouble() * ZIP_EXTRACT_SPACE_FACTOR).toLong())?.let { return it }
        }

        val scratchDir = File(filesDir, "WiiCompiled/.retro_rewind_zip_tmp")
        val filesExtracted = intArrayOf(0)
        val error = extractZipAndInstall({ contentResolver.openInputStream(zipUri) }, scratchDir, filesExtracted)
        scratchDir.deleteRecursively()
        return error
    }

    /**
     * Fetches the one official, pinned Retro Rewind release directly from update.rwfc.net (see
     * RetroRewindRelease.kt) instead of requiring the player to have already downloaded a zip
     * themselves - matches how KartPad's Android port does its Retro Rewind install. Verified by
     * exact byte size and SHA-256 before it's ever unzipped, so a corrupted/truncated/tampered
     * download fails closed rather than silently installing something unexpected.
     */
    private fun runDownloadInstall(): String? {
        RetroRewindInstallStatus.start("Downloading Retro Rewind ${RetroRewindRelease.VERSION}")
        // Enough room for the downloaded archive AND its extracted contents to coexist, plus the
        // same scratch/rename headroom the SAF zip path budgets for.
        checkAvailableSpace(
            RetroRewindRelease.ARCHIVE_BYTES + (RetroRewindRelease.ARCHIVE_BYTES.toDouble() * ZIP_EXTRACT_SPACE_FACTOR).toLong(),
        )?.let { return it }

        val downloadFile = File(cacheDir, "RetroRewind-${RetroRewindRelease.VERSION}.zip")
        val downloadError = downloadAndVerify(RetroRewindRelease.ARCHIVE_URL, downloadFile)
        if (downloadError != null) {
            downloadFile.delete()
            return downloadError
        }

        RetroRewindInstallStatus.start("Extracting Retro Rewind ${RetroRewindRelease.VERSION}")
        val scratchDir = File(filesDir, "WiiCompiled/.retro_rewind_zip_tmp")
        val filesExtracted = intArrayOf(0)
        val error = extractZipAndInstall({ downloadFile.inputStream() }, scratchDir, filesExtracted)
        scratchDir.deleteRecursively()
        downloadFile.delete()
        return error
    }

    /** Plain HTTPS GET with a bounded redirect chain - no resume support (unlike KartPad's Range-
     * request version), traded for simplicity: a failed/interrupted download is just deleted and
     * retried from scratch next time, same as any other failed install here. */
    private fun downloadAndVerify(url: String, dest: File): String? {
        var currentUrl = url
        var connection: java.net.HttpURLConnection? = null
        try {
            for (redirect in 0..MAX_REDIRECTS) {
                val parsed = java.net.URL(currentUrl)
                if (parsed.protocol != "https") {
                    return "refusing a non-HTTPS download URL"
                }
                val conn = parsed.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.instanceFollowRedirects = false
                conn.connect()
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: return "redirect with no Location header"
                    conn.disconnect()
                    currentUrl = location
                    continue
                }
                if (code != java.net.HttpURLConnection.HTTP_OK) {
                    conn.disconnect()
                    return "download failed (HTTP $code)"
                }
                connection = conn
                break
            }
            val conn = connection ?: return "too many redirects"

            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            RetroRewindInstallStatus.totalBytes = RetroRewindRelease.ARCHIVE_BYTES
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        RetroRewindInstallStatus.downloadedBytes = downloaded
                        RetroRewindInstallStatus.filesDone = (downloaded / (1024 * 1024)).toInt()
                        updateNotificationThrottled(
                            "Downloading... ${downloaded / (1024 * 1024)}MB / ${RetroRewindRelease.ARCHIVE_BYTES / (1024 * 1024)}MB",
                        )
                    }
                }
            }
            conn.disconnect()

            if (downloaded != RetroRewindRelease.ARCHIVE_BYTES) {
                return "download size mismatch (got $downloaded bytes, expected ${RetroRewindRelease.ARCHIVE_BYTES})"
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualHash.equals(RetroRewindRelease.ARCHIVE_SHA256, ignoreCase = true)) {
                return "downloaded file failed verification (hash mismatch) - try again"
            }
            return null
        } catch (e: Exception) {
            return e.message ?: "download failed"
        }
    }

    // --- Copy/extract logic, moved verbatim in spirit from ModePickerActivity - only the
    // notification progress hook and RetroRewindInstallStatus writes are new. ---

    private fun retroRewindRoot(): File = File(filesDir, MainActivity.RETRO_REWIND_STAGING_SUBDIR)

    private fun checkAvailableSpace(requiredBytes: Long): String? {
        val stat = android.os.StatFs(filesDir.path)
        val available = stat.availableBytes
        val needed = requiredBytes + SPACE_RESERVE_BYTES
        if (available < needed) {
            val availableMb = available / (1024 * 1024)
            val neededMb = needed / (1024 * 1024)
            return "Not enough free storage: ${availableMb}MB available, need about ${neededMb}MB. Free up space and try again."
        }
        return null
    }

    private fun sumTreeSize(doc: DocumentFile): Long {
        var total = 0L
        for (child in doc.listFiles()) {
            if (Thread.currentThread().isInterrupted) return total
            total += if (child.isDirectory) sumTreeSize(child) else child.length()
        }
        return total
    }

    private fun copyTree(source: DocumentFile, destDir: File, filesCopied: IntArray): String? {
        return try {
            destDir.mkdirs()
            for (child in source.listFiles()) {
                val name = child.name ?: continue
                if (child.isDirectory) {
                    val error = copyTree(child, File(destDir, name), filesCopied)
                    if (error != null) return error
                } else {
                    contentResolver.openInputStream(child.uri)?.use { input ->
                        File(destDir, name).outputStream().use { output -> input.copyTo(output) }
                    } ?: return "couldn't open $name"
                    filesCopied[0]++
                    RetroRewindInstallStatus.filesDone = filesCopied[0]
                    updateNotificationThrottled("Copying Retro Rewind files... ${filesCopied[0]} files done")
                }
            }
            null
        } catch (e: Exception) {
            e.message ?: "unknown error"
        }
    }

    private fun queryFileSize(uri: Uri): Long =
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else -1L
            } ?: -1L
        } catch (_: Exception) {
            -1L
        }

    private fun extractZipAndInstall(
        openInput: () -> java.io.InputStream?,
        scratchDir: File,
        filesExtracted: IntArray,
    ): String? {
        return try {
            scratchDir.deleteRecursively()
            scratchDir.mkdirs()
            val scratchCanonical = scratchDir.canonicalFile
            openInput()?.use { input ->
                java.util.zip.ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val outFile = File(scratchDir, entry.name)
                        if (!outFile.canonicalPath.startsWith(scratchCanonical.path + File.separator) &&
                            outFile.canonicalPath != scratchCanonical.path
                        ) {
                            return "archive contains an unsafe file path"
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { output -> zip.copyTo(output) }
                            filesExtracted[0]++
                            RetroRewindInstallStatus.filesDone = filesExtracted[0]
                            updateNotificationThrottled("Extracting... ${filesExtracted[0]} files done")
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: return "couldn't open the archive"

            val resolved = resolveRetroRewind6(scratchDir) ?: return "the archive doesn't contain RetroRewind6/Binaries/Code.pul"
            val destRoot = retroRewindRoot()
            destRoot.deleteRecursively()
            destRoot.parentFile?.mkdirs()
            if (!resolved.renameTo(destRoot)) {
                resolved.copyRecursively(destRoot, overwrite = true)
            }
            null
        } catch (e: Exception) {
            e.message ?: "unknown error"
        }
    }

    private fun resolveRetroRewind6(picked: File): File? {
        val candidates = mutableListOf(picked, File(picked, "RetroRewind6"))
        picked.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                candidates.add(File(child, "RetroRewind6"))
            }
        }
        return candidates.firstOrNull { File(it, "Binaries/Code.pul").isFile }
    }

    // --- Notification plumbing ---

    private var lastNotificationUpdateMs = 0L

    private fun updateNotificationThrottled(text: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdateMs < NOTIFICATION_UPDATE_INTERVAL_MS) return
        lastNotificationUpdateMs = now
        val manager = getSystemService(NotificationManager::class.java)
        try {
            manager?.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted - the service (and the copy) still runs fine, the
            // user just won't see live progress in the notification shade.
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Installing Retro Rewind")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return
        val channel =
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Retro Rewind install",
                NotificationManager.IMPORTANCE_LOW,
            )
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        @Suppress("DEPRECATION")
        stopForeground(true)
    }

    companion object {
        const val ACTION_INSTALL_FOLDER = "com.driftdroid.android.INSTALL_RETRO_REWIND_FOLDER"
        const val ACTION_INSTALL_ZIP = "com.driftdroid.android.INSTALL_RETRO_REWIND_ZIP"
        const val ACTION_INSTALL_DOWNLOAD = "com.driftdroid.android.INSTALL_RETRO_REWIND_DOWNLOAD"
        const val EXTRA_SOURCE_URI = "source_uri"
        const val EXTRA_ZIP_NAME = "zip_name"

        private const val NOTIFICATION_CHANNEL_ID = "retro_rewind_install"
        private const val NOTIFICATION_ID = 0x52520100
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 700L
        private const val SPACE_RESERVE_BYTES = 256L * 1024 * 1024
        private const val ZIP_EXTRACT_SPACE_FACTOR = 2.5
        private const val FOLDER_COPY_SPACE_FACTOR = 1.15
        private const val MAX_REDIRECTS = 5
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 30_000

        fun startFolderInstall(context: Context, sourceUri: Uri) {
            val intent = Intent(context, RetroRewindInstallService::class.java)
            intent.action = ACTION_INSTALL_FOLDER
            intent.putExtra(EXTRA_SOURCE_URI, sourceUri)
            context.startForegroundService(intent)
        }

        fun startZipInstall(context: Context, zipUri: Uri, name: String) {
            val intent = Intent(context, RetroRewindInstallService::class.java)
            intent.action = ACTION_INSTALL_ZIP
            intent.putExtra(EXTRA_SOURCE_URI, zipUri)
            intent.putExtra(EXTRA_ZIP_NAME, name)
            context.startForegroundService(intent)
        }

        fun startDownloadInstall(context: Context) {
            val intent = Intent(context, RetroRewindInstallService::class.java)
            intent.action = ACTION_INSTALL_DOWNLOAD
            context.startForegroundService(intent)
        }
    }
}

private fun Intent.getParcelableUriExtra(key: String): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }
