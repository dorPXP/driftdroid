package com.driftdroid.android.profile

import android.content.Context
import com.driftdroid.android.SaveData
import com.driftdroid.android.mii.Mii
import com.driftdroid.android.mii.MiiDatabase
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * The licenses inside a Mario Kart Wii save (rksys.dat): each one's Mii, friend code, VR/BR,
 * race counts and friend roster, and the few edits the Profile page makes to them (add or remove a
 * friend, give a license another Mii).
 *
 * Offsets from wiki.tockdom.com/wiki/Rksys.dat by way of WheelWizard's GameLicenseService.cs.
 * Every write re-validates the file, fixes its CRC-32 (at 0x27FFC, over everything before it),
 * keeps the previous save as rksys.dat.bak and swaps the new one in, so a failure can never leave
 * a half-written save behind.
 */
internal object SaveFile {
    private const val SAVE_BYTES = 0x2BC000
    private const val LICENSE_COUNT = 4
    private const val LICENSE_OFFSET = 0x08
    private const val LICENSE_BYTES = 0x8CC0
    private const val CHECKSUM_OFFSET = 0x27FFC
    private const val FRIEND_COUNT = 30
    private const val FRIEND_OFFSET = 0x56D0
    private const val FRIEND_BYTES = 0x1C0
    private const val FRIEND_EXTRA_OFFSET = 0x8B50
    private const val FRIEND_EXTRA_BYTES = 0x0C
    private const val FRIEND_ADDED = 0x0001
    private const val FRIEND_KEY = 0x10
    private const val DEFAULT_RATING = 5000

    class Friend(
        val slot: Int,
        val profileId: Long,
        val friendCode: String,
        val mii: Mii?,
        val vr: Int,
        val br: Int,
        val wins: Int,
        val losses: Int,
        val pending: Boolean,
    ) {
        val displayName: String get() = mii?.displayName ?: friendCode
    }

    class License(
        val index: Int,
        val name: String,
        val miiId: Long,
        val profileId: Long,
        val friendCode: String,
        val vr: Int,
        val br: Int,
        val races: Long,
        val wins: Long,
        val friends: List<Friend>,
        /** The license's Mii from the Mii file, when it's still there. */
        val mii: Mii?,
    ) {
        val displayName: String get() = name.ifBlank { mii?.displayName ?: "License ${index + 1}" }
        val isOnline: Boolean get() = profileId != 0L
    }

    class Save(val game: SaveData.Game, val licenses: List<License>)

    fun file(context: Context, game: SaveData.Game): File = File(context.filesDir, game.relativePath)

    /** The licenses in [game]'s save, or null when there's no usable save yet. */
    fun load(context: Context, game: SaveData.Game): Save? {
        val bytes = runCatching { file(context, game).takeIf { it.isFile }?.readBytes() }.getOrNull() ?: return null
        if (SaveData.validate(bytes) != null) return null
        val ratings = if (game == SaveData.Game.RetroRewind) RrRatings.load(context) else emptyMap()
        return parse(bytes, game, MiiDatabase.load(context), ratings)
    }

    /** The licenses in an already-validated save. */
    fun parse(bytes: ByteArray, game: SaveData.Game, miis: MiiDatabase.Snapshot?, ratings: Map<Long, Pair<Float, Float>>): Save =
        Save(game, (0 until LICENSE_COUNT).mapNotNull { parseLicense(bytes, it, game, miis, ratings) })

    private fun base(index: Int) = LICENSE_OFFSET + index * LICENSE_BYTES

    private fun parseLicense(
        bytes: ByteArray,
        index: Int,
        game: SaveData.Game,
        miis: MiiDatabase.Snapshot?,
        ratings: Map<Long, Pair<Float, Float>>,
    ): License? {
        val base = base(index)
        if (String(bytes, base, 4, Charsets.US_ASCII) != "RKPD") return null
        val name = utf16(bytes, base + 0x14, 10)
        val miiId = u32(bytes, base + 0x28)
        if (name.isBlank() && miiId == 0L) return null
        val profileId = u32(bytes, base + 0x5C)
        var vr = u16(bytes, base + 0xB0)
        var br = u16(bytes, base + 0xB2)
        // Retro Rewind keeps its real, uncapped rating outside rksys.dat (RRRating.pul) in VR/100.
        ratings[profileId]?.let { (ratingVr, ratingBr) ->
            vr = Math.round(ratingVr * 100)
            br = Math.round(ratingBr * 100)
        }
        val friends =
            (0 until FRIEND_COUNT).mapNotNull { slot -> parseFriend(bytes, base, slot, game) }
        return License(
            index = index,
            name = name,
            miiId = miiId,
            profileId = profileId,
            friendCode = FriendCode.format(profileId),
            vr = vr,
            br = br,
            races = u32(bytes, base + 0xB4),
            wins = u32(bytes, base + 0xDC),
            friends = friends,
            mii = miis?.byMiiId(miiId)?.mii,
        )
    }

    private fun parseFriend(bytes: ByteArray, base: Int, slot: Int, game: SaveData.Game): Friend? {
        val offset = base + FRIEND_OFFSET + slot * FRIEND_BYTES
        val miiBytes = bytes.copyOfRange(offset + 0x1A, offset + 0x1A + Mii.RECORD_BYTES)
        if (miiBytes.all { it == 0.toByte() }) return null
        val profileId = u32(bytes, offset + 0x04)
        val state = u16(bytes, offset + 0x10) and 0x3
        val control = bytes[base + FRIEND_EXTRA_OFFSET + slot * FRIEND_EXTRA_BYTES + 2].toInt() and 0xFF
        return Friend(
            slot = slot,
            profileId = profileId,
            friendCode = FriendCode.format(profileId),
            mii = Mii.fromBytes(miiBytes),
            vr = friendRating(u16(bytes, offset + 0x16), game),
            br = friendRating(u16(bytes, offset + 0x18), game),
            wins = u16(bytes, offset + 0x14),
            losses = u16(bytes, offset + 0x12),
            pending = state == FRIEND_ADDED && (control == FRIEND_KEY || control == 0),
        )
    }

    /** Retro Rewind stores a friend's rating in hundreds (29,367 VR as 293); 5000 is the default. */
    private fun friendRating(stored: Int, game: SaveData.Game): Int =
        if (game != SaveData.Game.RetroRewind || stored == DEFAULT_RATING || stored > 10_000) stored else stored * 100

    private fun storedFriendRating(points: Int, game: SaveData.Game): Int =
        if (game == SaveData.Game.RetroRewind) (points.coerceIn(0, 1_000_000) / 100) else points.coerceIn(0, 9999)

    // --- Edits ---

    /** Adds [friendCode] (with [mii] as its picture) to license [index]'s friend roster. */
    fun addFriend(context: Context, game: SaveData.Game, index: Int, friendCode: String, mii: Mii, vr: Int?, br: Int?) =
        edit(context, game) { applyAddFriend(it, game, index, friendCode, mii, vr, br) }

    fun applyAddFriend(bytes: ByteArray, game: SaveData.Game, index: Int, friendCode: String, mii: Mii, vr: Int?, br: Int?) {
        run {
            val base = base(index)
            if (String(bytes, base, 4, Charsets.US_ASCII) != "RKPD") throw IOException("that license is empty")
            val profileId = FriendCode.toProfileId(friendCode)
            if (profileId == 0L) throw IOException("that isn't a valid friend code")
            if (profileId == u32(bytes, base + 0x5C)) throw IOException("that's this license's own friend code")
            val slots = 0 until FRIEND_COUNT
            if (slots.any { u32(bytes, base + FRIEND_OFFSET + it * FRIEND_BYTES + 0x04) == profileId }) {
                throw IOException("they're already on this license's friend list")
            }
            val slot =
                slots.firstOrNull { s ->
                    val o = base + FRIEND_OFFSET + s * FRIEND_BYTES
                    u32(bytes, o + 0x04) == 0L && (u16(bytes, o + 0x10) and 0x3) == 0 &&
                        (o + 0x1A until o + 0x1A + Mii.RECORD_BYTES).all { bytes[it] == 0.toByte() }
                } ?: throw IOException("the friend list is full (30 friends)")
            val main = base + FRIEND_OFFSET + slot * FRIEND_BYTES
            val extra = base + FRIEND_EXTRA_OFFSET + slot * FRIEND_EXTRA_BYTES
            java.util.Arrays.fill(bytes, main, main + FRIEND_BYTES, 0)
            java.util.Arrays.fill(bytes, extra, extra + FRIEND_EXTRA_BYTES, 0)
            val code = FriendCode.fromProfileId(profileId)
            putU32(bytes, main, code ushr 32)
            putU32(bytes, main + 0x04, profileId)
            putU16(bytes, main + 0x10, FRIEND_ADDED)
            putU16(bytes, main + 0x16, storedFriendRating(vr ?: DEFAULT_RATING, game))
            putU16(bytes, main + 0x18, storedFriendRating(br ?: DEFAULT_RATING, game))
            System.arraycopy(mii.data, 0, bytes, main + 0x1A, Mii.RECORD_BYTES)
            putU16(bytes, main + 0x64, MiiDatabase.crc16(mii.data, 0, Mii.RECORD_BYTES))
            bytes[main + 0x66] = slot.toByte()
            bytes[main + 0x67] = 0
            bytes[main + 0x68] = 0xFF.toByte()
            bytes[main + 0x69] = 0xFF.toByte()
            for (i in 0 until 10) putU32(bytes, main + 0x70 + i * 8, 0xFFFFFFFFL)
            // A one-sided request: the friend shows as pending until they add this license back.
            bytes[extra + 0x2] = FRIEND_KEY.toByte()
            putU32(bytes, extra + 0x4, profileId)
        }
    }

    fun removeFriend(context: Context, game: SaveData.Game, index: Int, profileId: Long) =
        edit(context, game) { applyRemoveFriend(it, index, profileId) }

    fun applyRemoveFriend(bytes: ByteArray, index: Int, profileId: Long) {
        run {
            val base = base(index)
            val slot =
                (0 until FRIEND_COUNT).firstOrNull { u32(bytes, base + FRIEND_OFFSET + it * FRIEND_BYTES + 0x04) == profileId }
                    ?: throw IOException("that friend isn't on this license")
            val main = base + FRIEND_OFFSET + slot * FRIEND_BYTES
            val extra = base + FRIEND_EXTRA_OFFSET + slot * FRIEND_EXTRA_BYTES
            java.util.Arrays.fill(bytes, main, main + FRIEND_BYTES, 0)
            java.util.Arrays.fill(bytes, extra, extra + FRIEND_EXTRA_BYTES, 0)
        }
    }

    /** Points license [index] at [mii] (which must be in the Mii file) and renames it to match. */
    fun setLicenseMii(context: Context, game: SaveData.Game, index: Int, mii: Mii) =
        edit(context, game) { applySetLicenseMii(it, index, mii) }

    fun applySetLicenseMii(bytes: ByteArray, index: Int, mii: Mii) {
        run {
            val base = base(index)
            if (String(bytes, base, 4, Charsets.US_ASCII) != "RKPD") throw IOException("that license is empty")
            putU32(bytes, base + 0x28, mii.miiId)
            putU32(bytes, base + 0x2C, mii.systemId)
            val name = mii.name.take(10)
            for (i in 0 until 10) {
                val c = if (i < name.length) name[i].code else 0
                bytes[base + 0x14 + i * 2] = (c shr 8).toByte()
                bytes[base + 0x15 + i * 2] = c.toByte()
            }
        }
    }

    /** Which licenses (by index) use the Mii with avatar id [miiId], across both saves. */
    fun licensesUsing(context: Context, miiId: Long): List<Pair<SaveData.Game, License>> =
        SaveData.Game.values().flatMap { game ->
            load(context, game)?.licenses?.filter { it.miiId == miiId }?.map { game to it }.orEmpty()
        }

    private fun edit(context: Context, game: SaveData.Game, change: (ByteArray) -> Unit) {
        val dest = file(context, game)
        if (!dest.isFile) throw IOException("there's no ${game.label} save yet - play it once first")
        val bytes = dest.readBytes()
        SaveData.validate(bytes)?.let { throw IOException(it) }
        change(bytes)
        fixChecksum(bytes)
        SaveData.validate(bytes)?.let { throw IOException(it) }
        val dir = dest.parentFile ?: throw IOException("no save folder")
        dest.copyTo(File(dir, "rksys.dat.bak"), overwrite = true)
        val temporary = File(dir, "rksys.dat.saving")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(dest)) {
            temporary.delete()
            throw IOException("couldn't replace the save file")
        }
    }

    fun fixChecksum(bytes: ByteArray) {
        val crc = CRC32()
        crc.update(bytes, 0, CHECKSUM_OFFSET)
        ByteBuffer.wrap(bytes, CHECKSUM_OFFSET, 4).putInt(crc.value.toInt())
    }

    // --- Big-endian helpers ---

    private fun u16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun putU16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v shr 8).toByte()
        b[o + 1] = v.toByte()
    }

    private fun putU32(b: ByteArray, o: Int, v: Long) {
        b[o] = (v shr 24).toByte()
        b[o + 1] = (v shr 16).toByte()
        b[o + 2] = (v shr 8).toByte()
        b[o + 3] = v.toByte()
    }

    private fun utf16(b: ByteArray, o: Int, chars: Int): String {
        val out = StringBuilder()
        for (i in 0 until chars) {
            val c = u16(b, o + i * 2)
            if (c == 0) break
            out.append(c.toChar())
        }
        return out.toString()
    }

    /**
     * Retro Rewind's own rating file, RRRating.pul ("RRRT", version 1, 100 entries of profile id,
     * VR and BR as floats in hundreds, and flags). Format from WheelWizard's RrRatingReader.
     */
    object RrRatings {
        private val CANDIDATES =
            listOf(
                "WiiCompiled/NAND/shared2/Pulsar/RetroRewind6/RRRating.pul",
                "WiiCompiled/riivolution/save/RetroWFC/RRRating.pul",
            )

        fun load(context: Context): Map<Long, Pair<Float, Float>> {
            val bytes = CANDIDATES.asSequence().map { File(context.filesDir, it) }.firstOrNull { it.isFile }
                ?.let { runCatching { it.readBytes() }.getOrNull() } ?: return emptyMap()
            return parse(bytes)
        }

        fun parse(bytes: ByteArray): Map<Long, Pair<Float, Float>> {
            if (bytes.size < 8 + 16 * 100) return emptyMap()
            val buffer = ByteBuffer.wrap(bytes)
            if (buffer.getInt(0) != 0x52525254 || buffer.getShort(4).toInt() != 1) return emptyMap()
            val count = buffer.getShort(6).toInt().coerceIn(0, (bytes.size - 8) / 16)
            val result = HashMap<Long, Pair<Float, Float>>()
            for (i in 0 until count) {
                val o = 8 + i * 16
                val pid = buffer.getInt(o).toLong() and 0xFFFFFFFFL
                val flags = buffer.getInt(o + 12)
                if (pid != 0L && (flags and 1) != 0) result[pid] = buffer.getFloat(o + 4) to buffer.getFloat(o + 8)
            }
            return result
        }
    }
}
