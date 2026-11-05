package com.driftdroid.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import com.driftdroid.android.launcher.HomePage
import com.driftdroid.android.launcher.LeaderboardPage
import com.driftdroid.android.launcher.ModsPage
import com.driftdroid.android.launcher.Page
import com.driftdroid.android.launcher.ProfilePage
import com.driftdroid.android.launcher.RoomsPage
import com.driftdroid.android.launcher.SettingsPage
import com.driftdroid.android.launcher.Tab
import com.driftdroid.android.mods.ModInstallJob
import com.driftdroid.android.net.Http
import com.driftdroid.android.ui.DriftSheet
import com.driftdroid.android.ui.DriftUi
import com.driftdroid.android.ui.Icon
import com.driftdroid.android.ui.IconDrawable
import com.driftdroid.android.ui.LauncherBackground
import com.driftdroid.android.ui.Lx
import com.driftdroid.android.ui.Theme
import com.driftdroid.android.ui.RemoteImages
import com.driftdroid.android.ui.SheetPalette
import com.driftdroid.android.ui.SheetThemed
import com.driftdroid.android.ui.SlantDrawable
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The app's actual launcher (see AndroidManifest.xml). Chooses Original Mario Kart Wii vs. Retro
 * Rewind BEFORE any guest engine state exists, since neither the combined native library
 * (libGameCombined.so - both products, see runtime/cmake/build_combined_android_lib.py) nor a
 * running game has a supported way to reset and switch profiles live - MainActivity.EXTRA_PRODUCT
 * tells it which one nativeSetActiveProduct() should select.
 *
 * It is also the home of everything around the game: Home, Mods, Rooms, Ranks, Profile and
 * Settings (the pages in com.driftdroid.android.launcher), behind a bottom tab bar on a phone held
 * upright or a navigation rail on a tablet or handheld held sideways (LauncherOrientation picks
 * which). A header carries the page's title and actions, detail pages are pushed on top with a back
 * arrow, and a task bar for long jobs (Retro Rewind installs, mod downloads, imports) keeps
 * following a job across pages.
 *
 * Retro Rewind's pack (tracks/textures, ~2GB - distinct from Code.pul, which only carries the
 * mod's patched game LOGIC and is already compiled into libGameCombined.so) is downloaded from
 * Retro Rewind's own server by RetroRewindInstallService, or taken from a copy the player already
 * has: the .zip file exactly as downloaded, or an already-extracted folder.
 *
 * File pickers and anything else that hands Android's own UI the screen stay here rather than in
 * the in-game sidebar: launching one while a live game session's GPU surface is running lost the
 * WebGPU/Vulkan surface on-device ("QueuePresent failed with VK_ERROR_SURFACE_LOST_KHR") and
 * crashed the game. No such surface exists on this screen.
 */
class ModePickerActivity : Activity(), SheetThemed {

    override val sheetPalette: SheetPalette get() = SheetPalette.LAUNCHER

    private val mainHandler = Handler(Looper.getMainLooper())
    private val background = Executors.newCachedThreadPool { r -> Thread(r, "launcher-work").apply { isDaemon = true } }

    /** Phone layout: one column with a bottom tab bar, instead of a rail beside the pages. */
    internal var compact = false
        private set

    private val navItems = HashMap<Tab, NavItem>()
    private var rail: View? = null
    private lateinit var backButton: View
    private lateinit var settingsButton: View
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var titlesBox: View
    private lateinit var brandBox: LinearLayout
    private lateinit var brandArt: ImageView
    private lateinit var actionsBox: LinearLayout
    private lateinit var pageHost: FrameLayout
    private lateinit var taskBar: LinearLayout
    private lateinit var taskTitle: TextView
    private lateinit var taskDetail: TextView
    private lateinit var taskProgress: ProgressBar
    private lateinit var taskCancel: View
    private lateinit var snack: LinearLayout
    private lateinit var snackEdge: View
    private lateinit var snackText: TextView

    private val tabPages = HashMap<Tab, Page>()
    private val stack = ArrayList<Page>()
    private val actionViews = HashMap<Page, List<View>>()
    private var currentTab = Tab.HOME
    private var previousTab = Tab.HOME
    private var shown: Page? = null
    private var paused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Portrait on phones, landscape on tablets and handhelds, or the player's choice. When this
        // turns the screen, Android starts the Activity again the right way up straight away.
        requestedOrientation = LauncherOrientation.requested(this)
        I18n.init(this)
        Theme.load(this)
        Http.userAgent = "DriftDroid/${appVersionName()} (Android ${Build.VERSION.RELEASE})"
        RemoteImages.init(this)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        // Draw the backdrop under a camera cutout too (no black band beside or above it); the
        // shell keeps its content clear of the cutout itself.
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.setDecorFitsSystemWindows(false)
        compact = Lx.isCompact(this)
        buildShell()
        val tab = savedInstanceState?.getString(STATE_TAB)?.let { name -> Tab.values().firstOrNull { it.name == name } } ?: Tab.HOME
        navigate(tab)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAB, currentTab.name)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        // Coming back from the game or a picker: the page refreshes what may have changed.
        if (paused) currentPage()?.let { if (it === shown) it.onShow() }
        paused = false
        // An install may still be running in RetroRewindInstallService from a previous instance
        // of this Activity (e.g. this one is a fresh recreation after Android reclaimed the old
        // one while backgrounded) - pick up its live progress instead of showing an idle screen
        // over a copy that's actually still going.
        if (RetroRewindInstallStatus.running) trackingRetroInstall = true
        if (ModInstallJob.running) watchModInstall()
        startTaskPolling()
    }

    override fun onPause() {
        super.onPause()
        paused = true
        stopTaskPolling()
        shown?.onHide()
    }

    override fun onDestroy() {
        super.onDestroy()
        (stack + tabPages.values).forEach { it.onClose() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Full screen like the game itself; a swipe from the edge brings the system bars back briefly. */
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            // Android 10 has no WindowInsetsController.
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            stack.isNotEmpty() -> back()
            currentTab == Tab.SETTINGS && compact -> navigate(previousTab)
            currentTab != Tab.HOME -> navigate(Tab.HOME)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    private fun dp(value: Float): Int = Lx.dp(this, value)

    // --- Shell ---

    /** One destination in the tab bar or rail: its icon on a slanted green plate when picked, and a label. */
    private inner class NavItem(val tab: Tab) {
        val root: LinearLayout = Lx.vbox(this@ModePickerActivity)
        private val icon = ImageView(this@ModePickerActivity)
        private val label = Lx.text(this@ModePickerActivity, T(tab.label).uppercase(), 10.5f, Lx.FAINT, Lx.CONDENSED, 1)

        init {
            root.gravity = Gravity.CENTER
            val p = dp(15f)
            icon.setPadding(p, dp(4f), p, dp(4f))
            root.addView(icon, Lx.lp(this@ModePickerActivity, 56f, 30f))
            label.letterSpacing = 0.08f
            root.addView(label, Lx.lp(this@ModePickerActivity, -2f, -2f, top = 4f))
            root.background = Lx.pressable(this@ModePickerActivity, android.graphics.Color.TRANSPARENT, 8f)
            root.contentDescription = T(tab.label)
            root.setOnClickListener {
                UiSounds.playClick(this@ModePickerActivity)
                navigate(tab)
            }
        }

        fun paint(selected: Boolean) {
            icon.setImageDrawable(IconDrawable(tab.icon, if (selected) Lx.ACCENT_TEXT else Lx.MUTED))
            icon.background = if (selected) SlantDrawable(Lx.ACCENT, 0, 0f, dp(3f).toFloat()) else null
            label.setTextColor(if (selected) Lx.TEXT else Lx.FAINT)
            root.isSelected = selected
        }
    }

    private fun buildShell() {
        val root = FrameLayout(this)
        root.background = LauncherBackground(this, compact)

        pageHost = FrameLayout(this)
        val header = buildHeader()
        if (compact) {
            val column = Lx.vbox(this)
            column.addView(header)
            column.addView(pageHost, Lx.lp(this, -1f, 0f, 1f))
            val bar = Lx.hbox(this, Gravity.CENTER)
            bar.setBackgroundColor(Lx.BAR)
            for (tab in Tab.values().filter { it != Tab.SETTINGS }) {
                val item = NavItem(tab)
                navItems[tab] = item
                bar.addView(item.root, Lx.lp(this, 0f, -1f, 1f))
            }
            column.addView(Lx.divider(this))
            column.addView(bar, Lx.lp(this, -1f, BOTTOM_BAR_DP))
            root.addView(column, Lx.match())
        } else {
            val row = Lx.hbox(this, Gravity.TOP)
            val rail = Lx.vbox(this)
            rail.gravity = Gravity.CENTER_HORIZONTAL
            // A phone held sideways is only ~400dp tall: tighter items so all six and the icon fit
            // without the rail scrolling Settings out of sight.
            val short = resources.configuration.screenHeightDp < 480
            rail.setPadding(0, dp(if (short) 6f else 12f), 0, dp(if (short) 6f else 12f))
            val mark = ImageView(this)
            mark.setImageResource(R.mipmap.ic_launcher)
            mark.contentDescription = getString(R.string.app_name)
            rail.addView(mark, Lx.lp(this, if (short) 42f else 56f, if (short) 42f else 56f, bottom = if (short) 4f else 10f))
            for (tab in Tab.values()) {
                val item = NavItem(tab)
                navItems[tab] = item
                if (tab == Tab.SETTINGS) rail.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
                rail.addView(item.root, Lx.lp(this, 80f, if (short) 56f else 62f, bottom = if (short) 0f else 2f))
            }
            val railScroll = ScrollView(this)
            railScroll.isVerticalScrollBarEnabled = false
            railScroll.isFillViewport = true
            railScroll.setBackgroundColor(Lx.BAR)
            railScroll.addView(rail, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(railScroll, LinearLayout.LayoutParams(dp(RAIL_DP), ViewGroup.LayoutParams.MATCH_PARENT))
            row.addView(View(this).apply { setBackgroundColor(Lx.LINE) }, LinearLayout.LayoutParams(dp(1f), ViewGroup.LayoutParams.MATCH_PARENT))
            val main = Lx.vbox(this)
            main.addView(header)
            main.addView(pageHost, Lx.lp(this, -1f, 0f, 1f))
            row.addView(main, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            root.addView(row, Lx.match())
            this.rail = railScroll
        }

        buildTaskBar(root)
        buildSnack(root)
        // Keep content clear of a camera cutout. Sideways, a cutout on the rail's side widens the
        // rail instead, so the rail runs right to the screen's edge.
        root.setOnApplyWindowInsetsListener { view, insets ->
            val cutout = insets.displayCutout
            val left = cutout?.safeInsetLeft ?: 0
            val railView = rail
            if (railView != null) {
                railView.setPadding(left, 0, 0, 0)
                railView.layoutParams = railView.layoutParams.apply { width = dp(RAIL_DP) + left }
                view.setPadding(0, cutout?.safeInsetTop ?: 0, cutout?.safeInsetRight ?: 0, cutout?.safeInsetBottom ?: 0)
            } else {
                view.setPadding(left, cutout?.safeInsetTop ?: 0, cutout?.safeInsetRight ?: 0, cutout?.safeInsetBottom ?: 0)
            }
            insets
        }
        setContentView(root)
    }

    private fun buildHeader(): View {
        val header = Lx.hbox(this)
        header.setPadding(dp(if (compact) 8f else 16f), dp(8f), dp(if (compact) 8f else 20f), dp(8f))
        header.minimumHeight = dp(if (compact) 60f else 72f)
        backButton = Lx.iconButton(this, Icon.BACK, "Back", Lx.TEXT, android.graphics.Color.TRANSPARENT, 44f) { goBack() }
        header.addView(backButton, Lx.lp(this, 44f, 44f, end = 4f))

        // Home's header is the brand: the app icon (the rail already shows it in landscape) and the wordmark.
        brandBox = Lx.hbox(this)
        if (compact) {
            val mark = ImageView(this)
            mark.setImageResource(R.mipmap.ic_launcher)
            brandBox.addView(mark, Lx.lp(this, 44f, 44f, end = 6f))
        }
        brandArt = ImageView(this)
        brandArt.scaleType = ImageView.ScaleType.FIT_START
        brandArt.adjustViewBounds = true
        brandBox.addView(brandArt, Lx.lp(this, -2f, if (compact) 30f else 40f))
        header.addView(brandBox, Lx.lp(this, 0f, -2f, 1f, start = if (compact) 4f else 8f))

        val titles = Lx.vbox(this)
        titleView = Lx.display(this, "", if (compact) 23f else 27f, Lx.TEXT, 1)
        subtitleView = Lx.caption(this, "", Lx.MUTED, 1)
        titles.addView(titleView)
        titles.addView(subtitleView, Lx.lp(this, top = 3f))
        titlesBox = titles
        header.addView(titles, Lx.lp(this, 0f, -2f, 1f, start = 8f, end = 8f))

        actionsBox = Lx.hbox(this)
        header.addView(actionsBox)
        settingsButton = Lx.iconButton(this, Icon.SETTINGS, "Settings", Lx.MUTED, android.graphics.Color.TRANSPARENT, 44f) {
            UiSounds.playClick(this)
            navigate(Tab.SETTINGS)
        }
        header.addView(settingsButton, Lx.lp(this, 44f, 44f, start = 4f))
        return header
    }

    private fun buildTaskBar(root: FrameLayout) {
        taskBar = Lx.hbox(this)
        taskBar.background = Lx.rounded(this, Lx.SURFACE_HI, 8f)
        Lx.clipRounded(taskBar, 8f)
        taskBar.elevation = dp(10f).toFloat()
        taskBar.addView(View(this).apply { setBackgroundColor(Lx.ACCENT) }, LinearLayout.LayoutParams(dp(4f), ViewGroup.LayoutParams.MATCH_PARENT))
        val taskText = Lx.vbox(this)
        taskTitle = Lx.text(this, "", 14.5f, Lx.TEXT, Lx.CONDENSED, 1)
        taskDetail = Lx.caption(this, "", Lx.MUTED, 2)
        taskProgress = Lx.progressBar(this)
        taskText.addView(taskTitle)
        taskText.addView(taskDetail, Lx.lp(this, top = 3f))
        taskText.addView(taskProgress, Lx.lp(this, -1f, 6f, top = 8f))
        taskBar.addView(taskText, Lx.lp(this, 0f, -2f, 1f, start = 14f, top = 12f, end = 8f, bottom = 12f))
        taskCancel = Lx.iconButton(this, Icon.CLOSE, "Cancel", Lx.MUTED, android.graphics.Color.TRANSPARENT, 40f) { ModInstallJob.cancel() }
        taskBar.addView(taskCancel, Lx.lp(this, 40f, 40f, end = 6f))
        taskBar.visibility = View.GONE
        val params =
            if (compact) {
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                    setMargins(dp(12f), 0, dp(12f), dp(BOTTOM_BAR_DP + 10f))
                }
            } else {
                FrameLayout.LayoutParams(dp(380f), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
                    setMargins(0, 0, dp(20f), dp(18f))
                }
            }
        root.addView(taskBar, params)
    }

    private fun buildSnack(root: FrameLayout) {
        snack = Lx.hbox(this)
        snack.background = Lx.rounded(this, 0xF51E2229.toInt(), 8f)
        Lx.clipRounded(snack, 8f)
        snack.elevation = dp(12f).toFloat()
        snackEdge = View(this)
        snack.addView(snackEdge, LinearLayout.LayoutParams(dp(4f), ViewGroup.LayoutParams.MATCH_PARENT))
        snackText = Lx.text(this, "", 14f, Lx.TEXT, Lx.MEDIUM, 3)
        snackText.setLineSpacing(0f, 1.15f)
        snack.addView(snackText, Lx.lp(this, -2f, -2f, start = 14f, top = 12f, end = 16f, bottom = 12f))
        snack.visibility = View.GONE
        root.addView(snack, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            if (compact) setMargins(dp(16f), 0, dp(16f), dp(BOTTOM_BAR_DP + 12f)) else setMargins(dp(RAIL_DP + 24f), 0, dp(24f), dp(22f))
        })
    }

    private fun pageFor(tab: Tab): Page =
        tabPages.getOrPut(tab) {
            when (tab) {
                Tab.HOME -> HomePage(this)
                Tab.MODS -> ModsPage(this)
                Tab.ROOMS -> RoomsPage(this)
                Tab.RANKS -> LeaderboardPage(this)
                Tab.PROFILE -> ProfilePage(this)
                Tab.SETTINGS -> SettingsPage(this)
            }
        }

    private fun currentPage(): Page? = stack.lastOrNull() ?: tabPages[currentTab]

    internal fun isShowing(page: Page): Boolean = shown === page && !isFinishing

    /** Switches to a top-level page, closing any detail pages that were open. */
    internal fun navigate(tab: Tab) {
        while (stack.isNotEmpty()) stack.removeAt(stack.size - 1).also { if (it === shown) it.onHide() }.onClose()
        if (tab != currentTab && currentTab != Tab.SETTINGS) previousTab = currentTab
        currentTab = tab
        for ((t, item) in navItems) item.paint(t == tab)
        show(pageFor(tab))
    }

    /** Pushes a detail page on top of the current one. */
    internal fun open(page: Page) {
        stack.add(page)
        show(page)
    }

    /** The header's back arrow: a detail page closes; Settings (opened from the gear) returns to where it was opened. */
    private fun goBack() {
        if (stack.isNotEmpty()) back() else if (currentTab == Tab.SETTINGS) navigate(previousTab)
    }

    /** Closes the top detail page. [force] skips the page's own say (unsaved changes, say). */
    internal fun back(force: Boolean = false) {
        val top = stack.lastOrNull() ?: return
        if (!force && top.onBack()) return
        stack.removeAt(stack.size - 1)
        top.onHide()
        top.onClose()
        actionViews.remove(top)
        show(currentPage() ?: pageFor(currentTab))
    }

    private fun show(page: Page) {
        val previous = shown
        if (previous != null && previous !== page && (previous in stack || tabPages.containsValue(previous))) previous.onHide()
        shown = page
        pageHost.removeAllViews()
        val view = page.view
        (view.parent as? ViewGroup)?.removeView(view)
        pageHost.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val detail = stack.isNotEmpty()
        // Detail pages slide in from the side; top-level pages rise a little.
        view.alpha = 0f
        view.translationX = if (detail && page === stack.last()) dp(16f).toFloat() else 0f
        view.translationY = if (detail) 0f else dp(8f).toFloat()
        view.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(170).start()
        backButton.visibility = if (detail || (compact && currentTab == Tab.SETTINGS)) View.VISIBLE else View.GONE
        settingsButton.visibility = if (compact && !detail && currentTab != Tab.SETTINGS) View.VISIBLE else View.GONE
        actionsBox.removeAllViews()
        for (action in actionViews.getOrPut(page) { page.actions() }) {
            (action.parent as? ViewGroup)?.removeView(action)
            actionsBox.addView(action)
        }
        refreshHeader(page)
        page.onShow()
    }

    internal fun refreshHeader(page: Page) {
        if (page !== shown) return
        titleView.text = page.title
        val sub = page.subtitle
        subtitleView.text = sub ?: ""
        subtitleView.visibility = if (sub.isNullOrEmpty()) View.GONE else View.VISIBLE
        val art = page.headerArt
        if (art != null) brandArt.setImageResource(art)
        brandBox.contentDescription = page.title
        brandBox.visibility = if (art != null) View.VISIBLE else View.GONE
        titlesBox.visibility = if (art != null) View.GONE else View.VISIBLE
    }

    /** Tells every open page that [what] changed. */
    internal fun broadcast(what: Page.Data) {
        (tabPages.values + stack).forEach { it.onDataChanged(what) }
        shown?.let { refreshHeader(it) }
    }

    private val hideSnack = Runnable { snack.animate().alpha(0f).translationY(dp(8f).toFloat()).setDuration(200).withEndAction { snack.visibility = View.GONE }.start() }

    /** A short message over the bottom of the screen, edged in the colour of what it reports. */
    internal fun toast(message: String, tone: Lx.Tone = Lx.Tone.INFO) {
        val color =
            when (tone) {
                Lx.Tone.PRIMARY -> Lx.ACCENT
                Lx.Tone.DANGER -> Lx.DANGER
                else -> Lx.CYAN
            }
        snackText.text = message
        snackEdge.setBackgroundColor(color)
        snack.visibility = View.VISIBLE
        snack.alpha = 0f
        snack.translationY = dp(8f).toFloat()
        snack.animate().alpha(1f).translationY(0f).setDuration(160).start()
        mainHandler.removeCallbacks(hideSnack)
        mainHandler.postDelayed(hideSnack, if (tone == Lx.Tone.DANGER) 6000L else 3800L)
    }

    /** Runs [task] off the main thread and hands its result back on it (unless the screen is gone). */
    internal fun <T> work(task: () -> T, done: (Result<T>) -> Unit) {
        background.execute {
            val result = runCatching(task)
            mainHandler.post { if (!isDestroyed) done(result) }
        }
    }

    /** A job long enough to show in the task bar: [task] can report a line of progress as it goes. */
    internal fun <T> task(label: String, task: ((String) -> Unit) -> T, done: (Result<T>) -> Unit) {
        val job = LocalTask(label)
        localTasks.add(job)
        startTaskPolling()
        background.execute {
            val result = runCatching { task { job.detail = it } }
            mainHandler.post {
                localTasks.remove(job)
                paintTaskBar()
                if (!isDestroyed) done(result)
            }
        }
    }

    internal fun isGameLoaded(): Boolean = MainActivity.isGameLoaded

    /** Runs [block], after a warning when the game is still loaded and could overwrite the change. */
    internal fun guardGameRunning(block: () -> Unit) {
        if (!isGameLoaded()) {
            block()
            return
        }
        DriftSheet.confirm(
            this,
            "The game is still open",
            "It's running in the background and may write its own copy of this file back when it saves. To be safe, " +
                "close DriftDroid from Recents, reopen it and make the change then.",
            "Change it anyway",
            cancelLabel = "Not now",
        ) { block() }
    }

    // --- Pickers for pages ---

    private var pendingOpen: ((Uri?) -> Unit)? = null
    private var pendingCreate: ((Uri?) -> Unit)? = null

    internal fun openDocument(callback: (Uri?) -> Unit) {
        pendingOpen = callback
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        // Not restricted by MIME type: many file managers hand files over with a generic or
        // missing type, so each caller checks the file itself.
        intent.type = "*/*"
        startActivityForResult(intent, REQUEST_CODE_OPEN_DOCUMENT)
    }

    internal fun createDocument(mime: String, name: String, callback: (Uri?) -> Unit) {
        pendingCreate = callback
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = mime
        intent.putExtra(Intent.EXTRA_TITLE, name)
        startActivityForResult(intent, REQUEST_CODE_CREATE_DOCUMENT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = if (resultCode == RESULT_OK) data?.data else null
        when (requestCode) {
            REQUEST_CODE_OPEN_DOCUMENT -> pendingOpen.also { pendingOpen = null }?.invoke(uri)
            REQUEST_CODE_CREATE_DOCUMENT -> pendingCreate.also { pendingCreate = null }?.invoke(uri)
            REQUEST_CODE_PICK_RETRO_REWIND_FOLDER -> handleFolderPicked(data?.data)
            REQUEST_CODE_PICK_RETRO_REWIND_ZIP -> handleZipPicked(data?.data)
            SaveData.REQUEST_CODE_EXPORT -> SaveData.onExportDestinationPicked(this, data?.data)
            SaveData.REQUEST_CODE_IMPORT -> SaveData.onImportSourcePicked(this, data?.data)
            GpuDriverSettings.REQUEST_CODE_PICK_ZIP -> GpuDriverSettings.onZipPicked(this, data?.data)
            IconSettings.REQUEST_CODE_PICK_IMAGE -> IconSettings.onImagePicked(this, data?.data)
        }
    }

    // --- Task bar ---

    private class LocalTask(val label: String) {
        @Volatile var detail: String = ""
    }

    private val localTasks = ArrayList<LocalTask>()
    private var trackingRetroInstall = false
    private var pendingRetroLabel: String? = null
    private var watchingModInstall = false
    private var seenModFinishes = ModInstallJob.finishedCount
    private var taskPoller: Runnable? = null

    internal fun watchModInstall() {
        if (!watchingModInstall) seenModFinishes = ModInstallJob.finishedCount
        watchingModInstall = true
        startTaskPolling()
    }

    private fun startTaskPolling() {
        if (taskPoller != null) return
        val poller =
            object : Runnable {
                override fun run() {
                    pollTasks()
                    if (trackingRetroInstall || watchingModInstall || localTasks.isNotEmpty()) {
                        mainHandler.postDelayed(this, 400)
                    } else {
                        taskPoller = null
                    }
                }
            }
        taskPoller = poller
        mainHandler.post(poller)
    }

    private fun stopTaskPolling() {
        taskPoller?.let { mainHandler.removeCallbacks(it) }
        taskPoller = null
    }

    private fun pollTasks() {
        if (trackingRetroInstall && RetroRewindInstallStatus.finished && !RetroRewindInstallStatus.running) {
            trackingRetroInstall = false
            pendingRetroLabel = null
            finishRetroInstall(RetroRewindInstallStatus.error, RetroRewindInstallStatus.filesDone)
        }
        if (watchingModInstall && !ModInstallJob.running && ModInstallJob.finishedCount != seenModFinishes) {
            watchingModInstall = false
            seenModFinishes = ModInstallJob.finishedCount
            if (ModInstallJob.stage == ModInstallJob.Stage.DONE) {
                toast(T("Installed \"%s\". It loads the next time you start the game.").format(ModInstallJob.title), Lx.Tone.PRIMARY)
            } else if (ModInstallJob.error != "cancelled") {
                toast(T("Couldn't install \"%s\": %s").format(ModInstallJob.title, ModInstallJob.error ?: "unknown error"), Lx.Tone.DANGER)
            }
            broadcast(Page.Data.MODS)
        }
        paintTaskBar()
    }

    private fun paintTaskBar() {
        val local = localTasks.lastOrNull()
        when {
            trackingRetroInstall -> {
                val total = RetroRewindInstallStatus.totalBytes
                taskTitle.text = T("Retro Rewind")
                taskDetail.text =
                    when {
                        !RetroRewindInstallStatus.running -> pendingRetroLabel ?: T("Starting...")
                        total > 0 -> T("%s... %s of %s").format(RetroRewindInstallStatus.label, Lx.fileSize(RetroRewindInstallStatus.downloadedBytes), Lx.fileSize(total))
                        else -> T("%s... %s files done").format(RetroRewindInstallStatus.label, RetroRewindInstallStatus.filesDone)
                    }
                setProgress(if (total > 0) RetroRewindInstallStatus.downloadedBytes.toFloat() / total else -1f)
                taskCancel.visibility = View.GONE
                taskBar.visibility = View.VISIBLE
            }
            ModInstallJob.running -> {
                taskTitle.text = ModInstallJob.title
                val total = ModInstallJob.bytesTotal
                if (ModInstallJob.stage == ModInstallJob.Stage.DOWNLOADING) {
                    taskDetail.text =
                        if (total > 0) T("Downloading... %s of %s").format(Lx.fileSize(ModInstallJob.bytesDone), Lx.fileSize(total))
                        else T("Downloading... %s").format(Lx.fileSize(ModInstallJob.bytesDone))
                    setProgress(if (total > 0) ModInstallJob.bytesDone.toFloat() / total else -1f)
                    taskCancel.visibility = View.VISIBLE
                } else {
                    taskDetail.text = T("Unpacking... %s files").format(ModInstallJob.filesUnpacked)
                    setProgress(-1f)
                    taskCancel.visibility = View.GONE
                }
                taskBar.visibility = View.VISIBLE
            }
            local != null -> {
                taskTitle.text = local.label
                taskDetail.text = local.detail.ifEmpty { T("Working...") }
                setProgress(-1f)
                taskCancel.visibility = View.GONE
                taskBar.visibility = View.VISIBLE
            }
            else -> taskBar.visibility = View.GONE
        }
    }

    private fun setProgress(fraction: Float) {
        if (fraction < 0f) {
            taskProgress.isIndeterminate = true
        } else {
            taskProgress.isIndeterminate = false
            taskProgress.progress = (fraction.coerceIn(0f, 1f) * 1000).toInt()
        }
    }

    // --- The games ---

    internal fun isRomImported(): Boolean =
        com.driftdroid.android.rom.RomImportOverlay.isRomAlreadyImported(File(filesDir, "WiiCompiled/DiscData"))

    internal fun isRetroRewindInstalled(): Boolean = File(retroRewindRoot(), "Binaries/Code.pul").isFile

    internal fun retroRewindVersion(): String? = RetroRewindCatalog.installedVersion(retroRewindRoot())

    /** A game pack is in and it was built without Retro Rewind. */
    internal fun packWithoutRetroRewind(): Boolean =
        GamePack.state(this) == GamePack.State.Ready && !GamePack.hasRetroRewind(this)

    private fun retroRewindRoot(): File = File(filesDir, MainActivity.RETRO_REWIND_STAGING_SUBDIR)

    /** Plays the original game. Without the disc's data yet, MainActivity asks for the image first. */
    internal fun playBase() {
        UiSounds.playClick(this)
        launchProduct(MainActivity.PRODUCT_BASE)
    }

    internal fun playRetroRewind() {
        UiSounds.playClick(this)
        if (packWithoutRetroRewind()) {
            DriftSheet.message(
                this,
                "Retro Rewind isn't in your game pack",
                "Your game pack was made without Retro Rewind. To play it, run DriftDroid Builder again " +
                    "with \"Retro Rewind\" ticked and import the new game pack.",
            )
        } else if (isRetroRewindInstalled()) {
            launchRetroRewindOrUpdate()
        } else {
            showInstallRetroRewindDialog()
        }
    }

    internal fun showRetroRewindMenu() {
        val sheet = DriftSheet(this, "Retro Rewind", retroRewindVersion()?.let { T("Installed: %s").format(it) })
        sheet.action("Check for updates", "Downloads only what changed") {
            sheet.dismiss()
            startDeltaUpdate()
        }
        sheet.action("Reinstall", "Download it again, or pick a copy you have") {
            sheet.dismiss()
            showInstallRetroRewindDialog()
        }
        sheet.action("Delete", "Frees about 2 GB") {
            sheet.dismiss()
            showDeleteRetroRewindDialog()
        }
        sheet.show()
    }

    private fun showDeleteRetroRewindDialog() {
        DriftSheet.confirm(
            this,
            "Delete Retro Rewind?",
            "This removes the installed Retro Rewind files from the app. You'll need to download or import " +
                "them again to play. Your saves are kept.",
            "Delete",
            danger = true,
        ) {
            retroRewindRoot().deleteRecursively()
            broadcast(Page.Data.INSTALL)
            toast(T("Retro Rewind deleted."))
        }
    }

    /**
     * Starts Retro Rewind - unless the installed copy holds game code this build isn't compiled
     * for, which is what an install made by an older DriftDroid looks like after the app has been
     * updated for a newer Retro Rewind. Launching that would misbehave or crash, so it is brought
     * up to date first (the small per-version updates, not a new 1.9GB download) and the player
     * taps again once that has finished.
     */
    private fun launchRetroRewindOrUpdate() {
        val root = retroRewindRoot()
        work({ RetroRewindCatalog.isSupportedCodePul(RetroRewindCatalog.installedCodePulSha256(root)) }) { result ->
            if (isFinishing) return@work
            if (result.getOrDefault(false)) {
                launchProduct(MainActivity.PRODUCT_RETRO_REWIND)
            } else {
                val installed = RetroRewindCatalog.installedVersion(root)
                beginRetroInstall(
                    T("Retro Rewind %s needs updating for this version of DriftDroid - updating it now...").format(installed ?: ""),
                )
                RetroRewindInstallService.startDeltaUpdate(this)
            }
        }
    }

    private fun launchProduct(product: String) {
        // The game code is either inside the APK or comes from a game pack made on a PC from the
        // player's disc (GamePack.kt). Without either, the player is told how to get one.
        if (GamePack.expectedToolkitId(this) != null && !GamePack.hasBundledCode(this) &&
            GamePack.state(this) != GamePack.State.Ready
        ) {
            showGamePackNeededDialog(GamePack.state(this) == GamePack.State.WrongVersion)
            return
        }
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra(MainActivity.EXTRA_PRODUCT, product)
        startActivity(intent)
    }

    /** Three ways in: download the newest release this build can run, or a copy the player has. */
    private fun showInstallRetroRewindDialog() {
        val sheet = DriftSheet(this, T("Install Retro Rewind"), T("A free community mod with 300+ tracks"))
        sheet.note(
            "The download takes the newest Retro Rewind release this DriftDroid can run, straight from " +
                "Retro Rewind's own server, and checks it before installing. Or pick a copy you already " +
                "have (from the Wheel Wizard tool, for example).",
        )
        sheet.action("Download it", "About 1.9 GB. Wi-Fi recommended") {
            sheet.dismiss()
            beginRetroInstall(T("Checking for the newest Retro Rewind..."))
            RetroRewindInstallService.startLatestInstall(this)
        }
        sheet.action("Pick a .zip I have", "The file exactly as downloaded") {
            sheet.dismiss()
            launchRetroRewindZipPicker()
        }
        sheet.action("Pick an unzipped folder", "A folder with RetroRewind6 inside") {
            sheet.dismiss()
            launchRetroRewindFolderPicker()
        }
        sheet.show()
    }

    /** Applies the mod's own small per-version update archives to the existing install - a few MB
     * rather than another ~1.9GB. */
    private fun startDeltaUpdate() {
        beginRetroInstall(T("Checking for Retro Rewind updates..."))
        RetroRewindInstallService.startDeltaUpdate(this)
    }

    /** The install itself runs in [RetroRewindInstallService] (a foreground service that outlives
     * this Activity); the task bar follows [RetroRewindInstallStatus] until it finishes. */
    private fun beginRetroInstall(label: String) {
        pendingRetroLabel = label
        trackingRetroInstall = true
        // A finished flag left over from an earlier run must not end this one before it starts.
        RetroRewindInstallStatus.finished = false
        broadcast(Page.Data.INSTALL)
        startTaskPolling()
    }

    private fun finishRetroInstall(error: String?, filesCount: Int) {
        if (error == null) {
            toast(RetroRewindInstallStatus.successMessage ?: T("Retro Rewind installed (%s files).").format(filesCount), Lx.Tone.PRIMARY)
        } else {
            // Leave no half-copied install behind - isRetroRewindInstalled() only checks for
            // Code.pul, and a partial copy that happened to include it would then look
            // "installed" while actually missing tracks/assets. Only when the install really was
            // being rebuilt, though: a refused version or an update check that couldn't finish
            // has not touched the working install, and deleting it then cost the player 1.9GB.
            if (RetroRewindInstallStatus.installIncomplete) {
                retroRewindRoot().deleteRecursively()
            }
            DriftSheet.message(this, "Retro Rewind", T("Install failed: %s").format(error))
        }
        broadcast(Page.Data.INSTALL)
    }

    private fun launchRetroRewindFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        // FLAG_GRANT_PERSISTABLE_URI_PERMISSION has to be asked for HERE, on the picker intent -
        // without it the grant handed back is transient, and takePersistableUriPermission() on it
        // throws SecurityException ("No persistable permission grants found"), which was thrown
        // straight out of onActivityResult and took the whole app down the instant a folder was
        // picked (reported as "it just boots me out of the app").
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        startActivityForResult(intent, REQUEST_CODE_PICK_RETRO_REWIND_FOLDER)
    }

    private fun launchRetroRewindZipPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        // Not restricted to "application/zip": many file managers/browsers hand a downloaded zip
        // over with a generic or missing MIME type - the extension check on the picked file (see
        // handleZipPicked) is the real gate, same reasoning as RomImportOverlay's ISO/WBFS picker.
        intent.type = "*/*"
        startActivityForResult(intent, REQUEST_CODE_PICK_RETRO_REWIND_ZIP)
    }

    private fun handleFolderPicked(treeUri: Uri?) {
        if (treeUri == null) return
        // Best-effort: the transient grant this Activity already holds is enough to run the copy
        // (the service is the same UID, same process), so a provider that refuses to persist is
        // not a reason to fail the import - and must never be a reason to crash.
        try {
            contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        val picked = DocumentFile.fromTreeUri(this, treeUri)
        if (picked == null) {
            toast(T("Couldn't open that folder - try picking it again."), Lx.Tone.DANGER)
            return
        }
        // Walking a SAF tree means a content-provider round trip per entry, which on a big folder
        // on slow storage is far too slow for the main thread (an ANR reads to the player exactly
        // like a crash). Resolve off-thread, then come back to the UI.
        task(T("Checking the selected folder..."), { resolveRetroRewind6(picked) }) { result ->
            val resolved = result.getOrNull()
            if (resolved == null) {
                DriftSheet.message(
                    this,
                    "Retro Rewind",
                    T("That folder doesn't contain RetroRewind6/Binaries/Code.pul - pick the folder you extracted Retro Rewind into (or the RetroRewind6 folder itself)."),
                )
                return@task
            }
            beginRetroInstall(T("Copying Retro Rewind files - this can take a while for ~2GB..."))
            RetroRewindInstallService.startFolderInstall(this, resolved.uri)
        }
    }

    private fun handleZipPicked(uri: Uri?) {
        if (uri == null) return
        val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "selected file"
        if (!name.substringAfterLast('.', "").equals("zip", ignoreCase = true)) {
            toast(T("\"%s\" isn't a .zip file - pick the Retro Rewind archive you downloaded.").format(name), Lx.Tone.DANGER)
            return
        }
        beginRetroInstall(T("Extracting \"%s\" - this can take a while for ~2GB...").format(name))
        RetroRewindInstallService.startZipInstall(this, uri, name)
    }

    private fun queryDisplayName(uri: Uri): String? =
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        } catch (_: Exception) {
            null
        }

    /** Mirrors the PC installer's RetroRewindSource.ResolveRetroRewind6: accept either the
     * RetroRewind6 folder itself, its parent, or a one-level-deeper wrapper folder. */
    private fun resolveRetroRewind6(picked: DocumentFile): DocumentFile? {
        val candidates = mutableListOf(picked)
        picked.findFile("RetroRewind6")?.let { candidates.add(it) }
        for (child in picked.listFiles()) {
            if (child.isDirectory) {
                child.findFile("RetroRewind6")?.let { candidates.add(it) }
            }
        }
        return candidates.firstOrNull { candidate ->
            candidate.findFile("Binaries")?.findFile("Code.pul")?.isFile == true
        }
    }

    // --- Game pack ---

    private fun showGamePackNeededDialog(wrongVersion: Boolean) {
        val message =
            if (wrongVersion) {
                T("Your game pack was made for a different version of DriftDroid. Get the DriftDroid Builder for this version, make a new game pack with it and import that.")
            } else {
                T("DriftDroid doesn't include any of Mario Kart Wii's code. You make it once on a Windows PC from your own disc:\n\n1. Download DriftDroid Builder from the DriftDroid GitHub page.\n2. Run it with your Mario Kart Wii disc image (PAL, .iso or .wbfs).\n3. Copy the game pack .zip it makes to this phone and tap Import Game Pack.")
            }
        val sheet = DriftSheet(this, T("Game pack needed"), compact = true)
        sheet.note(message)
        sheet.button("Get the builder") {
            sheet.dismiss()
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GamePack.BUILDER_URL))) }
        }
        sheet.button("Import game pack", DriftUi.Style.PRIMARY) {
            sheet.dismiss()
            importGamePack()
        }
        sheet.show()
    }

    internal fun importGamePack() {
        openDocument { source ->
            if (source == null) return@openDocument
            task(T("Importing the game pack..."), { progress ->
                GamePack.import(this, source) { bytes -> progress(T("%s MB").format(bytes / (1 shl 20))) }
            }) { result ->
                val error = result.getOrElse { it.message ?: "unknown error" }
                if (error == null) {
                    toast(T("Game pack imported. You can play now."), Lx.Tone.PRIMARY)
                    broadcast(Page.Data.INSTALL)
                } else {
                    DriftSheet.message(this, "Game pack", T("Game pack import failed: %s").format(error))
                }
            }
        }
    }

    // --- Diagnostics ---

    /**
     * Bundles the runtime's own log files (see android_jni_bridge.cpp's freopen() of
     * stdout/stderr, and main.cpp's per-run Logs/<product>_<epoch>_pid<pid>/console.log folders -
     * both live directly under filesDir/WiiCompiled since ApplicationDataDirectory() resolves to
     * that same path on Android) into a single zip the user picks a destination for. This exists
     * so troubleshooting doesn't require a USB cable and `adb shell run-as ... cat` - the only way
     * this app's logs have been readable until now.
     */
    internal fun exportDiagnostics() {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        createDocument("application/zip", "DriftDroid-diagnostics-$stamp.zip") { destination ->
            if (destination == null) return@createDocument
            task(T("Exporting diagnostics..."), { writeDiagnosticsZip(destination) }) { result ->
                val error = result.getOrElse { it.message ?: "unknown error" }
                if (error == null) toast(T("Diagnostics exported."), Lx.Tone.PRIMARY)
                else toast(T("Diagnostics export failed: %s").format(error), Lx.Tone.DANGER)
            }
        }
    }

    private fun writeDiagnosticsZip(destination: Uri): String? {
        return try {
            val wiiCompiledDir = File(filesDir, "WiiCompiled")
            val runtimeLog = File(wiiCompiledDir, "android_runtime_attempt.log")
            val logsRoot = File(wiiCompiledDir, "Logs")
            // Newest run folders first - if there are more than MAX_LOG_FILES across all runs,
            // the oldest runs are the ones left out, not the newest.
            val runLogs =
                (logsRoot.listFiles { file -> file.isDirectory } ?: emptyArray())
                    .sortedByDescending { it.lastModified() }
                    .flatMap { runDir -> runDir.listFiles { file -> file.isFile }?.toList() ?: emptyList() }
            val files = (listOfNotNull(runtimeLog.takeIf { it.isFile }) + runLogs).take(MAX_LOG_FILES)

            val output =
                contentResolver.openOutputStream(destination)
                    ?: return "couldn't open the chosen destination"
            output.use { rawOutput ->
                ZipOutputStream(rawOutput.buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry("README.txt"))
                    zip.write(
                        buildString {
                            appendLine("${getString(R.string.app_name)} runtime diagnostics - review before sharing.")
                            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                            appendLine("API level: ${Build.VERSION.SDK_INT}")
                            appendLine("App version: ${appVersionName()}")
                            appendLine("Memory: ${totalMemoryMiB()} MiB")
                            appendLine("Each log is truncated to its last $MAX_LOG_FILE_BYTES bytes.")
                            appendLine("Files included: ${files.size}")
                        }.toByteArray(),
                    )
                    zip.closeEntry()

                    // A run that ends with no crash log could be an Android low-memory kill, a
                    // Java exception or a crash our handlers do not catch; only Android knows
                    // which (GitHub issues #11, #12).
                    zip.putNextEntry(ZipEntry("exit_reasons.txt"))
                    zip.write(describeRecentExits().toByteArray())
                    zip.closeEntry()

                    // A controller the game never lists is usually one Android does not report as
                    // a gamepad (GitHub issue #13).
                    zip.putNextEntry(ZipEntry("input_devices.txt"))
                    zip.write(describeInputDevices().toByteArray())
                    zip.closeEntry()

                    val buffer = ByteArray(32 * 1024)
                    for (file in files) {
                        val entryName =
                            file.relativeTo(wiiCompiledDir).invariantSeparatorsPath
                        zip.putNextEntry(ZipEntry(entryName))
                        RandomAccessFile(file, "r").use { input ->
                            val size = input.length()
                            val start = maxOf(0, size - MAX_LOG_FILE_BYTES)
                            input.seek(start)
                            var remaining = size - start
                            while (remaining > 0) {
                                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (count < 0) break
                                zip.write(buffer, 0, count)
                                remaining -= count
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            null
        } catch (e: Exception) {
            e.message ?: "unknown error"
        }
    }

    internal fun appVersionName(): String =
        try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }

    private fun totalMemoryMiB(): Long {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info.totalMem / (1024 * 1024)
    }

    private fun describeRecentExits(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "Not available before Android 11.\n"
        }
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val exits =
            try {
                activityManager.getHistoricalProcessExitReasons(null, 0, MAX_EXIT_REASONS)
            } catch (e: Exception) {
                return "Could not read exit reasons: ${e.message}\n"
            }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return buildString {
            appendLine("Most recent first. Times are the phone's local time.")
            for (exit in exits) {
                appendLine("---")
                appendLine("time: ${stamp.format(Date(exit.timestamp))}")
                appendLine("reason: ${exitReasonName(exit.reason)} (${exit.reason})")
                appendLine("status: ${exit.status}")
                appendLine("importance: ${exit.importance}")
                appendLine("pss: ${exit.pss / 1024} MiB, rss: ${exit.rss / 1024} MiB")
                exit.description?.let { appendLine("description: $it") }
            }
            if (exits.isEmpty()) appendLine("(none recorded)")
        }
    }

    private fun exitReasonName(reason: Int): String =
        when (reason) {
            android.app.ApplicationExitInfo.REASON_ANR -> "ANR (app not responding)"
            android.app.ApplicationExitInfo.REASON_CRASH -> "Java crash"
            android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
            android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
            android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource usage"
            android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "exited itself"
            android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
            android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory kill"
            android.app.ApplicationExitInfo.REASON_OTHER -> "other"
            android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
            android.app.ApplicationExitInfo.REASON_SIGNALED -> "killed by signal"
            android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "user requested"
            android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
            else -> "unknown"
        }

    private fun describeInputDevices(): String =
        buildString {
            for (id in android.view.InputDevice.getDeviceIds()) {
                val device = android.view.InputDevice.getDevice(id) ?: continue
                val sources = device.sources
                val kinds =
                    listOf(
                        android.view.InputDevice.SOURCE_GAMEPAD to "gamepad",
                        android.view.InputDevice.SOURCE_JOYSTICK to "joystick",
                        android.view.InputDevice.SOURCE_DPAD to "dpad",
                        android.view.InputDevice.SOURCE_KEYBOARD to "keyboard",
                        android.view.InputDevice.SOURCE_MOUSE to "mouse",
                        android.view.InputDevice.SOURCE_TOUCHSCREEN to "touchscreen",
                    ).filter { (bit, _) -> sources and bit == bit }.joinToString(",") { it.second }
                appendLine(
                    "id=$id name='${device.name}' vendor=0x${Integer.toHexString(device.vendorId)} " +
                        "product=0x${Integer.toHexString(device.productId)} virtual=${device.isVirtual} " +
                        "external=${device.isExternal} " +
                        "sources=0x${Integer.toHexString(sources)} [$kinds]",
                )
            }
        }

    companion object {
        private const val STATE_TAB = "tab"
        private const val BOTTOM_BAR_DP = 66f
        private const val RAIL_DP = 88f
        private const val REQUEST_CODE_PICK_RETRO_REWIND_FOLDER = 0x52520000 // "RR"
        private const val REQUEST_CODE_PICK_RETRO_REWIND_ZIP = 0x52520001
        private const val REQUEST_CODE_OPEN_DOCUMENT = 0x4C4E0001 // "LN"
        private const val REQUEST_CODE_CREATE_DOCUMENT = 0x4C4E0002
        private const val MAX_LOG_FILES = 12
        private const val MAX_LOG_FILE_BYTES = 4L * 1024 * 1024
        private const val MAX_EXIT_REASONS = 10
    }
}
