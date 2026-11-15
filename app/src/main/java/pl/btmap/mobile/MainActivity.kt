package pl.btmap.mobile

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.btmap.core.BtmapCore
import pl.btmap.core.telephony.CellMonitor
import pl.btmap.mobile.ui.AppShell
import pl.btmap.mobile.ui.PermissionScreen
import pl.btmap.mobile.ui.theme.BtmapTheme
import pl.btmap.mobile.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    private val core: BtmapCore get() = (application as BtmapApp).core

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("ui", Context.MODE_PRIVATE)
        setContent {
            var mode by remember {
                mutableStateOf(runCatching { ThemeMode.valueOf(prefs.getString("theme", null)!!) }.getOrDefault(ThemeMode.SYSTEM))
            }
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            BtmapTheme(mode) {
                Root(core, mode) {
                    mode = it
                    prefs.edit().putString("theme", it.name).apply()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (core.hasPermissions()) core.start()
    }

    override fun onStop() {
        // Foreground-only for now; a foreground service will keep logging in the background later.
        core.stop()
        super.onStop()
    }
}

@Composable
private fun Root(core: BtmapCore, mode: ThemeMode, onThemeChange: (ThemeMode) -> Unit) {
    var granted by remember { mutableStateOf(core.hasPermissions()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = core.hasPermissions()
        if (granted) core.start()
    }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(CellMonitor.REQUIRED_PERMISSIONS)
    }
    if (!granted) {
        PermissionScreen(onRequest = { launcher.launch(CellMonitor.REQUIRED_PERMISSIONS) })
    } else {
        val state by core.state.collectAsStateWithLifecycle()
        val log by core.log.entries.collectAsStateWithLifecycle()
        AppShell(state, log, mode, onThemeChange, core.api::stationUrl, core::clearLog, core::ensureStation)
    }
}
