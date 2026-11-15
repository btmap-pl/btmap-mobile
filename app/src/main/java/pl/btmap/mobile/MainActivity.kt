package pl.btmap.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.btmap.core.BtmapCore
import pl.btmap.core.telephony.CellMonitor
import pl.btmap.mobile.ui.LiveScreen
import pl.btmap.mobile.ui.PermissionScreen
import pl.btmap.mobile.ui.theme.BtmapTheme

class MainActivity : ComponentActivity() {
    private val core: BtmapCore get() = (application as BtmapApp).core

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BtmapTheme {
                Root(core)
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
private fun Root(core: BtmapCore) {
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
        LiveScreen(state)
    }
}
