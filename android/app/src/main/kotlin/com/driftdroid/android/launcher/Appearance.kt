package com.driftdroid.android.launcher

import com.driftdroid.android.ModePickerActivity
import com.driftdroid.android.T
import com.driftdroid.android.ui.DriftSheet
import com.driftdroid.android.ui.DriftUi
import com.driftdroid.android.ui.Lx
import com.driftdroid.android.ui.Theme

/**
 * Settings > Appearance: a preset, custom colours (accent hue, backdrop hue and brightness, gradient
 * angle), and an optional background picture with a dim level. Presets and the picture apply at once;
 * the sliders apply with the Apply button, because the launcher is rebuilt to wear the new colours.
 */
internal object Appearance {
    private fun presetName(preset: Theme.Preset): String =
        when (preset.id) {
            "pit_lane" -> T("Pit lane")
            "midnight" -> T("Midnight")
            "sunset" -> T("Sunset")
            "forest" -> T("Forest")
            "grape" -> T("Grape")
            "graphite" -> T("Graphite")
            else -> preset.name
        }

    fun summary(): String =
        Theme.PRESETS.firstOrNull { it.id == Theme.presetId }?.let { presetName(it) } ?: T("Custom")

    fun show(host: ModePickerActivity) {
        val sheet = DriftSheet(host, T("Appearance"), T("Colours and background of this launcher"))

        sheet.section(T("Style"))
        for (preset in Theme.PRESETS) {
            sheet.radio(presetName(preset), null, Theme.presetId == preset.id) {
                sheet.dismiss()
                Theme.usePreset(host, preset)
                host.recreate()
            }
        }

        sheet.section(T("Custom colours"))
        var accentHue = Theme.hue(Theme.accentColor)
        var backdropHue = Theme.hue(Theme.top)
        var brightness = Theme.value(Theme.bottom).coerceIn(0.05f, 0.35f)
        var angle = Theme.angle
        sheet.slider(T("Accent colour"), 0f, 360f, accentHue, 72, { "%d°".format(it.toInt()) }) { accentHue = it }
        sheet.slider(T("Backdrop colour"), 0f, 360f, backdropHue, 72, { "%d°".format(it.toInt()) }) { backdropHue = it }
        sheet.slider(T("Backdrop brightness"), 0.05f, 0.35f, brightness, 30, { "%d%%".format((it * 100).toInt()) }) { brightness = it }
        sheet.slider(T("Gradient angle"), 0f, 360f, angle, 36, { "%d°".format(it.toInt()) }) { angle = it }

        sheet.section(T("Background picture"))
        sheet.action(T("Choose a picture"), T("Copied into the app and scaled to fit the screen")) {
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
            sheet.action(T("Remove the picture"), null) {
                sheet.dismiss()
                Theme.removeImage(host)
                host.recreate()
            }
        }
        var dim = Theme.dim
        sheet.slider(T("Dim the picture"), 0f, Theme.MAX_DIM, dim, 16, { "%d%%".format((it * 100).toInt()) }) { dim = it }

        sheet.button(T("Apply"), DriftUi.Style.PRIMARY) {
            sheet.dismiss()
            Theme.setDim(host, dim)
            Theme.useCustom(
                host,
                top = Theme.fromHsv(backdropHue, 0.6f, brightness * 0.6f),
                bottom = Theme.fromHsv(backdropHue, 0.6f, brightness),
                accent = Theme.fromHsv(accentHue, 0.65f, 0.95f),
                angle = angle,
            )
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
