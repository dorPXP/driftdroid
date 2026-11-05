package com.driftdroid.android.profile

import java.security.MessageDigest

/**
 * Mario Kart Wii friend codes. A friend code is a 32-bit profile id with a 7-bit checksum on top:
 * the first byte of MD5(profile id little-endian + "JCMR"), shifted right by one. Same math as
 * WheelWizard's FriendCode.cs and the game's own DWC library.
 */
internal object FriendCode {
    private val GAME_ID_REVERSED = byteArrayOf('J'.code.toByte(), 'C'.code.toByte(), 'M'.code.toByte(), 'R'.code.toByte())

    /** The friend code number for [profileId], or 0 for profile id 0 (no license). */
    fun fromProfileId(profileId: Long): Long {
        val pid = profileId and 0xFFFFFFFFL
        if (pid == 0L) return 0
        val input = byteArrayOf(pid.toByte(), (pid shr 8).toByte(), (pid shr 16).toByte(), (pid shr 24).toByte()) + GAME_ID_REVERSED
        val checksum = (MessageDigest.getInstance("MD5").digest(input)[0].toInt() and 0xFF) shr 1
        return (checksum.toLong() shl 32) or pid
    }

    /** "1234-5678-9012" for [profileId], or "" when there is no license. */
    fun format(profileId: Long): String = formatNumber(fromProfileId(profileId))

    fun formatNumber(code: Long): String {
        if (code <= 0) return ""
        val digits = code.toString().padStart(12, '0')
        return "${digits.substring(0, 4)}-${digits.substring(4, 8)}-${digits.substring(8, 12)}"
    }

    /**
     * The profile id behind a typed or listed friend code, or 0 if it isn't 12 digits or its
     * checksum doesn't match (a typo, almost always).
     */
    fun toProfileId(friendCode: String?): Long {
        val digits = friendCode?.filter { it.isDigit() } ?: return 0
        if (digits.length != 12) return 0
        val code = digits.toLongOrNull() ?: return 0
        val pid = code and 0xFFFFFFFFL
        if (pid == 0L) return 0
        return if (fromProfileId(pid) == code) pid else 0
    }

    /** The canonical "1234-5678-9012" form of a typed code, or null if it isn't a real one. */
    fun normalize(friendCode: String?): String? {
        val pid = toProfileId(friendCode)
        return if (pid == 0L) null else format(pid)
    }
}
