package pl.btmap.core.track

import org.json.JSONArray
import pl.btmap.core.LiveState
import pl.btmap.core.location.Geo
import pl.btmap.core.model.Rat
import java.io.File

/** One point of a route: where the phone was and the signal of the data SIM's serving cell there. */
data class TrackPoint(
    val timeMillis: Long,
    val lat: Double,
    val lng: Double,
    val rat: Rat?,
    /** RSRP (LTE), SS-RSRP (NR), RSCP (UMTS) or RSSI (GSM); null with no service. */
    val dbm: Int?,
    /** Band label, e.g. "1800". */
    val band: String?,
)

/**
 * The routes the phone travelled, with the signal along the way, kept on the phone only. A point
 * is added after moving [MIN_STEP_M] (or a minute standing), and a pause longer than
 * [TRIP_GAP_MS] starts a new trip.
 */
class TrackRecorder(private val file: File) {
    private val lock = Any()
    private val points = ArrayList<TrackPoint>()
    private var dirty = false

    init {
        load()
    }

    fun record(state: LiveState) {
        val fix = state.fix ?: return
        if ((fix.accuracyMeters ?: Float.MAX_VALUE) > MAX_ACCURACY_M) return
        val now = System.currentTimeMillis()
        if (now - fix.timeMillis > MAX_FIX_AGE_MS) return
        val sim = state.snapshot.sims.firstOrNull { it.isDataSim } ?: state.snapshot.sims.firstOrNull() ?: return
        val cell = sim.primary
        val dbm = cell?.let {
            when (it.rat) {
                Rat.NR -> it.signal.ssRsrp
                Rat.LTE -> it.signal.rsrp
                Rat.WCDMA -> it.signal.rscp
                Rat.GSM -> it.signal.rssi
            }
        }
        val point = TrackPoint(now, fix.lat, fix.lng, cell?.rat, dbm, cell?.identity?.band?.label)
        synchronized(lock) {
            val last = points.lastOrNull()
            if (last != null) {
                val moved = Geo.distanceMeters(last.lat, last.lng, point.lat, point.lng)
                val elapsed = now - last.timeMillis
                if (moved < MIN_STEP_M && elapsed < STILL_INTERVAL_MS) return
                if (moved < 1.0 && elapsed < TRIP_GAP_MS) return
            }
            points += point
            if (points.size > MAX_POINTS) points.subList(0, points.size - MAX_POINTS).clear()
            dirty = true
        }
    }

    /** Trips, oldest first; a trip is a run of points without a long pause. */
    fun trips(): List<List<TrackPoint>> = synchronized(lock) { split(points.toList()) }

    fun clear() = synchronized(lock) {
        points.clear()
        dirty = true
        flush()
    }

    fun flush() {
        val snapshot = synchronized(lock) {
            if (!dirty) return
            dirty = false
            points.toList()
        }
        val json = JSONArray()
        snapshot.forEach { p ->
            json.put(
                JSONArray().put(p.timeMillis).put(p.lat).put(p.lng).put(p.rat?.name ?: "")
                    .put(p.dbm ?: Int.MIN_VALUE).put(p.band ?: ""),
            )
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file)
    }

    private fun load() {
        val text = runCatching { file.readText() }.getOrNull() ?: return
        runCatching {
            val json = JSONArray(text)
            for (i in 0 until json.length()) {
                val a = json.getJSONArray(i)
                points += TrackPoint(
                    timeMillis = a.getLong(0), lat = a.getDouble(1), lng = a.getDouble(2),
                    rat = a.getString(3).takeIf { it.isNotEmpty() }?.let { runCatching { Rat.valueOf(it) }.getOrNull() },
                    dbm = a.getInt(4).takeIf { it != Int.MIN_VALUE },
                    band = a.getString(5).takeIf { it.isNotEmpty() },
                )
            }
        }
    }

    companion object {
        const val MIN_STEP_M = 25.0
        const val STILL_INTERVAL_MS = 60_000L
        const val TRIP_GAP_MS = 10 * 60_000L
        const val MAX_ACCURACY_M = 50f
        const val MAX_FIX_AGE_MS = 20_000L
        const val MAX_POINTS = 60_000

        fun split(points: List<TrackPoint>): List<List<TrackPoint>> {
            val trips = mutableListOf<MutableList<TrackPoint>>()
            for (p in points) {
                val current = trips.lastOrNull()
                if (current == null || p.timeMillis - current.last().timeMillis > TRIP_GAP_MS) trips += mutableListOf(p)
                else current += p
            }
            return trips.filter { it.size >= 2 }
        }

        /** Length of a trip in metres. */
        fun lengthMeters(trip: List<TrackPoint>): Double =
            trip.zipWithNext { a, b -> Geo.distanceMeters(a.lat, a.lng, b.lat, b.lng).toDouble() }.sum()
    }
}
