package com.driftdroid.android

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Point
import android.os.Build
import android.view.Surface
import com.driftdroid.android.ui.DriftSheet
import kotlin.math.max
import kotlin.math.min

/**
 * Which way up the launcher stands. Automatic puts phones in portrait - held in one hand, the
 * pages are lists - and tablets and gaming handhelds in landscape, where the screen is held that
 * way anyway and a rail beside the content uses the width. The game itself is always landscape.
 */
internal object LauncherOrientation {
    enum class Mode(val label: String) { AUTO("Automatic"), PORTRAIT("Portrait"), LANDSCAPE("Landscape") }

    private const val PREFS = "launcher"
    private const val KEY = "orientation"

    fun mode(context: Context): Mode =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.let { name -> Mode.values().firstOrNull { it.name == name } } ?: Mode.AUTO

    fun setMode(context: Context, mode: Mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode.name).apply()
    }

    /** The requestedOrientation for [activity] under the saved mode. */
    fun requested(activity: Activity): Int {
        val landscape =
            when (mode(activity)) {
                Mode.PORTRAIT -> false
                Mode.LANDSCAPE -> true
                Mode.AUTO -> deviceWantsLandscape(activity)
            }
        return if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    /**
     * A tablet or a handheld: its screen is wider than tall when held the natural way (handhelds
     * and most tablets), it is tablet-sized (600dp+ on its short side), or it is too square for a
     * portrait column to pay off (4:3 and 1:1 handhelds, an unfolded foldable). A phone is none
     * of these: tall, narrow and portrait by nature.
     */
    fun deviceWantsLandscape(activity: Activity): Boolean {
        val (w, h) = displaySize(activity)
        @Suppress("DEPRECATION")
        val rotation = activity.windowManager.defaultDisplay.rotation
        val sideways = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
        val naturalWidth = if (sideways) h else w
        val naturalHeight = if (sideways) w else h
        if (naturalWidth > naturalHeight) return true
        if (activity.resources.configuration.smallestScreenWidthDp >= 600) return true
        val ratio = max(w, h).toFloat() / min(w, h).coerceAtLeast(1)
        return ratio < 1.6f
    }

    private fun displaySize(activity: Activity): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = activity.windowManager.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val size = Point()
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.getRealSize(size)
            size.x to size.y
        }

    /** The Settings sheet: three choices, applied at once. */
    fun show(activity: Activity, onChanged: () -> Unit = {}) {
        val current = mode(activity)
        val sheet = DriftSheet(activity, T("Launcher orientation"), T("The game itself always plays in landscape"))
        val auto = if (deviceWantsLandscape(activity)) T("landscape") else T("portrait")
        for (option in Mode.values()) {
            val detail =
                when (option) {
                    Mode.AUTO -> T("Portrait on phones, landscape on tablets and handhelds. This device: %s").format(auto)
                    Mode.PORTRAIT -> T("Always upright")
                    Mode.LANDSCAPE -> T("Always sideways, like the game")
                }
            sheet.radio(option.label, detail, option == current) {
                sheet.dismiss()
                if (option != current) {
                    setMode(activity, option)
                    onChanged()
                    activity.requestedOrientation = requested(activity)
                }
            }
        }
        sheet.show()
    }
}
