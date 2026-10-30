package com.driftdroid.android

import android.app.Activity
import com.driftdroid.android.ui.DriftSheet
import com.driftdroid.android.ui.DriftUi
import java.io.File

/**
 * Renderer choice, shown from the launcher rather than the in-game sidebar: it only takes effect
 * when the runtime starts up, and a device that cannot start the game at all (no working Vulkan
 * driver - see GitHub issue #3) never reaches the in-game settings to change it.
 *
 * Written straight into Config.toml's [video] section, which the runtime reads at startup
 * (runtime/include/runtime_config.h, "graphics_api").
 */
object GraphicsSettings {
    private const val SECTION = "video"
    private const val KEY = "graphics_api"

    private data class Option(val value: String, val label: String, val description: String)

    private val options =
        listOf(
            Option(
                "auto",
                T("Automatic (recommended)"),
                T("Vulkan when the device supports it, OpenGL ES otherwise."),
            ),
            Option("vulkan", "Vulkan", T("Fastest on devices with a working Vulkan driver.")),
            Option(
                "opengles",
                "OpenGL ES",
                T("For devices whose Vulkan driver is missing or broken. Slower, but it runs."),
            ),
        )

    private fun configFile(activity: Activity) = File(File(activity.filesDir, "WiiCompiled"), "Config.toml")

    fun currentValue(activity: Activity): String {
        val file = configFile(activity)
        val line =
            runCatching {
                file.takeIf { it.isFile }?.readLines()?.firstOrNull { it.trimStart().startsWith(KEY) }
            }.getOrNull() ?: return "auto"
        val value = line.substringAfter('=').trim().trim('"')
        return options.firstOrNull { it.value == value }?.value ?: "auto"
    }

    /** Rewrites the key in place, or appends it under [video], leaving every other line alone. */
    private fun write(activity: Activity, value: String) {
        val file = configFile(activity)
        runCatching {
            file.parentFile?.mkdirs()
            val lines = if (file.isFile) file.readLines().toMutableList() else mutableListOf()
            val entry = "$KEY = \"$value\""
            val existing = lines.indexOfFirst { it.trimStart().startsWith(KEY) }
            if (existing >= 0) {
                lines[existing] = entry
            } else {
                val section = lines.indexOfFirst { it.trim() == "[$SECTION]" }
                if (section >= 0) {
                    lines.add(section + 1, entry)
                } else {
                    if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
                    lines.add("[$SECTION]")
                    lines.add(entry)
                }
            }
            file.writeText(lines.joinToString("\n") + "\n")
        }
    }

    /** One panel: the graphics choice (saved on tap), vertical mode and the GPU driver. Each
     * applies the next time the game starts. */
    fun show(activity: Activity) {
        val sheet = DriftSheet(activity, "Graphics", T("Changes apply the next time the game starts"))
        val current = currentValue(activity)
        sheet.section("Drawing")
        val rows = ArrayList<android.widget.LinearLayout>()
        options.forEachIndexed { index, option ->
            rows +=
                sheet.radio(option.label, option.description, option.value == current) {
                    write(activity, option.value)
                    rows.forEachIndexed { i, row -> sheet.setPicked(row, i == index) }
                }
        }
        sheet.section("More")
        sheet.toggle(
            "Vertical mode",
            "Experimental: play upright like Mario Kart Tour. The touch controls get their own layout.",
            VerticalMode.isEnabled(activity),
        ) { VerticalMode.setEnabled(activity, it) }
        sheet.action("GPU driver", "Snapdragon phones only") {
            sheet.dismiss()
            GpuDriverSettings.show(activity)
        }
        sheet.button("Done", DriftUi.Style.PRIMARY) { sheet.dismiss() }
        sheet.show()
    }
}
