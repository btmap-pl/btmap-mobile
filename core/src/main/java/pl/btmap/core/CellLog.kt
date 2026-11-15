package pl.btmap.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import pl.btmap.core.location.GeoFix
import pl.btmap.core.model.Cell
import pl.btmap.core.model.CellIdentity
import pl.btmap.core.model.CellRole
import pl.btmap.core.model.CellSignal
import pl.btmap.core.model.NetworkSnapshot

/** One serving cell seen during the session (the "Log" screen). */
data class LogEntry(
    val key: String,
    val slotIndex: Int,
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
    val lastTaMeters: Int?,
    /** Main power readings (dBm), oldest first, capped at [CellLog.HISTORY]. */
    val history: List<Int>,
    /** Where the phone was at the last sample. */
    val lastFix: GeoFix?,
)

/**
 * Session log of serving cells (primary + secondary). In-memory for now; the persistence layer
 * can replay [entries] into a database later.
 */
class CellLog(private val maxEntries: Int = 5_000) {
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())

    /** Newest first. */
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun record(snapshot: NetworkSnapshot, fix: GeoFix?) {
        val now = snapshot.timestampMillis
        val serving = snapshot.sims.flatMap { sim -> sim.cells.filter(Cell::isServing).map { sim.slotIndex to it } }
            .filter { (_, c) -> c.identity.cid != null }
        if (serving.isEmpty()) return
        _entries.update { current ->
            val byKey = current.associateByTo(LinkedHashMap()) { it.key }
            for ((slot, cell) in serving) {
                val dbm = cell.signal.mainDbm
                val prev = byKey[cell.key]
                byKey[cell.key] = if (prev == null) {
                    LogEntry(
                        cell.key, slot, cell.identity, now, now, dbm, fix, 1,
                        cell.signal, cell.role, cell.taDistanceMeters, listOfNotNull(dbm), fix,
                    )
                } else {
                    val better = dbm != null && (prev.bestDbm == null || dbm > prev.bestDbm)
                    prev.copy(
                        identity = cell.identity,
                        lastSeenMillis = now,
                        bestDbm = if (better) dbm else prev.bestDbm,
                        bestFix = if (better && fix != null) fix else prev.bestFix,
                        samples = prev.samples + 1,
                        lastSignal = cell.signal,
                        lastRole = cell.role,
                        lastTaMeters = cell.taDistanceMeters ?: prev.lastTaMeters,
                        history = if (dbm == null) prev.history else (prev.history + dbm).takeLast(HISTORY),
                        lastFix = fix ?: prev.lastFix,
                    )
                }
            }
            byKey.values.sortedByDescending { it.lastSeenMillis }.take(maxEntries)
        }
    }

    fun entry(key: String): LogEntry? = _entries.value.firstOrNull { it.key == key }

    fun clear() {
        _entries.value = emptyList()
    }

    companion object {
        const val HISTORY = 120
    }
}
