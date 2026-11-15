package pl.btmap.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import pl.btmap.core.location.GeoFix
import pl.btmap.core.model.Cell
import pl.btmap.core.model.CellIdentity
import pl.btmap.core.model.CellRole
import pl.btmap.core.model.CellSignal
import pl.btmap.core.model.NetworkSnapshot
import pl.btmap.core.model.Plmn
import pl.btmap.core.persist.Codec
import pl.btmap.core.persist.Codec.intOrNull
import pl.btmap.core.persist.Codec.stringOrNull
import java.io.File

/** One cell the phone was connected to (PCell, CA secondary or the NR leg of EN-DC). */
data class LogEntry(
    val key: String,
    val slotIndex: Int,
    /** Network of the SIM that saw the cell; neighbours rarely carry their own PLMN. */
    val simPlmn: Plmn?,
    val identity: CellIdentity,
    val firstSeenMillis: Long,
    val lastSeenMillis: Long,
    val bestDbm: Int?,
    /** Where the cell was best heard, if a fix was available. */
    val bestFix: GeoFix?,
    val samples: Int,
    /** Most recent measurement of the cell. */
    val lastSignal: CellSignal,
    val lastRole: CellRole,
    /** Kept for stored logs from builds that also logged neighbours; always true for new entries. */
    val wasServing: Boolean,
    val lastTaMeters: Int?,
    /** Main power readings (dBm), oldest first, capped at [CellLog.HISTORY]. */
    val history: List<Int>,
    /** Where the phone was at the last sample. */
    val lastFix: GeoFix?,
) {
    val plmn: Plmn? get() = identity.plmn ?: simPlmn
}

/**
 * Log of every cell the phone was connected to, kept across app restarts in [file]. Neighbours are
 * not logged. Cells are keyed by their global identity; a serving cell reported without one (e.g. an
 * NSA leg) falls back to SIM + channel + physical id.
 */
class CellLog(
    private val file: File? = null,
    private val maxEntries: Int = 5_000,
) {
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())

    /** Newest first (by first sighting), so new cells appear on top and the list does not jump. */
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    @Volatile private var dirty = false

    /**
     * Reads the saved log (call once, off the main thread). Entries recorded meanwhile win over the
     * saved copies of the same cell.
     */
    fun loadFromDisk() {
        val f = file ?: return
        val saved = runCatching { load(f) }.getOrDefault(emptyList())
        if (saved.isEmpty()) return
        _entries.update { current ->
            val live = current.associateBy { it.key }
            (saved.filter { it.key !in live } + current).sortedByDescending { it.firstSeenMillis }.take(maxEntries)
        }
    }

    fun record(snapshot: NetworkSnapshot, fix: GeoFix?) {
        val now = snapshot.timestampMillis
        val seen = snapshot.sims.flatMap { sim ->
            val simPlmn = sim.registeredPlmn ?: sim.simPlmn
            val serving = sim.cells.filter(Cell::isServing)
            // CA secondaries matched from the bandwidth list are connected too, though the modem does not list them.
            val inferred = sim.carriers
                .filter { !it.isPrimary && it.cell == null && it.identity?.cid != null }
                .filter { c -> serving.none { it.identity.cid == c.identity!!.cid } }
                .map { c ->
                    Cell(
                        identity = c.identity!!.copy(bandwidthKhz = c.bandwidthKhz ?: c.identity.bandwidthKhz),
                        signal = CellSignal(),
                        role = CellRole.SECONDARY,
                        taDistanceMeters = null,
                        timestampNanos = 0,
                    )
                }
            (serving + inferred).map { Triple(sim.slotIndex, simPlmn, it) }
        }.filter { (_, _, c) -> c.identity.cid != null || (c.identity.channel != null && c.identity.physicalId != null) }
        if (seen.isEmpty()) return

        _entries.update { current ->
            val byKey = current.associateByTo(LinkedHashMap()) { it.key }
            for ((slot, simPlmn, cell) in seen) {
                val id = cell.identity
                val key = if (id.cid != null && id.plmn != null) cell.key else "$slot|${cell.key}"
                val dbm = cell.signal.mainDbm
                val serving = cell.role != CellRole.NEIGHBOUR
                val prev = byKey[key]
                byKey[key] = if (prev == null) {
                    LogEntry(
                        key = key, slotIndex = slot, simPlmn = simPlmn, identity = id,
                        firstSeenMillis = now, lastSeenMillis = now, bestDbm = dbm, bestFix = fix, samples = 1,
                        lastSignal = cell.signal, lastRole = cell.role, wasServing = serving,
                        lastTaMeters = cell.taDistanceMeters, history = listOfNotNull(dbm), lastFix = fix,
                    )
                } else {
                    val better = dbm != null && (prev.bestDbm == null || dbm > prev.bestDbm)
                    prev.copy(
                        // A neighbour sample must not erase what a serving sample taught us.
                        identity = if (serving || prev.identity.cid == null) id.mergedWith(prev.identity) else prev.identity,
                        lastSeenMillis = now,
                        bestDbm = if (better) dbm else prev.bestDbm,
                        bestFix = if (better && fix != null) fix else prev.bestFix,
                        samples = prev.samples + 1,
                        lastSignal = cell.signal,
                        lastRole = cell.role,
                        wasServing = prev.wasServing || serving,
                        lastTaMeters = cell.taDistanceMeters ?: prev.lastTaMeters,
                        history = if (dbm == null) prev.history else (prev.history + dbm).takeLast(HISTORY),
                        lastFix = fix ?: prev.lastFix,
                    )
                }
            }
            val sorted = byKey.values.sortedByDescending { it.firstSeenMillis }
            if (sorted.size > maxEntries) sorted.take(maxEntries) else sorted
        }
        dirty = true
    }

    private fun CellIdentity.mergedWith(old: CellIdentity) = copy(
        plmn = plmn ?: old.plmn,
        cid = cid ?: old.cid,
        nodeId = nodeId ?: old.nodeId,
        localCellId = localCellId ?: old.localCellId,
        areaCode = areaCode ?: old.areaCode,
        bandwidthKhz = bandwidthKhz ?: old.bandwidthKhz,
    )

    /** The log as CSV (one row per cell), for export to a spreadsheet. */
    fun toCsv(): String = buildString {
        appendLine("first_seen,last_seen,sim,plmn,rat,band,node,cid,ci,tac,pci,channel,bandwidth_khz,best_dbm,samples,best_lat,best_lng")
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT)
        for (e in _entries.value) {
            val id = e.identity
            listOf(
                time.format(java.util.Date(e.firstSeenMillis)),
                time.format(java.util.Date(e.lastSeenMillis)),
                e.slotIndex + 1,
                e.plmn?.numeric,
                id.rat.label,
                id.band?.label,
                id.nodeId,
                id.localCellId,
                id.cid,
                id.areaCode,
                id.physicalId,
                id.channel,
                id.bandwidthKhz,
                e.bestDbm,
                e.samples,
                e.bestFix?.lat?.let { "%.6f".format(java.util.Locale.ROOT, it) },
                e.bestFix?.lng?.let { "%.6f".format(java.util.Locale.ROOT, it) },
            ).joinTo(this, ",") { it?.toString().orEmpty() }
            appendLine()
        }
    }

    fun entry(key: String): LogEntry? = _entries.value.firstOrNull { it.key == key }

    fun clear() {
        _entries.value = emptyList()
        dirty = true
    }

    /** Writes the log to [file] if it changed. Call from a background thread. */
    @Synchronized
    fun flush() {
        val target = file ?: return
        if (!dirty) return
        dirty = false
        val array = JSONArray()
        _entries.value.forEach { e ->
            array.put(JSONObject().apply {
                put("k", e.key); put("s", e.slotIndex)
                e.simPlmn?.let { put("sp", it.numeric) }
                put("id", Codec.identity(e.identity))
                put("f", e.firstSeenMillis); put("l", e.lastSeenMillis)
                e.bestDbm?.let { put("bd", it) }
                e.bestFix?.let { put("bf", Codec.fix(it)) }
                put("n", e.samples)
                put("sig", Codec.signal(e.lastSignal))
                put("role", e.lastRole.name)
                put("srv", e.wasServing)
                e.lastTaMeters?.let { put("ta", it) }
                put("h", Codec.ints(e.history.takeLast(PERSISTED_HISTORY)))
                e.lastFix?.let { put("lf", Codec.fix(it)) }
            })
        }
        Codec.writeAtomically(target, array.toString())
    }

    private fun load(source: File): List<LogEntry> {
        if (!source.exists()) return emptyList()
        val array = JSONArray(source.readText())
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                LogEntry(
                    key = o.getString("k"),
                    slotIndex = o.getInt("s"),
                    simPlmn = o.stringOrNull("sp")?.let(Plmn::parse),
                    identity = Codec.identity(o.getJSONObject("id")),
                    firstSeenMillis = o.getLong("f"),
                    lastSeenMillis = o.getLong("l"),
                    // Older builds stored the -44 dBm "switching" artefact; drop it on load.
                    bestDbm = o.intOrNull("bd")?.takeIf { it < -44 }
                        ?: Codec.ints(o.optJSONArray("h")).filter { it < -44 }.maxOrNull(),
                    bestFix = o.optJSONObject("bf")?.let(Codec::fix),
                    samples = o.optInt("n", 1),
                    lastSignal = o.optJSONObject("sig")?.let(Codec::signal) ?: CellSignal(),
                    lastRole = runCatching { CellRole.valueOf(o.getString("role")) }.getOrDefault(CellRole.NEIGHBOUR),
                    wasServing = o.optBoolean("srv", false),
                    lastTaMeters = o.intOrNull("ta"),
                    history = Codec.ints(o.optJSONArray("h")).filter { it < -44 },
                    lastFix = o.optJSONObject("lf")?.let(Codec::fix),
                )
            }.getOrNull()
        }.sortedByDescending { it.firstSeenMillis }
    }

    companion object {
        const val HISTORY = 120
        private const val PERSISTED_HISTORY = 60
    }
}
