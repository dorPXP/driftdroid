package com.driftdroid.android.mods

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Riivolution-shaped mods (XML files in a riivolution folder, next to their replacement files) describe what they
 * change as options, and Riivolution starts every option on choice 0, "Disabled", unless the XML
 * says otherwise - the player turns them on in Riivolution's menu on a Wii, or in Dolphin. The
 * game's loader here (runtime/src/hle/storage/riivolution.cpp) has no such menu: it reads the
 * remembered choices from riivolution/config/<game>.xml in the mod's folder, the same file Dolphin
 * and the Wii keep them in. Without that file an installed pack applies nothing at all.
 *
 * So each pack gets that file, with every option switched on at its first choice (or at the XML's
 * own default when it names one). A file that is already there - choices someone made elsewhere -
 * is left alone.
 */
internal object RiivolutionChoices {
    /** Only PAL Mario Kart Wii (RMCP01) discs are accepted, so only its config is read. */
    private const val GAME_ID4 = "RMCP"

    fun configFile(root: File): File = File(root, "riivolution/config/$GAME_ID4.xml")

    /** Writes the config for the pack at [root] if it has Riivolution XMLs and no config yet. True when written. */
    fun ensure(root: File): Boolean {
        val xmls =
            File(root, "riivolution").listFiles { f -> f.isFile && f.name.endsWith(".xml", ignoreCase = true) }
                ?.sortedBy { it.name.lowercase() }
                .orEmpty()
        if (xmls.isEmpty()) return false
        val config = configFile(root)
        if (config.exists()) return false
        val choices = LinkedHashMap<String, Int>()
        for (xml in xmls) runCatching { choices.putAll(choicesFor(xml.readText())) }
        if (choices.isEmpty()) return false
        config.parentFile?.mkdirs()
        config.writeText(render(choices))
        return true
    }

    /**
     * Every option in one Riivolution XML, keyed the way the loader (and Dolphin) looks them up -
     * the option's id, or its section's name followed by its own name when it has none - mapped
     * to the choice to use: the XML's default if it picks one, else the first choice.
     */
    fun choicesFor(xml: String): Map<String, Int> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isExpandEntityReferences = false
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        val document = factory.newDocumentBuilder().parse(xml.byteInputStream())
        val result = LinkedHashMap<String, Int>()
        val sections = document.getElementsByTagName("section")
        for (i in 0 until sections.length) {
            val section = sections.item(i) as? Element ?: continue
            if ((section.parentNode as? Element)?.tagName != "options") continue
            val sectionName = section.getAttribute("name")
            for (option in section.childElements("option")) {
                val choiceCount = option.childElements("choice").size
                if (choiceCount == 0) continue
                val id = option.getAttribute("id").ifEmpty { sectionName + option.getAttribute("name") }
                if (id.isEmpty()) continue
                val default = option.getAttribute("default").toIntOrNull() ?: 0
                result[id] = if (default in 1..choiceCount) default else 1
            }
        }
        return result
    }

    fun render(choices: Map<String, Int>): String =
        buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<riivolution version=\"2\">\n")
            for ((id, choice) in choices) append("  <option id=\"").append(escape(id)).append("\" default=\"").append(choice).append("\" />\n")
            append("</riivolution>\n")
        }

    private fun Element.childElements(tag: String): List<Element> {
        val out = ArrayList<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && node.tagName == tag) out += node
        }
        return out
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
}
