package com.wiicompiled.android.touch

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/** One on-screen control's identity and default placement (fractions of the play area, 0..1). */
private data class ControlSpec(
    val id: String,
    val label: String,
    val sdlButton: Int, // TouchInputBridge.BUTTON_*, or -1 when isJoystick is used instead
    val defaultXFraction: Float,
    val defaultYFraction: Float,
    val defaultSizeDp: Float,
    val defaultEnabled: Boolean,
    val isJoystick: Boolean = false,
)

/**
 * Owns the whole on-screen touch control layout: creates every button/joystick, persists where
 * the player has dragged/resized/hidden each one (SharedPreferences - this is purely a per-device
 * UI preference, not game state, so it deliberately does not go through RuntimeConfigFile/
 * Config.toml), and toggles the shared "edit mode" all of them use for repositioning.
 *
 * Layout mirrors a Nintendo Switch Pro Controller, since that's the default mapping
 * AutoConfigureTouchControllerIfPresent (settings_overlay.cpp) applies to this virtual pad:
 * A accelerates, B reverses, L=item, R=drift, Start ("+") pauses, Select ("-") is GC Z, and the
 * D-Pad is enabled by default since bikes need it (Up) to wheelie.
 */
class TouchControlsOverlay private constructor(context: Context, parent: ViewGroup) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val container = FrameLayout(context)
    private val views = LinkedHashMap<String, View>()
    private var editMode = false
    private val resetButton: TextView = TextView(context)

    init {
        parent.addView(
            container,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        resetButton.text = "Reset layout"
        resetButton.setBackgroundColor(Color.argb(180, 40, 40, 40))
        resetButton.setTextColor(Color.WHITE)
        val pad = dp(10f)
        resetButton.setPadding(pad, pad / 2, pad, pad / 2)
        resetButton.visibility = View.GONE
        resetButton.setOnClickListener { resetLayout() }

        // Build the controls after the container has a real size - fractions need it to convert
        // to pixel positions, and onCreate() hasn't laid anything out yet at this point.
        container.post {
            for (spec in SPECS) {
                addControl(spec)
            }
            val resetParams =
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            resetParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            resetParams.topMargin = dp(70f)
            container.addView(resetButton, resetParams)
        }
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, container.resources.displayMetrics).toInt()

    private fun addControl(spec: ControlSpec) {
        val sizePx = dp(spec.defaultSizeDp)
        val view: View =
            if (spec.isJoystick) {
                TouchJoystickView(container.context)
            } else {
                TouchButtonView(container.context, spec.label, spec.sdlButton)
            }

        val params = FrameLayout.LayoutParams(sizePx, sizePx)
        container.addView(view, params)

        val containerWidth = container.width.takeIf { it > 0 } ?: container.resources.displayMetrics.widthPixels
        val containerHeight = container.height.takeIf { it > 0 } ?: container.resources.displayMetrics.heightPixels

        val xFraction = prefs.getFloat(key(spec.id, "x"), spec.defaultXFraction)
        val yFraction = prefs.getFloat(key(spec.id, "y"), spec.defaultYFraction)
        val scale = prefs.getFloat(key(spec.id, "scale"), 1f)
        val enabled = prefs.getBoolean(key(spec.id, "enabled"), spec.defaultEnabled)

        view.x = xFraction * containerWidth - sizePx / 2f
        view.y = yFraction * containerHeight - sizePx / 2f
        view.scaleX = scale
        view.scaleY = scale
        view.visibility = if (enabled) View.VISIBLE else View.GONE

        val onEditEnd: (Boolean) -> Unit = { moved ->
            if (moved) {
                saveTransform(spec.id, view, containerWidth, containerHeight)
            } else {
                // A plain tap while editing toggles this control on/off; it stays visible (dimmed)
                // during edit mode itself so it can be tapped again to bring it back.
                val nowEnabled = view.alpha < 1f
                view.alpha = if (nowEnabled) 1f else 0.35f
                prefs.edit().putBoolean(key(spec.id, "enabled"), nowEnabled).apply()
            }
        }
        view.alpha = if (enabled) 1f else 0.35f

        when (view) {
            is TouchButtonView -> {
                view.editMode = editMode
                view.onEditEnd = onEditEnd
            }
            is TouchJoystickView -> {
                view.editMode = editMode
                view.onEditEnd = onEditEnd
            }
        }

        views[spec.id] = view
    }

    private fun saveTransform(id: String, view: View, containerWidth: Int, containerHeight: Int) {
        val centerX = view.x + view.width / 2f
        val centerY = view.y + view.height / 2f
        prefs
            .edit()
            .putFloat(key(id, "x"), centerX / containerWidth)
            .putFloat(key(id, "y"), centerY / containerHeight)
            .putFloat(key(id, "scale"), view.scaleX)
            .apply()
    }

    /** Long-press the settings gear to enter/exit layout editing (drag to move, pinch to resize,
     * tap to hide/show a control). */
    fun toggleEditMode() {
        editMode = !editMode
        for (view in views.values) {
            when (view) {
                is TouchButtonView -> view.editMode = editMode
                is TouchJoystickView -> view.editMode = editMode
            }
            // While editing, show every control (dimmed if disabled) so hidden ones can be
            // brought back; leaving edit mode re-hides whatever is still disabled.
            view.visibility = if (editMode) View.VISIBLE else if (view.alpha >= 1f) View.VISIBLE else View.GONE
        }
        resetButton.visibility = if (editMode) View.VISIBLE else View.GONE
    }

    private fun resetLayout() {
        prefs.edit().clear().apply()
        container.removeAllViews()
        views.clear()
        val wasEditing = editMode
        editMode = false
        for (spec in SPECS) {
            addControl(spec)
        }
        val resetParams =
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        resetParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        resetParams.topMargin = dp(70f)
        container.addView(resetButton, resetParams)
        if (wasEditing) {
            toggleEditMode()
        }
    }

    private fun key(id: String, field: String) = "${id}_$field"

    companion object {
        private const val PREFS_NAME = "touch_controls"

        private val SPECS =
            listOf(
                // Smaller than the first draft and moved down so it no longer overlaps L or the
                // D-Pad above it - the drag-anywhere zone is this view's own bounds, so the visual
                // size here doubles as the steering hit area.
                ControlSpec("joystick", "", -1, 0.13f, 0.74f, 170f, true, isJoystick = true),
                // Enabled by default: bikes need Up on the D-Pad to wheelie, which the analog
                // stick alone cannot do.
                ControlSpec("dpad_up", "▲", TouchInputBridge.BUTTON_DPAD_UP, 0.13f, 0.28f, 46f, true),
                ControlSpec("dpad_down", "▼", TouchInputBridge.BUTTON_DPAD_DOWN, 0.13f, 0.40f, 46f, true),
                ControlSpec("dpad_left", "◀", TouchInputBridge.BUTTON_DPAD_LEFT, 0.07f, 0.34f, 46f, true),
                ControlSpec("dpad_right", "▶", TouchInputBridge.BUTTON_DPAD_RIGHT, 0.19f, 0.34f, 46f, true),
                // Switch Pro Controller diamond: X top, Y left, A right (the big button), B bottom.
                ControlSpec("x", "X", TouchInputBridge.BUTTON_X, 0.88f, 0.44f, 66f, true),
                ControlSpec("y", "Y", TouchInputBridge.BUTTON_Y, 0.79f, 0.62f, 66f, true),
                ControlSpec("a", "A", TouchInputBridge.BUTTON_A, 0.97f, 0.62f, 66f, true),
                ControlSpec("b", "B", TouchInputBridge.BUTTON_B, 0.88f, 0.80f, 74f, true),
                // L=item, R=drift (digital shoulder buttons, per the Classic Controller Pro
                // preset AutoConfigureTouchControllerIfPresent applies - see TouchInputBridge).
                ControlSpec("l", "L", TouchInputBridge.BUTTON_L, 0.05f, 0.10f, 68f, true),
                // Lower than L: the top-right corner is shared with the settings gear and the FPS
                // counter - confirmed on-device, R's hit-circle up there was swallowing taps
                // meant for the gear.
                ControlSpec("r", "R", TouchInputBridge.BUTTON_R, 0.90f, 0.22f, 68f, true),
                // No Select control: Mario Kart Wii has no Select-equivalent action, so a touch
                // button for it would just do nothing.
                ControlSpec("start", "Start", TouchInputBridge.BUTTON_START, 0.5f, 0.92f, 60f, true),
            )

        fun attach(context: Context, parent: ViewGroup): TouchControlsOverlay = TouchControlsOverlay(context, parent)
    }
}
