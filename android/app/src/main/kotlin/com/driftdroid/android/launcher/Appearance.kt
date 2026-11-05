package com.driftdroid.android.launcher

import android.view.View
import com.driftdroid.android.ModePickerActivity
import com.driftdroid.android.T
import com.driftdroid.android.ui.ColorPickerSheet
import com.driftdroid.android.ui.DriftSheet
import com.driftdroid.android.ui.DriftUi
import com.driftdroid.android.ui.Look
import com.driftdroid.android.ui.Lx
import com.driftdroid.android.ui.Theme
import com.driftdroid.android.ui.Wallpaper

/**
 * Settings > Appearance. Top: ready-made themes (a look, a wallpaper and colours together). Below:
 * the parts on their own - the look (flat, glass, Frutiger Aero, skeuomorphic), the wallpaper (a
 * built-in one, or your own picture, positioned separately for upright and sideways), the colours
 * with a proper picker, and how it feels (panel transparency, corners, text size).
 *
 * Themes and the picture apply at once; the rest applies with Apply, because the launcher is
 * rebuilt to wear it.
 */
internal object Appearance {
    private fun presetName(preset: Theme.Preset): String =
        when (preset.id) {
            "pit_lane" -> T("Pit lane")
            "aqua_glass" -> T("Aqua glass")
            "frutiger" -> T("Frutiger Aero")
            "deep_glass" -> T("Deep sea")
            "aurora_glass" -> T("Aurora")
            "sunset_glass" -> T("Sunset glass")
            "bubble_pop" -> T("Bubble pop")
            "steel" -> T("Brushed steel")
            "leather" -> T("Leather")
            "midnight" -> T("Midnight")
            "sunset" -> T("Sunset")
            "forest" -> T("Forest")
            "grape" -> T("Grape")
            "graphite" -> T("Graphite")
            else -> preset.name
        }

    private fun lookName(look: Look): String =
        when (look) {
            Look.FLAT -> T("Flat")
            Look.GLASS -> T("Glass")
            Look.AERO -> T("Frutiger Aero")
            Look.SKEUO -> T("Skeuomorphic")
        }

    private fun wallpaperName(wallpaper: Wallpaper?): String =
        when (wallpaper) {
            null -> T("None")
            Wallpaper.AQUA -> T("Water")
            Wallpaper.BUBBLES -> T("Bubbles")
            Wallpaper.MEADOW -> T("Meadow")
            Wallpaper.SUNSET -> T("Sunset")
            Wallpaper.AURORA -> T("Aurora")
            Wallpaper.DEEP -> T("Deep sea")
        }

    fun summary(): String =
        Theme.PRESETS.firstOrNull { it.id == Theme.presetId }?.let { presetName(it) } ?: T("Custom")

    fun show(host: ModePickerActivity) {
        val sheet = DriftSheet(host, T("Appearance"), T("Looks, wallpapers and colours for this launcher"))

        sheet.section(T("Themes"))
        for (preset in Theme.PRESETS) {
            sheet.radio(presetName(preset), "${lookName(preset.look)} · ${wallpaperName(preset.wallpaper)}", Theme.presetId == preset.id) {
                sheet.dismiss()
                Theme.usePreset(host, preset)
                host.recreate()
            }
        }

        // Pending choices, applied together by the Apply button.
        var look = Theme.look
        var wallpaper = Theme.wallpaper
        var accent = Theme.accentColor
        var top = Theme.top
        var bottom = Theme.bottom
        var angle = Theme.angle
        var panelAlpha = Theme.panelAlpha
        var corner = Theme.cornerScale
        var textScale = Theme.textScale

        sheet.section(T("Look"))
        val looks = Look.values().toList()
        sheet.choice(T("Look"), T("How panels and buttons are drawn"), looks.map { lookName(it) }, looks.indexOf(look)) { look = looks[it] }

        sheet.section(T("Wallpaper"))
        val wallpapers = listOf<Wallpaper?>(null) + Wallpaper.values().toList()
        val wallpaperRows = ArrayList<android.widget.LinearLayout>()
        wallpapers.forEachIndexed { index, option ->
            wallpaperRows.add(
                sheet.radio(wallpaperName(option), if (option == null) T("Just the colours below") else null, option == wallpaper && !Theme.hasImage(host)) {
                    wallpaper = option
                    wallpaperRows.forEachIndexed { i, row -> sheet.setPicked(row, i == index) }
                    if (Theme.hasImage(host)) Theme.removeImage(host)
                },
            )
        }
        sheet.action(T("Use my own picture"), T("Copied into the app and scaled to fit the screen")) {
            sheet.dismiss()
            host.openDocument { uri ->
                if (uri == null) return@openDocument
                host.work({ Theme.importImage(host, uri) }) { result ->
                    if (result.isSuccess) {
                        host.recreate()
                    } else {
                        host.toast(T("Couldn't use that picture: %s").format(result.exceptionOrNull()?.message), Lx.Tone.DANGER)
                    }
                }
            }
        }
        if (Theme.hasImage(host)) {
            sheet.action(T("Position the picture"), T("Set how it sits when the phone is upright and sideways")) {
                sheet.dismiss()
                BackgroundFraming.show(host)
            }
            sheet.action(T("Remove the picture"), null) {
                sheet.dismiss()
                Theme.removeImage(host)
                host.recreate()
            }
            var dim = Theme.dim
            sheet.slider(T("Dim the picture"), 0f, Theme.MAX_DIM, dim, 16, { "%d%%".format((it * 100).toInt()) }) {
                dim = it
                Theme.setDim(host, dim)
            }
        }

        sheet.section(T("Colours"))
        fun swatch(color: Int): View = View(host).apply {
            background = Lx.rounded(host, color, 8f, 0x66FFFFFF, 1.5f)
            layoutParams = Lx.lp(host, 36f, 28f)
        }
        lateinit var accentRow: android.widget.LinearLayout
        lateinit var topRow: android.widget.LinearLayout
        lateinit var bottomRow: android.widget.LinearLayout
        fun pick(title: String, current: Int, row: android.widget.LinearLayout, set: (Int) -> Unit) {
            ColorPickerSheet.show(host, title, current) { color ->
                set(color)
                (row.getChildAt(1) as? View)?.background = Lx.rounded(host, color, 8f, 0x66FFFFFF, 1.5f)
            }
        }
        accentRow = sheet.action(T("Accent colour"), T("Buttons, selected tabs and switches"), swatch(accent)) {
            pick(T("Accent colour"), accent, accentRow) { accent = it }
        }
        topRow = sheet.action(T("Backdrop, top"), null, swatch(top)) { pick(T("Backdrop, top"), top, topRow) { top = it } }
        bottomRow = sheet.action(T("Backdrop, bottom"), null, swatch(bottom)) { pick(T("Backdrop, bottom"), bottom, bottomRow) { bottom = it } }
        val directions = listOf(90f to T("Top to bottom"), 0f to T("Left to right"), 45f to T("Diagonal ↘"), 135f to T("Diagonal ↙"))
        sheet.choice(T("Gradient direction"), null, directions.map { it.second }, directions.indexOfFirst { it.first == angle }.coerceAtLeast(0)) { angle = directions[it].first }

        sheet.section(T("Feel"))
        sheet.slider(T("Panel transparency"), 0.12f, 0.85f, panelAlpha, 73, { "%d%%".format((it * 100).toInt()) }) { panelAlpha = it }
        sheet.slider(T("Corner roundness"), 0.5f, 1.8f, corner, 26, { "%d%%".format((it * 100).toInt()) }) { corner = it }
        val sizes = listOf(0.9f to T("Small"), 1f to T("Normal"), 1.15f to T("Large"), 1.3f to T("Larger"))
        sheet.choice(T("Text size"), null, sizes.map { it.second }, sizes.indexOfFirst { it.first >= textScale - 0.01f }.coerceAtLeast(1)) { textScale = sizes[it].first }

        sheet.button(T("Apply"), DriftUi.Style.PRIMARY) {
            sheet.dismiss()
            Theme.setFeel(host, panelAlpha, corner, textScale)
            Theme.useCustom(host, top = top, bottom = bottom, accent = accent, angle = angle, look = look, wallpaper = wallpaper)
            host.recreate()
        }
        sheet.button(T("Reset to the original look"), DriftUi.Style.SECONDARY) {
            sheet.dismiss()
            Theme.reset(host)
            host.recreate()
        }
        sheet.show()
    }
}
