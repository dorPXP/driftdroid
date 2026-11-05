package com.driftdroid.android

import com.driftdroid.android.mods.RiivolutionChoices
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiivolutionChoicesTest {
    private val xml =
        """
        <wiidisc version="1">
          <id game="RMC" />
          <options>
            <section name="Characters">
              <option name="Baby Rosalina">
                <choice name="Enabled"><patch id="rosalina" /></choice>
              </option>
              <option id="karts" name="Karts" default="2">
                <choice name="Blue"><patch id="blue" /></choice>
                <choice name="Red"><patch id="red" /></choice>
              </option>
              <option name="Nothing to pick" />
            </section>
          </options>
          <patch id="rosalina"><folder external="/Rosalina" /></patch>
        </wiidisc>
        """.trimIndent()

    @Test
    fun optionsAreTurnedOnAtTheirFirstChoiceOrTheirOwnDefault() {
        val choices = RiivolutionChoices.choicesFor(xml)
        // No id: Dolphin's key is the section's name followed by the option's name.
        assertEquals(1, choices["CharactersBaby Rosalina"])
        assertEquals(2, choices["karts"])
        assertFalse("an option without choices has nothing to turn on", choices.containsKey("CharactersNothing to pick"))
    }

    @Test
    fun configIsWrittenOnceInTheFormatTheLoaderReads() {
        val root = Files.createTempDirectory("riivo").toFile()
        try {
            File(root, "riivolution").mkdirs()
            File(root, "riivolution/mod.xml").writeText(xml)
            assertTrue(RiivolutionChoices.ensure(root))
            val config = RiivolutionChoices.configFile(root)
            assertEquals(File(root, "riivolution/config/RMCP.xml"), config)
            val text = config.readText()
            assertTrue(text.contains("<riivolution version=\"2\">"))
            assertTrue(text.contains("<option id=\"CharactersBaby Rosalina\" default=\"1\" />"))
            assertTrue(text.contains("<option id=\"karts\" default=\"2\" />"))

            // Choices already on disk (made elsewhere, or by an earlier launch) are never replaced.
            config.writeText("<riivolution version=\"2\"></riivolution>")
            assertFalse(RiivolutionChoices.ensure(root))
            assertEquals("<riivolution version=\"2\"></riivolution>", config.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aLooseFileModIsLeftAlone() {
        val root = Files.createTempDirectory("loose").toFile()
        try {
            File(root, "Race_E.szs").writeText("x")
            assertFalse(RiivolutionChoices.ensure(root))
            assertFalse(File(root, "riivolution").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
