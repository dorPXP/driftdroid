package com.driftdroid.android.mods

import android.content.Context
import com.driftdroid.android.ModManager
import com.driftdroid.android.net.Http
import java.io.File

/**
 * Downloads one file of a GameBanana mod and installs it with [ModManager.installArchive] - the
 * "no moving files around by hand" path. One at a time; the launcher's task bar polls the fields
 * below (same pattern as RetroRewindInstallStatus), so a recreated Activity picks a running
 * install straight back up.
 */
internal object ModInstallJob {
    enum class Stage { IDLE, DOWNLOADING, UNPACKING, DONE, FAILED }

    @Volatile var stage: Stage = Stage.IDLE
        private set
    @Volatile var title: String = ""
        private set
    @Volatile var modId: Int = 0
        private set
    @Volatile var bytesDone: Long = 0
        private set
    @Volatile var bytesTotal: Long = 0
        private set
    @Volatile var filesUnpacked: Int = 0
        private set
    @Volatile var error: String? = null
        private set

    /** Goes up by one every time a job finishes, so pollers can tell a new result from an old one. */
    @Volatile var finishedCount: Int = 0
        private set

    @Volatile private var cancelRequested = false

    val running: Boolean get() = stage == Stage.DOWNLOADING || stage == Stage.UNPACKING

    /** Starts installing [file] of [mod]. False when another install is still running. */
    @Synchronized
    fun start(context: Context, mod: GameBanana.ModDetails, file: GameBanana.ModFile): Boolean {
        if (running) return false
        val app = context.applicationContext
        title = mod.summary.name
        modId = mod.summary.id
        bytesDone = 0
        bytesTotal = file.size
        filesUnpacked = 0
        error = null
        cancelRequested = false
        stage = Stage.DOWNLOADING
        Thread({ run(app, mod, file) }, "mod-install").start()
        return true
    }

    fun cancel() {
        cancelRequested = true
    }

    private fun run(context: Context, mod: GameBanana.ModDetails, file: GameBanana.ModFile) {
        val downloads = File(context.cacheDir, "mod_downloads")
        val archive = File(downloads, "${file.id}.${file.extension.ifEmpty { "bin" }}")
        try {
            // A little over what GameBanana lists, so a slightly re-uploaded file still fits; never
            // more than 4 GB.
            val limit = if (file.size > 0) (file.size + file.size / 4 + (8L shl 20)).coerceAtMost(4L shl 30) else 4L shl 30
            Http.download(file.downloadUrl, GameBanana.HOSTS, archive, limit, { cancelRequested }) { done, total ->
                bytesDone = done
                if (total > 0) bytesTotal = total
            }
            if (cancelRequested) throw Http.CancelledException()
            stage = Stage.UNPACKING
            val meta =
                ModManager.Meta(
                    source = "gamebanana",
                    gameBananaId = mod.summary.id,
                    title = mod.summary.name,
                    author = mod.summary.author.ifBlank { null },
                    version = mod.version,
                    thumbnailUrl = mod.summary.thumbnailUrl,
                    fileName = file.name,
                    installedAt = System.currentTimeMillis(),
                )
            ModManager.installArchive(context, archive, meta) { filesUnpacked = it }
            finish(Stage.DONE, null)
        } catch (e: Throwable) {
            finish(Stage.FAILED, Http.describe(e))
        } finally {
            archive.delete()
            File(archive.path + ".part").delete()
        }
    }

    private fun finish(result: Stage, message: String?) {
        error = message
        stage = result
        finishedCount++
    }
}
