package com.driftdroid.android.launcher

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.driftdroid.android.ModePickerActivity
import com.driftdroid.android.SaveData
import com.driftdroid.android.T
import com.driftdroid.android.mii.Mii
import com.driftdroid.android.mii.MiiImages
import com.driftdroid.android.net.Http
import com.driftdroid.android.online.RetroWfc
import com.driftdroid.android.profile.FriendCode
import com.driftdroid.android.profile.SaveFile
import com.driftdroid.android.ui.DriftSheet
import com.driftdroid.android.ui.Icon
import com.driftdroid.android.ui.Lx

private const val POLL_MS = 40_000L

/** Who's racing on Retro WFC right now, room by room - refreshed every 40 seconds while open. */
internal class RoomsPage(host: ModePickerActivity) : Page(host) {
    override val title: String get() = T("Rooms")
    override val subtitle: String
        get() {
            val status = RetroWfc.lastRooms ?: return T("Live from Retro WFC")
            return T("%s racers in %s rooms · updated %s").format(
                Lx.number(status.totalPlayers), status.activeRooms.size, Lx.ago(status.fetchedAt),
            )
        }

    private val handler = Handler(Looper.getMainLooper())
    private val poll = Runnable { fetch() }
    private lateinit var frame: FrameLayout
    private lateinit var grid: RecyclerView
    private lateinit var results: RecyclerView
    private lateinit var state: FrameLayout
    private val roomAdapter = RoomAdapter()
    private val resultAdapter = PlayerAdapter()
    private var query = ""
    private var fetching = false
    private var lastError: Throwable? = null

    // On a phone the search box sits at the top of the page; on a wide screen it fits in the header.
    override fun actions(): List<View> {
        val refresh = Lx.iconButton(context, Icon.REFRESH, "Refresh", Lx.MUTED, android.graphics.Color.TRANSPARENT, 44f) { fetch() }
        if (compact) return listOf(refresh)
        val box = searchBox()
        box.layoutParams = LinearLayout.LayoutParams(dp(260f), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8f) }
        return listOf(box, refresh)
    }

    private fun searchBox(): View = Lx.searchField(context, "Find a player", { applyQuery(it) }, { applyQuery(it) }).first

    override fun build(): View {
        frame = FrameLayout(context)
        val inner = gutter - dp(6f)
        frame.setPadding(inner, 0, inner, 0)
        grid = RecyclerView(context)
        val layout = GridLayoutManager(context, 2)
        grid.layoutManager = layout
        grid.adapter = roomAdapter
        grid.clipToPadding = false
        grid.setPadding(0, dp(4f), 0, dp(96f))
        grid.addOnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                val spans = ((right - left) / dp(300f)).coerceIn(1, 4)
                if (layout.spanCount != spans) v.post { layout.spanCount = spans }
            }
        }
        results = RecyclerView(context)
        results.layoutManager = LinearLayoutManager(context)
        results.adapter = resultAdapter
        results.clipToPadding = false
        results.setPadding(dp(6f), dp(4f), dp(6f), dp(96f))
        state = FrameLayout(context)
        frame.addView(grid, match())
        frame.addView(results, match())
        frame.addView(state, match())
        render()
        if (!compact) return frame
        val column = Lx.vbox(context)
        column.addView(searchBox(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = gutter
            marginEnd = gutter
            bottomMargin = dp(8f)
        })
        column.addView(frame, Lx.lp(context, -1f, 0f, 1f))
        return column
    }

    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    override fun onShow() {
        val last = RetroWfc.lastRooms
        if (last == null || System.currentTimeMillis() - last.fetchedAt > 15_000) fetch() else schedule()
        render()
    }

    override fun onHide() = handler.removeCallbacks(poll)

    override fun onClose() = handler.removeCallbacks(poll)

    override fun onDataChanged(what: Data) {
        if (what == Data.ROOMS) render()
    }

    private fun schedule() {
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, POLL_MS)
    }

    private fun fetch() {
        if (fetching) return
        fetching = true
        handler.removeCallbacks(poll)
        host.work({ RetroWfc.fetchRooms() }) { result ->
            fetching = false
            lastError = result.exceptionOrNull()
            host.broadcast(Data.ROOMS)
            refreshHeader()
            render()
            if (host.isShowing(this)) schedule()
        }
    }

    private fun applyQuery(text: String) {
        query = text.trim()
        render()
    }

    private fun render() {
        if (!::frame.isInitialized) return
        val status = RetroWfc.lastRooms
        state.removeAllViews()
        grid.visibility = View.GONE
        results.visibility = View.GONE
        state.visibility = View.VISIBLE
        when {
            status == null && lastError == null -> state.addView(Lx.loading(context, "Asking Retro WFC who's online..."), match())
            status == null ->
                state.addView(
                    Lx.emptyState(context, Icon.WARNING, "Retro WFC didn't answer", T("Check your connection and try again (%s).").format(Http.describe(lastError!!)), "Try again") { fetch() },
                    match(),
                )
            query.isNotEmpty() -> {
                val q = query.lowercase()
                val hits =
                    status.rooms.flatMap { room -> room.players.map { room to it } }
                        .filter { (_, p) -> p.displayName.lowercase().contains(q) || p.friendCode.contains(q) }
                resultAdapter.items = hits
                resultAdapter.notifyDataSetChanged()
                if (hits.isEmpty()) {
                    state.addView(Lx.emptyState(context, Icon.SEARCH, "Nobody by that name", T("No one online matches \"%s\" right now.").format(query)), match())
                } else {
                    state.visibility = View.GONE
                    results.visibility = View.VISIBLE
                }
            }
            status.activeRooms.isEmpty() -> {
                state.addView(
                    Lx.emptyState(context, Icon.PUBLIC, "Nobody's racing right now", "Rooms show up here the moment people start racing. Check back soon.", "Refresh") { fetch() },
                    match(),
                )
            }
            else -> {
                roomAdapter.rooms = status.activeRooms
                roomAdapter.notifyDataSetChanged()
                state.visibility = View.GONE
                grid.visibility = View.VISIBLE
            }
        }
    }

    private inner class RoomAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var rooms: List<RetroWfc.Room> = emptyList()

        override fun getItemCount() = rooms.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val card = Lx.vbox(context)
            card.background = Lx.pressable(context, Lx.SURFACE, 8f)
            card.setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            card.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(6f), dp(6f), dp(6f), dp(6f))
            }
            return object : RecyclerView.ViewHolder(card) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val room = rooms[position]
            val card = holder.itemView as LinearLayout
            card.removeAllViews()
            val top = Lx.hbox(context)
            top.addView(Lx.label(context, room.label), Lx.lp(context, 0f, -2f, 1f))
            top.addView(if (room.isJoinable) Lx.status(context, T("Open"), Lx.ACCENT, Lx.TEXT) else Lx.status(context, T("Full or racing"), Lx.FAINT, Lx.MUTED))
            card.addView(top)
            val track = room.race?.trackName?.takeIf { it.isNotBlank() }
            card.addView(Lx.display(context, track ?: T("Between races"), 20f), Lx.lp(context, top = 10f))
            val line =
                listOfNotNull(
                    room.race?.takeIf { it.number > 0 }?.let { T("Race %s").format(it.number) },
                    room.createdAt?.let { T("open since %s").format(Lx.ago(it)) },
                ).joinToString(" · ")
            card.addView(Lx.caption(context, line, Lx.MUTED), Lx.lp(context, top = 4f))
            card.addView(miiStrip(context, room.players.map { it.mii }, 7), Lx.lp(context, top = 14f))
            val bottom = Lx.hbox(context, Gravity.BOTTOM)
            bottom.addView(Lx.display(context, room.players.size.toString(), 18f))
            bottom.addView(Lx.label(context, "/ 12 racers"), Lx.lp(context, -2f, -2f, start = 5f, bottom = 2f))
            bottom.addView(Lx.spring(context))
            room.averageVr?.let {
                bottom.addView(Lx.display(context, Lx.number(it), 18f, Lx.AMBER))
                bottom.addView(Lx.label(context, "avg VR"), Lx.lp(context, -2f, -2f, start = 5f, bottom = 2f))
            }
            card.addView(bottom, Lx.lp(context, top = 14f))
            card.setOnClickListener { host.open(RoomDetailPage(host, room)) }
        }
    }

    private inner class PlayerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<Pair<RetroWfc.Room, RetroWfc.Player>> = emptyList()

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(PlayerRow(context)) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val (room, player) = items[position]
            val row = holder.itemView as PlayerRow
            row.bind(player.mii, player.displayName, listOf(player.friendCode, room.label).filter { it.isNotBlank() }.joinToString(" · "))
            row.setValue(player.vr?.let { T("%s VR").format(Lx.number(it)) }, Lx.AMBER)
            row.setOnClickListener { host.open(RoomDetailPage(host, room)) }
        }
    }
}

/** One room's players, with who's hosting and everyone's rating. */
internal class RoomDetailPage(host: ModePickerActivity, private var room: RetroWfc.Room) : Page(host) {
    override val title: String get() = room.race?.trackName?.takeIf { it.isNotBlank() } ?: room.label
    override val subtitle: String
        get() = listOfNotNull(room.label, T("%s players").format(room.players.size), room.averageVr?.let { T("avg %s VR").format(Lx.number(it)) }, if (closed) T("closed") else null)
            .joinToString(" · ")

    private val handler = Handler(Looper.getMainLooper())
    private val poll = Runnable { fetch() }
    private var closed = false
    private val adapter = Adapter()

    override fun actions(): List<View> = listOf(Lx.iconButton(context, Icon.REFRESH, "Refresh", Lx.MUTED, android.graphics.Color.TRANSPARENT, 44f) { fetch() })

    override fun build(): View {
        val list = RecyclerView(context)
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        list.clipToPadding = false
        list.setPadding(gutter, dp(4f), gutter, dp(96f))
        return list
    }

    override fun onShow() = schedule()

    override fun onHide() = handler.removeCallbacks(poll)

    override fun onClose() = handler.removeCallbacks(poll)

    override fun onDataChanged(what: Data) {
        if (what != Data.ROOMS) return
        val status = RetroWfc.lastRooms ?: return
        // A room id can stand for several rooms after a split; pick the one sharing the most players.
        val mine = room.players.map { it.pid }.toSet()
        val match = status.rooms.filter { it.id == room.id }.maxByOrNull { r -> r.players.count { it.pid in mine } }
        if (match == null || match.players.isEmpty()) {
            closed = true
        } else {
            closed = false
            room = match
        }
        adapter.notifyDataSetChanged()
        refreshHeader()
    }

    private fun schedule() {
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, POLL_MS)
    }

    private fun fetch() {
        handler.removeCallbacks(poll)
        host.work({ RetroWfc.fetchRooms() }) { result ->
            if (result.isSuccess) host.broadcast(Data.ROOMS) else host.toast(T("Couldn't refresh: %s").format(Http.describe(result.exceptionOrNull()!!)), Lx.Tone.DANGER)
            if (host.isShowing(this)) schedule()
        }
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = room.players.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(PlayerRow(context)) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val player = room.players.sortedByDescending { it.vr ?: 0 }[position]
            val row = holder.itemView as PlayerRow
            row.bind(player.mii, player.displayName, player.friendCode.ifBlank { T("No friend code") })
            row.badges.removeAllViews()
            if (player.isOpenHost) row.badges.addView(Lx.badge(context, "Open host", Lx.CYAN))
            val values = listOfNotNull(player.vr?.let { T("%s VR").format(Lx.number(it)) }, player.br?.let { T("%s BR").format(Lx.number(it)) })
            row.setValue(values.firstOrNull(), Lx.AMBER, values.getOrNull(1))
            row.setOnClickListener { PlayerActions.show(host, player.displayName, player.friendCode, player.mii, player.vr, player.br) }
        }
    }
}

/** A Mii, a name and a line under it, with an optional value on the right. Used by every list of people. */
internal class PlayerRow(context: Context) : LinearLayout(context) {
    val mii = ImageView(context)
    private val name = Lx.text(context, "", 15f, Lx.TEXT, Lx.MEDIUM, 1)
    private val detail = Lx.caption(context, "", Lx.MUTED, 1)
    val badges = Lx.hbox(context)
    // A rating is never cut short: the name beside it gives way instead.
    private val value = Lx.display(context, "", 16f, Lx.AMBER).apply { ellipsize = null }
    private val value2 = Lx.caption(context, "", Lx.MUTED, 1)
    val lead = Lx.hbox(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Lx.pressable(context, Lx.SURFACE, 8f)
        setPadding(Lx.dp(context, 10f), Lx.dp(context, 8f), Lx.dp(context, 16f), Lx.dp(context, 8f))
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = Lx.dp(context, 6f) }
        addView(lead)
        mii.background = Lx.rounded(context, Lx.SURFACE_HI, 6f)
        addView(mii, Lx.lp(context, 46f, 46f))
        val column = Lx.vbox(context)
        val top = Lx.hbox(context)
        top.addView(name, Lx.lp(context, -2f, -2f, end = 8f))
        top.addView(badges)
        column.addView(top)
        column.addView(detail, Lx.lp(context, top = 3f))
        addView(column, Lx.lp(context, 0f, -2f, 1f, start = 12f, end = 8f))
        val right = Lx.vbox(context)
        right.gravity = Gravity.END
        value.gravity = Gravity.END
        value2.gravity = Gravity.END
        right.addView(value)
        right.addView(value2, Lx.lp(context, -2f, -2f, top = 2f))
        addView(right)
    }

    fun bind(m: Mii?, title: String, line: String) {
        MiiImages.bind(mii, m)
        name.text = title
        detail.text = line
    }

    fun setValue(main: String?, color: Int, second: String? = null) {
        value.text = main ?: ""
        value.setTextColor(color)
        value2.text = second ?: ""
        value2.visibility = if (second.isNullOrEmpty()) View.GONE else View.VISIBLE
    }
}

/** Up to [max] Mii faces overlapping in a row, with "+N" for the rest. */
internal fun miiStrip(context: Context, miis: List<Mii?>, max: Int): View {
    val row = Lx.hbox(context)
    val size = Lx.dp(context, 32f)
    miis.take(max).forEachIndexed { i, mii ->
        val view = ImageView(context)
        view.background = Lx.rounded(context, Lx.SURFACE_HI, 16f, Lx.SURFACE, 2f)
        MiiImages.bind(view, mii)
        row.addView(view, LinearLayout.LayoutParams(size, size).apply { if (i > 0) marginStart = -Lx.dp(context, 8f) })
    }
    if (miis.size > max) {
        row.addView(Lx.display(context, "+${miis.size - max}", 14f, Lx.MUTED), Lx.lp(context, -2f, -2f, start = 8f))
    }
    return row
}

/** What can be done with another player from anywhere in the launcher. */
internal object PlayerActions {
    fun show(host: ModePickerActivity, name: String, friendCode: String, mii: Mii?, vr: Int?, br: Int?) {
        val code = FriendCode.normalize(friendCode)
        val sheet = DriftSheet(host, name, code ?: T("No friend code"))
        if (code != null) {
            sheet.action("Copy friend code", code) {
                sheet.dismiss()
                copy(host, code)
            }
            sheet.action("Add as a friend", "Puts them on a license's friend list") {
                sheet.dismiss()
                addFriend(host, code, mii, vr, br)
            }
            sheet.action("Profile and VR history", "From the Retro WFC leaderboard") {
                sheet.dismiss()
                host.open(PlayerPage(host, code, name, mii))
            }
        } else {
            sheet.note("This player has no friend code to show.")
        }
        sheet.show()
    }

    fun copy(host: ModePickerActivity, text: String) {
        (host.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("Friend code", text))
        host.toast(T("Friend code copied."), Lx.Tone.PRIMARY)
    }

    /** Adds [code] to one of the player's licenses, asking which when there's more than one. */
    fun addFriend(host: ModePickerActivity, code: String, mii: Mii?, vr: Int?, br: Int?) {
        if (mii == null) {
            host.toast(T("Retro WFC didn't send this player's Mii, so they can't be added from here."), Lx.Tone.DANGER)
            return
        }
        host.work({
            SaveData.Game.values().sortedBy { if (it == SaveData.Game.RetroRewind) 0 else 1 }
                .flatMap { game -> SaveFile.load(host, game)?.licenses?.filter { it.isOnline }?.map { game to it }.orEmpty() }
        }) { result ->
            val licenses = result.getOrNull().orEmpty()
            when {
                licenses.isEmpty() -> host.toast(T("There's no license with a friend code yet - play online once first."), Lx.Tone.DANGER)
                licenses.size == 1 -> confirmAdd(host, licenses[0].first, licenses[0].second, code, mii, vr, br)
                else -> {
                    val sheet = DriftSheet(host, T("Add to which license?"))
                    for ((game, license) in licenses) {
                        sheet.action(license.displayName, T("%s · %s friends").format(game.label, license.friends.size)) {
                            sheet.dismiss()
                            confirmAdd(host, game, license, code, mii, vr, br)
                        }
                    }
                    sheet.show()
                }
            }
        }
    }

    private fun confirmAdd(host: ModePickerActivity, game: SaveData.Game, license: SaveFile.License, code: String, mii: Mii, vr: Int?, br: Int?) {
        host.guardGameRunning {
            host.work({ SaveFile.addFriend(host, game, license.index, code, mii, vr, br) }) { result ->
                result.fold(
                    onSuccess = {
                        host.toast(T("Added %s to %s. They show as pending until they add you back.").format(mii.displayName, license.displayName), Lx.Tone.PRIMARY)
                        host.broadcast(Page.Data.SAVES)
                    },
                    onFailure = { host.toast(T("Couldn't add them: %s").format(it.message ?: "unknown error"), Lx.Tone.DANGER) },
                )
            }
        }
    }
}
