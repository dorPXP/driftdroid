package com.wiicompiled.android

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Button
import android.widget.RelativeLayout
import com.wiicompiled.android.rom.RomImportOverlay
import com.wiicompiled.android.touch.EditGestureHelper
import com.wiicompiled.android.touch.TouchControlsOverlay
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
 * with [EXTRA_PRODUCT] set, since Android cannot swap which native library is resident in a
 * process: the Original/Retro Rewind choice must be resolved and loaded before SDLActivity's own
 * lifecycle (which calls getLibraries()) even starts.
 */
class MainActivity : SDLActivity() {

    private external fun nativeSetInstallPaths(filesDir: String, dvdRoot: String)
    private external fun nativeSetRetroRewindRoot(retroRewindRoot: String)
    private external fun nativeToggleSettingsOverlay()
    private external fun nativeSetTouchControlsVisibleCache(visible: Boolean)

    private var touchControls: TouchControlsOverlay? = null
    private var romImportOverlay: RomImportOverlay? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var resolvedProduct: String = PRODUCT_BASE

    override fun getLibraries(): Array<String> {
        return arrayOf("wii", "png16", if (resolvedProduct == PRODUCT_RETRO_REWIND) "RetroRewind" else "WiiCompiled")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        resolvedProduct = intent.getStringExtra(EXTRA_PRODUCT) ?: PRODUCT_BASE

        // A different product's .so may already be resident in this process from an earlier launch
        // (e.g. the user backgrounded the game, returned to ModePickerActivity, and picked the
        // OTHER mode without fully closing the app) - native libraries cannot be swapped within a
        // live process, so the only correct fix is a full process restart into the new choice.
        if (sLoadedProduct != null && sLoadedProduct != resolvedProduct) {
            restartIntoProduct(resolvedProduct)
            return
        }
        sLoadedProduct = resolvedProduct
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
        // libRetroRewind.so). Populated by ModePickerActivity's folder-import flow, mirroring how
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

        // 5s, then 3s, were both reported as feeling too long directly.
        private const val HOLD_TO_EDIT_MS = 1000L
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
        button.setBackgroundColor(Color.argb(120, 0, 0, 0))
        button.setTextColor(Color.WHITE)

        // Fills gray from the bottom up while held, so the hold is visibly registering instead of
        // looking unresponsive for the whole duration - confirmed as wanted directly ("the
        // indicator should make it so it fills up the gear button with the color gray"). Only
        // attached to the button as a foreground while a hold is actually in progress, not left in
        // place permanently at level 0 - a ClipDrawable clipped to nothing can still leave a faint
        // seam at its clip boundary on some GPU rendering paths, confirmed directly as "a barely
        // visible gray line" on the idle button.
        val holdFillDrawable = ClipDrawable(ColorDrawable(Color.argb(200, 160, 160, 160)), Gravity.BOTTOM, ClipDrawable.VERTICAL)
        holdFillDrawable.level = 0

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
        // A plain long-click (Android's default ~500ms) turned out far too easy to trigger by
        // accident during ordinary play (confirmed directly: "when i clicked on the gear it sent
        // me to edit layout"). A held press is deliberate enough that it can only mean "I want to
        // edit the layout" - exiting is the TouchControlsOverlay toolbar's explicit "Done" button
        // instead of holding again, which is what made exiting confusing before ("i couldn't exit
        // out of it").
        val holdHandler = Handler(Looper.getMainLooper())
        var holdRunnable: Runnable? = null
        var holdFillAnimator: ValueAnimator? = null
        // Captured once per gesture at ACTION_DOWN, not re-checked on every event: entering edit
        // mode mid-hold (the postDelayed runnable fires while the finger is still down) previously
        // let the REST of that same press - including any incidental finger drift before lifting,
        // which real fingers always have over a multi-second hold - immediately register as a drag
        // on the gear itself, silently relocating it the instant edit mode activated. Confirmed
        // on-device as "the gear button just disappears" after exiting - it hadn't disappeared, it
        // had been dragged somewhere else by the same press that turned edit mode on.
        var routeThisTouchToEditGesture = false
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    routeThisTouchToEditGesture = touchControls?.isEditMode() == true
                    if (!routeThisTouchToEditGesture) {
                        holdFillDrawable.level = 0
                        button.foreground = holdFillDrawable
                        val runnable =
                            Runnable {
                                touchControls?.setEditMode(true)
                                button.alpha = 1f
                                button.foreground = null
                            }
                        holdRunnable = runnable
                        holdHandler.postDelayed(runnable, HOLD_TO_EDIT_MS)
                        val animator = ValueAnimator.ofInt(0, 10000)
                        animator.duration = HOLD_TO_EDIT_MS
                        animator.addUpdateListener { holdFillDrawable.level = it.animatedValue as Int }
                        holdFillAnimator = animator
                        animator.start()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    holdRunnable?.let { holdHandler.removeCallbacks(it) }
                    holdRunnable = null
                    holdFillAnimator?.cancel()
                    holdFillAnimator = null
                    button.foreground = null
                }
            }
            if (routeThisTouchToEditGesture) {
                editGesture.onTouchEvent(event)
                return@setOnTouchListener true
            }
            false
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
