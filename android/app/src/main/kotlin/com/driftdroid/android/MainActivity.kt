package com.driftdroid.android

import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import com.driftdroid.android.rom.RomImportOverlay
import com.driftdroid.android.touch.EditGestureHelper
import com.driftdroid.android.touch.TouchControlsOverlay
import java.io.File
import org.libsdl.app.SDLActivity

/**
 * Extends SDLActivity (not a plain Activity) so SDL's own Java glue owns the whole native
 * lifecycle: creating the SDLSurface/ANativeWindow, calling SDL.setupJNI()/SDL.initialize()
 * (populating the mActivityClass/method-ID globals SDL_VideoInit's touch-input setup needs),
 * and spawning the background thread that dlsym's + calls SDL_main once the surface is ready.
 * Before this, MainActivity was a plain Activity with a debug button that manually re-implemented
 * pieces of that lifecycle (see git history) - fighting SDLActivity's own plumbing kept
 * resurfacing the same class of bug, so this is the "do it the SDL way" version (P5 groundwork).
 *
 * Always started explicitly from ModePickerActivity (the real launcher - see AndroidManifest.xml)
 * with [EXTRA_PRODUCT] set: even though both products now live in one combined native library
 * (nativeSetActiveProduct() picks which one actually runs, not which .so gets loaded), the guest
 * engine state a running game builds up (translated memory, guest threads, HLE device state) has
 * no supported "reset and switch profiles" path - the Original/Retro Rewind choice must still be
 * resolved before SDLActivity's own lifecycle (which calls getLibraries()) even starts, and
 * restartIntoProduct() below still fully kills the process rather than trying to switch live.
 */
class MainActivity : SDLActivity() {

    private external fun nativeSetInstallPaths(filesDir: String, dvdRoot: String)
    private external fun nativeSetRetroRewindRoot(retroRewindRoot: String)
    private external fun nativeSetActiveProduct(retroRewind: Boolean)
    private external fun nativeToggleSettingsOverlay()
    private external fun nativeSetTouchControlsVisibleCache(visible: Boolean)
    private external fun nativeReportExternalMediaPlaying(playing: Boolean)

    private var touchControls: TouchControlsOverlay? = null
    private var romImportOverlay: RomImportOverlay? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var resolvedProduct: String = PRODUCT_BASE
    private var motionSteering: MotionSteering? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    override fun getLibraries(): Array<String> {
        // REVERTED (2026-09-05): the combined libGameCombined.so build (see
        // runtime/cmake/build_combined_android_lib.py) crashes on load - its indirect-dispatch
        // function registry (runtime/src/abi_bridge.cpp, RegisterStaticIndirectDispatchTable) is
        // hard-coded to accept exactly one profile's table per process and aborts when both
        // products' tables register. Fixing that registry to be profile-aware is real follow-up
        // work, not done yet - see hermes/ notes. Back to separate libraries in the meantime.
        return arrayOf("wii", "png16", if (resolvedProduct == PRODUCT_RETRO_REWIND) "RetroRewind" else "WiiCompiled")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        resolvedProduct = intent.getStringExtra(EXTRA_PRODUCT) ?: PRODUCT_BASE

        // A different product may already be running in this process from an earlier launch (e.g.
        // the user backgrounded the game, returned to ModePickerActivity, and picked the OTHER
        // mode without fully closing the app) - the guest engine has no supported way to reset
        // itself and switch profiles live, so the only correct fix is a full process restart into
        // the new choice.
        if (sLoadedProduct != null && sLoadedProduct != resolvedProduct) {
            restartIntoProduct(resolvedProduct)
            return
        }
        sLoadedProduct = resolvedProduct
        // Must run before any System.loadLibrary() call below - network_deferred.cpp reads this
        // env var once, at process/native-library load time.
        PrivateServerSettings.configureLaunch(this)
        loadNativeLibraries(resolvedProduct)

        // Both of these must happen before super.onCreate(), which is what starts SDLActivity's
        // own lifecycle towards eventually calling SDL_main - by the time the guest boots (audio
        // init in particular), dsp_coef.bin and wii_bootstrap/ must already be on disk, or
        // RuntimeMain fails outright (confirmed on a clean install: SDL_main returning a nonzero
        // exit code makes SDLActivity's own Java glue call finish(), which looks exactly like an
        // ordinary close, not a crash - see runtime/src/hle/audio/ax_mix.cpp and nand_path.h for
        // why the runtime looks in the app's files dir for these instead of an executable
        // directory, which doesn't exist on Android).
        ensureBundledAssets()
        acquireMulticastLock()

        // The native side (dvd.cpp's WaitForAndroidDvdRoot, Android-only) polls this same path
        // rather than failing immediately if it isn't a valid extracted DATA folder yet, since
        // RomImportOverlay below fills it in concurrently - there is no ordering dependency
        // between the two beyond that path string.
        val dvdRoot = File(filesDir, "WiiCompiled/DiscData")
        nativeSetInstallPaths(filesDir.absolutePath, dvdRoot.absolutePath)
        if (resolvedProduct == PRODUCT_RETRO_REWIND) {
            nativeSetRetroRewindRoot(File(filesDir, RETRO_REWIND_STAGING_SUBDIR).absolutePath)
        }

        super.onCreate(savedInstanceState)

        // The settings gear and touch controls are gameplay UI - showing them over the ROM
        // picker would let a player fiddle with in-game settings before there's a game to apply
        // them to. Only add them once a game is actually present (either already imported from a
        // previous run, or - once extraction exists - right after RomImportOverlay finishes).
        if (RomImportOverlay.isRomAlreadyImported(dvdRoot)) {
            showGameUi()
        } else {
            romImportOverlay = RomImportOverlay.attach(this, mLayout, dvdRoot) { showGameUi() }
        }
    }

    private fun loadNativeLibraries(product: String) {
        // See getLibraries() above - reverted off the combined library for now.
        System.loadLibrary("wii")
        System.loadLibrary("png16")
        System.loadLibrary(if (product == PRODUCT_RETRO_REWIND) "RetroRewind" else "WiiCompiled")
    }

    /** Kills this process and relaunches straight back into MainActivity with the new product -
     * the standard Android trick for "this process cannot be reused, start clean." */
    private fun restartIntoProduct(product: String) {
        val relaunch = Intent(this, MainActivity::class.java)
        relaunch.putExtra(EXTRA_PRODUCT, product)
        relaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pending =
            PendingIntent.getActivity(
                this,
                0,
                relaunch,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
            )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + 100, pending)
        Process.killProcess(Process.myPid())
    }

    override fun onDestroy() {
        multicastLock?.release()
        multicastLock = null
        super.onDestroy()
    }

    // Android drops incoming broadcast/multicast UDP packets by default to save battery - without
    // this, the LAN host-discovery broadcasts the game's own network code (runtime/src/hle/net/)
    // relies on would never actually reach the socket, even though the socket itself opens fine.
    // Held for the whole activity lifetime rather than only during a match, since there's no
    // native hook yet that knows when the guest actually enters local-play vs. any other screen.
    private fun acquireMulticastLock() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val lock = wifiManager.createMulticastLock("WiiCompiledLocalPlay")
        lock.setReferenceCounted(false)
        lock.acquire()
        multicastLock = lock
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RomImportOverlay.REQUEST_CODE_PICK_ROM) {
            romImportOverlay?.onFilePicked(data?.data)
            return
        }
        touchControls?.onActivityResult(requestCode, data?.data)
    }

    private fun showGameUi() {
        val gearButton = addSettingsButton()
        touchControls = TouchControlsOverlay.attach(this, mLayout)
        nativeSetTouchControlsVisibleCache(touchControls?.isUserVisible() ?: true)
        touchControls?.onEditModeChanged = { editing -> gearButton.alpha = if (editing) 1f else 0.55f }
        // No floating button (pulled per direct request: "remove steering with motion the button
        // pls for now") - Motion Steering now lives in the settings sidebar's Controller page
        // instead, matching KartPad's own menu placement and dialog exactly.
        motionSteering = MotionSteering(this)
        if (motionSteering?.enabled == true) {
            touchControls?.setMotionSteeringActive(true)
            motionSteering?.start()
        }
        requestAudioFocus()
    }

    /**
     * Android's real equivalent of the PC version's "mute game music while external media is
     * playing" (music_attenuation.cpp - Windows-only there via WinRT media sessions). Requesting
     * normal AUDIOFOCUS_GAIN means a well-behaved music app (Spotify, YouTube Music, etc.)
     * requesting its own focus when the player starts playback triggers our loss callback, and we
     * get AUDIOFOCUS_GAIN back when they stop - the standard Android mechanism for this, not a
     * custom polling loop. Only reports up to native; native decides whether to actually attenuate
     * (gated by the existing "Mute game music while external media is playing" setting).
     */
    private var audioFocusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var duckedForExternalMedia = false

    /**
     * A plain (non-transient) AUDIOFOCUS_LOSS - what a real music app (Spotify, YouTube Music,
     * etc.) triggers, since those request permanent focus for a whole listening session, not a
     * short transient one - is NOT guaranteed to ever hand focus back to us automatically. That
     * guarantee only applies to AUDIOFOCUS_LOSS_TRANSIENT/_CAN_DUCK, where the framework itself
     * re-delivers AUDIOFOCUS_GAIN once the transient interruption ends.
     *
     * A first attempt at working around that (periodically re-requesting AUDIOFOCUS_GAIN on a
     * timer while ducked) was wrong and actively harmful: requesting non-transient GAIN is an
     * EXCLUSIVE request - succeeding at it forcibly steals focus away from whoever currently holds
     * it. Confirmed directly ("when i turn on my music it mutes... the check you added mutes the
     * external music") - the background timer was repeatedly stealing focus back from the
     * player's own music app every few seconds while they were actively listening to it, muting
     * THEM instead of helping.
     *
     * Fix: only ever attempt a reclaim once, at a natural, low-frequency, user-driven point - this
     * app returning to the foreground (onResume) - never on a running background timer. This
     * doesn't guarantee instant recovery the moment the other app stops, but it never fights
     * anyone for focus while they're actively using it either, which matters more.
     */
    private fun requestAudioFocus() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

        val listener =
            AudioManager.OnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                    -> {
                        duckedForExternalMedia = true
                        nativeReportExternalMediaPlaying(true)
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        duckedForExternalMedia = false
                        nativeReportExternalMediaPlaying(false)
                    }
                }
            }
        audioFocusListener = listener
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes =
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            val request =
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener(listener)
                    .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    /**
     * A single reclaim attempt on onResume alone turned out not to be enough: it only helps if the
     * player actually leaves and comes back to this app. Staying in-app the whole time while
     * toggling external music on/off - a completely normal thing to do - never generates a resume
     * event at all, so ducked audio just stayed muted indefinitely. Confirmed directly ("music
     * ducking is still not working well, its not unmuting").
     *
     * There is no way to check "is something else playing" on this platform without either (a)
     * briefly re-requesting focus - which can interrupt the other app if it's still going, since a
     * successful non-transient AUDIOFOCUS_GAIN request is exclusive - or (b) a passive check like
     * isMusicActive(), which this game's own continuously-running audio output would contaminate
     * regardless of whether anything external is playing. Given that, this polls for reclaim
     * periodically (not just once), but only while the app is actually resumed/foreground (never
     * while backgrounded) and at a much slower interval than the original, fully-broken attempt
     * (8s here vs. the original 3s) to keep how often it can possibly interrupt another app as low
     * as practical while still being reasonably responsive.
     */
    private var audioFocusPollHandler: Handler? = null
    private var audioFocusPollRunnable: Runnable? = null

    private fun tryReclaimAudioFocusIfDucked() {
        if (!duckedForExternalMedia) return
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val result =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.requestAudioFocus(it) } ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
            } else {
                val listener = audioFocusListener ?: return
                audioManager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            duckedForExternalMedia = false
            nativeReportExternalMediaPlaying(false)
        }
    }

    private fun startAudioFocusPolling() {
        if (audioFocusPollHandler == null) audioFocusPollHandler = Handler(Looper.getMainLooper())
        if (audioFocusPollRunnable != null) return
        val runnable =
            object : Runnable {
                override fun run() {
                    tryReclaimAudioFocusIfDucked()
                    audioFocusPollHandler?.postDelayed(this, AUDIO_FOCUS_POLL_INTERVAL_MS)
                }
            }
        audioFocusPollRunnable = runnable
        audioFocusPollHandler?.postDelayed(runnable, AUDIO_FOCUS_POLL_INTERVAL_MS)
    }

    private fun stopAudioFocusPolling() {
        audioFocusPollRunnable?.let { audioFocusPollHandler?.removeCallbacks(it) }
        audioFocusPollRunnable = null
    }

    override fun onPause() {
        super.onPause()
        if (motionSteering?.enabled == true) motionSteering?.stop()
        stopAudioFocusPolling()
    }

    override fun onResume() {
        super.onResume()
        if (motionSteering?.enabled == true) motionSteering?.start()
        tryReclaimAudioFocusIfDucked()
        startAudioFocusPolling()
    }

    /** Called from native (settings_overlay.cpp's "Motion Steering" sidebar button, via
     * AndroidShowMotionSteeringDialog). May arrive off the UI thread. */
    fun onNativeShowMotionSteeringDialog() {
        runOnUiThread { showMotionSteeringOptionsDialog() }
    }

    /**
     * Action-list dialog matching KartPad's own Motion Steering menu exactly (dev.kartpad.android's
     * KartPadActivity.showMotionSteering): a "Turn On & Recenter"/"Turn Off" action, "Recenter Now"
     * (only while on), an invert toggle, and a sensitivity CYCLE (0.5x -> 1x -> 2x -> 0.5x) rather
     * than a slider, plus "Continue Playing" to dismiss. Every action except "Continue Playing"
     * reopens the dialog afterward so the updated state is immediately visible, same as KartPad.
     */
    private fun showMotionSteeringOptionsDialog() {
        val steering = motionSteering ?: return
        val available = steering.sensorAvailable
        val state =
            when {
                !available -> "Unavailable on this device"
                steering.enabled -> "On"
                else -> "Off"
            }
        val actions =
            if (!available) {
                arrayOf("Continue Playing")
            } else if (steering.enabled) {
                arrayOf(
                    "Turn Off",
                    "Recenter Now",
                    if (steering.inverted) "Use Standard Direction" else "Invert Direction",
                    "Cycle Sensitivity",
                    "Continue Playing",
                )
            } else {
                arrayOf(
                    "Turn On & Recenter",
                    if (steering.inverted) "Use Standard Direction" else "Invert Direction",
                    "Cycle Sensitivity",
                    "Continue Playing",
                )
            }

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        val pad = (24 * resources.displayMetrics.density).toInt()
        content.setPadding(pad, (4 * resources.displayMetrics.density).toInt(), pad, (8 * resources.displayMetrics.density).toInt())

        val label = TextView(this)
        label.text =
            if (available) {
                "Tilt the device like a steering wheel. Current state: $state. " +
                    "Sensitivity: ${steering.sensitivity}x. Physical controllers take priority."
            } else {
                "Motion data is unavailable on this device or emulator. Touch and " +
                    "physical-controller steering remain available."
            }
        content.addView(label)

        val dialog =
            AlertDialog.Builder(this)
                .setTitle("Motion Steering")
                .setView(ScrollView(this).apply { addView(content) })
                .create()

        fun applyEnabled(value: Boolean) {
            steering.enabled = value
            touchControls?.setMotionSteeringActive(value)
            if (value) steering.start() else steering.stop()
        }

        actions.forEach { action ->
            content.addView(
                Button(this).apply {
                    text = action
                    contentDescription = action
                    setOnClickListener {
                        dialog.dismiss()
                        when (action) {
                            "Turn Off" -> applyEnabled(false)
                            "Recenter Now" -> steering.recenter()
                            "Invert Direction" -> steering.inverted = true
                            "Use Standard Direction" -> steering.inverted = false
                            "Cycle Sensitivity" ->
                                steering.sensitivity =
                                    when (steering.sensitivity) {
                                        0.5f -> 1f
                                        1f -> 2f
                                        else -> 0.5f
                                    }
                            "Turn On & Recenter" -> {
                                applyEnabled(true)
                                steering.recenter()
                            }
                        }
                        if (action != "Continue Playing") {
                            mLayout.post { showMotionSteeringOptionsDialog() }
                        }
                    }
                },
            )
        }
        dialog.show()
    }

    /** Called from native (android_jni_bridge.cpp's AndroidNotifyGamepadConnectionChanged), which
     * aurora-main/lib/window.cpp drives from real SDL_EVENT_GAMEPAD_ADDED/REMOVED - the touch
     * overlay's own virtual gamepad is filtered out there already. May arrive on the SDL_main
     * thread, not the UI thread. */
    fun onNativeGamepadConnectionChanged(connected: Boolean) {
        runOnUiThread { touchControls?.setControllerConnected(connected) }
    }

    /** Called from native (the in-game settings menu's "Touch controls" checkbox,
     * settings_overlay.cpp, via AndroidSetTouchOverlayVisible). May arrive off the UI thread. */
    fun onNativeSetTouchOverlayVisible(visible: Boolean) {
        runOnUiThread { touchControls?.setUserVisible(visible) }
    }

    /** Called from native (the settings sidebar's "Edit Touch Layout" button, settings_overlay.cpp
     * via AndroidStartTouchLayoutEdit). May arrive off the UI thread. Replaces the old "hold the
     * gear button for 5 seconds" gesture now that the sidebar has an explicit entry point. */
    fun onNativeStartTouchLayoutEdit() {
        runOnUiThread { touchControls?.setEditMode(true) }
    }

    /** Called from native (SetTopBarVisible in settings_overlay.cpp, via
     * AndroidNotifySettingsVisibilityChanged) whenever the settings sidebar opens/closes - touch
     * controls hide while it's open since they'd otherwise sit underneath it (confirmed directly
     * on-device) and gameplay input is already blocked while it's up regardless. May arrive off
     * the UI thread. */
    fun onNativeSettingsVisibilityChanged(visible: Boolean) {
        runOnUiThread { touchControls?.setSettingsOpen(visible) }
    }

    // Desktop's CMake build copies these next to the built executable (see
    // runtime/cmake/PublicProducts.cmake); Android has no equivalent "next to the executable"
    // location, so they ride along as APK assets instead and get copied out here, once, the same
    // way the first-run installer would on desktop. Idempotent and cheap after the first launch.
    private fun ensureBundledAssets() {
        val wiiCompiledDir = File(filesDir, "WiiCompiled")
        copyAssetIfMissing("dsp_coef.bin", File(wiiCompiledDir, "dsp_coef.bin"))
        for (relative in BUNDLED_BOOTSTRAP_FILES) {
            copyAssetIfMissing(relative, File(wiiCompiledDir, relative))
        }
    }

    private fun copyAssetIfMissing(assetPath: String, dest: File) {
        if (dest.exists()) {
            return
        }
        dest.parentFile?.mkdirs()
        assets.open(assetPath).use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
    }

    companion object {
        const val EXTRA_PRODUCT = "product"
        const val PRODUCT_BASE = "base"
        const val PRODUCT_RETRO_REWIND = "retro_rewind"

        // Set once per process by whichever product's onCreate runs first - see the process-
        // restart guard above. Not an instance field: it must survive across MainActivity
        // instances within the same still-alive process.
        private var sLoadedProduct: String? = null

        // Staging path for the Retro Rewind asset pack (tracks/textures - distinct from Code.pul,
        // which only carries the mod's patched game LOGIC and is already compiled into
        // libGameCombined.so). Populated by ModePickerActivity's folder-import flow, mirroring how
        // the PC version's Retro Rewind support works (WiiCompiled.Setup/RetroRewindSource.cs only
        // ever resolves a folder the user already has - it never packages or downloads one).
        const val RETRO_REWIND_STAGING_SUBDIR = "WiiCompiled/RetroRewind6"

        // Mirrors runtime/assets/wii/ (see nand_path.h's BootstrapPayloadPath) - Dolphin's own
        // free, synthetic WiiConnect24 bootstrap tree, not real user data (see
        // THIRD-PARTY-NOTICES.md).
        private val BUNDLED_BOOTSTRAP_FILES =
            listOf(
                "wii_bootstrap/README.md",
                "wii_bootstrap/shared2/wc24/misc.bin",
                "wii_bootstrap/shared2/wc24/nwc24dl.bin",
                "wii_bootstrap/shared2/wc24/nwc24fl.bin",
                "wii_bootstrap/shared2/wc24/nwc24fls.bin",
                "wii_bootstrap/shared2/wc24/nwc24msg.cbk",
                "wii_bootstrap/shared2/wc24/nwc24msg.cfg",
                "wii_bootstrap/shared2/wc24/mbox/Readme.txt",
                "wii_bootstrap/shared2/wc24/mbox/wc24recv.ctl",
                "wii_bootstrap/shared2/wc24/mbox/wc24recv.mbx",
                "wii_bootstrap/shared2/wc24/mbox/wc24send.ctl",
                "wii_bootstrap/shared2/wc24/mbox/wc24send.mbx",
            )

        private const val GEAR_PREFS_NAME = "settings_gear"
        private const val GEAR_KEY_X = "x"
        private const val GEAR_KEY_Y = "y"
        // A curved-edge screen's curve runs the FULL LENGTH of the phone's two long physical
        // edges - which, in landscape, is the entire top AND bottom edge of the screen, not just
        // the corners (confirmed by direct user feedback: the original ALIGN_PARENT_TOP/END
        // corner placement was hard to press). That means no y-inset near the top edge is fully
        // clear of the curve regardless of x - staying at x=0.90 (same column as the R touch
        // control) to gain y-clearance just overlapped R instead (confirmed on-device
        // screenshot). Top-CENTER instead: still near the top (some curve exposure is
        // unavoidable without leaving the top edge entirely) but far from every other touch
        // control on either side, and corners - where two curved edges meet - are usually the
        // single hardest spot to press precisely, so center is a real improvement over the
        // original corner placement even before dragging. Draggable below regardless, for
        // whatever exact spot ends up working best on this specific device.
        private const val GEAR_DEFAULT_X_FRACTION = 0.55f
        private const val GEAR_DEFAULT_Y_FRACTION = 0.08f

        private const val AUDIO_FOCUS_POLL_INTERVAL_MS = 8000L
    }

    // Desktop opens the (already fully built) in-game settings overlay with F10 - there is no
    // keyboard here, so this is the touch equivalent. A plain native Android view instead of an
    // Aurora/ImGui-drawn one on purpose: it must keep working even while Aurora's own render loop
    // is doing something unexpected, and it never competes with ImGui for the touch event. Long
    // press enters touch-control layout editing (drag/resize/hide) instead - see
    // TouchControlsOverlay. Draggable (via the same EditGestureHelper every other on-screen
    // control uses) and defaults away from the extreme corner, since a curved-edge screen's curve
    // sits right there and made it hard to press precisely.
    private fun addSettingsButton(): Button {
        val prefs: SharedPreferences = getSharedPreferences(GEAR_PREFS_NAME, Context.MODE_PRIVATE)
        val button = Button(this)
        button.text = "⚙"
        button.alpha = 0.55f
        button.setTextColor(Color.WHITE)
        // Circular instead of the default square button background - requested directly, and
        // pairs with the settings sidebar now being the single entry point for both opening
        // settings and (via its own "Edit Touch Layout" button) touch layout editing.
        val circleBackground =
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.argb(120, 0, 0, 0))
            }
        button.background = circleBackground

        // Edit mode is now entered via the settings sidebar's "Edit Touch Layout" button
        // (onNativeStartTouchLayoutEdit), not by holding this button - the old 5-second-hold
        // gesture is gone. While edit mode IS active (entered from the sidebar), touches here
        // still drag the gear itself out of the way of whatever's being edited.
        val editGesture =
            EditGestureHelper(this, button) { moved ->
                if (moved) {
                    val parentWidth = mLayout.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
                    val parentHeight = mLayout.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
                    // Clamped to stay fully on-screen - a drag that ends past the edge (e.g. a
                    // fast fling, or a gesture interrupted mid-drag by something else grabbing
                    // focus, such as an incoming call) previously persisted an out-of-range
                    // fraction that placed the gear permanently off-screen on every future
                    // launch, with no way to get it back short of clearing app data - confirmed
                    // directly on-device.
                    val xFraction = ((button.x + button.width / 2f) / parentWidth).coerceIn(0.05f, 0.95f)
                    val yFraction = ((button.y + button.height / 2f) / parentHeight).coerceIn(0.05f, 0.95f)
                    prefs.edit().putFloat(GEAR_KEY_X, xFraction).putFloat(GEAR_KEY_Y, yFraction).apply()
                }
            }
        button.setOnTouchListener { _, event ->
            if (touchControls?.isEditMode() == true) {
                editGesture.onTouchEvent(event)
                true
            } else {
                false
            }
        }
        button.setOnClickListener { nativeToggleSettingsOverlay() }

        // Bigger than the original 48dp - confirmed on-device, that was hard to hit precisely
        // with a finger.
        val sizePx = (64 * resources.displayMetrics.density).toInt()
        val params = RelativeLayout.LayoutParams(sizePx, sizePx)
        mLayout.addView(button, params)
        button.textSize = 22f

        // Positioned in pixels (not gravity/margins) so drag-to-reposition above can move it
        // freely - fractions convert once the layout has a real size, same approach
        // TouchControlsOverlay uses for its own controls.
        mLayout.post {
            val parentWidth = mLayout.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val parentHeight = mLayout.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
            val xFraction = prefs.getFloat(GEAR_KEY_X, GEAR_DEFAULT_X_FRACTION)
            val yFraction = prefs.getFloat(GEAR_KEY_Y, GEAR_DEFAULT_Y_FRACTION)
            button.x = xFraction * parentWidth - sizePx / 2f
            button.y = yFraction * parentHeight - sizePx / 2f
        }
        return button
    }
}
