package com.driftdroid.android.launcher

import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.driftdroid.android.ModManager
import com.driftdroid.android.ModePickerActivity
import com.driftdroid.android.T
import com.driftdroid.android.mods.GameBanana
import com.driftdroid.android.net.Http
import com.driftdroid.android.ui.AspectFrame
import com.driftdroid.android.ui.DriftSheet
import com.driftdroid.android.ui.DriftUi
import com.driftdroid.android.ui.Icon
import com.driftdroid.android.ui.IconDrawable
import com.driftdroid.android.ui.Lx
import com.driftdroid.android.ui.RemoteImages
import com.driftdroid.android.ui.Switch
import com.driftdroid.android.ui.TabStrip

/**
 * Mods: what's installed (on/off, priority by drag, delete, import a file you have) and a browser
 * for GameBanana's Mario Kart Wii mods that installs with one tap - the mod browser and "My Stuff"
 * manager WheelWizard has on PC, for the phone.
 */
internal class ModsPage(host: ModePickerActivity) : Page(host) {
    override val title: String get() = T("Mods")
    override val subtitle: String
        get() = if (browsing) T("Mario Kart Wii mods on GameBanana") else T("Turned-on mods load the next time you start the game")

    private var browsing = false
    private lateinit var tabs: TabStrip
    private lateinit var installedBar: View
    private lateinit var installedSummary: TextView
    private lateinit var browseBar: View
    private lateinit var searchField: EditText
    private val categoryChips = ArrayList<TextView>()
    private lateinit var content: FrameLayout

    // Installed
    private lateinit var installedList: RecyclerView
    private val installedAdapter = InstalledAdapter()
    private lateinit var installedEmpty: View

    // Browse
    private lateinit var browseList: RecyclerView
    private val browseAdapter = BrowseAdapter()
    private lateinit var browseState: FrameLayout
    private var category = 0
    private var query = ""
    private var page = 0
    private var complete = false
    private var loading = false
    private var generation = 0
    private var installedIds: Set<Int> = emptySet()

    override fun build(): View {
        val root = Lx.vbox(context)

        tabs = TabStrip(context, listOf(T("Installed"), T("Browse GameBanana")), fill = compact) { showTab(it == 1) }
        root.addView(tabs.view, Lx.lp(context).apply { marginStart = if (compact) 0 else gutter - dp(10f) })
        root.addView(Lx.divider(context))

        // Installed: how many are on, and a way in for a file the player already has.
        val iBar = Lx.hbox(context)
        installedSummary = Lx.caption(context, "", Lx.MUTED, 2)
        iBar.addView(installedSummary, Lx.lp(context, 0f, -2f, 1f, end = 12f))
        iBar.addView(Lx.button(context, "Import a file", Lx.Tone.GHOST, Icon.UPLOAD, compact = true) { importFile() })
        installedBar = iBar

        // Browse: search and categories.
        val bBar = Lx.vbox(context)
        val (box, field) = Lx.searchField(context, "Search mods", { startQuery(it) })
        searchField = field
        val chips = Lx.hbox(context)
        GameBanana.CATEGORIES.forEachIndexed { index, cat ->
            val chip = Lx.chip(context, cat.label, index == 0) { selectCategory(index) }
            categoryChips += chip
            chips.addView(chip, Lx.lp(context, -2f, -2f, end = 8f))
        }
        val chipScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }
        if (compact) {
            bBar.addView(box)
            bBar.addView(chipScroll, Lx.lp(context, top = 10f))
        } else {
            val line = Lx.hbox(context)
            line.addView(chipScroll, Lx.lp(context, 0f, -2f, 1f, end = 12f))
            line.addView(box, Lx.lp(context, 300f, -2f))
            bBar.addView(line)
        }
        browseBar = bBar

        val bars = FrameLayout(context)
        bars.setPadding(gutter, dp(12f), gutter, dp(4f))
        bars.addView(installedBar)
        bars.addView(browseBar)
        root.addView(bars)

        content = FrameLayout(context)
        root.addView(content, Lx.lp(context, -1f, 0f, 1f, top = 8f))

        // Installed
        installedList = RecyclerView(context)
        installedList.layoutManager = LinearLayoutManager(context)
        installedList.adapter = installedAdapter
        installedList.clipToPadding = false
        installedList.setPadding(gutter, 0, gutter, dp(96f))
        val touch = ItemTouchHelper(DragCallback())
        touch.attachToRecyclerView(installedList)
        installedAdapter.dragStarter = { touch.startDrag(it) }
        installedEmpty =
            Lx.emptyState(
                context, Icon.EXTENSION, "No mods yet",
                "Browse GameBanana and install one with a tap, or import a .zip, .7z or .rar you already have.",
                "Browse mods",
            ) { selectTab(true) }
        content.addView(installedList, Lx.match())
        content.addView(installedEmpty, Lx.match())

        // Browse
        browseList = RecyclerView(context)
        val grid = GridLayoutManager(context, 2)
        grid.spanSizeLookup =
            object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (browseAdapter.getItemViewType(position) == FOOTER) grid.spanCount else 1
            }
        browseList.layoutManager = grid
        browseList.adapter = browseAdapter
        browseList.clipToPadding = false
        val inner = gutter - dp(5f)
        browseList.setPadding(inner, 0, inner, dp(96f))
        browseList.addOnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                val spans = ((right - left) / dp(176f)).coerceIn(2, 6)
                if (grid.spanCount != spans) v.post { grid.spanCount = spans }
            }
        }
        browseList.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy > 0 && grid.findLastVisibleItemPosition() >= browseAdapter.itemCount - 6) loadMore()
                }
            },
        )
        browseState = FrameLayout(context)
        content.addView(browseList, Lx.match())
        content.addView(browseState, Lx.match())

        selectTab(false)
        return root
    }

    override fun onShow() {
        refreshInstalled()
    }

    override fun onDataChanged(what: Data) {
        if (what == Data.MODS) refreshInstalled()
    }

    private fun selectTab(browse: Boolean) {
        tabs.select(if (browse) 1 else 0)
        showTab(browse)
    }

    fun showTab(browse: Boolean) {
        browsing = browse
        installedBar.visibility = if (browse) View.GONE else View.VISIBLE
        browseBar.visibility = if (browse) View.VISIBLE else View.GONE
        installedList.visibility = View.GONE
        installedEmpty.visibility = View.GONE
        browseList.visibility = if (browse) View.VISIBLE else View.GONE
        browseState.visibility = if (browse) View.VISIBLE else View.GONE
        if (browse) {
            if (browseAdapter.mods.isEmpty() && !loading) startQuery(query)
        } else {
            refreshInstalled()
        }
        refreshHeader()
    }

    // --- Installed ---

    private fun refreshInstalled() {
        val packs = ModManager.installedPacks(context)
        installedIds = packs.mapNotNull { it.meta?.gameBananaId }.toSet()
        tabs.setLabel(0, if (packs.isEmpty()) T("Installed") else T("Installed (%s)").format(packs.size))
        val on = packs.count { it.enabled }
        installedSummary.text =
            when {
                packs.isEmpty() -> T("Mods you install show here")
                packs.size == 1 -> T("%s of 1 on").format(on)
                else -> T("%s of %s on · drag to set which wins").format(on, packs.size)
            }
        installedAdapter.items = packs.toMutableList()
        installedAdapter.notifyDataSetChanged()
        browseAdapter.notifyDataSetChanged()
        if (!browsing) {
            installedList.visibility = if (packs.isEmpty()) View.GONE else View.VISIBLE
            installedEmpty.visibility = if (packs.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun importFile() {
        host.openDocument { uri ->
            if (uri == null) return@openDocument
            val fileName = ModManager.displayName(context, uri) ?: "mod"
            val extension = fileName.substringAfterLast('.', "").lowercase()
            if (extension !in GameBanana.ARCHIVE_EXTENSIONS) {
                host.toast(T("\"%s\" isn't a .zip, .7z or .rar file.").format(fileName), Lx.Tone.DANGER)
                return@openDocument
            }
            val sheet = DriftSheet(host, T("Name this mod"), compact = true)
            val field = sheet.textField("Mod name", fileName.substringBeforeLast('.'))
            sheet.button("Cancel") { sheet.dismiss() }
            sheet.button("Install", DriftUi.Style.PRIMARY) {
                val name = field.text.toString()
                sheet.dismiss()
                host.task(T("Installing \"%s\"...").format(name), { progress ->
                    ModManager.importFile(context, uri, name) { count -> progress(T("%s files unpacked").format(count)) }
                }) { result ->
                    result.fold(
                        onSuccess = {
                            host.toast(T("Installed \"%s\". It loads the next time you start the game.").format(it.title), Lx.Tone.PRIMARY)
                            host.broadcast(Data.MODS)
                        },
                        onFailure = { host.toast(T("Couldn't install it: %s").format(it.message ?: "unknown error"), Lx.Tone.DANGER) },
                    )
                }
            }
            sheet.show()
        }
    }

    private fun showPackMenu(pack: ModManager.Pack) {
        val sheet = DriftSheet(host, pack.title, pack.meta?.author?.let { T("by %s").format(it) })
        val id = pack.meta?.gameBananaId
        if (id != null) {
            sheet.action("Open on GameBanana", "See its page, comments and updates") {
                sheet.dismiss()
                runCatching { host.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://gamebanana.com/mods/$id"))) }
            }
        }
        sheet.action("Delete", "Removes the mod's files from the app") {
            sheet.dismiss()
            DriftSheet.confirm(host, T("Delete \"%s\"?").format(pack.title), "The mod's files are removed from the app.", "Delete", danger = true) {
                ModManager.delete(context, pack)
                host.broadcast(Data.MODS)
            }
        }
        sheet.show()
    }

    /** Installed mods as rows: priority number and handle, picture, name, on/off, menu. First in the list wins. */
    private inner class InstalledAdapter : RecyclerView.Adapter<InstalledAdapter.Holder>() {
        var items: MutableList<ModManager.Pack> = mutableListOf()
        var dragStarter: ((RecyclerView.ViewHolder) -> Unit)? = null

        inner class Holder(
            val row: LinearLayout,
            val handle: LinearLayout,
            val order: TextView,
            val thumb: ImageView,
            val name: TextView,
            val detail: TextView,
            val toggle: Switch,
            val more: ImageView,
        ) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = Lx.hbox(context)
            row.background = Lx.pressable(context, Lx.SURFACE, 8f)
            row.setPadding(dp(4f), dp(10f), dp(6f), dp(10f))
            row.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8f) }
            val handle = Lx.vbox(context)
            handle.gravity = Gravity.CENTER
            val order = Lx.display(context, "", 13f, Lx.MUTED)
            handle.addView(order)
            handle.addView(Lx.iconView(context, Icon.DRAG, Lx.FAINT, 18f), Lx.lp(context, 18f, 18f, top = 2f))
            handle.contentDescription = T("Drag to reorder")
            row.addView(handle, Lx.lp(context, 34f, 48f))
            val thumb = ImageView(context)
            thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            thumb.background = Lx.rounded(context, Lx.SURFACE_HI, 6f)
            Lx.clipRounded(thumb, 6f)
            row.addView(thumb, Lx.lp(context, if (compact) 64f else 80f, if (compact) 40f else 48f, start = 2f))
            val column = Lx.vbox(context)
            val name = Lx.text(context, "", 15f, Lx.TEXT, Lx.MEDIUM, 2)
            val detail = Lx.caption(context, "", Lx.MUTED, 1)
            column.addView(name)
            column.addView(detail, Lx.lp(context, top = 4f))
            row.addView(column, Lx.lp(context, 0f, -2f, 1f, start = 12f, end = 4f))
            val toggle = Switch(context)
            row.addView(toggle, Lx.switchLp(context))
            val more = Lx.iconButton(context, Icon.MORE_VERT, "More", Lx.MUTED, android.graphics.Color.TRANSPARENT, 40f) {}
            row.addView(more, Lx.lp(context, 40f, 40f, start = 2f))
            return Holder(row, handle, order, thumb, name, detail, toggle, more)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val pack = items[position]
            holder.order.text = (position + 1).toString()
            holder.name.text = pack.title
            val meta = pack.meta
            holder.detail.text =
                when {
                    meta?.gameBananaId != null -> listOfNotNull(meta.author, Lx.ago(meta.installedAt)).filter { it.isNotBlank() }.joinToString(" · ")
                    meta != null -> listOfNotNull(meta.fileName, Lx.ago(meta.installedAt)).filter { it.isNotBlank() }.joinToString(" · ")
                    else -> T("Imported mod")
                }
            paintOn(holder, pack.enabled)
            RemoteImages.bind(holder.thumb, meta?.thumbnailUrl, dp(80f), IconDrawable(Icon.EXTENSION, Lx.FAINT))
            holder.thumb.scaleType = if (meta?.thumbnailUrl != null) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.CENTER_INSIDE
            if (meta?.thumbnailUrl == null) holder.thumb.setPadding(dp(20f), dp(8f), dp(20f), dp(8f)) else holder.thumb.setPadding(0, 0, 0, 0)
            holder.row.setOnClickListener {
                val index = holder.bindingAdapterPosition
                val current = items.getOrNull(index) ?: return@setOnClickListener
                val on = !holder.toggle.isOn
                paintOn(holder, on)
                ModManager.setEnabled(current, on)
                items[index] = current.copy(enabled = on)
                refreshSummary()
            }
            holder.more.setOnClickListener { items.getOrNull(holder.bindingAdapterPosition)?.let { showPackMenu(it) } }
            holder.handle.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) dragStarter?.invoke(holder)
                false
            }
        }

        private fun paintOn(holder: Holder, on: Boolean) {
            holder.toggle.isOn = on
            holder.thumb.alpha = if (on) 1f else 0.4f
            holder.name.setTextColor(if (on) Lx.TEXT else Lx.MUTED)
        }

        fun move(from: Int, to: Int) {
            val moved = items.removeAt(from)
            items.add(to, moved)
            notifyItemMoved(from, to)
        }
    }

    private fun refreshSummary() {
        val packs = installedAdapter.items
        val on = packs.count { it.enabled }
        installedSummary.text = if (packs.size == 1) T("%s of 1 on").format(on) else T("%s of %s on · drag to set which wins").format(on, packs.size)
    }

    // Drag to reorder by the handle only, so a long press on the switch doesn't start a drag. The
    // order is saved the moment a drag ends, and the priority numbers follow.
    private inner class DragCallback : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun isLongPressDragEnabled() = false

        override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            installedAdapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
            return true
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            ModManager.setOrder(context, installedAdapter.items.map { it.name })
            recyclerView.post { installedAdapter.notifyItemRangeChanged(0, installedAdapter.itemCount) }
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
    }

    // --- Browse ---

    private fun selectCategory(index: Int) {
        category = index
        categoryChips.forEachIndexed { i, chip -> Lx.paintChip(chip, i == index) }
        startQuery(searchField.text.toString())
    }

    private fun startQuery(text: String) {
        query = text.trim()
        generation++
        page = 0
        complete = false
        loading = false
        browseAdapter.mods.clear()
        browseAdapter.footer = Footer.NONE
        browseAdapter.notifyDataSetChanged()
        showBrowseState(Lx.loading(context, "Loading mods..."))
        loadMore()
    }

    private fun loadMore() {
        if (loading || complete) return
        loading = true
        val wanted = page + 1
        val token = generation
        val cat = GameBanana.CATEGORIES[category]
        val text = query
        if (browseAdapter.mods.isNotEmpty()) {
            browseAdapter.footer = Footer.LOADING
            browseAdapter.notifyItemChanged(browseAdapter.itemCount - 1)
        }
        host.work({
            if (text.isEmpty()) GameBanana.list(cat.id, wanted) else GameBanana.search(text, wanted, cat)
        }) { result ->
            if (token != generation) return@work
            loading = false
            result.fold(
                onSuccess = { list ->
                    page = wanted
                    complete = list.complete || list.mods.isEmpty() && text.isEmpty()
                    val known = browseAdapter.mods.map { it.id }.toHashSet()
                    val fresh = list.mods.filter { known.add(it.id) }
                    browseAdapter.mods.addAll(fresh)
                    browseAdapter.footer = if (complete) Footer.END else Footer.NONE
                    browseAdapter.notifyDataSetChanged()
                    if (browseAdapter.mods.isEmpty() && (complete || list.mods.isEmpty())) {
                        complete = true
                        showBrowseState(
                            Lx.emptyState(
                                context, Icon.SEARCH, "Nothing found",
                                if (text.isEmpty()) "There are no mods in this category yet." else T("No mods match \"%s\".").format(text),
                            ),
                        )
                    } else {
                        showBrowseState(null)
                        // A search filtered down to a few results may not fill the screen: keep going.
                        if (fresh.size < 6 && !complete) browseList.post { loadMore() }
                    }
                },
                onFailure = { error ->
                    if (browseAdapter.mods.isEmpty()) {
                        showBrowseState(
                            Lx.emptyState(context, Icon.WARNING, "GameBanana didn't answer", T("Check your connection and try again (%s).").format(Http.describe(error)), "Try again") {
                                startQuery(query)
                            },
                        )
                    } else {
                        browseAdapter.footer = Footer.ERROR
                        browseAdapter.notifyItemChanged(browseAdapter.itemCount - 1)
                    }
                },
            )
        }
    }

    private fun showBrowseState(view: View?) {
        browseState.removeAllViews()
        if (view != null) browseState.addView(view, Lx.match())
        browseState.visibility = if (view != null && browsing) View.VISIBLE else View.GONE
    }

    /** GameBanana mods as picture cards; one already installed carries a green slanted plate. */
    private inner class BrowseAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        val mods = ArrayList<GameBanana.ModSummary>()
        var footer = Footer.NONE

        inner class Card(
            val root: LinearLayout,
            val image: ImageView,
            val badge: TextView,
            val name: TextView,
            val author: TextView,
            val likes: TextView,
            val views: TextView,
        ) : RecyclerView.ViewHolder(root)

        inner class FooterHolder(val root: FrameLayout) : RecyclerView.ViewHolder(root)

        override fun getItemViewType(position: Int) = if (position >= mods.size) FOOTER else CARD

        override fun getItemCount() = mods.size + if (footer == Footer.NONE) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == FOOTER) {
                val frame = FrameLayout(context)
                frame.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64f))
                return FooterHolder(frame)
            }
            val root = Lx.vbox(context)
            root.background = Lx.pressable(context, Lx.SURFACE, 8f)
            Lx.clipRounded(root, 8f)
            root.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(5f), dp(5f), dp(5f), dp(5f))
            }
            val frame = AspectFrame(context, 16f / 10f)
            val image = ImageView(context)
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.setBackgroundColor(Lx.SURFACE_HI)
            frame.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val badge = Lx.plateBadge(context, T("Installed").uppercase(), Lx.ACCENT, Lx.ACCENT_TEXT, 11f)
            frame.addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(dp(8f), dp(8f), 0, 0)
            })
            root.addView(frame)
            val body = Lx.vbox(context)
            body.setPadding(dp(12f), dp(10f), dp(12f), dp(12f))
            val name = Lx.text(context, "", 14f, Lx.TEXT, Lx.MEDIUM, 2)
            name.minLines = 2
            name.setLineSpacing(0f, 1.1f)
            body.addView(name)
            val author = Lx.caption(context, "", Lx.MUTED, 1)
            body.addView(author, Lx.lp(context, top = 6f))
            val stats = Lx.hbox(context)
            stats.addView(Lx.iconView(context, Icon.THUMB_UP, Lx.FAINT, 13f))
            val likes = Lx.caption(context, "", Lx.FAINT)
            stats.addView(likes, Lx.lp(context, -2f, -2f, start = 5f, end = 12f))
            stats.addView(Lx.iconView(context, Icon.VISIBILITY, Lx.FAINT, 13f))
            val views = Lx.caption(context, "", Lx.FAINT)
            stats.addView(views, Lx.lp(context, -2f, -2f, start = 5f))
            body.addView(stats, Lx.lp(context, top = 8f))
            root.addView(body)
            return Card(root, image, badge, name, author, likes, views)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is FooterHolder) {
                holder.root.removeAllViews()
                val view: View =
                    when (footer) {
                        Footer.LOADING, Footer.NONE -> Lx.spinner(context, 28f)
                        Footer.END -> Lx.label(context, "That's everything")
                        Footer.ERROR -> Lx.button(context, "Couldn't load more - try again", Lx.Tone.GHOST, Icon.REFRESH, compact = true) { loadMore() }
                    }
                holder.root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                return
            }
            val card = holder as Card
            val mod = mods[position]
            card.name.text = mod.name
            card.author.text = listOf(mod.author, mod.category).filter { it.isNotBlank() }.joinToString(" · ")
            card.likes.text = Lx.number(mod.likes)
            card.views.text = Lx.number(mod.views)
            card.badge.visibility = if (mod.id in installedIds) View.VISIBLE else View.GONE
            RemoteImages.bind(card.image, mod.previewUrl ?: mod.thumbnailUrl, dp(240f), null)
            card.root.setOnClickListener { host.open(ModDetailPage(host, mod)) }
        }
    }

    private enum class Footer { NONE, LOADING, END, ERROR }

    private companion object {
        const val CARD = 0
        const val FOOTER = 1
    }
}
