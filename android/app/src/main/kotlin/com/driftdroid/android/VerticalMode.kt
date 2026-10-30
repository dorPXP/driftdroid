package com.driftdroid.android

import android.content.Context
import java.io.File

/**
 * Vertical mode (experimental): the game runs upright like Mario Kart Tour. The race fills the
 * tall screen, the race HUD moves to the corners (runtime/src/dynamic_aspect.cpp's
 * ControlLoader::LoadVariant) and the touch controls get their own portrait layout. Menus keep
 * their usual shape across the middle of the screen. Applies when the game starts.
 */
internal object VerticalMode {
    private fun flagFile(context: Context) = File(context.filesDir, "WiiCompiled/vertical_mode.cfg")

    fun isEnabled(context: Context): Boolean =
        runCatching { flagFile(context).let { it.isFile && it.readText().trim() == "1" } }.getOrDefault(false)

    fun setEnabled(context: Context, enabled: Boolean) {
        runCatching {
            val file = flagFile(context)
            file.parentFile?.mkdirs()
            file.writeText(if (enabled) "1\n" else "0\n")
        }
    }
}
