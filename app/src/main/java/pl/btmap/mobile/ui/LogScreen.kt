package pl.btmap.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import pl.btmap.core.model.CellIdentity
import pl.btmap.core.model.CellRole
import java.util.Calendar
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pl.btmap.core.LiveState
import pl.btmap.core.LogEntry
import pl.btmap.core.model.Rat
import pl.btmap.core.radio.Operators
import pl.btmap.mobile.ui.theme.Btmap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Cells the phone was connected to, grouped by day, newest first. Survives restarts. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LogScreen(
    entries: List<LogEntry>,
    state: LiveState,
    contentPadding: PaddingValues,
    onOpen: (LogEntry) -> Unit,
    onClear: () -> Unit,
    onNeedStation: (CellIdentity) -> Unit,
) {
    if (entries.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(contentPadding).padding(24.dp).enterAnimation()) {
            Text("Log jest pusty", style = MaterialTheme.typography.titleMedium)
            Text("Tu pojawi się każda komórka, z którą telefon był połączony.", color = Btmap.colors.muted)
        }
        return
    }
    var confirmClear by remember { mutableStateOf(false) }
    val time = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val groups = remember(entries) { entries.groupBy { dayLabel(it.firstSeenMillis) } }
    val order = remember(entries) { entries.withIndex().associate { it.value.key to it.index } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "header") {
            Row(Modifier.padding(start = 20.dp, end = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${entries.size} ${cellsWord(entries.size)} · ${entries.count { it.lastSeenMillis > System.currentTimeMillis() - 10_000 }} teraz",
                    style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { confirmClear = true }) {
                    Text("Wyczyść", color = Btmap.colors.muted, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        groups.forEach { (day, dayEntries) ->
            stickyHeader(key = "day-$day") {
                Text(
                    day.uppercase(),
                    style = MaterialTheme.typography.labelSmall, color = Btmap.colors.muted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            items(dayEntries, key = { it.key }) { e ->
                LogRow(e, state, time, Modifier.animateItemPlacement().enterAnimation((order[e.key] ?: 0).coerceAtMost(10)), onOpen, onNeedStation)
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Wyczyścić log?") },
            text = { Text("Usunie ${entries.size} ${cellsWord(entries.size)} z telefonu. Tego nie da się cofnąć.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClear() }) { Text("Wyczyść", color = Btmap.colors.bad) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Anuluj") } },
            containerColor = Btmap.colors.card,
        )
    }
}

@Composable
private fun LogRow(
    e: LogEntry,
    state: LiveState,
    time: SimpleDateFormat,
    modifier: Modifier,
    onOpen: (LogEntry) -> Unit,
    onNeedStation: (CellIdentity) -> Unit,
) {
    val id = e.identity
    val station = state.stationOf(id)
    LaunchedEffect(e.key) { onNeedStation(id) }
    val interaction = remember { MutableInteractionSource() }
    val live = e.lastSeenMillis > System.currentTimeMillis() - 10_000
    Column(modifier) {
        Column(
            Modifier
                .pressScale(interaction)
                .clickable(interaction, LocalIndication.current) { onOpen(e) }
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(
                    when (id.rat) { Rat.NR -> "5G"; Rat.LTE -> "4G"; Rat.WCDMA -> "3G"; Rat.GSM -> "2G" },
                    MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    listOfNotNull(id.nodeLabel, id.areaCode?.toString(), id.physicalId?.toString()).joinToString("  |  "),
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (live) {
                    PulseDot(Btmap.colors.good, active = true, modifier = Modifier.padding(end = 2.dp))
                }
                Text(
                    "${Operators.byPlmn(e.plmn)?.displayName ?: e.plmn ?: ""}  ${time.format(Date(e.lastSeenMillis))}",
                    style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted,
                )
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                id.band?.let { Pill(it.label, Btmap.colors.accent) }
                if (e.lastRole == CellRole.SECONDARY) Pill("SCell", Btmap.colors.muted, style = PillStyle.DASHED)
                Text(
                    station?.address ?: id.band?.shortName.orEmpty(),
                    color = if (station != null) Btmap.colors.accent else Btmap.colors.muted,
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                e.bestDbm?.let {
                    Text("$it", style = MaterialTheme.typography.bodySmall, color = powerQuality(it, id.rat).color())
                }
            }
        }
        Divider(Modifier.padding(horizontal = 20.dp))
    }
}

private fun dayLabel(millis: Long): String {
    val cal = Calendar.getInstance()
    val today = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
    cal.add(Calendar.DAY_OF_YEAR, -1)
    val yesterday = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
    cal.timeInMillis = millis
    val day = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
    return when (day) {
        today -> "Dziś"
        yesterday -> "Wczoraj"
        else -> SimpleDateFormat("EEEE, d MMMM yyyy", Locale("pl")).format(Date(millis))
    }
}

private fun cellsWord(n: Int) = when {
    n == 1 -> "komórka"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "komórki"
    else -> "komórek"
}
