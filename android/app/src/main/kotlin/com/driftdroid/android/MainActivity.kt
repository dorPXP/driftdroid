package com.driftdroid.android

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RelativeLayout
import com.driftdroid.android.rom.RomImportOverlay
import com.driftdroid.android.touch.EditGestureHelper
import com.driftdroid.android.touch.TouchControlsOverlay
import com.driftdroid.android.ui.DriftSheet
import com.driftdroid.android.ui.DriftUi
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
 * with [EXTRA_PRODUCT] set: neither the combined native library (libGameCombined.so - both
 * products, see runtime/cmake/build_combined_android_lib.py, not yet safe to ship - see
 * getLibraries()'s own doc comment) nor a running game has a supported way to reset and switch
 * profiles live - the Original/Retro Rewind choice must be resolved before SDLActivity's own
 * lifecycle (which calls getLibraries()) even starts, and restartIntoProduct() below still fully
 * kills the process rather than trying to switch live.
 */
class MainActivity : SDLActivity() {

    private external fun nativeSetInstallPaths(filesDir: String, dvdRoot: String)
    private external fun nativeSetRetroRewindRoot(retroRewindRoot: String)
    private external fun nativeAddOverlayRoot(overlayRoot: String)
    private external fun nativeSetDetectedWidescreen(widescreen: Boolean)
    private external fun nativeSetActiveProduct(retroRewind: Boolean)
    private external fun nativeToggleSettingsOverlay()
    private external fun nativeSetDeviceCanVibrate(canVibrate: Boolean)

    /** The gear can be hidden (GitHub issue #5); Back then opens the settings sidebar instead. */
    private var settingsButton: Button? = null
    private var nativeSettingsButtonHidden = false

    /** Called from native (settings_overlay.cpp's "Hide the settings button" checkbox). */
    fun onNativeSetSettingsButtonHidden(hidden: Boolean) {
        nativeSettingsButtonHidden = hidden
        runOnUiThread { settingsButton?.visibility = if (hidden) View.GONE else View.VISIBLE }
    }

    /**
     * SDL's key handling swallows KEYCODE_BACK before Android ever calls onBackPressed (it returns
     * true for both the down and up events, see SDLSurface.onKey), so the sidebar has to be opened
     * from here instead. Confirmed on device: the override of onBackPressed never ran.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            nativeToggleSettingsOverlay()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // Pull down from the top strip to open the quick settings panel. A swipe that starts exactly at the
    // screen edge is taken by Android's own bar reveal in full-screen mode, so the gesture starts a little
    // below it. Not while the touch layout is being edited, and it only takes the swipe when the panel
    // really opened (a scroll inside the open settings menu is left alone).
    private var pullStartY = -1f
    private var pullStartX = 0f
    private var layoutEditing = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val zone = minOf(resources.displayMetrics.heightPixels * 0.16f, 110f * density)
                pullStartY = if (!layoutEditing && ev.y < zone) ev.y else -1f
                pullStartX = ev.x
            }
            MotionEvent.ACTION_MOVE -> if (pullStartY >= 0f && ev.pointerCount == 1) {
                val dy = ev.y - pullStartY
                val dx = kotlin.math.abs(ev.x - pullStartX)
                if (dy > 56f * density && dy > dx * 1.5f) {
                    pullStartY = -1f
                    if (nativeOpenQuickPanel()) {
                        val cancel = MotionEvent.obtain(ev)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> pullStartY = -1f
        }
        return super.dispatchTouchEvent(ev)
    }
    private external fun nativeOpenQuickPanel(): Boolean
    private external fun nativeSetSystemLanguage(code: String)
    private external fun nativeSetTouchControlsVisibleCache(visible: Boolean)
    private external fun nativeSetDoubleTapAutoHoldCache(enabled: Boolean)
    private external fun nativeReportExternalMediaPlaying(playing: Boolean)
    private external fun nativeReportThermalHeadroom(headroom: Float)

    private var touchControls: TouchControlsOverlay? = null
    private var romImportOverlay: RomImportOverlay? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var resolvedProduct: String = PRODUCT_BASE
    private var motionSteering: MotionSteering? = null
    private var gamepadConnected = false
    private var wiiWheel: WiiWheelMode? = null
    // What this game session started with; the runtime only reads wii_wheel.cfg at start-up.
    private var wiiWheelActiveThisSession = false
    // Never requests audio focus: any focus request pauses the player's own music app.
    private val externalMediaDetector by lazy { ExternalMediaDetector(this) { nativeReportExternalMediaPlaying(it) } }
    private val thermalMonitor by lazy { ThermalMonitor(this) { nativeReportThermalHeadroom(it) } }

    override fun getLibraries(): Array<String> {
        // Original loads its OWN standalone libWiiCompiled.so, not the combined library - found
        // 2026-09-09 that Original (and only Original, not Retro Rewind) had a real gameplay bug
        // on the combined .so: steering wasn't blocked during the pre-race "3-2-1-GO" countdown,
        // letting the kart move before GO. Confirmed directly on-device that switching Original
        // alone back to its own untouched libWiiCompiled.so (never passes through the combined-lib
        // merge/rename script) fixes it - so this is a leftover profile-selection defect in that
        // merge specific to the "base" profile, not a genuine bug in Original's own translated
        // logic (which runs correctly here, unmerged). The exact defect in the merge itself
        // (some function that should differ per profile - most likely the race-countdown input
        // gate - still being resolved wrong for "base" specifically) has NOT been root-caused yet;
        // this split is the real fix for now, not a temporary diagnostic, until someone does that
        // deeper dispatch-table archaeology.
        if (resolvedProduct == PRODUCT_BASE) {
            return arrayOf("wii", "png16", "WiiCompiled")
        }
        // Retro Rewind still loads the combined library - confirmed working correctly there
        // (see the memory above for the full multi-session fix history that made this safe:
        // transitive sensitivity closure + source-level symbol aliasing in
        // TranslatedBuildShardEmitter.cs, profile-tagged registry in abi_bridge.cpp, deferred mod
        // registration in recomp_mod_loader.cpp). Still outstanding: the combined-lib CMake custom
        // command doesn't track mkw_combined_product's object content for staleness - always
        // `rm libGameCombined.so` before relinking after any C++ change, or ninja may silently
        // reuse a stale link.
        return arrayOf("wii", "png16", "GameCombined")
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
        // Vertical mode: upright screen, read by aurora's window setup (SDL_HINT_ORIENTATIONS) from
        // this variable, so it has to be set before the native side starts.
        val vertical = VerticalMode.isEnabled(this)
        android.system.Os.setenv("DRIFTDROID_VERTICAL", if (vertical) "1" else "0", true)
        requestedOrientation =
            if (vertical) {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        TouchControlsOverlay.portraitMode = vertical
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
        I18n.init(this)
        nativeSetSystemLanguage(I18n.systemLanguage(this))  // what "Automatic" means for the game's menus
        DeviceRumble.attach(this)
        nativeSetDeviceCanVibrate(DeviceRumble.canVibrate)
        if (resolvedProduct == PRODUCT_RETRO_REWIND) {
            nativeSetRetroRewindRoot(File(filesDir, RETRO_REWIND_STAGING_SUBDIR).absolutePath)
        }
        for (root in ModManager.enabledOverlayRoots(this)) {
            nativeAddOverlayRoot(root)
        }
        // Only ever used as a FIRST-RUN default (native side only writes it when no explicit
        // [video] widescreen key exists in Config.toml yet, i.e. the user has never touched the
        // settings sidebar's "Aspect Ratio" picker) - reported directly: on a real 4:3-screened
        // device (Retroid Pocket Nova), the game was always defaulting to 16:9 regardless of the
        // actual screen shape, mis-positioning HUD/menu elements toward the center instead of the
        // corners. `resources.displayMetrics` reflects the real physical screen here since this
        // Activity is locked to sensorLandscape (AndroidManifest.xml).
        val metrics = resources.displayMetrics
        val landscapeAspect = maxOf(metrics.widthPixels, metrics.heightPixels).toFloat() /
            minOf(metrics.widthPixels, metrics.heightPixels).toFloat()
        // Halfway between a true 4:3 (1.333) and true 16:9 (1.778) screen.
        nativeSetDetectedWidescreen(landscapeAspect >= 1.55f)
        // A no-op on the separate-library builds (getLibraries() below) - only the combined
        // library's RuntimeProduct provider (combined_product.cpp) does anything with this, and
        // it must run before super.onCreate() starts SDL's thread towards main(), which is when
        // TranslatedFunctionRegistry::Finalize() locks in which generated dispatch table is active.
        nativeSetActiveProduct(resolvedProduct == PRODUCT_RETRO_REWIND)

        super.onCreate(savedInstanceState)

        // Without this, Android is free to apply its default (often battery-biased) governor
        // behavior to this process even while it's pegging the CPU/GPU every frame - this is a
        // direct request for the OS to favor sustained throughput over battery/thermal headroom
        // for as long as this window is visible. No-ops harmlessly on hardware that doesn't
        // support it (see PowerManager.setSustainedPerformanceMode's own docs).
        window.setSustainedPerformanceMode(true)

        // Many phones default a new window to 60Hz even on 90/120Hz-capable displays unless the
        // app explicitly opts into a higher refresh rate mode - without this, the in-game "Race
        // frame interpolation" setting above 60 FPS has nothing to actually present onto and the
        // display stays locked at 60 regardless (confirmed on-device: selecting 90 FPS there had
        // no visible effect until this was added).
        SDLActivity.mContentFrameRate = configuredTargetFps().toFloat()
        requestRefreshRateFor(configuredTargetFps())

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
        // See getLibraries() above.
        System.loadLibrary("wii")
        System.loadLibrary("png16")
        loadGameLibrary(product)
    }

    /** The game code: from the imported game pack (GamePack.kt). Only a development build, which
     * carries no toolkit id and has the libraries inside the APK, falls back to those. */
    private fun gameLibraryFile(product: String): java.io.File? {
        val name = if (product == PRODUCT_BASE) "libWiiCompiled.so" else "libGameCombined.so"
        val file = GamePack.library(this, name)
        return if (GamePack.state(this) == GamePack.State.Ready) file else null
    }

    private fun loadGameLibrary(product: String) {
        val file = gameLibraryFile(product)
        if (file != null) {
            System.load(file.absolutePath)
        } else {
            System.loadLibrary(if (product == PRODUCT_BASE) "WiiCompiled" else "GameCombined")
        }
    }

    // SDL loads getLibraries() by name from the APK; the game library comes from the game pack.
    override fun loadLibraries() {
        for (lib in getLibraries().dropLast(1)) {
            System.loadLibrary(lib)
        }
        loadGameLibrary(resolvedProduct)
    }

    override fun getMainSharedObject(): String =
        gameLibraryFile(resolvedProduct)?.absolutePath ?: super.getMainSharedObject()

    /** Picks the display mode that matches what the game actually produces - Android does not do
     * this by default, even on a 90/120Hz-capable screen. Deliberately not the fastest mode: the
     * game renders 60fps unless frame interpolation is on, and holding a 120Hz panel at 120Hz
     * keeps the whole display pipeline running twice as fast for frames that are then discarded,
     * which is heat for nothing on a device that throttles. */
    private fun requestRefreshRateFor(targetFps: Int) {
        val display =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay
        val currentMode = display?.mode ?: return
        val sameSizeModes =
            display.supportedModes.filter {
                it.physicalWidth == currentMode.physicalWidth &&
                    it.physicalHeight == currentMode.physicalHeight
            }
        if (sameSizeModes.isEmpty()) {
            return
        }
        // The slowest mode that still covers the target, so 60fps content gets a 60Hz panel;
        // fall back to the fastest when nothing reaches the target (e.g. 120fps on a 60Hz screen).
        val mode =
            sameSizeModes.filter { it.refreshRate >= targetFps - REFRESH_RATE_EPSILON }
                .minByOrNull { it.refreshRate }
                ?: sameSizeModes.maxByOrNull { it.refreshRate }
                ?: return
        val attributes = window.attributes
        attributes.preferredDisplayModeId = mode.modeId
        attributes.preferredRefreshRate = mode.refreshRate
        window.attributes = attributes
    }

    /** Reads video.frame_interpolation_fps straight out of Config.toml, which the runtime owns.
     * 0 (the default) means "no interpolation", i.e. the game's own 60fps. */
    private fun configuredTargetFps(): Int {
        val config = File(File(filesDir, "WiiCompiled"), "Config.toml")
        val configured =
            runCatching {
                config.takeIf { it.isFile }
                    ?.readLines()
                    ?.firstOrNull { it.trimStart().startsWith("frame_interpolation_fps") }
                    ?.substringAfter('=')
                    ?.trim()
                    ?.toIntOrNull()
            }.getOrNull() ?: 0
        return if (configured > 0) configured else 60
    }

    /** Kills this process and relaunches straight back into MainActivity with the new product -
     * the standard Android trick for "this process cannot be reused, start clean." */
    private fun restartIntoProduct(product: String) {
        // RestartActivity (its own process) starts the game again once this process is gone. An
        // alarm set here and fired after the kill is a background activity start, which Android
        // 10+ and Huawei block, so the game just closed.
        val restart = Intent(this, RestartActivity::class.java)
        restart.putExtra(EXTRA_PRODUCT, product)
        restart.putExtra(RestartActivity.EXTRA_OLD_PID, Process.myPid())
        // startActivity hands the request to the system before it returns, so killing straight
        // away is safe (and needed: the onCreate path returns without super.onCreate()).
        startActivity(restart)
        overridePendingTransition(0, 0)
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
        settingsButton = gearButton
        gearButton.visibility = if (nativeSettingsButtonHidden) View.GONE else View.VISIBLE
        touchControls = TouchControlsOverlay.attach(this, mLayout)
        nativeSetTouchControlsVisibleCache(touchControls?.isUserVisible() ?: true)
        nativeSetDoubleTapAutoHoldCache(touchControls?.isDoubleTapAutoHoldEnabled() ?: false)
        touchControls?.onEditModeChanged = { editing ->
            layoutEditing = editing
            gearButton.alpha = if (editing) 1f else 0.55f
        }
        // No floating button (pulled per direct request: "remove steering with motion the button
        // pls for now") - Motion Steering now lives in the settings sidebar's Controller page
        // instead, matching KartPad's own menu placement and dialog exactly.
        motionSteering = MotionSteering(this)
        // A controller can connect before the game screen is up.
        motionSteering?.controllerConnected = gamepadConnected
        touchControls?.setControllerConnected(gamepadConnected)
        wiiWheel = WiiWheelMode(this)
        wiiWheelActiveThisSession = wiiWheel?.let { it.sessionMode != WiiWheelMode.MODE_OFF && it.sensorAvailable } ?: false
        if (wiiWheelActiveThisSession) {
            // The game's own Wii Wheel code steers; tilt steering on the touch stick would fight it.
            wiiWheel?.start()
        } else if (motionSteering?.enabled == true) {
            touchControls?.setMotionSteeringActive(true)
            motionSteering?.start()
        }
    }

    override fun onPause() {
        super.onPause()
        if (motionSteering?.enabled == true) motionSteering?.stop()
        wiiWheel?.stop()
        externalMediaDetector.stop()
        thermalMonitor.stop()
        DeviceRumble.setPaused(true)
    }

    override fun onResume() {
        super.onResume()
        if (wiiWheelActiveThisSession) {
            wiiWheel?.start()
        } else if (motionSteering?.enabled == true) {
            motionSteering?.start()
        }
        externalMediaDetector.start()
        thermalMonitor.start()
        DeviceRumble.setPaused(false)
    }

    /** Called from native (settings_overlay.cpp's "Motion Steering" sidebar button, via
     * AndroidShowMotionSteeringDialog). May arrive off the UI thread. */
    fun onNativeShowMotionSteeringDialog() {
        runOnUiThread { showMotionSteeringOptionsDialog() }
    }

    /**
     * The motion controls menu, in the settings drawer's look. One "Steer with" choice (stick,
     * plain tilt, or the game's own Wii Wheel) and a "Shake for tricks" switch, so it's clear which
     * tilt is in use: two tilt sections with one greyed out read as broken. Plain tilt applies at
     * once; anything that changes the Wii Wheel mode needs a restart (the game picks its
     * controller at start-up and the runtime reads the mode once per process), so that waits for
     * a "Restart" press.
     */
    private fun showMotionSteeringOptionsDialog() {
        val steering = motionSteering ?: return
        val wheel = wiiWheel
        val wheelAvailable = wheel != null && wheel.sensorAvailable
        val sessionWheelMode = if (wiiWheelActiveThisSession) wheel?.sessionMode ?: WiiWheelMode.MODE_OFF else WiiWheelMode.MODE_OFF
        val steerStick = 0
        val steerTilt = 1
        val steerWheel = 2
        var steerPick =
            when {
                sessionWheelMode == WiiWheelMode.MODE_TILT -> steerWheel
                steering.enabled && sessionWheelMode == WiiWheelMode.MODE_OFF -> steerTilt
                else -> steerStick
            }
        var shakePick = sessionWheelMode != WiiWheelMode.MODE_OFF
        val sheet = DriftSheet(this, T("Motion controls"), T("Steer and trick by moving the phone"))

        fun wantedWheelMode(): Int =
            when {
                steerPick == steerWheel -> WiiWheelMode.MODE_TILT
                shakePick -> WiiWheelMode.MODE_SHAKE_ONLY
                else -> WiiWheelMode.MODE_OFF
            }

        fun applyTilt(value: Boolean) {
            if (steering.enabled == value) return
            steering.enabled = value
            touchControls?.setMotionSteeringActive(value)
            if (value) {
                steering.start()
                steering.recenter()
            } else {
                steering.stop()
            }
        }

        fun render() {
            sheet.clear()
            if (!steering.sensorAvailable) {
                sheet.note(
                    "This phone doesn't report its motion, so tilt steering and shaking can't work here. " +
                        "Touch and controller steering still work.",
                )
                sheet.button("Done", DriftUi.Style.PRIMARY) { sheet.dismiss() }
                return
            }
            val needsRestart = wantedWheelMode() != sessionWheelMode

            sheet.section("Steering")
            val options = if (wheelAvailable) listOf("Stick", "Tilt", "Wii Wheel") else listOf("Stick", "Tilt")
            sheet.choice(
                "Steer with",
                when (steerPick) {
                    steerTilt -> "Tilt the phone like a wheel. Push the stick to take over."
                    steerWheel -> "The game's own Wii Wheel: hold the phone like one. Shaking does tricks. Push the stick to take over."
                    else -> "The stick or a controller."
                },
                options,
                steerPick,
            ) {
                steerPick = it
                if (steerPick == steerWheel) shakePick = true
                if (steerPick == steerTilt) shakePick = false
                // Plain tilt can switch on and off right away, unless the Wii Wheel has to change too.
                if (wantedWheelMode() == sessionWheelMode) applyTilt(steerPick == steerTilt)
                render()
            }
            val tiltLive = steerPick == steerTilt && !needsRestart
            if (steerPick == steerTilt) {
                val sensitivities = listOf(0.5f, 1f, 2f)
                sheet.choice(
                    "Sensitivity",
                    "How far you tilt for a full turn",
                    listOf("Low", "Normal", "High"),
                    sensitivities.indexOf(steering.sensitivity).takeIf { it >= 0 } ?: 1,
                ) { steering.sensitivity = sensitivities[it] }
                sheet.toggle("Flip direction", "For when tilting left turns right", steering.inverted) { steering.inverted = it }
                val straighten = sheet.action("Set straight ahead", "Uses how you hold the phone right now") { steering.recenter() }
                DriftUi.setUsable(straighten, tiltLive)
            } else if (steerPick == steerWheel) {
                val straighten = sheet.action("Set straight ahead", "Uses how you hold the phone right now") { wheel?.recenter() }
                DriftUi.setUsable(straighten, !needsRestart)
            }

            if (wheelAvailable) {
                sheet.section("Tricks")
                val shakeRow =
                    sheet.toggle(
                        "Shake for tricks",
                        when (steerPick) {
                            steerWheel -> "Always on with the Wii Wheel"
                            steerTilt -> "Not together with Tilt yet: use the Wii Wheel for both"
                            else -> "Flick the phone in the air for a trick, or on a bike for a wheelie"
                        },
                        shakePick,
                    ) {
                        shakePick = it
                        render()
                    }
                DriftUi.setUsable(shakeRow, steerPick == steerStick)
            }

            if (needsRestart) {
                sheet.note("This change restarts the game. A race in progress is not saved.")
                sheet.button("Cancel") {
                    steerPick =
                        when {
                            sessionWheelMode == WiiWheelMode.MODE_TILT -> steerWheel
                            steering.enabled && sessionWheelMode == WiiWheelMode.MODE_OFF -> steerTilt
                            else -> steerStick
                        }
                    shakePick = sessionWheelMode != WiiWheelMode.MODE_OFF
                    render()
                }
                sheet.button("Restart", DriftUi.Style.PRIMARY) {
                    steering.enabled = steerPick == steerTilt
                    wiiWheel?.mode = wantedWheelMode()
                    sheet.dismiss()
                    restartIntoProduct(resolvedProduct)
                }
            } else {
                sheet.button("Done", DriftUi.Style.PRIMARY) { sheet.dismiss() }
            }
        }
        render()
        sheet.show()
    }

    /** Called from native (android_jni_bridge.cpp's AndroidNotifyGamepadConnectionChanged), which
     * aurora-main/lib/window.cpp drives from real SDL_EVENT_GAMEPAD_ADDED/REMOVED - the touch
     * overlay's own virtual gamepad is filtered out there already. May arrive on the SDL_main
     * thread, not the UI thread. */
    fun onNativeGamepadConnectionChanged(connected: Boolean) {
        runOnUiThread {
            gamepadConnected = connected
            touchControls?.setControllerConnected(connected)
            motionSteering?.controllerConnected = connected
        }
    }

    /** Called from native (aurora pad.cpp's PADControlMotor, via android_jni_bridge.cpp's
     * AndroidDeviceRumble) when the phone should vibrate in place of player one's motorless
     * controller: the strength in percent, or 0 to stop. May arrive off the UI thread. */
    fun onNativeDeviceRumble(strengthPercent: Int) {
        DeviceRumble.setStrength(strengthPercent)
    }

    /** Called from native (the in-game settings menu's "Touch controls" checkbox,
     * settings_overlay.cpp, via AndroidSetTouchOverlayVisible). May arrive off the UI thread. */
    fun onNativeSetTouchOverlayVisible(visible: Boolean) {
        runOnUiThread { touchControls?.setUserVisible(visible) }
    }

    /** Called from native (the settings sidebar's "Double-tap A to auto-hold acceleration"
     * checkbox, settings_overlay.cpp, via AndroidSetDoubleTapAutoHold). May arrive off the UI
     * thread. */
    fun onNativeSetDoubleTapAutoHold(enabled: Boolean) {
        runOnUiThread { touchControls?.setDoubleTapAutoHoldEnabled(enabled) }
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
        // Refreshed whenever an app update ships a different bundle, since CAs rotate.
        copyAssetIfChanged("cacert.pem", File(wiiCompiledDir, "cacert.pem"))
        for (relative in BUNDLED_BOOTSTRAP_FILES) {
            copyAssetIfMissing(relative, File(wiiCompiledDir, relative))
        }
    }

    private fun copyAssetIfChanged(assetPath: String, dest: File) {
        val bundled = assets.open(assetPath).use { it.readBytes() }
        if (dest.exists() && dest.length() == bundled.size.toLong() && dest.readBytes().contentEquals(bundled)) {
            return
        }
        dest.parentFile?.mkdirs()
        dest.writeBytes(bundled)
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

        // Reported refresh rates are floats like 59.94 or 120.00021.
        private const val REFRESH_RATE_EPSILON = 1.0f
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
