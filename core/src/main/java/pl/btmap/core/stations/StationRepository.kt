package pl.btmap.core.stations

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pl.btmap.core.api.NodeKey
import pl.btmap.core.api.Station
import pl.btmap.core.model.Plmn
import pl.btmap.core.model.Rat
import pl.btmap.core.radio.Bands
import pl.btmap.core.radio.Operators
import java.util.Locale
import java.io.InputStream

/** Download state of one operator (BTSearch). */
data class OperatorProgress(
    val mnc: Int,
    val name: String,
    val status: Status,
    val done: Int = 0,
    val total: Int = 0,
    val cells: Int = 0,
) {
    enum class Status { PENDING, RUNNING, DONE, FAILED }
}

/** State of the database download, for the settings screen. */
sealed interface ImportState {
    data object Idle : ImportState
    data class Running(
        val source: StationSource,
        val done: Int,
        val total: Int,
        val cells: Int,
        /** Waiting for the server's rate limit until this time. */
        val waitingUntil: Long? = null,
        /** BTSearch: per-operator progress, in download order. */
        val operators: List<OperatorProgress> = emptyList(),
    ) : ImportState
    data class Failed(val source: StationSource, val message: String, val atMillis: Long) : ImportState
}

/**
 * The station database the app looks cells up in: either BTMap's own (no UKE records) or
 * BTSearch's, both downloaded to the phone so lookups work offline and cost no data.
 */
class StationRepository(context: Context) {
    private val db = StationDb(context)
    private val importLock = Mutex()

    private val _source = MutableStateFlow(StationSource.BTMAP)
    val source: StateFlow<StationSource> = _source.asStateFlow()

    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()

    /** Bumped whenever the data behind lookups changes (source switch, finished import). */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun setSource(source: StationSource) {
        if (_source.value == source) return
        _source.value = source
        _version.update { it + 1 }
    }

    fun info(source: StationSource): SourceInfo? = runCatching { db.info(source) }.getOrNull()

    /** Identity and station of a neighbour cell found by channel + PCI. */
    data class NeighbourMatch(val station: Station, val nodeId: Long?, val localCellId: Long?, val areaCode: Int?)

    /** Neighbour cell (reported without a cell id) on [channel]/[pci], nearest to the phone. Off the main thread. */
    fun findNeighbour(plmn: Plmn, rat: Rat, channel: Int, pci: Int, lat: Double, lng: Double): NeighbourMatch? {
        for (source in lookupOrder()) {
            val m = runCatching {
                db.findByPhysical(source, plmn.mcc.toInt(), plmn.mnc.toInt(), rat, channel, pci, lat, lng)
            }.getOrNull() ?: continue
            val key = NodeKey(plmn, rat, m.node ?: -1)
            return NeighbourMatch(toStation(source, key, m.site), m.node, m.cid, m.area)
        }
        return null
    }

    /** The user's own file first (it is what they trust for their area), then the selected database. */
    private fun lookupOrder(): List<StationSource> = listOf(StationSource.CUSTOM, _source.value).distinct()

    /** Station of a node in the selected database, or null. Call off the main thread. */
    fun find(key: NodeKey): Station? = lookupOrder().firstNotNullOfOrNull { find(it, key) }

    private fun find(source: StationSource, key: NodeKey): Station? {
        val mcc = key.plmn.mcc.toInt()
        val mnc = key.plmn.mnc.toInt()
        val site = runCatching {
            when (key.rat) {
                Rat.LTE, Rat.NR -> db.findNode(source, mcc, mnc, key.rat, key.nodeId)
                Rat.WCDMA, Rat.GSM -> {
                    val area = (key.nodeId ushr 32).toInt().takeIf { it > 0 }
                    val ci = key.nodeId and 0xFFFFFFFFL
                    if (key.rat == Rat.WCDMA && ci > 0xFFFF) {
                        // 28-bit UMTS cell identity = RNC · 65536 + cell id.
                        db.findCell(source, mcc, mnc, key.rat, ci shr 16, ci and 0xFFFF, area)
                            ?: db.findCell(source, mcc, mnc, key.rat, null, ci, area)
                    } else {
                        db.findCell(source, mcc, mnc, key.rat, null, ci, area)
                    }
                }
            }
        }.getOrNull() ?: return null
        return toStation(source, key, site)
    }

    private fun toStation(source: StationSource, key: NodeKey, site: DbSite): Station {
        // Stored labels are "L:800" / "N:3500" (BTSearch) or plain "800" (BTMap permits).
        val prefix = "${key.rat.name[0]}:"
        val stored = site.bands.filter { !it.contains(':') || it.startsWith(prefix) }.map { it.substringAfter(':') }.distinct()
        val bands = stored.ifEmpty {
            site.channels.mapNotNull { ch -> (if (key.rat == Rat.NR) Bands.nr(ch) else Bands.lte(ch))?.label }
                .distinct()
                .sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
        }
        val geo = "geo:%.6f,%.6f?q=%.6f,%.6f".format(Locale.ROOT, site.lat, site.lng, site.lat, site.lng)
        return Station(
            id = site.externalId ?: site.stationId ?: "${source.name}:${key.plmn.numeric}:${key.rat}:${key.nodeId}",
            stationId = site.stationId,
            address = site.name,
            province = null,
            lat = site.lat,
            lng = site.lng,
            operators = listOfNotNull(Operators.byPlmn(key.plmn)?.btmapName),
            bands = bands,
            nodeLinked = true,
            // BTMap stations open on btmap.pl, BTSearch ones on their station page.
            url = when (source) {
                StationSource.BTMAP -> null
                StationSource.CUSTOM -> geo
                StationSource.BTSEARCH -> site.externalId?.removePrefix("btsearch:")?.let { "https://btsearch.pl/stations/$it" } ?: geo
            },
        )
    }

    /**
     * Downloads [source]. BTSearch goes operator by operator ([preferredMncs] first, e.g. the SIM
     * cards' networks), and every finished operator is saved and usable right away; operators no
     * longer selected are removed. The old data of an operator stays until its new data is complete.
     */
    suspend fun download(
        source: StationSource,
        apiBase: String,
        btsearchMncs: Set<Int>,
        preferredMncs: List<Int> = emptyList(),
        /** Operators downloaded more recently than this are skipped; 0 = re-download everything. */
        maxAgeMs: Long = Long.MAX_VALUE,
    ): Result<SourceInfo> =
        importLock.withLock {
            withContext(Dispatchers.IO) {
                val ctx = currentCoroutineContext()
                val cancelled = { !ctx.isActive }
                val result = runCatching {
                    when (source) {
                        StationSource.BTMAP -> {
                            _state.value = ImportState.Running(source, 0, 0, 0)
                            val progress = progressFor(source)
                            db.replace(source, null) { writer -> BtmapImporter.import(writer, apiBase, progress, cancelled) }
                        }
                        StationSource.BTSEARCH -> downloadBtsearch(btsearchMncs, preferredMncs, maxAgeMs, cancelled)
                        StationSource.CUSTOM -> throw ImportException("Własną bazę wczytuje się z pliku")
                    }
                }
                result.onSuccess {
                    _state.value = ImportState.Idle
                    if (_source.value == source) _version.update { it + 1 }
                }.onFailure { e ->
                    val message = if (!ctx.isActive) "Przerwano" else e.message ?: e.javaClass.simpleName
                    _state.value = ImportState.Failed(source, message, System.currentTimeMillis())
                    // Operators finished before the failure are already saved.
                    if (_source.value == source) _version.update { it + 1 }
                }
                result
            }
        }

    private fun downloadBtsearch(mncs: Set<Int>, preferred: List<Int>, maxAgeMs: Long, cancelled: () -> Boolean): SourceInfo {
        val source = StationSource.BTSEARCH
        val order = DbOperators.ALL.filter { it.mnc in mncs }
            .sortedBy { op -> preferred.indexOf(op.mnc).let { if (it < 0) Int.MAX_VALUE else it } }
        if (order.isEmpty()) throw ImportException("Nie wybrano operatorów")
        val updated = db.operatorUpdated(source)
        val counts = db.cellsPerOperator(source)
        val now = System.currentTimeMillis()
        // Operators with fresh data are not downloaded again.
        fun isFresh(mnc: Int) = updated[mnc]?.let { maxAgeMs > 0 && now - it < maxAgeMs } == true
        var ops = order.map {
            if (isFresh(it.mnc)) OperatorProgress(it.mnc, it.name, OperatorProgress.Status.DONE, cells = counts[it.mnc] ?: 0)
            else OperatorProgress(it.mnc, it.name, OperatorProgress.Status.PENDING)
        }
        fun publish(waiting: Long? = (state.value as? ImportState.Running)?.waitingUntil) {
            val total = ops.sumOf { it.total }
            _state.value = ImportState.Running(source, ops.sumOf { it.done }, total, ops.sumOf { it.cells }, waiting, ops)
        }
        publish(null)

        val present = db.cellsPerOperator(source).keys.toMutableSet()
        // Nothing to fetch: no request at all.
        if (order.all { isFresh(it.mnc) } && (present - mncs).isEmpty()) {
            publish(null)
            return db.info(source) ?: throw ImportException("Brak danych")
        }
        val bandLabels = runCatching { BtsearchImporter.bandLabels(cancelled) }.getOrDefault(emptyMap())
        // Drop operators the user no longer wants.
        (present - mncs).forEach { mnc -> db.replaceOperator(source, mnc, (present - mnc).sorted().joinToString(",")) { } }
        present.retainAll(mncs)

        var info: SourceInfo? = null
        for ((index, op) in order.withIndex()) {
            if (isFresh(op.mnc)) continue
            ops = ops.mapIndexed { i, p -> if (i == index) p.copy(status = OperatorProgress.Status.RUNNING) else p }
            publish()
            val progress = object : ImportProgress {
                override fun update(done: Int, total: Int, cells: Int) {
                    ops = ops.mapIndexed { i, p -> if (i == index) p.copy(done = done, total = total, cells = cells) else p }
                    publish()
                }

                override fun waiting(untilMillis: Long?) = publish(untilMillis)
            }
            try {
                val detail = (present + op.mnc).sorted().joinToString(",")
                info = db.replaceOperator(source, op.mnc, detail) { writer ->
                    BtsearchImporter.importOperator(writer, op, bandLabels, progress, cancelled)
                }
                present += op.mnc
                ops = ops.mapIndexed { i, p -> if (i == index) p.copy(status = OperatorProgress.Status.DONE) else p }
                publish(null)
                // Make the finished operator usable for lookups straight away.
                if (_source.value == source) _version.update { it + 1 }
            } catch (e: Exception) {
                ops = ops.mapIndexed { i, p -> if (i == index) p.copy(status = OperatorProgress.Status.FAILED) else p }
                publish(null)
                throw e
            }
        }
        return info ?: db.info(source) ?: throw ImportException("Brak danych")
    }

    private fun progressFor(source: StationSource) = object : ImportProgress {
        override fun update(done: Int, total: Int, cells: Int) {
            _state.value = ImportState.Running(source, done, total, cells)
        }

        override fun waiting(untilMillis: Long?) {
            _state.update { if (it is ImportState.Running) it.copy(waitingUntil = untilMillis) else it }
        }
    }

    /** Operators of [source] that would be downloaded now: missing ones and those older than [maxAgeMs]. */
    fun staleOperators(source: StationSource, mncs: Set<Int>, maxAgeMs: Long): Set<Int> {
        val updated = runCatching { db.operatorUpdated(source) }.getOrDefault(emptyMap())
        val now = System.currentTimeMillis()
        return mncs.filterTo(mutableSetOf()) { mnc -> updated[mnc]?.let { maxAgeMs <= 0 || now - it >= maxAgeMs } ?: true }
    }

    /** Sites of the selected database in a box, for the map. Off the main thread. */
    fun sitesIn(south: Double, west: Double, north: Double, east: Double): List<StationDb.MapSite> =
        lookupOrder().flatMap { source -> runCatching { db.sitesIn(source, south, west, north, east) }.getOrDefault(emptyList()) }

    /** What an `.ntm` import read: cells saved and rows skipped (no coordinates or not a cell). */
    data class NtmImport(val info: SourceInfo, val skipped: Int)

    /**
     * Replaces the user's own database with a NetMonster `.ntm` file ([name] is kept to show
     * where it came from). Rows without coordinates are skipped: a station needs a place.
     */
    suspend fun importNtm(input: InputStream, name: String): Result<NtmImport> =
        importLock.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    var skipped = 0
                    val info = db.replace(StationSource.CUSTOM, name) { writer ->
                        input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                            lines.forEach { raw ->
                                val line = raw.trimEnd('\r').removePrefix("\uFEFF")
                                if (line.isBlank()) return@forEach
                                val cell = NtmParser.parse(line)
                                if (cell == null) skipped++ else writer.add(cell)
                            }
                        }
                    }
                    if (info.cells == 0) {
                        db.clear(StationSource.CUSTOM)
                        throw ImportException("Brak komórek z lokalizacją w pliku")
                    }
                    NtmImport(info, skipped)
                }.also { _version.update { it + 1 } }
            }
        }

    /** Cells per operator in [source], for the settings screen. */
    fun cellsPerOperator(source: StationSource): Map<Int, Int> = runCatching { db.cellsPerOperator(source) }.getOrDefault(emptyMap())

    fun clear(source: StationSource) {
        runBlocking(Dispatchers.IO) { importLock.withLock { db.clear(source) } }
        if (_source.value == source) _version.update { it + 1 }
    }

}
