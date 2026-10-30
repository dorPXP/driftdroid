package com.driftdroid.android

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * The game code, which this app does not ship: DriftDroid Builder (tools/pc-builder) makes it on
 * the player's PC from their own disc and packs it as a zip with libWiiCompiled.so,
 * libGameCombined.so (left out when the player builds without Retro Rewind) and
 * driftdroid-pack.json. The player imports that zip here; MainActivity loads
 * the libraries from [directory] instead of from the APK.
 *
 * A pack fits exactly one app build: the libraries contain the app's own JNI glue, so the pack's
 * toolkit id (a hash of everything the builder links against) must equal the one this APK was built
 * with (assets/toolkit-id.txt, written by the release build).
 */
internal object GamePack {
    private const val BASE_LIBRARY = "libWiiCompiled.so"
    private const val RETRO_REWIND_LIBRARY = "libGameCombined.so"
    val LIBRARIES = listOf(BASE_LIBRARY, RETRO_REWIND_LIBRARY)
    private const val MANIFEST = "driftdroid-pack.json"
    const val BUILDER_URL = "https://github.com/dorPXP/driftdroid/releases/latest"

    fun directory(context: Context) = File(context.filesDir, "gamepack")

    fun library(context: Context, name: String) = File(directory(context), name)

    fun expectedToolkitId(context: Context): String? =
        runCatching { context.assets.open("toolkit-id.txt").bufferedReader().readText().trim() }.getOrNull()

    private fun installedToolkitId(context: Context): String? =
        runCatching { JSONObject(File(directory(context), MANIFEST).readText()).getString("toolkit") }.getOrNull()

    enum class State { Missing, WrongVersion, Ready }

    fun state(context: Context): State {
        val dir = directory(context)
        if (!File(dir, BASE_LIBRARY).isFile) return State.Missing
        val expected = expectedToolkitId(context) ?: return State.Ready
        return if (installedToolkitId(context) == expected) State.Ready else State.WrongVersion
    }

    /** False when the installed pack was built without Retro Rewind. A development build (no
     * toolkit id) carries both libraries in the APK. */
    fun hasRetroRewind(context: Context): Boolean =
        expectedToolkitId(context) == null || File(directory(context), RETRO_REWIND_LIBRARY).isFile

    /** Copies the picked zip in. Returns null on success, otherwise what went wrong. Slow (a few
     * hundred MB): call from a background thread. */
    fun import(context: Context, source: Uri, progress: (Long) -> Unit): String? {
        val staging = File(context.filesDir, "gamepack.importing")
        staging.deleteRecursively()
        staging.mkdirs()
        var manifest: JSONObject? = null
        try {
            val input = context.contentResolver.openInputStream(source) ?: return "couldn't open the picked file"
            var copied = 0L
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.substringAfterLast('/')
                    if (!entry.isDirectory && (name in LIBRARIES || name == MANIFEST)) {
                        File(staging, name).outputStream().use { out ->
                            val buffer = ByteArray(1 shl 20)
                            while (true) {
                                val n = zip.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                copied += n
                                progress(copied)
                            }
                        }
                        if (name == MANIFEST) manifest = runCatching { JSONObject(File(staging, name).readText()) }.getOrNull()
                    }
                    entry = zip.nextEntry
                }
            }
            if (manifest == null || !File(staging, BASE_LIBRARY).isFile) {
                staging.deleteRecursively()
                return "that isn't a DriftDroid game pack (make one with DriftDroid Builder on a PC)"
            }
            val expected = expectedToolkitId(context)
            val packToolkit = manifest!!.optString("toolkit")
            if (expected != null && packToolkit != expected) {
                staging.deleteRecursively()
                val packVersion = manifest!!.optString("version", "another version")
                return "this game pack was made for DriftDroid $packVersion. Make a new one with the DriftDroid Builder " +
                    "that matches this version of the app."
            }
            val dir = directory(context)
            val old = File(context.filesDir, "gamepack.old")
            old.deleteRecursively()
            if (dir.exists() && !dir.renameTo(old)) return "couldn't replace the old game pack"
            if (!staging.renameTo(dir)) {
                old.renameTo(dir)
                return "couldn't store the game pack"
            }
            old.deleteRecursively()
            return null
        } catch (e: Exception) {
            staging.deleteRecursively()
            return e.message ?: "unknown error"
        }
    }
}
