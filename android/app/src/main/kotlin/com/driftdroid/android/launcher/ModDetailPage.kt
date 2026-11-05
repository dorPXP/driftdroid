package com.driftdroid.android.launcher

import android.content.Intent
import android.net.Uri
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.driftdroid.android.ModManager
import com.driftdroid.android.ModePickerActivity
import com.driftdroid.android.T
import com.driftdroid.android.mods.GameBanana
import com.driftdroid.android.mods.ModInstallJob
import com.driftdroid.android.net.Http
import com.driftdroid.android.ui.AspectFrame
import com.driftdroid.android.ui.Icon
import com.driftdroid.android.ui.Lx
import com.driftdroid.android.ui.RemoteImages

/** One GameBanana mod: screenshots, details, and its files with an Install button each. Stacked in one column on a phone. */
internal class ModDetailPage(host: ModePickerActivity, private val summary: GameBanana.ModSummary) : Page(host) {
    override val title: String get() = summary.name
    override val subtitle: String get() = listOf(summary.author, summary.category).filter { it.isNotBlank() }.joinToString(" · ")

    private lateinit var frame: FrameLayout
    private var details: GameBanana.ModDetails? = null
    private val fileButtons = ArrayList<Pair<GameBanana.ModFile, LinearLayout>>()

    override fun actions(): List<View> =
        listOf(
            Lx.iconButton(context, Icon.OPEN_IN_NEW, "Open on GameBanana", Lx.MUTED, android.graphics.Color.TRANSPARENT, 44f) {
                runCatching { host.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(summary.profileUrl))) }
            },
        )

    override fun build(): View {
        frame = FrameLayout(context)
        load()
        return frame
    }

    override fun onDataChanged(what: Data) {
        if (what == Data.MODS || what == Data.INSTALL) paintFileButtons()
    }

    private fun load() {
        frame.removeAllViews()
        frame.addView(Lx.loading(context, "Loading the mod..."), match())
        host.work({ GameBanana.details(summary.id) }) { result ->
            frame.removeAllViews()
            result.fold(
                onSuccess = {
                    details = it
                    frame.addView(content(it), match())
                },
                onFailure = {
                    frame.addView(
                        Lx.emptyState(context, Icon.WARNING, "Couldn't load this mod", T("GameBanana didn't answer (%s).").format(Http.describe(it)), "Try again") { load() },
                        match(),
                    )
                },
            )
        }
    }

    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun content(mod: GameBanana.ModDetails): View {
        val column = Lx.vbox(context)
        column.setPadding(gutter, dp(4f), gutter, dp(96f))

        val side = Lx.vbox(context)
        side.addView(Lx.display(context, mod.summary.name, if (compact) 22f else 25f, Lx.TEXT, 3))
        val by = Lx.caption(context, listOf(mod.summary.author.takeIf { it.isNotBlank() }?.let { T("by %s").format(it) }, mod.summary.category.ifBlank { null }).filterNotNull().joinToString(" · "))
        side.addView(by, Lx.lp(context, top = 6f))

        val badges = Lx.hbox(context)
        mod.version?.let { badges.addView(Lx.badge(context, "v$it", Lx.CYAN), Lx.lp(context, -2f, -2f, end = 6f)) }
        if (mod.summary.id in ModManager.installedGameBananaIds(context)) badges.addView(Lx.plateBadge(context, T("Installed").uppercase(), Lx.ACCENT, Lx.ACCENT_TEXT, 11f))
        if (badges.childCount > 0) side.addView(badges, Lx.lp(context, top = 10f))

        val stats = Lx.hbox(context)
        stats.addView(Lx.stat(context, Lx.number(mod.summary.likes), "likes", Lx.TEXT, 20f), Lx.lp(context, 0f, -2f, 1f))
        stats.addView(Lx.stat(context, Lx.number(mod.summary.views), "views", Lx.TEXT, 20f), Lx.lp(context, 0f, -2f, 1f))
        stats.addView(Lx.stat(context, Lx.number(mod.downloads), "downloads", Lx.TEXT, 20f), Lx.lp(context, 0f, -2f, 1f))
        side.addView(stats, Lx.lp(context, top = 18f))

        side.addView(Lx.section(context, "Files"), Lx.lp(context, top = 24f, bottom = 10f))
        fileButtons.clear()
        if (mod.files.isEmpty()) {
            side.addView(Lx.body(context, T("This mod has no files to download here. Its GameBanana page may link to them.")))
        }
        for (file in mod.files) side.addView(fileRow(file), Lx.lp(context, bottom = 8f))
        paintFileButtons()
        side.addView(
            Lx.caption(context, T("Installed mods load the next time you start the game. Manage them under Installed."), Lx.FAINT, 3),
            Lx.lp(context, top = 4f),
        )

        if (compact) {
            column.addView(gallery(mod))
            column.addView(side, Lx.lp(context, top = 16f))
        } else {
            val top = Lx.hbox(context, Gravity.TOP)
            top.addView(gallery(mod), Lx.lp(context, 0f, -2f, 1.15f, end = 24f))
            top.addView(side, Lx.lp(context, 0f, -2f, 1f))
            column.addView(top)
        }

        val text = description(mod.html)
        if (text.isNotBlank()) {
            column.addView(Lx.section(context, "About this mod"), Lx.lp(context, top = 28f, bottom = 10f))
            val panel = Lx.panel(context, 18f)
            val body = Lx.body(context, text, Lx.TEXT)
            body.setLinkTextColor(Lx.CYAN)
            body.movementMethod = LinkMovementMethod.getInstance()
            panel.addView(body)
            column.addView(panel)
        }

        val scroll = ScrollView(context)
        scroll.addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return scroll
    }

    /** The screenshots, swiped through one at a time. */
    private fun gallery(mod: GameBanana.ModDetails): View {
        val box = Lx.vbox(context)
        val shots = mod.screenshots.ifEmpty { listOfNotNull(mod.summary.thumbnailUrl) }
        val counter = Lx.label(context, "")
        val list = RecyclerView(context)
        list.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        list.adapter =
            object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val frame = AspectFrame(context, 16f / 10f)
                    // MATCH_PARENT in a horizontal list: each screenshot is exactly one page wide.
                    frame.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    val image = ImageView(context)
                    image.scaleType = ImageView.ScaleType.CENTER_CROP
                    image.setBackgroundColor(Lx.SURFACE_HI)
                    frame.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    return object : RecyclerView.ViewHolder(frame) {}
                }

                override fun getItemCount() = shots.size

                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    val image = (holder.itemView as FrameLayout).getChildAt(0) as ImageView
                    RemoteImages.bind(image, shots[position], dp(530f), null)
                }
            }
        PagerSnapHelper().attachToRecyclerView(list)
        fun paintCounter() {
            val position = (list.layoutManager as LinearLayoutManager).findFirstCompletelyVisibleItemPosition()
            counter.text = if (shots.size > 1 && position >= 0) T("%s / %s · swipe for more").format(position + 1, shots.size).uppercase() else ""
        }
        list.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) paintCounter()
                }
            },
        )
        val clip = FrameLayout(context)
        clip.background = Lx.rounded(context, Lx.SURFACE, 8f)
        Lx.clipRounded(clip, 8f)
        clip.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (shots.isEmpty()) {
            val empty = AspectFrame(context, 16f / 10f)
            empty.addView(Lx.iconView(context, Icon.IMAGE, Lx.FAINT, 40f), FrameLayout.LayoutParams(dp(40f), dp(40f), Gravity.CENTER))
            clip.addView(empty)
        }
        box.addView(clip)
        box.addView(counter, Lx.lp(context, top = 8f))
        list.post { paintCounter() }
        return box
    }

    private fun fileRow(file: GameBanana.ModFile): View {
        val row = Lx.hbox(context)
        row.background = Lx.rounded(context, Lx.SURFACE, 8f)
        row.setPadding(dp(14f), dp(12f), dp(12f), dp(12f))
        row.addView(Lx.iconView(context, if (file.isArchive) Icon.FOLDER else Icon.INFO, if (file.isArchive) Lx.MUTED else Lx.FAINT, 22f))
        val column = Lx.vbox(context)
        column.addView(Lx.text(context, file.name, 14.5f, Lx.TEXT, Lx.MEDIUM, 2))
        val detail =
            listOfNotNull(Lx.fileSize(file.size).takeIf { file.size > 0 }, file.description?.takeIf { it.isNotBlank() })
                .joinToString(" · ")
                .ifEmpty { file.extension.uppercase() }
        column.addView(Lx.caption(context, detail, Lx.MUTED, 2), Lx.lp(context, top = 3f))
        row.addView(column, Lx.lp(context, 0f, -2f, 1f, start = 14f, end = 8f))
        val actions = Lx.hbox(context)
        row.addView(actions)
        fileButtons += file to actions
        return row
    }

    /** Install / Installing... / Install again / can't, depending on what's going on. */
    private fun paintFileButtons() {
        val mod = details ?: return
        val installed = mod.summary.id in ModManager.installedGameBananaIds(context)
        val busy = ModInstallJob.running
        val thisBusy = busy && ModInstallJob.modId == mod.summary.id
        for ((file, actions) in fileButtons) {
            actions.removeAllViews()
            if (!file.isArchive) {
                actions.addView(Lx.badge(context, T(".%s not supported").format(file.extension.ifEmpty { "?" }), Lx.FAINT))
                continue
            }
            val label =
                when {
                    thisBusy -> "Installing..."
                    installed -> "Install again"
                    else -> "Install"
                }
            val button = Lx.button(context, label, if (installed) Lx.Tone.SECONDARY else Lx.Tone.PRIMARY, Icon.DOWNLOAD, compact = true) { install(mod, file) }
            Lx.setEnabled(button, !busy)
            actions.addView(button)
        }
    }

    private fun install(mod: GameBanana.ModDetails, file: GameBanana.ModFile) {
        if (!ModInstallJob.start(context, mod, file)) {
            host.toast(T("Another mod is still installing. Try again when it's done."), Lx.Tone.INFO)
            return
        }
        host.watchModInstall()
        paintFileButtons()
    }

    /** GameBanana's HTML description as styled text, without the pictures (they'd show as boxes). */
    private fun description(html: String): CharSequence {
        if (html.isBlank()) return ""
        val cleaned = html
            .replace(Regex("(?is)<img[^>]*>"), "")
            .replace(Regex("(?is)<iframe.*?</iframe>"), "")
            .replace(Regex("(?is)<script.*?</script>"), "")
        return Html.fromHtml(cleaned, Html.FROM_HTML_MODE_COMPACT).trim()
    }
}
