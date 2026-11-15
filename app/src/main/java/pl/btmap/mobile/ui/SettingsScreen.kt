package pl.btmap.mobile.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
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
                } else Card(Modifier.enterAnimation(1)) {
                    when (current) {
                        SettingsPage.DATABASE -> Unit
                        SettingsPage.APPEARANCE -> AppearancePage(settings, onChange)
                        SettingsPage.MEASUREMENTS -> {
                            ModemAccess(core)
                            Spacer(Modifier.height(18.dp))
                            MeasurementsPage(settings, onChange)
                        }
                        SettingsPage.NOTIFICATION -> NotificationPage(settings, onChange)
                        SettingsPage.DATA -> DataSettings(core, logSize)
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
