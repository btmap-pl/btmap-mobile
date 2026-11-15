package pl.btmap.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.btmap.core.api.BtmapApi
import pl.btmap.core.api.NodeKey
import pl.btmap.core.api.Station
import pl.btmap.core.location.Geo
import pl.btmap.core.location.GeoFix
import pl.btmap.core.location.LocationTracker
import pl.btmap.core.model.Carrier
import pl.btmap.core.model.CarrierSource
import pl.btmap.core.model.Cell
import pl.btmap.core.model.CellIdentity
import pl.btmap.core.model.SimState
import pl.btmap.core.radio.Bands
import pl.btmap.core.model.NetworkSnapshot
import pl.btmap.core.telephony.CellDictionary
import pl.btmap.core.telephony.CellMonitor
import java.io.File

/** Everything a screen needs, in one immutable value. */
data class LiveState(
    val snapshot: NetworkSnapshot = NetworkSnapshot.EMPTY,
    val fix: GeoFix? = null,
    /** BTMap station per node; a key mapped to null means "looked up, not on BTMap". */
    val stations: Map<NodeKey, Station?> = emptyMap(),
    val running: Boolean = false,
    val apiError: String? = null,
) {
    /**
     * BTMap station of a cell. A search hit that is not a community node link and lies further than
     * [MAX_UNLINKED_DISTANCE_M] from the phone is a coincidental stationId match, so it is dropped.
     */
    fun stationOf(cell: Cell): Station? = stationOf(cell.identity)

    fun stationOf(identity: CellIdentity): Station? {
        val station = NodeKey.of(identity)?.let { stations[it] } ?: return null
        if (station.nodeLinked || fix == null) return station
        val distance = Geo.distanceMeters(fix.lat, fix.lng, station.lat, station.lng)
        return station.takeIf { distance <= MAX_UNLINKED_DISTANCE_M }
    }

    /**
     * Carriers of a SIM, with unknown secondaries narrowed down using the BTMap station of the
     * PCell: if the site holds exactly as many unused bands of that technology as there are unknown
     * carriers and that number is one, the band is known.
     */
    fun carriersOf(sim: SimState): List<Carrier> {
        val carriers = sim.carriers
        val unknown = carriers.filter { it.source == CarrierSource.UNKNOWN && it.rat == carriers.firstOrNull()?.rat }
        if (unknown.size != 1) return carriers
        val pcell = sim.primary ?: return carriers
        val station = stationOf(pcell) ?: return carriers
        val used = carriers.filter { it.rat == pcell.rat }.mapNotNull { it.band?.label }.toSet()
        val free = station.bands.filter { it !in used }.distinct()
        if (free.size != 1) return carriers
        val band = Bands.fromLabel(pcell.rat, free.single()) ?: return carriers
        return carriers.map { if (it === unknown.single()) it.copy(band = band, source = CarrierSource.STATION) else it }
    }

    companion object {
        const val MAX_UNLINKED_DISTANCE_M = 35_000
    }
    fun isLookedUp(cell: Cell): Boolean = NodeKey.of(cell)?.let { it in stations } ?: true
}

/**
 * Entry point of the core: owns the modem monitor, location tracker, BTMap lookups and session log.
 * Call [start] once permissions are granted and [stop] when the UI / service no longer needs data.
 */
class BtmapCore(
    context: Context,
    apiBaseUrl: String,
    refreshIntervalMs: Long = 2_000,
) {
    val dictionary = CellDictionary(File(context.filesDir, "cell-dictionary.json"))
    val monitor = CellMonitor(context, refreshIntervalMs, dictionary)
    val location = LocationTracker(context)
    val api = BtmapApi(apiBaseUrl)
    val log = CellLog()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private var jobs: List<Job> = emptyList()
    private val inFlight = mutableSetOf<NodeKey>()
    /** Last failed lookup per node, so an offline phone does not retry on every refresh. */
    private val failedAt = mutableMapOf<NodeKey, Long>()

    fun hasPermissions() = monitor.hasPermissions()

    @Synchronized
    fun start() {
        if (jobs.isNotEmpty()) return
        _state.update { it.copy(running = true) }
        jobs = listOf(
            scope.launch {
                monitor.snapshots().collect { snap ->
                    _state.update { it.copy(snapshot = snap) }
                    log.record(snap, _state.value.fix)
                    resolveStations(snap)
                }
            },
            scope.launch {
                location.fixes()
                    .catch { /* No location permission or providers off: keep cells running. */ }
                    .collect { fix -> _state.update { it.copy(fix = fix) } }
            },
            scope.launch(Dispatchers.IO) {
                while (true) {
                    delay(30_000)
                    runCatching { dictionary.flush() }
                }
            },
        )
    }

    @Synchronized
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        scope.launch(Dispatchers.IO) { runCatching { dictionary.flush() } }
        _state.update { it.copy(running = false) }
    }

    /** Looks up BTMap stations for serving cells that were not resolved yet. */
    private fun resolveStations(snap: NetworkSnapshot) {
        val keys = snap.sims.flatMap { sim -> sim.cells.filter(Cell::isServing) }
            .mapNotNull(NodeKey::of)
            .distinct()
            .filter { it !in _state.value.stations }
        val now = System.currentTimeMillis()
        for (key in keys) {
            val claimed = synchronized(inFlight) {
                now - (failedAt[key] ?: 0L) >= RETRY_AFTER_MS && inFlight.add(key)
            }
            if (!claimed) continue
            scope.launch {
                try {
                    val station = api.stationFor(key)
                    synchronized(inFlight) { failedAt.remove(key) }
                    _state.update { it.copy(stations = it.stations + (key to station), apiError = null) }
                } catch (e: Exception) {
                    // Leave the key unresolved; it is retried after RETRY_AFTER_MS.
                    synchronized(inFlight) { failedAt[key] = System.currentTimeMillis() }
                    _state.update { it.copy(apiError = e.message ?: e.javaClass.simpleName) }
                } finally {
                    synchronized(inFlight) { inFlight.remove(key) }
                }
            }
        }
    }

    private companion object {
        const val RETRY_AFTER_MS = 30_000L
    }
}
