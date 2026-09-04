package com.wiicompiled.android

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.RelativeLayout
import com.wiicompiled.android.rom.RomImportOverlay
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
 */
class MainActivity : SDLActivity() {

    companion object {
        init {
            System.loadLibrary("wii")
            System.loadLibrary("png16")
            System.loadLibrary("WiiCompiled")
        }
    }

    private external fun nativeSetInstallPaths(filesDir: String, dvdRoot: String)
    private external fun nativeToggleSettingsOverlay()

    private var touchControls: TouchControlsOverlay? = null
    private var romImportOverlay: RomImportOverlay? = null

    override fun getLibraries(): Array<String> {
        return arrayOf("wii", "png16", "WiiCompiled")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must happen before super.onCreate(), which is what starts SDLActivity's own lifecycle
        // towards eventually calling SDL_main. The native side (dvd.cpp's WaitForAndroidDvdRoot,
        // Android-only) polls this same path rather than failing immediately if it isn't a valid
        // extracted DATA folder yet, since RomImportOverlay below fills it in concurrently -
        // there is no ordering dependency between the two beyond that path string.
        val dvdRoot = File(filesDir, "WiiCompiled/DiscData")
        nativeSetInstallPaths(filesDir.absolutePath, dvdRoot.absolutePath)

        super.onCreate(savedInstanceState)

        // The settings gear and touch controls are gameplay UI - showing them over the ROM
        // picker would let a player fiddle with in-game settings before there's a game to apply
        // them to. Only add them once a game is actually present (either already imported from a
        // previous run, or - once extraction exists - right after RomImportOverlay finishes).
        if (RomImportOverlay.isRomAlreadyImported(dvdRoot)) {
            showGameUi()
        } else {
            romImportOverlay = RomImportOverlay.attach(this, mLayout, dvdRoot)
            // TODO(extraction stage): once RomImportOverlay actually extracts into dvdRoot
            // instead of just validating the pick, it needs to remove itself and call
            // showGameUi() here - neither happens yet, since there is nothing to reveal.
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RomImportOverlay.REQUEST_CODE_PICK_ROM) {
            romImportOverlay?.onFilePicked(data?.data)
        }
    }

    private fun showGameUi() {
        addSettingsButton()
        touchControls = TouchControlsOverlay.attach(this, mLayout)
    }

    // Desktop opens the (already fully built) in-game settings overlay with F10 - there is no
    // keyboard here, so this is the touch equivalent. A plain native Android view instead of an
    // Aurora/ImGui-drawn one on purpose: it must keep working even while Aurora's own render loop
    // is doing something unexpected, and it never competes with ImGui for the touch event. Long
    // press enters touch-control layout editing (drag/resize/hide) instead - see
    // TouchControlsOverlay.
    private fun addSettingsButton() {
        val button = Button(this)
        button.text = "⚙"
        button.alpha = 0.55f
        button.setBackgroundColor(Color.argb(120, 0, 0, 0))
        button.setTextColor(Color.WHITE)
        button.setOnClickListener { nativeToggleSettingsOverlay() }
        button.setOnLongClickListener {
            touchControls?.toggleEditMode()
            true
        }

        val sizePx = (48 * resources.displayMetrics.density).toInt()
        val marginPx = (12 * resources.displayMetrics.density).toInt()
        val params = RelativeLayout.LayoutParams(sizePx, sizePx)
        params.addRule(RelativeLayout.ALIGN_PARENT_TOP)
        params.addRule(RelativeLayout.ALIGN_PARENT_END)
        params.topMargin = marginPx
        params.rightMargin = marginPx

        mLayout.addView(button, params)
    }
}
