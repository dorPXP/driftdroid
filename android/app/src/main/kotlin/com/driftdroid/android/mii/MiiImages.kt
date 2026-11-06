package com.driftdroid.android.mii

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.driftdroid.android.ui.Lx
import com.driftdroid.android.ui.RemoteImages

/**
 * A Mii's face in an ImageView: Nintendo's renderer when it's allowed and reachable, and a simple
 * drawing of the Mii's colours ([MiiAvatarDrawable]) while it loads, offline, or when the player
 * has turned the online pictures off in Settings.
 */
internal object MiiImages {
    private const val PREFS = "launcher"
    private const val KEY_ONLINE = "mii_pictures_online"

    private const val KEY_ASKED = "mii_pictures_asked"

    /** Off until the player turns it on: the pictures are drawn by Nintendo's online renderer. */
    fun onlineEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ONLINE, false)

    /** Whether the one-time question about online pictures has been answered. */
    fun asked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false) ||
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_ONLINE)

    fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply()
    }

    fun setOnlineEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ONLINE, enabled).apply()
    }

    fun bind(view: ImageView, mii: Mii?, size: MiiStudio.Size = MiiStudio.Size.SMALL) {
        view.scaleType = ImageView.ScaleType.FIT_CENTER
        val fallback = MiiAvatarDrawable(mii)
        if (mii == null || !onlineEnabled(view.context)) {
            RemoteImages.clear(view)
            view.setImageDrawable(fallback)
            return
        }
        RemoteImages.bind(view, MiiStudio.imageUrl(mii, size), size.px, fallback)
    }
}

/**
 * An offline stand-in for a Mii's picture, drawn from its own data: skin tone, hair colour,
 * eye colour, glasses and facial hair on a disc of its favourite colour. Deliberately simple - it
 * only has to tell Miis apart until the real picture arrives.
 */
internal class MiiAvatarDrawable(mii: Mii?) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val favorite = mii?.let { Mii.FAVORITE_COLORS[it.favoriteColor.coerceIn(0, Mii.FAVORITE_COLORS.size - 1)] } ?: Lx.FAINT
    private val skin = mii?.let { Mii.SKIN_COLORS[it.skinColor.coerceIn(0, Mii.SKIN_COLORS.size - 1)] } ?: Lx.SURFACE_HI
    private val hair = mii?.let { Mii.HAIR_COLORS[it.hairColor.coerceIn(0, Mii.HAIR_COLORS.size - 1)] } ?: Lx.LINE
    private val eyes = mii?.let { Mii.EYE_COLORS[it.eyeColor.coerceIn(0, Mii.EYE_COLORS.size - 1)] } ?: Lx.FAINT
    private val glasses = mii?.let { if (it.glassesType > 0) Mii.GLASSES_COLORS[it.glassesColor.coerceIn(0, Mii.GLASSES_COLORS.size - 1)] else 0 } ?: 0
    private val facialHair = mii?.let { if (it.mustacheType > 0 || it.beardType > 0) Mii.HAIR_COLORS[it.facialHairColor.coerceIn(0, 7)] else 0 } ?: 0
    private val wide = mii?.let { 0.30f + it.faceShape * 0.006f } ?: 0.3f
    private val blank = mii == null
    private val oval = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val size = minOf(b.width(), b.height()).toFloat()
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        paint.style = Paint.Style.FILL
        paint.color = Lx.alpha(favorite, 0.28f)
        canvas.drawCircle(cx, cy, size / 2f, paint)
        if (blank) {
            paint.color = Lx.alpha(Lx.TEXT, 0.25f)
            canvas.drawCircle(cx, cy - size * 0.08f, size * 0.16f, paint)
            oval.set(cx - size * 0.27f, cy + size * 0.12f, cx + size * 0.27f, cy + size * 0.5f)
            canvas.drawOval(oval, paint)
            return
        }
        // Hair behind the face.
        paint.color = hair
        oval.set(cx - size * (wide + 0.04f), cy - size * 0.36f, cx + size * (wide + 0.04f), cy + size * 0.18f)
        canvas.drawOval(oval, paint)
        // Face.
        paint.color = skin
        oval.set(cx - size * wide, cy - size * 0.27f, cx + size * wide, cy + size * 0.36f)
        canvas.drawOval(oval, paint)
        // Fringe.
        paint.color = hair
        oval.set(cx - size * (wide + 0.02f), cy - size * 0.37f, cx + size * (wide + 0.02f), cy - size * 0.06f)
        canvas.drawArc(oval, 180f, 180f, true, paint)
        // Eyes.
        paint.color = eyes
        val eyeY = cy + size * 0.03f
        canvas.drawCircle(cx - size * 0.11f, eyeY, size * 0.035f, paint)
        canvas.drawCircle(cx + size * 0.11f, eyeY, size * 0.035f, paint)
        if (glasses != 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size * 0.025f
            paint.color = glasses
            canvas.drawCircle(cx - size * 0.11f, eyeY, size * 0.075f, paint)
            canvas.drawCircle(cx + size * 0.11f, eyeY, size * 0.075f, paint)
            paint.style = Paint.Style.FILL
        }
        // Mouth, or facial hair.
        if (facialHair != 0) {
            paint.color = facialHair
            oval.set(cx - size * 0.12f, cy + size * 0.14f, cx + size * 0.12f, cy + size * 0.22f)
            canvas.drawOval(oval, paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size * 0.022f
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = 0xFF9C3B2B.toInt()
            oval.set(cx - size * 0.08f, cy + size * 0.1f, cx + size * 0.08f, cy + size * 0.2f)
            canvas.drawArc(oval, 20f, 140f, false, paint)
            paint.style = Paint.Style.FILL
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
