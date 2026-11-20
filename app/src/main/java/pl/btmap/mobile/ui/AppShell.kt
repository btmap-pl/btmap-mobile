package pl.btmap.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import pl.btmap.core.model.CellIdentity
import pl.btmap.mobile.ui.theme.Btmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.btmap.core.BtmapCore
import pl.btmap.mobile.settings.Settings

private enum class Tab(val title: String) { LIVE("Live"), MAP("Mapa"), LOG("Log"), SETTINGS("Ustawienia") }

/** What fills the content area: a tab, or the details of one log entry. */
private sealed interface Screen {
    val depth: Int
    data class TabScreen(val tab: Tab) : Screen { override val depth = 0 }
    data class Detail(val key: String) : Screen { override val depth = 1 }
}

@Composable
fun AppShell(
    state: LiveState,
    log: List<LogEntry>,
    settings: Settings,
    onSettingsChange: ((Settings) -> Settings) -> Unit,
    core: BtmapCore,
    stationUrl: (Station) -> String,
    onClearLog: () -> Unit,
    onNeedStation: (CellIdentity) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.LIVE) }
    var detailKey by rememberSaveable { mutableStateOf<String?>(null) }
    val screen: Screen = detailKey?.let { Screen.Detail(it) } ?: Screen.TabScreen(tab)
    BackHandler(enabled = detailKey != null) { detailKey = null }

    val nav = WindowInsets.navigationBars.asPaddingValues()
    val tabPadding = PaddingValues(top = 4.dp, bottom = nav.calculateBottomPadding() + 96.dp)
    val detailPadding = PaddingValues(top = 4.dp, bottom = nav.calculateBottomPadding() + 24.dp)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            TopBar(
                title = if (detailKey != null) "Szczegóły" else tab.title,
                running = state.running,
            )
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    val initial = initialState
                    val target = targetState
                    when {
                        // Push / pop to details: slide over, with depth.
                        target.depth > initial.depth ->
                            (slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { it / 3 } + fadeIn(tween(260))) togetherWith
                                (fadeOut(tween(180)) + scaleOut(tween(300), targetScale = 0.94f))
                        target.depth < initial.depth ->
                            (fadeIn(tween(260)) + scaleIn(tween(300), initialScale = 0.94f)) togetherWith
                                (slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { it / 3 } + fadeOut(tween(220)))
                        // Tab switch: slide in the direction of the tab.
                        else -> {
                            val forward = (target as Screen.TabScreen).tab.ordinal > (initial as Screen.TabScreen).tab.ordinal
                            val dir = if (forward) 1 else -1
                            (slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { dir * it / 5 } + fadeIn(tween(240))) togetherWith
                                (slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { -dir * it / 5 } + fadeOut(tween(160)))
                        }
                    }
                },
                contentKey = { if (it is Screen.TabScreen) it.tab.name else "detail" },
                label = "screen",
            ) { target ->
                when (target) {
                    is Screen.TabScreen -> when (target.tab) {
                        Tab.LIVE -> LiveScreen(state, stationUrl, tabPadding, settings.showNeighbours)
                        Tab.MAP -> MapScreen(state, settings.apiBaseUrl, stationUrl, tabPadding)
                        Tab.SETTINGS -> SettingsScreen(settings, onSettingsChange, core, log.size, tabPadding)
                        Tab.LOG -> {
                            val events by core.events.events.collectAsStateWithLifecycle()
                            LogTabs(events, tabPadding) { pad -> LogScreen(log, state, pad, { detailKey = it.key }, onClearLog, onNeedStation) }
                        }
                    }
                    is Screen.Detail -> {
                        // Keep showing the last known entry even if the log was cleared meanwhile.
                        val entry = log.firstOrNull { it.key == target.key }
                        if (entry != null) LogDetailScreen(entry, state, stationUrl, { detailKey = null }, detailPadding)
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = detailKey == null,
            enter = slideInVertically(tween(300)) { it } + fadeIn(),
            exit = slideOutVertically(tween(250)) { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = nav.calculateBottomPadding() + 14.dp),
        ) {
            BottomBar(current = tab, onSelect = { tab = it })
        }
    }
}

@Composable
private fun TopBar(title: String, running: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = title,
            transitionSpec = {
                (slideInVertically(tween(260)) { it / 2 } + fadeIn(tween(260))) togetherWith
                    (slideOutVertically(tween(200)) { -it / 2 } + fadeOut(tween(160))) using
                    SizeTransform(clip = false)
            },
            label = "title",
        ) { Text(it, style = MaterialTheme.typography.headlineMedium) }
        Spacer(Modifier.width(4.dp))
        PulseDot(if (running) Btmap.colors.good else Btmap.colors.faint, active = running)
    }
}

@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
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
            val bg by animateColorAsState(if (selected) colors.accent.copy(alpha = 0.16f) else Color.Transparent, tween(250), label = "tabBg")
            val fg by animateColorAsState(if (selected) colors.accent else colors.muted, tween(250), label = "tabFg")
            val interaction = remember { MutableInteractionSource() }
            Column(
                Modifier
                    .pressScale(interaction)
                    .clip(RoundedCornerShape(18.dp))
                    .background(bg)
                    .clickable(interaction, LocalIndication.current) { onSelect(tab) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (tab) {
                    Tab.LIVE -> LiveIcon(fg)
                    Tab.MAP -> MapIcon(fg)
                    Tab.LOG -> LogIcon(fg)
                    Tab.SETTINGS -> SettingsIcon(fg)
                }
                Text(tab.title, style = MaterialTheme.typography.labelMedium, color = fg)
            }
        }
    }
}

