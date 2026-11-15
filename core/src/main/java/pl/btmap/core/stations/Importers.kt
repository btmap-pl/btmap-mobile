package pl.btmap.core.stations

import org.json.JSONArray
import org.json.JSONObject
import pl.btmap.core.model.Rat
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Progress of a running import: stations processed out of [total] (0 = unknown), cells saved. */
fun interface ImportProgress {
    fun update(done: Int, total: Int, cells: Int)

    /** The server asked us to wait (rate limit) until [untilMillis]; null when waiting is over. */
    fun waiting(untilMillis: Long?) {}
}

class ImportException(message: String) : IOException(message)

/** Polish operators selectable for BTSearch, with their MNC and BTSearch operator id. */
data class DbOperator(val mnc: Int, val name: String, val btsearchId: Int)

object DbOperators {
    val ALL = listOf(
        DbOperator(1, "Plus", 1),
        DbOperator(2, "T-Mobile", 5),
        DbOperator(3, "Orange", 6),
        DbOperator(6, "Play", 2),
        DbOperator(34, "NetWorkS!", 8),
    )
    val DEFAULT_MNCS = setOf(1, 2, 3, 6)

    fun byName(name: String): DbOperator? = ALL.firstOrNull { it.name.equals(name, ignoreCase = true) }
    fun byBtsearchId(id: Int): DbOperator? = ALL.firstOrNull { it.btsearchId == id }
}

private const val USER_AGENT = "BTMap-Mobile (+https://github.com/btmap-pl/btmap-mobile)"

private fun open(url: String, readTimeoutMs: Int): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 30_000
        readTimeout = readTimeoutMs
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Accept", "application/json")
        setRequestProperty("Accept-Encoding", "gzip")
    }

private fun HttpURLConnection.body(): InputStream {
    val raw = inputStream
    return if (contentEncoding.equals("gzip", ignoreCase = true)) java.util.zip.GZIPInputStream(raw) else raw
}

/** Sleeps in short steps so a cancelled import stops promptly. */
private fun pause(ms: Long, isCancelled: () -> Boolean) {
    val until = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < until) {
        if (isCancelled()) throw ImportException("Przerwano")
        Thread.sleep(minOf(500L, until - System.currentTimeMillis()).coerceAtLeast(1))
    }
}

private fun HttpURLConnection.headerSeconds(vararg names: String): Long? =
    names.firstNotNullOfOrNull { getHeaderField(it)?.trim()?.toDoubleOrNull() }?.let { (it * 1000).toLong() }

/**
 * GET returning JSON. Honours the server's rate limit: on 429 it waits as long as
 * `x-retry-after` / `Retry-After` says (shown in the UI), and when `x-ratelimit-remaining` runs out
 * it waits for `x-ratelimit-reset` before the next request. Server errors and timeouts are retried.
 */
private fun getJson(url: String, isCancelled: () -> Boolean, progress: ImportProgress? = null): JSONObject {
    var failures = 0
    var waits = 0
    var last: Exception? = null
    while (failures < 4 && waits < 8) {
        if (isCancelled()) throw ImportException("Przerwano")
        try {
            val conn = open(url, readTimeoutMs = 90_000)
            try {
                val code = conn.responseCode
                when {
                    code == 200 -> {
                        val json = JSONObject(conn.body().bufferedReader().use { it.readText() })
                        // Out of requests for this window: wait for the reset before the next one.
                        val remaining = conn.getHeaderField("x-ratelimit-remaining")?.trim()?.toIntOrNull()
                        if (remaining != null && remaining <= 0) {
                            val reset = conn.headerSeconds("x-ratelimit-reset") ?: 60_000L
                            val wait = if (reset > 1_000_000_000_000L) reset - System.currentTimeMillis() else reset
                            if (wait > 0) {
                                progress?.waiting(System.currentTimeMillis() + wait)
                                pause(wait.coerceAtMost(15 * 60_000L), isCancelled)
                                progress?.waiting(null)
                            }
                        }
                        return json
                    }
                    code == 429 -> {
                        val wait = (conn.headerSeconds("x-retry-after", "Retry-After") ?: 60_000L).coerceIn(5_000L, 15 * 60_000L)
                        waits++
                        progress?.waiting(System.currentTimeMillis() + wait)
                        pause(wait, isCancelled)
                        progress?.waiting(null)
                        last = ImportException("Limit zapytań BTSearch")
                    }
                    code in 500..599 -> {
                        failures++
                        last = ImportException("Serwer nie odpowiada ($code)")
                        pause(3_000L * failures * failures, isCancelled)
                    }
                    else -> throw ImportException("HTTP $code")
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: ImportException) {
            throw e
        } catch (e: IOException) {
            failures++
            last = e
            pause(3_000L * failures * failures, isCancelled)
        }
    }
    throw last ?: ImportException("Brak połączenia")
}

/**
 * BTSearch API v2 (`/api/v2/stations`): stations with their location and cells, filtered by
 * operator and paged with a cursor, 1000 stations per request. Cells carry full identities
 * (eNB/CLID/ECI, gNB/NCI, LAC/CID), channels and the PLMNs they broadcast, so shared-network
 * cells (NetWorkS!) are stored under each operator that broadcasts them.
 */
object BtsearchImporter {
    private const val API = "https://btsearch.pl/api"
    private const val PAGE = 1_000

    /**
     * Downloads one operator: its own stations and those of shared networks that broadcast its
     * PLMN, keeping only cells for that operator's MNC.
     */
    fun importOperator(
        writer: StationDb.Writer,
        operator: DbOperator,
        bandLabels: Map<Int, Pair<Rat, String>>,
        progress: ImportProgress,
        isCancelled: () -> Boolean,
    ) {
        val mncs = setOf(operator.mnc)
        val ids = operator.btsearchId.toString()
        var cursor: String? = null
        var done = 0
        var total = 0
        do {
            val url = "$API/v2/stations?operatorIds=$ids&countryCodes=PL&include=location,cells&limit=$PAGE&includeTotal=true" +
                (cursor?.let { "&cursor=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
            val page = getJson(url, isCancelled, progress)
            val paging = page.optJSONObject("paging")
            if (total == 0) total = paging?.optInt("total") ?: 0
            val data = page.optJSONArray("data") ?: JSONArray()
            for (i in 0 until data.length()) addStation(writer, data.getJSONObject(i), mncs, bandLabels)
            done += data.length()
            progress.update(done, total, writer.cells)
            cursor = paging?.optString("nextCursor")?.takeIf { it.isNotEmpty() && it != "null" }
        } while (cursor != null && data.length() > 0)
    }

    /** Band id → (rat, label) from `/api/v1/bands`, for cells without a channel number. */
    fun bandLabels(isCancelled: () -> Boolean): Map<Int, Pair<Rat, String>> {
        val data = getJson("$API/v1/bands", isCancelled).optJSONArray("data") ?: return emptyMap()
        return buildMap {
            for (i in 0 until data.length()) {
                val b = data.getJSONObject(i)
                val value = b.optInt("value")
                if (value <= 0) continue
                val rat = ratOf(b.optString("rat")) ?: continue
                put(b.optInt("id"), rat to value.toString())
            }
        }
    }

    private fun ratOf(s: String): Rat? = when (s.lowercase()) {
        "gsm" -> Rat.GSM
        "umts" -> Rat.WCDMA
        "lte" -> Rat.LTE
        "nr" -> Rat.NR
        else -> null
    }

    private fun JSONObject.long(key: String): Long? = if (has(key) && !isNull(key)) optLong(key).takeIf { it >= 0 } else null

    private fun addStation(writer: StationDb.Writer, s: JSONObject, mncs: Set<Int>, bandLabels: Map<Int, Pair<Rat, String>>) {
        val loc = s.optJSONObject("location") ?: return
        val lat = loc.optDouble("latitude"); val lng = loc.optDouble("longitude")
        if (lat.isNaN() || lng.isNaN()) return
        val name = listOf(loc.optString("city"), loc.optString("address"))
            .filter { it.isNotBlank() && it != "null" }.joinToString(", ").ifEmpty { "Stacja BTSearch" }
        val siteId = s.optString("siteId").takeIf { it.isNotBlank() && it != "null" }
        val ownMnc = DbOperators.byBtsearchId(s.optInt("operatorId"))?.mnc
        val cells = s.optJSONArray("cells") ?: return

        // Band labels per technology, for the station card (e.g. "L:800", "N:3500").
        val labels = linkedSetOf<String>()
        val rows = mutableListOf<DbCell>()
        for (i in 0 until cells.length()) {
            val c = cells.getJSONObject(i)
            val rat = ratOf(c.optString("rat")) ?: continue
            bandLabels[c.optInt("bandId")]?.let { (r, label) -> labels += "${r.name[0]}:$label" }
            val plmns = c.optJSONArray("plmns")
            val cellMncs = buildSet {
                if (plmns != null) for (k in 0 until plmns.length()) {
                    val p = plmns.optString(k)
                    if (p.startsWith("260") && p.length >= 5) p.substring(3).toIntOrNull()?.let(::add)
                }
                if (isEmpty()) ownMnc?.let(::add)
            }.filter { it in mncs }
            if (cellMncs.isEmpty()) continue
            val (node, cid, area, pci, ch) = when (rat) {
                Rat.LTE -> {
                    val eci = c.long("eci")
                    val enb = c.long("enbid") ?: eci?.shr(8)
                    Cols(enb, c.long("clid") ?: eci?.and(0xFF), c.long("tac"), c.long("pci"), c.long("earfcn"))
                }
                Rat.NR -> {
                    val nci = c.long("nci")
                    val bits = c.optInt("gnbidLength", 0).takeIf { it in 22..32 }
                    val gnb = c.long("gnbid") ?: nci?.let { if (bits != null) it shr (36 - bits) else pl.btmap.core.radio.CellMath.splitNci(it).first }
                    val clid = c.long("clid") ?: nci?.let { if (bits != null) it and ((1L shl (36 - bits)) - 1) else pl.btmap.core.radio.CellMath.splitNci(it).second.toLong() }
                    Cols(gnb, clid, c.long("tac"), c.long("pci"), c.long("arfcn"))
                }
                Rat.WCDMA -> Cols(c.long("rnc"), c.long("cid"), c.long("lac"), c.long("psc"), c.long("uarfcn"))
                Rat.GSM -> Cols(null, c.long("cid"), c.long("lac"), c.long("bsic"), null)
            }
            if (node == null && cid == null) continue
            for (mnc in cellMncs) {
                rows += DbCell(rat, 260, mnc, node, cid, area?.toInt(), pci?.toInt(), ch?.toInt(), lat, lng, siteId, name)
            }
        }
        val bands = labels.toList()
        rows.forEach { writer.add(it, externalId = s.optString("id").takeIf { id -> id.isNotEmpty() }?.let { id -> "btsearch:$id" }, bands = bands) }
    }

    private data class Cols(val node: Long?, val cid: Long?, val area: Long?, val pci: Long?, val ch: Long?)
}

/**
 * Stations from the BTMap database that do not come from the UKE registry (added by the community),
 * with the eNB/gNB they serve: community node links, or the number in the station code
 * (e.g. Plus "BT15087" → eNB 15087), the same rule btmap.pl search uses.
 */
object BtmapImporter {
    private const val PAGE = 1_500

    fun import(writer: StationDb.Writer, apiBase: String, progress: ImportProgress, isCancelled: () -> Boolean) {
        var offset = 0
        while (true) {
            if (isCancelled()) throw ImportException("Przerwano")
            val conn = open("${apiBase.trimEnd('/')}/api/towers?source=BTMAP&limit=$PAGE&offset=$offset", readTimeoutMs = 60_000)
            val json = try {
                if (conn.responseCode != 200) throw ImportException("BTMap: HTTP ${conn.responseCode}")
                conn.body().bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
            val o = JSONObject(json)
            val items = o.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) addTower(writer, items.getJSONObject(i))
            progress.update(offset + items.length(), o.optInt("total"), writer.cells)
            offset += items.length()
            if (items.length() == 0 || !o.optBoolean("hasMore", false)) break
        }
    }

    private val CODE_NUMBER = Regex("""^[A-Za-z]{0,3}0*(\d{2,8})$""")

    private fun addTower(writer: StationDb.Writer, t: JSONObject) {
        val lat = t.optDouble("lat"); val lng = t.optDouble("lng")
        if (lat.isNaN() || lng.isNaN()) return
        val towerId = t.optString("id").ifEmpty { return }
        val stationId = t.optString("stationId").takeIf { it.isNotEmpty() && it != "null" }
        val name = t.optString("address").ifEmpty { stationId ?: towerId }
        val links = t.optJSONArray("nodeLinks") ?: JSONArray()
        val ops = t.optJSONArray("operators") ?: JSONArray()
        for (i in 0 until ops.length()) {
            val op = ops.optJSONObject(i) ?: continue
            val operator = DbOperators.byName(op.optString("name")) ?: continue
            val bands = op.optJSONArray("bands") ?: JSONArray()
            val techs = mutableSetOf<Rat>()
            val labels = linkedSetOf<String>()
            for (b in 0 until bands.length()) {
                val band = bands.optJSONObject(b) ?: continue
                when (band.optString("tech")) {
                    "LTE", "4G" -> techs += Rat.LTE
                    "5G", "NR" -> techs += Rat.NR
                    "UMTS", "3G" -> techs += Rat.WCDMA
                    "GSM", "2G" -> techs += Rat.GSM
                }
                band.optString("band").takeIf { it.isNotEmpty() }?.let(labels::add)
            }
            val nodes = mutableSetOf<Pair<Rat, Long>>()
            for (l in 0 until links.length()) {
                val link = links.optJSONObject(l) ?: continue
                if (!link.optString("operator").equals(operator.name, ignoreCase = true)) continue
                nodes += (if (link.optString("tech") == "5G") Rat.NR else Rat.LTE) to link.optLong("nodeId")
            }
            val codes = buildList {
                stationId?.let(::add)
                op.optJSONArray("stationIds")?.let { a -> for (k in 0 until a.length()) add(a.optString(k)) }
            }
            for (code in codes) {
                val number = CODE_NUMBER.find(code.trim())?.groupValues?.get(1)?.toLongOrNull() ?: continue
                techs.filter { it == Rat.LTE || it == Rat.NR }.forEach { nodes += it to number }
            }
            for ((rat, node) in nodes) {
                writer.add(
                    DbCell(rat, 260, operator.mnc, node, null, null, null, null, lat, lng, stationId, name),
                    externalId = towerId,
                    bands = labels.toList(),
                )
            }
        }
    }
}
