package com.driftdroid.android

import com.driftdroid.android.mii.Mii
import com.driftdroid.android.mii.MiiDatabase
import com.driftdroid.android.mods.ArchiveExtractor
import com.driftdroid.android.mods.GameBanana
import com.driftdroid.android.online.RetroWfc
import com.driftdroid.android.profile.FriendCode
import com.driftdroid.android.profile.SaveFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Synthetic data only: the shapes of the real responses and files, none of anyone's details. */
class OnlineAndSaveFormatsTest {
    private val sampleMii: Mii =
        Mii.blank().apply {
            name = "Tester"
            miiId = 0x80001234L
            systemId = 0x11223344L
        }

    // --- Retro WFC ---

    @Test
    fun parsesRoomsAndSkipsNullsAndSuspensions() {
        val json =
            """
            {"rooms":[
              {"id":"AAAAAA","type":"anybody","roomType":"Retro Tracks","created":"2026-01-01T10:00:00.1234567Z","rk":"vs_10",
               "averageVR":12000,"isPublic":true,"isJoinable":true,"race":{"num":5,"course":300,"cc":2,"trackName":"Test Track"},
               "players":[
                 {"pid":"1","name":"One","friendCode":"0000-0000-0001","vr":15000,"br":null,"isOpenHost":true,
                  "mii":{"data":"${java.util.Base64.getEncoder().encodeToString(sampleMii.data)}","name":"One"},"connectionMap":["1"]},
                 {"pid":"2","name":"Two","friendCode":"0000-0000-0002","vr":9000,"isSuspended":false,"connectionMap":["1"]},
                 {"pid":"3","name":"Banned","isSuspended":true}
               ]},
              {"id":"BBBBBB","type":"private","roomType":null,"created":null,"isPublic":false,"players":[]},
              {"id":"CCCCCC","type":"anybody","isSuspended":true,"players":[{"pid":"9"}]}
            ],"totalPlayers":2}
            """.trimIndent()
        val status = RetroWfc.parseRooms(json, 42L)
        assertEquals(2, status.rooms.size)
        assertEquals(2, status.totalPlayers)
        val room = status.rooms[0]
        assertEquals("Retro Tracks", room.label)
        assertEquals("Test Track", room.race?.trackName)
        assertEquals(2, room.players.size)
        assertNull(room.players[0].br)
        assertEquals("Tester", room.players[0].mii?.name)
        assertEquals("Private room", status.rooms[1].label)
        assertEquals(1, status.activeRooms.size)
        assertNotNull(room.createdAt)
    }

    @Test
    fun splitsRoomsWhosePlayersCannotSeeEachOther() {
        fun player(id: String, map: List<String>) = RetroWfc.Player(id, id, "", null, null, false, null, map)
        // Players 0-1 see each other, 2-3 see each other, nobody across: two rooms reported as one.
        val merged =
            RetroWfc.Room(
                "R", "anybody", null, null, null,
                listOf(
                    player("a", listOf("0", "1", "0", "0")),
                    player("b", listOf("1", "0", "0", "0")),
                    player("c", listOf("0", "0", "0", "1")),
                    player("d", listOf("0", "0", "1", "0")),
                ),
                null, null, true, true,
            )
        val parts = RetroWfc.splitMergedRoom(merged)
        assertEquals(2, parts.size)
        assertEquals(listOf("a", "b"), parts[0].players.map { it.pid })
        assertEquals(listOf("c", "d"), parts[1].players.map { it.pid })
        // No connection info: left alone.
        val unknown = RetroWfc.Room("R", "anybody", null, null, null, listOf(player("a", emptyList()), player("b", emptyList())), null, null, true, true)
        assertEquals(1, RetroWfc.splitMergedRoom(unknown).size)
    }

    @Test
    fun parsesLeaderboardAndHistory() {
        val top =
            RetroWfc.parseLeaderboard(
                """[{"pid":"5","name":"Fast","friendCode":"1111-2222-3333","vr":200000,"rank":1,"lastSeen":"2026-02-01T00:00:00Z",
                    "vrStats":{"last24Hours":-50,"lastWeek":300,"lastMonth":900},"miiData":null,"vehiclePreference":"bike"}]""",
            )
        assertEquals(1, top.size)
        assertEquals(200000, top[0].vr)
        assertEquals(-50, top[0].vr24h)
        assertNull(top[0].mii)
        assertEquals("bike", top[0].vehiclePreference)
        val history =
            RetroWfc.parseHistory(
                """{"history":[{"date":"2026-02-02T00:00:00Z","totalVR":110},{"date":"2026-02-01T00:00:00Z","totalVR":100},{"date":"bad","totalVR":1}]}""",
            )
        assertEquals(listOf(100, 110), history.map { it.totalVr })
        assertNull(RetroWfc.parseTime("not a date"))
    }

    // --- GameBanana ---

    @Test
    fun parsesGameBananaListingsAndDetails() {
        val list =
            GameBanana.parseList(
                """{"_aMetadata":{"_nRecordCount":2,"_nPerpage":20,"_bIsComplete":true},"_aRecords":[
                  {"_idRow":10,"_sModelName":"Mod","_sName":"Skin","_aSubmitter":{"_sName":"Maker"},"_aRootCategory":{"_sName":"Skins"},
                   "_aPreviewMedia":{"_aImages":[{"_sBaseUrl":"https://images.gamebanana.com/img/ss/mods","_sFile":"a.jpg","_sFile220":"220-90_a.jpg"}]},
                   "_nLikeCount":3,"_nViewCount":40,"_tsDateAdded":1700000000},
                  {"_idRow":11,"_sModelName":"Question","_sName":"Not a mod"}]}""",
            )
        assertTrue(list.complete)
        assertEquals(1, list.mods.size)
        val mod = list.mods[0]
        assertEquals("https://images.gamebanana.com/img/ss/mods/220-90_a.jpg", mod.thumbnailUrl)
        assertEquals(1_700_000_000_000L, mod.addedAt)

        val details =
            GameBanana.parseDetails(
                org.json.JSONObject(
                    """{"_idRow":10,"_sName":"Skin","_sText":"<b>Hi</b>","_sVersion":"1.2","_nDownloadCount":7,
                    "_aSubmitter":{"_sName":"Maker"},"_aCategory":{"_sName":"Mario"},
                    "_aPreviewMedia":{"_aImages":[{"_sBaseUrl":"https://images.gamebanana.com/img/ss/mods","_sFile":"a.jpg","_sFile530":"530-90_a.jpg"},
                                                  {"_sBaseUrl":"https://evil.example.com","_sFile":"x.jpg"}]},
                    "_aFiles":[{"_idRow":99,"_sFile":"skin.rar","_nFilesize":1234,"_sDownloadUrl":"https://gamebanana.com/dl/99"},
                               {"_idRow":98,"_sFile":"readme.txt","_nFilesize":10,"_sDownloadUrl":"https://gamebanana.com/dl/98"}]}""",
                ),
            )
        assertEquals(listOf("https://images.gamebanana.com/img/ss/mods/530-90_a.jpg"), details.screenshots)
        assertEquals(2, details.files.size)
        assertTrue(details.files[0].isArchive)
        assertFalse(details.files[1].isArchive)
        assertEquals("1.2", details.version)
    }

    @Test
    fun downloadsMayOnlyGoToGameBananaOverHttps() {
        val hosts = GameBanana.HOSTS
        assertTrue(com.driftdroid.android.net.Http.isAllowed("https://gamebanana.com/dl/1", hosts))
        assertTrue(com.driftdroid.android.net.Http.isAllowed("https://filecache53.gamebanana.com/mods/x.rar", hosts))
        assertTrue(com.driftdroid.android.net.Http.isAllowed("https://images.gamebanana.com/img/a.jpg", hosts))
        assertFalse(com.driftdroid.android.net.Http.isAllowed("http://files.gamebanana.com/mods/x.rar", hosts))
        assertFalse(com.driftdroid.android.net.Http.isAllowed("https://evilgamebanana.com/x.rar", hosts))
        assertFalse(com.driftdroid.android.net.Http.isAllowed("https://gamebanana.com.evil.example/x.rar", hosts))
    }

    // --- Mii database ---

    private fun emptyDatabase(): ByteArray {
        val db = ByteArray(MiiDatabase.DATABASE_BYTES)
        "RNOD".toByteArray().copyInto(db)
        MiiDatabase.fixCrc(db)
        return db
    }

    @Test
    fun miiDatabaseSlotsKeepItValid() {
        val db = emptyDatabase()
        assertTrue(MiiDatabase.isValid(db))
        assertEquals(0, MiiDatabase.firstFreeSlot(db))
        val withOne = MiiDatabase.withSlot(db, 0, sampleMii)
        assertTrue(MiiDatabase.isValid(withOne))
        assertEquals(1, MiiDatabase.firstFreeSlot(withOne))
        val snapshot = MiiDatabase.Snapshot(withOne)
        assertEquals("Tester", snapshot.byMiiId(0x80001234L)?.mii?.name)
        assertEquals(0x11223344L, snapshot.systemId)
        val cleared = MiiDatabase.withSlot(withOne, 0, null)
        assertTrue(MiiDatabase.isValid(cleared))
        assertEquals(0, MiiDatabase.Snapshot(cleared).slots.size)
        // A flipped byte is caught.
        val broken = withOne.copyOf()
        broken[100] = (broken[100] + 1).toByte()
        assertFalse(MiiDatabase.isValid(broken))
    }

    @Test
    fun adoptedMiisGetAFreshIdAndThisConsole() {
        val snapshot = MiiDatabase.Snapshot(MiiDatabase.withSlot(emptyDatabase(), 0, sampleMii))
        val visitor = sampleMii.copy().apply { systemId = 0x99999999L }
        val adopted = MiiDatabase.adopt(snapshot, visitor)
        assertTrue(adopted.miiId != sampleMii.miiId)
        assertEquals(0x11223344L, adopted.systemId)
        assertEquals("Tester", adopted.name)
    }

    // --- Save file ---

    private fun syntheticSave(profileId: Long): ByteArray {
        val save = ByteArray(0x2BC000)
        "RKSD0006".toByteArray().copyInto(save)
        val base = 0x08
        "RKPD".toByteArray().copyInto(save, base)
        val name = "Racer"
        name.forEachIndexed { i, c ->
            save[base + 0x14 + i * 2] = (c.code shr 8).toByte()
            save[base + 0x15 + i * 2] = c.code.toByte()
        }
        ByteBuffer.wrap(save).putInt(base + 0x28, sampleMii.miiId.toInt())
        ByteBuffer.wrap(save).putInt(base + 0x5C, profileId.toInt())
        ByteBuffer.wrap(save).putShort(base + 0xB0, 7000.toShort())
        ByteBuffer.wrap(save).putInt(base + 0xB4, 12)
        SaveFile.fixChecksum(save)
        return save
    }

    @Test
    fun readsLicensesAndEditsFriendsSafely() {
        val save = syntheticSave(100100100)
        assertNull(SaveData.validate(save))
        val miis = MiiDatabase.Snapshot(MiiDatabase.withSlot(emptyDatabase(), 0, sampleMii))
        val parsed = SaveFile.parse(save, SaveData.Game.MarioKartWii, miis, emptyMap())
        assertEquals(1, parsed.licenses.size)
        val license = parsed.licenses[0]
        assertEquals("Racer", license.name)
        assertEquals("0001-0010-0100", license.friendCode)
        assertEquals(7000, license.vr)
        assertEquals(12L, license.races)
        assertEquals("Tester", license.mii?.name)

        val friendCode = FriendCode.format(138707)
        SaveFile.applyAddFriend(save, SaveData.Game.RetroRewind, 0, friendCode, sampleMii, 143211, null)
        SaveFile.fixChecksum(save)
        assertNull(SaveData.validate(save))
        val withFriend = SaveFile.parse(save, SaveData.Game.RetroRewind, null, emptyMap()).licenses[0].friends
        assertEquals(1, withFriend.size)
        assertEquals(friendCode, withFriend[0].friendCode)
        assertTrue(withFriend[0].pending)
        assertEquals(143200, withFriend[0].vr) // stored in hundreds
        assertEquals(5000, withFriend[0].br)

        try {
            SaveFile.applyAddFriend(save, SaveData.Game.RetroRewind, 0, friendCode, sampleMii, null, null)
            fail("a duplicate friend must be refused")
        } catch (_: java.io.IOException) {
        }
        try {
            SaveFile.applyAddFriend(save, SaveData.Game.RetroRewind, 0, "0001-0010-0100", sampleMii, null, null)
            fail("a license's own code must be refused")
        } catch (_: java.io.IOException) {
        }

        SaveFile.applyRemoveFriend(save, 0, 138707)
        assertEquals(0, SaveFile.parse(save, SaveData.Game.RetroRewind, null, emptyMap()).licenses[0].friends.size)

        val other = sampleMii.copy().apply {
            name = "Renamed"
            miiId = 0x80005678L
        }
        SaveFile.applySetLicenseMii(save, 0, other)
        val renamed = SaveFile.parse(save, SaveData.Game.MarioKartWii, null, emptyMap()).licenses[0]
        assertEquals("Renamed", renamed.name)
        assertEquals(0x80005678L, renamed.miiId)
    }

    @Test
    fun readsRetroRewindRatings() {
        val bytes = ByteArray(8 + 16 * 100)
        val b = ByteBuffer.wrap(bytes)
        b.putInt(0, 0x52525254).putShort(4, 1).putShort(6, 100)
        b.putInt(8, 138707).putFloat(12, 1432.95f).putFloat(16, 50f).putInt(20, 1)
        b.putInt(24, 5).putFloat(28, 1f).putFloat(32, 1f).putInt(36, 0) // no data flag: ignored
        val ratings = SaveFile.RrRatings.parse(bytes)
        assertEquals(1, ratings.size)
        assertEquals(1432.95f, ratings[138707L]!!.first, 0.001f)
        assertTrue(SaveFile.RrRatings.parse(ByteArray(10)).isEmpty())
    }

    // --- Archives ---

    @Test
    fun archiveEntriesCannotEscape() {
        val root = File("build/test-out/archive-root").canonicalFile
        root.mkdirs()
        assertNull(ArchiveExtractor.safeTarget(root, "../evil.szs"))
        assertNull(ArchiveExtractor.safeTarget(root, "a/../../evil.szs"))
        assertNull(ArchiveExtractor.safeTarget(root, ""))
        assertEquals(File(root, "Race/Course/x.szs").canonicalPath, ArchiveExtractor.safeTarget(root, "/Race\\Course/x.szs")!!.canonicalPath)
    }

    @Test
    fun unpacksAZipAndKnowsTheFormats() {
        val dir = File("build/test-out/zip-test")
        dir.deleteRecursively()
        dir.mkdirs()
        val zip = File(dir, "mod.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("My Mod/Race_E.szs"))
            out.write(byteArrayOf(1, 2, 3))
            out.closeEntry()
            out.putNextEntry(ZipEntry("../outside.txt"))
            out.write(byteArrayOf(9))
            out.closeEntry()
        }
        assertEquals(ArchiveExtractor.Format.ZIP, ArchiveExtractor.sniff(zip))
        val dest = File(dir, "out")
        ArchiveExtractor.extract(zip, dest)
        assertTrue(File(dest, "My Mod/Race_E.szs").isFile)
        assertFalse(File(dir, "outside.txt").exists())

        val rar = File(dir, "x.rar").apply { writeBytes(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)) }
        assertEquals(ArchiveExtractor.Format.RAR, ArchiveExtractor.sniff(rar))
        val sevenZip = File(dir, "x.7z").apply { writeBytes(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C, 0, 3)) }
        assertEquals(ArchiveExtractor.Format.SEVEN_ZIP, ArchiveExtractor.sniff(sevenZip))
        val text = File(dir, "x.txt").apply { writeText("hello") }
        assertEquals(ArchiveExtractor.Format.UNKNOWN, ArchiveExtractor.sniff(text))
    }
}
