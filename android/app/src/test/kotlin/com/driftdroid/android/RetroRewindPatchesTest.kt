package com.driftdroid.android

import com.driftdroid.android.mods.RetroRewindPatches
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetroRewindPatchesTest {
    private fun withTemp(block: (File) -> Unit) {
        val root = Files.createTempDirectory("rrpatches").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun file(dir: File, path: String, text: String = path): File =
        File(dir, path).apply {
            parentFile.mkdirs()
            writeText(text)
        }

    @Test
    fun archiveOverridesAreRecognisedTheWayWheelWizardDoes() {
        assertTrue(RetroRewindPatches.isArchiveOverride("SteamDeckButtonPrompts.race.szs"))
        assertFalse(RetroRewindPatches.isArchiveOverride("Race_U.szs"))
        assertFalse(RetroRewindPatches.isArchiveOverride(".race.szs"))
        assertFalse(RetroRewindPatches.isArchiveOverride("wr.brres"))
        assertEquals("Name.race.szs", RetroRewindPatches.stripPriority("12.Name.race.szs"))
        assertEquals("Name.race.szs", RetroRewindPatches.stripPriority("Name.race.szs"))
    }

    @Test
    fun enabledModsAreFlattenedTopMostWinningWithNumberedOverrides() =
        withTemp { root ->
            val top = File(root, "mods/Top")
            val lower = File(root, "mods/Lower")
            file(top, "race/la_kart-wr.szs", "top")
            file(top, "Prompts.race.szs", "top override")
            file(top, ".driftdroid_mod.json")
            file(lower, "deep/folder/la_kart-wr.szs", "lower")
            file(lower, "Prompts.race.szs", "lower override")
            file(lower, "__MACOSX/._junk.szs")
            val riivo = File(root, "mods/Riivo")
            file(riivo, "riivolution/pack.xml", "<wiidisc version=\"1\"/>")
            file(riivo, "Pack/la_kart-wr.szs", "riivolution")

            val patches = File(root, "RetroRewind6/Patches")
            val placed = RetroRewindPatches.sync(listOf(top, riivo, lower), patches)

            assertEquals(setOf("la_kart-wr.szs", "0.Prompts.race.szs", "2.Prompts.race.szs"), placed)
            // A plain file clash: the top mod's copy is the one in place.
            assertEquals("top", File(patches, "la_kart-wr.szs").readText())
            // Archive overrides from both mods stay, each numbered by its mod's place.
            assertEquals("top override", File(patches, "0.Prompts.race.szs").readText())
            assertEquals("lower override", File(patches, "2.Prompts.race.szs").readText())
        }

    @Test
    fun turningModsOffRemovesOnlyWhatTheAppPutThere() =
        withTemp { root ->
            val mod = File(root, "mods/Mod")
            file(mod, "Mod.race.szs")
            val patches = File(root, "RetroRewind6/Patches")
            file(patches, "SomeoneElses.szs", "keep me")
            RetroRewindPatches.sync(listOf(mod), patches)
            assertTrue(File(patches, "0.Mod.race.szs").isFile)

            RetroRewindPatches.sync(emptyList(), patches)
            assertFalse(File(patches, "0.Mod.race.szs").exists())
            assertEquals("keep me", File(patches, "SomeoneElses.szs").readText())
        }

    @Test
    fun usEnglishUiArchivesGetAEuropeanTwin() =
        withTemp { root ->
            file(root, "Race_U.szs", "us race")
            file(root, "MenuSingle_U.szs", "us menu")
            file(root, "MenuSingle_E.szs", "pal menu")
            file(root, "Prompts.race_u.szs", "override")

            assertEquals(1, RetroRewindPatches.addRegionTwins(root))
            assertEquals("us race", File(root, "Race_E.szs").readText())
            // The mod's own PAL file is never replaced, and archive overrides keep their names.
            assertEquals("pal menu", File(root, "MenuSingle_E.szs").readText())
            assertFalse(File(root, "Prompts.race_e.szs").exists())
            // Running again makes nothing new.
            assertEquals(0, RetroRewindPatches.addRegionTwins(root))
        }
}
