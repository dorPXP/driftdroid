package com.driftdroid.android

import com.driftdroid.android.mii.Mii
import com.driftdroid.android.mii.MiiStudio
import com.driftdroid.android.profile.FriendCode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MiiAndFriendCodeTest {
    // A public Mii as Retro WFC's /api/roomstatus lists it (player "aubrey.").
    private val roomMii = "wA4AYQB1AGIAcgBlAHkALgAAAAAAAEMngAAAAAAAAAAAPXnAmbkGoniLqFgURnyNAIoAioVUAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    @Test
    fun friendCodesMatchRetroWfc() {
        // Profile id / friend code pairs exactly as Retro WFC's API reports them.
        assertEquals("4123-1699-9123", FriendCode.format(138707))
        assertEquals("3137-3137-3137", FriendCode.format(198760529))
        assertEquals("0001-0010-0100", FriendCode.format(100100100))
        assertEquals(138707L, FriendCode.toProfileId("4123 1699 9123"))
        assertEquals(0L, FriendCode.toProfileId("4123-1699-9124"))
        assertEquals(0L, FriendCode.toProfileId("123"))
        assertEquals("", FriendCode.format(0))
        assertNull(FriendCode.normalize("0000-0000-0000"))
    }

    @Test
    fun decodesARealMii() {
        val mii = Mii.fromBase64(roomMii)
        assertNotNull(mii)
        assertEquals("aubrey.", mii!!.name)
        assertTrue(mii.isGirl)
    }

    @Test
    fun editingOneFieldLeavesEveryOtherBitAlone() {
        val original = Mii.fromBase64(roomMii)!!
        val edited = original.copy()
        val before = edited.hairColor
        edited.hairColor = (before + 1) % Mii.HAIR_COLORS.size
        edited.hairColor = before
        assertArrayEquals(original.data, edited.data)

        edited.eyeType = 47
        assertEquals(47, edited.eyeType)
        assertEquals(original.eyeColor, edited.eyeColor)
        assertEquals(original.eyeSpacing, edited.eyeSpacing)
        assertEquals(original.browType, edited.browType)
        edited.eyeType = 99 // clamped to the editor's range
        assertEquals(47, edited.eyeType)
    }

    @Test
    fun namesRoundTrip() {
        val mii = Mii.blank()
        mii.name = "Zoë☆12345678"
        assertEquals("Zoë☆123456", mii.name)
        mii.creatorName = ""
        assertEquals("", mii.creatorName)
    }

    @Test
    fun emptySlotsAreNotMiis() {
        assertNull(Mii.fromBytes(ByteArray(Mii.RECORD_BYTES)))
        assertNull(Mii.fromBytes(ByteArray(Mii.RECORD_BYTES) { 0xFF.toByte() }))
        assertNull(Mii.fromBytes(ByteArray(10)))
    }

    @Test
    fun newMiiIdsAreUniqueAndNormal() {
        val now = 1_760_000_000_000L
        val first = Mii.newMiiId(emptySet(), now)
        val second = Mii.newMiiId(setOf(first), now)
        assertTrue(first != second)
        assertEquals(0b100L, first shr 29)
    }

    @Test
    fun studioDataHasTheRendererShape() {
        val mii = Mii.fromBase64(roomMii)!!
        val encoded = MiiStudio.encode(mii)
        assertEquals(94, encoded.length)
        assertTrue(encoded.startsWith("00"))
        // Written out so the URL can be checked against the live renderer by hand.
        File("build/test-out").mkdirs()
        File("build/test-out/studio_url.txt").writeText(MiiStudio.imageUrl(mii, MiiStudio.Size.LARGE) + "\n")
    }
}
