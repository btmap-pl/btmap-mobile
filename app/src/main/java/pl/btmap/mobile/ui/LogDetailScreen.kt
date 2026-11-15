package pl.btmap.mobile.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.btmap.core.LiveState
import pl.btmap.core.LogEntry
import pl.btmap.core.api.Station
import pl.btmap.core.location.Geo
import pl.btmap.core.model.CellRole
import pl.btmap.core.model.Rat
import pl.btmap.core.radio.CellMath
import pl.btmap.core.radio.Operators
import pl.btmap.mobile.ui.theme.Btmap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything known about one logged cell: identity, radio, signal history and its BTMap station. */
@Composable
fun LogDetailScreen(
    entry: LogEntry,
    state: LiveState,
    stationUrl: (Station) -> String,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
) {
    val id = entry.identity
    val operator = Operators.byPlmn(id.plmn)
    val brand = operator?.let { Btmap.colors.brand(Color(it.color)) } ?: Btmap.colors.accent
    val station = state.stationOf(id)
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val isNode = id.rat == Rat.LTE || id.rat == Rat.NR

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.enterAnimation(0)) {
            BackChip(onBack)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${id.rat.generation} · ${id.band?.let { "${it.label} ${it.shortName}" } ?: id.rat.label}",
                    style = MaterialTheme.typography.labelLarge, color = brand,
                )
                Text(
                    id.nodeLabel ?: "—",
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(operator?.displayName ?: id.plmn?.toString() ?: "", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("SIM ${entry.slotIndex + 1}", style = MaterialTheme.typography.labelSmall, color = Btmap.colors.muted)
            }
        }

        // Signal history
        DetailCard(Modifier.enterAnimation(1)) {
            val dbm = entry.lastSignal.mainDbm
            val q = powerQuality(dbm, id.rat)
            Row(verticalAlignment = Alignment.Bottom) {
                AnimatedNumber(dbm, MaterialTheme.typography.displaySmall, q.color())
                Text(" dBm", style = MaterialTheme.typography.titleMedium, color = Btmap.colors.muted, modifier = Modifier.padding(bottom = 6.dp))
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 4.dp)) {
                    Text("NAJLEPSZY", style = MaterialTheme.typography.labelSmall, color = Btmap.colors.muted)
                    Text(entry.bestDbm?.let { "$it dBm" } ?: "—", style = MaterialTheme.typography.labelLarge, color = powerQuality(entry.bestDbm, id.rat).color())
                }
            }
            Spacer(Modifier.height(10.dp))
            Sparkline(entry.history, brand, Modifier.fillMaxWidth().height(96.dp))
            Spacer(Modifier.height(6.dp))
            Text(
                "${entry.samples} pomiarów · ${clock.format(Date(entry.firstSeenMillis))} – ${clock.format(Date(entry.lastSeenMillis))}",
                style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted,
            )
        }

        // Identity
        DetailCard(Modifier.enterAnimation(2)) {
            CardTitle("Identyfikacja")
            KeyValues(
                listOf(
                    (if (id.rat == Rat.NR) "gNB" else if (id.rat == Rat.LTE) "eNB" else "RNC") to id.nodeId?.toString(),
                    "CID" to id.localCellId?.toString(),
                    "Sektor" to (if (isNode) CellMath.sector(id.localCellId)?.toString() else null),
                    (if (id.rat == Rat.NR) "NCI" else "CI") to id.cid?.toString(),
                    (if (isNode) "TAC" else "LAC") to id.areaCode?.toString(),
                    "PLMN" to id.plmn?.toString(),
                ),
            )
        }

        // Radio
        DetailCard(Modifier.enterAnimation(3)) {
            CardTitle("Radio")
            KeyValues(
                listOf(
                    (when (id.rat) { Rat.LTE, Rat.NR -> "PCI"; Rat.WCDMA -> "PSC"; Rat.GSM -> "BSIC" }) to id.physicalId?.toString(),
                    (when (id.rat) { Rat.LTE -> "EARFCN"; Rat.NR -> "NR-ARFCN"; Rat.WCDMA -> "UARFCN"; Rat.GSM -> "ARFCN" }) to id.channel?.toString(),
                    "Pasmo" to id.band?.let { "${it.shortName} · ${it.label} MHz" },
                    "DL" to id.band?.downlinkMhz?.let { "%.1f MHz".format(it) },
                    "Szerokość" to id.bandwidthKhz?.let { if (it % 1000 == 0) "${it / 1000} MHz" else "%.1f MHz".format(it / 1000.0) },
                    "Rola" to when (entry.lastRole) { CellRole.PRIMARY -> "PCell"; CellRole.SECONDARY -> "SCell / NR"; CellRole.NEIGHBOUR -> "sąsiednia" },
                ),
            )
        }

        // Last measurement
        DetailCard(Modifier.enterAnimation(4)) {
            CardTitle("Ostatni pomiar")
            val s = entry.lastSignal
            KeyValues(
                when (id.rat) {
                    Rat.NR -> listOf(
                        "SS-RSRP" to s.ssRsrp?.let { "$it dBm" }, "SS-RSRQ" to s.ssRsrq?.let { "$it dB" },
                        "SS-SINR" to s.ssSinr?.let { "$it dB" }, "CSI-RSRP" to s.csiRsrp?.let { "$it dBm" },
                    )
                    Rat.LTE -> listOf(
                        "RSRP" to s.rsrp?.let { "$it dBm" }, "RSRQ" to s.rsrq?.let { "$it dB" },
                        "RSSI" to s.rssi?.let { "$it dBm" }, "SNR" to s.snr?.let { "$it dB" }, "CQI" to s.cqi?.toString(),
                    )
                    Rat.WCDMA -> listOf("RSCP" to s.rscp?.let { "$it dBm" }, "Ec/No" to s.ecno?.let { "$it dB" })
                    Rat.GSM -> listOf("RSSI" to s.rssi?.let { "$it dBm" }, "BER" to s.ber?.toString())
                } + listOf(
                    "TA" to (s.timingAdvance ?: s.timingAdvanceMicros)?.let { ta ->
                        "$ta" + (entry.lastTaMeters?.let { " · ${formatDistance(it)}" } ?: "")
                    },
                ),
            )
        }

        // Station
        if (station != null) {
            StationCard(station, entry, stationUrl, Modifier.enterAnimation(5))
        } else if (isNode && id.nodeId != null) {
            DetailCard(Modifier.enterAnimation(5)) {
                CardTitle("Stacja")
                Text("Ten ${if (id.rat == Rat.NR) "gNB" else "eNB"} nie jest jeszcze powiązany ze stacją w BTMap.", color = Btmap.colors.muted)
            }
        }

        // Where it was heard
        entry.bestFix?.let { fix ->
            DetailCard(Modifier.enterAnimation(6)) {
                CardTitle("Najlepszy odbiór w miejscu")
                Text("%.5f°  %.5f°".format(Locale.ROOT, fix.lat, fix.lng), style = MaterialTheme.typography.bodyLarge)
                fix.accuracyMeters?.let { Text("dokładność ±${it.toInt()} m", style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted) }
            }
        }
    }
}

@Composable
private fun StationCard(station: Station, entry: LogEntry, stationUrl: (Station) -> String, modifier: Modifier) {
    val context = LocalContext.current
    val interaction = remember { MutableInteractionSource() }
    val fix = entry.lastFix ?: entry.bestFix
    val dist = fix?.let { Geo.distanceMeters(it.lat, it.lng, station.lat, station.lng) }
    val bearing = fix?.let { Geo.bearing(it.lat, it.lng, station.lat, station.lng) }
    DetailCard(
        modifier
            .pressScale(interaction)
            .clickable(interaction, LocalIndication.current) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(stationUrl(station))))
            },
    ) {
        CardTitle("Stacja BTMap")
        Row(verticalAlignment = Alignment.CenterVertically) {
            PinIcon(Btmap.colors.accent)
            Spacer(Modifier.width(10.dp))
            Text(station.address, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Btmap.colors.accent, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        KeyValues(
            listOf(
                "ID" to station.stationId,
                "Pasma" to station.bands.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                "Operatorzy" to station.operators.takeIf { it.isNotEmpty() }?.joinToString(", "),
                "Odległość" to dist?.let(::formatDistance),
                "Kierunek" to bearing?.let { "${it.toInt()}° ${compass(it)}" },
            ),
        )
        Spacer(Modifier.height(6.dp))
        Text("Otwórz w BTMap →", style = MaterialTheme.typography.labelLarge, color = Btmap.colors.accent)
    }
}

private fun compass(deg: Double): String =
    listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg + 22.5) % 360) / 45).toInt()]

@Composable
private fun DetailCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
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
private fun CardTitle(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Btmap.colors.muted, modifier = Modifier.padding(bottom = 10.dp))
}

@Composable
private fun KeyValues(rows: List<Pair<String, String?>>) {
    val shown = rows.filter { it.second != null }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.chunked(2).forEach { pair ->
            Row {
                pair.forEach { (k, v) ->
                    Column(Modifier.weight(1f)) {
                        Text(k, style = MaterialTheme.typography.labelSmall, color = Btmap.colors.muted)
                        Text(v!!, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun BackChip(onBack: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val c = MaterialTheme.colorScheme.onSurface
    Canvas(
        Modifier
            .pressScale(interaction)
            .clip(RoundedCornerShape(14.dp))
            .background(Btmap.colors.cardRaised)
            .clickable(interaction, LocalIndication.current, onClick = onBack)
            .padding(12.dp)
            .width(20.dp)
            .height(20.dp),
    ) {
        val stroke = 2.2.dp.toPx()
        drawLine(c, Offset(size.width * 0.62f, size.height * 0.18f), Offset(size.width * 0.28f, size.height / 2), stroke, StrokeCap.Round)
        drawLine(c, Offset(size.width * 0.28f, size.height / 2), Offset(size.width * 0.62f, size.height * 0.82f), stroke, StrokeCap.Round)
    }
}

/** Signal-history line with a soft gradient fill that draws itself in from the left. */
@Composable
private fun Sparkline(values: List<Int>, color: Color, modifier: Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val track = Btmap.colors.meterTrack
    if (values.size < 2) {
        Text("Za mało pomiarów na wykres", modifier = modifier.padding(top = 30.dp), color = Btmap.colors.faint, style = MaterialTheme.typography.bodySmall)
        return
    }
    val min = (values.min() - 3).toFloat()
    val max = (values.max() + 3).toFloat()
    Canvas(modifier) {
        val stepX = size.width / (values.size - 1)
        fun y(v: Int) = size.height - (v - min) / (max - min) * size.height
        // Mid guide line
        drawLine(track, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())
        val line = Path().apply {
            values.forEachIndexed { i, v -> if (i == 0) moveTo(0f, y(v)) else lineTo(i * stepX, y(v)) }
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        clipRect(right = size.width * progress.value) {
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), Color.Transparent)))
            drawPath(line, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
