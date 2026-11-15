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
import pl.btmap.core.model.Rat
import pl.btmap.core.radio.Bands
import pl.btmap.core.radio.Operators
import java.util.Locale

/** State of the database download, for the settings screen. */
sealed interface ImportState {
    data object Idle : ImportState
    data class Running(val source: StationSource, val bytes: Long, val cells: Int) : ImportState
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

    /** Station of a node in the selected database, or null. Call off the main thread. */
    fun find(key: NodeKey): Station? {
        val source = _source.value
        val site = runCatching {
            db.findNode(source, key.plmn.mcc.toInt(), key.plmn.mnc.toInt(), key.rat, key.nodeId)
        }.getOrNull() ?: return null
        val bands = site.bands.ifEmpty {
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
            // BTMap stations open on btmap.pl; BTSearch has no public station link, so a map pin.
            url = if (source == StationSource.BTMAP) null else geo,
        )
    }

    /**
     * Downloads [source] and replaces its data. Retries server errors (BTSearch often times out)
     * with growing pauses; the old data stays usable until a download completes.
     */
    suspend fun download(source: StationSource, apiBase: String, btsearchMncs: Set<Int>): Result<SourceInfo> =
        importLock.withLock {
            withContext(Dispatchers.IO) {
                val detail = if (source == StationSource.BTSEARCH) btsearchMncs.sorted().joinToString(",") else null
                var lastError: Throwable? = null
                for (attempt in 0 until ATTEMPTS) {
                    if (attempt > 0) delay(RETRY_DELAYS_MS[attempt - 1])
                    _state.value = ImportState.Running(source, 0, 0)
                    val ctx = currentCoroutineContext()
                    val progress = ImportProgress { bytes, cells -> _state.value = ImportState.Running(source, bytes, cells) }
                    val result = runCatching {
                        db.replace(source, detail) { writer ->
                            when (source) {
                                StationSource.BTSEARCH -> BtsearchImporter.import(writer, btsearchMncs, progress) { !ctx.isActive }
                                StationSource.BTMAP -> BtmapImporter.import(writer, apiBase, progress) { !ctx.isActive }
                            }
                        }
                    }
                    if (result.isSuccess) {
                        _state.value = ImportState.Idle
                        if (_source.value == source) _version.update { it + 1 }
                        return@withContext result
                    }
                    lastError = result.exceptionOrNull()
                    if (!ctx.isActive) break
                    // Client errors and empty files will not get better by retrying.
                    if (lastError is ImportException && lastError.message?.contains("Serwer") != true) break
                }
                val message = lastError?.message ?: lastError?.javaClass?.simpleName ?: "Błąd"
                _state.value = ImportState.Failed(source, message, System.currentTimeMillis())
                Result.failure(lastError ?: ImportException(message))
            }
        }

    fun clear(source: StationSource) {
        runBlocking(Dispatchers.IO) { importLock.withLock { db.clear(source) } }
        if (_source.value == source) _version.update { it + 1 }
    }

    private companion object {
        const val ATTEMPTS = 3
        val RETRY_DELAYS_MS = longArrayOf(30_000, 120_000)
    }
}
