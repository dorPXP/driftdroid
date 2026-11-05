package com.driftdroid.android.mii

/**
 * One Wii Mii: the 74-byte record the Mii Channel keeps in RFL_DB.dat, that rksys.dat embeds for
 * friends, that .rcd files hold on their own and that Retro WFC hands out base64-encoded.
 *
 * The record itself is the state. Every property reads and writes its own bits in [data], so a
 * Mii that is opened and saved again keeps every bit nothing here knows about (Nintendo left a
 * few unused ones between fields) exactly as it was. Bit layout from WheelWizard's MiiSerializer
 * (github.com/TeamWheelWizard/WheelWizard), which matches wiibrew's Mii data documentation.
 */
internal class Mii(val data: ByteArray) {
    init {
        require(data.size == RECORD_BYTES) { "a Mii is $RECORD_BYTES bytes, not ${data.size}" }
    }

    fun copy(): Mii = Mii(data.copyOf())

    var isGirl: Boolean by flag(Field(0x00, 2, 14, 1))
    var birthMonth: Int by int(Field(0x00, 2, 10, 4), 0, 12)
    var birthDay: Int by int(Field(0x00, 2, 5, 5), 0, 31)
    var favoriteColor: Int by int(Field(0x00, 2, 1, 4), 0, FAVORITE_COLORS.size - 1)
    var isFavorite: Boolean by flag(Field(0x00, 2, 0, 1))

    var name: String
        get() = readName(NAME_OFFSET)
        set(value) = writeName(NAME_OFFSET, value)

    var height: Int
        get() = data[0x16].toInt() and 0xFF
        set(value) {
            data[0x16] = value.coerceIn(0, 127).toByte()
        }

    var weight: Int
        get() = data[0x17].toInt() and 0xFF
        set(value) {
            data[0x17] = value.coerceIn(0, 127).toByte()
        }

    /** The "avatar id" a license points at. Stored unsigned; kept in a Long so it never goes negative. */
    var miiId: Long
        get() = readU32(0x18)
        set(value) = writeU32(0x18, value)

    /** The "client id" of the console that made this Mii. */
    var systemId: Long
        get() = readU32(0x1C)
        set(value) = writeU32(0x1C, value)

    var faceShape: Int by int(Field(0x20, 2, 13, 3), 0, 7)
    var skinColor: Int by int(Field(0x20, 2, 10, 3), 0, SKIN_COLORS.size - 1)
    var facialFeature: Int by int(Field(0x20, 2, 6, 4), 0, 11)
    var mingleOff: Boolean by flag(Field(0x20, 2, 2, 1))

    var hairType: Int by int(Field(0x22, 2, 9, 7), 0, 71)
    var hairColor: Int by int(Field(0x22, 2, 6, 3), 0, HAIR_COLORS.size - 1)
    var hairFlipped: Boolean by flag(Field(0x22, 2, 5, 1))

    var browType: Int by int(Field(0x24, 4, 27, 5), 0, 23)
    var browRotation: Int by int(Field(0x24, 4, 22, 4), 0, 11)
    var browColor: Int by int(Field(0x24, 4, 13, 3), 0, HAIR_COLORS.size - 1)
    var browSize: Int by int(Field(0x24, 4, 9, 4), 0, 8)
    var browVertical: Int by int(Field(0x24, 4, 4, 5), 3, 18)
    var browSpacing: Int by int(Field(0x24, 4, 0, 4), 0, 12)

    var eyeType: Int by int(Field(0x28, 4, 26, 6), 0, 47)
    var eyeRotation: Int by int(Field(0x28, 4, 21, 3), 0, 7)
    var eyeVertical: Int by int(Field(0x28, 4, 16, 5), 0, 18)
    var eyeColor: Int by int(Field(0x28, 4, 13, 3), 0, EYE_COLORS.size - 1)
    var eyeSize: Int by int(Field(0x28, 4, 9, 3), 0, 7)
    var eyeSpacing: Int by int(Field(0x28, 4, 5, 4), 0, 12)

    var noseType: Int by int(Field(0x2C, 2, 12, 4), 0, 11)
    var noseSize: Int by int(Field(0x2C, 2, 8, 4), 0, 8)
    var noseVertical: Int by int(Field(0x2C, 2, 3, 5), 0, 18)

    var lipType: Int by int(Field(0x2E, 2, 11, 5), 0, 23)
    var lipColor: Int by int(Field(0x2E, 2, 9, 2), 0, LIP_COLORS.size - 1)
    var lipSize: Int by int(Field(0x2E, 2, 5, 4), 0, 8)
    var lipVertical: Int by int(Field(0x2E, 2, 0, 5), 0, 18)

    var glassesType: Int by int(Field(0x30, 2, 12, 4), 0, 8)
    var glassesColor: Int by int(Field(0x30, 2, 9, 3), 0, GLASSES_COLORS.size - 1)
    var glassesSize: Int by int(Field(0x30, 2, 5, 3), 0, 7)
    var glassesVertical: Int by int(Field(0x30, 2, 0, 5), 0, 20)

    var mustacheType: Int by int(Field(0x32, 2, 14, 2), 0, 3)
    var beardType: Int by int(Field(0x32, 2, 12, 2), 0, 3)
    var facialHairColor: Int by int(Field(0x32, 2, 9, 3), 0, HAIR_COLORS.size - 1)
    var mustacheSize: Int by int(Field(0x32, 2, 5, 4), 0, 8)
    var mustacheVertical: Int by int(Field(0x32, 2, 0, 5), 0, 16)

    var moleOn: Boolean by flag(Field(0x34, 2, 15, 1))
    var moleSize: Int by int(Field(0x34, 2, 11, 4), 0, 8)
    var moleVertical: Int by int(Field(0x34, 2, 6, 5), 0, 30)
    var moleHorizontal: Int by int(Field(0x34, 2, 1, 5), 0, 16)

    var creatorName: String
        get() = readName(CREATOR_OFFSET)
        set(value) = writeName(CREATOR_OFFSET, value)

    /** Name for display: never blank, so a list row always has something to show. */
    val displayName: String get() = name.ifBlank { "Mii" }

    // --- Bit access ---

    class Field(val offset: Int, val bytes: Int, val shift: Int, val width: Int) {
        val mask: Long get() = (1L shl width) - 1
    }

    fun get(field: Field): Int {
        val word = readWord(field.offset, field.bytes)
        return ((word shr field.shift) and field.mask).toInt()
    }

    fun set(field: Field, value: Int) {
        val word = readWord(field.offset, field.bytes)
        val cleared = word and (field.mask shl field.shift).inv()
        writeWord(field.offset, field.bytes, cleared or ((value.toLong() and field.mask) shl field.shift))
    }

    private fun readWord(offset: Int, bytes: Int): Long {
        var word = 0L
        for (i in 0 until bytes) word = (word shl 8) or (data[offset + i].toLong() and 0xFF)
        return word
    }

    private fun writeWord(offset: Int, bytes: Int, word: Long) {
        for (i in 0 until bytes) data[offset + i] = (word shr (8 * (bytes - 1 - i))).toByte()
    }

    private fun readU32(offset: Int): Long = readWord(offset, 4)

    private fun writeU32(offset: Int, value: Long) = writeWord(offset, 4, value and 0xFFFFFFFFL)

    private fun readName(offset: Int): String {
        val chars = StringBuilder()
        for (i in 0 until NAME_CHARS) {
            val c = ((data[offset + i * 2].toInt() and 0xFF) shl 8) or (data[offset + i * 2 + 1].toInt() and 0xFF)
            if (c == 0) break
            chars.append(c.toChar())
        }
        return chars.toString()
    }

    private fun writeName(offset: Int, value: String) {
        val clipped = value.take(NAME_CHARS)
        for (i in 0 until NAME_CHARS) {
            val c = if (i < clipped.length) clipped[i].code else 0
            data[offset + i * 2] = (c shr 8).toByte()
            data[offset + i * 2 + 1] = c.toByte()
        }
    }

    private fun flag(field: Field) =
        object : kotlin.properties.ReadWriteProperty<Mii, Boolean> {
            override fun getValue(thisRef: Mii, property: kotlin.reflect.KProperty<*>) = get(field) != 0

            override fun setValue(thisRef: Mii, property: kotlin.reflect.KProperty<*>, value: Boolean) =
                set(field, if (value) 1 else 0)
        }

    private fun int(field: Field, min: Int, max: Int) =
        object : kotlin.properties.ReadWriteProperty<Mii, Int> {
            override fun getValue(thisRef: Mii, property: kotlin.reflect.KProperty<*>) = get(field)

            override fun setValue(thisRef: Mii, property: kotlin.reflect.KProperty<*>, value: Int) =
                set(field, value.coerceIn(min, max))
        }

    companion object {
        const val RECORD_BYTES = 74
        const val NAME_CHARS = 10
        private const val NAME_OFFSET = 0x02
        private const val CREATOR_OFFSET = 0x36

        /** A Mii from raw bytes, or null when they are empty (an unused slot) or not a Mii. */
        fun fromBytes(bytes: ByteArray?): Mii? {
            if (bytes == null || bytes.size != RECORD_BYTES) return null
            if (bytes.all { it == 0.toByte() } || bytes.all { it == 0xFF.toByte() }) return null
            return Mii(bytes.copyOf())
        }

        fun fromBase64(text: String?): Mii? =
            if (text.isNullOrBlank()) null else runCatching { fromBytes(java.util.Base64.getDecoder().decode(text.trim())) }.getOrNull()

        /** A plain, centred starting point for a new Mii: every part in a mid-range position. */
        fun blank(): Mii {
            val mii = Mii(ByteArray(RECORD_BYTES))
            mii.name = "New Mii"
            mii.height = 64
            mii.weight = 64
            mii.faceShape = 5
            mii.hairType = 33
            mii.hairColor = 1
            mii.browType = 6
            mii.browSize = 4
            mii.browVertical = 10
            mii.browSpacing = 2
            mii.browColor = 1
            mii.eyeType = 2
            mii.eyeRotation = 4
            mii.eyeVertical = 12
            mii.eyeSize = 4
            mii.eyeSpacing = 2
            mii.noseType = 1
            mii.noseSize = 4
            mii.noseVertical = 9
            mii.lipType = 23
            mii.lipSize = 4
            mii.lipVertical = 13
            mii.glassesSize = 4
            mii.glassesVertical = 10
            mii.mustacheSize = 4
            mii.mustacheVertical = 10
            mii.moleSize = 4
            mii.moleVertical = 20
            mii.moleHorizontal = 2
            mii.favoriteColor = 5
            return mii
        }

        /**
         * A fresh avatar id, the way a Wii makes one: a counter of 4-second ticks since 2006-01-01
         * in the low 29 bits, under the 0b100 "normal Mii" prefix. [taken] avoids handing out an id
         * already in the database when several are made within one tick.
         */
        fun newMiiId(taken: Set<Long>, nowMillis: Long = System.currentTimeMillis()): Long {
            val epoch2006 = 1_136_073_600_000L
            var counter = ((nowMillis - epoch2006) / 4000L).coerceAtLeast(0)
            while (true) {
                val id = (0b100L shl 29) or (counter and 0x1FFFFFFFL)
                if (id !in taken) return id
                counter++
            }
        }

        // Display colours. Mii palettes are fixed indices; these RGB values are the ones
        // WheelWizard's editor uses (MiiColorMappings.cs).
        val FAVORITE_COLORS =
            intArrayOf(
                0xFFFC2114.toInt(), 0xFFFF771B.toInt(), 0xFFFFED21.toInt(), 0xFF8FF01F.toInt(),
                0xFF008232.toInt(), 0xFF0A50B8.toInt(), 0xFF47BAE1.toInt(), 0xFFFF627E.toInt(),
                0xFF8A2AB0.toInt(), 0xFF573E17.toInt(), 0xFFFFFFFA.toInt(), 0xFF181818.toInt(),
            )
        val FAVORITE_COLOR_NAMES =
            listOf("Red", "Orange", "Yellow", "Light green", "Green", "Blue", "Light blue", "Pink", "Purple", "Brown", "White", "Black")
        val SKIN_COLORS =
            intArrayOf(0xFFFFD39D.toInt(), 0xFFFFB963.toInt(), 0xFFDE7B3D.toInt(), 0xFFFFAB80.toInt(), 0xFFC85327.toInt(), 0xFF752E17.toInt())
        val HAIR_COLORS =
            intArrayOf(
                0xFF1A1A1A.toInt(), 0xFF562D1B.toInt(), 0xFF782515.toInt(), 0xFF9D4A20.toInt(),
                0xFF988B8C.toInt(), 0xFF684E1B.toInt(), 0xFFAB6A24.toInt(), 0xFFFFB757.toInt(),
            )
        val EYE_COLORS =
            intArrayOf(0xFF1A1A1A.toInt(), 0xFF6B7280.toInt(), 0xFF96482D.toInt(), 0xFFA59837.toInt(), 0xFF555DC3.toInt(), 0xFF488F64.toInt())
        val LIP_COLORS = intArrayOf(0xFFD8643A.toInt(), 0xFFE0261E.toInt(), 0xFFF0605A.toInt())
        val GLASSES_COLORS =
            intArrayOf(0xFF909090.toInt(), 0xFFCA9366.toInt(), 0xFFFF574D.toInt(), 0xFF7B87BD.toInt(), 0xFFFFAF47.toInt(), 0xFFDCC5BE.toInt())
    }
}
