package com.wiicompiled.android.touch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * One on-screen button (A, B, X, Y, L, R, Start, Select, or a D-Pad direction) - either a circle
 * or a stretched "pill" (rounded rectangle). White/translucent by default, same as always -
 * fillColor/outlineColor/textColor are a per-button customization the player opts into via the
 * control editor (tap a button in edit mode), not a different hardcoded default - confirmed
 * directly ("the touchpad controls should just be white by default, what i meant is there should
 * be customizability for custom colors, outlines etc"). Sends SDL_GamepadButton down/up events
 * straight to the virtual gamepad (runtime/src/android_touch_controls.cpp) - the same pipeline a
 * physical controller uses.
 */
class TouchButtonView(
    context: Context,
    private val label: String,
    private val sdlButton: Int,
    fillColor: Int = DEFAULT_FILL_COLOR,
    var pill: Boolean = false,
    textColor: Int = Color.WHITE,
    outlineColor: Int = DEFAULT_OUTLINE_COLOR,
) : View(context) {

    // A plain property here would leave the yellow edit-mode border painted stale until the next
    // unrelated redraw (confirmed on-device: exiting edit mode left every button yellow until it
    // was pressed once, which is what actually triggered onDraw again) - setting this externally
    // (TouchControlsOverlay.setEditMode) must repaint immediately regardless of touch activity.
    var editMode: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var onEditEnd: ((moved: Boolean) -> Unit)? = null

    private var pressed = false

    private fun withAlpha(color: Int, alpha: Int) =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 42f
            isFakeBoldText = true
        }

    /** Live-editable so the control editor dialog can preview a color change immediately, rather
     * than only taking effect after the view is recreated. */
    var fillColor: Int = fillColor
        set(value) {
            field = value
            fillPaint.color = value
            fillPressedPaint.color = withAlpha(value, minOf(255, Color.alpha(value) + 80))
            invalidate()
        }
    var outlineColor: Int = outlineColor
        set(value) {
            field = value
            borderPaint.color = value
            invalidate()
        }
    var textColor: Int = textColor
        set(value) {
            field = value
            textPaint.color = value
            invalidate()
        }

    init {
        fillPaint.color = fillColor
        fillPressedPaint.color = withAlpha(fillColor, minOf(255, Color.alpha(fillColor) + 80))
        borderPaint.color = outlineColor
        textPaint.color = textColor
    }

    private val editGesture = EditGestureHelper(context, this) { moved -> onEditEnd?.invoke(moved) }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val fill = if (pressed) fillPressedPaint else fillPaint
        // Always the real, user-chosen outline color - it used to be forced yellow in edit mode,
        // which made it impossible to actually see the outline color you'd just picked while
        // editing it. Confirmed directly ("it makes you unable to see what outline color you
        // chose").
        val stroke = borderPaint
        if (pill) {
            val inset = 6f
            val rect = RectF(inset, inset, width - inset, height - inset)
            val cornerRadius = (height - inset * 2f) / 2f
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fill)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, stroke)
        } else {
            val radius = minOf(width, height) / 2f - 6f
            canvas.drawCircle(cx, cy, radius, fill)
            canvas.drawCircle(cx, cy, radius, stroke)
        }
        canvas.drawText(label, cx, cy - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editMode) {
            editGesture.onTouchEvent(event)
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                sendInputState(true)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressed = false
                sendInputState(false)
                invalidate()
            }
        }
        return true
    }

    private fun sendInputState(down: Boolean) {
        TouchInputBridge.nativeSetTouchButton(sdlButton, down)
    }

    companion object {
        val DEFAULT_FILL_COLOR = Color.argb(110, 255, 255, 255)
        val DEFAULT_OUTLINE_COLOR = Color.argb(200, 255, 255, 255)
    }
}
