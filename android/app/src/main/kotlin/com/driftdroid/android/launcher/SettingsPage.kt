package com.driftdroid.android.launcher

import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import com.driftdroid.android.GraphicsSettings
import com.driftdroid.android.IconSettings
import com.driftdroid.android.LanguageSettings
import com.driftdroid.android.LauncherOrientation
import com.driftdroid.android.ModePickerActivity
import com.driftdroid.android.PrivateServerSettings
import com.driftdroid.android.R
import com.driftdroid.android.SaveData
import com.driftdroid.android.T
import com.driftdroid.android.mii.MiiImages
import com.driftdroid.android.ui.Icon
import com.driftdroid.android.ui.Lx
import com.driftdroid.android.ui.Switch

/** Settings, in groups: the game, this launcher, and where things come from. */
internal class SettingsPage(host: ModePickerActivity) : Page(host) {
    override val title: String get() = T("Settings")
    override val subtitle: String get() = "${host.getString(R.string.app_name)} ${host.appVersionName()}"

    override fun build(): View {
        val game = group("Game") {
            row(Icon.TUNE, "Graphics", "Renderer, vertical mode and GPU driver") { GraphicsSettings.show(host) }
            row(Icon.TRANSLATE, "Language", "For these screens and the game's menus") { LanguageSettings.show(host) }
            row(Icon.SAVE, "Save data", "Import or export your licenses, unlocks and ghosts") { SaveData.showDialog(host) }
            row(Icon.FOLDER, "Import game pack", "Game code made by DriftDroid Builder on a PC") { host.importGamePack() }
            row(Icon.SERVER, "Experimental server", "Only for people who run their own server") { PrivateServerSettings.show(host) }
        }
        val launcher = group("Launcher") {
            row(Icon.SCREEN_ROTATION, "Launcher orientation", orientationDetail()) { LauncherOrientation.show(host) }
            row(Icon.IMAGE, "Appearance", Appearance.summary()) { Appearance.show(host) }
            val switch = Switch(context)
            switch.isOn = MiiImages.onlineEnabled(context)
            switch.layoutParams = Lx.switchLp(context)
            add(
                Lx.settingsRow(context, Icon.FACE, "Mii pictures", "Draw Miis with Nintendo's online Mii renderer. Off: simple offline drawings", Lx.MUTED, switch) {
                    switch.isOn = !switch.isOn
                    MiiImages.setOnlineEnabled(context, switch.isOn)
                    host.broadcast(Data.MIIS)
                },
            )
            row(Icon.IMAGE, "App icon", "Put a shortcut with your own picture on the home screen") { IconSettings.show(host) }
            row(Icon.BUG, "Export diagnostics", "Logs to attach to a bug report - review before sharing") { host.exportDiagnostics() }
        }
        val about = group("About") {
            row(Icon.OPEN_IN_NEW, "DriftDroid on GitHub", "Source code, bug reports and DriftDroid Builder") { open("https://github.com/dorPXP/driftdroid") }
            row(Icon.PUBLIC, "Retro WFC", "The online service behind Rooms and Ranks") { open("https://rwfc.net") }
            row(Icon.EXTENSION, "GameBanana", "Where the mods in the Mods browser come from") { open("https://gamebanana.com/games/5896") }
        }
        val note =
            Lx.caption(
                context,
                T("Online pages use Retro WFC's and GameBanana's public APIs, the same ones the Wheel Wizard tool uses on PC. No Nintendo code or game data is included. Mii pictures come from Nintendo's public Mii service only if you turn them on, and the mods are third-party files this project doesn't host."),
                Lx.FAINT,
                5,
            ).apply { setLineSpacing(0f, 1.2f) }

        val content: LinearLayout
        if (compact) {
            content = Lx.vbox(context)
            content.addView(game)
            content.addView(launcher)
            content.addView(about)
            content.addView(note, Lx.lp(context, start = 4f, top = 4f, end = 4f))
        } else {
            content = Lx.hbox(context, Gravity.TOP)
            val left = Lx.vbox(context)
            left.addView(game)
            left.addView(launcher)
            val right = Lx.vbox(context)
            right.addView(about)
            right.addView(note, Lx.lp(context, start = 4f, top = 4f, end = 4f))
            content.addView(left, Lx.lp(context, 0f, -2f, 1f, end = 10f))
            content.addView(right, Lx.lp(context, 0f, -2f, 1f, start = 10f))
        }
        content.setPadding(gutter, dp(4f), gutter, dp(28f))
        val scroll = ScrollView(context)
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return scroll
    }

    private fun orientationDetail(): String {
        val mode = LauncherOrientation.mode(context)
        return if (mode == LauncherOrientation.Mode.AUTO) T("Automatic: portrait on phones, landscape on tablets and handhelds") else T(mode.label)
    }

    private fun open(url: String) {
        runCatching { host.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    /** A labelled group: rows on one panel, hairlines between them. */
    private fun group(label: String, fill: Group.() -> Unit): View {
        val box = Lx.vbox(context)
        box.addView(Lx.section(context, label), Lx.lp(context, bottom = 10f))
        val panel = Lx.panel(context, 0f)
        Group(panel).fill()
        box.addView(panel)
        box.layoutParams = Lx.lp(context, bottom = 22f)
        return box
    }

    private inner class Group(private val panel: LinearLayout) {
        fun add(row: View) {
            if (panel.childCount > 0) panel.addView(Lx.divider(context, 54f))
            panel.addView(row)
        }

        fun row(icon: Icon, title: String, detail: String, onClick: () -> Unit) = add(Lx.settingsRow(context, icon, title, detail, Lx.MUTED, null, onClick))
    }
}
