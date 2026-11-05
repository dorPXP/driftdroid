package com.driftdroid.android.online

import com.driftdroid.android.mii.Mii
import com.driftdroid.android.net.Http
import org.json.JSONArray
import org.json.JSONObject

/**
 * Retro WFC's public API (rwfc.net), the online service Retro Rewind plays on: who is racing in
 * which room right now, and the VR leaderboard. The same endpoints WheelWizard's Rooms and
 * Leaderboard pages read (its IRwfcApi.cs).
 *
 * Read-only. Nothing here talks to the game servers themselves or can join a room; that only
 * happens inside the game.
 */
internal object RetroWfc {
    const val HOST = "rwfc.net"
    private val HOSTS = setOf(HOST)
    private const val API = "https://$HOST/api"

    class Player(
        val pid: String,
        val name: String,
        val friendCode: String,
        val vr: Int?,
        val br: Int?,
        val isOpenHost: Boolean,
        val miiData: String?,
        val connectionMap: List<String>,
    ) {
        val mii: Mii? by lazy { Mii.fromBase64(miiData) }
        val displayName: String get() = name.ifBlank { mii?.displayName ?: "Player" }
    }

    class Race(val number: Int, val course: Int, val trackName: String?)

    class Room(
        val id: String,
        val type: String,
        val roomType: String?,
        val createdAt: Long?,
        val rk: String?,
        val players: List<Player>,
        val averageVr: Int?,
        val race: Race?,
        val isPublic: Boolean,
        val isJoinable: Boolean,
    ) {
        /** What the room is, for a person: "Retro Tracks", "Private room"... */
        val label: String
            get() = roomType?.takeIf { it.isNotBlank() } ?: when {
                !isPublic || type.equals("private", ignoreCase = true) -> "Private room"
                type.equals("anybody", ignoreCase = true) -> "Worldwide"
                else -> type.replaceFirstChar { it.uppercase() }
            }
    }

    class RoomStatus(val rooms: List<Room>, val totalPlayers: Int, val fetchedAt: Long) {
        val activeRooms: List<Room> get() = rooms.filter { it.players.isNotEmpty() }
    }

    class Ranked(
        val pid: String,
        val name: String,
        val friendCode: String,
        val vr: Int,
        val rank: Int?,
        val lastSeen: Long?,
        val vr24h: Int,
        val vrWeek: Int,
        val vrMonth: Int,
        val miiData: String?,
        val vehiclePreference: String?,
    ) {
        val mii: Mii? by lazy { Mii.fromBase64(miiData) }
        val displayName: String get() = name.ifBlank { mii?.displayName ?: "Player" }
    }

    class VrPoint(val time: Long, val totalVr: Int)

    // --- Fetching (call off the main thread) ---

    @Volatile var lastRooms: RoomStatus? = null
        private set

    fun fetchRooms(): RoomStatus =
        parseRooms(Http.getText("$API/roomstatus", HOSTS), System.currentTimeMillis()).also { lastRooms = it }

    @Volatile private var cachedTop: Pair<Long, List<Ranked>>? = null

    /** The top [limit] players, kept for 90 seconds (the leaderboard only moves after races). */
    fun fetchTop(limit: Int = 100, force: Boolean = false): List<Ranked> {
        val cached = cachedTop
        if (!force && cached != null && System.currentTimeMillis() - cached.first < 90_000 && cached.second.size >= limit) {
            return cached.second.take(limit)
        }
        val bounded = limit.coerceIn(1, 200)
        val list = parseLeaderboard(Http.getText("$API/leaderboard/top/$bounded", HOSTS))
        cachedTop = System.currentTimeMillis() to list
        return list
    }

    fun fetchPlayer(friendCode: String): Ranked =
        parseRanked(JSONObject(Http.getText("$API/leaderboard/player/${friendCode.trim()}", HOSTS)))

    fun fetchHistory(friendCode: String, days: Int = 7): List<VrPoint> =
        parseHistory(Http.getText("$API/leaderboard/player/${friendCode.trim()}/history?days=$days", HOSTS))

    // --- Parsing (pure, unit-tested) ---

    fun parseRooms(json: String, now: Long): RoomStatus {
        val root = JSONObject(json)
        val rooms = ArrayList<Room>()
        val array = root.optJSONArray("rooms") ?: JSONArray()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            if (o.optBoolean("isSuspended") || o.optBoolean("suspend")) continue
            val players = ArrayList<Player>()
            val playerArray = o.optJSONArray("players") ?: JSONArray()
            for (j in 0 until playerArray.length()) {
                val p = playerArray.optJSONObject(j) ?: continue
                if (p.optBoolean("isSuspended")) continue
                val mii = p.optJSONObject("mii")
                val map = p.optJSONArray("connectionMap")
                players +=
                    Player(
                        pid = p.str("pid") ?: "",
                        name = p.str("name") ?: mii?.str("name") ?: "",
                        friendCode = p.str("friendCode") ?: "",
                        vr = p.int("vr"),
                        br = p.int("br"),
                        isOpenHost = p.optBoolean("isOpenHost"),
                        miiData = mii?.str("data"),
                        connectionMap = if (map == null) emptyList() else List(map.length()) { map.optString(it) },
                    )
            }
            val race = o.optJSONObject("race")
            val base =
                Room(
                    id = o.str("id") ?: "",
                    type = o.str("type") ?: "",
                    roomType = o.str("roomType"),
                    createdAt = parseTime(o.str("created")),
                    rk = o.str("rk"),
                    players = players,
                    averageVr = o.int("averageVR"),
                    race = race?.let { Race(it.optInt("num"), it.optInt("course"), it.str("trackName")) },
                    isPublic = o.optBoolean("isPublic", true),
                    isJoinable = o.optBoolean("isJoinable", false),
                )
            rooms.addAll(splitMergedRoom(base))
        }
        rooms.sortWith(compareByDescending<Room> { it.players.size }.thenBy { it.id })
        val total = root.int("totalPlayers") ?: rooms.sumOf { it.players.size }
        return RoomStatus(rooms, total, now)
    }

    /**
     * The server sometimes reports two groups of players that can't see each other as one room.
     * Their connection maps give it away: split the room into the groups that are connected both
     * ways. A port of WheelWizard's LiveRoomsService.SplitMergedRooms (from kevinvg207's rr-rooms).
     */
    fun splitMergedRoom(room: Room): List<Room> {
        val n = room.players.size
        if (n < 2) return listOf(room)
        val adjacency = Array(n) { HashSet<Int>() }
        var anyEdge = false
        for (i in 0 until n) {
            val map = room.players[i].connectionMap
            if (map.isEmpty()) continue
            val includesSelf = map.size == n
            if (!includesSelf && map.size != n - 1) continue
            for (j in map.indices) {
                if (map[j].firstOrNull()?.let { it == '0' } != false) continue
                if (includesSelf && j == i) continue
                val other = if (includesSelf) j else if (j >= i) j + 1 else j
                if (other in 0 until n) {
                    adjacency[i].add(other)
                    anyEdge = true
                }
            }
        }
        if (!anyEdge) return listOf(room)
        val seen = BooleanArray(n)
        val groups = ArrayList<List<Int>>()
        for (start in 0 until n) {
            if (seen[start]) continue
            val group = ArrayList<Int>()
            val stack = ArrayDeque<Int>().apply { addLast(start) }
            while (stack.isNotEmpty()) {
                val u = stack.removeLast()
                if (seen[u]) continue
                seen[u] = true
                group.add(u)
                for (v in adjacency[u]) if (u in adjacency[v] && !seen[v]) stack.addLast(v)
            }
            groups.add(group.sorted())
        }
        if (groups.size <= 1) return listOf(room)
        return groups.map { group ->
            Room(
                room.id, room.type, room.roomType, room.createdAt, room.rk,
                group.map { room.players[it] }, null, room.race, room.isPublic, room.isJoinable,
            )
        }
    }

    fun parseLeaderboard(json: String): List<Ranked> {
        val array = JSONArray(json)
        return List(array.length()) { parseRanked(array.getJSONObject(it)) }
    }

    fun parseRanked(o: JSONObject): Ranked {
        val stats = o.optJSONObject("vrStats")
        return Ranked(
            pid = o.str("pid") ?: "",
            name = o.str("name") ?: "",
            friendCode = o.str("friendCode") ?: "",
            vr = o.int("vr") ?: 0,
            rank = o.int("rank") ?: o.int("activeRank"),
            lastSeen = parseTime(o.str("lastSeen")),
            vr24h = stats?.int("last24Hours") ?: 0,
            vrWeek = stats?.int("lastWeek") ?: 0,
            vrMonth = stats?.int("lastMonth") ?: 0,
            miiData = o.str("miiData"),
            vehiclePreference = o.str("vehiclePreference"),
        )
    }

    fun parseHistory(json: String): List<VrPoint> {
        val array = JSONObject(json).optJSONArray("history") ?: return emptyList()
        val points = ArrayList<VrPoint>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val time = parseTime(o.str("date")) ?: continue
            points += VrPoint(time, o.int("totalVR") ?: continue)
        }
        return points.sortedBy { it.time }
    }

    /** "2026-09-13T18:56:58.4018284Z" in epoch milliseconds, or null. */
    fun parseTime(text: String?): Long? =
        if (text.isNullOrBlank()) null else runCatching { java.time.Instant.parse(text).toEpochMilli() }.getOrNull()

    // org.json returns the text "null" for a JSON null from optString; these treat it as missing.
    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private fun JSONObject.int(key: String): Int? = if (isNull(key)) null else (opt(key) as? Number)?.toInt()
}
