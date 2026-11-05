package com.driftdroid.android.launcher

import android.view.View
import com.driftdroid.android.ModePickerActivity
import com.driftdroid.android.T
import com.driftdroid.android.ui.BackgroundMedia
import com.driftdroid.android.ui.ColorPickerSheet
import com.driftdroid.android.ui.DriftSheet
import com.driftdroid.android.ui.DriftUi
import com.driftdroid.android.ui.Look
import com.driftdroid.android.ui.Lx
import com.driftdroid.android.ui.Theme
import com.driftdroid.android.ui.Texture
import com.driftdroid.android.ui.ThemeStore

/**
 * Settings > Appearance. Top: ready-made themes (a look and colours together). Below:
 * the parts on their own - the look (flat, glass, Frutiger Aero, skeuomorphic), the material (a
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

    /** Short names for the pills (the long ones do not fit four to a row). */
    private fun lookPill(look: Look): String =
        when (look) {
            Look.FLAT -> T("Flat")
            Look.GLASS -> T("Glass")
            Look.AERO -> T("Aero")
            Look.SKEUO -> T("Skeuo")
        }

    private fun textureName(texture: Texture): String =
        when (texture) {
            Texture.NONE -> T("Smooth")
            Texture.METAL -> T("Metal")
            Texture.LEATHER -> T("Leather")
        }

    fun summary(): String =
        Theme.PRESETS.firstOrNull { it.id == Theme.presetId }?.let { presetName(it) } ?: T("Custom")

    fun show(host: ModePickerActivity) {
        val sheet = DriftSheet(host, T("Appearance"), T("Looks, colours and backgrounds for this launcher"))

        // Pending choices, applied together by the Apply button.
        var look = Theme.look
        var texture = Theme.texture
        var accent = Theme.accentColor
        var top = Theme.top
        var bottom = Theme.bottom
        var angle = Theme.angle
        var panelAlpha = Theme.panelAlpha
        var corner = Theme.cornerScale
        var textScale = Theme.textScale

        sheet.section(T("Themes"))
        val themeCards =
            Theme.PRESETS.map { preset ->
                val card = ThemePreview(host, preset.look, preset.top, preset.bottom, preset.accent, preset.texture)
                card.picked = Theme.presetId == preset.id
                card.setOnClickListener {
                    sheet.dismiss()
                    Theme.usePreset(host, preset)
                    host.recreate()
                }
                card to presetName(preset)
            }
        sheet.custom(strip(host, themeCards))

        sheet.section(T("My themes"))
        val mine = ThemeStore.list(host)
        if (mine.isNotEmpty()) {
            val cards =
                mine.map { saved ->
                    val thumb = if (saved.media != null) com.driftdroid.android.ui.BackgroundMedia.thumbnail(ThemeStore.mediaFile(host, saved), saved.mediaKind) else null
                    val card = ThemePreview(host, saved.look, saved.top, saved.bottom, saved.accent, saved.texture, backdrop = thumb)
                    card.setOnClickListener {
                        sheet.dismiss()
                        ThemeStore.apply(host, saved)
                        host.recreate()
                    }
                    card.setOnLongClickListener {
                        sheet.dismiss()
                        themeMenu(host, saved)
                        true
                    }
                    card to saved.name
                }
            sheet.custom(strip(host, cards))
            sheet.note(T("Your themes keep their own background. Touch and hold one to export or delete it. The ready-made themes only change colours and look."))
        }
        sheet.action(T("Save this look as a theme"), T("Name it and keep it with your themes")) {
            sheet.dismiss()
            askName(host) { name ->
                host.work({ ThemeStore.save(host, name, look, texture, top, bottom, accent, angle, panelAlpha, corner) }) { result ->
                    if (result.isSuccess) host.toast((if (result.getOrNull()?.media != null) T("Saved \"%s\" with its background.") else T("Saved \"%s\".")).format(result.getOrNull()?.name), Lx.Tone.PRIMARY) else host.toast(T("Couldn't save: %s").format(result.exceptionOrNull()?.message), Lx.Tone.DANGER)
                    show(host)
                }
            }
        }
        sheet.action(T("Import a theme file"), T("A .driftdroid-theme.json file from someone, or from another phone")) {
            sheet.dismiss()
            host.openDocument { uri ->
                if (uri == null) return@openDocument
                host.work({
                    val text = host.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: throw java.io.IOException("couldn't open the file")
                    ThemeStore.import(host, text)
                }) { result ->
                    if (result.isSuccess) host.toast(T("Imported \"%s\".").format(result.getOrNull()?.name), Lx.Tone.PRIMARY) else host.toast(T("Couldn't import: %s").format(result.exceptionOrNull()?.message), Lx.Tone.DANGER)
                    show(host)
                }
            }
        }

        sheet.section(T("Look"))
        val looks = Look.values().toList()
        sheet.choice(T("Look"), T("How panels and buttons are drawn"), looks.map { lookPill(it) }, looks.indexOf(look)) { look = looks[it] }

        val textures = Texture.values().toList()
        sheet.choice(T("Material"), T("For the skeuomorphic look"), textures.map { textureName(it) }, textures.indexOf(texture)) { texture = textures[it] }

        sheet.section(T("Background"))
        sheet.action(T("Choose a picture, GIF or video"), T("Copied into the app. GIF and MP4 play muted and loop")) {
            sheet.dismiss()
            host.openDocument { uri ->
                if (uri == null) return@openDocument
                host.work({ BackgroundMedia.import(host, uri) }) { result ->
                    if (result.isSuccess) {
                        host.recreate()
                    } else {
                        host.toast(T("Couldn't use that file: %s").format(result.exceptionOrNull()?.message), Lx.Tone.DANGER)
                    }
                }
            }
        }
        if (BackgroundMedia.hasAny(host)) {
            sheet.action(T("Position the background"), T("Set how it sits when the phone is upright and sideways")) {
                sheet.dismiss()
                BackgroundFraming.show(host)
            }
            sheet.action(T("Remove the background"), null) {
                sheet.dismiss()
                BackgroundMedia.remove(host)
                host.recreate()
            }
            var dim = Theme.dim
            sheet.slider(T("Dim the background"), 0f, Theme.MAX_DIM, dim, 16, { "%d%%".format((it * 100).toInt()) }) {
                dim = it
                Theme.setDim(host, dim)
            }
            sheet.toggle(T("Animate the background"), T("Off, or in battery saver, shows the first frame"), BackgroundMedia.animateEnabled(host)) {
                BackgroundMedia.setAnimateEnabled(host, it)
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
        val directions = listOf(90f to "↓", 0f to "→", 45f to "↘", 135f to "↙")
        sheet.choice(T("Gradient direction"), T("Which way the backdrop colours run"), directions.map { it.second }, directions.indexOfFirst { it.first == angle }.coerceAtLeast(0)) { angle = directions[it].first }

        sheet.section(T("Feel"))
        sheet.slider(T("Panel transparency"), 0.12f, 0.85f, panelAlpha, 73, { "%d%%".format((it * 100).toInt()) }) { panelAlpha = it }
        sheet.slider(T("Corner roundness"), 0.5f, 1.8f, corner, 26, { "%d%%".format((it * 100).toInt()) }) { corner = it }
        val sizes = listOf(0.9f to T("Small"), 1f to T("Normal"), 1.15f to T("Large"), 1.3f to T("Larger"))
        sheet.choice(T("Text size"), null, sizes.map { it.second }, sizes.indexOfFirst { it.first >= textScale - 0.01f }.coerceAtLeast(1)) { textScale = sizes[it].first }

        sheet.button(T("Apply"), DriftUi.Style.PRIMARY) {
            sheet.dismiss()
            Theme.setFeel(host, panelAlpha, corner, textScale)
            Theme.useCustom(host, top = top, bottom = bottom, accent = accent, angle = angle, look = look, texture = texture)
            host.recreate()
        }
        sheet.button(T("Reset"), DriftUi.Style.SECONDARY) {
            sheet.dismiss()
            Theme.reset(host)
            host.recreate()
        }
        sheet.show()
    }

    /** A swipeable row of preview cards, each with its name underneath. */
    private fun strip(host: ModePickerActivity, cards: List<Pair<View, String>>): View {
        val row = Lx.hbox(host, android.view.Gravity.TOP)
        for ((card, name) in cards) {
            val column = Lx.vbox(host)
            column.addView(card, Lx.lp(host, 96f, 156f))
            column.addView(
                Lx.caption(host, name, Lx.MUTED, 1).apply { gravity = android.view.Gravity.CENTER; textSize = 12f },
                Lx.lp(host, 96f, -2f, top = 6f),
            )
            row.addView(column, Lx.lp(host, -2f, -2f, end = 10f))
        }
        return android.widget.HorizontalScrollView(host).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, Lx.dp(host, 4f), 0, Lx.dp(host, 4f))
            addView(row)
        }
    }

    private fun askName(host: ModePickerActivity, onName: (String) -> Unit) {
        val sheet = DriftSheet(host, T("Name your theme"))
        val field = sheet.textField("Theme name", "")
        sheet.button(T("Save"), DriftUi.Style.PRIMARY) {
            sheet.dismiss()
            onName(field.text.toString())
        }
        sheet.button(T("Cancel"), DriftUi.Style.SECONDARY) {
            sheet.dismiss()
            show(host)
        }
        sheet.show()
    }

    private fun themeMenu(host: ModePickerActivity, saved: ThemeStore.Saved) {
        val menu = DriftSheet(host, saved.name)
        menu.action(T("Export to a file"), if (ThemeStore.exportIncludesPicture(host, saved)) T("Includes the background picture") else if (saved.media == "anim" || saved.media == "video") T("GIFs and videos stay on this phone") else null) {
            menu.dismiss()
            val safe = saved.name.map { if (it.isLetterOrDigit() || it == ' ' || it == '-') it else '_' }.joinToString("").trim().ifEmpty { "theme" }
            host.createDocument("application/json", "$safe.driftdroid-theme.json") { uri ->
                if (uri == null) {
                    show(host)
                    return@createDocument
                }
                host.work({
                    val text = ThemeStore.export(host, saved)
                    host.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: throw java.io.IOException("couldn't open the destination")
                }) { result ->
                    host.toast(if (result.isSuccess) T("Theme exported.") else T("Export failed: %s").format(result.exceptionOrNull()?.message), if (result.isSuccess) Lx.Tone.PRIMARY else Lx.Tone.DANGER)
                    show(host)
                }
            }
        }
        menu.action(T("Delete"), null) {
            menu.dismiss()
            DriftSheet.confirm(host, T("Delete \"%s\"?").format(saved.name), T("This removes it from your themes. Your current look doesn't change."), T("Delete"), danger = true) {
                runCatching { ThemeStore.delete(host, saved.id) }
                show(host)
            }
        }
        menu.button(T("Cancel"), DriftUi.Style.SECONDARY) {
            menu.dismiss()
            show(host)
        }
        menu.show()
    }
}
