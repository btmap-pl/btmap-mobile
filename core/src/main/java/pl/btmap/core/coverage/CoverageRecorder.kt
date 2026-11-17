package pl.btmap.core.coverage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import pl.btmap.core.LiveState
import pl.btmap.core.model.Rat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.floor

/** One coverage measurement as the BTMap server expects it. */
data class CoverageSample(
    val lat: Double,
    val lng: Double,
    val mcc: String,
    val mnc: String,
    val tech: String,
    val band: String,
    val rsrp: Int,
)

/**
 * Builds the crowdsourced coverage map: for every band the phone is connected on (PCell, carrier
 * aggregation SCells, the 5G leg), a measurement with the position is queued and later uploaded
 * in batches. Nothing identifies the phone; the same band in the same ~100 m grid cell is recorded
 * at most once per [REPEAT_MS], which keeps the queue small and stops a parked phone from
 * dominating a cell.
 */
class CoverageRecorder(private val file: File) {
    private val lock = Any()
    private val queue = ArrayList<CoverageSample>()
    private val lastRecorded = HashMap<String, Long>()

    init {
        runCatching { if (file.exists()) queue += decode(file.readText()) }
    }

    val pending: Int get() = synchronized(lock) { queue.size }

    /** Adds the bands of [state] at its position. Skips imprecise or stale fixes. */
    fun record(state: LiveState) {
        val fix = state.fix ?: return
        if ((fix.accuracyMeters ?: Float.MAX_VALUE) > MAX_ACCURACY_M) return
        if (System.currentTimeMillis() - fix.timeMillis > MAX_FIX_AGE_MS) return
        val now = System.currentTimeMillis()
        val gy = floor(fix.lat / GRID_LAT).toLong()
        val gx = floor(fix.lng / GRID_LNG).toLong()
        val added = mutableListOf<CoverageSample>()
        for (sim in state.snapshot.sims) {
            val plmn = sim.registeredPlmn ?: continue
            if (sim.isRoaming) continue
            for (carrier in sim.carriers) {
                val band = carrier.band ?: continue
                val cell = carrier.cell ?: continue
                // Only fresh measurements: an SCell value older than a few seconds is not "here".
                if ((carrier.signalAgeMs ?: 0) > MAX_SIGNAL_AGE_MS) continue
                val dbm = when (carrier.rat) {
                    Rat.NR -> cell.signal.ssRsrp
                    Rat.LTE -> cell.signal.rsrp
                    Rat.WCDMA -> cell.signal.rscp
                    Rat.GSM -> cell.signal.rssi
                } ?: continue
                val tech = when (carrier.rat) { Rat.NR -> "5G"; Rat.LTE -> "LTE"; Rat.WCDMA -> "UMTS"; Rat.GSM -> "GSM" }
                val key = "$gy:$gx:${plmn.numeric}:$tech:${band.label}"
                if (now - (lastRecorded[key] ?: 0) < REPEAT_MS) continue
                lastRecorded[key] = now
                added += CoverageSample(fix.lat, fix.lng, plmn.mcc, plmn.mnc, tech, band.label, dbm)
            }
        }
        if (added.isEmpty()) return
        synchronized(lock) {
            queue += added
            if (queue.size > MAX_QUEUE) queue.subList(0, queue.size - MAX_QUEUE).clear()
            if (lastRecorded.size > 20_000) lastRecorded.entries.removeIf { now - it.value > REPEAT_MS }
        }
    }

    /** Saves the queue so measurements survive the app being killed. */
    fun flush() {
        val text = synchronized(lock) { encode(queue).toString() }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(file)
    }

    /**
     * Uploads queued measurements to [apiBase] in batches of 500. Returns how many were sent;
     * on an error the rest stays queued for the next attempt.
     */
    suspend fun upload(apiBase: String): Int = withContext(Dispatchers.IO) {
        var sent = 0
        while (true) {
            val batch = synchronized(lock) { queue.take(BATCH).toList() }
            if (batch.isEmpty()) break
            val conn = (URL(apiBase.trimEnd('/') + "/api/coverage").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "BTMap-Mobile")
            }
            try {
                conn.outputStream.use { it.write(JSONObject().put("samples", encode(batch)).toString().toByteArray()) }
                val code = conn.responseCode
                if (code !in 200..299) break
            } catch (e: Exception) {
                break
            } finally {
                conn.disconnect()
            }
            synchronized(lock) { repeat(batch.size.coerceAtMost(queue.size)) { queue.removeAt(0) } }
            sent += batch.size
        }
        if (sent > 0) runCatching { flush() }
        sent
    }

    fun clear() {
        synchronized(lock) { queue.clear() }
        runCatching { flush() }
    }

    private fun encode(list: List<CoverageSample>) = JSONArray().apply {
        list.forEach {
            put(
                JSONObject().put("lat", Math.round(it.lat * 1e5) / 1e5).put("lng", Math.round(it.lng * 1e5) / 1e5)
                    .put("mcc", it.mcc).put("mnc", it.mnc).put("tech", it.tech).put("band", it.band).put("rsrp", it.rsrp),
            )
        }
    }

    private fun decode(text: String): List<CoverageSample> {
        val a = JSONArray(text)
        return (0 until a.length()).mapNotNull { i ->
            runCatching {
                val o = a.getJSONObject(i)
                CoverageSample(o.getDouble("lat"), o.getDouble("lng"), o.getString("mcc"), o.getString("mnc"), o.getString("tech"), o.getString("band"), o.getInt("rsrp"))
            }.getOrNull()
        }
    }

    companion object {
        /** Same grid as the server (~100 m). */
        const val GRID_LAT = 0.001
        const val GRID_LNG = 0.0016
        private const val MAX_ACCURACY_M = 50f
        private const val MAX_FIX_AGE_MS = 15_000L
        private const val MAX_SIGNAL_AGE_MS = 8_000L
        private const val REPEAT_MS = 10 * 60_000L
        private const val MAX_QUEUE = 20_000
        private const val BATCH = 500
    }
}
