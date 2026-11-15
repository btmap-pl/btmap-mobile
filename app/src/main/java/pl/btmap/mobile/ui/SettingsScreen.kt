package pl.btmap.mobile.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.LinearProgressIndicator
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.btmap.core.stations.DbOperators
import pl.btmap.core.stations.ImportState
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
                Card(Modifier.enterAnimation(1)) {
                    when (current) {
                        SettingsPage.DATABASE -> DatabasePage(settings, onChange, core)
                        SettingsPage.APPEARANCE -> AppearancePage(settings, onChange)
                        SettingsPage.MEASUREMENTS -> MeasurementsPage(settings, onChange)
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

    Label("Źródło")
    Segmented(
        options = StationSource.entries.map { it to it.label },
        selected = settings.database,
        onSelect = { src -> onChange { it.copy(database = src) } },
    )
    Spacer(Modifier.height(10.dp))
    Hint(
        when (settings.database) {
            StationSource.BTMAP -> "Stacje dodane i zweryfikowane przez społeczność BTMap, bez danych z rejestru UKE."
            StationSource.BTSEARCH -> "Baza btsearch.pl tworzona przez pasjonatów. Plik obejmuje całą Polskę, więc pobranie trwa kilka minut; zapisywani są tylko wybrani operatorzy."
        },
    )

    AnimatedVisibility(settings.database == StationSource.BTSEARCH, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column {
            Spacer(Modifier.height(18.dp))
            Label("Operatorzy")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DbOperators.ALL.forEach { op ->
                    val on = op.mnc in settings.btsearchMncs
                    val interaction = remember { MutableInteractionSource() }
                    Pill(
                        op.name,
                        if (on) Btmap.colors.accent else Btmap.colors.muted,
                        style = if (on) PillStyle.FILLED else PillStyle.OUTLINED,
                        modifier = Modifier
                            .pressScale(interaction)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(interaction, LocalIndication.current) {
                                onChange {
                                    val next = if (on) it.btsearchMncs - op.mnc else it.btsearchMncs + op.mnc
                                    it.copy(btsearchMncs = next.ifEmpty { it.btsearchMncs })
                                }
                            },
                    )
                }
            }
            val downloaded = info?.detail?.split(',')?.mapNotNull(String::toIntOrNull)?.toSet()
            if (info != null && downloaded != settings.btsearchMncs) {
                Spacer(Modifier.height(8.dp))
                Text("Zmieniono operatorów — pobierz bazę ponownie, aby to zastosować.", style = MaterialTheme.typography.bodySmall, color = Btmap.colors.poor)
            }
        }
    }

    Spacer(Modifier.height(18.dp))
    Label("Automatyczna aktualizacja")
    Segmented(
        options = Settings.DB_REFRESH_OPTIONS,
        selected = settings.dbRefreshDays,
        onSelect = { days -> onChange { it.copy(dbRefreshDays = days) } },
    )
    Spacer(Modifier.height(8.dp))
    ToggleRow("Tylko przez Wi-Fi", "Nie zużywa danych komórkowych; pobiera też tylko przy niewyczerpanej baterii", settings.dbWifiOnly) { on ->
        onChange { it.copy(dbWifiOnly = on) }
    }

    Spacer(Modifier.height(14.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Btmap.colors.cardRaised)
            .padding(14.dp),
    ) {
        Column {
            when (val st = state) {
                is ImportState.Running -> {
                    Text("Pobieram ${st.source.label}…", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)),
                        color = Btmap.colors.accent,
                        trackColor = Btmap.colors.meterTrack,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${formatBytes(st.bytes)} · ${formatCount(st.cells)} komórek zapisanych",
                        style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted,
                    )
                }
                else -> {
                    if (info != null) {
                        StatRow("Zaktualizowano", SimpleDateFormat("d.MM.yyyy HH:mm", Locale.getDefault()).format(Date(info.updatedAtMillis)))
                        StatRow("Stacje", formatCount(info.sites))
                        StatRow("Komórki", formatCount(info.cells))
                    } else {
                        Text("Baza nie została jeszcze pobrana.", style = MaterialTheme.typography.bodyMedium, color = Btmap.colors.muted)
                    }
                    if (st is ImportState.Failed && st.source == settings.database) {
                        Spacer(Modifier.height(6.dp))
                        Text("Ostatnie pobieranie nie powiodło się: ${st.message}", style = MaterialTheme.typography.bodySmall, color = Btmap.colors.bad)
                    }
                    if (info != null && info.cells == 0 && settings.database == StationSource.BTMAP) {
                        Spacer(Modifier.height(6.dp))
                        Text("W BTMap nie ma jeszcze stacji spoza UKE.", style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted)
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state is ImportState.Running) {
            ActionButton("Anuluj", danger = true) { DatabaseWorker.cancel(context) }
        } else {
            ActionButton(if (info == null) "Pobierz teraz" else "Aktualizuj teraz", primary = true) {
                DatabaseWorker.runNow(context, settings)
            }
            ActionButton("Usuń bazę", enabled = info != null, danger = true) { confirmDelete = true }
        }
    }
    if (settings.dbWifiOnly && state !is ImportState.Running) {
        Spacer(Modifier.height(6.dp))
        Hint("Pobieranie ruszy, gdy telefon będzie połączony z Wi-Fi.")
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

private fun formatCount(n: Int): String = "%,d".format(Locale("pl"), n).replace('\u00A0', ' ')

private fun formatBytes(b: Long): String = when {
    b >= 1_000_000 -> "%.1f MB".format(b / 1_000_000.0)
    b >= 1_000 -> "${b / 1_000} kB"
    else -> "$b B"
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
