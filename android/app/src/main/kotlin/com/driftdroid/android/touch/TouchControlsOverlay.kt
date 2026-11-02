package com.driftdroid.android.touch

import com.driftdroid.android.T
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.driftdroid.android.ui.DriftSheet
import com.driftdroid.android.ui.DriftUi
import org.json.JSONObject

/** One on-screen control's identity and default placement (fractions of the play area, 0..1).
 * color/pill give each button its own look by default (matching a real Wii Classic Controller
 * Pro's color-coded face buttons and stretched L/R shoulder buttons - requested directly after
 * comparing against KartPad's Android port, which does the same, instead of every button being an
 * identical plain gray circle). */
private data class ControlSpec(
    val id: String,
    val label: String,
    val sdlButton: Int, // TouchInputBridge.BUTTON_*, or -1 when isJoystick is used instead
    val defaultXFraction: Float,
    val defaultYFraction: Float,
    val defaultSizeDp: Float,
    val defaultEnabled: Boolean,
    val isJoystick: Boolean = false,
    val color: Int = TouchButtonView.DEFAULT_FILL_COLOR,
    val pill: Boolean = false,
    val textColor: Int = Color.WHITE,
    val outlineColor: Int = TouchButtonView.DEFAULT_OUTLINE_COLOR,
)

/**
 * Owns the whole on-screen touch control layout: creates every button/joystick, persists where
 * the player has dragged/resized/hidden each one (SharedPreferences - this is purely a per-device
 * UI preference, not game state, so it deliberately does not go through RuntimeConfigFile/
 * Config.toml), and drives layout editing (drag/resize/hide, named layout save/load, file
 * export/import).
 *
 * Layout mirrors a Nintendo Switch Pro Controller, since that's the default mapping
 * AutoConfigureTouchControllerIfPresent (settings_overlay.cpp) applies to this virtual pad:
 * A accelerates, B reverses, L=item, R=drift, Start ("+") pauses, Select ("-") is GC Z, and the
 * D-Pad is enabled by default since bikes need it (Up) to wheelie.
 */
class TouchControlsOverlay private constructor(private val activity: Activity, parent: ViewGroup) {

    // Vertical mode keeps its own layout, so neither orientation's edits move the other's buttons.
    private val prefs: SharedPreferences =
        activity.getSharedPreferences(if (portraitMode) PORTRAIT_PREFS_NAME else PREFS_NAME, Context.MODE_PRIVATE)
    private val library: SharedPreferences = activity.getSharedPreferences(LIBRARY_PREFS_NAME, Context.MODE_PRIVATE)
    // Vertical and horizontal mode keep separate saved-layout lists in the same library file: a
    // layout made for one puts the buttons in odd places on the other. Horizontal keeps the
    // original key names, so nothing saved before the split moves unless it was made upright.
    private val libraryNamesKey = if (portraitMode) KEY_LIBRARY_NAMES_PORTRAIT else KEY_LIBRARY_NAMES
    private val container = FrameLayout(activity)
    private val views = LinkedHashMap<String, View>()
    private var editMode = false
    private val toolbar = LinearLayout(activity)
    private var pendingExportJson: String? = null

    // Effective visibility is userVisible AND NOT controllerConnected AND NOT settingsOpen: a real
    // Bluetooth controller auto-hides the touch overlay (see
    // MainActivity.onNativeGamepadConnectionChanged, driven by aurora-main/lib/window.cpp's
    // SDL_EVENT_GAMEPAD_ADDED/REMOVED), the settings sidebar hides it while open (see
    // MainActivity.onNativeSettingsVisibilityChanged - the sidebar docks to the same screen edge
    // these buttons do, and gameplay input is already blocked while it's open anyway), and
    // userVisible is the persisted "Touch controls" checkbox in the in-game settings menu
    // (MainActivity.onNativeSetTouchOverlayVisible). Independent of the per-button drag/hide state
    // stored below - this only ever hides/shows the WHOLE overlay at once.
    private var userVisible = true
    private var controllerConnected = false
    private var settingsOpen = false

    /** Lets MainActivity keep the settings gear's dimmed/highlighted state in sync when edit mode
     * exits via the toolbar's "Done" button rather than however it was entered. */
    var onEditModeChanged: ((Boolean) -> Unit)? = null
    /** Called after Reset puts every touch control back, so the settings gear (which lives outside this overlay) can go back too. */
    var onLayoutReset: (() -> Unit)? = null

    init {
        userVisible = prefs.getBoolean(KEY_MASTER_VISIBLE, true)

        parent.addView(
            container,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        applyMasterVisibility()

        // Fixes a real, reproducible bug: controls restored slightly ABOVE their saved position
        // on every launch. Root cause - SDL asynchronously switches this window to fullscreen
        // (SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN, once the emulator core sets up video output) some
        // time AFTER onCreate/attach() runs, growing `container` to include what was status-bar
        // space. The layout editor is normally used during actual gameplay (i.e. already
        // fullscreen), so saved x/y fractions are relative to the LARGER, post-fullscreen size -
        // but the one-shot `container.post {}` measurement below that restores them on next
        // launch fires before that switch happens, against the SMALLER, pre-fullscreen size,
        // and nothing ever corrected for the container growing out from under it afterward.
        // Re-applying every control's saved fraction whenever the container's real size actually
        // changes (not just once at startup) means the final resting position always matches
        // whatever size the container ends up at, regardless of when that resize lands.
        container.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val widthChanged = (right - left) != (oldRight - oldLeft)
            val heightChanged = (bottom - top) != (oldBottom - oldTop)
            if (widthChanged || heightChanged) {
                reapplyPositions()
            }
        }

        splitLibraryOnce()
        buildToolbar()

        // Build the controls after the container has a real size - fractions need it to convert
        // to pixel positions, and onCreate() hasn't laid anything out yet at this point.
        container.post {
            for (spec in SPECS) {
                addControl(spec)
            }
            addToolbar()
        }
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, container.resources.displayMetrics).toInt()

    /** "Done editing" first and most prominent - the previous design (long-press the gear again
     * to exit) was confirmed confusing on-device ("i clicked on the gear it sent me to edit
     * layout, but i couldn't exit out of it"). Exiting is always this explicit button. A panel in
     * the settings drawer's colours, with the four buttons sharing its width so they fit upright
     * too (the old row of stock buttons ran off a vertical screen). */
    private fun buildToolbar() {
        toolbar.orientation = LinearLayout.VERTICAL
        toolbar.visibility = View.GONE
        toolbar.background = GradientDrawable().apply {
            setColor(0xEE141A33.toInt())
            cornerRadius = dp(16f).toFloat()
            setStroke(dp(1.5f), DriftUi.LINE)
        }
        toolbar.setPadding(dp(12f), dp(10f), dp(12f), dp(12f))

        toolbar.addView(
            TextView(activity).apply {
                text = if (portraitMode) T("Editing the vertical layout") else T("Editing the horizontal layout")
                setTextColor(DriftUi.TEXT)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
            },
        )
        toolbar.addView(
            TextView(activity).apply {
                text = T("Drag to move · pinch to resize · tap a button to change it")
                setTextColor(DriftUi.MUTED)
                textSize = 13f
                setPadding(0, dp(2f), 0, dp(10f))
            },
        )
        val row = LinearLayout(activity)
        row.orientation = LinearLayout.HORIZONTAL
        // Auto-sized labels have different baselines; aligning on them pushes buttons out of line.
        row.isBaselineAligned = false
        fun add(text: String, style: DriftUi.Style, onClick: () -> Unit) {
            val button = DriftUi.button(activity, text, style, onClick)
            button.minWidth = if (portraitMode) 0 else dp(96f)
            // Four buttons share the row: a long translation shrinks instead of being cut off.
            // isSingleLine turns on horizontal scrolling, which makes auto-size see an unlimited
            // width and never shrink, so use maxLines = 1 without it.
            button.isSingleLine = false
            button.maxLines = 1
            button.setHorizontallyScrolling(false)
            button.setAutoSizeTextTypeUniformWithConfiguration(9, 15, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            row.addView(
                button,
                LinearLayout.LayoutParams(0, dp(46f), 1f).apply {
                    if (row.childCount > 0) marginStart = dp(8f)
                },
            )
        }
        add("Done", DriftUi.Style.PRIMARY) { setEditMode(false) }
        add("Reset", DriftUi.Style.SECONDARY) { confirmResetLayout() }
        add("Save", DriftUi.Style.SECONDARY) { promptSaveLayout() }
        add("Layouts", DriftUi.Style.SECONDARY) { showLayoutLibrary() }
        toolbar.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun addToolbar() {
        val params =
            FrameLayout.LayoutParams(
                if (portraitMode) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.topMargin = dp(70f)
        params.leftMargin = dp(12f)
        params.rightMargin = dp(12f)
        container.addView(toolbar, params)
    }

    private fun addControl(spec: ControlSpec) {
        val sizePx = dp(spec.defaultSizeDp)
        // Pills get a wider-than-tall base size (not just a rounded square) so they actually read
        // as stretched shoulder buttons - scaleX/scaleY below stay uniform, so this aspect ratio
        // is preserved through resizing.
        val widthPx = if (spec.pill) (sizePx * 1.8f).toInt() else sizePx
        val heightPx = sizePx
        val view: View =
            if (spec.isJoystick) {
                TouchJoystickView(container.context).apply {
                    baseColor = prefs.getInt(key(spec.id, "fillColor"), TouchJoystickView.DEFAULT_BASE_COLOR)
                    outlineColor = prefs.getInt(key(spec.id, "outlineColor"), TouchJoystickView.DEFAULT_OUTLINE_COLOR)
                    knobColor = prefs.getInt(key(spec.id, "knobColor"), TouchJoystickView.DEFAULT_KNOB_COLOR)
                }
            } else {
                val fillColor = prefs.getInt(key(spec.id, "fillColor"), spec.color)
                val outlineColor = prefs.getInt(key(spec.id, "outlineColor"), spec.outlineColor)
                TouchButtonView(container.context, spec.label, spec.sdlButton, fillColor, spec.pill, Color.WHITE, outlineColor).apply {
                    // Only the A (accelerate) button ever supports this - GitHub issue #4's whole
                    // point was freeing up the thumb that's holding A down, not any other button.
                    if (spec.id == "a") {
                        doubleTapAutoHoldEnabled = prefs.getBoolean(KEY_DOUBLE_TAP_AUTO_HOLD, false)
                    }
                }
            }

        val params = FrameLayout.LayoutParams(widthPx, heightPx)
        container.addView(view, params)

        val containerWidth = container.width.takeIf { it > 0 } ?: container.resources.displayMetrics.widthPixels
        val containerHeight = container.height.takeIf { it > 0 } ?: container.resources.displayMetrics.heightPixels

        val xFraction = prefs.getFloat(key(spec.id, "x"), defaultX(spec))
        val yFraction = prefs.getFloat(key(spec.id, "y"), defaultY(spec))
        val scale = prefs.getFloat(key(spec.id, "scale"), 1f)
        val enabled = prefs.getBoolean(key(spec.id, "enabled"), spec.defaultEnabled)
        val opacity = prefs.getFloat(key(spec.id, "opacity"), 1f)

        view.x = xFraction * containerWidth - widthPx / 2f
        view.y = yFraction * containerHeight - heightPx / 2f
        view.scaleX = scale
        view.scaleY = scale
        view.visibility = if (enabled) View.VISIBLE else View.GONE
        view.alpha = if (enabled) opacity else 0.35f

        val onEditEnd: (Boolean) -> Unit = { moved ->
            if (moved) {
                // Deliberately NOT the containerWidth/containerHeight captured above - those are
                // a snapshot from whenever this control was first created (possibly before the
                // window's later fullscreen switch), and saving against a stale size is exactly
                // what caused the restore-position bug this replaced. Editing happens live, so
                // the container's actual current size is always the right denominator here.
                saveTransform(spec.id, view)
            } else {
                // A plain tap (no drag/pinch) while editing opens the per-control editor instead
                // of instantly toggling visibility - requested directly: "tapping on a button
                // lets you resize it or hide it with a GUI option."
                showControlEditor(spec, view)
            }
        }

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

    private fun controlName(spec: ControlSpec): String =
        when {
            spec.isJoystick -> T("Steering stick")
            spec.id.startsWith("dpad_") -> "D-pad ${spec.label}"
            spec.id == "start" -> T("Start button")
            else -> "${spec.label} button"
        }

    /** Opens on the side of the screen away from the control, so the change can be watched live
     * while making it. */
    private fun showControlEditor(spec: ControlSpec, view: View) {
        val centerX = view.x + view.width / 2f
        val centerY = view.y + view.height / 2f
        val dock =
            if (portraitMode) {
                if (centerY > container.height / 2f) DriftSheet.Dock.TOP else DriftSheet.Dock.BOTTOM
            } else {
                if (centerX > container.width / 2f) DriftSheet.Dock.START else DriftSheet.Dock.END
            }
        val sheet = DriftSheet(activity, controlName(spec), T("Changes show on the button as you make them"), dock)

        var visible = view.visibility == View.VISIBLE && prefs.getBoolean(key(spec.id, "enabled"), spec.defaultEnabled)
        var opacity = prefs.getFloat(key(spec.id, "opacity"), 1f).coerceIn(0.1f, 1f)
        fun applyLook() {
            view.alpha = if (visible) opacity else 0.35f
        }

        sheet.toggle("Show on screen", "Off hides it while you play", visible) {
            visible = it
            applyLook()
        }
        // A floor of 10% keeps a control from becoming a truly invisible-but-still-there dead zone
        // on screen - "Show on screen" off is the actual "hide it" action.
        sheet.slider("Button opacity", 0.1f, 1f, opacity, 90, { "${(it * 100).toInt()}%" }) {
            opacity = it
            applyLook()
        }
        // Scale range mirrors EditGestureHelper's pinch-to-resize clamp (0.5x-2.5x) so the slider
        // and pinching can never disagree about what's achievable.
        sheet.slider("Size", 0.5f, 2.5f, view.scaleX, 200, { "${(it * 100).toInt()}%" }) {
            view.scaleX = it
            view.scaleY = it
        }

        // Color/outline customization - covers the joystick too ("make it so the joystick can be
        // colored"). Each colour opens its picker in place.
        sheet.section("Colours")
        when (view) {
            is TouchButtonView -> {
                addColorPicker(sheet, "Fill", view.fillColor) { view.fillColor = it }
                addColorPicker(sheet, "Outline", view.outlineColor) { view.outlineColor = it }
            }
            is TouchJoystickView -> {
                addColorPicker(sheet, "Base", view.baseColor) { view.baseColor = it }
                addColorPicker(sheet, "Outline", view.outlineColor) { view.outlineColor = it }
                addColorPicker(sheet, "Knob", view.knobColor) { view.knobColor = it }
            }
        }
        sheet.button("Done", DriftUi.Style.PRIMARY) { sheet.dismiss() }

        // Persists on ANY dismiss path (Done, tapping outside, the back button) - changes apply
        // live, so a dismiss that didn't save looked like it worked but silently lost them.
        sheet.onDismiss = {
            view.visibility = if (visible) View.VISIBLE else View.GONE
            applyLook()
            val editor =
                prefs
                    .edit()
                    .putBoolean(key(spec.id, "enabled"), visible)
                    .putFloat(key(spec.id, "scale"), view.scaleX)
                    .putFloat(key(spec.id, "opacity"), opacity)
            when (view) {
                is TouchButtonView -> {
                    editor.putInt(key(spec.id, "fillColor"), view.fillColor)
                    editor.putInt(key(spec.id, "outlineColor"), view.outlineColor)
                }
                is TouchJoystickView -> {
                    editor.putInt(key(spec.id, "fillColor"), view.baseColor)
                    editor.putInt(key(spec.id, "outlineColor"), view.outlineColor)
                    editor.putInt(key(spec.id, "knobColor"), view.knobColor)
                }
                else -> {}
            }
            editor.apply()
            saveTransform(spec.id, view)
            // Edit mode keeps disabled controls visible-but-dimmed so they stay reachable.
            if (!visible && editMode) {
                view.visibility = View.VISIBLE
            }
        }
        sheet.show()
    }

    /** A colour row with a swatch; tapping it opens a standard SV-square + hue-strip + hex-field
     * picker under it (see SaturationValuePickerView's doc comment - "having a limited selection
     * kinda sucks" led here). [onPick] fires live on every drag/edit for an immediate preview. */
    private fun addColorPicker(sheet: DriftSheet, label: String, initialColor: Int, onPick: (Int) -> Unit) {
        val activityContext = activity
        val hsv = FloatArray(3)
        Color.colorToHSV(initialColor, hsv)
        // How solid the colour is, kept with it. The shipped colours are only 43% solid (see-through
        // over the race), so before this was shown the brightest red picked still came out a dim,
        // see-through red ("why is the brightest red not.. the brightest").
        var alpha = Color.alpha(initialColor)

        val swatchShape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(initialColor)
            setStroke(dp(2f), DriftUi.LINE)
        }
        val swatch = View(activityContext).apply {
            background = swatchShape
            layoutParams = LinearLayout.LayoutParams(dp(30f), dp(30f)).apply { marginStart = dp(12f) }
        }
        val picker = LinearLayout(activityContext)
        picker.orientation = LinearLayout.VERTICAL
        picker.visibility = View.GONE
        picker.setPadding(dp(4f), dp(4f), dp(4f), dp(10f))
        sheet.action(label, null, swatch) {
            picker.visibility = if (picker.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        sheet.custom(picker)

        val preview = View(activityContext)
        val svPicker = SaturationValuePickerView(activityContext)
        val hueSlider = HueSliderView(activityContext)
        val hexField = EditText(activityContext)

        fun currentColor(): Int = Color.HSVToColor(alpha, hsv)

        var syncingHexField = false
        fun refreshPreview() {
            val color = currentColor()
            preview.background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(10f).toFloat()
            }
            swatchShape.setColor(color)
            syncingHexField = true
            hexField.setText(String.format("#%06X", 0xFFFFFF and color))
            syncingHexField = false
            onPick(color)
        }

        val pickerRow = LinearLayout(activityContext)
        pickerRow.orientation = LinearLayout.HORIZONTAL
        pickerRow.addView(preview, LinearLayout.LayoutParams(dp(48f), dp(140f)))
        val svParams = LinearLayout.LayoutParams(0, dp(140f), 1f)
        svParams.marginStart = dp(8f)
        pickerRow.addView(svPicker, svParams)
        picker.addView(pickerRow)

        val hueParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36f))
        hueParams.topMargin = dp(8f)
        picker.addView(hueSlider, hueParams)

        val (solidCard, _) =
            sheet.buildSlider("Solid", 0.1f, 1f, alpha / 255f, 90, { "${(it * 100).toInt()}%" }) {
                alpha = (it * 255).toInt().coerceIn(26, 255)
                refreshPreview()
            }
        picker.addView(
            solidCard,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) },
        )

        hexField.inputType = InputType.TYPE_CLASS_TEXT
        hexField.isSingleLine = true
        hexField.filters = arrayOf(android.text.InputFilter.LengthFilter(7))
        DriftUi.styleField(hexField)
        picker.addView(
            hexField,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) },
        )

        svPicker.hue = hsv[0]
        svPicker.setSaturationValue(hsv[1], hsv[2])
        hueSlider.setHue(hsv[0])
        refreshPreview()

        svPicker.onColorChanged = { saturation, value ->
            hsv[1] = saturation
            hsv[2] = value
            refreshPreview()
        }
        hueSlider.onHueChanged = { hue ->
            hsv[0] = hue
            svPicker.hue = hue
            refreshPreview()
        }
        hexField.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus || syncingHexField) return@setOnFocusChangeListener
            val typed = hexField.text.toString().removePrefix("#")
            val parsed = typed.toLongOrNull(16)?.takeIf { typed.length == 6 } ?: return@setOnFocusChangeListener
            val color = Color.rgb((parsed shr 16 and 0xFF).toInt(), (parsed shr 8 and 0xFF).toInt(), (parsed and 0xFF).toInt())
            Color.colorToHSV(color, hsv)
            svPicker.hue = hsv[0]
            svPicker.setSaturationValue(hsv[1], hsv[2])
            hueSlider.setHue(hsv[0])
            refreshPreview()
        }
    }

    private fun saveTransform(id: String, view: View) {
        val containerWidth = container.width.takeIf { it > 0 } ?: container.resources.displayMetrics.widthPixels
        val containerHeight = container.height.takeIf { it > 0 } ?: container.resources.displayMetrics.heightPixels
        val centerX = view.x + view.width / 2f
        val centerY = view.y + view.height / 2f
        prefs
            .edit()
            .putFloat(key(id, "x"), centerX / containerWidth)
            .putFloat(key(id, "y"), centerY / containerHeight)
            .putFloat(key(id, "scale"), view.scaleX)
            .apply()
    }

    /** Re-applies every control's saved (or, if never saved, default) x/y fraction against the
     * container's CURRENT size - called whenever that size actually changes (see the
     * OnLayoutChangeListener in attach()), not just once at startup, so a later fullscreen resize
     * can't leave positions permanently off, including for a first-time install that hasn't saved
     * anything yet. Does not touch scale/enabled/opacity/colors, only position. */
    private fun reapplyPositions() {
        val containerWidth = container.width.takeIf { it > 0 } ?: return
        val containerHeight = container.height.takeIf { it > 0 } ?: return
        val specsById = SPECS.associateBy { it.id }
        for ((id, view) in views) {
            val spec = specsById[id] ?: continue
            val xFraction = prefs.getFloat(key(id, "x"), defaultX(spec))
            val yFraction = prefs.getFloat(key(id, "y"), defaultY(spec))
            view.x = xFraction * containerWidth - view.width / 2f
            view.y = yFraction * containerHeight - view.height / 2f
        }
    }

    fun isUserVisible(): Boolean = userVisible

    /** From the in-game settings menu's "Touch controls" checkbox (settings_overlay.cpp, via
     * MainActivity.onNativeSetTouchOverlayVisible). */
    fun setUserVisible(visible: Boolean) {
        userVisible = visible
        prefs.edit().putBoolean(KEY_MASTER_VISIBLE, visible).apply()
        applyMasterVisibility()
    }

    fun isDoubleTapAutoHoldEnabled(): Boolean = prefs.getBoolean(KEY_DOUBLE_TAP_AUTO_HOLD, false)

    /** From the in-game settings menu's "Double-tap A to auto-hold acceleration" checkbox
     * (settings_overlay.cpp, via MainActivity.onNativeSetDoubleTapAutoHold). Turning it off while
     * already locked also releases the hold immediately, rather than leaving A stuck down with no
     * way to release it until the player re-enables the feature just to double-tap it off. */
    fun setDoubleTapAutoHoldEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DOUBLE_TAP_AUTO_HOLD, enabled).apply()
        (views["a"] as? TouchButtonView)?.doubleTapAutoHoldEnabled = enabled
    }

    /** From MainActivity.onNativeGamepadConnectionChanged - a real controller connecting/
     * disconnecting, not the touch overlay's own virtual gamepad (filtered out native-side). */
    fun setControllerConnected(connected: Boolean) {
        controllerConnected = connected
        applyMasterVisibility()
    }

    /** From MainActivity.onNativeSettingsVisibilityChanged - the native settings sidebar just
     * opened/closed. */
    fun setSettingsOpen(open: Boolean) {
        settingsOpen = open
        applyMasterVisibility()
    }

    private fun applyMasterVisibility() {
        container.visibility =
            if (userVisible && !controllerConnected && !settingsOpen) View.VISIBLE else View.GONE
    }

    /** From MotionSteering - hides just the on-screen steering stick while gravity-based steering
     * is driving the same virtual axis (TouchInputBridge.AXIS_LEFT_X), so the two don't fight over
     * it. Restores whatever hidden/visible + opacity state the player had saved for it. */
    fun setMotionSteeringActive(active: Boolean) {
        val view = views[JOYSTICK_CONTROL_ID] ?: return
        if (active) {
            view.visibility = View.GONE
            return
        }
        val enabled = prefs.getBoolean(key(JOYSTICK_CONTROL_ID, "enabled"), true)
        val opacity = prefs.getFloat(key(JOYSTICK_CONTROL_ID, "opacity"), 1f)
        view.visibility = if (enabled) View.VISIBLE else View.GONE
        view.alpha = if (enabled) opacity else 0.35f
    }

    /** Enter/exit layout editing (drag to move, pinch to resize, tap to open the hide/resize
     * dialog). Entered by holding the settings gear for 5 seconds (MainActivity), exited via the
     * toolbar's "Done" button - not a toggle callable from two different places, since that's
     * exactly what made exiting confusing before ("i couldn't exit out of it"). */
    fun setEditMode(enabled: Boolean) {
        if (editMode == enabled) return
        editMode = enabled
        for ((id, view) in views) {
            when (view) {
                is TouchButtonView -> view.editMode = editMode
                is TouchJoystickView -> view.editMode = editMode
            }
            // While editing, show every control (dimmed if disabled) so hidden ones can be
            // brought back; leaving edit mode re-hides whatever is still disabled. Checked against
            // the real persisted enabled flag, not alpha - opacity can legitimately be anywhere
            // from 10%-100% on an ENABLED control now, so alpha alone can no longer tell disabled
            // apart from "enabled but dialed down".
            val isEnabled = prefs.getBoolean(key(id, "enabled"), true)
            view.visibility = if (editMode || isEnabled) View.VISIBLE else View.GONE
        }
        toolbar.visibility = if (editMode) View.VISIBLE else View.GONE
        onEditModeChanged?.invoke(editMode)
    }

    fun isEditMode(): Boolean = editMode

    private fun confirmResetLayout() {
        val sheet = DriftSheet(activity, T("Reset this layout?"), compact = true)
        sheet.note(
            "Every button goes back to where it started, with its first size and colours. " +
                "Saved layouts are kept.",
        )
        sheet.button("Cancel") { sheet.dismiss() }
        sheet.button("Reset", DriftUi.Style.DANGER) {
            sheet.dismiss()
            resetLayout()
        }
        sheet.show()
    }

    private fun resetLayout() {
        prefs.edit().clear().apply()
        rebuildControls()
        onLayoutReset?.invoke()
    }

    private fun rebuildControls() {
        val wasEditing = editMode
        container.removeAllViews()
        views.clear()
        editMode = false
        for (spec in SPECS) {
            addControl(spec)
        }
        addToolbar()
        if (wasEditing) {
            setEditMode(true)
        }
    }

    private fun key(id: String, field: String) = "${id}_$field"

    // --- Named layout library (save/apply/delete) + file export/import ---------------------

    private fun currentLayoutJson(): JSONObject {
        val root = JSONObject()
        for (spec in SPECS) {
            val obj = JSONObject()
            obj.put("x", prefs.getFloat(key(spec.id, "x"), defaultX(spec)).toDouble())
            obj.put("y", prefs.getFloat(key(spec.id, "y"), defaultY(spec)).toDouble())
            obj.put("scale", prefs.getFloat(key(spec.id, "scale"), 1f).toDouble())
            obj.put("enabled", prefs.getBoolean(key(spec.id, "enabled"), spec.defaultEnabled))
            obj.put("opacity", prefs.getFloat(key(spec.id, "opacity"), 1f).toDouble())
            // Colors were saved straight to the shared per-device prefs, not into named layouts -
            // meaning switching layouts never changed them, so every saved layout looked like it
            // used "the same" (whatever was currently live) colors. Confirmed directly ("touchpad
            // colors arent being saved to their layouts, the colors apply to every layout saved").
            val defaultFill = if (spec.isJoystick) TouchJoystickView.DEFAULT_BASE_COLOR else spec.color
            val defaultOutline = if (spec.isJoystick) TouchJoystickView.DEFAULT_OUTLINE_COLOR else spec.outlineColor
            obj.put("fillColor", prefs.getInt(key(spec.id, "fillColor"), defaultFill))
            obj.put("outlineColor", prefs.getInt(key(spec.id, "outlineColor"), defaultOutline))
            if (spec.isJoystick) {
                obj.put("knobColor", prefs.getInt(key(spec.id, "knobColor"), TouchJoystickView.DEFAULT_KNOB_COLOR))
            }
            root.put(spec.id, obj)
        }
        root.put(KEY_ORIENTATION, if (portraitMode) ORIENTATION_VERTICAL else ORIENTATION_HORIZONTAL)
        return root
    }

    private fun applyLayoutJson(json: JSONObject) {
        val editor = prefs.edit()
        for (spec in SPECS) {
            val obj = json.optJSONObject(spec.id) ?: continue
            editor.putFloat(key(spec.id, "x"), obj.optDouble("x", defaultX(spec).toDouble()).toFloat())
            editor.putFloat(key(spec.id, "y"), obj.optDouble("y", defaultY(spec).toDouble()).toFloat())
            editor.putFloat(key(spec.id, "scale"), obj.optDouble("scale", 1.0).toFloat())
            editor.putBoolean(key(spec.id, "enabled"), obj.optBoolean("enabled", spec.defaultEnabled))
            editor.putFloat(key(spec.id, "opacity"), obj.optDouble("opacity", 1.0).toFloat())
            val defaultFill = if (spec.isJoystick) TouchJoystickView.DEFAULT_BASE_COLOR else spec.color
            val defaultOutline = if (spec.isJoystick) TouchJoystickView.DEFAULT_OUTLINE_COLOR else spec.outlineColor
            editor.putInt(key(spec.id, "fillColor"), obj.optInt("fillColor", defaultFill))
            editor.putInt(key(spec.id, "outlineColor"), obj.optInt("outlineColor", defaultOutline))
            if (spec.isJoystick) {
                editor.putInt(key(spec.id, "knobColor"), obj.optInt("knobColor", TouchJoystickView.DEFAULT_KNOB_COLOR))
            }
        }
        editor.apply()
        rebuildControls()
    }

    private fun libraryNames(): MutableSet<String> = LinkedHashSet(library.getStringSet(libraryNamesKey, emptySet()) ?: emptySet())

    private fun libraryKey(name: String) = (if (portraitMode) PORTRAIT_LAYOUT_PREFIX else LAYOUT_PREFIX) + name

    /** Layouts saved before the split all sit in the horizontal list. Sends each one that was made
     * in vertical mode to the vertical list, once: tagged ones by their tag, older ones by whether
     * their buttons sit nearer the vertical or the horizontal starting spots. */
    private fun splitLibraryOnce() {
        if (library.getBoolean(KEY_LIBRARY_SPLIT, false)) return
        val horizontal = LinkedHashSet(library.getStringSet(KEY_LIBRARY_NAMES, emptySet()) ?: emptySet())
        val vertical = LinkedHashSet(library.getStringSet(KEY_LIBRARY_NAMES_PORTRAIT, emptySet()) ?: emptySet())
        val editor = library.edit()
        for (name in horizontal.toList()) {
            val text = library.getString(LAYOUT_PREFIX + name, null) ?: continue
            val json = runCatching { JSONObject(text) }.getOrNull() ?: continue
            if (!madeUpright(json)) continue
            horizontal.remove(name)
            vertical.add(name)
            editor.remove(LAYOUT_PREFIX + name).putString(PORTRAIT_LAYOUT_PREFIX + name, text)
        }
        editor
            .putStringSet(KEY_LIBRARY_NAMES, horizontal)
            .putStringSet(KEY_LIBRARY_NAMES_PORTRAIT, vertical)
            .putBoolean(KEY_LIBRARY_SPLIT, true)
            .apply()
    }

    private fun madeUpright(json: JSONObject): Boolean {
        when (json.optString(KEY_ORIENTATION)) {
            ORIENTATION_VERTICAL -> return true
            ORIENTATION_HORIZONTAL -> return false
        }
        var nearVertical = 0.0
        var nearHorizontal = 0.0
        for (spec in SPECS) {
            val obj = json.optJSONObject(spec.id) ?: continue
            val x = obj.optDouble("x", spec.defaultXFraction.toDouble())
            val y = obj.optDouble("y", spec.defaultYFraction.toDouble())
            val upright = PORTRAIT_DEFAULTS[spec.id] ?: continue
            nearVertical += (x - upright.first) * (x - upright.first) + (y - upright.second) * (y - upright.second)
            nearHorizontal +=
                (x - spec.defaultXFraction) * (x - spec.defaultXFraction) + (y - spec.defaultYFraction) * (y - spec.defaultYFraction)
        }
        return nearVertical < nearHorizontal
    }

    private fun nextLayoutName(): String {
        val names = libraryNames()
        var n = names.size + 1
        while ("Layout $n" in names) n++
        return "Layout $n"
    }

    private fun promptSaveLayout() {
        val sheet = DriftSheet(activity, T("Save layout"), if (portraitMode) T("For vertical mode") else T("For horizontal mode"), compact = true)
        sheet.note("Saves the buttons as they are now. A name that's already in the list gets replaced.")
        val input = sheet.textField("Layout name", nextLayoutName())
        sheet.button("Cancel") { sheet.dismiss() }
        sheet.button("Save", DriftUi.Style.PRIMARY) {
            val name = input.text.toString().trim()
            if (name.isNotEmpty()) {
                saveNamedLayout(name, currentLayoutJson())
                sheet.dismiss()
            }
        }
        sheet.show()
        input.requestFocus()
    }

    private fun saveNamedLayout(name: String, json: JSONObject) {
        val names = libraryNames()
        names.add(name)
        library
            .edit()
            .putString(libraryKey(name), json.toString())
            .putStringSet(libraryNamesKey, names)
            .apply()
    }

    private fun deleteNamedLayout(name: String) {
        val names = libraryNames()
        names.remove(name)
        library.edit().remove(libraryKey(name)).putStringSet(libraryNamesKey, names).apply()
    }

    /** Tap a layout to use it; its "⋯" opens Replace / Export / Delete under it. */
    private fun showLayoutLibrary() {
        val sheet = DriftSheet(activity, T("Saved layouts"), if (portraitMode) "Vertical mode" else T("Horizontal mode"))
        fun render() {
            sheet.clear()
            val names = libraryNames().toList()
            if (names.isEmpty()) {
                sheet.note("Nothing saved yet. Save the layout on screen to keep it, then switch between layouts here.")
            } else {
                sheet.note("Tap a layout to use it. Vertical and horizontal mode each keep their own list.")
            }
            for (name in names) {
                val actions = LinearLayout(activity)
                actions.orientation = LinearLayout.HORIZONTAL
                actions.visibility = View.GONE
                actions.setPadding(0, 0, 0, dp(6f))
                val more = TextView(activity).apply {
                    text = "⋯"
                    textSize = 22f
                    gravity = Gravity.CENTER
                    setTextColor(DriftUi.TEXT)
                    background = DriftUi.pressable(activity, DriftUi.ROW_STRONG, 10f)
                    contentDescription = T("More for %s").format(name)
                    layoutParams = LinearLayout.LayoutParams(dp(48f), dp(42f)).apply { marginStart = dp(12f) }
                    setOnClickListener { actions.visibility = if (actions.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
                }
                sheet.action(name, "Tap to use", more) {
                    val json = library.getString(libraryKey(name), null)
                    if (json != null) {
                        applyLayoutJson(JSONObject(json))
                    }
                    sheet.dismiss()
                }
                fun add(button: TextView) {
                    actions.addView(
                        button,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (actions.childCount > 0) marginStart = dp(6f)
                        },
                    )
                }
                // Replaces this saved layout with whatever is currently live - requested directly
                // ("there should also be an overwrite option to replace one of the saved ones").
                lateinit var replace: TextView
                replace = DriftUi.button(activity, "Replace") {
                    saveNamedLayout(name, currentLayoutJson())
                    replace.text = T("Replaced ✓")
                }
                add(replace)
                add(
                    DriftUi.button(activity, "Export") {
                        val json = library.getString(libraryKey(name), null) ?: return@button
                        sheet.dismiss()
                        launchExportLayout(json)
                    },
                )
                lateinit var delete: TextView
                var armed = false
                delete = DriftUi.button(activity, "Delete", DriftUi.Style.DANGER) {
                    if (!armed) {
                        armed = true
                        delete.text = T("Sure?")
                    } else {
                        deleteNamedLayout(name)
                        render()
                    }
                }
                add(delete)
                sheet.custom(actions)
            }
            sheet.button("Import file") {
                sheet.dismiss()
                launchImportLayout()
            }
            sheet.button("Save current", DriftUi.Style.PRIMARY) {
                sheet.dismiss()
                promptSaveLayout()
            }
        }
        render()
        sheet.show()
    }

    private fun launchExportLayout(json: String) {
        pendingExportJson = json
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "application/json"
        intent.putExtra(Intent.EXTRA_TITLE, "driftdroid_touch_layout.json")
        activity.startActivityForResult(intent, REQUEST_CODE_EXPORT_LAYOUT)
    }

    private fun launchImportLayout() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        activity.startActivityForResult(intent, REQUEST_CODE_IMPORT_LAYOUT)
    }

    /** Call from the activity's onActivityResult. Returns true if this was one of this overlay's
     * own request codes. */
    fun onActivityResult(requestCode: Int, uri: Uri?): Boolean {
        when (requestCode) {
            REQUEST_CODE_EXPORT_LAYOUT -> {
                val json = pendingExportJson
                pendingExportJson = null
                if (uri != null && json != null) {
                    activity.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                }
                return true
            }
            REQUEST_CODE_IMPORT_LAYOUT -> {
                if (uri != null) {
                    val text = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.toString(Charsets.UTF_8)
                    if (text != null) {
                        try {
                            val json = JSONObject(text)
                            promptImportedLayoutName(json)
                        } catch (_: Exception) {
                            // Not a valid layout file - silently ignored, same as a cancelled picker.
                        }
                    }
                }
                return true
            }
            else -> return false
        }
    }

    private fun promptImportedLayoutName(json: JSONObject) {
        val sheet = DriftSheet(activity, T("Import layout"), if (portraitMode) T("Into the vertical list") else T("Into the horizontal list"), compact = true)
        if (madeUpright(json) != portraitMode) {
            sheet.note(
                if (portraitMode) {
                    "This layout was made for horizontal mode, so some buttons may land in odd places."
                } else {
                    "This layout was made for vertical mode, so some buttons may land in odd places."
                },
            )
        }
        val input = sheet.textField("Layout name", "Imported layout")
        sheet.button("Cancel") { sheet.dismiss() }
        sheet.button("Import", DriftUi.Style.PRIMARY) {
            val name = input.text.toString().trim().ifEmpty { "Imported layout" }
            saveNamedLayout(name, json)
            applyLayoutJson(json)
            sheet.dismiss()
        }
        sheet.show()
    }

    private fun defaultX(spec: ControlSpec): Float =
        if (portraitMode) PORTRAIT_DEFAULTS[spec.id]?.first ?: spec.defaultXFraction else spec.defaultXFraction

    private fun defaultY(spec: ControlSpec): Float =
        if (portraitMode) PORTRAIT_DEFAULTS[spec.id]?.second ?: spec.defaultYFraction else spec.defaultYFraction

    companion object {
        /** Set by MainActivity before attach() when the game runs in vertical mode. */
        var portraitMode = false

        private const val PREFS_NAME = "touch_controls"
        private const val PORTRAIT_PREFS_NAME = "touch_controls_portrait"

        // Vertical mode defaults (x, y as fractions of the tall play area): everything in the
        // lower half under the race, stick and D-pad on the left, face buttons on the right, L and
        // R above them, Start at the bottom centre.
        private val PORTRAIT_DEFAULTS =
            mapOf(
                "joystick" to (0.25f to 0.82f),
                "dpad_up" to (0.18f to 0.585f),
                "dpad_down" to (0.18f to 0.655f),
                "dpad_left" to (0.08f to 0.62f),
                "dpad_right" to (0.28f to 0.62f),
                "x" to (0.88f to 0.66f),
                "y" to (0.70f to 0.70f),
                "a" to (0.84f to 0.78f),
                "b" to (0.68f to 0.88f),
                "l" to (0.18f to 0.50f),
                "r" to (0.82f to 0.55f),
                "start" to (0.50f to 0.955f),
            )
        private const val LIBRARY_PREFS_NAME = "touch_controls_library"
        private const val KEY_MASTER_VISIBLE = "master_visible"
        private const val KEY_DOUBLE_TAP_AUTO_HOLD = "double_tap_auto_hold"
        private const val JOYSTICK_CONTROL_ID = "joystick"
        private const val KEY_LIBRARY_NAMES = "layout_names"
        private const val KEY_LIBRARY_NAMES_PORTRAIT = "portrait_layout_names"
        private const val KEY_LIBRARY_SPLIT = "split_by_orientation"
        private const val LAYOUT_PREFIX = "layout_"
        private const val PORTRAIT_LAYOUT_PREFIX = "portrait_layout_"
        private const val KEY_ORIENTATION = "orientation"
        private const val ORIENTATION_VERTICAL = "vertical"
        private const val ORIENTATION_HORIZONTAL = "horizontal"

        const val REQUEST_CODE_EXPORT_LAYOUT = 0x544C4558 // "TLEX"
        const val REQUEST_CODE_IMPORT_LAYOUT = 0x544C494D // "TLIM"

        private val SPECS =
            listOf(
                // Smaller than the first draft and moved down so it no longer overlaps L or the
                // D-Pad above it - the drag-anywhere zone is this view's own bounds, so the visual
                // size here doubles as the steering hit area.
                // Position and colors are the user's own fully hand-tuned layout, pulled live from
                // an installed device and adopted as the shipped default for every install
                // ("ok now make the default layout the one i have, with the colors and stuff").
                ControlSpec("joystick", "", -1, 0.12939323f, 0.68577796f, 170f, true, isJoystick = true),
                // Enabled by default: bikes need Up on the D-Pad to wheelie, which the analog
                // stick alone cannot do.
                ControlSpec("dpad_up", "▲", TouchInputBridge.BUTTON_DPAD_UP, 0.12407264f, 0.23613377f, 46f, true, color = 1862270975, outlineColor = -939524096),
                ControlSpec("dpad_down", "▼", TouchInputBridge.BUTTON_DPAD_DOWN, 0.12449999f, 0.3633333f, 46f, true, color = 1862270975, outlineColor = -939524096),
                ControlSpec("dpad_left", "◀", TouchInputBridge.BUTTON_DPAD_LEFT, 0.06703846f, 0.30883333f, 46f, true, color = 1862270975, outlineColor = -939524096),
                ControlSpec("dpad_right", "▶", TouchInputBridge.BUTTON_DPAD_RIGHT, 0.18196154f, 0.30883333f, 46f, true, color = 1862270975, outlineColor = -939524096),
                // Switch Pro Controller diamond: X top, Y left, A right (the big button), B bottom.
                // Custom colors are a per-button customization the player can further override via
                // the control editor (tap a button in edit mode). Only the L/R/Start shape is
                // structurally different by default: a stretched "pill" instead of a circle,
                // matching a real shoulder button's proportions.
                ControlSpec("x", "X", TouchInputBridge.BUTTON_X, 0.85796094f, 0.47683534f, 66f, true, color = 1862257920, outlineColor = -931108096),
                ControlSpec("y", "Y", TouchInputBridge.BUTTON_Y, 0.774085f, 0.64033693f, 66f, true, color = 1845559067, outlineColor = -937015552),
                ControlSpec("a", "A", TouchInputBridge.BUTTON_A, 0.93149424f, 0.623813f, 66f, true, color = 1862205440, outlineColor = -939524096),
                ControlSpec("b", "B", TouchInputBridge.BUTTON_B, 0.8581538f, 0.8199371f, 74f, true, color = 1846542591, outlineColor = -939524010),
                // L=item, R=drift (digital shoulder buttons, per the Classic Controller Pro
                // preset AutoConfigureTouchControllerIfPresent applies - see TouchInputBridge).
                ControlSpec("l", "L", TouchInputBridge.BUTTON_L, 0.085525885f, 0.08287472f, 68f, true, pill = true, color = 1862270975, outlineColor = -922746881),
                // Lower than L: the top-right corner is shared with the FPS counter - confirmed
                // on-device, R's hit-circle up there was swallowing taps. The settings gear no
                // longer lives up here at all (moved to top-center, see MainActivity).
                ControlSpec("r", "R", TouchInputBridge.BUTTON_R, 0.86071074f, 0.2600174f, 68f, true, pill = true, color = 1862270975, outlineColor = -922746881),
                // No Select control: Mario Kart Wii has no Select-equivalent action, so a touch
                // button for it would just do nothing.
                ControlSpec("start", "Start", TouchInputBridge.BUTTON_START, 0.47884616f, 0.83566666f, 60f, true, pill = true, color = 1862270975, outlineColor = -922746881),
            )

        fun attach(activity: Activity, parent: ViewGroup): TouchControlsOverlay = TouchControlsOverlay(activity, parent)
    }
}
