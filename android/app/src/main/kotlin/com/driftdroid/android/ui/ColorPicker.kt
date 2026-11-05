package com.driftdroid.android.ui

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import com.driftdroid.android.T
import kotlin.math.max
import kotlin.math.min

/**
 * A colour picker: a square for saturation (across) and brightness (down) at the chosen hue, and a
 * hue bar under it. Touch or drag either; [onChange] fires with the new colour as you go.
 */
internal class ColorPickerView(context: Context) : View(context) {
    var onChange: ((Int) -> Unit)? = null

    private var hue = 0f
    private var sat = 1f
    private var value = 1f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val square = RectF()
    private val bar = RectF()
    private var dragging = 0 // 1 = square, 2 = hue bar
    private val density = resources.displayMetrics.density

    fun setColor(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hue = hsv[0]
        sat = hsv[1]
        value = hsv[2]
        invalidate()
    }

    fun color(): Int = Color.HSVToColor(floatArrayOf(hue, sat, value))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (w * 0.62f + 14 * density + 30 * density).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val squareH = w * 0.62f
        square.set(0f, 0f, w.toFloat(), squareH)
        bar.set(0f, squareH + 14 * density, w.toFloat(), squareH + 14 * density + 30 * density)
    }

    override fun onDraw(canvas: Canvas) {
        val r = 10 * density
        // Saturation across, brightness down.
        val pure = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        fill.shader = LinearGradient(square.left, 0f, square.right, 0f, Color.WHITE, pure, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(square, r, r, fill)
        fill.shader = LinearGradient(0f, square.top, 0f, square.bottom, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(square, r, r, fill)
        // Hue bar.
        val stops = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
        fill.shader = LinearGradient(bar.left, 0f, bar.right, 0f, stops, null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(bar, bar.height() / 2f, bar.height() / 2f, fill)
        fill.shader = null
        // Handles.
        thumb(canvas, square.left + sat * square.width(), square.top + (1f - value) * square.height(), color())
        thumb(canvas, bar.left + hue / 360f * bar.width(), bar.centerY(), pure)
    }

    private fun thumb(canvas: Canvas, x: Float, y: Float, color: Int) {
        val rad = 11 * density
        fill.color = color
        canvas.drawCircle(x, y, rad, fill)
        ring.strokeWidth = 3 * density
        ring.color = Color.WHITE
        canvas.drawCircle(x, y, rad, ring)
        ring.strokeWidth = 1f * density
        ring.color = 0x66000000
        canvas.drawCircle(x, y, rad + 2 * density, ring)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = if (e.y <= square.bottom + 7 * density) 1 else 2
                apply(e)
            }
            MotionEvent.ACTION_MOVE -> apply(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = 0
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun apply(e: MotionEvent) {
        if (dragging == 1) {
            sat = ((e.x - square.left) / square.width()).coerceIn(0f, 1f)
            value = 1f - ((e.y - square.top) / square.height()).coerceIn(0f, 1f)
        } else {
            hue = (((e.x - bar.left) / bar.width()) * 360f).coerceIn(0f, 359.9f)
        }
        invalidate()
        onChange?.invoke(color())
    }
}

/** A sheet with the picker, a hex box and a preview swatch. [onPicked] gets the chosen colour. */
internal object ColorPickerSheet {
    fun show(activity: Activity, title: String, initial: Int, onPicked: (Int) -> Unit) {
        val sheet = DriftSheet(activity, title)
        val picker = ColorPickerView(activity)
        picker.setColor(initial)
        sheet.custom(picker)
        val hex = sheet.textField("Hex colour, like #3DDC84", hexOf(initial))
        var updating = false
        picker.onChange = { c ->
            updating = true
            hex.setText(hexOf(c))
            updating = false
        }
        hex.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}

                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}

                override fun afterTextChanged(s: Editable?) {
                    if (updating) return
                    val text = s?.toString()?.trim()?.removePrefix("#") ?: return
                    if (text.length == 6 && text.all { it in "0123456789abcdefABCDEF" }) {
                        picker.setColor(Color.parseColor("#$text"))
                    }
                }
            },
        )
        sheet.button(T("Use this colour"), DriftUi.Style.PRIMARY) {
            sheet.dismiss()
            onPicked(picker.color())
        }
        sheet.button(T("Cancel"), DriftUi.Style.SECONDARY) { sheet.dismiss() }
        sheet.show()
    }

    private fun hexOf(color: Int): String = "#%06X".format(color and 0xFFFFFF)
}
