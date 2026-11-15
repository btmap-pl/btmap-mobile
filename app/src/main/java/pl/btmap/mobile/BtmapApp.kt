package pl.btmap.mobile

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import pl.btmap.core.BtmapCore
import pl.btmap.mobile.settings.SettingsStore

class BtmapApp : Application() {
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val core: BtmapCore by lazy {
        BtmapCore(this, settings.settings.value.apiBaseUrl).also(::applySettings)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Keeps the core in sync with settings that affect it. */
    private fun applySettings(core: BtmapCore) {
        scope.launch {
            settings.settings.map { it.liveRefreshMs }.distinctUntilChanged().collect(core::setLiveRefreshMs)
        }
        scope.launch {
            settings.settings.map { it.apiBaseUrl }.distinctUntilChanged().collect(core::setApiBaseUrl)
        }
    }
}
