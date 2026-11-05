package com.driftdroid.android

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.driftdroid.android.mods.ArchiveExtractor
import org.json.JSONObject
import java.io.File

/**
 * User-installed mods: Riivolution-shaped packs (a riivolution folder with an XML file in it, plus
 * replacement files) or plain loose files such as most GameBanana downloads - covers texture packs,
 * UI skins, character/vehicle model swaps and custom tracks alike, since none of this is
 * texture-specific: it's the same generic disc-file-replacement pipeline Retro Rewind's own custom
 * tracks use. Each mod lives in its own subdirectory of [packsDir] (still named "TexturePacks" on
 * disk - renaming it would orphan whatever's already imported there for existing installs, and
 * it's an internal implementation detail the user never sees), registered as an extra DVD overlay
 * root (see nativeAddOverlayRoot / android_jni_bridge.cpp's g_androidOverlayRoots) exactly like
 * Retro Rewind's own install already is. The actual file-serve pipeline (RiivoDiscoverRoots ->
 * ScanOverlayRoot -> RegisterFileEntry, runtime/src/hle/storage) needs no changes for this - it
 * never distinguishes where an overlay root's files came from, and a root with no XML has its
 * files matched to disc files by name (dvd.cpp's ApplyFolderByNameMapping).
 *
 * Mods are only picked up at the next app launch (same limitation as Retro Rewind: nothing here
 * can hot-swap which files the running guest fiber is reading from), so enable/disable/install all
 * just edit files on disk. The Mods page in the launcher is the UI for all of this.
 */
object ModManager {

    private const val DISABLED_MARKER = ".disabled"
    private const val ORDER_FILE = ".mod_order"
    private const val META_FILE = ".driftdroid_mod.json"

    /** Where a mod came from, kept next to its files. Only GameBanana installs have most fields. */
    data class Meta(
        val source: String,
        val gameBananaId: Int?,
        val title: String,
        val author: String?,
        val version: String?,
        val thumbnailUrl: String?,
        val fileName: String?,
        val installedAt: Long,
    ) {
        fun toJson(): JSONObject =
            JSONObject()
                .put("source", source)
                .put("gamebananaId", gameBananaId ?: JSONObject.NULL)
                .put("title", title)
                .put("author", author ?: JSONObject.NULL)
                .put("version", version ?: JSONObject.NULL)
                .put("thumbnailUrl", thumbnailUrl ?: JSONObject.NULL)
                .put("fileName", fileName ?: JSONObject.NULL)
                .put("installedAt", installedAt)

        companion object {
            fun fromJson(o: JSONObject): Meta =
                Meta(
                    source = o.optString("source", "file"),
                    gameBananaId = if (o.isNull("gamebananaId")) null else o.optInt("gamebananaId"),
                    title = o.optString("title"),
                    author = if (o.isNull("author")) null else o.optString("author"),
                    version = if (o.isNull("version")) null else o.optString("version"),
                    thumbnailUrl = if (o.isNull("thumbnailUrl")) null else o.optString("thumbnailUrl"),
                    fileName = if (o.isNull("fileName")) null else o.optString("fileName"),
                    installedAt = o.optLong("installedAt"),
                )
        }
    }

    data class Pack(val dir: File, val enabled: Boolean, val meta: Meta?) {
        val name: String get() = dir.name
        val title: String get() = meta?.title?.takeIf { it.isNotBlank() } ?: dir.name
    }

    fun packsDir(context: Context): File = File(context.filesDir, "WiiCompiled/TexturePacks")

    private fun orderFile(context: Context): File = File(packsDir(context), ORDER_FILE)

    // Priority order, top (index 0) wins: dvd.cpp applies discovered overlay roots in REVERSE
    // (overlays.rbegin()..rend(), "the explicitly configured root outranks the mod manifest"),
    // so the root at index 0 - the first one Kotlin pushes via enabledOverlayRoots/
    // nativeAddOverlayRoot - is the LAST one applied and therefore wins any file-path collision.
    // Persisted as a flat, newline-separated list of folder names (not the packs' own file
    // contents) since order needs to survive across imports/deletes independent of what's
    // actually installed at any given moment.
    fun installedPacks(context: Context): List<Pack> {
        val root = packsDir(context)
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        val byName = dirs.associateBy { it.name }
        val saved = orderFile(context).takeIf { it.isFile }?.readLines().orEmpty()
        val ordered = mutableListOf<File>()
        for (name in saved) {
            byName[name]?.let { ordered.add(it) }
        }
        val remaining = dirs.filter { it.name !in saved }.sortedBy { it.name.lowercase() }
        ordered.addAll(remaining)
        return ordered.map { Pack(it, enabled = !File(it, DISABLED_MARKER).exists(), meta = readMeta(it)) }
    }

    private fun readMeta(dir: File): Meta? =
        runCatching { Meta.fromJson(JSONObject(File(dir, META_FILE).readText())) }.getOrNull()

    /** Persists a new priority order - top of the list (index 0) wins, matching
     * [installedPacks]'s convention above. */
    fun setOrder(context: Context, orderedNames: List<String>) {
        packsDir(context).mkdirs()
        orderFile(context).writeText(orderedNames.joinToString("\n"))
    }

    /** Called from MainActivity right before super.onCreate(), same timing rule as
     * nativeSetRetroRewindRoot - RuntimeRiivolution::Overlays() caches this list on first use,
     * which happens later during DVD init, but never re-reads it after. */
    fun enabledOverlayRoots(context: Context): List<String> =
        installedPacks(context).filter { it.enabled }.map { it.dir.absolutePath }

    fun setEnabled(pack: Pack, enabled: Boolean) {
        val marker = File(pack.dir, DISABLED_MARKER)
        if (enabled) marker.delete() else marker.createNewFile()
    }

    fun delete(context: Context, pack: Pack) {
        pack.dir.deleteRecursively()
        setOrder(context, installedPacks(context).map { it.name })
    }

    /** GameBanana mod ids that are installed right now. */
    fun installedGameBananaIds(context: Context): Set<Int> =
        installedPacks(context).mapNotNull { it.meta?.gameBananaId }.toSet()

    // --- Install ---

    /**
     * Unpacks [archive] (.zip, .7z or .rar) into a new mod folder named after [meta]'s title and
     * puts it at the top of the list, so it wins over older mods that replace the same files.
     * Slow: call from a background thread. Throws with a readable message.
     */
    fun installArchive(context: Context, archive: File, meta: Meta, onFile: (Int) -> Unit = {}): Pack {
        val root = packsDir(context)
        root.mkdirs()
        // Unpacked next to the packs, not in cacheDir: a folder moved out of cacheDir keeps its
        // cache group, and Android would then count the installed mod as clearable cache.
        val scratchRoot = File(context.filesDir, ".mod_install")
        val scratch = File(scratchRoot, System.nanoTime().toString())
        scratch.deleteRecursively()
        try {
            ArchiveExtractor.extract(archive, scratch, onFile)
            // A GameBanana archive commonly wraps everything in one top-level folder named after
            // the mod - unwrap it so `dest` itself becomes the overlay root riivolution.cpp
            // expects (its riivolution/*.xml at the top). A loose-file mod works either way.
            val resolved = resolveOverlayRoot(scratch)
            val dest = uniqueDir(root, safeFolderName(meta.title))
            if (!resolved.renameTo(dest)) {
                resolved.copyRecursively(dest, overwrite = true)
            }
            File(dest, META_FILE).writeText(meta.toJson().toString())
            val order = installedPacks(context).map { it.name }.filter { it != dest.name }
            setOrder(context, listOf(dest.name) + order)
            return Pack(dest, enabled = true, meta = meta)
        } finally {
            scratch.deleteRecursively()
            scratchRoot.delete() // only goes when empty, so a parallel install's folder stays
        }
    }

    /** Installs a .zip/.7z/.rar the player picked with the system file picker. */
    fun importFile(context: Context, source: Uri, title: String, onFile: (Int) -> Unit = {}): Pack {
        val fileName = displayName(context, source) ?: "mod.zip"
        val copy = File(context.cacheDir, "mod_import_${System.nanoTime()}")
        try {
            val input = context.contentResolver.openInputStream(source) ?: throw IllegalStateException("couldn't open the picked file")
            input.use { stream -> copy.outputStream().use { stream.copyTo(it, 1 shl 16) } }
            val meta =
                Meta(
                    source = "file",
                    gameBananaId = null,
                    title = title.trim().ifEmpty { fileName.substringBeforeLast('.') },
                    author = null,
                    version = null,
                    thumbnailUrl = null,
                    fileName = fileName,
                    installedAt = System.currentTimeMillis(),
                )
            return installArchive(context, copy, meta, onFile)
        } finally {
            copy.delete()
        }
    }

    fun displayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()

    private fun safeFolderName(title: String): String =
        title.trim().replace(Regex("[^A-Za-z0-9 _().-]"), "_").trim('.', ' ').take(60).ifEmpty { "Mod" }

    private fun uniqueDir(root: File, name: String): File {
        var dest = File(root, name)
        var suffix = 2
        while (dest.exists()) {
            dest = File(root, "$name ($suffix)")
            suffix++
        }
        return dest
    }

    private fun resolveOverlayRoot(extracted: File): File {
        if (File(extracted, "riivolution").isDirectory) return extracted
        val children = extracted.listFiles() ?: return extracted
        val dirs = children.filter { it.isDirectory }
        if (dirs.size == 1 && children.size == 1) return dirs[0]
        if (dirs.size == 1 && File(dirs[0], "riivolution").isDirectory) return dirs[0]
        return extracted
    }
}
