package com.driftdroid.android.mods

import java.io.File
import java.nio.file.Files

/**
 * Mods for Retro Rewind the way Retro Rewind itself takes them, which is how the Wheel Wizard PC
 * tool installs them: every turned-on mod's files, flattened into RetroRewind6/Patches. Retro
 * Rewind's XML maps that folder onto the disc as /patches, and its own code (Pulsar, compiled into
 * the game here) applies what it finds there - including "loose archive overrides", files named
 * like `Name.race.szs` that are merged into the game's archive of that name rather than replacing
 * a whole file. GameBanana's Retro Rewind UI mods are built for exactly this and do nothing as a
 * plain file swap.
 *
 * Mirrors Wheel Wizard's ModsLaunchService: the top mod wins a clash of names, and archive
 * overrides carry their mod's place in the list as a numeric prefix so Pulsar can settle clashes
 * inside an archive. Files are hard-linked, not copied, so the folder costs no space, and only
 * files this app placed (listed in a manifest beside the folder) are ever removed from it.
 */
internal object RetroRewindPatches {
    private const val MANIFEST = ".driftdroid_patches.txt"

    /**
     * Makes [patchesDir] hold exactly the files of [packs] (turned-on mod folders, highest priority
     * first). Riivolution-shaped packs are skipped: their XML places their files itself. Returns
     * the names now in place.
     */
    fun sync(packs: List<File>, patchesDir: File): Set<String> {
        val wanted = LinkedHashMap<String, Pair<String, File>>() // lower-case name -> (name, source)
        // Lowest priority first, so a higher mod's file of the same name replaces it.
        for (index in packs.indices.reversed()) {
            val pack = packs[index]
            if (isRiivolutionPack(pack)) continue
            pack.walkTopDown()
                .onEnter { dir -> dir == pack || !(dir.name.startsWith(".") || dir.name.equals("__MACOSX", ignoreCase = true)) }
                .filter { it.isFile && !it.name.startsWith(".") }
                .forEach { file ->
                    val name = if (isArchiveOverride(file.name)) "$index.${stripPriority(file.name)}" else file.name
                    wanted[name.lowercase()] = name to file
                }
        }

        patchesDir.mkdirs()
        val manifest = File(patchesDir.parentFile, MANIFEST)
        val placedBefore = manifest.takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }.orEmpty()
        for (old in placedBefore) {
            if (old.lowercase() !in wanted) File(patchesDir, old).delete()
        }
        val placed = LinkedHashSet<String>()
        for ((name, source) in wanted.values) {
            val dest = File(patchesDir, name)
            if (!sameFile(source, dest)) {
                dest.delete()
                link(source, dest)
            }
            placed += name
        }
        manifest.writeText(placed.joinToString("\n"))
        return placed
    }

    /**
     * Gives every US-English UI archive in [dir] (`Race_U.szs`) a European-English twin
     * (`Race_E.szs`), unless the mod already has one: the app only plays the PAL disc, which
     * reads the _E files, and most GameBanana UI mods are made on the US version. Returns how many
     * twins were made.
     */
    fun addRegionTwins(dir: File): Int {
        var made = 0
        dir.walkTopDown()
            .filter { it.isFile && US_ENGLISH.matches(it.name) }
            .toList()
            .forEach { file ->
                val twin = File(file.parentFile, file.name.dropLast("_U.szs".length) + "_E.szs")
                val existing = file.parentFile?.listFiles()?.any { it.name.equals(twin.name, ignoreCase = true) } == true
                if (!existing) {
                    link(file, twin)
                    made++
                }
            }
        return made
    }

    /** `Name.race.szs`: a file Pulsar merges into an archive, recognisable by the tag before .szs. */
    fun isArchiveOverride(fileName: String): Boolean {
        if (!fileName.endsWith(".szs", ignoreCase = true)) return false
        val stem = fileName.dropLast(4)
        val tag = stem.lastIndexOf('.')
        return tag > 0 && tag + 1 < stem.length
    }

    /** "3.Name.race.szs" -> "Name.race.szs", so a re-numbered mod isn't numbered twice. */
    fun stripPriority(fileName: String): String {
        val digits = fileName.takeWhile { it.isDigit() }.length
        return if (digits > 0 && digits < fileName.length && fileName[digits] == '.') fileName.substring(digits + 1) else fileName
    }

    private fun isRiivolutionPack(pack: File): Boolean =
        File(pack, "riivolution").listFiles()?.any { it.isFile && it.name.endsWith(".xml", ignoreCase = true) } == true

    private fun sameFile(a: File, b: File): Boolean =
        b.exists() && runCatching { Files.isSameFile(a.toPath(), b.toPath()) }.getOrDefault(false)

    /** A hard link when the filesystem allows it (no extra space), a copy otherwise. */
    private fun link(source: File, dest: File) {
        try {
            Files.createLink(dest.toPath(), source.toPath())
        } catch (_: Exception) {
            source.copyTo(dest, overwrite = true)
        }
    }

    private val US_ENGLISH = Regex("^[^.]+_U\\.szs$", RegexOption.IGNORE_CASE)
}
