package com.driftdroid.android

import com.driftdroid.android.ui.Look
import com.driftdroid.android.ui.Texture
import com.driftdroid.android.ui.Theme
import com.driftdroid.android.ui.ThemeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class ThemeStoreTest {
    private fun theme(name: String = "Sky glass") =
        ThemeStore.Saved(
            "id-1", name, Look.AERO, Texture.METAL, 0xFF42B0F5.toInt(), 0xFFD4F3FF.toInt(), 0xFF1E8FF0.toInt(),
            45f, 0.3f, 1.2f, 0.35f, Theme.Framing(1.5f, 0.25f, 0.75f), Theme.Framing(0.8f, 0.5f, 0.5f),
        )

    @Test
    fun aThemeSurvivesAnExportAndAnImport() {
        val parsed = ThemeStore.parse(ThemeStore.exportText(theme())).theme
        assertEquals("Sky glass", parsed.name)
        assertEquals(Look.AERO, parsed.look)
        assertEquals(Texture.METAL, parsed.texture)
        assertEquals(0xFF42B0F5.toInt(), parsed.top)
        assertEquals(0xFF1E8FF0.toInt(), parsed.accent)
        assertEquals(45f, parsed.angle, 0.001f)
        assertEquals(0.3f, parsed.panelAlpha, 0.001f)
        assertEquals(1.5f, parsed.portrait.zoom, 0.001f)
        assertEquals(0.75f, parsed.portrait.cy, 0.001f)
        assertEquals(0.8f, parsed.landscape.zoom, 0.001f)
    }

    @Test
    fun anImportedThemeGetsAFreshId() {
        assertTrue(ThemeStore.parse(ThemeStore.exportText(theme())).theme.id != "id-1")
    }

    @Test
    fun theExportedFileCarriesNoIdAndNoFloatNoise() {
        val text = ThemeStore.exportText(theme())
        assertTrue(!text.contains("\"id\""))
        assertTrue(text.contains("\"panelAlpha\": 0.3"))
    }

    @Test
    fun outOfRangeValuesAreClamped() {
        val text = """{"format":"driftdroid-theme","name":"x","look":"glass","panelAlpha":9,"cornerScale":-4,"dim":5,"angle":999,
            "portrait":[99,5,-5],"landscape":[0.0,0.5,0.5]}"""
        val t = ThemeStore.parse(text).theme
        assertEquals(0.85f, t.panelAlpha, 0.001f)
        assertEquals(0.5f, t.cornerScale, 0.001f)
        assertEquals(Theme.MAX_DIM, t.dim, 0.001f)
        assertEquals(360f, t.angle, 0.001f)
        assertEquals(6f, t.portrait.zoom, 0.001f)
        assertEquals(1f, t.portrait.cx, 0.001f)
        assertEquals(0f, t.portrait.cy, 0.001f)
        assertEquals(0.2f, t.landscape.zoom, 0.001f)
    }

    @Test
    fun badColoursAndUnknownLooksFallBack() {
        val t = ThemeStore.parse("""{"format":"driftdroid-theme","name":"x","look":"nonsense","top":"red","accent":"#12"}""").theme
        assertEquals(Look.FLAT, t.look)
        assertEquals(0xFF0C0E11.toInt(), t.top)
        assertEquals(0xFF3DDC84.toInt(), t.accent)
    }

    @Test
    fun namesAreCleanedAndShortened() {
        val long = "A".repeat(100)
        assertEquals(40, ThemeStore.parse("""{"format":"driftdroid-theme","name":"$long"}""").theme.name.length)
        assertEquals("My theme", ThemeStore.parse("""{"format":"driftdroid-theme","name":"   "}""").theme.name)
        assertEquals("ab", ThemeStore.parse("{\"format\":\"driftdroid-theme\",\"name\":\"a\\u0007b\"}").theme.name)
    }

    @Test
    fun filesThatAreNotThemesAreRefused() {
        for (bad in listOf("", "not json", "[]", """{"name":"no format"}""", """{"format":"something-else"}""")) {
            try {
                ThemeStore.parse(bad)
                fail("accepted: $bad")
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("theme"))
            }
        }
    }

    @Test
    fun aHugeFileIsRefused() {
        try {
            ThemeStore.parse("""{"format":"driftdroid-theme","name":"${"x".repeat(7_000_000)}"}""")
            fail("accepted a huge file")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("too big"))
        }
    }

    @Test
    fun anEmbeddedPictureIsReportedNotDecodedHere() {
        val parsed = ThemeStore.parse("""{"format":"driftdroid-theme","name":"x","image":"AAAA"}""")
        assertEquals("AAAA", parsed.image)
    }
}
