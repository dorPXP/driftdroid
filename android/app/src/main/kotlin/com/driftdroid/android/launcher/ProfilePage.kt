package com.driftdroid.android.launcher

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.driftdroid.android.ModePickerActivity
import com.driftdroid.android.SaveData
import com.driftdroid.android.T
import com.driftdroid.android.mii.Mii
import com.driftdroid.android.mii.MiiDatabase
import com.driftdroid.android.mii.MiiImages
import com.driftdroid.android.mii.MiiStudio
import com.driftdroid.android.profile.SaveFile
import com.driftdroid.android.ui.DriftSheet
import com.driftdroid.android.ui.DriftUi
import com.driftdroid.android.ui.Icon
import com.driftdroid.android.ui.Lx
import com.driftdroid.android.ui.TabStrip
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The player's side of things: their licenses (Mii, friend code, VR/BR, friend list) from each
 * save, and every Mii in the Mii file with an editor - WheelWizard's profile and Mii pages.
 */
internal class ProfilePage(host: ModePickerActivity) : Page(host) {
    override val title: String get() = T("Profile")
    override val subtitle: String
        get() = if (showingMiis) T("The Miis your licenses race as") else T("Licenses, ratings and friends from your saves")

    private var showingMiis = false
    private var game = SaveData.Game.RetroRewind
    private var selectedLicense = 0
    private lateinit var tabs: TabStrip
    private lateinit var tools: LinearLayout
    private lateinit var body: FrameLayout
    private val gameChips = HashMap<SaveData.Game, TextView>()

    override fun build(): View {
        val root = Lx.vbox(context)
        tabs = TabStrip(context, listOf(T("Licenses"), T("Miis")), fill = compact) { showTab(it == 1) }
        root.addView(tabs.view, Lx.lp(context).apply { marginStart = if (compact) 0 else gutter - dp(10f) })
        root.addView(Lx.divider(context))
        // The tab's own tools: which game's licenses, or what to do with Miis.
        tools = Lx.hbox(context)
        root.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(gutter, dp(12f), gutter, 0)
            clipToPadding = false
            addView(tools)
        })
        if (host.isGameLoaded()) {
            val warn = Lx.hbox(context)
            warn.background = Lx.rounded(context, Lx.alpha(Lx.AMBER, 0.12f), 8f)
            warn.setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            warn.addView(Lx.iconView(context, Icon.WARNING, Lx.AMBER, 18f))
            warn.addView(
                Lx.caption(context, T("The game is still open in the background and may overwrite changes made here when it saves. Close DriftDroid from Recents and reopen it first to be safe."), Lx.TEXT, 4),
                Lx.lp(context, 0f, -2f, 1f, start = 12f),
            )
            root.addView(warn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = gutter
                marginEnd = gutter
                topMargin = dp(12f)
            })
        }
        body = FrameLayout(context)
        root.addView(body, Lx.lp(context, -1f, 0f, 1f, top = 12f))
        tabs.select(0)
        showTab(false)
        return root
    }

    override fun onShow() {
        reload()
        askAboutOnlinePictures()
    }

    /** Once: Mii pictures come from Nintendo's online renderer, so they stay off until the player agrees. */
    private fun askAboutOnlinePictures() {
        if (MiiImages.asked(context)) return
        MiiImages.markAsked(context)
        val sheet = DriftSheet(host, T("Show Mii pictures?"))
        sheet.note(
            T("Miis can be drawn by Nintendo's public Mii renderer. Turning this on sends each Mii's look (face, hair and colours, not names or friend codes) to studio.mii.nintendo.com. Off, simple pictures are drawn on your phone. You can change this in Settings."),
        )
        sheet.button(T("Turn on"), DriftUi.Style.PRIMARY) {
            MiiImages.setOnlineEnabled(context, true)
            sheet.dismiss()
            host.broadcast(Data.MIIS)
        }
        sheet.button(T("Keep off"), DriftUi.Style.SECONDARY) { sheet.dismiss() }
        sheet.show()
    }

    override fun onDataChanged(what: Data) {
        if (what == Data.MIIS || what == Data.SAVES) reload()
    }

    private fun showTab(miis: Boolean) {
        showingMiis = miis
        tools.removeAllViews()
        if (miis) {
            tools.addView(Lx.button(context, "New Mii", Lx.Tone.PRIMARY, Icon.ADD, compact = true) { host.open(MiiEditorPage(host, null, null)) }, Lx.lp(context, -2f, -2f, end = 8f))
            tools.addView(Lx.button(context, "Import", Lx.Tone.GHOST, Icon.UPLOAD, compact = true) { importMii() }, Lx.lp(context, -2f, -2f, end = 8f))
            tools.addView(Lx.button(context, "Export all", Lx.Tone.GHOST, Icon.DOWNLOAD, compact = true) { exportAll() })
        } else {
            for (g in listOf(SaveData.Game.RetroRewind, SaveData.Game.MarioKartWii)) {
                val chip = Lx.chip(context, g.label, g == game) { selectGame(g) }
                gameChips[g] = chip
                tools.addView(chip, Lx.lp(context, -2f, -2f, end = 8f))
            }
        }
        refreshHeader()
        reload()
    }

    private fun selectGame(g: SaveData.Game) {
        game = g
        selectedLicense = 0
        gameChips.forEach { (k, chip) -> Lx.paintChip(chip, k == g) }
        reload()
    }

    private fun reload() {
        if (!::body.isInitialized) return
        if (showingMiis) loadMiis() else loadLicenses()
    }

    private fun setBody(view: View) {
        body.removeAllViews()
        body.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // --- Licenses ---

    private fun loadLicenses() {
        val wanted = game
        host.work({ SaveFile.load(context, wanted) to MiiDatabase.load(context) }) { result ->
            if (showingMiis || game != wanted) return@work
            val (save, miis) = result.getOrNull() ?: (null to null)
            if (save == null || save.licenses.isEmpty()) {
                setBody(Lx.emptyState(context, Icon.FLAG, T("No %s save yet").format(wanted.label), "Play it once and make a license - it'll show up here with its friend code, ratings and friends."))
                return@work
            }
            selectedLicense = selectedLicense.coerceIn(0, save.licenses.size - 1)
            setBody(licensesView(save, miis))
        }
    }

    private fun licensesView(save: SaveFile.Save, miis: MiiDatabase.Snapshot?): View {
        val column = Lx.vbox(context)
        column.setPadding(0, 0, 0, dp(96f))
        val cards = Lx.hbox(context, Gravity.TOP)
        save.licenses.forEachIndexed { i, license ->
            cards.addView(licenseCard(license, i == selectedLicense) {
                selectedLicense = i
                setBody(licensesView(save, miis))
            }, Lx.lp(context, if (compact) 250f else 270f, -2f, end = 10f))
        }
        column.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(gutter, 0, gutter, 0)
            clipToPadding = false
            addView(cards)
        })

        val license = save.licenses[selectedLicense]
        val inner = Lx.vbox(context)
        inner.setPadding(gutter, 0, gutter, 0)
        val actions = Lx.hbox(context)
        if (license.isOnline) {
            actions.addView(Lx.button(context, "Copy friend code", Lx.Tone.GHOST, Icon.COPY, compact = true) { PlayerActions.copy(host, license.friendCode) }, Lx.lp(context, -2f, -2f, end = 8f))
        }
        actions.addView(Lx.button(context, "Change Mii", Lx.Tone.GHOST, Icon.FACE, compact = true) { changeMii(save.game, license, miis) })
        inner.addView(actions, Lx.lp(context, top = 14f))
        inner.addView(Lx.section(context, T("Friends · %s of 30").format(license.friends.size)), Lx.lp(context, top = 24f, bottom = 10f))

        if (license.friends.isEmpty()) {
            inner.addView(
                Lx.panel(context, 18f).apply {
                    addView(Lx.body(context, T("No friends on this license yet. Add people from Rooms or Ranks - they'll show as pending until they add you back.")))
                },
            )
        }
        for (friend in license.friends) {
            val row = PlayerRow(context)
            row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6f) }
            row.bind(friend.mii, friend.displayName, friend.friendCode)
            if (friend.pending) row.badges.addView(Lx.badge(context, "Pending", Lx.AMBER))
            row.setValue(T("%s VR").format(Lx.number(friend.vr)), Lx.AMBER, T("%s wins · %s losses").format(friend.wins, friend.losses))
            row.setOnClickListener { friendMenu(save.game, license, friend) }
            inner.addView(row)
        }
        column.addView(inner)
        val scroll = ScrollView(context)
        scroll.addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return scroll
    }

    private fun licenseCard(license: SaveFile.License, selected: Boolean, onClick: () -> Unit): View {
        // A racing license: the one being shown is outlined green; the others are a tap away.
        val card = Lx.vbox(context)
        card.background = Lx.pressable(context, if (selected) Lx.SURFACE_HI else Lx.SURFACE, 8f, if (selected) Lx.ACCENT else 0)
        card.setPadding(dp(16f), dp(14f), dp(16f), dp(16f))
        card.setOnClickListener { onClick() }
        card.isSelected = selected
        val top = Lx.hbox(context)
        val mii = ImageView(context)
        MiiImages.bind(mii, license.mii, MiiStudio.Size.LARGE)
        top.addView(mii, Lx.lp(context, 58f, 58f))
        val names = Lx.vbox(context)
        names.addView(Lx.display(context, license.displayName, 20f))
        names.addView(Lx.caption(context, if (license.isOnline) license.friendCode else T("Not online yet"), Lx.MUTED), Lx.lp(context, top = 4f))
        top.addView(names, Lx.lp(context, 0f, -2f, 1f, start = 12f))
        card.addView(top)
        val stats = Lx.hbox(context)
        stats.addView(Lx.stat(context, Lx.number(license.vr), "VR", Lx.AMBER, 22f), Lx.lp(context, 0f, -2f, 1f))
        stats.addView(Lx.stat(context, Lx.number(license.br), "BR", Lx.TEXT, 22f), Lx.lp(context, 0f, -2f, 1f))
        stats.addView(Lx.stat(context, Lx.number(license.wins), "wins", Lx.TEXT, 22f), Lx.lp(context, 0f, -2f, 1f))
        card.addView(stats, Lx.lp(context, top = 16f))
        card.addView(Lx.caption(context, T("%s races").format(Lx.number(license.races)), Lx.FAINT), Lx.lp(context, top = 10f))
        return card
    }

    private fun friendMenu(game: SaveData.Game, license: SaveFile.License, friend: SaveFile.Friend) {
        val sheet = DriftSheet(host, friend.displayName, friend.friendCode)
        if (friend.friendCode.isNotBlank()) {
            sheet.action("Copy friend code", friend.friendCode) {
                sheet.dismiss()
                PlayerActions.copy(host, friend.friendCode)
            }
            sheet.action("Profile and VR history", "From the Retro WFC leaderboard") {
                sheet.dismiss()
                host.open(PlayerPage(host, friend.friendCode, friend.displayName, friend.mii))
            }
        }
        sheet.action("Remove friend", T("Takes them off %s's list").format(license.displayName)) {
            sheet.dismiss()
            DriftSheet.confirm(host, T("Remove %s?").format(friend.displayName), "They're taken off this license's friend list. The save is backed up first (rksys.dat.bak).", "Remove", danger = true) {
                host.guardGameRunning {
                    host.work({ SaveFile.removeFriend(context, game, license.index, friend.profileId) }) { result ->
                        result.fold(
                            onSuccess = { host.broadcast(Data.SAVES) },
                            onFailure = { host.toast(T("Couldn't remove them: %s").format(it.message ?: "unknown error"), Lx.Tone.DANGER) },
                        )
                    }
                }
            }
        }
        sheet.show()
    }

    private fun changeMii(game: SaveData.Game, license: SaveFile.License, miis: MiiDatabase.Snapshot?) {
        if (miis == null || miis.slots.isEmpty()) {
            host.toast(T("There are no Miis to pick from yet."), Lx.Tone.INFO)
            return
        }
        val sheet = DriftSheet(host, T("Race as which Mii?"), license.displayName)
        val rows = ArrayList<LinearLayout>()
        miis.slots.forEach { slot ->
            rows += sheet.radio(slot.mii.displayName, slot.mii.creatorName.ifBlank { null }?.let { T("by %s").format(it) }, slot.mii.miiId == license.miiId) {
                sheet.dismiss()
                if (slot.mii.miiId == license.miiId) return@radio
                host.guardGameRunning {
                    host.work({ SaveFile.setLicenseMii(context, game, license.index, slot.mii) }) { result ->
                        result.fold(
                            onSuccess = {
                                host.toast(T("%s now races as %s.").format(license.displayName, slot.mii.displayName), Lx.Tone.PRIMARY)
                                host.broadcast(Data.SAVES)
                            },
                            onFailure = { host.toast(T("Couldn't change the Mii: %s").format(it.message ?: "unknown error"), Lx.Tone.DANGER) },
                        )
                    }
                }
            }
        }
        sheet.show()
    }

    // --- Miis ---

    private fun loadMiis() {
        host.work({
            val snapshot = MiiDatabase.load(context)
            val used =
                SaveData.Game.values().flatMap { g -> SaveFile.load(context, g)?.licenses.orEmpty() }.map { it.miiId }.toSet()
            snapshot to used
        }) { result ->
            if (!showingMiis) return@work
            val (snapshot, used) = result.getOrNull() ?: (null to emptySet())
            if (snapshot == null) {
                val exists = MiiDatabase.exists(context)
                setBody(
                    Lx.emptyState(
                        context, Icon.FACE,
                        if (exists) "The Mii file couldn't be read" else "No Mii file yet",
                        if (exists) "It failed its own check, so DriftDroid won't touch it. Import a good RFL_DB.dat to replace it." else "Start Mario Kart Wii once - the game makes the Mii file the first time it runs.",
                    ),
                )
                return@work
            }
            if (snapshot.slots.isEmpty()) {
                setBody(Lx.emptyState(context, Icon.FACE, "No Miis yet", "Make one here, or import a .rcd Mii file.", "New Mii") { host.open(MiiEditorPage(host, null, null)) })
                return@work
            }
            val list = RecyclerView(context)
            val grid = GridLayoutManager(context, 5)
            list.layoutManager = grid
            list.clipToPadding = false
            val inner = gutter - dp(5f)
            list.setPadding(inner, 0, inner, dp(96f))
            list.addOnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) {
                    val spans = ((right - left) / dp(112f)).coerceIn(3, 10)
                    if (grid.spanCount != spans) v.post { grid.spanCount = spans }
                }
            }
            list.adapter = MiiAdapter(snapshot.slots, used)
            val column = Lx.vbox(context)
            column.addView(Lx.label(context, T("%s of 100 slots used · hold one for more").format(snapshot.slots.size)), Lx.lp(context, start = gutter / context.resources.displayMetrics.density, bottom = 8f))
            column.addView(list, Lx.lp(context, -1f, 0f, 1f))
            setBody(column)
        }
    }

    private inner class MiiAdapter(val slots: List<MiiDatabase.Slot>, val used: Set<Long>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = slots.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val tile = Lx.vbox(context)
            tile.gravity = Gravity.CENTER_HORIZONTAL
            tile.background = Lx.pressable(context, Lx.SURFACE, 8f)
            tile.setPadding(dp(6f), dp(10f), dp(6f), dp(12f))
            tile.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(5f), dp(5f), dp(5f), dp(5f))
            }
            return object : RecyclerView.ViewHolder(tile) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val slot = slots[position]
            val tile = holder.itemView as LinearLayout
            tile.removeAllViews()
            val image = ImageView(context)
            MiiImages.bind(image, slot.mii)
            tile.addView(image, Lx.lp(context, 72f, 72f))
            val name = Lx.hbox(context, Gravity.CENTER)
            if (slot.mii.isFavorite) name.addView(Lx.iconView(context, Icon.STAR, Lx.AMBER, 13f), Lx.lp(context, 13f, 13f, end = 4f))
            name.addView(Lx.text(context, slot.mii.displayName, 13.5f, Lx.TEXT, Lx.MEDIUM, 1))
            tile.addView(name, Lx.lp(context, -2f, -2f, top = 8f))
            val badge = if (slot.mii.miiId in used) Lx.badge(context, "License", Lx.ACCENT) else Lx.caption(context, slot.mii.creatorName.ifBlank { " " }, Lx.FAINT)
            tile.addView(badge, Lx.lp(context, -2f, -2f, top = 5f))
            tile.setOnClickListener { host.open(MiiEditorPage(host, slot.index, slot.mii)) }
            tile.setOnLongClickListener {
                miiMenu(slot, slot.mii.miiId in used)
                true
            }
        }
    }

    private fun miiMenu(slot: MiiDatabase.Slot, inUse: Boolean) {
        val sheet = DriftSheet(host, slot.mii.displayName, T("Slot %s").format(slot.index + 1))
        sheet.action("Edit", null) {
            sheet.dismiss()
            host.open(MiiEditorPage(host, slot.index, slot.mii))
        }
        sheet.action("Duplicate", "Makes an editable copy") {
            sheet.dismiss()
            writeMiis {
                val snapshot = MiiDatabase.load(context) ?: throw IOException("the Mii file is gone")
                MiiDatabase.add(context, MiiDatabase.adopt(snapshot, slot.mii))
            }
        }
        sheet.action("Export as .rcd", "One Mii, for another Wii, Dolphin or a friend") {
            sheet.dismiss()
            host.createDocument("application/octet-stream", "${slot.mii.displayName.replace(Regex("[^A-Za-z0-9 _-]"), "_")}.rcd") { uri ->
                if (uri == null) return@createDocument
                host.work({ host.contentResolver.openOutputStream(uri)?.use { it.write(slot.mii.data) } ?: throw IOException("couldn't open the destination") }) { result ->
                    host.toast(if (result.isSuccess) T("Mii exported.") else T("Export failed: %s").format(result.exceptionOrNull()?.message), if (result.isSuccess) Lx.Tone.PRIMARY else Lx.Tone.DANGER)
                }
            }
        }
        sheet.action("Delete", if (inUse) T("A license races as this Mii - pick another for it first") else T("Frees the slot")) {
            sheet.dismiss()
            if (inUse) {
                host.toast(T("A license still uses this Mii. Change that license's Mii first."), Lx.Tone.INFO)
                return@action
            }
            DriftSheet.confirm(host, T("Delete %s?").format(slot.mii.displayName), "The Mii is removed from the Mii file. A backup of the file is kept (RFL_DB.dat.bak).", "Delete", danger = true) {
                writeMiis {
                    val snapshot = MiiDatabase.load(context) ?: throw IOException("the Mii file is gone")
                    val current = snapshot.slots.firstOrNull { it.index == slot.index }
                    if (current?.mii?.miiId != slot.mii.miiId) throw IOException("the Mii file changed - reopen this page and try again")
                    MiiDatabase.save(context, MiiDatabase.withSlot(snapshot.bytes, slot.index, null))
                }
            }
        }
        sheet.show()
    }

    /** Runs a Mii file change off the main thread, then refreshes everything that shows Miis. */
    private fun writeMiis(change: () -> Unit) {
        host.guardGameRunning {
            host.work({ change() }) { result ->
                result.fold(
                    onSuccess = { host.broadcast(Data.MIIS) },
                    onFailure = { host.toast(it.message ?: T("Something went wrong"), Lx.Tone.DANGER) },
                )
            }
        }
    }

    /** A single .rcd Mii goes into a free slot; a whole RFL_DB.dat replaces the file (after asking). */
    private fun importMii() {
        host.openDocument { uri ->
            if (uri == null) return@openDocument
            host.work({ host.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("couldn't open the file") }) { result ->
                val bytes = result.getOrElse {
                    host.toast(T("Couldn't read that file: %s").format(it.message), Lx.Tone.DANGER)
                    return@work
                }
                when {
                    bytes.size == Mii.RECORD_BYTES -> {
                        val mii = Mii.fromBytes(bytes)
                        if (mii == null) {
                            host.toast(T("That Mii file is empty."), Lx.Tone.DANGER)
                            return@work
                        }
                        writeMiis {
                            val snapshot = MiiDatabase.load(context) ?: throw IOException("there's no Mii file yet - start Mario Kart Wii once first")
                            val slot = MiiDatabase.add(context, MiiDatabase.adopt(snapshot, mii))
                            host.runOnUiThread { host.toast(T("%s added to slot %s.").format(mii.displayName, slot + 1), Lx.Tone.PRIMARY) }
                        }
                    }
                    bytes.size == MiiDatabase.DATABASE_BYTES -> {
                        if (!MiiDatabase.isValid(bytes)) {
                            host.toast(T("That Mii database failed its check - it may be damaged."), Lx.Tone.DANGER)
                            return@work
                        }
                        DriftSheet.confirm(host, "Replace all Miis?", "Every Mii is replaced with the ones in the picked file. The current file is backed up first (RFL_DB.dat.bak).", "Replace", danger = true) {
                            writeMiis { MiiDatabase.save(context, bytes) }
                        }
                    }
                    else -> host.toast(T("That isn't a Mii (.rcd, 74 bytes) or a Mii database (RFL_DB.dat)."), Lx.Tone.DANGER)
                }
            }
        }
    }

    private fun exportAll() {
        val file = MiiDatabase.file(context)
        if (!file.isFile) {
            host.toast(T("No Mii file yet - start Mario Kart Wii once first."), Lx.Tone.INFO)
            return
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        host.createDocument("application/octet-stream", "DriftDroid-Mii-$stamp.dat") { uri ->
            if (uri == null) return@createDocument
            host.work({ host.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: throw IOException("couldn't open the destination") }) { result ->
                host.toast(if (result.isSuccess) T("Mii file exported.") else T("Export failed: %s").format(result.exceptionOrNull()?.message), if (result.isSuccess) Lx.Tone.PRIMARY else Lx.Tone.DANGER)
            }
        }
    }
}
