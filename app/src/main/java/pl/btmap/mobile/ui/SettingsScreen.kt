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
import androidx.compose.material.icons.outlined.Lock
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
import pl.btmap.mobile.i18n.tr
import pl.btmap.mobile.i18n.translateMessage
import pl.btmap.mobile.i18n.AppLanguage
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.OpenableColumns
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.fillMaxHeight

/** Settings categories, shown as a menu; each opens its own page. */
private enum class SettingsPage(private val pl: String, private val en: String) {
    DATABASE("Baza danych", "Database"),
    APPEARANCE("Wygląd", "Appearance"),
    MEASUREMENTS("Pomiary", "Measurements"),
    NOTIFICATION("Powiadomienie", "Notification"),
    DATA("Dane", "Data"),
    BANDS("Wymuszanie pasm", "Band locking"),
    TOOLS("Narzędzia", "Tools"),
    MODEM("Modem na żywo", "Live modem"),
    ABOUT("O aplikacji", "About");

    val title: String get() = tr(pl, en)
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
                } else if (current == SettingsPage.MODEM) {
                    ModemLivePage(settings, onChange)
                } else Card(Modifier.enterAnimation(1)) {
                    when (current) {
                        SettingsPage.DATABASE, SettingsPage.BANDS, SettingsPage.MODEM -> Unit
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
    val theme = when (settings.theme) { ThemeMode.SYSTEM -> "Auto"; ThemeMode.LIGHT -> tr("Jasny", "Light"); ThemeMode.DARK -> tr("Ciemny", "Dark") }
    val language = when (settings.language) { AppLanguage.SYSTEM -> "Auto"; AppLanguage.PL -> "Polski"; AppLanguage.EN -> "English" }
    val notification = listOfNotNull(
        tr("sygnał", "signal").takeIf { settings.notificationSignal },
        tr("stacja", "station").takeIf { settings.notificationStation },
        tr("druga karta", "second SIM").takeIf { settings.notificationOtherSim },
    ).joinToString(", ").ifEmpty { tr("tylko pasma", "bands only") }
    val host = runCatching { Uri.parse(settings.apiBaseUrl).host + (Uri.parse(settings.apiBaseUrl).port.takeIf { it > 0 }?.let { ":$it" } ?: "") }
        .getOrNull() ?: settings.apiBaseUrl

    val dbState by core.stations.state.collectAsStateWithLifecycle()
    val dbVersion by core.stations.version.collectAsStateWithLifecycle()
    val dbInfo = remember(settings.database, dbState, dbVersion) { core.stations.info(settings.database) }
    val dbSummary = when (val st = dbState) {
        is ImportState.Running -> tr("${st.source.label} · pobieram…", "${st.source.label} · downloading…")
        else -> settings.database.label + " · " + (dbInfo?.let { tr("${formatCount(it.sites)} stacji", "${formatCount(it.sites)} stations") } ?: tr("nie pobrano", "not downloaded"))
    }
    val rootActive by (LocalContext.current.applicationContext as pl.btmap.mobile.BtmapApp).shizuku.rootActive.collectAsStateWithLifecycle()
    val items: List<Triple<SettingsPage, String, @Composable (Color) -> Unit>> = listOfNotNull(
        Triple(SettingsPage.DATABASE, dbSummary) { c -> DatabaseIcon(c) },
        Triple(SettingsPage.APPEARANCE, tr("Motyw: $theme · język: $language", "Theme: $theme · language: $language")) { c -> ThemeIcon(c) },
        Triple(SettingsPage.MEASUREMENTS, tr("Odświeżanie co ${settings.liveRefreshMs / 1000} s · w tle oszczędnie", "Refresh every ${settings.liveRefreshMs / 1000} s · battery-saving in background")) { c -> LiveIcon(c) },
        Triple(SettingsPage.NOTIFICATION, tr("Pasma, $notification", "Bands, $notification")) { c -> Icon(Icons.Outlined.Notifications, null, tint = c) },
        Triple(SettingsPage.DATA, tr("$logSize w logu · ${core.dictionary.size} nauczonych", "$logSize in log · ${core.dictionary.size} learned")) { c -> LogIcon(c) },
        Triple(SettingsPage.BANDS, bandsSummary(settings)) { c -> BandLockIcon(c) },
        Triple(SettingsPage.TOOLS, toolsSummary(settings)) { c -> Icon(Icons.Outlined.Build, null, tint = c) },
        // Reads the modem's own logs (Qualcomm DIAG), which needs root.
        if (rootActive) Triple<SettingsPage, String, @Composable (Color) -> Unit>(SettingsPage.MODEM, tr("MCS, modulacja, BLER, przepustowość (root)", "MCS, modulation, BLER, throughput (root)")) { c -> LiveIcon(c) } else null,
        Triple(SettingsPage.ABOUT, tr("Wersja ${BuildConfig.VERSION_NAME} · MIT", "Version ${BuildConfig.VERSION_NAME} · MIT")) { c -> Icon(Icons.Outlined.Info, null, tint = c) },
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
                            running != null -> tr("Pobieranie…", "Downloading…")
                            failed != null -> tr("Pobieranie nie powiodło się", "Download failed")
                            info == null -> tr("Nie pobrano", "Not downloaded")
                            else -> tr("Zaktualizowano ${relativeTime(info.updatedAtMillis)}", "Updated ${relativeTime(info.updatedAtMillis)}")
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
                                tr("${formatCount(running.done)} / ${if (running.total > 0) formatCount(running.total) else "?"} stacji", "${formatCount(running.done)} / ${if (running.total > 0) formatCount(running.total) else "?"} stations"),
                                style = MaterialTheme.typography.bodyMedium, color = Btmap.colors.muted,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Meter(fraction, Btmap.colors.accent, Modifier.fillMaxWidth(), height = 8.dp)
                        Spacer(Modifier.height(8.dp))
                        Text(tr("${formatCount(running.cells)} komórek zapisanych", "${formatCount(running.cells)} cells saved"), style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted)
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
                            StatTile(tr("Stacje", "Stations"), info?.let { formatCount(it.sites) } ?: "—", Modifier.weight(1f))
                            StatTile(tr("Komórki", "Cells"), info?.let { formatCount(it.cells) } ?: "—", Modifier.weight(1f))
                            StatTile(
                                tr("Następna", "Next"),
                                if (settings.dbRefreshDays == 0) tr("ręcznie", "manual")
                                else info?.let { relativeFuture(it.updatedAtMillis + settings.dbRefreshDays * 86_400_000L) } ?: tr("teraz", "now"),
                                Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            failed?.let {
                Spacer(Modifier.height(10.dp))
                Text(translateMessage(it.message), style = MaterialTheme.typography.bodySmall, color = Btmap.colors.bad)
            }
            if (outdatedSelection && running == null) {
                Spacer(Modifier.height(10.dp))
                Text(tr("Zmieniono operatorów — zaktualizuj bazę, aby to zastosować.", "Operators changed — update the database to apply it."), style = MaterialTheme.typography.bodySmall, color = Btmap.colors.poor)
            }
            if (info != null && info.cells == 0 && settings.database == StationSource.BTMAP && running == null) {
                Spacer(Modifier.height(10.dp))
                Hint(tr("W BTMap nie ma jeszcze stacji spoza UKE. Pojawią się, gdy społeczność je doda.", "BTMap has no stations outside UKE yet. They will appear as the community adds them."))
            }

            Spacer(Modifier.height(16.dp))
            if (running != null) {
                WideButton(tr("Anuluj pobieranie", "Cancel download"), danger = true) { DatabaseWorker.cancel(context) }
            } else {
                val stale = remember(info, settings.btsearchMncs, settings.dbRefreshDays, settings.database) {
                    if (settings.database == StationSource.BTSEARCH)
                        core.stations.staleOperators(StationSource.BTSEARCH, settings.btsearchMncs, DatabaseWorker.maxAgeMs(settings))
                    else emptySet()
                }
                when {
                    info == null -> WideButton(tr("Pobierz bazę", "Download database"), primary = true) { DatabaseWorker.runNow(context, settings) }
                    stale.isNotEmpty() -> {
                        val names = DbOperators.ALL.filter { it.mnc in stale }.joinToString(", ") { it.name }
                        WideButton(tr("Pobierz: $names", "Download: $names"), primary = true) { DatabaseWorker.runNow(context, settings) }
                    }
                    else -> WideButton(tr("Odśwież wszystko", "Refresh all"), primary = false) { DatabaseWorker.runNow(context, settings, force = true) }
                }
                if (settings.dbWifiOnly) {
                    Spacer(Modifier.height(6.dp))
                    Hint(tr("Ruszy po połączeniu z Wi-Fi.", "Starts once connected to Wi-Fi."))
                }
            }
        }

        // ---- Source ----
        SectionLabel(tr("Źródło", "Source"), 2)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.enterAnimation(2)) {
            SourceCard(
                title = "BTMap",
                subtitle = tr("Stacje społeczności, bez danych z UKE", "Community stations, without UKE data"),
                selected = settings.database == StationSource.BTMAP,
                modifier = Modifier.weight(1f),
            ) { onChange { it.copy(database = StationSource.BTMAP) } }
            SourceCard(
                title = "BTSearch",
                subtitle = tr("Baza btsearch.pl z pełnymi ID komórek", "The btsearch.pl database with full cell IDs"),
                selected = settings.database == StationSource.BTSEARCH,
                modifier = Modifier.weight(1f),
            ) { onChange { it.copy(database = StationSource.BTSEARCH) } }
        }

        // ---- Operators (BTSearch) ----
        AnimatedVisibility(settings.database == StationSource.BTSEARCH, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel(tr("Operatorzy", "Operators"), 3)
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
                    Hint(tr("NetWorkS! to wspólna sieć T-Mobile i Orange; jej komórki trafiają też do tych operatorów.", "NetWorkS! is the shared T-Mobile and Orange network; its cells are also added to those operators."))
                }
            }
        }

        // ---- Own NetMonster file ----
        SectionLabel(tr("Własna baza", "Own database"), 4)
        CustomDatabaseCard(core, Modifier.enterAnimation(4))

        // ---- Schedule ----
        SectionLabel(tr("Aktualizacja", "Updates"), 4)
        Card(Modifier.enterAnimation(4)) {
            Segmented(
                options = Settings.DB_REFRESH_OPTIONS.map { (d, l) -> d to l.replace("Co ", "") },
                selected = settings.dbRefreshDays,
                onSelect = { days -> onChange { it.copy(dbRefreshDays = days) } },
            )
            Spacer(Modifier.height(6.dp))
            ToggleRow(tr("Tylko przez Wi-Fi", "Wi-Fi only"), tr("Bez zużywania danych komórkowych", "Without using mobile data"), settings.dbWifiOnly) { on ->
                onChange { it.copy(dbWifiOnly = on) }
            }
        }

        if (info != null && running == null) {
            Row(Modifier.fillMaxWidth().enterAnimation(5), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { confirmDelete = true }) {
                    Text(tr("Usuń pobraną bazę", "Delete downloaded database"), color = Btmap.colors.bad, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("Usunąć bazę ${settings.database.label}?", "Delete the ${settings.database.label} database?")) },
            text = { Text(tr("Stacje przestaną być rozpoznawane, dopóki baza nie zostanie pobrana ponownie.", "Stations will not be recognised until the database is downloaded again.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    core.stations.clear(settings.database)
                }) { Text(tr("Usuń", "Delete"), color = Btmap.colors.bad) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Anuluj", "Cancel")) } },
            containerColor = Btmap.colors.card,
        )
    }
}

/**
 * The user's own NetMonster `.ntm` file: imported from storage, looked up before the downloaded
 * database, so stations are named in any country.
 */
@Composable
private fun CustomDatabaseCard(core: BtmapCore, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by core.stations.version.collectAsStateWithLifecycle()
    val info = remember(version) { core.stations.info(StationSource.CUSTOM) }
    var importing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: "plik.ntm"
        importing = true
        result = null
        scope.launch {
            val outcome = runCatching {
                context.contentResolver.openInputStream(uri)?.use { core.stations.importNtm(it, name).getOrThrow() }
                    ?: throw java.io.IOException(tr("Nie można otworzyć pliku", "Cannot open the file"))
            }
            importing = false
            result = outcome.fold(
                onSuccess = { r ->
                    true to tr(
                        "Wczytano ${formatCount(r.info.sites)} stacji i ${formatCount(r.info.cells)} komórek" +
                            if (r.skipped > 0) " · pominięto ${formatCount(r.skipped)} bez lokalizacji" else "",
                        "Imported ${formatCount(r.info.sites)} stations and ${formatCount(r.info.cells)} cells" +
                            if (r.skipped > 0) " · skipped ${formatCount(r.skipped)} without a location" else "",
                    )
                },
                onFailure = { e -> false to translateMessage(e.message ?: e.javaClass.simpleName) },
            )
        }
    }

    Card(modifier) {
        Text(
            info?.detail ?: tr("Plik .ntm z NetMonstera", "NetMonster .ntm file"),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
        )
        Text(
            when {
                importing -> tr("Wczytuję…", "Importing…")
                info != null -> tr(
                    "${formatCount(info.sites)} stacji · ${formatCount(info.cells)} komórek · ${relativeTime(info.updatedAtMillis)}",
                    "${formatCount(info.sites)} stations · ${formatCount(info.cells)} cells · ${relativeTime(info.updatedAtMillis)}",
                )
                else -> tr("Nie wczytano", "Not imported")
            },
            style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted,
        )
        result?.let { (ok, text) ->
            Spacer(Modifier.height(6.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = if (ok) Btmap.colors.good else Btmap.colors.bad)
        }
        Spacer(Modifier.height(12.dp))
        WideButton(
            if (info == null) tr("Wczytaj plik .ntm", "Import .ntm file") else tr("Wczytaj inny plik", "Import another file"),
            primary = info == null,
        ) { if (!importing) picker.launch(arrayOf("*/*")) }
        if (info != null && !importing) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { confirmDelete = true }) {
                    Text(tr("Usuń własną bazę", "Delete own database"), color = Btmap.colors.bad, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
    Hint(
        tr(
            "Wyeksportuj bazę w NetMonsterze (format .ntm) i wczytaj ją tutaj. Działa w każdym kraju; stacje z tego pliku mają pierwszeństwo przed pobraną bazą.",
            "Export your database in NetMonster (.ntm format) and import it here. Works in any country; stations from this file take priority over the downloaded database.",
        ),
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("Usunąć własną bazę?", "Delete own database?")) },
            text = { Text(tr("Plik w telefonie się nie zmieni — możesz go wczytać ponownie.", "The file on your phone is not touched — you can import it again.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    result = null
                    core.stations.clear(StationSource.CUSTOM)
                }) { Text(tr("Usuń", "Delete"), color = Btmap.colors.bad) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Anuluj", "Cancel")) } },
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
                            OperatorProgress.Status.DONE -> Text(tr("✓ ${formatCount(op.cells)} komórek", "✓ ${formatCount(op.cells)} cells"), style = MaterialTheme.typography.bodySmall, color = colors.good)
                            OperatorProgress.Status.RUNNING -> Text(
                                if (op.total > 0) "${(100 * op.done / op.total)}%" else tr("łączę…", "connecting…"),
                                style = MaterialTheme.typography.bodySmall, color = colors.accent, fontWeight = FontWeight.SemiBold,
                            )
                            OperatorProgress.Status.PENDING -> Text(tr("w kolejce", "queued"), style = MaterialTheme.typography.bodySmall, color = colors.faint)
                            OperatorProgress.Status.FAILED -> Text(tr("błąd", "error"), style = MaterialTheme.typography.bodySmall, color = colors.bad)
                        }
                    }
                }
                if (op.status == OperatorProgress.Status.RUNNING) {
                    Spacer(Modifier.height(6.dp))
                    Meter(if (op.total > 0) op.done.toFloat() / op.total else 0.02f, brand, Modifier.fillMaxWidth(), height = 6.dp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tr("${formatCount(op.done)} / ${if (op.total > 0) formatCount(op.total) else "?"} stacji · ${formatCount(op.cells)} komórek", "${formatCount(op.done)} / ${if (op.total > 0) formatCount(op.total) else "?"} stations · ${formatCount(op.cells)} cells"),
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
        tr("BTSearch ogranicza liczbę zapytań — wznowię za %d:%02d", "BTSearch limits the number of requests — resuming in %d:%02d").format(left / 60, left % 60),
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
        minutes < 1 -> tr("przed chwilą", "just now")
        minutes < 60 -> tr("$minutes min temu", "$minutes min ago")
        minutes < 24 * 60 -> tr("${minutes / 60} godz. temu", "${minutes / 60} h ago")
        minutes < 48 * 60 -> tr("wczoraj", "yesterday")
        else -> tr("${minutes / (24 * 60)} dni temu", "${minutes / (24 * 60)} days ago")
    }
}

private fun relativeFuture(millis: Long): String {
    val hours = (millis - System.currentTimeMillis()) / 3_600_000
    return when {
        hours <= 0 -> tr("wkrótce", "soon")
        hours < 24 -> tr("za $hours godz.", "in $hours h")
        else -> tr("za ${(hours + 12) / 24} dni", "in ${(hours + 12) / 24} days")
    }
}

private fun formatCount(n: Int): String = "%,d".format(Locale("pl"), n).replace('\u00A0', ' ')


@Composable
private fun bandsSummary(settings: Settings): String {
    val app = LocalContext.current.applicationContext as pl.btmap.mobile.BtmapApp
    val status by app.shizuku.status.collectAsStateWithLifecycle()
    val active by app.bands.active.collectAsStateWithLifecycle()
    val root by app.shizuku.rootActive.collectAsStateWithLifecycle()
    if (root && status != ShizukuBridge.Status.READY) return tr("Root: tryb sieci", "Root: network mode")
    if (status != ShizukuBridge.Status.READY || !settings.shizukuEnabled) return tr("Wymaga Shizuku lub roota", "Requires Shizuku or root")
    val restricted = active.filterValues { !it.isUnrestricted }
    return if (restricted.isEmpty()) tr("Wszystkie pasma odblokowane", "All bands unlocked") else tr("Ograniczone: ", "Restricted: ") + describe(restricted.values.first())
}

@Composable
private fun toolsSummary(settings: Settings): String {
    val shizuku = (LocalContext.current.applicationContext as pl.btmap.mobile.BtmapApp).shizuku
    val status by shizuku.status.collectAsStateWithLifecycle()
    val root by shizuku.rootActive.collectAsStateWithLifecycle()
    if (root) return tr("Root: włączony", "Root: enabled")
    return "Shizuku: " + when {
        status == ShizukuBridge.Status.READY && settings.shizukuEnabled -> tr("włączone", "enabled")
        status == ShizukuBridge.Status.READY -> tr("połączone, wyłączone", "connected, disabled")
        status == ShizukuBridge.Status.NO_PERMISSION -> tr("wymaga połączenia", "needs authorising")
        status == ShizukuBridge.Status.NOT_RUNNING -> tr("nie uruchomione", "not running")
        else -> tr("nie zainstalowane", "not installed")
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
    val rootActive by shizuku.rootActive.collectAsStateWithLifecycle()
    val privileged = (ready && settings.shizukuEnabled) || rootActive

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
                        ShizukuBridge.Status.READY -> tr("Połączono z BTMap", "Connected to BTMap")
                        ShizukuBridge.Status.NO_PERMISSION -> tr("Działa — BTMap nie jest jeszcze podłączony", "Running — BTMap is not authorised yet")
                        ShizukuBridge.Status.NOT_RUNNING -> tr("Zainstalowane, usługa nie działa", "Installed, service not running")
                        ShizukuBridge.Status.NOT_INSTALLED -> tr("Nie zainstalowane", "Not installed")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (ready) Btmap.colors.good else Btmap.colors.muted,
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Hint(tr("Shizuku pozwala BTMap korzystać z uprawnień powłoki bez roota i bez komputera — np. odczytywać pasma i agregację prosto z modemu.", "Shizuku lets BTMap use shell permissions without root or a computer — for example to read bands and aggregation straight from the modem."))

    Spacer(Modifier.height(14.dp))
    when (status) {
        ShizukuBridge.Status.NOT_INSTALLED -> WideButton(tr("Zainstaluj Shizuku", "Install Shizuku"), primary = true) {
            val market = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("market://details?id=${ShizukuBridge.SHIZUKU_PACKAGE}"))
            runCatching { context.startActivity(market) }.onFailure {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
            }
        }
        ShizukuBridge.Status.NOT_RUNNING -> {
            WideButton(tr("Otwórz Shizuku", "Open Shizuku"), primary = true) { shizuku.openShizuku(context) }
            Spacer(Modifier.height(6.dp))
            Hint(tr("Uruchom usługę w aplikacji Shizuku (przez debugowanie bezprzewodowe), a potem wróć tutaj.", "Start the service in the Shizuku app (via wireless debugging), then come back here."))
        }
        ShizukuBridge.Status.NO_PERMISSION -> {
            WideButton(tr("Podłącz BTMap w Shizuku", "Authorise BTMap in Shizuku"), primary = true) { shizuku.requestPermission() }
            Spacer(Modifier.height(6.dp))
            Hint(tr("Shizuku zapyta, czy zezwolić BTMap. Możesz to też zrobić w Shizuku → Aplikacje autoryzowane.", "Shizuku will ask whether to allow BTMap. You can also do it in Shizuku → Authorized applications."))
        }
        ShizukuBridge.Status.READY -> Unit
    }

    Spacer(Modifier.height(10.dp))
    // Unlocks only once BTMap is authorised in Shizuku.
    Box(Modifier.graphicsLayer { alpha = if (ready) 1f else 0.45f }) {
        ToggleRow(
            tr("Włącz Shizuku", "Enable Shizuku"),
            if (ready) tr("BTMap użyje Shizuku do odczytu danych z modemu", "BTMap will use Shizuku to read data from the modem") else tr("Odblokuje się po podłączeniu BTMap w Shizuku", "Unlocks once BTMap is authorised in Shizuku"),
            settings.shizukuEnabled && ready,
        ) { on -> if (ready) onChange { it.copy(shizukuEnabled = on) } }
    }
    if (privileged) {
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulseDot(if (hasDump) Btmap.colors.good else Btmap.colors.poor, active = !hasDump)
            Text(
                if (hasDump) tr("Pasma z modemu: aktywne", "Bands from the modem: active") else tr("Włączam dostęp do modemu…", "Enabling modem access…"),
                style = MaterialTheme.typography.bodySmall, color = if (hasDump) Btmap.colors.good else Btmap.colors.muted,
            )
        }
    }
    if (privileged) {
        Spacer(Modifier.height(14.dp))
        Hint(tr("Wymuszanie pasm jest dostępne w osobnej kategorii ustawień.", "Band locking has its own settings category."))
    }

    Spacer(Modifier.height(28.dp))
    RootSection(settings, onChange, rootActive)

    Spacer(Modifier.height(28.dp))
    Text(tr("Diagnostyka modemu", "Modem diagnostics"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    Hint(
        tr(
            "Sprawdza wszystkie drogi do modemu: root, SELinux, interfejs diagnostyczny Qualcomm (/dev/diag), tryb USB, sterowniki i to, co zgłasza modem. Raport zapisuje się w Pobranych i od razu można go wysłać. Nie zawiera numerów IMEI, karty SIM ani telefonu.",
            "Checks every way to reach the modem: root, SELinux, the Qualcomm diagnostic interface (/dev/diag), USB mode, drivers and what the modem reports. The report is saved to Downloads and can be sent right away. It contains no IMEI, SIM or phone numbers.",
        ),
    )
    Spacer(Modifier.height(10.dp))
    DiagnosticsButton(tr("Sprawdź i wyślij", "Check and send"), primary = true)
    if (rootActive) {
        Spacer(Modifier.height(14.dp))
        Hint(
            tr(
                "Nagrywa przez 30 s logi warstwy radiowej modemu (LTE i 5G: MCS, modulacja, CQI, pomiary, RRC) do pliku .qmdl i wyłącza logowanie. Tylko telefony z Qualcommem i rootem.",
                "Records the modem's radio-layer logs (LTE and 5G: MCS, modulation, CQI, measurements, RRC) for 30 s to a .qmdl file, then switches logging off. Qualcomm phones with root only.",
            ),
        )
        Spacer(Modifier.height(8.dp))
        ModemCaptureButton()
    }
}

/** Records 30 s of modem logs and opens the share sheet with the .qmdl and its summary. */
@Composable
private fun ModemCaptureButton() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var second by remember { mutableStateOf<Int?>(null) }
    var summary by remember { mutableStateOf<String?>(null) }
    val seconds = 30
    ActionButton(
        second?.let { tr("Nagrywam… $it/$seconds s", "Recording… $it/$seconds s") } ?: tr("Nagraj logi modemu (30 s)", "Record modem logs (30 s)"),
        enabled = second == null,
    ) {
        second = 0
        summary = null
        scope.launch {
            val result = runCatching { pl.btmap.mobile.tools.ModemCapture.record(context, seconds) { s -> second = s } }.getOrNull()
            second = null
            summary = result?.summary?.lineSequence()?.take(3)?.joinToString("\n") ?: tr("Nie udało się nagrać", "Recording failed")
            if (result != null && result.files.isNotEmpty()) {
                val send = Intent(Intent.ACTION_SEND_MULTIPLE)
                    .setType("*/*")
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(result.files))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { context.startActivity(Intent.createChooser(send, tr("Wyślij logi modemu", "Send modem logs"))) }
            }
        }
    }
    summary?.let {
        Spacer(Modifier.height(6.dp))
        Hint(it)
    }
}

/** Root (su) as the alternative to Shizuku on rooted phones. */
@Composable
private fun RootSection(settings: Settings, onChange: ((Settings) -> Settings) -> Unit, rootActive: Boolean) {
    val installed = remember { pl.btmap.mobile.tools.RootShell.isInstalled() }
    val waiting = settings.rootEnabled && !rootActive
    // The root manager's prompt can take a while; after that, a still inactive root means "denied".
    var denied by remember { mutableStateOf(false) }
    LaunchedEffect(waiting) {
        denied = false
        if (waiting) {
            kotlinx.coroutines.delay(15_000)
            denied = true
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Btmap.colors.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Lock, null, tint = Btmap.colors.accent, modifier = Modifier.size(26.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Root", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulseDot(
                    when {
                        rootActive -> Btmap.colors.good
                        waiting -> Btmap.colors.poor
                        else -> Btmap.colors.faint
                    },
                    active = waiting && !denied,
                )
                Text(
                    when {
                        rootActive -> tr("BTMap ma dostęp roota", "BTMap has root access")
                        waiting && denied -> tr("Brak zgody — zezwól BTMap w Magisk / KernelSU", "Not allowed — allow BTMap in Magisk / KernelSU")
                        waiting -> tr("Czekam na zgodę w menedżerze roota…", "Waiting for the root manager…")
                        installed -> tr("Wykryto root na tym telefonie", "Root detected on this phone")
                        else -> tr("Nie wykryto roota", "No root detected")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (rootActive) Btmap.colors.good else Btmap.colors.muted,
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Hint(
        tr(
            "Na telefonie z rootem (Magisk, KernelSU, APatch) BTMap działa bez Shizuku: nada sobie dostęp do modemu i zmieni tryb sieci. Wymuszanie pasm nadal wymaga Shizuku.",
            "On a rooted phone (Magisk, KernelSU, APatch) BTMap works without Shizuku: it grants itself modem access and can change the network mode. Band locking still needs Shizuku.",
        ),
    )
    Spacer(Modifier.height(10.dp))
    Box(Modifier.graphicsLayer { alpha = if (installed || settings.rootEnabled) 1f else 0.45f }) {
        ToggleRow(
            tr("Użyj roota", "Use root"),
            if (installed || settings.rootEnabled) tr("Menedżer roota zapyta o zgodę", "The root manager will ask for permission")
            else tr("Ten telefon nie ma roota", "This phone is not rooted"),
            settings.rootEnabled,
        ) { on -> if (installed || !on) onChange { it.copy(rootEnabled = on) } }
    }
    if (waiting && denied) {
        Spacer(Modifier.height(8.dp))
        val scope = rememberCoroutineScope()
        val app = LocalContext.current.applicationContext as pl.btmap.mobile.BtmapApp
        WideButton(tr("Zapytaj ponownie", "Ask again")) { scope.launch { app.shizuku.enableRoot() } }
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
    val rootActive by app.shizuku.rootActive.collectAsStateWithLifecycle()
    val available = (status == ShizukuBridge.Status.READY && settings.shizukuEnabled) || rootActive

    if (!available) {
        Card(Modifier.enterAnimation(1)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Btmap.colors.poor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) { BandLockIcon(Btmap.colors.poor) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("Wymaga Shizuku lub roota", "Requires Shizuku or root"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(tr("Podłącz BTMap w Shizuku albo włącz root w Narzędziach.", "Authorise BTMap in Shizuku or enable root in Tools."), style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted)
                }
            }
            Spacer(Modifier.height(14.dp))
            WideButton(tr("Przejdź do Narzędzi", "Go to Tools"), primary = true, onClick = onOpenTools)
        }
        return
    }
    if (!app.shizuku.binderAvailable) {
        Hint(
            tr(
                "Z rootem działa tryb sieci. Blokada pojedynczych pasm wymaga Shizuku (wywołania modemu przez binder).",
                "With root, the network mode works. Locking individual bands needs Shizuku (modem calls through binder).",
            ),
        )
        Spacer(Modifier.height(10.dp))
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
        feedback = if (mtk) BandFeedback(true, tr("Zapisuję pasma w modemie…", "Writing bands to the modem…"), tr("Potem sprawdzam, na jakim paśmie jest SIM ${s.slotIndex + 1}", "Then checking which band SIM ${s.slotIndex + 1} is on"))
            else BandFeedback(true, tr("Wymuszam i sprawdzam…", "Locking and checking…"), tr("SIM ${s.slotIndex + 1} na chwilę przejdzie na 3G, potem przez ~30 s sprawdzam, na jakim paśmie zostaje", "SIM ${s.slotIndex + 1} will briefly switch to 3G, then for ~30 s I check which band it stays on"))
        scope.launch {
            val simLabel = "SIM ${s.slotIndex + 1} · " + Operators.displayName(s.registeredPlmn, s.networkOperatorName)
            val outcome = network.applyBands(s.slotIndex, s.subscriptionId, target)
            feedback = when (outcome) {
                is pl.btmap.mobile.tools.BandLockOutcome.Applied -> BandFeedback(true, tr("Wymuszono pasma", "Bands locked"), tr("$simLabel · teraz ${outcome.band}", "$simLabel · now ${outcome.band}"))
                is pl.btmap.mobile.tools.BandLockOutcome.Ignored -> BandFeedback(false, tr("Modem zignorował blokadę", "The modem ignored the lock"), tr("$simLabel · nadal ${outcome.band}. Ten telefon nie pozwala blokować pasm bez roota — użyj trybu sieci.", "$simLabel · still ${outcome.band}. This phone does not allow band locking without root — use the network mode."))
                pl.btmap.mobile.tools.BandLockOutcome.NoService -> BandFeedback(false, tr("Brak zasięgu na wybranych pasmach", "No service on the selected bands"), tr("$simLabel · odblokuj więcej pasm", "$simLabel · unlock more bands"))
                pl.btmap.mobile.tools.BandLockOutcome.Restored -> BandFeedback(true, tr("Odblokowano wszystkie pasma", "All bands unlocked"), simLabel)
                is pl.btmap.mobile.tools.BandLockOutcome.Failed -> BandFeedback(false, tr("Nie udało się", "Failed"), outcome.message)
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
        feedback = BandFeedback(true, tr("Przywracam domyślne pasma…", "Restoring default bands…"), tr("Wszystkie karty SIM", "All SIM cards"))
        scope.launch {
            val results = sims.map { s -> network.resetBands(s.slotIndex, s.subscriptionId) to s }
            sims.forEach { store.setActive(it.slotIndex, pl.btmap.mobile.tools.BandSelection()) }
            val failed = results.filter { it.first is pl.btmap.mobile.tools.BandLockOutcome.Failed }
            feedback = if (failed.isEmpty()) BandFeedback(true, tr("Przywrócono domyślne pasma", "Default bands restored"), tr("Wszystkie pasma odblokowane na ${sims.size} kartach SIM", "All bands unlocked on ${sims.size} SIM cards"))
                else BandFeedback(false, tr("Nie udało się przywrócić pasm", "Could not restore the bands"), failed.joinToString { "SIM ${it.second.slotIndex + 1}" })
            lte = lteBands.toSet(); nr = nrBands.toSet()
            reload++
            busy = false
        }
    }

    fun applyMode(preset: pl.btmap.mobile.tools.NetworkModePreset) {
        val s = sim ?: return
        modeBusy = true
        if (preset == pl.btmap.mobile.tools.NetworkModePreset.NR) {
            feedback = BandFeedback(true, tr("Szukam 5G SA…", "Looking for 5G SA…"), tr("SIM ${s.slotIndex + 1}: do 40 s. Jeśli go tu nie ma, przywrócę poprzedni tryb", "SIM ${s.slotIndex + 1}: up to 40 s. If it is not available here, I will restore the previous mode"))
        }
        scope.launch {
            val error = network.setMode(s.slotIndex, preset)
            val simLabel = "SIM ${s.slotIndex + 1} · " + Operators.displayName(s.registeredPlmn, s.networkOperatorName)
            feedback = if (error == null) {
                mode = preset
                BandFeedback(true, tr("Tryb sieci: ${preset.label}", "Network mode: ${preset.label}"), simLabel)
            } else BandFeedback(false, tr("Nie udało się zmienić trybu sieci", "Could not change the network mode"), error)
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
                            if (r) tr("Pasma ograniczone", "Bands restricted") else tr("Wszystkie pasma odblokowane", "All bands unlocked"),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        if (restricted) describe(current) else tr("Modem sam wybiera pasma", "The modem picks bands itself"),
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
                    modem == null -> tr("Sprawdzam modem…", "Checking the modem…")
                    mtk -> tr("Sterowanie: bezpośrednio w modemie MediaTek · pasma odczytane z modemu", "Control: directly in the MediaTek modem · bands read from the modem")
                    else -> tr("Sterowanie: przez Androida (zależnie od modemu)", "Control: through Android (depends on the modem)")
                },
                style = MaterialTheme.typography.labelSmall, color = if (mtk) Btmap.colors.good else Btmap.colors.muted,
            )
            if (restricted) {
                Spacer(Modifier.height(12.dp))
                WideButton(tr("Odblokuj wszystkie pasma", "Unlock all bands")) {
                    lte = lteBands.toSet(); nr = nrBands.toSet()
                    apply(pl.btmap.mobile.tools.BandSelection(), tr("wszystkie pasma", "all bands"))
                }
            }
        }

        // ---- Network mode (works on every phone) ----
        Card(Modifier.enterAnimation(2)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Tryb sieci", "Network mode"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (modeBusy) Text(tr("zmieniam…", "changing…"), style = MaterialTheme.typography.labelMedium, color = Btmap.colors.accent)
            }
            Text(tr("Które technologie może używać karta. Działa na każdym telefonie.", "Which technologies the SIM may use. Works on every phone."), style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted)
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
                    if (works) tr("Modem tego telefonu respektuje blokadę pasm.", "This phone's modem honours band locking.")
                    else tr("Modem tego telefonu ignoruje blokadę pasm (ogranicza tylko skanowanie w tle). Bez roota zostaje tryb sieci.", "This phone's modem ignores band locking (it only limits background scans). Without root, the network mode is what remains."),
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
            Text(if (mtk) tr("obsługiwane przez modem (odczytane z modemu)", "supported by the modem (read from the modem)") else tr("wykryte przez modem tego telefonu", "detected by this phone's modem"), style = MaterialTheme.typography.labelSmall, color = Btmap.colors.muted)
        }

        // ---- Actions ----
        Column(Modifier.enterAnimation(4), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val blocked = lte.isEmpty() || nr.isEmpty()
            WideButton(
                when {
                    busy -> tr("Wysyłam do modemu…", "Sending to the modem…")
                    blocked -> tr("Zostaw co najmniej jedno pasmo LTE i 5G", "Keep at least one LTE and one 5G band")
                    changed -> tr("Wymuś wybrane pasma i sprawdź", "Lock the selected bands and check")
                    else -> tr("Bez zmian", "No changes")
                },
                primary = changed && !blocked && !busy,
            ) { if (changed && !blocked && !busy) apply(selection, describe(selection)) }
            if (!selection.isUnrestricted) {
                ActionButton(tr("Zapisz jako konfigurację…", "Save as configuration…")) { naming = true }
            }
        }

        // ---- Saved configurations ----
        if (presets.isNotEmpty()) {
            SectionLabel(tr("Zapisane konfiguracje", "Saved configurations"), 5)
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
                                        Text(tr("AKTYWNA", "ACTIVE"), style = MaterialTheme.typography.labelSmall, color = Btmap.colors.accent)
                                    }
                                }
                                Text(describe(preset.selection), style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted)
                            }
                            if (!isActive) {
                                ActionButton(tr("Wymuś", "Apply"), primary = true, enabled = !busy) {
                                    lte = preset.selection.lte.ifEmpty { lteBands.toSet() }
                                    nr = preset.selection.nr.ifEmpty { nrBands.toSet() }
                                    apply(preset.selection, preset.name)
                                }
                            }
                            TextButton(onClick = { deleting = preset }) {
                                Text(tr("Usuń", "Delete"), color = Btmap.colors.bad, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
        // ---- Reset ----
        Card(Modifier.enterAnimation(6)) {
            Text(tr("Domyślne pasma", "Default bands"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                tr("Przywraca fabryczne ustawienia: wszystkie pasma, które obsługuje modem, na obu kartach SIM.", "Restores the factory setting: every band the modem supports, on both SIM cards."),
                style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted,
            )
            Spacer(Modifier.height(12.dp))
            WideButton(tr("Resetuj pasma do domyślnych", "Reset bands to default"), danger = true) { if (!busy) confirmReset = true }
        }
        Hint(if (mtk) tr("Blokada jest zapisana w modemie. Stan powyżej jest odczytywany z modemu przy każdym otwarciu tej strony.", "The lock is stored in the modem. The state above is read from the modem every time this page opens.") else tr("Wymuszenie działa do ponownego uruchomienia telefonu — potem zastosuj konfigurację jeszcze raz.", "The lock lasts until the phone restarts — then apply the configuration again."))
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(tr("Przywrócić domyślne pasma?", "Restore default bands?")) },
            text = { Text(tr("Wszystkie pasma obsługiwane przez modem zostaną odblokowane na obu kartach SIM.", "Every band the modem supports will be unlocked on both SIM cards.")) },
            confirmButton = { TextButton(onClick = { confirmReset = false; resetAll() }) { Text(tr("Resetuj", "Reset"), color = Btmap.colors.bad) } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(tr("Anuluj", "Cancel")) } },
            containerColor = Btmap.colors.card,
        )
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(tr("Nazwa konfiguracji", "Configuration name")) },
            text = {
                Column {
                    Text(describe(selection), style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                        placeholder = { Text(tr("np. Dom — 1800 + n78", "e.g. Home — 1800 + n78")) },
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Btmap.colors.accent, cursorColor = Btmap.colors.accent),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val saved = store.savePreset(name, selection)
                    naming = false
                    feedback = BandFeedback(true, tr("Zapisano konfigurację", "Configuration saved"), saved.name)
                }) { Text(tr("Zapisz", "Save"), color = Btmap.colors.accent) }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text(tr("Anuluj", "Cancel")) } },
            containerColor = Btmap.colors.card,
        )
    }
    deleting?.let { preset ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr("Usunąć „${preset.name}”?", "Delete “${preset.name}”?")) },
            text = { Text(tr("Konfiguracja zniknie z listy. Pasma ustawione teraz w modemie się nie zmienią.", "The configuration will be removed from the list. The bands currently set in the modem will not change.")) },
            confirmButton = { TextButton(onClick = { store.deletePreset(preset.id); deleting = null }) { Text(tr("Usuń", "Delete"), color = Btmap.colors.bad) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("Anuluj", "Cancel")) } },
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
                Text(if (all) tr("Odznacz wszystkie", "Deselect all") else tr("Zaznacz wszystkie", "Select all"), color = colors.accent, style = MaterialTheme.typography.labelMedium)
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
    if (s.isUnrestricted) return tr("wszystkie pasma", "all bands")
    val parts = mutableListOf<String>()
    parts += if (s.lte.isEmpty()) tr("LTE: wszystkie", "LTE: all") else "LTE: " + s.lte.sorted().joinToString(", ") { "B$it" }
    parts += if (s.nr.isEmpty()) tr("5G: wszystkie", "5G: all") else "5G: " + s.nr.sorted().joinToString(", ") { "n$it" }
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
    Label(tr("Motyw", "Theme"))
    Segmented(
        options = listOf(ThemeMode.SYSTEM to "Auto", ThemeMode.LIGHT to tr("Jasny", "Light"), ThemeMode.DARK to tr("Ciemny", "Dark")),
        selected = settings.theme,
        onSelect = { mode -> onChange { it.copy(theme = mode) } },
    )
    Spacer(Modifier.height(14.dp))
    Label(tr("Język", "Language"))
    Segmented(
        options = listOf(AppLanguage.SYSTEM to "Auto", AppLanguage.PL to "Polski", AppLanguage.EN to "English"),
        selected = settings.language,
        onSelect = { lang -> onChange { it.copy(language = lang) } },
    )
    Spacer(Modifier.height(14.dp))
    ToggleRow(tr("Sąsiednie komórki", "Neighbour cells"), tr("Lista mierzonych komórek pod kartą operatora", "List of measured cells under the operator card"), settings.showNeighbours) { on ->
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
            if (available) tr("Pasma odczytywane z modemu", "Bands read from the modem") else tr("Pasma z modemu: wyłączone", "Bands from the modem: off"),
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        )
    }
    Spacer(Modifier.height(6.dp))
    if (available) {
        Hint(tr("Agregacja, szerokości i noga 5G pochodzą bezpośrednio z konfiguracji nośnych w modemie.", "Aggregation, bandwidths and the 5G leg come straight from the modem's carrier configuration."))
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
                tr("Ostatni odczyt: ", "Last read: ") + m.entries.sortedBy { it.key }.joinToString(" · ") { (slot, list) ->
                    "SIM ${slot + 1}: " + if (list.isEmpty()) tr("brak transmisji", "no transmission") else list.joinToString(" + ") { pc ->
                        (if (pc.rat == pl.btmap.core.model.Rat.NR) "n" else "B") + (pc.band ?: "?") + " " + ((pc.bandwidthKhz ?: 0) / 1000) + " MHz"
                    }
                }
            } ?: tr("Ostatni odczyt: brak danych", "Last read: no data"),
            style = MaterialTheme.typography.labelSmall, color = Btmap.colors.muted,
        )
    } else {
        Hint(
            tr("Android pokazuje dokładne nośne (agregacja, 5G NSA) tylko aplikacjom z uprawnieniem DUMP. ", "Android shows exact carriers (aggregation, 5G NSA) only to apps with the DUMP permission. ") +
                tr("Nadaj je raz z komputera — bez roota, działa do odinstalowania aplikacji:", "Grant it once from a computer — no root needed, it lasts until the app is uninstalled:"),
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
        Hint(tr("Stuknij, aby skopiować. Bez tego pasma w agregacji są szacowane.", "Tap to copy. Without it, aggregation bands are estimated."))
    }
}

@Composable
private fun MeasurementsPage(settings: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    val pending = (LocalContext.current.applicationContext as pl.btmap.mobile.BtmapApp).core.coverage.pending
    ToggleRow(
        tr("Mapa zasięgu", "Coverage map"),
        if (settings.coverageConsent == true) tr("Wysyłam anonimowe pomiary · w kolejce: $pending", "Sending anonymous measurements · queued: $pending") else tr("Udostępniaj anonimowe pomiary do wspólnej mapy", "Share anonymous measurements for the shared map"),
        settings.coverageConsent == true,
    ) { on -> onChange { it.copy(coverageConsent = on) } }
    Spacer(Modifier.height(14.dp))
    Label(tr("Odświeżanie na ekranie", "On-screen refresh"))
    Segmented(
        options = Settings.REFRESH_OPTIONS.map { it to "${it / 1000} s" },
        selected = settings.liveRefreshMs,
        onSelect = { ms -> onChange { it.copy(liveRefreshMs = ms) } },
    )
    Spacer(Modifier.height(10.dp))
    Hint(
        tr("W tle aplikacja sama zwalnia: przy włączonym ekranie odświeża co 5 s, a przy wyłączonym ", "In the background the app slows down on its own: with the screen on it refreshes every 5 s, and with it off ") +
            tr("nie używa własnego GPS i opiera się na zgłoszeniach modemu, żeby oszczędzać baterię.", "it uses no GPS of its own and relies on modem reports to save battery."),
    )
}

@Composable
private fun NotificationPage(settings: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    ToggleRow(tr("Sygnał", "Signal"), tr("Przybliżone RSRP w treści", "Approximate RSRP in the text"), settings.notificationSignal) { on ->
        onChange { it.copy(notificationSignal = on) }
    }
    ToggleRow(tr("Stacja BTMap", "BTMap station"), tr("Adres i odległość zamiast numeru eNB", "Address and distance instead of the eNB number"), settings.notificationStation) { on ->
        onChange { it.copy(notificationStation = on) }
    }
    ToggleRow(tr("Druga karta SIM", "Second SIM"), tr("Skrót drugiej karty w nagłówku", "Second SIM summary in the header"), settings.notificationOtherSim) { on ->
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
        label = { Text(tr("Adres API", "API address")) },
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
        ActionButton(if (checking) tr("Sprawdzam…", "Checking…") else tr("Sprawdź", "Check"), enabled = !checking) {
            checking = true
            scope.launch {
                status = runCatching {
                    val health = BtmapApi(url.trim()).health()
                    val towers = health.optJSONObject("stats")?.optInt("towers")
                    true to buildString {
                        append(tr("Połączono", "Connected"))
                        health.optString("version").takeIf { it.isNotEmpty() }?.let { append(" · BTMap $it") }
                        towers?.let { append(tr(" · $it stacji", " · $it stations")) }
                    }
                }.getOrElse { false to tr("Brak połączenia: ${it.message ?: it.javaClass.simpleName}", "No connection: ${it.message ?: it.javaClass.simpleName}") }
                checking = false
            }
        }
        ActionButton(tr("Zapisz", "Save"), enabled = changed, primary = true) {
            onChange { it.copy(apiBaseUrl = url.trim().trimEnd('/')) }
        }
        if (settings.apiBaseUrl != BuildConfig.API_BASE_URL) {
            ActionButton(tr("Domyślny", "Default")) {
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

    StatRow(tr("Komórki w logu", "Cells in log"), logSize.toString())
    StatRow(tr("Nauczone komórki", "Learned cells"), learned.toString())
    Hint(tr("Nauczone komórki pozwalają rozpoznać pasma w agregacji i podpisać sąsiednie komórki numerem eNB.", "Learned cells make it possible to recognise aggregation bands and label neighbour cells with their eNB."))
    Spacer(Modifier.height(12.dp))
    // .ntm is what BTSearch's log analyzer and NetMonster read (BTSearch takes any .csv as Netmonitor CSV).
    fun export(extension: String, mime: String, text: () -> String) {
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
                File(dir, "btmap-log-$stamp.$extension").apply { writeText(text()) }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, tr("Eksportuj log", "Export log")))
        }
    }
    WideButton(tr("Eksportuj", "Export"), primary = true) {
        if (logSize > 0) export("ntm", "application/octet-stream") { core.log.toNtm() }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton(tr("Eksportuj CSV", "Export CSV"), enabled = logSize > 0) {
            export("csv", "text/csv") { core.log.toCsv() }
        }
        ActionButton(tr("Wyczyść log", "Clear log"), enabled = logSize > 0, danger = true) { confirm = "log" }
    }
    Spacer(Modifier.height(8.dp))
    ActionButton(tr("Zapomnij nauczone komórki", "Forget learned cells"), enabled = learned > 0, danger = true) { confirm = "dictionary" }

    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (what == "log") tr("Wyczyścić log?", "Clear the log?") else tr("Zapomnieć nauczone komórki?", "Forget learned cells?")) },
            text = {
                Text(
                    if (what == "log") tr("Usunie $logSize wpisów z telefonu. Tego nie da się cofnąć.", "This removes $logSize entries from the phone. It cannot be undone.")
                    else tr("Aplikacja nauczy się ich od nowa, ale do tego czasu pasma w agregacji mogą być nieznane.", "The app will learn them again, but until then aggregation bands may be unknown."),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (what == "log") core.clearLog() else core.clearDictionary()
                    dictionaryVersion++
                    confirm = null
                }) { Text(tr("Usuń", "Delete"), color = Btmap.colors.bad) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(tr("Anuluj", "Cancel")) } },
            containerColor = Btmap.colors.card,
        )
    }
}

@Composable
private fun About() {
    val context = LocalContext.current
    StatRow(tr("Wersja", "Version"), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    StatRow(tr("Licencja", "License"), "MIT")
    Spacer(Modifier.height(10.dp))
    ActionButton(tr("Kod źródłowy na GitHubie", "Source code on GitHub")) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/btmap-pl/btmap-mobile")))
    }
    Spacer(Modifier.height(10.dp))
    DiagnosticsButton(tr("Zapisz diagnostykę", "Save diagnostics"), primary = false)
}

/**
 * Live downlink figures straight from the modem's PDSCH statistics (root, Qualcomm DIAG): the
 * session runs while this page is open and stops when it is left.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModemLivePage(settings: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    val colors = Btmap.colors
    DisposableEffect(Unit) {
        pl.btmap.mobile.tools.DiagLive.start()
        onDispose { pl.btmap.mobile.tools.DiagLive.stop() }
    }
    val state by pl.btmap.mobile.tools.DiagLive.state.collectAsStateWithLifecycle()
    val running = state as? pl.btmap.mobile.tools.DiagLive.State.Running
    val stats = running?.stats

    Card(Modifier.enterAnimation(1)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulseDot(
                when (state) {
                    is pl.btmap.mobile.tools.DiagLive.State.Running -> if (stats != null) colors.good else colors.poor
                    is pl.btmap.mobile.tools.DiagLive.State.Failed -> colors.bad
                    else -> colors.faint
                },
                active = state is pl.btmap.mobile.tools.DiagLive.State.Running,
            )
            Text(
                when (val st = state) {
                    pl.btmap.mobile.tools.DiagLive.State.Off -> tr("Zatrzymano", "Stopped")
                    is pl.btmap.mobile.tools.DiagLive.State.Connecting -> when (st.stage) {
                        pl.btmap.mobile.tools.DiagLive.Stage.STARTING -> tr("Uruchamiam diag_socket_log…", "Starting diag_socket_log…")
                        pl.btmap.mobile.tools.DiagLive.Stage.RANGES -> tr("Połączono · pytam modem o zakres logów…", "Connected · asking the modem for log ranges…")
                        pl.btmap.mobile.tools.DiagLive.Stage.MASK -> tr("Włączam log PDSCH (próba ${st.attempt})…", "Switching the PDSCH log on (attempt ${st.attempt})…")
                        pl.btmap.mobile.tools.DiagLive.Stage.LISTENING -> tr("Słucham…", "Listening…")
                    }
                    is pl.btmap.mobile.tools.DiagLive.State.Failed -> tr("Błąd: ", "Error: ") + st.message
                    is pl.btmap.mobile.tools.DiagLive.State.Running ->
                        if (stats == null) tr(
                            "Czekam na dane · maska ${if (st.maskConfirmed) "przyjęta" else "bez potwierdzenia"} (prób ${st.maskAttempts}) · ramek ${st.frames}, PDSCH ${st.packets}",
                            "Waiting for data · mask ${if (st.maskConfirmed) "accepted" else "not confirmed"} (attempts ${st.maskAttempts}) · frames ${st.frames}, PDSCH ${st.packets}",
                        )
                        else tr("Na żywo · ${"%.0f".format(stats.blocksPerSecond)} bloków/s", "Live · ${"%.0f".format(stats.blocksPerSecond)} blocks/s")
                },
                style = MaterialTheme.typography.bodyMedium, color = colors.muted,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(stats?.let { "%.1f".format(it.mbps) } ?: "—", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text(" Mb/s DL", style = MaterialTheme.typography.titleMedium, color = colors.muted, modifier = Modifier.padding(bottom = 6.dp))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("MCS", stats?.let { "%.0f · %d".format(it.mcsAvg, it.mcsLast) } ?: "—", Modifier.weight(1f))
            StatTile("BLER", stats?.let { "%.1f%%".format(it.bler * 100) } ?: "—", Modifier.weight(1f))
            StatTile("RB", stats?.let { "%.0f · max %d".format(it.rbsAvg, it.rbsMax) } ?: "—", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Spatial layers per block = MIMO rank (up to 4 with 4×4 MIMO).
            StatTile(tr("Warstwy (rank)", "Layers (rank)"), stats?.let { "%.1f · max %d".format(it.layersAvg, it.layersMax) } ?: "—", Modifier.weight(1f))
            StatTile(tr("Retransmisje", "Retransmissions"), stats?.let { "%.0f%%".format(it.retransmissions * 100) } ?: "—", Modifier.weight(1f))
        }
        stats?.let { st ->
            Spacer(Modifier.height(8.dp))
            Text(
                "Rank " + (1..4).filter { (st.layers[it] ?: 0.0) > 0 }.joinToString("  ") { "$it: ${"%.0f".format((st.layers[it] ?: 0.0) * 100)}%" },
                style = MaterialTheme.typography.labelSmall, color = colors.muted,
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(tr("Modulacja", "Modulation"), style = MaterialTheme.typography.labelMedium, color = colors.muted)
        Spacer(Modifier.height(6.dp))
        val mods = listOf(2 to "QPSK", 4 to "16QAM", 6 to "64QAM", 8 to "256QAM")
        val modColors = listOf(colors.bad, colors.poor, colors.fair, colors.good)
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(colors.meterTrack)) {
            mods.forEachIndexed { i, (q, _) ->
                val share = stats?.modulation?.get(q) ?: 0.0
                if (share > 0) Box(Modifier.weight(share.toFloat()).fillMaxHeight().background(modColors[i]))
            }
            val rest = 1.0 - (stats?.modulation?.values?.sum() ?: 0.0)
            if (rest > 0.0001) Box(Modifier.weight(rest.toFloat()).fillMaxHeight())
        }
        Spacer(Modifier.height(6.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            mods.forEachIndexed { i, (q, name) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(modColors[i]))
                    Text(" $name ${stats?.modulation?.get(q)?.let { "%.0f%%".format(it * 100) } ?: ""}", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                }
            }
        }
    }
    Card(Modifier.enterAnimation(2)) {
        ToggleRow(
            tr("Pokazuj na ekranie Live", "Show on the Live screen"),
            tr("Karta z Mb/s, MCS, BLER, rank i modulacją pod kartami SIM", "A card with Mb/s, MCS, BLER, rank and modulation below the SIM cards"),
            settings.modemOnLive,
        ) { on -> onChange { it.copy(modemOnLive = on) } }
    }
    Hint(
        tr(
            "Dane prosto z logów modemu Qualcomm (DIAG): statystyki PDSCH łącza w dół LTE z ostatniej sekundy. MCS: średni · ostatni. Ekran działa tylko gdy jest otwarty; po wyjściu logowanie w modemie się wyłącza. 5G NR wkrótce.",
            "Straight from the Qualcomm modem's logs (DIAG): LTE downlink PDSCH statistics over the last second. MCS: average · last. Runs only while open; leaving switches the modem's logging off. 5G NR coming soon.",
        ),
    )
}

/**
 * Runs the modem check, saves the report to Downloads and opens the share sheet, so a tester can
 * send it straight from the phone (Messenger, e-mail…).
 */
@Composable
private fun DiagnosticsButton(label: String, primary: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<String?>(null) }
    ActionButton(if (running) tr("Sprawdzam modem…", "Checking the modem…") else label, enabled = !running, primary = primary) {
        running = true
        scope.launch {
            val result = runCatching { pl.btmap.mobile.Diagnostics.save(context) }.getOrNull()
            running = false
            saved = result?.name ?: tr("błąd zapisu", "save error")
            result?.let {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, it.uri)
                    .putExtra(Intent.EXTRA_SUBJECT, it.name)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { context.startActivity(Intent.createChooser(send, tr("Wyślij diagnostykę", "Send diagnostics"))) }
            }
        }
    }
    saved?.let {
        Spacer(Modifier.height(6.dp))
        Hint(tr("Zapisano w Pobranych: $it", "Saved to Downloads: $it"))
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
