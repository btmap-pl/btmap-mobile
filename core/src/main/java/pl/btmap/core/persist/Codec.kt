package pl.btmap.core.persist

import org.json.JSONArray
import org.json.JSONObject
import pl.btmap.core.location.GeoFix
import pl.btmap.core.model.CellIdentity
import pl.btmap.core.model.CellSignal
import pl.btmap.core.model.Plmn
import pl.btmap.core.model.Rat
import pl.btmap.core.radio.Bands
import java.io.File

/**
 * Compact JSON encoding of core models for on-device storage. Derived values (band, DL frequency)
 * are not stored; they are recomputed from the channel on load so table fixes apply to old data.
 */
internal object Codec {

    fun identity(id: CellIdentity) = JSONObject().apply {
        put("r", id.rat.name)
        id.plmn?.let { put("p", it.numeric) }
        id.cid?.let { put("cid", it) }
        id.nodeId?.let { put("n", it) }
        id.localCellId?.let { put("lc", it) }
        id.areaCode?.let { put("ac", it) }
        id.physicalId?.let { put("pci", it) }
        id.channel?.let { put("ch", it) }
        id.band?.number?.let { put("b", it) }
        id.bandwidthKhz?.let { put("bw", it) }
        if (id.learned) put("l", true)
    }

    fun identity(o: JSONObject): CellIdentity {
        val rat = Rat.valueOf(o.getString("r"))
        val channel = o.intOrNull("ch")
        val reported = o.intOrNull("b")?.let { intArrayOf(it) }
        val band = when (rat) {
            Rat.LTE -> Bands.lte(channel, reported)
            Rat.NR -> Bands.nr(channel, reported)
            Rat.WCDMA -> Bands.umts(channel)
            Rat.GSM -> Bands.gsm(channel)
        }
        return CellIdentity(
            rat = rat,
            plmn = o.stringOrNull("p")?.let(Plmn::parse),
            cid = o.longOrNull("cid"),
            nodeId = o.longOrNull("n"),
            localCellId = o.intOrNull("lc"),
            areaCode = o.intOrNull("ac"),
            physicalId = o.intOrNull("pci"),
            channel = channel,
            band = band,
            bandwidthKhz = o.intOrNull("bw"),
            learned = o.optBoolean("l", false),
        )
    }

    private val SIGNAL_FIELDS = listOf<Pair<String, (CellSignal) -> Int?>>(
        "rssi" to CellSignal::rssi, "rsrp" to CellSignal::rsrp, "rsrq" to CellSignal::rsrq, "snr" to CellSignal::snr,
        "cqi" to CellSignal::cqi, "ssrsrp" to CellSignal::ssRsrp, "ssrsrq" to CellSignal::ssRsrq, "sssinr" to CellSignal::ssSinr,
        "csirsrp" to CellSignal::csiRsrp, "csirsrq" to CellSignal::csiRsrq, "csisinr" to CellSignal::csiSinr,
        "rscp" to CellSignal::rscp, "ecno" to CellSignal::ecno, "ber" to CellSignal::ber,
        "ta" to CellSignal::timingAdvance, "tau" to CellSignal::timingAdvanceMicros, "lvl" to CellSignal::level,
    )

    fun signal(s: CellSignal) = JSONObject().apply {
        SIGNAL_FIELDS.forEach { (k, get) -> get(s)?.let { put(k, it) } }
    }

    fun signal(o: JSONObject) = CellSignal(
        rssi = o.intOrNull("rssi"), rsrp = o.intOrNull("rsrp"), rsrq = o.intOrNull("rsrq"), snr = o.intOrNull("snr"),
        cqi = o.intOrNull("cqi"), ssRsrp = o.intOrNull("ssrsrp"), ssRsrq = o.intOrNull("ssrsrq"), ssSinr = o.intOrNull("sssinr"),
        csiRsrp = o.intOrNull("csirsrp"), csiRsrq = o.intOrNull("csirsrq"), csiSinr = o.intOrNull("csisinr"),
        rscp = o.intOrNull("rscp"), ecno = o.intOrNull("ecno"), ber = o.intOrNull("ber"),
        timingAdvance = o.intOrNull("ta"), timingAdvanceMicros = o.intOrNull("tau"), level = o.intOrNull("lvl"),
    )

    fun fix(f: GeoFix) = JSONObject().apply {
        put("lat", f.lat); put("lng", f.lng); put("t", f.timeMillis)
        f.accuracyMeters?.let { put("acc", it.toDouble()) }
        f.altitudeMeters?.let { put("alt", it) }
        f.provider?.let { put("pr", it) }
    }

    fun fix(o: JSONObject) = GeoFix(
        lat = o.getDouble("lat"), lng = o.getDouble("lng"),
        accuracyMeters = o.doubleOrNull("acc")?.toFloat(), altitudeMeters = o.doubleOrNull("alt"),
        speedKmh = null, bearing = null, timeMillis = o.optLong("t"), provider = o.stringOrNull("pr"),
    )

    fun ints(values: List<Int>) = JSONArray().apply { values.forEach { put(it) } }
    fun ints(a: JSONArray?): List<Int> = if (a == null) emptyList() else (0 until a.length()).map { a.getInt(it) }

    /** Atomic write: a crash mid-write never leaves a truncated file behind. */
    fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    fun JSONObject.intOrNull(k: String): Int? = if (has(k) && !isNull(k)) getInt(k) else null
    fun JSONObject.longOrNull(k: String): Long? = if (has(k) && !isNull(k)) getLong(k) else null
    fun JSONObject.doubleOrNull(k: String): Double? = if (has(k) && !isNull(k)) getDouble(k) else null
    fun JSONObject.stringOrNull(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null
}
