package pl.btmap.mobile.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import pl.btmap.core.LiveState
import pl.btmap.core.LogEntry
import pl.btmap.core.api.Station
import pl.btmap.mobile.ui.theme.Btmap
import pl.btmap.mobile.ui.theme.ThemeMode

private enum class Tab(val title: String) { LIVE("Live"), LOG("Log") }

@Composable
fun AppShell(
    state: LiveState,
    log: List<LogEntry>,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    stationUrl: (Station) -> String,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.LIVE) }
    val nav = WindowInsets.navigationBars.asPaddingValues()
    val content = PaddingValues(top = 4.dp, bottom = nav.calculateBottomPadding() + 96.dp)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            TopBar(tab.title, state.running, themeMode, onThemeChange)
            when (tab) {
                Tab.LIVE -> LiveScreen(state, stationUrl, content)
                Tab.LOG -> LogScreen(log, state, content)
            }
        }
        BottomBar(
            current = tab,
            onSelect = { tab = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = nav.calculateBottomPadding() + 14.dp),
        )
    }
}

@Composable
private fun TopBar(title: String, running: Boolean, mode: ThemeMode, onThemeChange: (ThemeMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (running) Btmap.colors.good else Btmap.colors.faint))
        Spacer(Modifier.weight(1f))
        val next = when (mode) {
            ThemeMode.SYSTEM -> ThemeMode.LIGHT
            ThemeMode.LIGHT -> ThemeMode.DARK
            ThemeMode.DARK -> ThemeMode.SYSTEM
        }
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable { onThemeChange(next) }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThemeIcon(MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(6.dp))
            Text(
                when (mode) { ThemeMode.SYSTEM -> "Auto"; ThemeMode.LIGHT -> "Jasny"; ThemeMode.DARK -> "Ciemny" },
                style = MaterialTheme.typography.labelMedium, color = Btmap.colors.muted,
            )
        }
    }
}

@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit, modifier: Modifier) {
    val colors = Btmap.colors
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier
            .shadow(16.dp, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
            .clip(shape)
            .background(if (colors.isDark) colors.cardRaised else MaterialTheme.colorScheme.surface)
            .border(1.dp, colors.divider, shape)
            .padding(6.dp),
    ) {
        Tab.entries.forEach { tab ->
            val selected = tab == current
            val bg by animateColorAsState(if (selected) colors.accent.copy(alpha = 0.16f) else Color.Transparent, label = "tab")
            val fg = if (selected) colors.accent else colors.muted
            Column(
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(bg)
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 26.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (tab) {
                    Tab.LIVE -> LiveIcon(fg)
                    Tab.LOG -> LogIcon(fg)
                }
                Text(tab.title, style = MaterialTheme.typography.labelMedium, color = fg)
            }
        }
    }
}
