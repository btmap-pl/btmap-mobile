package pl.btmap.mobile

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import pl.btmap.core.BtmapCore
import kotlinx.coroutines.withContext
import pl.btmap.core.stations.StationSource
import kotlinx.coroutines.flow.combine
import pl.btmap.mobile.settings.SettingsStore
import pl.btmap.mobile.tools.BandStore
import pl.btmap.mobile.tools.ShizukuBridge
import pl.btmap.mobile.work.DatabaseWorker

class BtmapApp : Application() {
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val shizuku: ShizukuBridge by lazy { ShizukuBridge(this) }
    val bands: BandStore by lazy { BandStore(this, core, scope) }
    val core: BtmapCore by lazy {
        BtmapCore(this, settings.settings.value.apiBaseUrl).also(::applySettings)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Keeps the core in sync with settings that affect it. */
    private fun applySettings(core: BtmapCore) {
        // Start collecting the bands the modem reports.
        scope.launch { bands }
        scope.launch {
            settings.settings.map { it.liveRefreshMs }.distinctUntilChanged().collect(core::setLiveRefreshMs)
        }
        scope.launch {
            settings.settings.map { it.apiBaseUrl }.distinctUntilChanged().collect(core::setApiBaseUrl)
        }
        scope.launch {
            settings.settings.map { it.database }.distinctUntilChanged().collect { source ->
                core.stations.setSource(source)
                // Fetch right away only what is missing or outdated; fresh operators are never re-downloaded.
                val s = settings.settings.value
                val needed = withContext(Dispatchers.IO) {
                    when (source) {
                        StationSource.BTMAP -> core.stations.info(source) == null
                        StationSource.BTSEARCH -> core.stations.staleOperators(source, s.btsearchMncs, DatabaseWorker.maxAgeMs(s)).isNotEmpty()
                    }
                }
                if (needed) DatabaseWorker.runNow(this@BtmapApp, s)
            }
        }
        // With Shizuku switched on and authorised, unlock modem access (DUMP) without a computer.
        scope.launch {
            combine(settings.settings.map { it.shizukuEnabled }.distinctUntilChanged(), shizuku.status) { on, st -> on to st }
                .collect { (on, st) -> if (on && st == ShizukuBridge.Status.READY) shizuku.grantDump() }
        }
        scope.launch {
            settings.settings.map { Triple(it.dbRefreshDays, it.dbWifiOnly, it.database) }.distinctUntilChanged().collect {
                DatabaseWorker.schedule(this@BtmapApp, settings.settings.value)
            }
        }
    }
}
