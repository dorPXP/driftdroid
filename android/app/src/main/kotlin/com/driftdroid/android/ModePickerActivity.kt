package com.driftdroid.android

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import com.driftdroid.android.ui.DriftSheet
import com.driftdroid.android.ui.DriftUi
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The app's actual launcher (see AndroidManifest.xml). Chooses Original Mario Kart Wii vs. Retro
 * Rewind BEFORE any guest engine state exists, since neither the combined native library
 * (libGameCombined.so - both products, see runtime/cmake/build_combined_android_lib.py) nor a
 * running game has a supported way to reset and switch profiles live - MainActivity.EXTRA_PRODUCT
 * tells it which one nativeSetActiveProduct() should select.
 *
 * Retro Rewind's pack (tracks/textures, ~2GB - distinct from Code.pul, which only carries the
 * mod's patched game LOGIC and is already compiled into libGameCombined.so) is never bundled or
 * downloaded by this app, matching how the PC version works: WiiCompiled.Setup's
 * RetroRewindSource.cs only ever resolves a folder the user already has (normally obtained via the
 * separate Wheel Wizard tool) - it never packages or downloads it itself. This picker offers two
 * ways to hand over a copy the user already obtained: the .zip file exactly as downloaded (most
 * people will only ever have this - extracted first, matching how someone who downloaded Retro
 * Rewind normally has it), or an already-extracted folder for anyone who prefers that.
 */
class ModePickerActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var baseButton: Button
    private lateinit var retroButton: Button
    private lateinit var retroMenuButton: Button
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var diagnosticsButton: Button
    private lateinit var graphicsButton: Button
    private lateinit var iconButton: Button
    private lateinit var languageButton: Button
    private lateinit var serverSettingsButton: Button
    private lateinit var modsButton: Button
    private lateinit var miiDataButton: Button
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.init(this)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refreshChannelButtons()
        // An install may still be running in RetroRewindInstallService from a previous instance
        // of this Activity (e.g. this one is a fresh recreation after Android reclaimed the old
        // one while backgrounded) - pick up its live progress instead of showing an idle screen
        // over a copy that's actually still going.
        if (RetroRewindInstallStatus.running) {
            beginImportUi(RetroRewindInstallStatus.label)
            startStatusPolling()
        }
    }

    override fun onPause() {
        super.onPause()
        stopStatusPolling()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** A rounded "channel" tile background, Wii Menu channel style. */
    private fun channelDrawable(topColor: Int, bottomColor: Int, radius: Float): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(topColor, bottomColor),
        ).also { it.cornerRadius = radius }

    private fun styleChannelButton(
        button: Button,
        topColor: Int,
        bottomColor: Int,
        radius: Float = dp(18).toFloat(),
    ) {
        button.background = channelDrawable(topColor, bottomColor, radius)
        button.setTextColor(Color.WHITE)
        button.typeface = Typeface.DEFAULT_BOLD
        button.isAllCaps = false
        button.setShadowLayer(3f, 0f, 2f, Color.argb(140, 0, 0, 0))
        button.stateListAnimator = null
        button.elevation = dp(4).toFloat()
        addPressBounce(button)
    }

    /**
     * Custom channel art (a full banner image, already carrying its own logo/text) as a button's
     * background, clipped to rounded corners via the view's outline rather than baked into the
     * image itself - the source banners are plain rectangles at 1120x256 (= 280x64dp at 4x/xxxhdpi,
     * matching buttonParams exactly, so the image stretches to fill with no cropping needed).
     */
    private fun styleImageButton(
        button: Button,
        drawableRes: Int,
        locked: Boolean,
        lockedLabel: String,
        radius: Float = dp(18).toFloat(),
    ) {
        val art = androidx.core.content.ContextCompat.getDrawable(this, drawableRes)!!.mutate()
        button.background =
            if (locked) {
                // A dark scrim over the banner art (rather than editing the art itself) both
                // signals "locked" Wii-channel style and naturally dims the banner's own baked-in
                // title text underneath the "Install..." label drawn on top, so the two don't
                // compete for attention.
                val scrim = GradientDrawable()
                scrim.setColor(Color.argb(175, 0, 0, 0))
                scrim.cornerRadius = radius
                LayerDrawable(arrayOf(art, scrim))
            } else {
                art
            }
        button.text = if (locked) lockedLabel else ""
        button.setTextColor(Color.WHITE)
        button.typeface = Typeface.DEFAULT_BOLD
        button.textSize = 16f
        button.isAllCaps = false
        button.setShadowLayer(3f, 0f, 2f, Color.argb(200, 0, 0, 0))
        button.stateListAnimator = null
        button.elevation = dp(4).toFloat()
        button.clipToOutline = true
        button.outlineProvider =
            object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
        addPressBounce(button)
    }

    /**
     * The little "press in, spring back" wiggle every Wii Menu channel does when tapped -
     * squishes non-uniformly (width in more than height) rather than a plain uniform scale, and
     * overshoots slightly past 1.0 on release before settling - the actual difference between
     * "shrinks and grows back" (mechanical) and "liquid"/gel-like motion is in that asymmetry and
     * overshoot, not just easing curve choice. Purely a touch-feedback animation (returns false so
     * the real click listener still fires), not a gate on the click itself.
     */
    private fun addPressBounce(view: View) {
        val overshoot = android.view.animation.OvershootInterpolator(3.5f)
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.92f).scaleY(0.95f).setDuration(90)
                        .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                // Sound plays on an actual completed tap only, not ACTION_CANCEL (a drag that
                // left the view, or the touch getting reassigned elsewhere) - both still spring
                // the view back visually, but a canceled touch never counted as a "click" and
                // shouldn't sound like one either.
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(overshoot).start()
                    UiSounds.playClick(v.context)
                }
                MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(overshoot).start()
            }
            false
        }
    }

    private fun buildUi() {
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        val pad = dp(24)
        root.setPadding(pad, pad, pad, pad)

        val title = ImageView(this)
        title.setImageResource(R.drawable.driftdroid_logo)
        title.adjustViewBounds = true
        val titleParams = LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT)
        title.layoutParams = titleParams
        title.setPadding(0, dp(8), 0, 0)

        val subtitle = TextView(this)
        subtitle.text = T("Kart Channel")
        subtitle.setTextColor(Color.argb(230, 0xDD, 0xDD, 0xDD))
        subtitle.textSize = 14f
        subtitle.gravity = Gravity.CENTER
        subtitle.setPadding(0, dp(2), 0, dp(28))

        baseButton = Button(this)
        baseButton.setOnClickListener { launchProduct(MainActivity.PRODUCT_BASE) }

        retroButton = Button(this)
        retroButton.setOnClickListener {
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

        val buttonParams = LinearLayout.LayoutParams(dp(280), dp(64))
        buttonParams.topMargin = dp(14)

        retroMenuButton = Button(this)
        retroMenuButton.text = "⋮" // vertical ellipsis ("3 dots")
        retroMenuButton.textSize = 18f
        retroMenuButton.setOnClickListener { showRetroRewindMenuDialog() }
        styleChannelButton(retroMenuButton, Color.rgb(0xF2, 0x5C, 0x4C), Color.rgb(0xD1, 0x2A, 0x1E), dp(14).toFloat())

        val retroRowParams =
            LinearLayout.LayoutParams(dp(280), dp(64))
        retroRowParams.topMargin = dp(14)
        val retroRow = LinearLayout(this)
        retroRow.orientation = LinearLayout.HORIZONTAL
        val retroButtonParams =
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        val retroMenuButtonParams =
            LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.MATCH_PARENT)
        retroMenuButtonParams.marginStart = dp(8)
        retroRow.addView(retroButton, retroButtonParams)
        retroRow.addView(retroMenuButton, retroMenuButtonParams)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.max = 1000
        progressBar.visibility = View.GONE
        val progressParams = LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT)
        progressParams.topMargin = dp(14)

        statusText = TextView(this)
        statusText.setTextColor(Color.rgb(0xDD, 0xDD, 0xDD))
        statusText.textSize = 13f
        statusText.gravity = Gravity.CENTER
        statusText.setPadding(0, dp(16), 0, 0)


        diagnosticsButton = Button(this)
        diagnosticsButton.text = T("Export Diagnostics")
        diagnosticsButton.textSize = 11f
        diagnosticsButton.setOnClickListener { launchDiagnosticsExportPicker() }
        val diagnosticsButtonParams =
            LinearLayout.LayoutParams(dp(260), dp(40))
        diagnosticsButtonParams.topMargin = dp(24)
        styleChannelButton(
            diagnosticsButton,
            Color.rgb(0x5A, 0x5A, 0x5A),
            Color.rgb(0x3A, 0x3A, 0x3A),
            dp(12).toFloat(),
        )
        diagnosticsButton.setTextColor(Color.rgb(0xDD, 0xDD, 0xDD))
        diagnosticsButton.setShadowLayer(0f, 0f, 0f, 0)

        serverSettingsButton = Button(this)
        serverSettingsButton.text = T("Experimental Server Settings...")
        serverSettingsButton.textSize = 11f
        serverSettingsButton.setOnClickListener { PrivateServerSettings.show(this) }
        val serverSettingsButtonParams =
            LinearLayout.LayoutParams(dp(260), dp(40))
        serverSettingsButtonParams.topMargin = dp(8)
        styleChannelButton(
            serverSettingsButton,
            Color.rgb(0x5A, 0x5A, 0x5A),
            Color.rgb(0x3A, 0x3A, 0x3A),
            dp(12).toFloat(),
        )
        serverSettingsButton.setTextColor(Color.rgb(0xDD, 0xDD, 0xDD))
        serverSettingsButton.setShadowLayer(0f, 0f, 0f, 0)

        // Renderer choice lives here because a device with no working Vulkan driver crashes before
        // the in-game settings exist (GitHub issue #3).
        graphicsButton = Button(this)
        graphicsButton.text = T("Graphics...")
        graphicsButton.textSize = 11f
        graphicsButton.setOnClickListener { GraphicsSettings.show(this) }
        val graphicsButtonParams =
            LinearLayout.LayoutParams(dp(260), dp(40))
        graphicsButtonParams.topMargin = dp(8)
        styleChannelButton(
            graphicsButton,
            Color.rgb(0x5A, 0x5A, 0x5A),
            Color.rgb(0x3A, 0x3A, 0x3A),
            dp(12).toFloat(),
        )
        graphicsButton.setTextColor(Color.rgb(0xDD, 0xDD, 0xDD))
        graphicsButton.setShadowLayer(0f, 0f, 0f, 0)

        // Custom home-screen shortcut icon. Lives here rather than in the in-game sidebar because
        // it opens the gallery picker, which must not run over a live game surface (see the
        // Mods... button below for the crash that caused).
        iconButton = Button(this)
        iconButton.text = T("App icon...")
        iconButton.textSize = 11f
        iconButton.setOnClickListener { IconSettings.show(this) }
        val iconButtonParams =
            LinearLayout.LayoutParams(dp(260), dp(40))
        iconButtonParams.topMargin = dp(8)
        styleChannelButton(
            iconButton,
            Color.rgb(0x5A, 0x5A, 0x5A),
            Color.rgb(0x3A, 0x3A, 0x3A),
            dp(12).toFloat(),
        )
        iconButton.setTextColor(Color.rgb(0xDD, 0xDD, 0xDD))
        iconButton.setShadowLayer(0f, 0f, 0f, 0)

        languageButton = Button(this)
        languageButton.text = T("Language...")
        languageButton.textSize = 11f
        languageButton.setOnClickListener { LanguageSettings.show(this) }
        val languageButtonParams = LinearLayout.LayoutParams(dp(260), dp(40))
        languageButtonParams.topMargin = dp(8)
        styleChannelButton(
            languageButton,
            Color.rgb(0x5A, 0x5A, 0x5A),
            Color.rgb(0x3A, 0x3A, 0x3A),
            dp(12).toFloat(),
        )
        languageButton.setTextColor(Color.rgb(0xDD, 0xDD, 0xDD))
        languageButton.setShadowLayer(0f, 0f, 0f, 0)

        // Import/manage from here, not from the in-game settings sidebar - launching Android's
        // file picker while a live game session's GPU surface is running lost the WebGPU/Vulkan
        // surface on-device ("QueuePresent failed with VK_ERROR_SURFACE_LOST_KHR") and crashed
        // the game. No such surface exists yet on this screen, so it's safe here - same reasoning
        // as why Retro Rewind's own install flow only ever runs from this Activity.
        modsButton = Button(this)
        modsButton.text = T("Mods...")
        modsButton.textSize = 11f
        modsButton.setOnClickListener { ModManager.showManagerDialog(this) }
        val modsButtonParams =
            LinearLayout.LayoutParams(dp(260), dp(40))
        modsButtonParams.topMargin = dp(8)
        styleChannelButton(
            modsButton,
            Color.rgb(0x5A, 0x5A, 0x5A),
            Color.rgb(0x3A, 0x3A, 0x3A),
            dp(12).toFloat(),
        )
        modsButton.setTextColor(Color.rgb(0xDD, 0xDD, 0xDD))
        modsButton.setShadowLayer(0f, 0f, 0f, 0)

        val gamePackButton = Button(this)
        gamePackButton.text = T("Import Game Pack...")
        gamePackButton.textSize = 11f
        gamePackButton.setOnClickListener { launchGamePackPicker() }
        val gamePackButtonParams = LinearLayout.LayoutParams(dp(260), dp(40))
        gamePackButtonParams.topMargin = dp(8)
        styleChannelButton(
            gamePackButton,
            Color.rgb(0x5A, 0x5A, 0x5A),
            Color.rgb(0x3A, 0x3A, 0x3A),
            dp(12).toFloat(),
        )
        gamePackButton.setTextColor(Color.rgb(0xDD, 0xDD, 0xDD))
        gamePackButton.setShadowLayer(0f, 0f, 0f, 0)

        // GitHub issue #14: bring a save over from a Wii NAND backup or Dolphin, or copy it out.
        val saveDataButton = Button(this)
        saveDataButton.text = T("Save Data...")
        saveDataButton.textSize = 11f
        saveDataButton.setOnClickListener { SaveData.showDialog(this) }
        val saveDataButtonParams = LinearLayout.LayoutParams(dp(260), dp(40))
        saveDataButtonParams.topMargin = dp(8)
        styleChannelButton(
            saveDataButton,
            Color.rgb(0x5A, 0x5A, 0x5A),
            Color.rgb(0x3A, 0x3A, 0x3A),
            dp(12).toFloat(),
        )
        saveDataButton.setTextColor(Color.rgb(0xDD, 0xDD, 0xDD))
        saveDataButton.setShadowLayer(0f, 0f, 0f, 0)

        // A square channel-style tile, same idea as baseButton/retroButton but square (96dp,
        // matching the eventual custom Mii icon art) rather than a wide banner - Mii Data isn't a
        // product to launch, but it's frequent enough (importing/exporting Miis) to earn its own
        // channel rather than living in the pale utility-button stack below.
        miiDataButton = Button(this)
        miiDataButton.setOnClickListener { showMiiDataMenuDialog() }
        val miiDataButtonParams = LinearLayout.LayoutParams(dp(96), dp(96))
        miiDataButtonParams.topMargin = dp(14)
        styleImageButton(
            miiDataButton,
            R.drawable.channel_mii_data,
            locked = false,
            lockedLabel = "",
            radius = dp(20).toFloat(),
        )

        root.addView(title)
        root.addView(subtitle)
        root.addView(baseButton, buttonParams)
        root.addView(retroRow, retroRowParams)
        root.addView(miiDataButton, miiDataButtonParams)
        root.addView(progressBar, progressParams)
        root.addView(statusText)
        root.addView(diagnosticsButton, diagnosticsButtonParams)
        root.addView(graphicsButton, graphicsButtonParams)
        root.addView(iconButton, iconButtonParams)
        root.addView(languageButton, languageButtonParams)
        root.addView(serverSettingsButton, serverSettingsButtonParams)
        root.addView(modsButton, modsButtonParams)
        root.addView(saveDataButton, saveDataButtonParams)
        root.addView(gamePackButton, gamePackButtonParams)

        // The picker has grown past what fits on-screen in landscape on some phones - confirmed
        // directly on-device: the two lowest buttons (server settings, Mii data) were laid out
        // fine but simply extended below the visible screen with no way to reach them, since a
        // LinearLayout doesn't scroll on its own. A ScrollView guarantees every control stays
        // reachable regardless of screen height/font scale, at the cost of no longer being
        // perfectly vertically centered when everything DOES fit - worth it for correctness.
        val scroll = android.widget.ScrollView(this)
        scroll.background = ScrollingCheckerboardDrawable(this)
        scroll.addView(
            root,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        setContentView(scroll)
        refreshChannelButtons()
    }

    private fun refreshChannelButtons() {
        val romInstalled = isRomImported()
        styleImageButton(baseButton, R.drawable.channel_mkwii, locked = !romInstalled, lockedLabel = "Install with ISO/WBFS")

        val retroInstalled = isRetroRewindInstalled()
        val notInPack = packWithoutRetroRewind()
        styleImageButton(
            retroButton,
            R.drawable.channel_retro_rewind,
            locked = !retroInstalled || notInPack,
            lockedLabel = if (notInPack) T("NOT IN GAME PACK") else "INSTALL",
        )
        // Nothing to update or delete until a copy is actually installed.
        retroMenuButton.visibility = if (retroInstalled) View.VISIBLE else View.GONE
    }

    /** A game pack is in and it was built without Retro Rewind. */
    private fun packWithoutRetroRewind(): Boolean =
        GamePack.state(this) == GamePack.State.Ready && !GamePack.hasRetroRewind(this)

    private fun isRomImported(): Boolean =
        com.driftdroid.android.rom.RomImportOverlay.isRomAlreadyImported(File(filesDir, "WiiCompiled/DiscData"))

    private fun showRetroRewindMenuDialog() {
        val sheet = DriftSheet(this, "Retro Rewind", RetroRewindCatalog.installedVersion(retroRewindRoot())?.let { T("Installed: %s").format(it) })
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
            refreshChannelButtons()
            statusText.setTextColor(Color.rgb(0xDD, 0xDD, 0xDD))
            statusText.text = T("Retro Rewind deleted.")
        }
    }

    private fun isRetroRewindInstalled(): Boolean =
        File(retroRewindRoot(), "Binaries/Code.pul").isFile

    private fun retroRewindRoot(): File = File(filesDir, MainActivity.RETRO_REWIND_STAGING_SUBDIR)

    /**
     * Starts Retro Rewind - unless the installed copy holds game code this build isn't compiled
     * for, which is what an install made by an older DriftDroid looks like after the app has been
     * updated for a newer Retro Rewind. Launching that would misbehave or crash, so it is brought
     * up to date first (the small per-version updates, not a new 1.9GB download) and the player
     * taps again once that has finished.
     */
    private fun launchRetroRewindOrUpdate() {
        val root = retroRewindRoot()
        retroButton.isEnabled = false
        Thread {
            val supported = RetroRewindCatalog.isSupportedCodePul(RetroRewindCatalog.installedCodePulSha256(root))
            mainHandler.post {
                retroButton.isEnabled = true
                if (isFinishing || isDestroyed) return@post
                if (supported) {
                    launchProduct(MainActivity.PRODUCT_RETRO_REWIND)
                } else {
                    val installed = RetroRewindCatalog.installedVersion(root)
                    beginImportUi(
                        T("Retro Rewind %s needs updating for this version of DriftDroid - updating it now...").format(installed ?: ""),
                    )
                    RetroRewindInstallService.startDeltaUpdate(this)
                    startStatusPolling()
                }
            }
        }.start()
    }

    private fun launchProduct(product: String) {
        // The game code is not in the APK: it is made on a PC from the player's disc (GamePack.kt).
        // A development build (no toolkit id) still carries it.
        if (GamePack.expectedToolkitId(this) != null && GamePack.state(this) != GamePack.State.Ready) {
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
            startLatestInstall()
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

    private fun startDownloadInstall() {
        beginImportUi(T("Downloading Retro Rewind %s...").format(RetroRewindRelease.VERSION))
        RetroRewindInstallService.startDownloadInstall(this)
        startStatusPolling()
    }

    /** Installs whatever the live release catalog says is newest AND runnable on this build,
     * rather than only ever the release that was current when the app was compiled. */
    private fun startLatestInstall() {
        beginImportUi(T("Checking for the newest Retro Rewind..."))
        RetroRewindInstallService.startLatestInstall(this)
        startStatusPolling()
    }

    /** Applies the mod's own small per-version update archives to the existing install - a few MB
     * rather than another ~1.9GB. */
    private fun startDeltaUpdate() {
        beginImportUi(T("Checking for Retro Rewind updates..."))
        RetroRewindInstallService.startDeltaUpdate(this)
        startStatusPolling()
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
        // onActivityResult) is the real gate, same reasoning as RomImportOverlay's ISO/WBFS picker.
        intent.type = "*/*"
        startActivityForResult(intent, REQUEST_CODE_PICK_RETRO_REWIND_ZIP)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_PICK_RETRO_REWIND_FOLDER -> handleFolderPicked(data?.data)
            REQUEST_CODE_PICK_RETRO_REWIND_ZIP -> handleZipPicked(data?.data)
            REQUEST_CODE_EXPORT_DIAGNOSTICS -> handleDiagnosticsDestinationPicked(data?.data)
            REQUEST_CODE_EXPORT_MII_DATA -> handleMiiExportDestinationPicked(data?.data)
            REQUEST_CODE_IMPORT_MII_DATA -> handleMiiImportSourcePicked(data?.data)
            REQUEST_CODE_IMPORT_SINGLE_MII -> handleSingleMiiSourcePicked(data?.data)
            ModManager.REQUEST_CODE_PICK_ZIP -> ModManager.onZipPicked(this, data?.data)
            REQUEST_CODE_IMPORT_GAME_PACK -> handleGamePackPicked(data?.data)
            SaveData.REQUEST_CODE_EXPORT -> SaveData.onExportDestinationPicked(this, data?.data)
            SaveData.REQUEST_CODE_IMPORT -> SaveData.onImportSourcePicked(this, data?.data)
            GpuDriverSettings.REQUEST_CODE_PICK_ZIP -> GpuDriverSettings.onZipPicked(this, data?.data)
            IconSettings.REQUEST_CODE_PICK_IMAGE -> IconSettings.onImagePicked(this, data?.data)
        }
    }

    /**
     * The Mii Channel's own database (RFL_DB.dat, distinct from RFL_Res.dat - the read-only face
     * resource seeded from the ISO by nand_fs.cpp's SeedFaceLibResource, never written by the
     * player) - lives at NAND/shared2/menu/FaceLib/RFL_DB.dat under filesDir/WiiCompiled, since
     * ApplicationDataDirectory() / "NAND" (nand_path.h) resolves to that same directory on
     * Android. A fixed-size file (779968 bytes, the standard RFL database size on real Wii/Wii
     * consoles) - checked before import so a wrong/corrupt file can't silently wedge the Mii
     * Channel, with the previous database always backed up first regardless.
     */
    private fun miiDatabaseFile(): File =
        File(filesDir, "WiiCompiled/NAND/shared2/menu/FaceLib/RFL_DB.dat")

    private fun showMiiDataMenuDialog() {
        val sheet = DriftSheet(this, "Mii data", T("The Miis in the Mii Channel"))
        sheet.note("Start Mario Kart Wii at least once first: that makes the Mii file.")
        sheet.action("Export all Miis", "Saves RFL_DB.dat where you choose") {
            sheet.dismiss()
            launchMiiExportPicker()
        }
        sheet.action("Replace all Miis", "From an RFL_DB.dat file. The current one is backed up") {
            sheet.dismiss()
            launchMiiImportPicker()
        }
        sheet.action("Add one Mii", "From a .rcd file, into a free slot") {
            sheet.dismiss()
            launchSingleMiiImportPicker()
        }
        sheet.show()
    }

    private fun launchSingleMiiImportPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        startActivityForResult(intent, REQUEST_CODE_IMPORT_SINGLE_MII)
    }

    private fun handleSingleMiiSourcePicked(source: Uri?) {
        if (source == null) return
        insertSingleMii(source)
    }

    /**
     * Inserts one standalone Mii (.rcd, the 74-byte Wii Mii exchange format) into a free slot in
     * the existing database - see MiiDatabaseEditor.kt for the format details and where they came
     * from. Backs up the current database first, same as the whole-database Replace path.
     */
    private fun insertSingleMii(source: Uri) {
        try {
            val size = queryFileSize(source)
            if (size >= 0 && size != MiiDatabaseEditor.MII_RECORD_BYTES.toLong()) {
                throw IllegalArgumentException(
                    "not a single-Mii file (expected ${MiiDatabaseEditor.MII_RECORD_BYTES} bytes, got $size)",
                )
            }
            val record =
                contentResolver.openInputStream(source)?.use { it.readBytes() }
                    ?: throw IllegalStateException("couldn't open the picked file")

            val dbFile = miiDatabaseFile()
            if (!dbFile.isFile) {
                throw IllegalStateException("no existing Mii database - start Mario Kart Wii once first")
            }
            val database = dbFile.readBytes()

            when (val result = MiiDatabaseEditor.insertMii(database, record)) {
                is MiiDatabaseEditor.Result.Failure -> throw IllegalStateException(result.message)
                is MiiDatabaseEditor.Result.Success -> {
                    val backup = File(dbFile.parentFile, "RFL_DB.dat.bak")
                    dbFile.copyTo(backup, overwrite = true)
                    dbFile.writeBytes(result.database)
                    statusText.setTextColor(Color.rgb(20, 140, 60))
                    statusText.text = T("Mii added to slot %s of 100.").format(result.slotIndex + 1)
                }
            }
        } catch (e: Exception) {
            statusText.setTextColor(Color.rgb(190, 30, 30))
            statusText.text = T("Mii import failed: %s").format(e.message ?: "unknown error")
        }
    }

    private fun launchMiiExportPicker() {
        if (!miiDatabaseFile().isFile) {
            statusText.setTextColor(Color.rgb(190, 30, 30))
            statusText.text = T("No Mii database yet - start Mario Kart Wii once first.")
            return
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "application/octet-stream"
        intent.putExtra(Intent.EXTRA_TITLE, "DriftDroid-Mii-$stamp.dat")
        startActivityForResult(intent, REQUEST_CODE_EXPORT_MII_DATA)
    }

    private fun handleMiiExportDestinationPicked(destination: Uri?) {
        if (destination == null) return
        try {
            contentResolver.openOutputStream(destination)?.use { output ->
                miiDatabaseFile().inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("couldn't open the chosen destination")
            statusText.setTextColor(Color.rgb(20, 140, 60))
            statusText.text = T("Mii data exported.")
        } catch (e: Exception) {
            statusText.setTextColor(Color.rgb(190, 30, 30))
            statusText.text = T("Mii export failed: %s").format(e.message ?: "unknown error")
        }
    }

    private fun launchMiiImportPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        startActivityForResult(intent, REQUEST_CODE_IMPORT_MII_DATA)
    }

    private fun handleMiiImportSourcePicked(source: Uri?) {
        if (source == null) return
        DriftSheet.confirm(
            this,
            "Replace all Miis?",
            "Every Mii in the Mii Channel is replaced with the picked file's Miis. The current ones are " +
                "backed up first (RFL_DB.dat.bak) in case this goes wrong.",
            "Replace",
            danger = true,
        ) { importMiiDatabase(source) }
    }

    private fun importMiiDatabase(source: Uri): Unit =
        try {
            val picked =
                contentResolver.openInputStream(source)?.use { it.readBytes() }
                    ?: throw IllegalStateException("couldn't open the picked file")
            // Real CRC-16 validation (matches KartPad's own check), not just a size comparison -
            // catches a corrupted-but-right-size file that a size-only check would wave through.
            if (!MiiDatabaseEditor.isValidDatabase(picked)) {
                throw IllegalArgumentException("that doesn't look like a valid Mii database file")
            }
            val dest = miiDatabaseFile()
            if (!dest.isFile) {
                throw IllegalStateException("no existing Mii database - start Mario Kart Wii once first")
            }
            val backup = File(dest.parentFile, "RFL_DB.dat.bak")
            dest.copyTo(backup, overwrite = true)
            dest.writeBytes(picked)
            statusText.setTextColor(Color.rgb(20, 140, 60))
            statusText.text = T("Mii data replaced (previous copy saved as RFL_DB.dat.bak).")
            Unit
        } catch (e: Exception) {
            statusText.setTextColor(Color.rgb(190, 30, 30))
            statusText.text = T("Mii import failed: %s").format(e.message ?: "unknown error")
        }

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
            launchGamePackPicker()
        }
        sheet.show()
    }

    private fun launchGamePackPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        startActivityForResult(intent, REQUEST_CODE_IMPORT_GAME_PACK)
    }

    private fun handleGamePackPicked(source: Uri?) {
        if (source == null) return
        statusText.setTextColor(Color.rgb(0xDD, 0xDD, 0xDD))
        statusText.text = T("Importing the game pack...")
        Thread {
            val error =
                GamePack.import(this, source) { bytes ->
                    mainHandler.post { statusText.text = T("Importing the game pack... %s MB").format(bytes / (1 shl 20)) }
                }
            mainHandler.post {
                if (error == null) {
                    statusText.setTextColor(Color.rgb(20, 140, 60))
                    statusText.text = T("Game pack imported. You can play now.")
                } else {
                    statusText.setTextColor(Color.rgb(190, 30, 30))
                    statusText.text = T("Game pack import failed: %s").format(error)
                }
            }
        }.start()
    }

    /**
     * Bundles the runtime's own log files (see android_jni_bridge.cpp's freopen() of
     * stdout/stderr, and main.cpp's per-run Logs/<product>_<epoch>_pid<pid>/console.log folders -
     * both live directly under filesDir/WiiCompiled since ApplicationDataDirectory() resolves to
     * that same path on Android) into a single zip the user picks a destination for. This exists
     * so troubleshooting doesn't require a USB cable and `adb shell run-as ... cat` - the only way
     * this app's logs have been readable until now.
     */
    private fun launchDiagnosticsExportPicker() {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "application/zip"
        intent.putExtra(Intent.EXTRA_TITLE, "DriftDroid-diagnostics-$stamp.zip")
        startActivityForResult(intent, REQUEST_CODE_EXPORT_DIAGNOSTICS)
    }

    private fun handleDiagnosticsDestinationPicked(destination: Uri?) {
        if (destination == null) return
        statusText.setTextColor(Color.rgb(0xDD, 0xDD, 0xDD))
        statusText.text = T("Exporting diagnostics...")
        val thread =
            Thread {
                val error = writeDiagnosticsZip(destination)
                mainHandler.post {
                    if (error == null) {
                        statusText.setTextColor(Color.rgb(20, 140, 60))
                        statusText.text = T("Diagnostics exported.")
                    } else {
                        statusText.setTextColor(Color.rgb(190, 30, 30))
                        statusText.text = T("Diagnostics export failed: %s").format(error)
                    }
                }
            }
        thread.start()
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
                            appendLine("DriftDroid runtime diagnostics - review before sharing.")
                            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                            appendLine("API level: ${android.os.Build.VERSION.SDK_INT}")
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

    private fun appVersionName(): String =
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
            showImportError(T("Couldn't open that folder - try picking it again."))
            return
        }
        // Walking a SAF tree means a content-provider round trip per entry, which on a big folder
        // on slow storage is far too slow for the main thread (an ANR reads to the player exactly
        // like the crash above). Resolve off-thread, then come back to the UI.
        beginImportUi(T("Checking the selected folder..."))
        Thread {
            val resolved =
                try {
                    resolveRetroRewind6(picked)
                } catch (_: Exception) {
                    null
                }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (resolved == null) {
                    endImportUi()
                    showImportError(
                        T("That folder doesn't contain RetroRewind6/Binaries/Code.pul - pick the folder you extracted Retro Rewind into (or the RetroRewind6 folder itself)."),
                    )
                    return@post
                }
                statusText.text = T("Copying Retro Rewind files - this can take a while for ~2GB...")
                RetroRewindInstallService.startFolderInstall(this, resolved.uri)
                startStatusPolling()
            }
        }.start()
    }

    private fun showImportError(message: String) {
        statusText.setTextColor(Color.rgb(190, 30, 30))
        statusText.text = message
    }

    private fun handleZipPicked(uri: Uri?) {
        if (uri == null) return
        val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "selected file"
        if (!name.substringAfterLast('.', "").equals("zip", ignoreCase = true)) {
            statusText.setTextColor(Color.rgb(190, 30, 30))
            statusText.text = "\"$name\" isn't a .zip file - pick the Retro Rewind archive you downloaded."
            return
        }
        beginImportUi(T("Extracting \"%s\" - this can take a while for ~2GB...").format(name))
        RetroRewindInstallService.startZipInstall(this, uri, name)
        startStatusPolling()
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

    private fun beginImportUi(statusMessage: String) {
        baseButton.isEnabled = false
        retroButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        // Indeterminate (spinning), not a 0-100% fill: neither the folder-copy nor the zip-extract
        // path knows the total file/byte count up front without a slow pre-scan, so a fixed
        // progress value just sat frozen at 0% the whole time - confirmed directly as "no progress
        // bar... which is weird" (it was there, just never moving, so it looked broken/absent).
        // The file-count text next to it already gives a live sense of movement.
        progressBar.isIndeterminate = true
        statusText.setTextColor(Color.rgb(0xDD, 0xDD, 0xDD))
        statusText.text = statusMessage
    }

    /** Puts the buttons/progress bar back without writing any status text - for bailing out of an
     * import before the service was ever started (the caller sets its own message). */
    private fun endImportUi() {
        progressBar.isIndeterminate = false
        progressBar.visibility = View.GONE
        baseButton.isEnabled = true
        retroButton.isEnabled = true
    }

    private fun finishImportUi(error: String?, filesCount: Int) {
        endImportUi()
        if (error == null) {
            statusText.setTextColor(Color.rgb(20, 140, 60))
            statusText.text =
                RetroRewindInstallStatus.successMessage ?: T("Retro Rewind installed (%s files).").format(filesCount)
            refreshChannelButtons()
        } else {
            // Leave no half-copied install behind - isRetroRewindInstalled() only checks for
            // Code.pul, and a partial copy that happened to include it would then look
            // "installed" while actually missing tracks/assets. Only when the install really was
            // being rebuilt, though: a refused version or an update check that couldn't finish
            // has not touched the working install, and deleting it then cost the player 1.9GB.
            if (RetroRewindInstallStatus.installIncomplete) {
                retroRewindRoot().deleteRecursively()
            }
            refreshChannelButtons()
            statusText.setTextColor(Color.rgb(190, 30, 30))
            statusText.text = T("Install failed: %s").format(error)
        }
    }

    /**
     * The actual copy/extract now runs in [RetroRewindInstallService], a foreground service that
     * outlives this Activity's lifecycle (see the service's own doc comment for why - short
     * version: a plain background Thread here died whenever Android reclaimed this Activity's
     * process while backgrounded, losing the whole ~2GB copy). This just reflects
     * [RetroRewindInstallStatus] into the UI, polling rather than needing a callback since a
     * brand new Activity instance (post-recreation) can start polling and pick up exactly where
     * an old, now-gone instance left off.
     */
    private var statusPoller: Runnable? = null

    private fun startStatusPolling() {
        if (statusPoller != null) return
        val poller =
            object : Runnable {
                override fun run() {
                    if (RetroRewindInstallStatus.finished) {
                        statusPoller = null
                        finishImportUi(RetroRewindInstallStatus.error, RetroRewindInstallStatus.filesDone)
                        return
                    }
                    val totalBytes = RetroRewindInstallStatus.totalBytes
                    statusText.text =
                        if (totalBytes > 0) {
                            val doneMb = RetroRewindInstallStatus.downloadedBytes / (1024 * 1024)
                            val totalMb = totalBytes / (1024 * 1024)
                            "${RetroRewindInstallStatus.label}... ${doneMb}MB / ${totalMb}MB"
                        } else {
                            "${RetroRewindInstallStatus.label}... ${RetroRewindInstallStatus.filesDone} files done"
                        }
                    mainHandler.postDelayed(this, 500)
                }
            }
        statusPoller = poller
        mainHandler.post(poller)
    }

    private fun stopStatusPolling() {
        statusPoller?.let { mainHandler.removeCallbacks(it) }
        statusPoller = null
    }

    private fun queryFileSize(uri: Uri): Long =
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else -1L
            } ?: -1L
        } catch (_: Exception) {
            -1L
        }

    companion object {
        private const val REQUEST_CODE_PICK_RETRO_REWIND_FOLDER = 0x52520000 // "RR"
        private const val REQUEST_CODE_PICK_RETRO_REWIND_ZIP = 0x52520001
        private const val REQUEST_CODE_EXPORT_DIAGNOSTICS = 0x52520002
        private const val MAX_LOG_FILES = 12
        private const val MAX_LOG_FILE_BYTES = 4L * 1024 * 1024
        private const val MAX_EXIT_REASONS = 10
        private const val REQUEST_CODE_IMPORT_GAME_PACK = 0x47500000 // "GP"
        private const val REQUEST_CODE_EXPORT_MII_DATA = 0x52520003
        private const val REQUEST_CODE_IMPORT_MII_DATA = 0x52520004
        private const val REQUEST_CODE_IMPORT_SINGLE_MII = 0x52520005
    }
}

/**
 * A gray checkerboard wallpaper, tiled and drifting diagonally forever - replaces the earlier sky
 * gradient background. A Drawable (not a View) so it can just be dropped in as `view.background`;
 * animates itself via Drawable.scheduleSelf, which only works because setting a Drawable as a
 * View's background makes that View its Drawable.Callback automatically (no manual wiring needed,
 * and it stops scheduling redraws on its own once the View is gone/detached).
 */
private class ScrollingCheckerboardDrawable(context: android.content.Context) : Drawable() {
    private val tilePx = (28 * context.resources.displayMetrics.density).toInt().coerceAtLeast(8)
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val shader: android.graphics.BitmapShader
    private val localMatrix = android.graphics.Matrix()
    private var offsetPx = 0f
    private var lastFrameUptimeMs = android.os.SystemClock.uptimeMillis()
    private var started = false

    init {
        val size = tilePx * 2
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val light = android.graphics.Paint().apply { color = Color.rgb(0x3A, 0x3A, 0x3A) }
        val dark = android.graphics.Paint().apply { color = Color.rgb(0x22, 0x22, 0x22) }
        val t = tilePx.toFloat()
        val s = size.toFloat()
        canvas.drawRect(0f, 0f, t, t, light)
        canvas.drawRect(t, 0f, s, t, dark)
        canvas.drawRect(0f, t, t, s, dark)
        canvas.drawRect(t, t, s, s, light)
        shader = android.graphics.BitmapShader(
            bitmap, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT,
        )
        paint.shader = shader
    }

    private val tick =
        object : Runnable {
            override fun run() {
                val now = android.os.SystemClock.uptimeMillis()
                val deltaMs = (now - lastFrameUptimeMs).coerceIn(0, 100)
                lastFrameUptimeMs = now
                offsetPx += deltaMs * SPEED_PX_PER_MS
                invalidateSelf()
                scheduleSelf(this, now + FRAME_INTERVAL_MS)
            }
        }

    override fun draw(canvas: android.graphics.Canvas) {
        localMatrix.setTranslate(offsetPx, offsetPx * 0.6f)
        shader.setLocalMatrix(localMatrix)
        canvas.drawRect(bounds, paint)
        // First draw() (right after the background is actually attached/visible) is what starts
        // the animation loop, once - scheduling from init{} instead would need scheduleSelf's
        // Callback (set by View.setBackground) that doesn't exist yet at construction time; after
        // this, `tick` reschedules itself, so draw() must NOT also reschedule on every frame (that
        // would stack a second, independent chain of ticks on top of tick's own, each triggering
        // more invalidateSelf() calls than the last - confirmed this exact mistake in an earlier
        // draft, caught before it shipped).
        if (!started) {
            started = true
            lastFrameUptimeMs = android.os.SystemClock.uptimeMillis()
            scheduleSelf(tick, lastFrameUptimeMs + FRAME_INTERVAL_MS)
        }
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}

    override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE

    companion object {
        private const val FRAME_INTERVAL_MS = 32L // ~30fps - plenty smooth for a slow drift
        private const val SPEED_PX_PER_MS = 0.006f // slow: one tile crossed roughly every 9s
    }
}
