package pl.btmap.mobile.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.btmap.core.CellEvent
import pl.btmap.core.model.Plmn
import pl.btmap.core.radio.Operators
import pl.btmap.mobile.ui.theme.Btmap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import pl.btmap.mobile.i18n.Lang
import pl.btmap.mobile.i18n.tr
import pl.btmap.mobile.i18n.translateChange

private enum class LogView(private val pl: String, private val en: String) {
    CELLS("Komórki", "Cells"), HISTORY("Historia", "History");

    val label: String get() = tr(pl, en)
}

/** Log tab: the cells the phone has used, or the timeline of every change. */
@Composable
fun LogTabs(
    events: List<CellEvent>,
    contentPadding: PaddingValues,
    cells: @Composable (PaddingValues) -> Unit,
) {
    var view by rememberSaveable { mutableStateOf(LogView.CELLS) }
    val colors = Btmap.colors
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.cardRaised)
                .padding(4.dp),
        ) {
            LogView.entries.forEach { v ->
                val on = v == view
                val bg by animateColorAsState(if (on) colors.accent.copy(alpha = 0.18f) else Color.Transparent, tween(200), label = "logView")
                Text(
                    if (v == LogView.HISTORY && events.isNotEmpty()) "${v.label} · ${events.size}" else v.label,
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(bg)
                        .clickable { view = v }
                        .padding(vertical = 9.dp),
                    color = if (on) colors.accent else colors.muted,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        val inner = PaddingValues(top = 4.dp, bottom = contentPadding.calculateBottomPadding())
        AnimatedContent(view, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "logTabs") { v ->
            when (v) {
                LogView.CELLS -> cells(inner)
                LogView.HISTORY -> HistoryList(events, inner)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun HistoryList(events: List<CellEvent>, contentPadding: PaddingValues) {
    val colors = Btmap.colors
    if (events.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(contentPadding).padding(24.dp)) {
            Text(tr("Brak zmian", "No changes"), style = MaterialTheme.typography.titleMedium)
            Text(tr("Tu pojawi się każda zmiana komórki: nowy PCI, pasmo, agregacja, utrata zasięgu.", "Every cell change will appear here: a new PCI, band, aggregation, loss of service."), color = colors.muted)
        }
        return
    }
    val time = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val day = remember(Lang.english) { SimpleDateFormat("d MMMM", Lang.locale) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        var lastDay = ""
        events.forEachIndexed { index, e ->
            val d = day.format(Date(e.timeMillis))
            if (d != lastDay) {
                lastDay = d
                item(key = "d-$d-$index") {
                    Text(d.uppercase(), style = MaterialTheme.typography.labelSmall, color = colors.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
            }
            item(key = "e-${e.timeMillis}-${e.slot}-$index") {
                val lost = e.carriers.isEmpty()
                val op = e.plmn?.let { Plmn.parse(it) }?.let { Operators.byPlmn(it) }
                val brand = op?.let { colors.brand(Color(it.color)) } ?: colors.accent
                Row(Modifier.animateItemPlacement().padding(horizontal = 20.dp, vertical = 6.dp)) {
                    // Timeline dot.
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(18.dp)) {
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(if (lost) colors.bad else brand))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(time.format(Date(e.timeMillis)), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(8.dp))
                            Text("SIM ${e.slot + 1} · ${op?.displayName ?: e.plmn ?: "?"}", style = MaterialTheme.typography.bodySmall, color = colors.muted)
                            Spacer(Modifier.weight(1f))
                            if (!lost) Text(e.mode, style = MaterialTheme.typography.labelMedium, color = colors.accent)
                        }
                        Text(
                            e.changes.joinToString(" · ") { translateChange(it) },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (lost) colors.bad else MaterialTheme.colorScheme.onSurface,
                        )
                        if (!lost) {
                            Spacer(Modifier.height(4.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                e.carriers.forEach { c ->
                                    Text(
                                        buildString {
                                            append(c.label)
                                            c.pci?.let { append(" · PCI $it") }
                                            c.node?.let { append(" · $it") }
                                            c.dbm?.let { append(" · $it dBm") }
                                        },
                                        Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (c.primary) brand.copy(alpha = 0.16f) else colors.cardRaised)
                                            .padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }
                Divider(Modifier.padding(start = 48.dp, end = 20.dp))
            }
        }
    }
}
