package pl.btmap.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import pl.btmap.core.PowerMode
import pl.btmap.mobile.service.MonitorService
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
import pl.btmap.mobile.settings.Settings

class MainActivity : ComponentActivity() {
    private val core: BtmapCore get() = (application as BtmapApp).core

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = (application as BtmapApp).settings
        setContent {
            val settings by store.settings.collectAsStateWithLifecycle()
            val dark = when (settings.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            BtmapTheme(settings.theme) {
                Root(core, settings, store::update)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        MonitorService.uiVisible = true
        core.setMode(PowerMode.LIVE)
        if (core.hasPermissions()) {
            core.start()
            // Always monitors in the background; the power mode adapts to screen and app state.
            MonitorService.start(this)
        }
    }

    override fun onStop() {
        MonitorService.uiVisible = false
        super.onStop()
    }
}

@Composable
private fun Root(core: BtmapCore, settings: Settings, onSettingsChange: ((Settings) -> Settings) -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(core.hasPermissions()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = core.hasPermissions()
        if (granted) {
            core.start()
            MonitorService.start(context)
        }
    }
    val permissions = remember {
        CellMonitor.REQUIRED_PERMISSIONS + if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
    }
    LaunchedEffect(Unit) {
        val notificationsMissing = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (!granted || notificationsMissing) launcher.launch(permissions)
    }
    if (!granted) {
        PermissionScreen(onRequest = { launcher.launch(permissions) })
    } else {
        val state by core.state.collectAsStateWithLifecycle()
        val log by core.log.entries.collectAsStateWithLifecycle()
        AppShell(state, log, settings, onSettingsChange, core, core.api::stationUrl, core::clearLog, core::ensureStation)
    }
}
