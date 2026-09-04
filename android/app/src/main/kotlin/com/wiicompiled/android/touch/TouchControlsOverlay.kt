package com.wiicompiled.android.touch

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONObject

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
 * Config.toml), and drives layout editing (drag/resize/hide, named layout save/load, file
 * export/import).
 *
 * Layout mirrors a Nintendo Switch Pro Controller, since that's the default mapping
 * AutoConfigureTouchControllerIfPresent (settings_overlay.cpp) applies to this virtual pad:
 * A accelerates, B reverses, L=item, R=drift, Start ("+") pauses, Select ("-") is GC Z, and the
 * D-Pad is enabled by default since bikes need it (Up) to wheelie.
 */
class TouchControlsOverlay private constructor(private val activity: Activity, parent: ViewGroup) {

    private val prefs: SharedPreferences = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val library: SharedPreferences = activity.getSharedPreferences(LIBRARY_PREFS_NAME, Context.MODE_PRIVATE)
    private val container = FrameLayout(activity)
    private val views = LinkedHashMap<String, View>()
    private var editMode = false
    private val toolbar = LinearLayout(activity)
    private var pendingExportJson: String? = null

    // Effective visibility is userVisible AND NOT controllerConnected: a real Bluetooth controller
    // auto-hides the touch overlay (see MainActivity.onNativeGamepadConnectionChanged, driven by
    // aurora-main/lib/window.cpp's SDL_EVENT_GAMEPAD_ADDED/REMOVED), while userVisible is the
    // persisted "Touch controls" checkbox in the in-game settings menu
    // (MainActivity.onNativeSetTouchOverlayVisible). Independent of the per-button drag/hide state
    // stored below - this only ever hides/shows the WHOLE overlay at once.
    private var userVisible = true
    private var controllerConnected = false

    /** Lets MainActivity keep the settings gear's dimmed/highlighted state in sync when edit mode
     * exits via the toolbar's "Done" button rather than however it was entered. */
    var onEditModeChanged: ((Boolean) -> Unit)? = null

    init {
        userVisible = prefs.getBoolean(KEY_MASTER_VISIBLE, true)

        parent.addView(
            container,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        applyMasterVisibility()

        buildToolbar()

        // Build the controls after the container has a real size - fractions need it to convert
        // to pixel positions, and onCreate() hasn't laid anything out yet at this point.
        container.post {
            for (spec in SPECS) {
                addControl(spec)
            }
            val toolbarParams =
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            toolbarParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            toolbarParams.topMargin = dp(70f)
            container.addView(toolbar, toolbarParams)
        }
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, container.resources.displayMetrics).toInt()

    private fun toolbarButton(text: String, onClick: () -> Unit): Button {
        val button = Button(activity)
        button.text = text
        button.textSize = 12f
        button.setOnClickListener { onClick() }
        return button
    }

    /** "Done editing" first and most prominent - the previous design (long-press the gear again
     * to exit) was confirmed confusing on-device ("i clicked on the gear it sent me to edit
     * layout, but i couldn't exit out of it"). Entering edit mode is now the gear's 5-second-hold
     * gesture (MainActivity.addSettingsButton) - exiting is always this explicit button. */
    private fun buildToolbar() {
        toolbar.orientation = LinearLayout.HORIZONTAL
        toolbar.visibility = View.GONE
        toolbar.setBackgroundColor(Color.argb(180, 30, 30, 30))
        val pad = dp(6f)
        toolbar.setPadding(pad, pad, pad, pad)

        val doneButton = toolbarButton("Done") { setEditMode(false) }
        doneButton.setBackgroundColor(Color.rgb(60, 130, 70))
        toolbar.addView(doneButton)
        toolbar.addView(toolbarButton("Reset layout") { resetLayout() })
        toolbar.addView(toolbarButton("Save layout") { promptSaveLayout() })
        toolbar.addView(toolbarButton("Layouts...") { showLayoutLibraryDialog() })
    }

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
        val opacity = prefs.getFloat(key(spec.id, "opacity"), 1f)

        view.x = xFraction * containerWidth - sizePx / 2f
        view.y = yFraction * containerHeight - sizePx / 2f
        view.scaleX = scale
        view.scaleY = scale
        view.visibility = if (enabled) View.VISIBLE else View.GONE
        view.alpha = if (enabled) opacity else 0.35f

        val onEditEnd: (Boolean) -> Unit = { moved ->
            if (moved) {
                saveTransform(spec.id, view, containerWidth, containerHeight)
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

    private fun showControlEditor(spec: ControlSpec, view: View) {
        val activityContext = activity
        val root = LinearLayout(activityContext)
        root.orientation = LinearLayout.VERTICAL
        val pad = dp(16f)
        root.setPadding(pad, pad, pad, pad)

        val visibleCheckbox = CheckBox(activityContext)
        visibleCheckbox.text = "Visible"
        visibleCheckbox.isChecked = view.visibility == View.VISIBLE
        root.addView(visibleCheckbox)

        val opacityLabel = TextView(activityContext)
        opacityLabel.text = "Opacity"
        opacityLabel.setPadding(0, dp(12f), 0, 0)
        root.addView(opacityLabel)

        val startingOpacity = prefs.getFloat(key(spec.id, "opacity"), 1f)
        val opacitySeekBar = SeekBar(activityContext)
        opacitySeekBar.max = 100
        opacitySeekBar.progress = (startingOpacity * 100).toInt().coerceIn(10, 100)
        opacitySeekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                    // A floor of 10% keeps a control from becoming a truly invisible-but-still-
                    // there dead zone on screen - Visible unchecked is the actual "hide it" action.
                    if (fromUser && visibleCheckbox.isChecked) {
                        view.alpha = progress.coerceAtLeast(10) / 100f
                    }
                }

                override fun onStartTrackingTouch(seek: SeekBar) {}

                override fun onStopTrackingTouch(seek: SeekBar) {}
            },
        )
        root.addView(opacitySeekBar)

        val sizeLabel = TextView(activityContext)
        sizeLabel.text = "Size"
        sizeLabel.setPadding(0, dp(12f), 0, 0)
        root.addView(sizeLabel)

        val sizeRow = LinearLayout(activityContext)
        sizeRow.orientation = LinearLayout.HORIZONTAL
        sizeRow.gravity = Gravity.CENTER_VERTICAL

        // Scale range mirrors EditGestureHelper's pinch-to-resize clamp (0.5x-2.5x) so typing a
        // value and pinching can never disagree about what's achievable.
        val seekBar = SeekBar(activityContext)
        seekBar.max = 200
        val currentScale = view.scaleX
        seekBar.progress = (((currentScale - 0.5f) / 2.0f) * 200).toInt().coerceIn(0, 200)
        val seekParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        sizeRow.addView(seekBar, seekParams)

        val sizeInput = EditText(activityContext)
        sizeInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        sizeInput.setText(String.format("%.2f", currentScale))
        val inputParams = LinearLayout.LayoutParams(dp(80f), ViewGroup.LayoutParams.WRAP_CONTENT)
        inputParams.marginStart = dp(8f)
        sizeInput.layoutParams = inputParams

        var syncingFromSeek = false
        var syncingFromText = false
        seekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val scale = 0.5f + (progress / 200f) * 2.0f
                    view.scaleX = scale
                    view.scaleY = scale
                    if (!syncingFromText) {
                        syncingFromSeek = true
                        sizeInput.setText(String.format("%.2f", scale))
                        syncingFromSeek = false
                    }
                }

                override fun onStartTrackingTouch(seek: SeekBar) {}

                override fun onStopTrackingTouch(seek: SeekBar) {}
            },
        )
        sizeInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus || syncingFromSeek) return@setOnFocusChangeListener
            val typed = sizeInput.text.toString().toFloatOrNull()?.coerceIn(0.5f, 2.5f) ?: return@setOnFocusChangeListener
            view.scaleX = typed
            view.scaleY = typed
            syncingFromText = true
            seekBar.progress = (((typed - 0.5f) / 2.0f) * 200).toInt().coerceIn(0, 200)
            syncingFromText = false
        }

        sizeRow.addView(sizeInput)
        root.addView(sizeRow)

        AlertDialog.Builder(activityContext)
            .setTitle(spec.label.ifEmpty { "Joystick" })
            .setView(root)
            .setPositiveButton("Close") { _, _ ->
                val enabled = visibleCheckbox.isChecked
                val opacity = opacitySeekBar.progress.coerceAtLeast(10) / 100f
                view.visibility = if (enabled) View.VISIBLE else View.GONE
                view.alpha = if (enabled) opacity else 0.35f
                val containerWidth = container.width.takeIf { it > 0 } ?: container.resources.displayMetrics.widthPixels
                val containerHeight = container.height.takeIf { it > 0 } ?: container.resources.displayMetrics.heightPixels
                prefs
                    .edit()
                    .putBoolean(key(spec.id, "enabled"), enabled)
                    .putFloat(key(spec.id, "scale"), view.scaleX)
                    .putFloat(key(spec.id, "opacity"), opacity)
                    .apply()
                saveTransform(spec.id, view, containerWidth, containerHeight)
                // Edit mode keeps disabled controls visible-but-dimmed so they stay reachable -
                // matches the pre-dialog behavior.
                if (!enabled) {
                    view.visibility = View.VISIBLE
                }
            }
            .show()
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

    fun isUserVisible(): Boolean = userVisible

    /** From the in-game settings menu's "Touch controls" checkbox (settings_overlay.cpp, via
     * MainActivity.onNativeSetTouchOverlayVisible). */
    fun setUserVisible(visible: Boolean) {
        userVisible = visible
        prefs.edit().putBoolean(KEY_MASTER_VISIBLE, visible).apply()
        applyMasterVisibility()
    }

    /** From MainActivity.onNativeGamepadConnectionChanged - a real controller connecting/
     * disconnecting, not the touch overlay's own virtual gamepad (filtered out native-side). */
    fun setControllerConnected(connected: Boolean) {
        controllerConnected = connected
        applyMasterVisibility()
    }

    private fun applyMasterVisibility() {
        container.visibility = if (userVisible && !controllerConnected) View.VISIBLE else View.GONE
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

    private fun resetLayout() {
        prefs.edit().clear().apply()
        rebuildControls()
    }

    private fun rebuildControls() {
        val wasEditing = editMode
        container.removeAllViews()
        views.clear()
        editMode = false
        for (spec in SPECS) {
            addControl(spec)
        }
        val toolbarParams =
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        toolbarParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        toolbarParams.topMargin = dp(70f)
        container.addView(toolbar, toolbarParams)
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
            obj.put("x", prefs.getFloat(key(spec.id, "x"), spec.defaultXFraction).toDouble())
            obj.put("y", prefs.getFloat(key(spec.id, "y"), spec.defaultYFraction).toDouble())
            obj.put("scale", prefs.getFloat(key(spec.id, "scale"), 1f).toDouble())
            obj.put("enabled", prefs.getBoolean(key(spec.id, "enabled"), spec.defaultEnabled))
            obj.put("opacity", prefs.getFloat(key(spec.id, "opacity"), 1f).toDouble())
            root.put(spec.id, obj)
        }
        return root
    }

    private fun applyLayoutJson(json: JSONObject) {
        val editor = prefs.edit()
        for (spec in SPECS) {
            val obj = json.optJSONObject(spec.id) ?: continue
            editor.putFloat(key(spec.id, "x"), obj.optDouble("x", spec.defaultXFraction.toDouble()).toFloat())
            editor.putFloat(key(spec.id, "y"), obj.optDouble("y", spec.defaultYFraction.toDouble()).toFloat())
            editor.putFloat(key(spec.id, "scale"), obj.optDouble("scale", 1.0).toFloat())
            editor.putBoolean(key(spec.id, "enabled"), obj.optBoolean("enabled", spec.defaultEnabled))
            editor.putFloat(key(spec.id, "opacity"), obj.optDouble("opacity", 1.0).toFloat())
        }
        editor.apply()
        rebuildControls()
    }

    private fun libraryNames(): MutableSet<String> = LinkedHashSet(library.getStringSet(KEY_LIBRARY_NAMES, emptySet()) ?: emptySet())

    private fun promptSaveLayout() {
        val input = EditText(activity)
        input.hint = "Layout name"
        AlertDialog.Builder(activity)
            .setTitle("Save layout")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    saveNamedLayout(name, currentLayoutJson())
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveNamedLayout(name: String, json: JSONObject) {
        val names = libraryNames()
        names.add(name)
        library
            .edit()
            .putString(libraryKey(name), json.toString())
            .putStringSet(KEY_LIBRARY_NAMES, names)
            .apply()
    }

    private fun deleteNamedLayout(name: String) {
        val names = libraryNames()
        names.remove(name)
        library.edit().remove(libraryKey(name)).putStringSet(KEY_LIBRARY_NAMES, names).apply()
    }

    private fun libraryKey(name: String) = "layout_$name"

    private fun showLayoutLibraryDialog() {
        val names = libraryNames().toList()
        val root = LinearLayout(activity)
        root.orientation = LinearLayout.VERTICAL
        val pad = dp(8f)
        root.setPadding(pad, pad, pad, pad)

        if (names.isEmpty()) {
            val empty = TextView(activity)
            empty.text = "No saved layouts yet."
            empty.setPadding(pad, pad, pad, pad)
            root.addView(empty)
        }
        for (name in names) {
            val row = LinearLayout(activity)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL

            val nameButton = Button(activity)
            nameButton.text = name
            val nameParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(nameButton, nameParams)

            val exportButton = toolbarButton("Export") {
                val json = library.getString(libraryKey(name), null) ?: return@toolbarButton
                launchExportLayout(json)
            }
            row.addView(exportButton)

            val deleteButton = toolbarButton("Delete") {
                deleteNamedLayout(name)
                showLayoutLibraryDialog()
            }
            row.addView(deleteButton)

            root.addView(row)
        }

        val dialog =
            AlertDialog.Builder(activity)
                .setTitle("Layouts")
                .setView(root)
                .setPositiveButton("Import from file...") { _, _ -> launchImportLayout() }
                .setNegativeButton("Close", null)
                .create()

        // Tapping a name applies it and closes - wired after dialog creation so the click
        // listener can dismiss it (Builder's own buttons handle their own dismissal already).
        for (i in 0 until root.childCount) {
            val row = root.getChildAt(i) as? LinearLayout ?: continue
            val nameButton = row.getChildAt(0) as? Button ?: continue
            val name = nameButton.text.toString()
            nameButton.setOnClickListener {
                val json = library.getString(libraryKey(name), null)
                if (json != null) {
                    applyLayoutJson(JSONObject(json))
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun launchExportLayout(json: String) {
        pendingExportJson = json
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "application/json"
        intent.putExtra(Intent.EXTRA_TITLE, "wiicompiled_touch_layout.json")
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
        val input = EditText(activity)
        input.hint = "Layout name"
        input.setText("Imported layout")
        AlertDialog.Builder(activity)
            .setTitle("Import layout")
            .setView(input)
            .setPositiveButton("Import") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Imported layout" }
                saveNamedLayout(name, json)
                applyLayoutJson(json)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private const val PREFS_NAME = "touch_controls"
        private const val LIBRARY_PREFS_NAME = "touch_controls_library"
        private const val KEY_MASTER_VISIBLE = "master_visible"
        private const val KEY_LIBRARY_NAMES = "layout_names"

        const val REQUEST_CODE_EXPORT_LAYOUT = 0x544C4558 // "TLEX"
        const val REQUEST_CODE_IMPORT_LAYOUT = 0x544C494D // "TLIM"

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
                // Lower than L: the top-right corner is shared with the FPS counter - confirmed
                // on-device, R's hit-circle up there was swallowing taps. The settings gear no
                // longer lives up here at all (moved to top-center, see MainActivity).
                ControlSpec("r", "R", TouchInputBridge.BUTTON_R, 0.90f, 0.22f, 68f, true),
                // No Select control: Mario Kart Wii has no Select-equivalent action, so a touch
                // button for it would just do nothing.
                ControlSpec("start", "Start", TouchInputBridge.BUTTON_START, 0.5f, 0.92f, 60f, true),
            )

        fun attach(activity: Activity, parent: ViewGroup): TouchControlsOverlay = TouchControlsOverlay(activity, parent)
    }
}
