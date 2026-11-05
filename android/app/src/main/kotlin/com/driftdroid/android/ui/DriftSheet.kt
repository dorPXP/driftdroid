package com.driftdroid.android.ui

import com.driftdroid.android.T
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.min

/** Colours and small builders shared by every in-game Android menu. They match the native settings
 * drawer (settings_overlay.cpp's ui:: colours), so the touch layout editor and the motion menu
 * look like the same app as the drawer that opens them. */
internal object DriftUi {
    const val PANEL = 0xFF141A33.toInt()
    const val ROW = 0xFF1B2242.toInt()
    const val ROW_STRONG = 0xFF242D57.toInt()
    const val LINE = 0xFF2A3360.toInt()
    const val TEXT = 0xFFEEF1FA.toInt()
    const val MUTED = 0xFF9AA3C7.toInt()
    const val ACCENT = 0xFF7FD957.toInt()
    const val ACCENT_TEXT = 0xFF10200A.toInt()
    const val FOCUS = 0xFF3FD0F0.toInt()
    const val TRACK_OFF = 0xFF33395E.toInt()
    const val KNOB_OFF = 0xFFC7CDE6.toInt()
    const val DANGER = 0xFFE5636B.toInt()

    fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    /** A rounded fill that ripples when pressed. */
    fun pressable(context: Context, color: Int, radiusDp: Float = 12f): Drawable {
        val radius = dp(context, radiusDp).toFloat()
        val shape = GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = radius
        }
        return RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), shape, mask)
    }

    enum class Style { PRIMARY, SECONDARY, DANGER }

    /** A flat pill button: green for the main action, row-coloured otherwise. */
    fun button(context: Context, text: String, style: Style = Style.SECONDARY, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = T(text)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isSingleLine = true
            minHeight = dp(context, 46f)
            setPadding(dp(context, 14f), 0, dp(context, 14f), 0)
            when (style) {
                Style.PRIMARY -> {
                    background = pressable(context, ACCENT)
                    setTextColor(ACCENT_TEXT)
                }
                Style.SECONDARY -> {
                    background = pressable(context, ROW_STRONG)
                    setTextColor(TEXT)
                }
                Style.DANGER -> {
                    background = pressable(context, ROW_STRONG)
                    setTextColor(DANGER)
                }
            }
            setOnClickListener { onClick() }
        }

    fun styleField(field: EditText) {
        val context = field.context
        field.setTextColor(TEXT)
        field.setHintTextColor(MUTED)
        field.textSize = 16f
        field.background = GradientDrawable().apply {
            setColor(ROW)
            cornerRadius = dp(context, 12f).toFloat()
            setStroke(dp(context, 1.5f), LINE)
        }
        field.setPadding(dp(context, 14f), dp(context, 12f), dp(context, 14f), dp(context, 12f))
    }

    fun setUsable(view: View, usable: Boolean) {
        view.isEnabled = usable
        view.alpha = if (usable) 1f else 0.4f
    }
}

/**
 * The colours a [DriftSheet] draws with. In game it is the native settings drawer's ([GAME]); a
 * screen with a look of its own - the launcher - hands sheets its palette by being [SheetThemed].
 */
class SheetPalette internal constructor(
    val panel: Int,
    val row: Int,
    val rowStrong: Int,
    val line: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
    val accentText: Int,
    val focus: Int,
    val trackOff: Int,
    val knobOff: Int,
    val danger: Int,
    val radiusDp: Float,
    val panelRadiusDp: Float,
    /** Footer buttons as the launcher's slanted plates instead of the drawer's pills. */
    val plates: Boolean,
) {
    internal fun pressable(context: Context, color: Int, radiusDp: Float = this.radiusDp): Drawable =
        DriftUi.pressable(context, color, min(radiusDp, if (plates) this.radiusDp else radiusDp))

    internal fun button(context: Context, text: String, style: DriftUi.Style, onClick: () -> Unit): TextView {
        if (!plates) return DriftUi.button(context, text, style, onClick)
        val (fill, ink) =
            when (style) {
                DriftUi.Style.PRIMARY -> accent to accentText
                DriftUi.Style.SECONDARY -> rowStrong to this.text
                DriftUi.Style.DANGER -> Lx.alpha(danger, 0.16f) to danger
            }
        return TextView(context).apply {
            this.text = text.uppercase()
            textSize = 15f
            typeface = Lx.CONDENSED
            letterSpacing = 0.04f
            gravity = Gravity.CENTER
            isSingleLine = true
            minHeight = DriftUi.dp(context, 46f)
            setPadding(DriftUi.dp(context, 18f), 0, DriftUi.dp(context, 18f), 0)
            background = Lx.plate(context, fill)
            setTextColor(ink)
            setOnClickListener { onClick() }
        }
    }

    internal fun styleField(field: EditText) {
        if (!plates) return DriftUi.styleField(field)
        field.setTextColor(text)
        field.setHintTextColor(muted)
        field.textSize = 16f
        field.background = Lx.fieldBackground(field.context)
        field.setPadding(DriftUi.dp(field.context, 14f), DriftUi.dp(field.context, 12f), DriftUi.dp(field.context, 14f), DriftUi.dp(field.context, 12f))
    }

    companion object {
        val GAME =
            SheetPalette(
                DriftUi.PANEL, DriftUi.ROW, DriftUi.ROW_STRONG, DriftUi.LINE, DriftUi.TEXT, DriftUi.MUTED, DriftUi.ACCENT,
                DriftUi.ACCENT_TEXT, DriftUi.FOCUS, DriftUi.TRACK_OFF, DriftUi.KNOB_OFF, DriftUi.DANGER, 12f, 20f, false,
            )

        val LAUNCHER: SheetPalette
            get() = SheetPalette(
                Lx.SURFACE, Lx.SURFACE_HI, if (Theme.isLight) LookDrawable.darken(Lx.SURFACE_HI, 0.10f) else 0xFF292E36.toInt(), Lx.LINE, Lx.TEXT, Lx.MUTED, Lx.ACCENT,
                Lx.ACCENT_TEXT, Lx.MUTED, Lx.LINE, Lx.MUTED, Lx.DANGER, 8f, 14f, true,
            )

        internal fun of(activity: Activity): SheetPalette = (activity as? SheetThemed)?.sheetPalette ?: GAME
    }
}

/** A screen whose sheets should wear its own colours rather than the in-game drawer's. */
interface SheetThemed {
    val sheetPalette: SheetPalette
}

/** The drawer's on/off switch: green track with the knob on the right when on. */
internal class PillSwitch(context: Context) : View(context) {
    var palette: SheetPalette = SheetPalette.GAME
    var isOn = false
        set(value) {
            field = value
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        paint.color = if (isOn) palette.accent else palette.trackOff
        canvas.drawRoundRect(0f, 0f, w, h, r, r, paint)
        paint.color = if (isOn) Color.WHITE else palette.knobOff
        canvas.drawCircle(if (isOn) w - r else r, r, r * 0.74f, paint)
    }
}

/** The check circle of a [DriftSheet.radio] row. */
internal class RadioMark(context: Context) : View(context) {
    var palette: SheetPalette = SheetPalette.GAME
    var picked = false
        set(value) {
            field = value
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        val size = DriftUi.dp(context, 24f)
        layoutParams = LinearLayout.LayoutParams(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val r = width / 2f
        val stroke = DriftUi.dp(context, 2f).toFloat()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = if (picked) palette.accent else palette.muted
        canvas.drawCircle(r, r, r - stroke, paint)
        if (picked) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(r, r, r * 0.5f, paint)
        }
    }
}

/** Never taller than [maxHeightPx], so a long list scrolls inside the sheet instead of pushing the
 * buttons under it off the screen. */
private class CappedScrollView(context: Context, private val maxHeightPx: Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val capped =
            if (mode == MeasureSpec.UNSPECIFIED) {
                MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
            } else {
                MeasureSpec.makeMeasureSpec(min(size, maxHeightPx), mode)
            }
        super.onMeasure(widthMeasureSpec, capped)
    }
}

/**
 * One in-game menu panel: docked to the right edge in landscape and rising from the bottom in
 * vertical mode, like the native settings drawer. Rows go into [body] through the builders below;
 * [button] adds to the footer. The game keeps running full screen behind it.
 */
internal class DriftSheet(
    private val activity: Activity,
    title: String,
    subtitle: String? = null,
    dock: Dock = Dock.AUTO,
    // A short question or a one-field prompt: rises from the bottom as a card in landscape too,
    // instead of a full-height side panel with one line in it.
    compact: Boolean = false,
) {
    enum class Dock { AUTO, START, END, TOP, BOTTOM }

    private val pal = SheetPalette.of(activity)
    private val portrait = activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    private val dock =
        when (dock) {
            Dock.AUTO -> if (portrait || compact) Dock.BOTTOM else Dock.END
            else -> dock
        }
    private val sideDock = this.dock == Dock.START || this.dock == Dock.END
    val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
    val body = LinearLayout(activity)
    private val footer = LinearLayout(activity)
    private val subtitleView = TextView(activity)
    var onDismiss: (() -> Unit)? = null

    private fun dp(value: Float) = DriftUi.dp(activity, value)

    private fun screenSize(): Pair<Int, Int> {
        val decor = activity.window.decorView
        val metrics = activity.resources.displayMetrics
        val w = decor.width.takeIf { it > 0 } ?: metrics.widthPixels
        val h = decor.height.takeIf { it > 0 } ?: metrics.heightPixels
        return w to h
    }

    init {
        val (_, screenH) = screenSize()
        val root = LinearLayout(activity)
        root.orientation = LinearLayout.VERTICAL
        val radius = dp(pal.panelRadiusDp).toFloat()
        root.background = GradientDrawable().apply {
            setColor(pal.panel)
            setStroke(dp(1.5f), pal.line)
            cornerRadii =
                when (this@DriftSheet.dock) {
                    Dock.BOTTOM -> floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
                    Dock.TOP -> floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius)
                    Dock.END -> floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
                    else -> floatArrayOf(0f, 0f, radius, radius, radius, radius, 0f, 0f)
                }
        }

        // Header: title, an optional line under it, and a close button.
        val header = LinearLayout(activity)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(20f), dp(if (this.dock == Dock.BOTTOM) 10f else 16f), dp(8f), dp(12f))
        val titles = LinearLayout(activity)
        titles.orientation = LinearLayout.VERTICAL
        titles.addView(
            TextView(activity).apply {
                text = T(title)
                setTextColor(pal.text)
                textSize = 21f
                typeface = Typeface.DEFAULT_BOLD
            },
        )
        subtitleView.setTextColor(pal.muted)
        subtitleView.textSize = 14f
        subtitleView.visibility = View.GONE
        titles.addView(subtitleView)
        setSubtitle(subtitle)
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            TextView(activity).apply {
                text = "✕"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(pal.muted)
                background = pal.pressable(activity, pal.row, 22f)
                contentDescription = "Close"
                setOnClickListener { dismiss() }
            },
            LinearLayout.LayoutParams(dp(44f), dp(44f)),
        )

        if (this.dock == Dock.BOTTOM) {
            // The grab handle a bottom sheet usually has, so it reads as one.
            root.addView(
                View(activity).apply {
                    background = GradientDrawable().apply {
                        setColor(pal.line)
                        cornerRadius = dp(3f).toFloat()
                    }
                },
                LinearLayout.LayoutParams(dp(40f), dp(5f)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(10f)
                },
            )
        }
        root.addView(header)
        root.addView(View(activity).apply { setBackgroundColor(pal.line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1.5f)))

        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(16f), dp(12f), dp(16f), dp(8f))
        val scroll = CappedScrollView(activity, if (sideDock) screenH else (screenH * 0.62f).toInt())
        scroll.isFillViewport = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(
            scroll,
            if (sideDock) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            },
        )

        footer.orientation = LinearLayout.HORIZONTAL
        footer.setPadding(dp(16f), dp(10f), dp(16f), dp(if (this.dock == Dock.BOTTOM) 22f else 16f))
        footer.visibility = View.GONE
        root.addView(footer)

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (sideDock) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener { onDismiss?.invoke() }
    }

    fun setSubtitle(text: String?) {
        subtitleView.text = text?.let { T(it) } ?: ""
        subtitleView.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun addRow(view: View) {
        body.addView(
            view,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6f)
            },
        )
    }

    private fun rowBase(): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(58f)
            setPadding(dp(16f), dp(10f), dp(14f), dp(10f))
            background = pal.pressable(activity, pal.row)
        }

    private fun labelColumn(label: String, detail: String?): LinearLayout {
        val column = LinearLayout(activity)
        column.orientation = LinearLayout.VERTICAL
        column.addView(
            TextView(activity).apply {
                text = T(label)
                setTextColor(pal.text)
                textSize = 16f
            },
        )
        if (!detail.isNullOrEmpty()) {
            column.addView(
                TextView(activity).apply {
                    text = T(detail)
                    setTextColor(pal.muted)
                    textSize = 13f
                    setPadding(0, dp(2f), 0, 0)
                },
            )
        }
        return column
    }

    fun section(text: String) {
        body.addView(
            TextView(activity).apply {
                this.text = T(text).uppercase()
                setTextColor(pal.focus)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
                setPadding(dp(4f), dp(if (body.childCount == 0) 2f else 14f), dp(4f), dp(8f))
            },
        )
    }

    fun note(text: String): TextView =
        TextView(activity).apply {
            this.text = T(text)
            setTextColor(pal.muted)
            textSize = 14f
            setLineSpacing(0f, 1.15f)
            setPadding(dp(4f), dp(2f), dp(4f), dp(10f))
            body.addView(this)
        }

    fun toggle(label: String, detail: String? = null, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = rowBase()
        row.addView(labelColumn(label, detail), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val pill = PillSwitch(activity)
        pill.palette = pal
        pill.isOn = checked
        row.addView(pill, LinearLayout.LayoutParams(dp(52f), dp(30f)).apply { marginStart = dp(12f) })
        row.setOnClickListener {
            pill.isOn = !pill.isOn
            onChange(pill.isOn)
        }
        addRow(row)
        return row
    }

    /** A row that does something when tapped, with a trailing chevron (or [trailing] text). */
    fun action(label: String, detail: String? = null, trailing: View? = null, onClick: () -> Unit): LinearLayout {
        val row = rowBase()
        row.addView(labelColumn(label, detail), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            trailing ?: TextView(activity).apply {
                text = "›"
                textSize = 24f
                setTextColor(pal.muted)
                setPadding(dp(12f), 0, dp(2f), dp(2f))
            },
        )
        row.setOnClickListener { onClick() }
        addRow(row)
        return row
    }

    /** A label with a row of pills under it, one of which is picked. */
    fun choice(label: String, detail: String? = null, options: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
        val card = LinearLayout(activity)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(16f), dp(12f), dp(12f), dp(12f))
        card.background = GradientDrawable().apply {
            setColor(pal.row)
            cornerRadius = dp(pal.radiusDp).toFloat()
        }
        card.addView(labelColumn(label, detail))
        val pills = LinearLayout(activity)
        pills.orientation = LinearLayout.HORIZONTAL
        val pillViews = ArrayList<TextView>()
        fun paint(current: Int) {
            pillViews.forEachIndexed { index, view ->
                val on = index == current
                view.background = pal.pressable(activity, if (on) pal.accent else pal.rowStrong, 10f)
                view.setTextColor(if (on) pal.accentText else pal.text)
            }
        }
        options.forEachIndexed { index, option ->
            val view = TextView(activity).apply {
                text = T(option)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                isSingleLine = true
                minHeight = dp(42f)
                setPadding(dp(6f), 0, dp(6f), 0)
                setOnClickListener {
                    paint(index)
                    onSelect(index)
                }
            }
            pillViews.add(view)
            pills.addView(
                view,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = dp(6f)
                },
            )
        }
        paint(selected)
        card.addView(pills, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })
        addRow(card)
        return card
    }

    /** A label with its value on the right and a green slider under it. [steps] is the number of
     * positions from [min] to [max]. */
    fun slider(
        label: String,
        min: Float,
        max: Float,
        value: Float,
        steps: Int = 100,
        format: (Float) -> String,
        onChange: (Float) -> Unit,
    ): SeekBar {
        val (card, seek) = buildSlider(label, min, max, value, steps, format, onChange)
        addRow(card)
        return seek
    }

    /** [slider] without adding it, for a caller that places the card itself. */
    fun buildSlider(
        label: String,
        min: Float,
        max: Float,
        value: Float,
        steps: Int = 100,
        format: (Float) -> String,
        onChange: (Float) -> Unit,
    ): Pair<View, SeekBar> {
        val card = LinearLayout(activity)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(16f), dp(12f), dp(8f), dp(6f))
        card.background = GradientDrawable().apply {
            setColor(pal.row)
            cornerRadius = dp(pal.radiusDp).toFloat()
        }
        val top = LinearLayout(activity)
        top.orientation = LinearLayout.HORIZONTAL
        top.addView(
            TextView(activity).apply {
                text = label
                setTextColor(pal.text)
                textSize = 16f
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val valueView = TextView(activity).apply {
            text = format(value)
            setTextColor(pal.text)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, dp(8f), 0)
        }
        top.addView(valueView)
        card.addView(top)
        val seek = SeekBar(activity)
        seek.max = steps
        seek.progress = (((value - min) / (max - min)) * steps).toInt().coerceIn(0, steps)
        seek.progressTintList = ColorStateList.valueOf(pal.accent)
        seek.progressBackgroundTintList = ColorStateList.valueOf(pal.trackOff)
        seek.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        seek.setPadding(dp(8f), dp(12f), dp(16f), dp(12f))
        seek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = min + (progress.toFloat() / steps) * (max - min)
                    valueView.text = format(v)
                    onChange(v)
                }

                override fun onStartTrackingTouch(bar: SeekBar) {}

                override fun onStopTrackingTouch(bar: SeekBar) {}
            },
        )
        card.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return card to seek
    }

    fun textField(hint: String, initial: String = ""): EditText {
        val field = EditText(activity)
        field.hint = T(hint)
        field.setText(initial)
        field.isSingleLine = true
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        pal.styleField(field)
        addRow(field)
        field.setSelection(field.text.length)
        return field
    }

    fun custom(view: View) = addRow(view)

    /** A row with a round check mark, for picking one of several (paint the others with
     * [setPicked] when one is tapped). */
    fun radio(label: String, detail: String? = null, picked: Boolean, onClick: () -> Unit): LinearLayout {
        val mark = RadioMark(activity)
        mark.palette = pal
        mark.picked = picked
        val row = action(label, detail, mark, onClick)
        (mark.layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(12f)
        return row
    }

    /** Repaints a [radio] row's check mark. */
    fun setPicked(row: LinearLayout, picked: Boolean) {
        (row.getChildAt(row.childCount - 1) as? RadioMark)?.picked = picked
    }

    /** A green progress bar (0..1000), indeterminate until told otherwise. */
    fun progress(): android.widget.ProgressBar {
        val bar = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 1000
        bar.isIndeterminate = true
        bar.progressTintList = ColorStateList.valueOf(pal.accent)
        bar.progressBackgroundTintList = ColorStateList.valueOf(pal.trackOff)
        bar.indeterminateTintList = ColorStateList.valueOf(pal.accent)
        addRow(bar)
        return bar
    }

    companion object {
        /** A plain message with one button. */
        fun message(activity: Activity, title: String, text: String, button: String = "OK", onClose: (() -> Unit)? = null): DriftSheet {
            val sheet = DriftSheet(activity, title, compact = true)
            sheet.note(text)
            sheet.button(button, DriftUi.Style.PRIMARY) { sheet.dismiss() }
            sheet.onDismiss = onClose
            sheet.show()
            return sheet
        }

        /** A yes/no question. [danger] paints the confirm button red-text for something that
         * deletes or replaces. */
        fun confirm(
            activity: Activity,
            title: String,
            text: String,
            confirmLabel: String,
            danger: Boolean = false,
            cancelLabel: String = "Cancel",
            onConfirm: () -> Unit,
        ): DriftSheet {
            val sheet = DriftSheet(activity, title, compact = true)
            sheet.note(text)
            sheet.button(cancelLabel) { sheet.dismiss() }
            sheet.button(confirmLabel, if (danger) DriftUi.Style.DANGER else DriftUi.Style.PRIMARY) {
                sheet.dismiss()
                onConfirm()
            }
            sheet.show()
            return sheet
        }
    }

    /** A footer button. Buttons share the footer's width equally. */
    fun button(text: String, style: DriftUi.Style = DriftUi.Style.SECONDARY, onClick: () -> Unit): TextView {
        val view = pal.button(activity, T(text), style, onClick)
        footer.visibility = View.VISIBLE
        footer.addView(
            view,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (footer.childCount > 0) marginStart = dp(8f)
            },
        )
        return view
    }

    /** Empties the sheet so a caller can draw it again with new state, in place. */
    fun clear() {
        body.removeAllViews()
        footer.removeAllViews()
        footer.visibility = View.GONE
    }

    fun dismiss() = dialog.dismiss()

    fun show() {
        val window = dialog.window
        if (window == null) {
            dialog.show()
            return
        }
        val (screenW, _) = screenSize()
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.decorView.setPadding(0, 0, 0, 0)
        window.setDimAmount(0.3f)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            // The system bars are hidden, so the sheet sits on the screen's real edge rather than
            // above where the navigation bar would be (which left a strip of the page under it).
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) fitInsetsTypes = 0
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) window.setDecorFitsSystemWindows(false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // With the decor not fitting the system windows, the keyboard no longer shrinks the dialog on its
            // own: lift the sheet by the keyboard's height so a text box and its buttons stay in view.
            window.decorView.setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(0, 0, 0, insets.getInsets(WindowInsets.Type.ime()).bottom)
                insets
            }
        }
        if (sideDock) {
            val width = (screenW * 0.40f).toInt().coerceIn(min(dp(340f), screenW), (screenW * 0.62f).toInt().coerceAtLeast(dp(340f)))
            window.setLayout(width, ViewGroup.LayoutParams.MATCH_PARENT)
            window.setGravity(if (dock == Dock.START) Gravity.START else Gravity.END)
        } else {
            val width = if (screenW > dp(640f)) dp(560f) else ViewGroup.LayoutParams.MATCH_PARENT
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity((if (dock == Dock.TOP) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL)
            if (dock == Dock.BOTTOM) window.setWindowAnimations(android.R.style.Animation_InputMethod)
        }
        // Show without taking focus first, copy the game's full-screen state, then take focus: the
        // usual way to keep a dialog from bringing the status and navigation bars back.
        window.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        dialog.show()
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = activity.window.decorView.systemUiVisibility
        // WindowInsetsController is Android 11+; on Android 10 the copied flags above do the job.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }
}
