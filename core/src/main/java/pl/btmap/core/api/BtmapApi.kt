package pl.btmap.core.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import pl.btmap.core.model.Cell
import pl.btmap.core.model.CellIdentity
import pl.btmap.core.model.Plmn
import pl.btmap.core.model.Rat
import pl.btmap.core.radio.Operators
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A BTMap station matched to a cell. */
data class Station(
    val id: String,
    val stationId: String?,
    val address: String,
    val province: String?,
    val lat: Double,
    val lng: Double,
    /** Operators present on the site, BTMap names ("Play", "Plus"…). */
    val operators: List<String>,
    /** UKE band labels the matched operator holds there, e.g. ["800", "1800", "2100"]. */
    val bands: List<String>,
    /** True when the eNB/gNB is linked to this site on BTMap (not just a search hit). */
    val nodeLinked: Boolean,
    val distanceKm: Double? = null,
    /** Page to open for the station; null = BTMap station page. */
    val url: String? = null,
)

/** Which node a station lookup is for; also the cache key. */
data class NodeKey(val plmn: Plmn, val rat: Rat, val nodeId: Long) {
    val operatorName: String? get() = Operators.byPlmn(plmn)?.btmapName

    /** Search text understood by btmap.pl (`shared/cellSearch.ts`), e.g. "260 06 eNB 24102". */
    val query: String
        get() = "${plmn.mcc} ${plmn.mnc} ${if (rat == Rat.NR) "gNB" else "eNB"} $nodeId"

    companion object {
        fun of(cell: Cell): NodeKey? = of(cell.identity)

        fun of(id: CellIdentity): NodeKey? {
            if (id.rat != Rat.LTE && id.rat != Rat.NR) return null
            return NodeKey(id.plmn ?: return null, id.rat, id.nodeId ?: return null)
        }
    }
}

/**
 * Client for the BTMap web API. Lookups are cached in memory per node, including misses, so a
 * live screen can ask on every refresh without hitting the server.
 */
class BtmapApi(
    baseUrl: String,
    private val missTtlMs: Long = 10 * 60_000,
    private val timeoutMs: Int = 8_000,
) {
    @Volatile private var base = baseUrl.trimEnd('/')

    val baseUrl: String get() = base

    /** Points the client at another server; cached lookups belong to the old one and are dropped. */
    suspend fun setBaseUrl(url: String) {
        val clean = url.trim().trimEnd('/')
        if (clean == base) return
        mutex.withLock {
            base = clean
            cache.clear()
        }
    }
    private val mutex = Mutex()
    private val cache = object : LinkedHashMap<NodeKey, CacheEntry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<NodeKey, CacheEntry>?) = size > 2_000
    }

    private data class CacheEntry(val station: Station?, val atMillis: Long)

    /** Station a cell belongs to, or null when BTMap does not know the node. */
    suspend fun stationFor(cell: Cell): Station? = NodeKey.of(cell)?.let { stationFor(it) }

    /** Cached result only — never touches the network. */
    suspend fun cachedStation(key: NodeKey): Station? = mutex.withLock { cache[key]?.station }

    suspend fun stationFor(key: NodeKey): Station? {
        mutex.withLock {
            cache[key]?.let { hit ->
                if (hit.station != null || System.currentTimeMillis() - hit.atMillis < missTtlMs) return hit.station
            }
        }
        val station = searchNode(key)
        mutex.withLock { cache[key] = CacheEntry(station, System.currentTimeMillis()) }
        return station
    }

    /** `GET /api/towers?q=260 06 eNB 24102` — best match first. */
    suspend fun searchNode(key: NodeKey): Station? {
        val json = getJson("/api/towers", mapOf("q" to key.query, "limit" to "5"))
        val items = json.optJSONArray("items") ?: return null
        if (items.length() == 0) return null
        val all = (0 until items.length()).map { parseStation(items.getJSONObject(it), key) }
        // A community link beats a fuzzy stationId match.
        return all.firstOrNull { it.nodeLinked } ?: all.first()
    }

    /** `GET /api/towers/nearest` — candidate sites around a position, for node linking. */
    suspend fun nearest(
        lat: Double,
        lng: Double,
        operator: String? = null,
        rat: Rat = Rat.LTE,
        radiusKm: Int = 5,
        limit: Int = 10,
    ): List<Station> {
        val params = buildMap {
            put("lat", lat.toString())
            put("lng", lng.toString())
            put("radiusKm", radiusKm.toString())
            put("limit", limit.toString())
            put("techs", if (rat == Rat.NR) "5G" else "LTE")
            operator?.let { put("operators", it) }
        }
        val items = getJson("/api/towers/nearest", params).optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).map { parseStation(items.getJSONObject(it), null) }
    }

    /** `GET /api/health` — reachability check for settings / diagnostics. */
    suspend fun health(): JSONObject = getJson("/api/health", emptyMap())

    fun stationUrl(station: Station): String = station.url ?: "$base/?station=${encode(station.id)}"

    private fun parseStation(o: JSONObject, key: NodeKey?): Station {
        val operators = o.optJSONArray("operators") ?: JSONArray()
        val names = mutableListOf<String>()
        val bands = linkedSetOf<String>()
        val wanted = key?.operatorName
        val wantNr = key?.rat == Rat.NR
        for (i in 0 until operators.length()) {
            val op = operators.optJSONObject(i) ?: continue
            val name = op.optString("name")
            if (name.isNotEmpty()) names += name
            if (wanted != null && name != wanted) continue
            val opBands = op.optJSONArray("bands") ?: continue
            for (b in 0 until opBands.length()) {
                val band = opBands.optJSONObject(b) ?: continue
                val tech = band.optString("tech")
                val isNr = tech == "5G" || tech == "NR"
                if (key != null && isNr != wantNr) continue
                band.optString("band").takeIf { it.isNotEmpty() }?.let(bands::add)
            }
        }
        val links = o.optJSONArray("nodeLinks")
        val linked = key != null && links != null && (0 until links.length()).any { i ->
            val l = links.optJSONObject(i) ?: return@any false
            l.optLong("nodeId") == key.nodeId &&
                (l.optString("tech") == "5G") == (key.rat == Rat.NR) &&
                (key.operatorName == null || l.optString("operator") == key.operatorName)
        }
        return Station(
            id = o.getString("id"),
            stationId = o.optString("stationId").takeIf { it.isNotEmpty() && it != "null" },
            address = o.optString("address"),
            province = o.optString("province").takeIf { it.isNotEmpty() && it != "null" },
            lat = o.optDouble("lat"),
            lng = o.optDouble("lng"),
            operators = names.distinct(),
            bands = bands.toList(),
            nodeLinked = linked,
            distanceKm = o.optDouble("distanceKm").takeIf { !it.isNaN() },
        )
    }

    private suspend fun getJson(path: String, params: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val query = params.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        val url = URL(base + path + if (query.isEmpty()) "" else "?$query")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "BTMap-Mobile")
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(body).optString("error") }.getOrNull()
                throw BtmapApiException(code, message?.takeIf { it.isNotEmpty() } ?: "HTTP $code")
            }
            JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun encode(v: String) = URLEncoder.encode(v, "UTF-8")
}

class BtmapApiException(val code: Int, message: String) : IOException(message)
