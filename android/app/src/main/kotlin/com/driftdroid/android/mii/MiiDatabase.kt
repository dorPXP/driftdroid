package com.driftdroid.android.mii

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * The Mii Channel's database, RFL_DB.dat, as the game keeps it in the emulated NAND
 * (filesDir/WiiCompiled/NAND/shared2/menu/FaceLib - ApplicationDataDirectory() / "NAND" on Android).
 * Not to be confused with RFL_Res.dat, the read-only face resource seeded from the disc.
 *
 * Layout confirmed against SuperFromND/rfl_mii_extractor and WheelWizard's MiiRepositoryService:
 * a fixed 779,968-byte file starting "RNOD", then 100 "My Miis" slots of 74 bytes each from offset
 * 4 (an all-zero slot is free), and a CRC-16/CCITT-XMODEM of everything before 0x1F1DE stored
 * big-endian at 0x1F1DE. Every write recomputes that CRC, so the result is a genuinely valid
 * database rather than one only our runtime would accept, and keeps the previous file as
 * RFL_DB.dat.bak.
 */
internal object MiiDatabase {
    const val DATABASE_BYTES = 779_968
    const val SLOT_COUNT = 100
    private const val TABLE_OFFSET = 4
    private const val MAGIC = "RNOD"
    private const val CRC_OFFSET = 0x1F1DE

    class Slot(val index: Int, val mii: Mii)

    /** One read of the file: its bytes, and the Miis in it with their slot numbers. */
    class Snapshot(val bytes: ByteArray) {
        val slots: List<Slot> =
            (0 until SLOT_COUNT).mapNotNull { index -> Mii.fromBytes(record(bytes, index))?.let { Slot(index, it) } }

        fun byMiiId(id: Long): Slot? = slots.firstOrNull { it.mii.miiId == id }

        val freeSlots: Int get() = SLOT_COUNT - slots.size

        /** The console id most Miis here were made with, for new Miis to count as this console's. */
        val systemId: Long?
            get() = slots.map { it.mii.systemId }.filter { it != 0L }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    }

    fun file(context: Context): File = File(context.filesDir, "WiiCompiled/NAND/shared2/menu/FaceLib/RFL_DB.dat")

    fun exists(context: Context): Boolean = file(context).isFile

    /** The current database, or null when there is none yet or it fails validation. */
    fun load(context: Context): Snapshot? {
        val file = file(context)
        if (!file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return if (isValid(bytes)) Snapshot(bytes) else null
    }

    fun isValid(bytes: ByteArray): Boolean {
        if (bytes.size != DATABASE_BYTES) return false
        if (String(bytes, 0, 4, Charsets.US_ASCII) != MAGIC) return false
        val stored = ((bytes[CRC_OFFSET].toInt() and 0xFF) shl 8) or (bytes[CRC_OFFSET + 1].toInt() and 0xFF)
        return stored == crc16(bytes, 0, CRC_OFFSET)
    }

    private fun record(bytes: ByteArray, index: Int): ByteArray {
        val offset = TABLE_OFFSET + index * Mii.RECORD_BYTES
        return bytes.copyOfRange(offset, offset + Mii.RECORD_BYTES)
    }

    /** [bytes] with slot [index] set to [mii] (or cleared for null), CRC fixed. A new array. */
    fun withSlot(bytes: ByteArray, index: Int, mii: Mii?): ByteArray {
        require(index in 0 until SLOT_COUNT)
        val updated = bytes.copyOf()
        val offset = TABLE_OFFSET + index * Mii.RECORD_BYTES
        if (mii == null) {
            java.util.Arrays.fill(updated, offset, offset + Mii.RECORD_BYTES, 0)
        } else {
            System.arraycopy(mii.data, 0, updated, offset, Mii.RECORD_BYTES)
        }
        fixCrc(updated)
        return updated
    }

    fun firstFreeSlot(bytes: ByteArray): Int =
        (0 until SLOT_COUNT).firstOrNull { index ->
            val offset = TABLE_OFFSET + index * Mii.RECORD_BYTES
            (offset until offset + Mii.RECORD_BYTES).all { bytes[it] == 0.toByte() }
        } ?: -1

    /** Writes [bytes] as the new database (validated first), keeping the old one as a backup. */
    fun save(context: Context, bytes: ByteArray) {
        if (!isValid(bytes)) throw IOException("the Mii data failed its own check, so nothing was written")
        val dest = file(context)
        val dir = dest.parentFile ?: throw IOException("no Mii folder")
        dir.mkdirs()
        if (dest.isFile) dest.copyTo(File(dir, "RFL_DB.dat.bak"), overwrite = true)
        val temporary = File(dir, "RFL_DB.dat.saving")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(dest)) {
            temporary.delete()
            throw IOException("couldn't replace the Mii file")
        }
    }

    /** Adds [mii] into the first free slot of the current database. Returns the slot number. */
    fun add(context: Context, mii: Mii): Int {
        val snapshot = load(context) ?: throw IOException("there's no Mii file yet - start Mario Kart Wii once first")
        val slot = firstFreeSlot(snapshot.bytes)
        if (slot < 0) throw IOException("all 100 Mii slots are in use - delete one first")
        save(context, withSlot(snapshot.bytes, slot, mii))
        return slot
    }

    /**
     * Prepares a Mii that came from somewhere else (a file, a friend, Retro WFC) for this console:
     * a fresh avatar id so it can't clash with one already here, and this console's id so the Mii
     * Channel treats it as an editable Mii rather than a foreign one.
     */
    fun adopt(snapshot: Snapshot, mii: Mii): Mii {
        val copy = mii.copy()
        copy.miiId = Mii.newMiiId(snapshot.slots.map { it.mii.miiId }.toSet())
        snapshot.systemId?.let { copy.systemId = it }
        return copy
    }

    fun fixCrc(bytes: ByteArray) {
        val crc = crc16(bytes, 0, CRC_OFFSET)
        bytes[CRC_OFFSET] = (crc shr 8).toByte()
        bytes[CRC_OFFSET + 1] = crc.toByte()
    }

    /** CRC-16/CCITT-XMODEM: polynomial 0x1021, initial 0, no reflection. */
    fun crc16(data: ByteArray, start: Int, length: Int): Int {
        var crc = 0
        for (index in start until start + length) {
            crc = crc xor ((data[index].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }
}
