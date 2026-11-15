package pl.btmap.mobile.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.drawscope.Stroke
import pl.btmap.core.model.Plmn
import pl.btmap.core.radio.Operators
import pl.btmap.core.stations.DbOperator
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.LinearProgressIndicator
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.btmap.core.stations.DbOperators
import pl.btmap.core.stations.ImportState
import pl.btmap.core.stations.OperatorProgress
import androidx.compose.animation.Crossfade
import pl.btmap.core.stations.StationSource
import pl.btmap.mobile.work.DatabaseWorker
import pl.btmap.mobile.tools.ShizukuBridge
import pl.btmap.mobile.tools.BandPreset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.btmap.core.BtmapCore
import pl.btmap.core.api.BtmapApi
import pl.btmap.mobile.BuildConfig
import pl.btmap.mobile.settings.Settings
import pl.btmap.mobile.ui.theme.Btmap
import pl.btmap.mobile.ui.theme.ThemeMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings categories, shown as a menu; each opens its own page. */
private enum class SettingsPage(val title: String) {
    DATABASE("Baza danych"),
    APPEARANCE("Wygląd"),
    MEASUREMENTS("Pomiary"),
    NOTIFICATION("Powiadomienie"),
    DATA("Dane"),
    BANDS("Wymuszanie pasm"),
    TOOLS("Narzędzia"),
    ABOUT("O aplikacji"),
}

@Composable
fun SettingsScreen(
    settings: Settings,
    onChange: ((Settings) -> Settings) -> Unit,
    core: BtmapCore,
    logSize: Int,
    contentPadding: PaddingValues,
) {
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    BackHandler(enabled = page != null) { page = null }

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it / 3 } + fadeIn(tween(250))) togetherWith
                    (fadeOut(tween(160)) + scaleOut(tween(300), targetScale = 0.95f))
            } else {
                (fadeIn(tween(250)) + scaleIn(tween(300), initialScale = 0.95f)) togetherWith
                    (slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { it / 3 } + fadeOut(tween(200)))
            }
        },
        label = "settingsPage",
    ) { current ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (current == null) {
                SettingsMenu(settings, core, logSize) { page = it }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.enterAnimation(0)) {
                    BackChip { page = null }
                    Spacer(Modifier.width(14.dp))
                    Text(current.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                if (current == SettingsPage.DATABASE) {
                    DatabasePage(settings, onChange, core)
                } else if (current == SettingsPage.BANDS) {
                    BandsPage(settings) { page = SettingsPage.TOOLS }
                } else Card(Modifier.enterAnimation(1)) {
                    when (current) {
                        SettingsPage.DATABASE, SettingsPage.BANDS -> Unit
                        SettingsPage.APPEARANCE -> AppearancePage(settings, onChange)
                        SettingsPage.MEASUREMENTS -> {
                            ModemAccess(core)
                            Spacer(Modifier.height(18.dp))
                            MeasurementsPage(settings, onChange)
                        }
                        SettingsPage.NOTIFICATION -> NotificationPage(settings, onChange)
                        SettingsPage.DATA -> DataSettings(core, logSize)
                        SettingsPage.TOOLS -> ToolsPage(settings, onChange)
                        SettingsPage.ABOUT -> About()
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsMenu(settings: Settings, core: BtmapCore, logSize: Int, onOpen: (SettingsPage) -> Unit) {
    val accent = Btmap.colors.accent
    val theme = when (settings.theme) { ThemeMode.SYSTEM -> "Auto"; ThemeMode.LIGHT -> "Jasny"; ThemeMode.DARK -> "Ciemny" }
    val notification = listOfNotNull(
        "sygnał".takeIf { settings.notificationSignal },
        "stacja".takeIf { settings.notificationStation },
        "druga karta".takeIf { settings.notificationOtherSim },
    ).joinToString(", ").ifEmpty { "tylko pasma" }
    val host = runCatching { Uri.parse(settings.apiBaseUrl).host + (Uri.parse(settings.apiBaseUrl).port.takeIf { it > 0 }?.let { ":$it" } ?: "") }
        .getOrNull() ?: settings.apiBaseUrl

    val dbState by core.stations.state.collectAsStateWithLifecycle()
    val dbVersion by core.stations.version.collectAsStateWithLifecycle()
    val dbInfo = remember(settings.database, dbState, dbVersion) { core.stations.info(settings.database) }
    val dbSummary = when (val st = dbState) {
        is ImportState.Running -> "${st.source.label} · pobieram…"
        else -> settings.database.label + " · " + (dbInfo?.let { "${formatCount(it.sites)} stacji" } ?: "nie pobrano")
    }
    val items: List<Triple<SettingsPage, String, @Composable (Color) -> Unit>> = listOf(
        Triple(SettingsPage.DATABASE, dbSummary) { c -> DatabaseIcon(c) },
        Triple(SettingsPage.APPEARANCE, "Motyw: $theme · sąsiednie ${if (settings.showNeighbours) "widoczne" else "ukryte"}") { c -> ThemeIcon(c) },
        Triple(SettingsPage.MEASUREMENTS, "Odświeżanie co ${settings.liveRefreshMs / 1000} s · w tle oszczędnie") { c -> LiveIcon(c) },
        Triple(SettingsPage.NOTIFICATION, "Pasma, $notification") { c -> Icon(Icons.Outlined.Notifications, null, tint = c) },
        Triple(SettingsPage.DATA, "$logSize w logu · ${core.dictionary.size} nauczonych") { c -> LogIcon(c) },
        Triple(SettingsPage.BANDS, bandsSummary(settings)) { c -> BandLockIcon(c) },
        Triple(SettingsPage.TOOLS, toolsSummary(settings)) { c -> Icon(Icons.Outlined.Build, null, tint = c) },
        Triple(SettingsPage.ABOUT, "Wersja ${BuildConfig.VERSION_NAME} · MIT") { c -> Icon(Icons.Outlined.Info, null, tint = c) },
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { i, (page, subtitle, icon) ->
            MenuRow(page.title, subtitle, Modifier.enterAnimation(i), { icon(accent) }) { onOpen(page) }
        }
    }
}

/** Icon tile + title + one-line summary, like a launcher menu entry. */
@Composable
private fun MenuRow(title: String, subtitle: String, modifier: Modifier, icon: @Composable () -> Unit, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .pressScale(interaction)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(interaction, LocalIndication.current, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Btmap.colors.accent.copy(alpha = if (Btmap.colors.isDark) 0.12f else 0.10f)),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Btmap.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Chevron(Btmap.colors.faint)
    }
}

@Composable
private fun Chevron(color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val w = 2.dp.toPx()
        drawLine(color, Offset(size.width * 0.35f, size.height * 0.2f), Offset(size.width * 0.7f, size.height / 2), w, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.7f, size.height / 2), Offset(size.width * 0.35f, size.height * 0.8f), w, StrokeCap.Round)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DatabasePage(settings: Settings, onChange: ((Settings) -> Settings) -> Unit, core: BtmapCore) {
    val context = LocalContext.current
    val state by core.stations.state.collectAsStateWithLifecycle()
    val version by core.stations.version.collectAsStateWithLifecycle()
    val info = remember(settings.database, state, version) { core.stations.info(settings.database) }
    var confirmDelete by remember { mutableStateOf(false) }
    val running = state as? ImportState.Running
    val failed = (state as? ImportState.Failed)?.takeIf { it.source == settings.database }
    val downloadedMncs = info?.detail?.split(',')?.mapNotNull(String::toIntOrNull)?.toSet()
    val outdatedSelection = settings.database == StationSource.BTSEARCH && info != null && downloadedMncs != settings.btsearchMncs

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // ---- Status ----
        Card(Modifier.enterAnimation(1)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(Btmap.colors.accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) { DatabaseIcon(Btmap.colors.accent) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(settings.database.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            running != null -> "Pobieranie…"
                            failed != null -> "Pobieranie nie powiodło się"
                            info == null -> "Nie pobrano"
                            else -> "Zaktualizowano ${relativeTime(info.updatedAtMillis)}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            failed != null -> Btmap.colors.bad
                            running != null -> Btmap.colors.accent
                            else -> Btmap.colors.muted
                        },
                    )
                }
                StatusDot(
                    when {
                        running != null -> Btmap.colors.accent
                        failed != null -> Btmap.colors.bad
                        info == null || outdatedSelection -> Btmap.colors.poor
                        else -> Btmap.colors.good
                    },
                    pulsing = running != null,
                )
            }

            AnimatedContent(targetState = running != null, label = "dbStatus") { isRunning ->
                Column {
                    Spacer(Modifier.height(16.dp))
                    if (isRunning && running != null && running.operators.isNotEmpty()) {
                        OperatorProgressList(running.operators)
                        running.waitingUntil?.let { until -> RateLimitCountdown(until) }
                    } else if (isRunning && running != null) {
                        val fraction = if (running.total > 0) running.done.toFloat() / running.total else 0f
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.displaySmall.copy(fontSize = MaterialTheme.typography.headlineLarge.fontSize), color = Btmap.colors.accent)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "${formatCount(running.done)} / ${if (running.total > 0) formatCount(running.total) else "?"} stacji",
                                style = MaterialTheme.typography.bodyMedium, color = Btmap.colors.muted,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Meter(fraction, Btmap.colors.accent, Modifier.fillMaxWidth(), height = 8.dp)
                        Spacer(Modifier.height(8.dp))
                        Text("${formatCount(running.cells)} komórek zapisanych", style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted)
                        running.waitingUntil?.let { until -> RateLimitCountdown(until) }
                    } else {
                        if (settings.database == StationSource.BTSEARCH && info != null) {
                            val perOperator = remember(info) { core.stations.cellsPerOperator(StationSource.BTSEARCH) }
                            OperatorProgressList(
                                DbOperators.ALL.filter { it.mnc in settings.btsearchMncs || it.mnc in perOperator }.map { op ->
                                    val cells = perOperator[op.mnc]
                                    OperatorProgress(
                                        op.mnc, op.name,
                                        if (cells != null) OperatorProgress.Status.DONE else OperatorProgress.Status.PENDING,
                                        cells = cells ?: 0,
                                    )
                                },
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile("Stacje", info?.let { formatCount(it.sites) } ?: "—", Modifier.weight(1f))
                            StatTile("Komórki", info?.let { formatCount(it.cells) } ?: "—", Modifier.weight(1f))
                            StatTile(
                                "Następna",
                                if (settings.dbRefreshDays == 0) "ręcznie"
                                else info?.let { relativeFuture(it.updatedAtMillis + settings.dbRefreshDays * 86_400_000L) } ?: "teraz",
                                Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            failed?.let {
                Spacer(Modifier.height(10.dp))
                Text(it.message, style = MaterialTheme.typography.bodySmall, color = Btmap.colors.bad)
            }
            if (outdatedSelection && running == null) {
                Spacer(Modifier.height(10.dp))
                Text("Zmieniono operatorów — zaktualizuj bazę, aby to zastosować.", style = MaterialTheme.typography.bodySmall, color = Btmap.colors.poor)
            }
            if (info != null && info.cells == 0 && settings.database == StationSource.BTMAP && running == null) {
                Spacer(Modifier.height(10.dp))
                Hint("W BTMap nie ma jeszcze stacji spoza UKE. Pojawią się, gdy społeczność je doda.")
            }

            Spacer(Modifier.height(16.dp))
            if (running != null) {
                WideButton("Anuluj pobieranie", danger = true) { DatabaseWorker.cancel(context) }
            } else {
                val stale = remember(info, settings.btsearchMncs, settings.dbRefreshDays, settings.database) {
                    if (settings.database == StationSource.BTSEARCH)
                        core.stations.staleOperators(StationSource.BTSEARCH, settings.btsearchMncs, DatabaseWorker.maxAgeMs(settings))
                    else emptySet()
                }
                when {
                    info == null -> WideButton("Pobierz bazę", primary = true) { DatabaseWorker.runNow(context, settings) }
                    stale.isNotEmpty() -> {
                        val names = DbOperators.ALL.filter { it.mnc in stale }.joinToString(", ") { it.name }
                        WideButton("Pobierz: $names", primary = true) { DatabaseWorker.runNow(context, settings) }
                    }
                    else -> WideButton("Odśwież wszystko", primary = false) { DatabaseWorker.runNow(context, settings, force = true) }
                }
                if (settings.dbWifiOnly) {
                    Spacer(Modifier.height(6.dp))
                    Hint("Ruszy po połączeniu z Wi-Fi.")
                }
            }
        }

        // ---- Source ----
        SectionLabel("Źródło", 2)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.enterAnimation(2)) {
            SourceCard(
                title = "BTMap",
                subtitle = "Stacje społeczności, bez danych z UKE",
                selected = settings.database == StationSource.BTMAP,
                modifier = Modifier.weight(1f),
            ) { onChange { it.copy(database = StationSource.BTMAP) } }
            SourceCard(
                title = "BTSearch",
                subtitle = "Baza btsearch.pl z pełnymi ID komórek",
                selected = settings.database == StationSource.BTSEARCH,
                modifier = Modifier.weight(1f),
            ) { onChange { it.copy(database = StationSource.BTSEARCH) } }
        }

        // ---- Operators (BTSearch) ----
        AnimatedVisibility(settings.database == StationSource.BTSEARCH, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Operatorzy", 3)
                Card(Modifier.enterAnimation(3)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DbOperators.ALL.forEach { op ->
                            OperatorChip(op, selected = op.mnc in settings.btsearchMncs) {
                                onChange {
                                    val on = op.mnc in it.btsearchMncs
                                    val next = if (on) it.btsearchMncs - op.mnc else it.btsearchMncs + op.mnc
                                    it.copy(btsearchMncs = next.ifEmpty { it.btsearchMncs })
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Hint("NetWorkS! to wspólna sieć T-Mobile i Orange; jej komórki trafiają też do tych operatorów.")
                }
            }
        }

        // ---- Schedule ----
        SectionLabel("Aktualizacja", 4)
        Card(Modifier.enterAnimation(4)) {
            Segmented(
                options = Settings.DB_REFRESH_OPTIONS.map { (d, l) -> d to l.replace("Co ", "") },
                selected = settings.dbRefreshDays,
                onSelect = { days -> onChange { it.copy(dbRefreshDays = days) } },
            )
            Spacer(Modifier.height(6.dp))
            ToggleRow("Tylko przez Wi-Fi", "Bez zużywania danych komórkowych", settings.dbWifiOnly) { on ->
                onChange { it.copy(dbWifiOnly = on) }
            }
        }

        if (info != null && running == null) {
            Row(Modifier.fillMaxWidth().enterAnimation(5), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { confirmDelete = true }) {
                    Text("Usuń pobraną bazę", color = Btmap.colors.bad, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Usunąć bazę ${settings.database.label}?") },
            text = { Text("Stacje przestaną być rozpoznawane, dopóki baza nie zostanie pobrana ponownie.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    core.stations.clear(settings.database)
                }) { Text("Usuń", color = Btmap.colors.bad) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Anuluj") } },
            containerColor = Btmap.colors.card,
        )
    }
}

/** One row per operator: done (with its cell count), downloading (with a bar), queued or failed. */
@Composable
private fun OperatorProgressList(ops: List<OperatorProgress>) {
    val colors = Btmap.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ops.forEach { op ->
            val brand = Operators.byPlmn(Plmn("260", "%02d".format(op.mnc)))?.let { colors.brand(Color(it.color)) } ?: colors.accent
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(brand))
                    Spacer(Modifier.width(10.dp))
                    Text(op.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Crossfade(op.status, label = "opStatus") { status ->
                        when (status) {
                            OperatorProgress.Status.DONE -> Text("✓ ${formatCount(op.cells)} komórek", style = MaterialTheme.typography.bodySmall, color = colors.good)
                            OperatorProgress.Status.RUNNING -> Text(
                                if (op.total > 0) "${(100 * op.done / op.total)}%" else "łączę…",
                                style = MaterialTheme.typography.bodySmall, color = colors.accent, fontWeight = FontWeight.SemiBold,
                            )
                            OperatorProgress.Status.PENDING -> Text("w kolejce", style = MaterialTheme.typography.bodySmall, color = colors.faint)
                            OperatorProgress.Status.FAILED -> Text("błąd", style = MaterialTheme.typography.bodySmall, color = colors.bad)
                        }
                    }
                }
                if (op.status == OperatorProgress.Status.RUNNING) {
                    Spacer(Modifier.height(6.dp))
                    Meter(if (op.total > 0) op.done.toFloat() / op.total else 0.02f, brand, Modifier.fillMaxWidth(), height = 6.dp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${formatCount(op.done)} / ${if (op.total > 0) formatCount(op.total) else "?"} stacji · ${formatCount(op.cells)} komórek",
                        style = MaterialTheme.typography.labelSmall, color = colors.muted,
                    )
                }
            }
        }
    }
}

/** "Waiting for the server" line with a live countdown. */
@Composable
private fun RateLimitCountdown(until: Long) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(until) {
        while (now < until) {
            kotlinx.coroutines.delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val left = ((until - now) / 1000).coerceAtLeast(0)
    Spacer(Modifier.height(8.dp))
    Text(
        "BTSearch ogranicza liczbę zapytań — wznowię za %d:%02d".format(left / 60, left % 60),
        style = MaterialTheme.typography.bodySmall, color = Btmap.colors.poor,
    )
}

@Composable
private fun SectionLabel(text: String, index: Int) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Btmap.colors.muted,
        modifier = Modifier.padding(start = 6.dp, top = 4.dp).enterAnimation(index),
    )
}

@Composable
private fun StatusDot(color: Color, pulsing: Boolean) {
    PulseDot(color, active = pulsing)
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Btmap.colors.cardRaised)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Btmap.colors.muted)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Selectable card with a radio mark, for picking the data source. */
@Composable
private fun SourceCard(title: String, subtitle: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = Btmap.colors
    val interaction = remember { MutableInteractionSource() }
    val border by animateColorAsState(if (selected) colors.accent else colors.divider, tween(250), label = "srcBorder")
    val bg by animateColorAsState(if (selected) colors.accent.copy(alpha = 0.10f) else colors.card, tween(250), label = "srcBg")
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .pressScale(interaction)
            .clip(shape)
            .background(bg)
            .border(if (selected) 1.5.dp else 1.dp, border, shape)
            .clickable(interaction, LocalIndication.current, onClick = onClick)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            RadioMark(selected)
        }
        Spacer(Modifier.height(6.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.muted, minLines = 2)
    }
}

@Composable
private fun RadioMark(selected: Boolean) {
    val color by animateColorAsState(if (selected) Btmap.colors.accent else Btmap.colors.faint, tween(250), label = "radio")
    val inner by animateFloatAsState(if (selected) 1f else 0f, tween(250), label = "radioInner")
    Canvas(Modifier.size(20.dp)) {
        drawCircle(color, radius = size.minDimension / 2 - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
        if (inner > 0f) drawCircle(color, radius = (size.minDimension / 2 - 5.dp.toPx()) * inner)
    }
}

/** Operator toggle with its brand colour. */
@Composable
private fun OperatorChip(op: DbOperator, selected: Boolean, onClick: () -> Unit) {
    val colors = Btmap.colors
    val brand = Operators.byPlmn(Plmn("260", "%02d".format(op.mnc)))?.let { colors.brand(Color(it.color)) } ?: colors.accent
    val interaction = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (selected) brand.copy(alpha = 0.16f) else Color.Transparent, tween(250), label = "opBg")
    val border by animateColorAsState(if (selected) brand.copy(alpha = 0.8f) else colors.divider, tween(250), label = "opBorder")
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .pressScale(interaction)
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .clickable(interaction, LocalIndication.current, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(if (selected) brand else colors.faint))
        Spacer(Modifier.width(8.dp))
        Text(op.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = if (selected) MaterialTheme.colorScheme.onSurface else colors.muted)
    }
}

@Composable
private fun WideButton(text: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val colors = Btmap.colors
    val interaction = remember { MutableInteractionSource() }
    val tint = if (danger) colors.bad else if (primary) colors.accent else MaterialTheme.colorScheme.onSurface
    Text(
        text,
        Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .clip(RoundedCornerShape(16.dp))
            .background(if (primary) colors.accent else tint.copy(alpha = 0.10f))
            .clickable(interaction, LocalIndication.current, onClick = onClick)
            .padding(vertical = 14.dp),
        color = if (primary) (if (colors.isDark) Color(0xFF00281E) else Color.White) else tint,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

private fun relativeTime(millis: Long): String {
    val minutes = (System.currentTimeMillis() - millis) / 60_000
    return when {
        minutes < 1 -> "przed chwilą"
        minutes < 60 -> "$minutes min temu"
        minutes < 24 * 60 -> "${minutes / 60} godz. temu"
        minutes < 48 * 60 -> "wczoraj"
        else -> "${minutes / (24 * 60)} dni temu"
    }
}

private fun relativeFuture(millis: Long): String {
    val hours = (millis - System.currentTimeMillis()) / 3_600_000
    return when {
        hours <= 0 -> "wkrótce"
        hours < 24 -> "za $hours godz."
        else -> "za ${(hours + 12) / 24} dni"
    }
}

private fun formatCount(n: Int): String = "%,d".format(Locale("pl"), n).replace('\u00A0', ' ')


@Composable
private fun bandsSummary(settings: Settings): String {
    val app = LocalContext.current.applicationContext as pl.btmap.mobile.BtmapApp
    val status by app.shizuku.status.collectAsStateWithLifecycle()
    val active by app.bands.active.collectAsStateWithLifecycle()
    if (status != ShizukuBridge.Status.READY || !settings.shizukuEnabled) return "Wymaga Shizuku"
    val restricted = active.filterValues { !it.isUnrestricted }
    return if (restricted.isEmpty()) "Wszystkie pasma odblokowane" else "Ograniczone: " + describe(restricted.values.first())
}

@Composable
private fun toolsSummary(settings: Settings): String {
    val status by (LocalContext.current.applicationContext as pl.btmap.mobile.BtmapApp).shizuku.status.collectAsStateWithLifecycle()
    return "Shizuku: " + when {
        status == ShizukuBridge.Status.READY && settings.shizukuEnabled -> "włączone"
        status == ShizukuBridge.Status.READY -> "połączone, wyłączone"
        status == ShizukuBridge.Status.NO_PERMISSION -> "wymaga połączenia"
        status == ShizukuBridge.Status.NOT_RUNNING -> "nie uruchomione"
        else -> "nie zainstalowane"
    }
}

@Composable
private fun ToolsPage(settings: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    val context = LocalContext.current
    val shizuku = (context.applicationContext as pl.btmap.mobile.BtmapApp).shizuku
    val status by shizuku.status.collectAsStateWithLifecycle()
    var hasDump by remember { mutableStateOf(shizuku.hasDump) }
    // Shizuku is started and authorised in its own app; pick up changes while this page is open.
    LaunchedEffect(Unit) {
        while (true) {
            shizuku.refresh()
            hasDump = shizuku.hasDump
            kotlinx.coroutines.delay(1_500)
        }
    }
    val ready = status == ShizukuBridge.Status.READY

    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(pl.btmap.mobile.R.drawable.shizuku_logo),
            contentDescription = "Shizuku",
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Shizuku", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulseDot(
                    when (status) {
                        ShizukuBridge.Status.READY -> Btmap.colors.good
                        ShizukuBridge.Status.NO_PERMISSION -> Btmap.colors.poor
                        else -> Btmap.colors.faint
                    },
                    active = status == ShizukuBridge.Status.NO_PERMISSION,
                )
                Text(
                    when (status) {
                        ShizukuBridge.Status.READY -> "Połączono z BTMap"
                        ShizukuBridge.Status.NO_PERMISSION -> "Działa — BTMap nie jest jeszcze podłączony"
                        ShizukuBridge.Status.NOT_RUNNING -> "Zainstalowane, usługa nie działa"
                        ShizukuBridge.Status.NOT_INSTALLED -> "Nie zainstalowane"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (ready) Btmap.colors.good else Btmap.colors.muted,
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Hint("Shizuku pozwala BTMap korzystać z uprawnień powłoki bez roota i bez komputera — np. odczytywać pasma i agregację prosto z modemu.")

    Spacer(Modifier.height(14.dp))
    when (status) {
        ShizukuBridge.Status.NOT_INSTALLED -> WideButton("Zainstaluj Shizuku", primary = true) {
            val market = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("market://details?id=${ShizukuBridge.SHIZUKU_PACKAGE}"))
            runCatching { context.startActivity(market) }.onFailure {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
            }
        }
        ShizukuBridge.Status.NOT_RUNNING -> {
            WideButton("Otwórz Shizuku", primary = true) { shizuku.openShizuku(context) }
            Spacer(Modifier.height(6.dp))
            Hint("Uruchom usługę w aplikacji Shizuku (przez debugowanie bezprzewodowe), a potem wróć tutaj.")
        }
        ShizukuBridge.Status.NO_PERMISSION -> {
            WideButton("Podłącz BTMap w Shizuku", primary = true) { shizuku.requestPermission() }
            Spacer(Modifier.height(6.dp))
            Hint("Shizuku zapyta, czy zezwolić BTMap. Możesz to też zrobić w Shizuku → Aplikacje autoryzowane.")
        }
        ShizukuBridge.Status.READY -> Unit
    }

    Spacer(Modifier.height(10.dp))
    // Unlocks only once BTMap is authorised in Shizuku.
    Box(Modifier.graphicsLayer { alpha = if (ready) 1f else 0.45f }) {
        ToggleRow(
            "Włącz Shizuku",
            if (ready) "BTMap użyje Shizuku do odczytu danych z modemu" else "Odblokuje się po podłączeniu BTMap w Shizuku",
            settings.shizukuEnabled && ready,
        ) { on -> if (ready) onChange { it.copy(shizukuEnabled = on) } }
    }
    if (ready && settings.shizukuEnabled) {
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulseDot(if (hasDump) Btmap.colors.good else Btmap.colors.poor, active = !hasDump)
            Text(
                if (hasDump) "Pasma z modemu: aktywne" else "Włączam dostęp do modemu…",
                style = MaterialTheme.typography.bodySmall, color = if (hasDump) Btmap.colors.good else Btmap.colors.muted,
            )
        }
    }
    if (ready && settings.shizukuEnabled) {
        Spacer(Modifier.height(14.dp))
        Hint("Wymuszanie pasm jest dostępne w osobnej kategorii ustawień.")
    }
}

/** Result banner shown after applying bands. */
private data class BandFeedback(val ok: Boolean, val title: String, val detail: String, val id: Long = System.nanoTime())

/**
 * Band locking page (needs Shizuku). Every band starts selected — all bands unlocked — and the user
 * deselects the ones to block. Selecting every band of a technology means no restriction for it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BandsPage(settings: Settings, onOpenTools: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as pl.btmap.mobile.BtmapApp
    val status by app.shizuku.status.collectAsStateWithLifecycle()
    val available = status == ShizukuBridge.Status.READY && settings.shizukuEnabled

    if (!available) {
        Card(Modifier.enterAnimation(1)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Btmap.colors.poor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) { BandLockIcon(Btmap.colors.poor) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Wymaga Shizuku", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Podłącz BTMap w Shizuku i włącz je w Narzędziach.", style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted)
                }
            }
            Spacer(Modifier.height(14.dp))
            WideButton("Przejdź do Narzędzi", primary = true, onClick = onOpenTools)
        }
        return
    }

    val store = app.bands
    val scope = rememberCoroutineScope()
    val live by app.core.state.collectAsStateWithLifecycle()
    val observed by store.observed.collectAsStateWithLifecycle()
    val presets by store.presets.collectAsStateWithLifecycle()
    val active by store.active.collectAsStateWithLifecycle()
    val sims = live.snapshot.sims
    var slot by rememberSaveable { mutableStateOf(sims.firstOrNull { it.isDataSim }?.slotIndex ?: 0) }
    val sim = sims.firstOrNull { it.slotIndex == slot } ?: sims.firstOrNull()
    val network = app.network
    // What the modem itself says: supported bands and the current lock (MediaTek answers directly).
    var modem by remember(slot) { mutableStateOf<pl.btmap.mobile.tools.ModemBands?>(null) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(slot, reload) { modem = network.modemBands(sim?.slotIndex ?: slot) }
    val mtk = modem?.mediatek == true
    val current = (if (mtk) modem?.current else active[sim?.slotIndex ?: 0]) ?: pl.btmap.mobile.tools.BandSelection()

    val lteBands = if (mtk && modem!!.supportedLte.isNotEmpty()) modem!!.supportedLte.sorted()
        else (pl.btmap.mobile.tools.BandLock.POLISH_LTE + observed.lte + current.lte).distinct().sorted()
    val nrBands = if (mtk && modem!!.supportedNr.isNotEmpty()) modem!!.supportedNr.sorted()
        else (pl.btmap.mobile.tools.BandLock.POLISH_NR + observed.nr + current.nr).distinct().sorted()
    val detectedLte = if (mtk) lteBands.toSet() else observed.lte
    val detectedNr = if (mtk) nrBands.toSet() else observed.nr
    var confirmReset by remember { mutableStateOf(false) }
    // Shown as "allowed": empty restriction = everything allowed.
    // Re-derived whenever the modem's answer (or the band list) changes.
    var lte by remember(slot, current, lteBands) { mutableStateOf(current.lte.ifEmpty { lteBands.toSet() }) }
    var nr by remember(slot, current, nrBands) { mutableStateOf(current.nr.ifEmpty { nrBands.toSet() }) }
    val selection = pl.btmap.mobile.tools.BandSelection(
        lte = if (lte.containsAll(lteBands)) emptySet() else lte,
        nr = if (nr.containsAll(nrBands)) emptySet() else nr,
    )
    val changed = selection != current
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<BandFeedback?>(null) }
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<BandPreset?>(null) }
    var modeBusy by remember { mutableStateOf(false) }
    LaunchedEffect(feedback, busy, modeBusy) {
        // The "in progress" banner stays until the result replaces it.
        if (feedback != null && !busy && !modeBusy) {
            kotlinx.coroutines.delay(5_000)
            feedback = null
        }
    }

    val support by network.bandLockSupport.collectAsStateWithLifecycle()
    var mode by remember(slot) { mutableStateOf(network.mode(slot)) }

    fun apply(target: pl.btmap.mobile.tools.BandSelection, label: String) {
        val s = sim ?: return
        busy = true
        feedback = if (mtk) BandFeedback(true, "Zapisuję pasma w modemie…", "Potem sprawdzam, na jakim paśmie jest SIM ${s.slotIndex + 1}")
            else BandFeedback(true, "Wymuszam i sprawdzam…", "SIM ${s.slotIndex + 1} na chwilę przejdzie na 3G, potem przez ~30 s sprawdzam, na jakim paśmie zostaje")
        scope.launch {
            val simLabel = "SIM ${s.slotIndex + 1} · " + Operators.displayName(s.registeredPlmn, s.networkOperatorName)
            val outcome = network.applyBands(s.slotIndex, s.subscriptionId, target)
            feedback = when (outcome) {
                is pl.btmap.mobile.tools.BandLockOutcome.Applied -> BandFeedback(true, "Wymuszono pasma", "$simLabel · teraz ${outcome.band}")
                is pl.btmap.mobile.tools.BandLockOutcome.Ignored -> BandFeedback(false, "Modem zignorował blokadę", "$simLabel · nadal ${outcome.band}. Ten telefon nie pozwala blokować pasm bez roota — użyj trybu sieci.")
                pl.btmap.mobile.tools.BandLockOutcome.NoService -> BandFeedback(false, "Brak zasięgu na wybranych pasmach", "$simLabel · odblokuj więcej pasm")
                pl.btmap.mobile.tools.BandLockOutcome.Restored -> BandFeedback(true, "Odblokowano wszystkie pasma", simLabel)
                is pl.btmap.mobile.tools.BandLockOutcome.Failed -> BandFeedback(false, "Nie udało się", outcome.message)
            }
            val keep = outcome is pl.btmap.mobile.tools.BandLockOutcome.Applied || outcome is pl.btmap.mobile.tools.BandLockOutcome.NoService ||
                outcome is pl.btmap.mobile.tools.BandLockOutcome.Restored
            // A lock the modem ignores is lifted again so the app never claims a restriction that is not real.
            if (outcome is pl.btmap.mobile.tools.BandLockOutcome.Ignored) {
                runCatching { pl.btmap.mobile.tools.BandLock.apply(s.subscriptionId, pl.btmap.mobile.tools.BandSelection()) }
                store.setActive(s.slotIndex, pl.btmap.mobile.tools.BandSelection())
            } else if (keep) {
                store.setActive(s.slotIndex, target)
            }
            reload++
            busy = false
        }
    }

    /** Factory band settings on every SIM. */
    fun resetAll() {
        busy = true
        feedback = BandFeedback(true, "Przywracam domyślne pasma…", "Wszystkie karty SIM")
        scope.launch {
            val results = sims.map { s -> network.resetBands(s.slotIndex, s.subscriptionId) to s }
            sims.forEach { store.setActive(it.slotIndex, pl.btmap.mobile.tools.BandSelection()) }
            val failed = results.filter { it.first is pl.btmap.mobile.tools.BandLockOutcome.Failed }
            feedback = if (failed.isEmpty()) BandFeedback(true, "Przywrócono domyślne pasma", "Wszystkie pasma odblokowane na ${sims.size} kartach SIM")
                else BandFeedback(false, "Nie udało się przywrócić pasm", failed.joinToString { "SIM ${it.second.slotIndex + 1}" })
            lte = lteBands.toSet(); nr = nrBands.toSet()
            reload++
            busy = false
        }
    }

    fun applyMode(preset: pl.btmap.mobile.tools.NetworkModePreset) {
        val s = sim ?: return
        modeBusy = true
        if (preset == pl.btmap.mobile.tools.NetworkModePreset.NR) {
            feedback = BandFeedback(true, "Szukam 5G SA…", "SIM ${s.slotIndex + 1}: do 40 s. Jeśli go tu nie ma, przywrócę poprzedni tryb")
        }
        scope.launch {
            val error = network.setMode(s.slotIndex, preset)
            val simLabel = "SIM ${s.slotIndex + 1} · " + Operators.displayName(s.registeredPlmn, s.networkOperatorName)
            feedback = if (error == null) {
                mode = preset
                BandFeedback(true, "Tryb sieci: ${preset.label}", simLabel)
            } else BandFeedback(false, "Nie udało się zmienić trybu sieci", error)
            reload++
            modeBusy = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // ---- Feedback banner ----
        AnimatedVisibility(
            visible = feedback != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            val fb = feedback
            if (fb != null) {
                val tint = if (fb.ok) Btmap.colors.good else Btmap.colors.bad
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(tint.copy(alpha = 0.14f))
                        .border(1.dp, tint.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                        .clickable { feedback = null }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (fb.ok) "✓" else "!", style = MaterialTheme.typography.titleLarge, color = tint, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(fb.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = tint)
                        Text(fb.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }

        // ---- Status ----
        Card(Modifier.enterAnimation(1)) {
            val restricted = !current.isUnrestricted
            val tint = if (restricted) Btmap.colors.poor else Btmap.colors.good
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(tint.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) { BandLockIcon(tint) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Crossfade(restricted, label = "lockTitle") { r ->
                        Text(
                            if (r) "Pasma ograniczone" else "Wszystkie pasma odblokowane",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        if (restricted) describe(current) else "Modem sam wybiera pasma",
                        style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted,
                    )
                }
            }
            if (sims.size > 1) {
                Spacer(Modifier.height(14.dp))
                Segmented(
                    options = sims.map { it.slotIndex to "SIM ${it.slotIndex + 1} · " + Operators.displayName(it.registeredPlmn, it.networkOperatorName) },
                    selected = sim?.slotIndex ?: 0,
                    onSelect = { slot = it },
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    modem == null -> "Sprawdzam modem…"
                    mtk -> "Sterowanie: bezpośrednio w modemie MediaTek · pasma odczytane z modemu"
                    else -> "Sterowanie: przez Androida (zależnie od modemu)"
                },
                style = MaterialTheme.typography.labelSmall, color = if (mtk) Btmap.colors.good else Btmap.colors.muted,
            )
            if (restricted) {
                Spacer(Modifier.height(12.dp))
                WideButton("Odblokuj wszystkie pasma") {
                    lte = lteBands.toSet(); nr = nrBands.toSet()
                    apply(pl.btmap.mobile.tools.BandSelection(), "wszystkie pasma")
                }
            }
        }

        // ---- Network mode (works on every phone) ----
        Card(Modifier.enterAnimation(2)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tryb sieci", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (modeBusy) Text("zmieniam…", style = MaterialTheme.typography.labelMedium, color = Btmap.colors.accent)
            }
            Text("Które technologie może używać karta. Działa na każdym telefonie.", style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted)
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                pl.btmap.mobile.tools.NetworkModePreset.entries.forEach { preset ->
                    val on = preset == mode
                    val interaction = remember { MutableInteractionSource() }
                    val bg by animateColorAsState(if (on) Btmap.colors.accent.copy(alpha = 0.16f) else Color.Transparent, tween(200), label = "modeBg")
                    val border by animateColorAsState(if (on) Btmap.colors.accent else Btmap.colors.divider, tween(200), label = "modeBorder")
                    Column(
                        Modifier
                            .pressScale(interaction)
                            .clip(RoundedCornerShape(14.dp))
                            .background(bg)
                            .border(1.dp, border, RoundedCornerShape(14.dp))
                            .clickable(interaction, LocalIndication.current, enabled = !modeBusy && !on) { applyMode(preset) }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    ) {
                        Text(preset.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = if (on) Btmap.colors.accent else MaterialTheme.colorScheme.onSurface)
                        Text(preset.description, style = MaterialTheme.typography.labelSmall, color = Btmap.colors.muted)
                    }
                }
            }
        }

        // ---- Band support on this phone (only matters without direct modem control) ----
        if (!mtk && support != pl.btmap.mobile.tools.BandLockSupport.UNKNOWN) {
            val works = support == pl.btmap.mobile.tools.BandLockSupport.WORKS
            Row(
                Modifier
                    .enterAnimation(2)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background((if (works) Btmap.colors.good else Btmap.colors.poor).copy(alpha = 0.10f))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PulseDot(if (works) Btmap.colors.good else Btmap.colors.poor, active = false)
                Spacer(Modifier.width(4.dp))
                Text(
                    if (works) "Modem tego telefonu respektuje blokadę pasm."
                    else "Modem tego telefonu ignoruje blokadę pasm (ogranicza tylko skanowanie w tle). Bez roota zostaje tryb sieci.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // ---- LTE / NR ----
        BandCard("LTE", lteBands, lte, detectedLte, 3, { "B$it" }, { pl.btmap.mobile.tools.BandLock.lteLabel(it) }) { lte = it }
        BandCard("5G NR", nrBands, nr, detectedNr, 3, { "n$it" }, { pl.btmap.mobile.tools.BandLock.nrLabel(it) }) { nr = it }

        Row(Modifier.enterAnimation(4), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(Btmap.colors.good))
            Spacer(Modifier.width(6.dp))
            Text(if (mtk) "obsługiwane przez modem (odczytane z modemu)" else "wykryte przez modem tego telefonu", style = MaterialTheme.typography.labelSmall, color = Btmap.colors.muted)
        }

        // ---- Actions ----
        Column(Modifier.enterAnimation(4), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val blocked = lte.isEmpty() || nr.isEmpty()
            WideButton(
                when {
                    busy -> "Wysyłam do modemu…"
                    blocked -> "Zostaw co najmniej jedno pasmo LTE i 5G"
                    changed -> "Wymuś wybrane pasma i sprawdź"
                    else -> "Bez zmian"
                },
                primary = changed && !blocked && !busy,
            ) { if (changed && !blocked && !busy) apply(selection, describe(selection)) }
            if (!selection.isUnrestricted) {
                ActionButton("Zapisz jako konfigurację…") { naming = true }
            }
        }

        // ---- Saved configurations ----
        if (presets.isNotEmpty()) {
            SectionLabel("Zapisane konfiguracje", 5)
            Card(Modifier.enterAnimation(5)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    presets.forEach { preset ->
                        val isActive = current == preset.selection
                        val border by animateColorAsState(if (isActive) Btmap.colors.accent else Btmap.colors.divider, tween(250), label = "presetBorder")
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isActive) Btmap.colors.accent.copy(alpha = 0.08f) else Btmap.colors.cardRaised)
                                .border(1.dp, border, RoundedCornerShape(16.dp))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(preset.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                    if (isActive) {
                                        Spacer(Modifier.width(8.dp))
                                        Text("AKTYWNA", style = MaterialTheme.typography.labelSmall, color = Btmap.colors.accent)
                                    }
                                }
                                Text(describe(preset.selection), style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted)
                            }
                            if (!isActive) {
                                ActionButton("Wymuś", primary = true, enabled = !busy) {
                                    lte = preset.selection.lte.ifEmpty { lteBands.toSet() }
                                    nr = preset.selection.nr.ifEmpty { nrBands.toSet() }
                                    apply(preset.selection, preset.name)
                                }
                            }
                            TextButton(onClick = { deleting = preset }) {
                                Text("Usuń", color = Btmap.colors.bad, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
        // ---- Reset ----
        Card(Modifier.enterAnimation(6)) {
            Text("Domyślne pasma", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Przywraca fabryczne ustawienia: wszystkie pasma, które obsługuje modem, na obu kartach SIM.",
                style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted,
            )
            Spacer(Modifier.height(12.dp))
            WideButton("Resetuj pasma do domyślnych", danger = true) { if (!busy) confirmReset = true }
        }
        Hint(if (mtk) "Blokada jest zapisana w modemie. Stan powyżej jest odczytywany z modemu przy każdym otwarciu tej strony." else "Wymuszenie działa do ponownego uruchomienia telefonu — potem zastosuj konfigurację jeszcze raz.")
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Przywrócić domyślne pasma?") },
            text = { Text("Wszystkie pasma obsługiwane przez modem zostaną odblokowane na obu kartach SIM.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; resetAll() }) { Text("Resetuj", color = Btmap.colors.bad) } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Anuluj") } },
            containerColor = Btmap.colors.card,
        )
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Nazwa konfiguracji") },
            text = {
                Column {
                    Text(describe(selection), style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                        placeholder = { Text("np. Dom — 1800 + n78") },
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Btmap.colors.accent, cursorColor = Btmap.colors.accent),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val saved = store.savePreset(name, selection)
                    naming = false
                    feedback = BandFeedback(true, "Zapisano konfigurację", saved.name)
                }) { Text("Zapisz", color = Btmap.colors.accent) }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Anuluj") } },
            containerColor = Btmap.colors.card,
        )
    }
    deleting?.let { preset ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Usunąć „${preset.name}”?") },
            text = { Text("Konfiguracja zniknie z listy. Pasma ustawione teraz w modemie się nie zmienią.") },
            confirmButton = { TextButton(onClick = { store.deletePreset(preset.id); deleting = null }) { Text("Usuń", color = Btmap.colors.bad) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Anuluj") } },
            containerColor = Btmap.colors.card,
        )
    }
}

/** One technology: header with select all / none and a grid of band toggles. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BandCard(
    title: String,
    bands: List<Int>,
    selected: Set<Int>,
    detected: Set<Int>,
    index: Int,
    code: (Int) -> String,
    mhz: (Int) -> String,
    onChange: (Set<Int>) -> Unit,
) {
    val colors = Btmap.colors
    Card(Modifier.enterAnimation(index)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text("${selected.size}/${bands.size}", style = MaterialTheme.typography.labelMedium, color = colors.muted)
            Spacer(Modifier.weight(1f))
            val all = selected.containsAll(bands)
            TextButton(onClick = { onChange(if (all) emptySet() else bands.toSet()) }) {
                Text(if (all) "Odznacz wszystkie" else "Zaznacz wszystkie", color = colors.accent, style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            bands.forEach { b ->
                val on = b in selected
                val known = b in detected
                val interaction = remember { MutableInteractionSource() }
                val bg by animateColorAsState(if (on) colors.accent.copy(alpha = 0.16f) else Color.Transparent, tween(200), label = "bandBg")
                val border by animateColorAsState(if (on) colors.accent.copy(alpha = 0.8f) else colors.divider, tween(200), label = "bandBorder")
                Column(
                    Modifier
                        .width(96.dp)
                        .pressScale(interaction)
                        .clip(RoundedCornerShape(14.dp))
                        .background(bg)
                        .border(1.dp, border, RoundedCornerShape(14.dp))
                        .clickable(interaction, LocalIndication.current) { onChange(if (on) selected - b else selected + b) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            code(b), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                            color = if (on) colors.accent else colors.faint, modifier = Modifier.weight(1f),
                        )
                        if (known) Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(colors.good))
                    }
                    Text(
                        mhz(b), style = MaterialTheme.typography.labelSmall,
                        color = if (on) MaterialTheme.colorScheme.onSurface else colors.faint,
                    )
                }
            }
        }
    }
}

private fun describe(s: pl.btmap.mobile.tools.BandSelection): String {
    if (s.isUnrestricted) return "wszystkie pasma"
    val parts = mutableListOf<String>()
    parts += if (s.lte.isEmpty()) "LTE: wszystkie" else "LTE: " + s.lte.sorted().joinToString(", ") { "B$it" }
    parts += if (s.nr.isEmpty()) "5G: wszystkie" else "5G: " + s.nr.sorted().joinToString(", ") { "n$it" }
    return parts.joinToString(" · ")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BandChips(bands: List<Int>, label: (Int) -> String, selected: Set<Int>, detected: Set<Int>, onToggle: (Int) -> Unit) {
    val colors = Btmap.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        bands.forEach { b ->
            val on = b in selected
            val known = b in detected
            val interaction = remember { MutableInteractionSource() }
            val bg by animateColorAsState(if (on) colors.accent.copy(alpha = 0.18f) else Color.Transparent, tween(200), label = "bandBg")
            val border by animateColorAsState(if (on) colors.accent else colors.divider, tween(200), label = "bandBorder")
            Row(
                Modifier
                    .pressScale(interaction)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg)
                    .border(1.dp, border, RoundedCornerShape(12.dp))
                    .clickable(interaction, LocalIndication.current) { onToggle(b) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (known) {
                    Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(colors.good))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    label(b),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    color = when {
                        on -> colors.accent
                        known -> MaterialTheme.colorScheme.onSurface
                        else -> colors.faint
                    },
                )
            }
        }
    }
}

@Composable
private fun AppearancePage(settings: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    Label("Motyw")
    Segmented(
        options = listOf(ThemeMode.SYSTEM to "Auto", ThemeMode.LIGHT to "Jasny", ThemeMode.DARK to "Ciemny"),
        selected = settings.theme,
        onSelect = { mode -> onChange { it.copy(theme = mode) } },
    )
    Spacer(Modifier.height(14.dp))
    ToggleRow("Sąsiednie komórki", "Lista mierzonych komórek pod kartą operatora", settings.showNeighbours) { on ->
        onChange { it.copy(showNeighbours = on) }
    }
}

/** Status of modem carrier access (DUMP) with the one-time adb command to enable it. */
@Composable
private fun ModemAccess(core: BtmapCore) {
    val context = LocalContext.current
    var available by remember { mutableStateOf(core.physicalChannels.isAvailable) }
    LaunchedEffect(Unit) {
        while (!available) {
            kotlinx.coroutines.delay(2_000)
            available = core.physicalChannels.isAvailable
        }
    }
    val command = "adb shell pm grant ${context.packageName} android.permission.DUMP"
    Row(verticalAlignment = Alignment.CenterVertically) {
        PulseDot(if (available) Btmap.colors.good else Btmap.colors.poor, active = !available)
        Spacer(Modifier.width(6.dp))
        Text(
            if (available) "Pasma odczytywane z modemu" else "Pasma z modemu: wyłączone",
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        )
    }
    Spacer(Modifier.height(6.dp))
    if (available) {
        Hint("Agregacja, szerokości i noga 5G pochodzą bezpośrednio z konfiguracji nośnych w modemie.")
        var last by remember { mutableStateOf(core.physicalChannels.lastRead) }
        LaunchedEffect(Unit) {
            while (true) {
                last = core.physicalChannels.lastRead
                kotlinx.coroutines.delay(2_000)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            last?.let { m ->
                "Ostatni odczyt: " + m.entries.sortedBy { it.key }.joinToString(" · ") { (slot, list) ->
                    "SIM ${slot + 1}: " + if (list.isEmpty()) "brak transmisji" else list.joinToString(" + ") { pc ->
                        (if (pc.rat == pl.btmap.core.model.Rat.NR) "n" else "B") + (pc.band ?: "?") + " " + ((pc.bandwidthKhz ?: 0) / 1000) + " MHz"
                    }
                }
            } ?: "Ostatni odczyt: brak danych",
            style = MaterialTheme.typography.labelSmall, color = Btmap.colors.muted,
        )
    } else {
        Hint(
            "Android pokazuje dokładne nośne (agregacja, 5G NSA) tylko aplikacjom z uprawnieniem DUMP. " +
                "Nadaj je raz z komputera — bez roota, działa do odinstalowania aplikacji:",
        )
        Spacer(Modifier.height(8.dp))
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        Text(
            command,
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Btmap.colors.cardRaised)
                .clickable { clipboard.setText(androidx.compose.ui.text.AnnotatedString(command)) }
                .padding(12.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
        )
        Spacer(Modifier.height(4.dp))
        Hint("Stuknij, aby skopiować. Bez tego pasma w agregacji są szacowane.")
    }
}

@Composable
private fun MeasurementsPage(settings: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    Label("Odświeżanie na ekranie")
    Segmented(
        options = Settings.REFRESH_OPTIONS.map { it to "${it / 1000} s" },
        selected = settings.liveRefreshMs,
        onSelect = { ms -> onChange { it.copy(liveRefreshMs = ms) } },
    )
    Spacer(Modifier.height(10.dp))
    Hint(
        "W tle aplikacja sama zwalnia: przy włączonym ekranie odświeża co 5 s, a przy wyłączonym " +
            "nie używa własnego GPS i opiera się na zgłoszeniach modemu, żeby oszczędzać baterię.",
    )
}

@Composable
private fun NotificationPage(settings: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    ToggleRow("Sygnał", "Przybliżone RSRP w treści", settings.notificationSignal) { on ->
        onChange { it.copy(notificationSignal = on) }
    }
    ToggleRow("Stacja BTMap", "Adres i odległość zamiast numeru eNB", settings.notificationStation) { on ->
        onChange { it.copy(notificationStation = on) }
    }
    ToggleRow("Druga karta SIM", "Skrót drugiej karty w nagłówku", settings.notificationOtherSim) { on ->
        onChange { it.copy(notificationOtherSim = on) }
    }
}

// ---- Sections ------------------------------------------------------------------------------

@Composable
private fun ServerSettings(settings: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember(settings.apiBaseUrl) { mutableStateOf(settings.apiBaseUrl) }
    var status by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var checking by remember { mutableStateOf(false) }
    val changed = url.trim().trimEnd('/') != settings.apiBaseUrl.trimEnd('/')

    OutlinedTextField(
        value = url,
        onValueChange = { url = it; status = null },
        label = { Text("Adres API") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Btmap.colors.accent,
            focusedLabelColor = Btmap.colors.accent,
            cursorColor = Btmap.colors.accent,
        ),
    )
    AnimatedVisibility(status != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        status?.let { (ok, text) ->
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                PulseDot(if (ok) Btmap.colors.good else Btmap.colors.bad, active = false)
                Text(text, style = MaterialTheme.typography.bodySmall, color = if (ok) Btmap.colors.good else Btmap.colors.bad)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton(if (checking) "Sprawdzam…" else "Sprawdź", enabled = !checking) {
            checking = true
            scope.launch {
                status = runCatching {
                    val health = BtmapApi(url.trim()).health()
                    val towers = health.optJSONObject("stats")?.optInt("towers")
                    true to buildString {
                        append("Połączono")
                        health.optString("version").takeIf { it.isNotEmpty() }?.let { append(" · BTMap $it") }
                        towers?.let { append(" · $it stacji") }
                    }
                }.getOrElse { false to "Brak połączenia: ${it.message ?: it.javaClass.simpleName}" }
                checking = false
            }
        }
        ActionButton("Zapisz", enabled = changed, primary = true) {
            onChange { it.copy(apiBaseUrl = url.trim().trimEnd('/')) }
        }
        if (settings.apiBaseUrl != BuildConfig.API_BASE_URL) {
            ActionButton("Domyślny") {
                url = BuildConfig.API_BASE_URL
                onChange { it.copy(apiBaseUrl = BuildConfig.API_BASE_URL) }
            }
        }
    }
}

@Composable
private fun DataSettings(core: BtmapCore, logSize: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<String?>(null) }
    var dictionaryVersion by remember { mutableIntStateOf(0) }
    val learned = remember(dictionaryVersion) { core.dictionary.size }

    StatRow("Komórki w logu", logSize.toString())
    StatRow("Nauczone komórki", learned.toString())
    Hint("Nauczone komórki pozwalają rozpoznać pasma w agregacji i podpisać sąsiednie komórki numerem eNB.")
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton("Eksportuj CSV", enabled = logSize > 0, primary = true) {
            scope.launch {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
                    File(dir, "btmap-log-$stamp.csv").apply { writeText(core.log.toCsv()) }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/csv")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, "Eksportuj log"))
            }
        }
        ActionButton("Wyczyść log", enabled = logSize > 0, danger = true) { confirm = "log" }
    }
    Spacer(Modifier.height(8.dp))
    ActionButton("Zapomnij nauczone komórki", enabled = learned > 0, danger = true) { confirm = "dictionary" }

    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (what == "log") "Wyczyścić log?" else "Zapomnieć nauczone komórki?") },
            text = {
                Text(
                    if (what == "log") "Usunie $logSize wpisów z telefonu. Tego nie da się cofnąć."
                    else "Aplikacja nauczy się ich od nowa, ale do tego czasu pasma w agregacji mogą być nieznane.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (what == "log") core.clearLog() else core.clearDictionary()
                    dictionaryVersion++
                    confirm = null
                }) { Text("Usuń", color = Btmap.colors.bad) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Anuluj") } },
            containerColor = Btmap.colors.card,
        )
    }
}

@Composable
private fun About() {
    val context = LocalContext.current
    StatRow("Wersja", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    StatRow("Licencja", "MIT")
    Spacer(Modifier.height(10.dp))
    ActionButton("Kod źródłowy na GitHubie") {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/btmap-pl/btmap-mobile")))
    }
}

// ---- Building blocks -----------------------------------------------------------------------

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Btmap.colors.card)
            .border(1.dp, Btmap.colors.divider, shape)
            .padding(18.dp),
    ) { content() }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 10.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted)
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = Btmap.colors.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(interaction, LocalIndication.current) { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Btmap.colors.accent,
                uncheckedTrackColor = Btmap.colors.meterTrack,
                uncheckedBorderColor = Btmap.colors.divider,
                uncheckedThumbColor = Btmap.colors.muted,
            ),
        )
    }
}

/** Pill-shaped segmented selector with an animated selection. */
@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Btmap.colors.cardRaised)
            .padding(4.dp),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            val bg by animateColorAsState(if (isSelected) Btmap.colors.accent.copy(alpha = 0.18f) else Color.Transparent, tween(250), label = "seg")
            val fg by animateColorAsState(if (isSelected) Btmap.colors.accent else Btmap.colors.muted, tween(250), label = "segFg")
            val interaction = remember { MutableInteractionSource() }
            Text(
                label,
                Modifier
                    .weight(1f)
                    .pressScale(interaction)
                    .clip(RoundedCornerShape(10.dp))
                    .background(bg)
                    .clickable(interaction, LocalIndication.current) { onSelect(value) }
                    .padding(vertical = 10.dp),
                color = fg,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    enabled: Boolean = true,
    primary: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = Btmap.colors
    val tint = when {
        !enabled -> colors.faint
        danger -> colors.bad
        primary -> colors.accent
        else -> MaterialTheme.colorScheme.onSurface
    }
    val interaction = remember { MutableInteractionSource() }
    Text(
        text,
        Modifier
            .pressScale(interaction)
            .clip(RoundedCornerShape(12.dp))
            .background(if (primary && enabled) colors.accent.copy(alpha = 0.14f) else colors.cardRaised)
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .clickable(interaction, LocalIndication.current, enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        color = tint,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
    )
}
