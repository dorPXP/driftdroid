package com.driftdroid.android.launcher

import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.driftdroid.android.ModePickerActivity
import com.driftdroid.android.T
import com.driftdroid.android.mii.BodyFigure
import com.driftdroid.android.mii.FocusImageView
import com.driftdroid.android.mii.Mii
import com.driftdroid.android.mii.MiiDatabase
import com.driftdroid.android.mii.MiiImages
import com.driftdroid.android.mii.MiiStudio
import com.driftdroid.android.mii.PartFocus
import com.driftdroid.android.mii.PartTile
import com.driftdroid.android.profile.SaveFile
import com.driftdroid.android.ui.DriftSheet
import com.driftdroid.android.ui.GridBox
import com.driftdroid.android.ui.Icon
import com.driftdroid.android.ui.IconDrawable
import com.driftdroid.android.ui.Lx
import com.driftdroid.android.ui.RemoteImages
import com.driftdroid.android.ui.TabStrip
import java.io.IOException
import kotlin.random.Random
import kotlin.reflect.KMutableProperty1

/**
 * Edits one Mii, or makes a new one: every part of the Wii Mii format, grouped like the Mii
 * Channel's own tabs, with a live picture. Each part is picked from pictures of this Mii wearing
 * it - zoomed onto the eyes for eyes, the mouth for mouths - and height and weight show on a full
 * body and on a figure measured against an average Mii. Saving writes that one slot of the Mii
 * file back (with a backup), and renames any license that races as this Mii.
 */
internal class MiiEditorPage(
    host: ModePickerActivity,
    private val slot: Int?,
    private val original: Mii?,
) : Page(host) {
    private val mii: Mii = original?.copy() ?: Mii.blank()
    private var dirty = false
    private var saving = false
    private val handler = Handler(Looper.getMainLooper())
    private val repaint = Runnable { repaint() }

    override val title: String get() = if (original == null) T("New Mii") else T("Edit %s").format(original.displayName)
    override val subtitle: String
        get() = when {
            saving -> T("Saving...")
            dirty -> T("Unsaved changes")
            slot != null -> T("Slot %s of 100").format(slot + 1)
            else -> T("Not saved yet")
        }

    private lateinit var preview: ImageView
    private lateinit var nameView: TextView
    private lateinit var creatorView: TextView
    private lateinit var controls: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var tabStrip: TabStrip

    /** What the open tab needs redrawn after a change: its part pictures, the body views. */
    private val refreshers = ArrayList<() -> Unit>()

    private val tabs: List<Pair<String, () -> Unit>> =
        listOf(
            T("Info") to ::infoTab,
            T("Body") to ::bodyTab,
            T("Face") to ::faceTab,
            T("Hair") to ::hairTab,
            T("Eyebrows") to ::browTab,
            T("Eyes") to ::eyeTab,
            T("Nose") to ::noseTab,
            T("Mouth") to ::mouthTab,
            T("Glasses") to ::glassesTab,
            T("Facial hair") to ::facialHairTab,
            T("Mole") to ::moleTab,
        )

    override fun actions(): List<View> = listOf(Lx.button(context, "Save", Lx.Tone.PRIMARY, Icon.CHECK, compact = true) { save() })

    override fun build(): View {
        preview = ImageView(context)
        nameView = Lx.display(context, "", if (compact) 22f else 24f)
        creatorView = Lx.caption(context, "", Lx.MUTED, 1)
        tabStrip = TabStrip(context, tabs.map { it.first }, fill = false) { selectTab(it) }
        controls = Lx.vbox(context)
        controls.setPadding(gutter, dp(4f), gutter, dp(32f))
        scroll = ScrollView(context)
        scroll.addView(controls, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val root: View
        if (compact) {
            // Phone: the Mii stays in view at the top while the parts scroll under it.
            val column = Lx.vbox(context)
            val head = Lx.hbox(context)
            head.setPadding(gutter, 0, gutter, dp(8f))
            preview.background = Lx.rounded(context, Lx.SURFACE, 8f)
            head.addView(preview, Lx.lp(context, 108f, 108f))
            val names = Lx.vbox(context)
            names.addView(nameView)
            names.addView(creatorView, Lx.lp(context, top = 4f))
            names.addView(Lx.caption(context, T("Tap a picture to pick that part"), Lx.FAINT, 2), Lx.lp(context, top = 10f))
            head.addView(names, Lx.lp(context, 0f, -2f, 1f, start = 16f))
            column.addView(head)
            column.addView(tabStrip.view, Lx.lp(context).apply { marginStart = dp(4f) })
            column.addView(Lx.divider(context))
            column.addView(scroll, Lx.lp(context, -1f, 0f, 1f))
            root = column
        } else {
            val row = Lx.hbox(context, Gravity.TOP)
            val left = Lx.panel(context, 18f)
            left.gravity = Gravity.CENTER_HORIZONTAL
            left.addView(preview, Lx.lp(context, 190f, 190f))
            left.addView(nameView.apply { gravity = Gravity.CENTER }, Lx.lp(context, -2f, -2f, top = 12f))
            left.addView(creatorView, Lx.lp(context, -2f, -2f, top = 4f))
            left.addView(Lx.caption(context, T("Tap a picture to pick that part"), Lx.FAINT, 2).apply { gravity = Gravity.CENTER }, Lx.lp(context, -2f, -2f, top = 14f))
            row.addView(ScrollView(context).apply { addView(left) }, Lx.lp(context, 250f, -2f, start = 24f, end = 4f))
            val right = Lx.vbox(context)
            right.addView(tabStrip.view, Lx.lp(context).apply { marginStart = dp(10f) })
            right.addView(Lx.divider(context))
            right.addView(scroll, Lx.lp(context, -1f, 0f, 1f))
            row.addView(right, Lx.lp(context, 0f, -1f, 1f))
            root = row
        }
        selectTab(0)
        tabStrip.select(0)
        paintPreview()
        return root
    }

    override fun onBack(): Boolean {
        if (!dirty) return false
        leave()
        return true
    }

    override fun onClose() = handler.removeCallbacks(repaint)

    override fun onDataChanged(what: Data) {
        // Mii pictures turned on or off in Settings.
        if (what == Data.MIIS && ::controls.isInitialized) repaint()
    }

    private fun leave() {
        if (!dirty) {
            host.back(force = true)
            return
        }
        DriftSheet.confirm(host, "Discard your changes?", "Your changes to this Mii haven't been saved.", "Discard", danger = true, cancelLabel = "Keep editing") {
            dirty = false
            host.back(force = true)
        }
    }

    private fun selectTab(index: Int) {
        refreshers.clear()
        controls.removeAllViews()
        tabs[index].second()
        scroll.scrollTo(0, 0)
    }

    private fun changed() {
        dirty = true
        nameView.text = mii.displayName
        refreshHeader()
        handler.removeCallbacks(repaint)
        handler.postDelayed(repaint, 350)
    }

    private fun repaint() {
        paintPreview()
        refreshers.forEach { it() }
    }

    private fun paintPreview() {
        nameView.text = mii.displayName
        creatorView.text = mii.creatorName.takeIf { it.isNotBlank() }?.let { T("by %s").format(it) } ?: T("No creator name")
        MiiImages.bind(preview, mii.copy(), MiiStudio.Size.LARGE)
    }

    // --- Control builders ---

    private fun section(label: String) {
        controls.addView(Lx.section(context, label), Lx.lp(context, top = if (controls.childCount == 0) 14f else 26f, bottom = 12f))
    }

    /**
     * A part's shapes as pictures: the Mii wearing each one, cropped to [focus]. [neutral] puts the
     * part back to its standard size and place in those pictures, so moving a slider doesn't make
     * every picture download again - the pictures compare shapes; the sliders place them.
     */
    private fun shapes(
        label: String,
        count: Int,
        prop: KMutableProperty1<Mii, Int>,
        focus: PartFocus,
        noneFirst: Boolean = false,
        neutral: (Mii) -> Unit = {},
    ) {
        section(label)
        val grid = GridBox(context, dp(if (compact) 72f else 84f), dp(8f))
        val tiles = ArrayList<PartTile>()
        for (i in 0 until count) {
            val none = noneFirst && i == 0
            val tile = PartTile(context, if (none) "—" else (if (noneFirst) i else i + 1).toString(), if (none) T("None") else null)
            tile.contentDescription = if (none) T("%s: none").format(T(label)) else T("%s %s").format(T(label), if (noneFirst) i else i + 1)
            tile.setOnClickListener {
                prop.set(mii, i)
                tiles.forEachIndexed { j, t -> t.setPicked(j == i) }
                changed()
            }
            tiles += tile
            grid.addView(tile)
        }
        fun paint() {
            val online = MiiImages.onlineEnabled(context)
            val base = mii.copy().also(neutral)
            val current = prop.get(mii)
            tiles.forEachIndexed { i, tile ->
                val variant = base.copy()
                prop.set(variant, i)
                tile.show(variant, focus, online)
                tile.setPicked(i == current)
            }
        }
        paint()
        refreshers += { paint() }
        controls.addView(grid)
        if (!MiiImages.onlineEnabled(context)) {
            controls.addView(
                Lx.caption(context, T("Turn on Mii pictures in Settings to see each shape instead of its number."), Lx.FAINT, 2),
                Lx.lp(context, top = 8f),
            )
        }
    }

    private fun colors(label: String, palette: IntArray, prop: KMutableProperty1<Mii, Int>, names: List<String>? = null) {
        section(label)
        val grid = GridBox(context, dp(44f), dp(10f))
        val swatches = ArrayList<FrameLayout>()
        fun paint() {
            val current = prop.get(mii)
            swatches.forEachIndexed { i, s ->
                val on = i == current
                s.background = Lx.rounded(context, palette[i], 8f, if (on) Lx.TEXT else Lx.alpha(Lx.TEXT, 0.12f), if (on) 3f else 1f)
                s.getChildAt(0).visibility = if (on) View.VISIBLE else View.GONE
            }
        }
        palette.forEachIndexed { i, color ->
            val swatch = FrameLayout(context)
            val check = ImageView(context)
            // A dark tick on light colours, a light one on dark colours.
            val light = android.graphics.Color.luminance(color) > 0.45f
            check.setImageDrawable(IconDrawable(Icon.CHECK, if (light) Lx.BG else Lx.TEXT))
            swatch.addView(check, FrameLayout.LayoutParams(dp(20f), dp(20f), Gravity.CENTER))
            swatch.contentDescription = names?.getOrNull(i)?.let { T(it) } ?: T("Colour %s").format(i + 1)
            swatch.setOnClickListener {
                prop.set(mii, i)
                paint()
                changed()
            }
            swatches += swatch
            grid.addView(swatch)
        }
        paint()
        controls.addView(grid)
    }

    private fun slider(
        label: String,
        min: Int,
        max: Int,
        prop: KMutableProperty1<Mii, Int>,
        ends: Pair<String, String>? = null,
        format: (Int) -> String = { it.toString() },
        onMove: () -> Unit = {},
    ) {
        controls.addView(
            Lx.slider(context, label, min, max, prop.get(mii), format, ends?.let { T(it.first) to T(it.second) }) {
                prop.set(mii, it)
                onMove()
                changed()
            },
        )
    }

    private fun toggle(label: String, detail: String?, get: () -> Boolean, set: (Boolean) -> Unit) {
        controls.addView(
            Lx.toggleRow(context, label, detail, get()) {
                set(it)
                changed()
            },
        )
    }

    private fun choice(label: String, options: List<String>, get: () -> Int, set: (Int) -> Unit) {
        section(label)
        val row = Lx.hbox(context)
        val chips = ArrayList<TextView>()
        options.forEachIndexed { i, option ->
            val chip = Lx.chip(context, option, get() == i) {
                set(i)
                chips.forEachIndexed { j, c -> Lx.paintChip(c, j == i) }
                changed()
            }
            chips += chip
            row.addView(chip, Lx.lp(context, 0f, 42f, 1f, end = if (i < options.size - 1) 8f else 0f))
        }
        controls.addView(row)
    }

    private fun field(label: String, hint: String, get: () -> String, set: (String) -> Unit) {
        section(label)
        val input = Lx.textField(context, hint, get(), Mii.NAME_CHARS)
        input.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                override fun afterTextChanged(s: Editable?) {
                    val text = s?.toString().orEmpty()
                    if (text != get()) {
                        set(text)
                        changed()
                    }
                }
            },
        )
        controls.addView(input)
    }

    // Words for the ends of the placement sliders. On a Mii a bigger "height" number sits lower.
    private val sizeEnds = "Smaller" to "Bigger"
    private val upDown = "Higher" to "Lower"
    private val spacingEnds = "Closer" to "Further apart"

    // --- Tabs ---

    private fun infoTab() {
        field("Name", "Up to 10 characters", { mii.name }, { mii.name = it })
        field("Made by", "Creator's name", { mii.creatorName }, { mii.creatorName = it })
        choice("Gender", listOf("Boy", "Girl"), { if (mii.isGirl) 1 else 0 }, { mii.isGirl = it == 1 })
        colors("Favorite colour", Mii.FAVORITE_COLORS, Mii::favoriteColor, Mii.FAVORITE_COLOR_NAMES)
        section("Birthday")
        slider("Month", 0, 12, Mii::birthMonth, format = { if (it == 0) T("Not set") else monthName(it) })
        slider("Day", 0, 31, Mii::birthDay, format = { if (it == 0) T("Not set") else it.toString() })
        section("More")
        toggle("Favorite", "Marked as a favorite in the Mii Channel", { mii.isFavorite }, { mii.isFavorite = it })
        toggle("Allow copying and mingling", "Lets this Mii travel to other consoles", { !mii.mingleOff }, { mii.mingleOff = !it })
    }

    private fun monthName(month: Int): String =
        java.text.DateFormatSymbols.getInstance().months.getOrNull(month - 1)?.takeIf { it.isNotBlank() } ?: month.toString()

    /** Height and weight: Nintendo's full-body picture beside a figure measured against an average Mii. */
    private fun bodyTab() {
        section("Build")
        val panel = Lx.panel(context, 12f)
        val pictures = Lx.hbox(context, Gravity.BOTTOM)
        val online = MiiImages.onlineEnabled(context)
        // The renderer leaves a lot of room around a full body; crop in a little so the Mii reads.
        val body = FocusImageView(context)
        body.focus = PartFocus(0.5f, 0.54f, 1.3f)
        val figure = BodyFigure(context)
        figure.set(mii)
        if (online) {
            val column = Lx.vbox(context)
            column.gravity = Gravity.CENTER_HORIZONTAL
            column.addView(body, Lx.lp(context, -1f, 0f, 1f))
            column.addView(Lx.label(context, "Your Mii"), Lx.lp(context, -2f, -2f, top = 8f))
            pictures.addView(column, Lx.lp(context, 0f, -1f, 1f, end = 6f))
        }
        val figureColumn = Lx.vbox(context)
        figureColumn.gravity = Gravity.CENTER_HORIZONTAL
        figureColumn.addView(figure, Lx.lp(context, -1f, 0f, 1f))
        figureColumn.addView(Lx.label(context, "Against average"), Lx.lp(context, -2f, -2f, top = 8f))
        pictures.addView(figureColumn, Lx.lp(context, 0f, -1f, 1f, start = if (online) 6f else 0f))
        panel.addView(pictures, Lx.lp(context, -1f, if (compact) 240f else 280f))
        controls.addView(panel)

        fun paintBody() {
            if (online) RemoteImages.bind(body, MiiStudio.imageUrl(mii.copy(), MiiStudio.Size.LARGE, body = true), dp(240f), null)
        }
        paintBody()
        refreshers += { paintBody() }

        slider("Height", 0, 127, Mii::height, "Short" to "Tall") { figure.set(mii) }
        slider("Weight", 0, 127, Mii::weight, "Slim" to "Heavy") { figure.set(mii) }
        controls.addView(
            Lx.caption(context, T("The dashed outline is an average Mii (64 and 64). Your Mii's shirt is its favorite colour."), Lx.FAINT, 3),
            Lx.lp(context, top = 6f),
        )
    }

    private fun faceTab() {
        shapes("Face shape", 8, Mii::faceShape, PartFocus(0.5f, 0.56f, 1.15f))
        colors("Skin tone", Mii.SKIN_COLORS, Mii::skinColor)
        shapes("Features and makeup", 12, Mii::facialFeature, PartFocus(0.5f, 0.6f, 1.35f), noneFirst = true)
    }

    private fun hairTab() {
        shapes("Hairstyle", 72, Mii::hairType, PartFocus.WHOLE)
        colors("Hair colour", Mii.HAIR_COLORS, Mii::hairColor)
        section("Parting")
        toggle("Flip hairstyle", "Mirrors the parting", { mii.hairFlipped }, { mii.hairFlipped = it })
    }

    private fun browTab() {
        shapes("Eyebrows", 24, Mii::browType, PartFocus(0.5f, 0.45f, 2.8f)) { it.browSize = 4; it.browVertical = 10; it.browSpacing = 2 }
        colors("Colour", Mii.HAIR_COLORS, Mii::browColor)
        section("Placement")
        slider("Size", 0, 8, Mii::browSize, sizeEnds)
        slider("Angle", 0, 11, Mii::browRotation)
        slider("Height", 3, 18, Mii::browVertical, upDown)
        slider("Spacing", 0, 12, Mii::browSpacing, spacingEnds)
    }

    private fun eyeTab() {
        shapes("Eyes", 48, Mii::eyeType, PartFocus(0.5f, 0.505f, 2.8f)) { it.eyeSize = 4; it.eyeVertical = 12; it.eyeSpacing = 2; it.eyeRotation = 4 }
        colors("Colour", Mii.EYE_COLORS, Mii::eyeColor)
        section("Placement")
        slider("Size", 0, 7, Mii::eyeSize, sizeEnds)
        slider("Angle", 0, 7, Mii::eyeRotation)
        slider("Height", 0, 18, Mii::eyeVertical, upDown)
        slider("Spacing", 0, 12, Mii::eyeSpacing, spacingEnds)
    }

    private fun noseTab() {
        shapes("Nose", 12, Mii::noseType, PartFocus(0.5f, 0.575f, 3.0f)) { it.noseSize = 4; it.noseVertical = 9 }
        section("Placement")
        slider("Size", 0, 8, Mii::noseSize, sizeEnds)
        slider("Height", 0, 18, Mii::noseVertical, upDown)
    }

    private fun mouthTab() {
        shapes("Mouth", 24, Mii::lipType, PartFocus(0.5f, 0.69f, 2.8f)) { it.lipSize = 4; it.lipVertical = 13 }
        colors("Colour", Mii.LIP_COLORS, Mii::lipColor)
        section("Placement")
        slider("Size", 0, 8, Mii::lipSize, sizeEnds)
        slider("Height", 0, 18, Mii::lipVertical, upDown)
    }

    private fun glassesTab() {
        shapes("Glasses", 9, Mii::glassesType, PartFocus(0.5f, 0.505f, 1.9f), noneFirst = true) { it.glassesSize = 4; it.glassesVertical = 10 }
        colors("Colour", Mii.GLASSES_COLORS, Mii::glassesColor)
        section("Placement")
        slider("Size", 0, 7, Mii::glassesSize, sizeEnds)
        slider("Height", 0, 20, Mii::glassesVertical, upDown)
    }

    private fun facialHairTab() {
        shapes("Mustache", 4, Mii::mustacheType, PartFocus(0.5f, 0.64f, 2.5f), noneFirst = true) { it.mustacheSize = 4; it.mustacheVertical = 10 }
        shapes("Beard", 4, Mii::beardType, PartFocus(0.5f, 0.68f, 1.3f), noneFirst = true)
        colors("Colour", Mii.HAIR_COLORS, Mii::facialHairColor)
        section("Mustache placement")
        slider("Size", 0, 8, Mii::mustacheSize, sizeEnds)
        slider("Height", 0, 16, Mii::mustacheVertical, upDown)
    }

    private fun moleTab() {
        section("Mole")
        toggle("Show a mole", null, { mii.moleOn }, { mii.moleOn = it })
        slider("Size", 0, 8, Mii::moleSize, sizeEnds)
        slider("Height", 0, 30, Mii::moleVertical, upDown)
        slider("Across", 0, 16, Mii::moleHorizontal)
    }

    // --- Saving ---

    private fun save() {
        if (saving) return
        if (mii.name.isBlank()) {
            host.toast(T("Give the Mii a name first."), Lx.Tone.INFO)
            tabStrip.select(0)
            selectTab(0)
            return
        }
        host.guardGameRunning {
            saving = true
            refreshHeader()
            val result = mii.copy()
            host.work({ write(result) }) { outcome ->
                saving = false
                outcome.fold(
                    onSuccess = {
                        dirty = false
                        host.toast(T("%s saved.").format(result.displayName), Lx.Tone.PRIMARY)
                        host.broadcast(Data.MIIS)
                        host.broadcast(Data.SAVES)
                        host.back(force = true)
                    },
                    onFailure = {
                        refreshHeader()
                        host.toast(T("Couldn't save: %s").format(it.message ?: "unknown error"), Lx.Tone.DANGER)
                    },
                )
            }
        }
    }

    /** Runs off the main thread. */
    private fun write(result: Mii) {
        val snapshot = MiiDatabase.load(context) ?: throw IOException("there's no Mii file yet - start Mario Kart Wii once first")
        if (slot == null || original == null) {
            result.miiId = Mii.newMiiId(snapshot.slots.map { it.mii.miiId }.toSet())
            result.systemId = snapshot.systemId ?: (Random.nextLong(1, 0xFFFFFFFFL))
            val free = MiiDatabase.firstFreeSlot(snapshot.bytes)
            if (free < 0) throw IOException("all 100 Mii slots are in use - delete one first")
            MiiDatabase.save(context, MiiDatabase.withSlot(snapshot.bytes, free, result))
            return
        }
        val current = snapshot.slots.firstOrNull { it.index == slot }
        if (current == null || current.mii.miiId != original.miiId) {
            throw IOException("the Mii file changed while you were editing - reopen this Mii and try again")
        }
        MiiDatabase.save(context, MiiDatabase.withSlot(snapshot.bytes, slot, result))
        // Licenses keep their own copy of the name; keep them matching the Mii.
        if (result.name != original.name) {
            for ((game, license) in SaveFile.licensesUsing(context, result.miiId)) {
                runCatching { SaveFile.setLicenseMii(context, game, license.index, result) }
            }
        }
    }
}
