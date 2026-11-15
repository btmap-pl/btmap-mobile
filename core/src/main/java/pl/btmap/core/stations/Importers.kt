package pl.btmap.core.stations

import org.json.JSONArray
import org.json.JSONObject
import pl.btmap.core.model.Rat
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Progress of a running import. */
fun interface ImportProgress {
    fun update(bytes: Long, cells: Int)
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
        DbOperator(11, "Nordisk", 3),
        DbOperator(35, "PKP PLK", 7),
        DbOperator(18, "PGE Systemy", 4),
    )
    val DEFAULT_MNCS = setOf(1, 2, 3, 6)

    fun byName(name: String): DbOperator? = ALL.firstOrNull { it.name.equals(name, ignoreCase = true) }
}

private const val USER_AGENT = "BTMap-Mobile (+https://github.com/btmap-pl/btmap-mobile)"

private fun open(url: String, readTimeoutMs: Int): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 30_000
        readTimeout = readTimeoutMs
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Accept-Encoding", "gzip")
    }

private fun HttpURLConnection.body(): InputStream {
    val raw = inputStream
    return if (contentEncoding.equals("gzip", ignoreCase = true)) java.util.zip.GZIPInputStream(raw) else raw
}

/** Counts bytes read, for the progress bar. */
private class CountingStream(input: InputStream, private val onRead: (Long) -> Unit) : FilterInputStream(input) {
    private var total = 0L
    override fun read(): Int = super.read().also { if (it >= 0) onRead(++total) }
    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) { total += it; onRead(total) } }
}

/**
 * BTSearch export (NetMonster `.ntm`, whole Poland). The server streams the full file and is slow,
 * so this runs over Wi-Fi in the background; rows of operators the user did not pick are skipped
 * while streaming, so only those reach the database.
 */
object BtsearchImporter {
    private const val EXPORT = "https://btsearch.pl/api/v1/cells/export"

    fun url(mncs: Set<Int>): String {
        val ids = DbOperators.ALL.filter { it.mnc in mncs }.map { it.btsearchId }.sorted()
        return "$EXPORT?format=ntm&countryCodes=PL" + if (ids.isNotEmpty()) "&operatorIds=${ids.joinToString(",")}" else ""
    }

    fun import(writer: StationDb.Writer, mncs: Set<Int>, progress: ImportProgress, isCancelled: () -> Boolean) {
        val conn = open(url(mncs), readTimeoutMs = 180_000)
        try {
            val code = conn.responseCode
            if (code != 200) throw ImportException(if (code in 500..599) "Serwer BTSearch nie odpowiada ($code)" else "BTSearch: HTTP $code")
            var bytes = 0L
            CountingStream(conn.body()) { bytes = it }.reader(Charsets.UTF_8).buffered(1 shl 16).useLines { lines ->
                for (line in lines) {
                    if (isCancelled()) throw ImportException("Przerwano")
                    val cell = NtmParser.parse(line) ?: continue
                    if (cell.mcc != 260 || cell.mnc !in mncs) continue
                    writer.add(cell)
                    if (writer.cells % 2_000 == 0) progress.update(bytes, writer.cells)
                }
            }
            progress.update(bytes, writer.cells)
            if (writer.cells == 0) throw ImportException("BTSearch zwrócił pusty plik")
        } finally {
            conn.disconnect()
        }
    }
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
        var bytes = 0L
        while (true) {
            if (isCancelled()) throw ImportException("Przerwano")
            val conn = open("${apiBase.trimEnd('/')}/api/towers?source=BTMAP&limit=$PAGE&offset=$offset", readTimeoutMs = 60_000)
            val json = try {
                if (conn.responseCode != 200) throw ImportException("BTMap: HTTP ${conn.responseCode}")
                conn.body().bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
            bytes += json.length
            val o = JSONObject(json)
            val items = o.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) addTower(writer, items.getJSONObject(i))
            progress.update(bytes, writer.cells)
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
