package pl.btmap.mobile.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import pl.btmap.mobile.BuildConfig
import pl.btmap.mobile.ui.theme.ThemeMode

/** User settings. Monitoring in the background is not a setting: the app adapts on its own. */
data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Cell refresh while the app is on screen. */
    val liveRefreshMs: Long = 2_000,
    val apiBaseUrl: String = BuildConfig.API_BASE_URL,
    val notificationSignal: Boolean = true,
    val notificationStation: Boolean = true,
    val notificationOtherSim: Boolean = true,
    /** Show neighbour cells on the Live screen. */
    val showNeighbours: Boolean = true,
) {
    companion object {
        val REFRESH_OPTIONS = listOf(1_000L, 2_000L, 5_000L)
    }
}

/** Settings in SharedPreferences, exposed as a flow so the UI, core and notification follow changes. */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    init {
        migrateTheme(context)
    }

    fun update(change: (Settings) -> Settings) {
        _settings.update(change)
        write(_settings.value)
    }

    private fun read(): Settings {
        val d = Settings()
        return Settings(
            theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", null)!!) }.getOrDefault(d.theme),
            liveRefreshMs = prefs.getLong("liveRefreshMs", d.liveRefreshMs),
            apiBaseUrl = prefs.getString("apiBaseUrl", null)?.takeIf { it.isNotBlank() } ?: d.apiBaseUrl,
            notificationSignal = prefs.getBoolean("notificationSignal", d.notificationSignal),
            notificationStation = prefs.getBoolean("notificationStation", d.notificationStation),
            notificationOtherSim = prefs.getBoolean("notificationOtherSim", d.notificationOtherSim),
            showNeighbours = prefs.getBoolean("showNeighbours", d.showNeighbours),
        )
    }

    private fun write(s: Settings) {
        prefs.edit()
            .putString("theme", s.theme.name)
            .putLong("liveRefreshMs", s.liveRefreshMs)
            .putString("apiBaseUrl", s.apiBaseUrl)
            .putBoolean("notificationSignal", s.notificationSignal)
            .putBoolean("notificationStation", s.notificationStation)
            .putBoolean("notificationOtherSim", s.notificationOtherSim)
            .putBoolean("showNeighbours", s.showNeighbours)
            .apply()
    }

    /** The theme used to live in the "ui" preferences file. */
    private fun migrateTheme(context: Context) {
        val old = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
        val theme = old.getString("theme", null) ?: return
        if (!prefs.contains("theme")) update { it.copy(theme = runCatching { ThemeMode.valueOf(theme) }.getOrDefault(it.theme)) }
        old.edit().remove("theme").apply()
    }
}
