package com.wiicompiled.android.touch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * One round on-screen button (A, B, X, Y, L, R, Start, Select, or a D-Pad direction). Sends
 * SDL_GamepadButton down/up events straight to the virtual gamepad
 * (runtime/src/android_touch_controls.cpp) - the same pipeline a physical controller uses.
 */
class TouchButtonView(
    context: Context,
    private val label: String,
    private val sdlButton: Int,
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

    private val fillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(110, 255, 255, 255)
            style = Paint.Style.FILL
        }
    private val fillPressedPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 255, 255, 255)
            style = Paint.Style.FILL
        }
    private val borderPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
    private val editBorderPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 255, 215, 0)
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 42f
            isFakeBoldText = true
        }

    private val editGesture = EditGestureHelper(context, this) { moved -> onEditEnd?.invoke(moved) }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) / 2f - 6f
        canvas.drawCircle(cx, cy, radius, if (pressed) fillPressedPaint else fillPaint)
        canvas.drawCircle(cx, cy, radius, if (editMode) editBorderPaint else borderPaint)
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
}
