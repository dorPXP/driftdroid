package com.driftdroid.android.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Shader
import android.graphics.LinearGradient
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import com.driftdroid.android.T
import java.text.NumberFormat
import kotlin.math.max
import kotlin.math.tan

/**
 * The launcher's look, "pit lane": dark asphalt neutrals, the kart's Android green as the one "go"
 * colour (play, on, selected), amber for ratings, cyan for links, and condensed italic type for
 * titles and numbers, like a race timing screen. Its signature is the slanted plate - primary
 * buttons, rank numbers and the selected-tab marker are parallelograms - and everything else stays
 * flat: panels without outlines, rows with hairlines, no cards inside cards.
 */
internal object Lx {
    // Asphalt neutrals, darkest to lightest.
    val BG: Int get() = Theme.bg
    val BAR: Int get() = Theme.bar
    val SURFACE_LO: Int get() = Theme.surfaceLo
    val SURFACE: Int get() = Theme.surface
    val SURFACE_HI: Int get() = Theme.surfaceHi
    val LINE: Int get() = Theme.line
    val TEXT: Int get() = Theme.text
    val MUTED: Int get() = Theme.muted
    val FAINT: Int get() = Theme.faint

    // Signals. Green is reserved for "go": the main action, things that are on, the current tab.
    val ACCENT: Int get() = Theme.accent
    val ACCENT_TEXT: Int get() = Theme.accentText
    const val AMBER = 0xFFFFB547.toInt()
    const val CYAN = 0xFF5CC8FF.toInt()
    const val DANGER = 0xFFFF5A5F.toInt()
    const val VIOLET = 0xFFB69CFF.toInt()
    const val GOLD = 0xFFFFC94A.toInt()
    const val SILVER = 0xFFCDD3DC.toInt()
    const val BRONZE = 0xFFE0955A.toInt()

    /** Titles, big numbers and plates: condensed, bold, leaning forward. */
    val DISPLAY: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC)
    /** Labels and tabs: condensed bold, upright. */
    val CONDENSED: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val BOLD: Typeface = Typeface.DEFAULT_BOLD

    /** How far plates lean: the top edge sits this many degrees ahead of the bottom. */
    const val SLANT_DEGREES = 12f

    fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    fun alpha(color: Int, fraction: Float): Int = (color and 0x00FFFFFF) or ((fraction.coerceIn(0f, 1f) * 255).toInt() shl 24)

    /** Phone-sized width: one column, bottom tab bar, controls stacked instead of side by side. */
    fun isCompact(context: Context): Boolean = context.resources.configuration.screenWidthDp < 600

    /** The side margin pages use. */
    fun gutter(context: Context): Int = dp(context, if (isCompact(context)) 16f else 24f)

    // --- Shapes ---

    fun rounded(context: Context, color: Int, radiusDp: Float, strokeColor: Int = 0, strokeDp: Float = 1f): GradientDrawable {
        val look = Theme.look
        val scaled = radiusDp * Theme.cornerScale
        if (look == Look.FLAT) {
            return GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(context, scaled).toFloat()
                if (strokeColor != 0) setStroke(dp(context, strokeDp).coerceAtLeast(1), strokeColor)
            }
        }
        val radius =
            when (look) {
                Look.GLASS -> max(scaled * 1.8f, 14f * Theme.cornerScale)
                Look.AERO -> max(scaled * 2.4f, 18f * Theme.cornerScale)
                else -> max(scaled * 1.3f, 8f * Theme.cornerScale)
            }
        val family = color == SURFACE || color == SURFACE_LO || color == SURFACE_HI || color == BAR
        val drawable =
            LookDrawable(look, color, dp(context, radius).toFloat(), strokeColor, dp(context, strokeDp), family, Theme.panelAlpha, Theme.isLight)
        // A focus outline in the accent colour stays an accent outline in every look.
        if (strokeColor == ACCENT) drawable.setStroke(dp(context, strokeDp).coerceAtLeast(1), ACCENT)
        return drawable
    }

    /** A rounded fill that ripples when pressed and lights up when focused by a gamepad. */
    fun pressable(context: Context, color: Int, radiusDp: Float = 8f, strokeColor: Int = 0): Drawable {
        val shape = rounded(context, color, radiusDp, strokeColor)
        val mask = rounded(context, Color.WHITE, radiusDp)
        return RippleDrawable(ColorStateList.valueOf(0x2EFFFFFF), shape, mask)
    }

    /** A slanted plate that ripples (in the other looks, a glossy rounded plate). */
    fun plate(context: Context, fill: Int, stroke: Int = 0): Drawable {
        if (Theme.look != Look.FLAT) {
            return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(context, fill, 12f, stroke), rounded(context, Color.WHITE, 12f))
        }
        val shape = SlantDrawable(fill, stroke, dp(context, 1.5f).toFloat(), dp(context, 3f).toFloat())
        val mask = SlantDrawable(Color.WHITE, 0, 0f, dp(context, 3f).toFloat())
        return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, mask)
    }

    /** A flat panel: the one surface level content sits on. No outline, small corners. */
    fun panel(context: Context, padDp: Float = 16f, color: Int = SURFACE): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(context, color, 8f)
            val p = dp(context, padDp)
            setPadding(p, p, p, p)
        }

    /** Clips a view (and its children, such as a banner image) to rounded corners. */
    fun clipRounded(view: View, radiusDp: Float) {
        val radius = dp(view.context, radiusDp).toFloat()
        view.clipToOutline = true
        view.outlineProvider =
            object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, radius)
                }
            }
    }

    // --- Text ---

    fun text(
        context: Context,
        value: CharSequence,
        sizeSp: Float,
        color: Int = TEXT,
        face: Typeface = Typeface.DEFAULT,
        maxLines: Int = 0,
    ): TextView =
        TextView(context).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            typeface = face
            includeFontPadding = false
            if (maxLines > 0) {
                this.maxLines = maxLines
                ellipsize = TextUtils.TruncateAt.END
            }
        }

    /** Condensed italic for a title or a big number; figures are tabular so counts don't jitter. */
    fun display(context: Context, value: CharSequence, sizeSp: Float, color: Int = TEXT, maxLines: Int = 1): TextView =
        text(context, value, sizeSp, color, DISPLAY, maxLines).apply {
            fontFeatureSettings = "tnum"
            // Italic glyphs lean past the advance width TextView measures; room so the last one isn't shaved.
            setPadding(0, 0, (sizeSp * 0.12f * resources.displayMetrics.scaledDensity).toInt(), 0)
        }

    fun heading(context: Context, value: String, maxLines: Int = 1) = text(context, value, 16f, TEXT, BOLD, maxLines)

    fun body(context: Context, value: CharSequence, color: Int = MUTED) =
        text(context, value, 14f, color).apply { setLineSpacing(0f, 1.25f) }

    fun caption(context: Context, value: CharSequence, color: Int = MUTED, maxLines: Int = 1) =
        text(context, value, 12.5f, color, Typeface.DEFAULT, maxLines)

    /** A small upright all-caps label. */
    fun label(context: Context, value: String, color: Int = FAINT, sizeSp: Float = 11f) =
        text(context, T(value).uppercase(), sizeSp, color, CONDENSED, 1).apply { letterSpacing = 0.12f }

    /** A section heading: a green slanted tick, then the label. */
    fun section(context: Context, value: String, trailing: View? = null): LinearLayout =
        hbox(context).apply {
            addView(View(context).apply { background = SlantDrawable(ACCENT, 0, 0f, 0f) }, lp(context, 10f, 12f, end = 8f))
            addView(label(context, value, MUTED, 12f), lp(context, 0f, -2f, 1f))
            if (trailing != null) addView(trailing)
        }

    /** A big number with a small label under it. */
    fun stat(context: Context, value: String, label: String, color: Int = TEXT, sizeSp: Float = 24f): LinearLayout =
        vbox(context).apply {
            addView(display(context, value, sizeSp, color))
            addView(label(context, label), lp(context, -2f, -2f, top = 4f))
        }

    // --- Controls ---

    enum class Tone { PRIMARY, SECONDARY, GHOST, DANGER, INFO }

    /** A slanted plate button with an optional leading icon. */
    fun button(
        context: Context,
        label: String,
        tone: Tone = Tone.SECONDARY,
        icon: Icon? = null,
        compact: Boolean = false,
        onClick: () -> Unit,
    ): LinearLayout {
        val (fill, stroke, ink) =
            when (tone) {
                Tone.PRIMARY -> Triple(ACCENT, 0, ACCENT_TEXT)
                Tone.SECONDARY -> Triple(SURFACE_HI, 0, TEXT)
                Tone.GHOST -> Triple(Color.TRANSPARENT, LINE, MUTED)
                Tone.DANGER -> Triple(alpha(DANGER, 0.14f), 0, DANGER)
                Tone.INFO -> Triple(Color.TRANSPARENT, alpha(CYAN, 0.6f), CYAN)
            }
        return hbox(context, Gravity.CENTER).apply {
            minimumHeight = dp(context, if (compact) 36f else 46f)
            val h = dp(context, if (compact) 16f else 22f)
            setPadding(h, 0, h, 0)
            background = plate(context, fill, stroke)
            isClickable = true
            isFocusable = true
            if (icon != null) {
                addView(
                    ImageView(context).apply { setImageDrawable(IconDrawable(icon, ink)) },
                    LinearLayout.LayoutParams(dp(context, 18f), dp(context, 18f)).apply { marginEnd = dp(context, 8f) },
                )
            }
            addView(text(context, T(label).uppercase(), if (compact) 13f else 15f, ink, CONDENSED, 1).apply { letterSpacing = 0.04f })
            setOnClickListener { onClick() }
            contentDescription = T(label)
        }
    }

    fun setEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.4f
    }

    /** A square icon-only button. */
    fun iconButton(
        context: Context,
        icon: Icon,
        description: String,
        tint: Int = TEXT,
        fill: Int = SURFACE_HI,
        sizeDp: Float = 40f,
        onClick: () -> Unit,
    ): ImageView =
        ImageView(context).apply {
            setImageDrawable(IconDrawable(icon, tint))
            val p = dp(context, sizeDp * 0.25f)
            setPadding(p, p, p, p)
            background = pressable(context, fill, 8f)
            contentDescription = T(description)
            layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
            setOnClickListener { onClick() }
        }

    fun iconView(context: Context, icon: Icon, tint: Int, sizeDp: Float): ImageView =
        ImageView(context).apply {
            setImageDrawable(IconDrawable(icon, tint))
            layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
        }

    /** A filter chip: outlined when off, solid white when on (green is kept for "go"). */
    fun chip(context: Context, label: String, selected: Boolean, onClick: () -> Unit): TextView =
        text(context, T(label), 13.5f, TEXT, MEDIUM, 1).apply {
            gravity = Gravity.CENTER
            minHeight = dp(context, 34f)
            setPadding(dp(context, 14f), 0, dp(context, 14f), 0)
            paintChip(this, selected)
            setOnClickListener { onClick() }
        }

    fun paintChip(chip: TextView, selected: Boolean) {
        val context = chip.context
        chip.background = pressable(context, if (selected) TEXT else Color.TRANSPARENT, 6f, if (selected) 0 else LINE)
        chip.setTextColor(if (selected) BG else MUTED)
        chip.isSelected = selected
    }

    /** A short coloured label: state, version, "Pending". */
    fun badge(context: Context, label: String, color: Int): TextView =
        text(context, T(label).uppercase(), 10.5f, color, CONDENSED, 1).apply {
            letterSpacing = 0.08f
            setPadding(dp(context, 7f), dp(context, 3f), dp(context, 7f), dp(context, 3f))
            background = rounded(context, alpha(color, 0.15f), 3f)
        }

    /** A slanted number plate: ranks, "Installed". */
    fun plateBadge(context: Context, label: String, fill: Int, ink: Int, sizeSp: Float = 12f): TextView =
        text(context, label, sizeSp, ink, DISPLAY, 1).apply {
            fontFeatureSettings = "tnum"
            gravity = Gravity.CENTER
            setPadding(dp(context, 9f), dp(context, 3f), dp(context, 10f), dp(context, 3f))
            background = SlantDrawable(fill, 0, 0f, dp(context, 2f).toFloat())
        }

    /** A dot and a line of text: "● Ready", "● Open". */
    fun status(context: Context, value: String, color: Int, textColor: Int = color): LinearLayout =
        hbox(context).apply {
            addView(View(context).apply { background = rounded(context, color, 4f) }, lp(context, 7f, 7f, end = 7f))
            addView(caption(context, value, textColor, 2), lp(context, 0f, -2f, 1f))
        }

    fun divider(context: Context, insetDp: Float = 0f): View =
        View(context).apply {
            setBackgroundColor(LINE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1f)).apply { marginStart = dp(context, insetDp) }
        }

    fun spinner(context: Context, sizeDp: Float = 32f): ProgressBar =
        ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(ACCENT)
            layoutParams = LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
        }

    fun progressBar(context: Context): ProgressBar =
        ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(LINE)
            indeterminateTintList = ColorStateList.valueOf(ACCENT)
        }

    /** The look of every text box: a dark well that outlines green while it has focus. */
    fun fieldBackground(context: Context): Drawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(context, SURFACE_LO, 6f, ACCENT, 1.5f))
            addState(intArrayOf(), rounded(context, SURFACE_LO, 6f, LINE))
        }

    /** A search box. [onSearch] runs on the keyboard's search key; [onChange] on every edit. */
    fun searchField(
        context: Context,
        hint: String,
        onSearch: (String) -> Unit,
        onChange: ((String) -> Unit)? = null,
    ): Pair<LinearLayout, EditText> {
        val box = hbox(context)
        box.background = rounded(context, SURFACE_LO, 6f, LINE)
        box.setPadding(dp(context, 12f), 0, dp(context, 4f), 0)
        box.minimumHeight = dp(context, 42f)
        box.addView(iconView(context, Icon.SEARCH, FAINT, 18f))
        val field = EditText(context)
        field.hint = T(hint)
        field.setHintTextColor(FAINT)
        field.setTextColor(TEXT)
        field.textSize = 14.5f
        field.background = null
        field.isSingleLine = true
        field.inputType = InputType.TYPE_CLASS_TEXT
        field.imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        field.setPadding(dp(context, 10f), dp(context, 8f), dp(context, 4f), dp(context, 8f))
        field.setOnFocusChangeListener { _, focused ->
            box.background = rounded(context, SURFACE_LO, 6f, if (focused) ACCENT else LINE, if (focused) 1.5f else 1f)
        }
        field.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard(v)
                onSearch(field.text.toString())
                true
            } else {
                false
            }
        }
        if (onChange != null) {
            field.addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                    override fun afterTextChanged(s: Editable?) = onChange(s?.toString().orEmpty())
                },
            )
        }
        box.addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(
            iconButton(context, Icon.CLOSE, "Clear", FAINT, Color.TRANSPARENT, 34f) {
                field.setText("")
                onSearch("")
            },
        )
        return box to field
    }

    fun hideKeyboard(view: View) {
        (view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }

    fun textField(context: Context, hint: String, initial: String = "", maxChars: Int = 0): EditText =
        EditText(context).apply {
            this.hint = T(hint)
            setText(initial)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
            if (maxChars > 0) filters = arrayOf(android.text.InputFilter.LengthFilter(maxChars))
            setTextColor(TEXT)
            setHintTextColor(FAINT)
            textSize = 15f
            background = fieldBackground(context)
            setPadding(dp(context, 14f), dp(context, 12f), dp(context, 14f), dp(context, 12f))
        }

    /** An icon, a title and a message, with an optional button: empty lists, errors, offline. */
    fun emptyState(
        context: Context,
        icon: Icon,
        title: String,
        message: String,
        actionLabel: String? = null,
        action: (() -> Unit)? = null,
    ): LinearLayout =
        vbox(context).apply {
            gravity = Gravity.CENTER
            val p = dp(context, 28f)
            setPadding(p, p, p, p)
            addView(iconView(context, icon, FAINT, 40f))
            addView(display(context, T(title), 21f, TEXT, 2).apply { gravity = Gravity.CENTER }, lp(context, -2f, -2f, top = 14f, bottom = 8f))
            addView(body(context, T(message)).apply { gravity = Gravity.CENTER; maxWidth = dp(context, 380f) })
            if (actionLabel != null && action != null) {
                addView(button(context, actionLabel, Tone.PRIMARY, onClick = action), lp(context, -2f, -2f, top = 18f))
            }
        }

    /** A centred spinner with a line under it. */
    fun loading(context: Context, message: String = "Loading..."): LinearLayout =
        vbox(context).apply {
            gravity = Gravity.CENTER
            addView(spinner(context, 34f))
            addView(caption(context, T(message)).apply { setPadding(0, dp(context, 12f), 0, 0) })
        }

    /** A list row: icon, title, detail, then [trailing] or a chevron. Flat; a panel groups them. */
    fun settingsRow(
        context: Context,
        icon: Icon,
        title: String,
        detail: String?,
        color: Int = MUTED,
        trailing: View? = null,
        onClick: (() -> Unit)?,
    ): LinearLayout =
        hbox(context).apply {
            minimumHeight = dp(context, 64f)
            setPadding(dp(context, 16f), dp(context, 10f), dp(context, 12f), dp(context, 10f))
            background = pressable(context, Color.TRANSPARENT, 8f)
            addView(iconView(context, icon, color, 22f))
            val column = vbox(context)
            column.setPadding(dp(context, 16f), 0, dp(context, 8f), 0)
            column.addView(text(context, T(title), 15.5f, TEXT, MEDIUM, 2))
            if (!detail.isNullOrEmpty()) column.addView(caption(context, T(detail), MUTED, 3).apply { setPadding(0, dp(context, 4f), 0, 0); setLineSpacing(0f, 1.15f) })
            addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (trailing != null) {
                addView(trailing)
            } else if (onClick != null) {
                addView(iconView(context, Icon.CHEVRON, FAINT, 20f))
            }
            if (onClick != null) setOnClickListener { onClick() } else isClickable = false
        }

    /** A labelled slider over [min]..[max] showing [format] of its value, with optional words for its two ends. */
    fun slider(
        context: Context,
        label: String,
        min: Int,
        max: Int,
        value: Int,
        format: (Int) -> String = { it.toString() },
        ends: Pair<String, String>? = null,
        onChange: (Int) -> Unit,
    ): LinearLayout =
        vbox(context).apply {
            setPadding(0, dp(context, 8f), 0, dp(context, 4f))
            val top = hbox(context)
            top.addView(text(context, T(label), 14.5f, TEXT, MEDIUM, 1), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val valueView = display(context, format(value), 16f, TEXT)
            top.addView(valueView)
            addView(top)
            val seek = SeekBar(context)
            seek.max = max - min
            seek.progress = (value - min).coerceIn(0, max - min)
            seek.progressTintList = ColorStateList.valueOf(ACCENT)
            seek.progressBackgroundTintList = ColorStateList.valueOf(LINE)
            seek.thumbTintList = ColorStateList.valueOf(TEXT)
            seek.setPadding(dp(context, 6f), dp(context, 12f), dp(context, 6f), dp(context, 8f))
            seek.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        valueView.text = format(min + progress)
                        onChange(min + progress)
                    }

                    override fun onStartTrackingTouch(bar: SeekBar) {}

                    override fun onStopTrackingTouch(bar: SeekBar) {}
                },
            )
            addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (ends != null) {
                val row = hbox(context)
                row.addView(label(context, ends.first), lp(context, 0f, -2f, 1f))
                row.addView(label(context, ends.second))
                addView(row, lp(context, start = 6f, end = 6f))
            }
        }

    /** A label with an on/off switch at the end; the whole row toggles. */
    fun toggleRow(context: Context, label: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout =
        hbox(context).apply {
            minimumHeight = dp(context, 56f)
            setPadding(0, dp(context, 6f), 0, dp(context, 6f))
            val column = vbox(context)
            column.addView(text(context, T(label), 14.5f, TEXT, MEDIUM, 2))
            if (!detail.isNullOrEmpty()) column.addView(caption(context, T(detail), MUTED, 2).apply { setPadding(0, dp(context, 3f), 0, 0) })
            addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val switch = Switch(context)
            switch.isOn = checked
            addView(switch, switchLp(context))
            background = pressable(context, Color.TRANSPARENT, 8f)
            setOnClickListener {
                switch.isOn = !switch.isOn
                onChange(switch.isOn)
            }
        }

    fun switchLp(context: Context): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(dp(context, 46f), dp(context, 26f)).apply { marginStart = dp(context, 12f) }

    // --- Layout helpers ---

    fun hbox(context: Context, gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            this.gravity = gravity
        }

    fun vbox(context: Context): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun space(context: Context, widthDp: Float = 0f, heightDp: Float = 0f): View =
        View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(context, widthDp), dp(context, heightDp)) }

    /** A flexible space that pushes what follows to the end of a row. */
    fun spring(context: Context): View = View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }

    fun lp(
        context: Context,
        widthDp: Float = -1f,
        heightDp: Float = -2f,
        weight: Float = 0f,
        start: Float = 0f,
        top: Float = 0f,
        end: Float = 0f,
        bottom: Float = 0f,
    ): LinearLayout.LayoutParams {
        fun size(v: Float) =
            when (v) {
                -1f -> ViewGroup.LayoutParams.MATCH_PARENT
                -2f -> ViewGroup.LayoutParams.WRAP_CONTENT
                else -> dp(context, v)
            }
        return LinearLayout.LayoutParams(size(widthDp), size(heightDp), weight).apply {
            marginStart = dp(context, start)
            topMargin = dp(context, top)
            marginEnd = dp(context, end)
            bottomMargin = dp(context, bottom)
        }
    }

    fun match(): FrameLayout.LayoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    // --- Formatting ---

    fun number(value: Long): String = NumberFormat.getIntegerInstance().format(value)

    fun number(value: Int): String = number(value.toLong())

    fun signed(value: Int): String = (if (value > 0) "+" else "") + number(value)

    fun fileSize(bytes: Long): String =
        when {
            bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / (1L shl 30).toDouble())
            bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / (1L shl 20).toDouble())
            bytes >= 1L shl 10 -> String.format("%.0f KB", bytes / (1L shl 10).toDouble())
            else -> "$bytes B"
        }

    /** "just now", "5 min ago", "3 h ago", "2 days ago". */
    fun ago(timeMs: Long?, now: Long = System.currentTimeMillis()): String {
        if (timeMs == null || timeMs <= 0) return ""
        val seconds = max(0L, (now - timeMs) / 1000)
        return when {
            seconds < 60 -> T("just now")
            seconds < 3600 -> T("%s min ago").format(seconds / 60)
            seconds < 86_400 -> T("%s h ago").format(seconds / 3600)
            seconds < 86_400 * 60 -> T("%s days ago").format(seconds / 86_400)
            else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(timeMs))
        }
    }
}

/**
 * A parallelogram leaning forward by [Lx.SLANT_DEGREES]: the launcher's plate shape. The slant is
 * taken out of the bounds' own width, so text padded by about a third of the height clears it.
 */
internal class SlantDrawable(
    private val fill: Int,
    private val stroke: Int,
    private val strokeWidth: Float,
    cornerRadius: Float,
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fill
        style = Paint.Style.FILL
        if (cornerRadius > 0f) pathEffect = CornerPathEffect(cornerRadius)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = stroke
        style = Paint.Style.STROKE
        this.strokeWidth = strokeWidth
        if (cornerRadius > 0f) pathEffect = CornerPathEffect(cornerRadius)
    }
    private val path = Path()

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val inset = if (stroke != 0) strokeWidth / 2f else 0f
        val l = bounds.left + inset
        val r = bounds.right - inset
        val t = bounds.top + inset
        val b = bounds.bottom - inset
        val lean = ((b - t) * tan(Math.toRadians(Lx.SLANT_DEGREES.toDouble())).toFloat()).coerceAtMost((r - l) / 3f)
        path.reset()
        path.moveTo(l + lean, t)
        path.lineTo(r, t)
        path.lineTo(r - lean, b)
        path.lineTo(l, b)
        path.close()
    }

    override fun draw(canvas: Canvas) {
        if (Color.alpha(fill) > 0) canvas.drawPath(path, fillPaint)
        if (stroke != 0) canvas.drawPath(path, strokePaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Text tabs, the picked one in white over a slanted green underline. [fill] spreads them across
 * the width (two or three tabs); otherwise they keep their own width and scroll sideways.
 */
internal class TabStrip(context: Context, labels: List<String>, fill: Boolean, private val onSelect: (Int) -> Unit) {
    private val tabs = ArrayList<TextView>()
    private val scroller: android.widget.HorizontalScrollView?
    val view: View
    var selected = -1
        private set

    init {
        val row = Lx.hbox(context)
        labels.forEachIndexed { i, label ->
            val tab = Lx.text(context, T(label).uppercase(), 13f, Lx.MUTED, Lx.CONDENSED, 1)
            tab.letterSpacing = 0.08f
            tab.gravity = Gravity.CENTER
            val p = Lx.dp(context, 14f)
            tab.setPadding(p, 0, p, 0)
            tab.setOnClickListener {
                if (selected != i) {
                    select(i)
                    onSelect(i)
                }
            }
            tabs += tab
            row.addView(tab, if (fill) Lx.lp(context, 0f, 46f, 1f) else Lx.lp(context, -2f, 46f))
        }
        if (fill) {
            scroller = null
            view = row
        } else {
            scroller = android.widget.HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            }
            view = scroller
        }
    }

    fun select(index: Int) {
        selected = index
        tabs.forEachIndexed { i, tab ->
            val on = i == index
            tab.setTextColor(if (on) Lx.TEXT else Lx.MUTED)
            tab.background = if (on) underline(tab.context) else Lx.pressable(tab.context, Color.TRANSPARENT, 6f)
            tab.isSelected = on
        }
        val tab = tabs.getOrNull(index) ?: return
        scroller?.post { scroller.smoothScrollTo((tab.left - (scroller.width - tab.width) / 2).coerceAtLeast(0), 0) }
    }

    fun setLabel(index: Int, label: String) {
        tabs.getOrNull(index)?.text = label.uppercase()
    }

    private fun underline(context: Context): Drawable {
        val bar = SlantDrawable(Lx.ACCENT, 0, 0f, 0f)
        val layers = android.graphics.drawable.LayerDrawable(arrayOf(Lx.pressable(context, Color.TRANSPARENT, 6f), bar))
        layers.setLayerGravity(1, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
        layers.setLayerHeight(1, Lx.dp(context, 3f))
        layers.setLayerInsetLeft(1, Lx.dp(context, 10f))
        layers.setLayerInsetRight(1, Lx.dp(context, 10f))
        return layers
    }
}

/** The launcher's on/off switch: a squared track that turns green, its knob riding to the end. */
internal class Switch(context: Context) : View(context) {
    var isOn = false
        set(value) {
            field = value
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = Lx.dp(context, 6f).toFloat()
        paint.color = if (isOn) Lx.ACCENT else Lx.LINE
        canvas.drawRoundRect(0f, 0f, w, h, r, r, paint)
        val pad = Lx.dp(context, 3f).toFloat()
        val knob = h - pad * 2
        val x = if (isOn) w - pad - knob else pad
        paint.color = if (isOn) Lx.ACCENT_TEXT else Lx.MUTED
        canvas.drawRoundRect(x, pad, x + knob, pad + knob, r - pad / 2, r - pad / 2, paint)
    }
}

/** A ViewGroup that lays its children out left to right and wraps onto new lines. */
internal class FlowLayout(context: Context, private val gapPx: Int = Lx.dp(context, 8f)) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val unbounded = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
        var x = 0
        var y = 0
        var lineHeight = 0
        var widest = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            measureChild(child, MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST), heightMeasureSpec)
            if (!unbounded && x > 0 && x + child.measuredWidth > maxWidth) {
                x = 0
                y += lineHeight + gapPx
                lineHeight = 0
            }
            x += child.measuredWidth + gapPx
            widest = max(widest, x - gapPx)
            lineHeight = max(lineHeight, child.measuredHeight)
        }
        val width = if (unbounded) widest else maxWidth
        setMeasuredDimension(width + paddingLeft + paddingRight, resolveSize(y + lineHeight + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxWidth = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            if (x > 0 && x + child.measuredWidth > maxWidth) {
                x = 0
                y += lineHeight + gapPx
                lineHeight = 0
            }
            child.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + child.measuredWidth, paddingTop + y + child.measuredHeight)
            x += child.measuredWidth + gapPx
            lineHeight = max(lineHeight, child.measuredHeight)
        }
    }
}

/**
 * Equal cells, as many per row as fit [minCellPx] - the Mii part pickers and colour rows, which
 * sit inside a scrolling column where a RecyclerView grid would fight the scroll.
 */
internal class GridBox(context: Context, private val minCellPx: Int, private val gapPx: Int, private val ratio: Float = 1f) : ViewGroup(context) {
    private var columns = 1
    private var cell = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        columns = max(1, (width + gapPx) / (minCellPx + gapPx))
        cell = (width - gapPx * (columns - 1)) / columns
        val cellHeight = (cell / ratio).toInt()
        val spec = MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY)
        val hSpec = MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY)
        var visible = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            child.measure(spec, hSpec)
            visible++
        }
        val rows = (visible + columns - 1) / columns
        val height = rows * cellHeight + max(0, rows - 1) * gapPx + paddingTop + paddingBottom
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cellHeight = (cell / ratio).toInt()
        var index = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val col = index % columns
            val row = index / columns
            val x = paddingLeft + col * (cell + gapPx)
            val y = paddingTop + row * (cellHeight + gapPx)
            child.layout(x, y, x + cell, y + cellHeight)
            index++
        }
    }
}

/** An ImageView that keeps a fixed width:height ratio, sized from its width. */
internal class AspectImageView(context: Context, private val ratio: Float) : ImageView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, (width / ratio).toInt())
    }
}

/** A FrameLayout that is always as tall as it is wide times [ratio]. */
internal class AspectFrame(context: Context, private val ratio: Float) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((width / ratio).toInt(), MeasureSpec.EXACTLY),
        )
    }
}

/**
 * The launcher's backdrop: flat asphalt with three faint slanted stripes in the top corner - the
 * kart's speed lines, quiet enough to sit behind any page. Static, so it costs nothing.
 */
internal class LauncherBackground(private val context: Context, private val compact: Boolean) : Drawable() {
    private val stripe = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint()
    private val picture = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val placed = android.graphics.RectF()
    private var shader: LinearGradient? = null
    private var shaderKey = 0L

    override fun draw(canvas: Canvas) {
        val b = bounds
        val wallpaper = Theme.wallpaper
        if (wallpaper != null && b.width() > 0 && b.height() > 0) {
            val art = Wallpapers.get(wallpaper, b.width(), b.height())
            val cover = max(b.width().toFloat() / art.width, b.height().toFloat() / art.height)
            val w = art.width * cover
            val h = art.height * cover
            placed.set(b.exactCenterX() - w / 2f, b.exactCenterY() - h / 2f, b.exactCenterX() + w / 2f, b.exactCenterY() + h / 2f)
            canvas.drawBitmap(art, null, placed, picture)
        } else if (Theme.top == Theme.bottom && Theme.presetId == "pit_lane") {
            canvas.drawColor(Lx.BG)
        } else {
            val rad = Math.toRadians(Theme.angle.toDouble())
            val dx = Math.cos(rad).toFloat()
            val dy = Math.sin(rad).toFloat()
            val half = (kotlin.math.abs(dx) * b.width() + kotlin.math.abs(dy) * b.height()) / 2f
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            val key = Theme.top.toLong() * 31 + Theme.bottom + Theme.angle.toLong() * 7 + b.width() * 1000L + b.height()
            if (shader == null || key != shaderKey) {
                shaderKey = key
                shader = LinearGradient(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half, Theme.top, Theme.bottom, Shader.TileMode.CLAMP)
            }
            fill.shader = shader
            canvas.drawRect(b, fill)
        }
        val image = Theme.image(context)
        if (image != null && b.width() > 0 && b.height() > 0) {
            Theme.place(image.width, image.height, b.width().toFloat(), b.height().toFloat(), Theme.framingFor(b.width(), b.height()), placed)
            canvas.save()
            canvas.clipRect(b)
            canvas.drawBitmap(image, null, placed, picture)
            canvas.clipRect(placed)
            canvas.drawColor(Lx.alpha(0xFF000000.toInt(), Theme.dim))
            canvas.restore()
        }
        if (Theme.look != Look.FLAT) return
        val unit = (if (compact) b.width() else b.height()) * 0.09f
        val lean = unit * 2.2f
        for (i in 0 until 3) {
            stripe.color = Lx.alpha(Lx.ACCENT, 0.045f - i * 0.012f)
            val x = b.right - unit * (1.6f + i * 1.25f)
            path.reset()
            path.moveTo(x, b.top.toFloat())
            path.lineTo(x + unit * 0.8f, b.top.toFloat())
            path.lineTo(x + unit * 0.8f - lean, b.top + unit * 5.5f)
            path.lineTo(x - lean, b.top + unit * 5.5f)
            path.close()
            canvas.drawPath(path, stripe)
        }
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
