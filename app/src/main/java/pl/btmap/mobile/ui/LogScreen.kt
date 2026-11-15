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
import androidx.compose.foundation.lazy.itemsIndexed
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

/** Serving cells seen during this session, newest first. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LogScreen(entries: List<LogEntry>, state: LiveState, contentPadding: PaddingValues, onOpen: (LogEntry) -> Unit) {
    if (entries.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(contentPadding).padding(24.dp)) {
            Text("Log jest pusty", style = MaterialTheme.typography.titleMedium)
            Text("Tu pojawi się każda komórka, na której telefon był zalogowany.", color = Btmap.colors.muted)
        }
        return
    }
    val time = SimpleDateFormat("HH:mm", Locale.getDefault())
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            Text(
                "DZIŚ · ${entries.size}", style = MaterialTheme.typography.labelSmall, color = Btmap.colors.muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        itemsIndexed(entries, key = { _, e -> e.key }) { index, e ->
            val id = e.identity
            val station = state.stationOf(id)
            val interaction = remember { MutableInteractionSource() }
            Column(
                Modifier
                    .animateItemPlacement()
                    .enterAnimation(index.coerceAtMost(10))
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
                    )
                    Text(
                        "${Operators.byPlmn(id.plmn)?.displayName ?: id.plmn ?: ""}  ${time.format(Date(e.lastSeenMillis))}",
                        style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted,
                    )
                }
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    id.band?.let { Pill(it.label, Btmap.colors.accent) }
                    Text(
                        station?.address ?: id.band?.shortName.orEmpty(),
                        color = if (station != null) Btmap.colors.accent else Btmap.colors.muted,
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Divider(Modifier.padding(horizontal = 20.dp))
        }
    }
}
