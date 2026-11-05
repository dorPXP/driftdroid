package com.driftdroid.android.launcher

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.driftdroid.android.ModManager
import com.driftdroid.android.ModePickerActivity
import com.driftdroid.android.R
import com.driftdroid.android.RetroRewindInstallStatus
import com.driftdroid.android.SaveData
import com.driftdroid.android.T
import com.driftdroid.android.mii.Mii
import com.driftdroid.android.mii.MiiImages
import com.driftdroid.android.online.RetroWfc
import com.driftdroid.android.profile.SaveFile
import com.driftdroid.android.ui.AspectImageView
import com.driftdroid.android.ui.Icon
import com.driftdroid.android.ui.Lx

/** The first page: the start grid (the two games, ready to play or to install) and a glance trackside. */
internal class HomePage(host: ModePickerActivity) : Page(host) {
    override val title: String get() = T("Home")
    override val headerArt: Int get() = R.drawable.driftdroid_logo

    private lateinit var baseCard: GameCard
    private lateinit var retroCard: GameCard
    private lateinit var onlineTile: Tile
    private lateinit var racerTile: Tile
    private lateinit var modsTile: Tile
    private var lastRoomsFetch = 0L

    override fun build(): View {
        val column = Lx.vbox(context)
        column.setPadding(gutter, dp(4f), gutter, dp(28f))

        column.addView(Lx.section(context, "Start grid"), Lx.lp(context, bottom = 12f))
        baseCard = GameCard(R.drawable.channel_mkwii, T("Mario Kart Wii"))
        retroCard = GameCard(R.drawable.channel_retro_rewind, T("Retro Rewind"))
        if (compact) {
            column.addView(retroCard.root)
            column.addView(baseCard.root, Lx.lp(context, top = 12f))
        } else {
            val games = Lx.hbox(context, Gravity.TOP)
            games.addView(retroCard.root, Lx.lp(context, 0f, -2f, 1f, end = 8f))
            games.addView(baseCard.root, Lx.lp(context, 0f, -2f, 1f, start = 8f))
            column.addView(games)
        }

        column.addView(Lx.section(context, "Trackside"), Lx.lp(context, top = 28f, bottom = 12f))
        onlineTile = Tile(Icon.PUBLIC, T("Online now")) { host.navigate(Tab.ROOMS) }
        racerTile = Tile(Icon.FACE, T("Your racer")) { host.navigate(Tab.PROFILE) }
        modsTile = Tile(Icon.EXTENSION, T("Mods")) { host.navigate(Tab.MODS) }
        if (compact) {
            // One panel of rows on a phone; three tiles side by side when there's room.
            val panel = Lx.panel(context, 0f)
            panel.addView(onlineTile.root)
            panel.addView(Lx.divider(context, 70f))
            panel.addView(racerTile.root)
            panel.addView(Lx.divider(context, 70f))
            panel.addView(modsTile.root)
            column.addView(panel)
        } else {
            val tiles = Lx.hbox(context, Gravity.TOP)
            tiles.addView(onlineTile.root, Lx.lp(context, 0f, -1f, 1f, end = 6f))
            tiles.addView(racerTile.root, Lx.lp(context, 0f, -1f, 1f, start = 6f, end = 6f))
            tiles.addView(modsTile.root, Lx.lp(context, 0f, -1f, 1f, start = 6f))
            column.addView(tiles)
        }

        val scroll = ScrollView(context)
        scroll.isFillViewport = true
        scroll.addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return scroll
    }

    override fun onShow() {
        refresh()
        if (System.currentTimeMillis() - lastRoomsFetch > 60_000) fetchRooms()
    }

    override fun onDataChanged(what: Data) {
        if (what == Data.ROOMS) refreshOnline() else refresh()
    }

    private fun fetchRooms() {
        lastRoomsFetch = System.currentTimeMillis()
        host.work({ RetroWfc.fetchRooms() }) { result ->
            if (result.isSuccess) refreshOnline() else onlineTile.set("—", T("Retro WFC is unreachable right now"))
        }
    }

    fun refresh() {
        refreshGames()
        refreshOnline()
        refreshRacer()
        refreshMods()
    }

    private fun refreshGames() {
        val romReady = host.isRomImported()
        val busy = RetroRewindInstallStatus.running
        baseCard.set(
            ready = romReady,
            status = if (romReady) T("Ready") else T("Needs your disc image (.iso or .wbfs)"),
            primaryLabel = if (romReady) "Play" else "Install with ISO/WBFS",
            primaryIcon = if (romReady) Icon.PLAY else Icon.FOLDER,
            primaryEnabled = !busy,
            onPrimary = { host.playBase() },
            menu = null,
        )

        val retroInstalled = host.isRetroRewindInstalled()
        val notInPack = host.packWithoutRetroRewind()
        val version = host.retroRewindVersion()
        val playable = retroInstalled && !notInPack
        val status =
            when {
                busy -> RetroRewindInstallStatus.label.ifBlank { T("Installing...") }
                notInPack -> T("Not in your game pack")
                retroInstalled -> if (version != null) T("Ready · version %s").format(version) else T("Ready")
                else -> T("300+ tracks, free. About 1.9 GB")
            }
        retroCard.set(
            ready = playable && !busy,
            status = status,
            primaryLabel = if (playable) "Play" else "Install",
            primaryIcon = if (playable) Icon.PLAY else Icon.DOWNLOAD,
            primaryEnabled = !busy,
            onPrimary = { host.playRetroRewind() },
            menu = if (retroInstalled && !busy) ({ host.showRetroRewindMenu() }) else null,
        )
    }

    private fun refreshOnline() {
        val rooms = RetroWfc.lastRooms
        if (rooms == null) {
            onlineTile.set("…", T("Checking Retro WFC"))
            return
        }
        onlineTile.set(Lx.number(rooms.totalPlayers), T("racers in %s rooms").format(rooms.activeRooms.size))
    }

    private fun refreshRacer() {
        host.work({
            val save = SaveFile.load(context, SaveData.Game.RetroRewind) ?: SaveFile.load(context, SaveData.Game.MarioKartWii)
            save?.licenses?.firstOrNull()
        }) { result ->
            val license = result.getOrNull()
            if (license == null) {
                racerTile.set(T("No license"), T("Play once to make one"))
                racerTile.showMii(null)
            } else {
                racerTile.set(license.displayName, if (license.isOnline) T("%s VR").format(Lx.number(license.vr)) else T("Offline license"))
                racerTile.showMii(license.mii)
            }
        }
    }

    private fun refreshMods() {
        val packs = ModManager.installedPacks(context)
        val on = packs.count { it.enabled }
        modsTile.set(
            if (packs.isEmpty()) "0" else Lx.number(on),
            if (packs.isEmpty()) T("Browse GameBanana") else T("on, of %s installed").format(packs.size),
        )
    }

    /** A game: its banner, a dot for its state, and the one thing to do next on a slanted plate. */
    private inner class GameCard(art: Int, name: String) {
        val root: LinearLayout = Lx.vbox(context)
        private val banner = AspectImageView(context, 1120f / 256f)
        private val statusRow = Lx.status(context, "", Lx.MUTED)
        private val actions = Lx.hbox(context)

        init {
            root.background = Lx.rounded(context, Lx.SURFACE, 8f)
            Lx.clipRounded(root, 8f)
            banner.scaleType = ImageView.ScaleType.FIT_XY
            banner.setImageResource(art)
            root.addView(banner)
            val body = Lx.vbox(context)
            body.setPadding(dp(16f), dp(14f), dp(16f), dp(16f))
            body.addView(Lx.display(context, name, 21f))
            body.addView(statusRow, Lx.lp(context, top = 6f))
            body.addView(actions, Lx.lp(context, top = 14f))
            root.addView(body)
        }

        fun set(
            ready: Boolean,
            status: String,
            primaryLabel: String,
            primaryIcon: Icon,
            primaryEnabled: Boolean,
            onPrimary: () -> Unit,
            menu: (() -> Unit)?,
        ) {
            // Not playable yet: the banner fades to grey rather than hiding behind a black veil.
            banner.colorFilter = if (ready) null else ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.15f) })
            banner.alpha = if (ready) 1f else 0.55f
            statusRow.getChildAt(0).background = Lx.rounded(context, if (ready) Lx.ACCENT else Lx.AMBER, 4f)
            (statusRow.getChildAt(1) as TextView).apply {
                text = status
                setTextColor(if (ready) Lx.TEXT else Lx.MUTED)
            }
            actions.removeAllViews()
            val tone = if (primaryIcon == Icon.PLAY) Lx.Tone.PRIMARY else Lx.Tone.SECONDARY
            val primary = Lx.button(context, primaryLabel, tone, primaryIcon) { onPrimary() }
            Lx.setEnabled(primary, primaryEnabled)
            actions.addView(primary, Lx.lp(context, 0f, -2f, 1f))
            if (menu != null) {
                actions.addView(Lx.iconButton(context, Icon.MORE_VERT, "More", Lx.TEXT, Lx.SURFACE_HI, 46f) { menu() }, Lx.lp(context, 46f, 46f, start = 8f))
            }
        }
    }

    /** A glance at another page - a row on a phone, a tile beside its neighbours otherwise. Tapping goes there. */
    private inner class Tile(icon: Icon, label: String, onClick: () -> Unit) {
        val root: LinearLayout
        private val value = Lx.display(context, "", if (compact) 21f else 26f)
        private val detail = Lx.caption(context, "", Lx.MUTED, 2)
        private val iconView = Lx.iconView(context, icon, Lx.MUTED, 24f)
        private val mii = ImageView(context)

        init {
            mii.visibility = View.GONE
            if (compact) {
                root = Lx.hbox(context)
                root.background = Lx.pressable(context, android.graphics.Color.TRANSPARENT, 8f)
                root.setPadding(dp(16f), dp(12f), dp(12f), dp(12f))
                val lead = FrameLayout(context)
                lead.addView(iconView, FrameLayout.LayoutParams(dp(24f), dp(24f), Gravity.CENTER))
                lead.addView(mii, FrameLayout.LayoutParams(dp(42f), dp(42f), Gravity.CENTER))
                root.addView(lead, Lx.lp(context, 42f, 42f))
                val column = Lx.vbox(context)
                column.addView(Lx.label(context, label))
                val line = Lx.hbox(context, Gravity.BOTTOM)
                line.addView(value)
                line.addView(detail, Lx.lp(context, 0f, -2f, 1f, start = 8f, bottom = 2f))
                column.addView(line, Lx.lp(context, top = 4f))
                root.addView(column, Lx.lp(context, 0f, -2f, 1f, start = 12f, end = 8f))
                root.addView(Lx.iconView(context, Icon.CHEVRON, Lx.FAINT, 20f))
            } else {
                root = Lx.vbox(context)
                root.background = Lx.pressable(context, Lx.SURFACE, 8f)
                root.setPadding(dp(16f), dp(14f), dp(16f), dp(16f))
                val top = Lx.hbox(context)
                top.addView(iconView)
                top.addView(mii, Lx.lp(context, 30f, 30f))
                top.addView(Lx.label(context, label), Lx.lp(context, 0f, -2f, 1f, start = 10f))
                top.addView(Lx.iconView(context, Icon.CHEVRON, Lx.FAINT, 18f))
                root.addView(top)
                root.addView(value, Lx.lp(context, top = 14f))
                root.addView(detail, Lx.lp(context, top = 4f))
            }
            root.setOnClickListener { onClick() }
        }

        fun set(main: String, line: String) {
            value.text = main
            detail.text = line
        }

        fun showMii(m: Mii?) {
            if (m != null) {
                iconView.visibility = View.GONE
                mii.visibility = View.VISIBLE
                MiiImages.bind(mii, m)
            } else {
                iconView.visibility = View.VISIBLE
                mii.visibility = View.GONE
            }
        }
    }
}
