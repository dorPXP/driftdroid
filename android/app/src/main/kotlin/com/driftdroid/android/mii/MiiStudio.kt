package com.driftdroid.android.mii

/**
 * Pictures of Miis from Nintendo's public Mii renderer (studio.mii.nintendo.com), the same one
 * WheelWizard used for its Mii faces before it grew its own 3D renderer.
 *
 * The renderer takes the Mii in "studio" form: 46 bytes in a different order and with different
 * colour numbering from the Wii record, then lightly obfuscated into hex. The mapping is a port of
 * WheelWizard's MiiStudioDataSerializer.GenerateStudioDataArray (Wii branch).
 */
internal object MiiStudio {
    const val HOST = "studio.mii.nintendo.com"

    enum class Size(val px: Int) { SMALL(128), LARGE(270), XL(512) }

    fun imageUrl(mii: Mii, size: Size = Size.SMALL, body: Boolean = false): String =
        "https://$HOST/miis/image.png?data=${encode(mii)}" +
            "&type=${if (body) "all_body" else "face"}&expression=normal&width=${size.px}&bgColor=FFFFFF00"

    /** The renderer's `data=` value for [mii]. */
    fun encode(mii: Mii): String {
        val studio = studioBytes(mii)
        val out = StringBuilder(2 + studio.size * 2).append("00")
        var previous = 0
        for (b in studio) {
            val value = (7 + ((b.toInt() and 0xFF) xor previous)) and 0xFF
            previous = value
            out.append(HEX[value shr 4]).append(HEX[value and 0xF])
        }
        return out.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
    private val MAKEUP = intArrayOf(0, 1, 6, 9, 0, 0, 0, 0, 0, 10, 0, 0)
    private val WRINKLES = intArrayOf(0, 0, 0, 0, 5, 2, 3, 7, 8, 0, 9, 11)

    fun studioBytes(mii: Mii): ByteArray {
        val s = IntArray(46)
        s[0x16] = if (mii.isGirl) 1 else 0
        s[0x15] = mii.favoriteColor
        s[0x1E] = mii.height
        s[0x02] = mii.weight

        s[0x13] = mii.faceShape
        s[0x11] = mii.skinColor
        val feature = mii.facialFeature
        s[0x14] = WRINKLES.getOrElse(feature) { 0 }
        s[0x12] = MAKEUP.getOrElse(feature) { 0 }

        s[0x1D] = mii.hairType
        s[0x1B] = hairColor(mii.hairColor)
        s[0x1C] = if (mii.hairFlipped) 1 else 0

        s[0x0E] = mii.browType
        s[0x0C] = mii.browRotation
        s[0x0B] = hairColor(mii.browColor)
        s[0x0D] = mii.browSize
        s[0x0A] = 3
        s[0x10] = mii.browVertical
        s[0x0F] = mii.browSpacing

        s[0x07] = mii.eyeType
        s[0x05] = mii.eyeRotation
        s[0x09] = mii.eyeVertical
        s[0x04] = mii.eyeColor + 8
        s[0x06] = mii.eyeSize
        s[0x03] = 3
        s[0x08] = mii.eyeSpacing

        s[0x2C] = mii.noseType
        s[0x2B] = mii.noseSize
        s[0x2D] = mii.noseVertical

        s[0x26] = mii.lipType
        s[0x24] = mii.lipColor + 19
        s[0x25] = mii.lipSize
        s[0x23] = 3
        s[0x27] = mii.lipVertical

        s[0x29] = mii.mustacheType
        s[0x01] = mii.beardType
        s[0x00] = hairColor(mii.facialHairColor)
        s[0x28] = mii.mustacheSize
        s[0x2A] = mii.mustacheVertical

        s[0x19] = mii.glassesType
        s[0x17] =
            when (val color = mii.glassesColor) {
                0 -> 8
                in 1..5 -> color + 13
                else -> 0
            }
        s[0x18] = mii.glassesSize
        s[0x1A] = mii.glassesVertical

        s[0x20] = if (mii.moleOn) 1 else 0
        s[0x1F] = mii.moleSize
        s[0x22] = mii.moleVertical
        s[0x21] = mii.moleHorizontal
        return ByteArray(s.size) { s[it].toByte() }
    }

    /** Wii hair colour 0 (black) is studio colour 8; the rest keep their number. */
    private fun hairColor(wii: Int): Int = if (wii == 0) 8 else wii
}
