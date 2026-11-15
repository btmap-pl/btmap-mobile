package pl.btmap.mobile.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.btmap.core.LiveState
import pl.btmap.core.api.Station
import pl.btmap.core.location.Geo
import pl.btmap.core.model.Carrier
import pl.btmap.core.model.CarrierSource
import pl.btmap.core.model.Cell
import pl.btmap.core.model.CellRole
import pl.btmap.core.model.Rat
import pl.btmap.core.model.SimState
import pl.btmap.core.radio.Operators
import pl.btmap.mobile.ui.theme.Btmap

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiveScreen(state: LiveState, stationUrl: (Station) -> String, contentPadding: PaddingValues) {
    val sims = state.snapshot.sims
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { LocationStrip(state) }
        if (sims.isEmpty()) {
            item {
                Text(
                    if (state.running) "Czekam na dane z modemu…" else "Monitor zatrzymany",
                    Modifier.padding(24.dp), color = Btmap.colors.muted,
                )
            }
        }
        items(sims, key = { it.subscriptionId }) { sim ->
            SimCard(sim, state, stationUrl, Modifier.animateItemPlacement())
        }
    }
}

@Composable
private fun LocationStrip(state: LiveState) {
    val fix = state.fix
    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (fix != null) Btmap.colors.good else Btmap.colors.faint))
        Spacer(Modifier.width(8.dp))
        Text(
            if (fix == null) "Szukam lokalizacji…"
            else buildString {
                append("%.5f°  %.5f°".format(java.util.Locale.ROOT, fix.lat, fix.lng))
                fix.accuracyMeters?.let { append("  ±${it.toInt()} m") }
                fix.speedKmh?.takeIf { it >= 1f }?.let { append("  ${it.toInt()} km/h") }
            },
            style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted,
        )
        state.apiError?.let {
            Spacer(Modifier.weight(1f))
            Text("BTMap offline", style = MaterialTheme.typography.labelSmall, color = Btmap.colors.poor)
        }
    }
}

// ---- SIM card ------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SimCard(sim: SimState, state: LiveState, stationUrl: (Station) -> String, modifier: Modifier) {
    val colors = Btmap.colors
    val brand = sim.operator?.let { colors.brand(Color(it.color)) } ?: colors.accent
    val glow = brand.copy(alpha = if (colors.isDark) 0.20f else 0.12f)
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(colors.card)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(glow, Color.Transparent),
                        center = Offset(size.width * 0.15f, 0f),
                        radius = size.width * 0.85f,
                    ),
                )
            }
            .border(1.dp, colors.divider, shape)
            .padding(horizontal = 20.dp, vertical = 20.dp),
    ) {
        OperatorHeader(sim, brand)
        sim.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = colors.bad, style = MaterialTheme.typography.bodySmall)
        }

        val primary = sim.primary
        if (primary == null) {
            Spacer(Modifier.height(16.dp))
            Text("Brak obsługującej komórki", color = colors.muted)
            return@Column
        }

        val carriers = state.carriersOf(sim)
        Spacer(Modifier.height(18.dp))
        ModeRow(sim)
        if (carriers.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                carriers.forEach { CarrierPill(it, brand) }
            }
        }

        Spacer(Modifier.height(20.dp))
        SignalHero(primary)
        Spacer(Modifier.height(12.dp))
        MetricsRow(primary)

        Spacer(Modifier.height(18.dp))
        IdentityGrid(primary)

        StationBlock(primary, state, stationUrl)

        val extra = carriers.filter { !it.isPrimary }
        if (extra.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            SectionTitle(if (extra.any { it.rat != primary.rat }) "Agregacja i 5G" else "Agregacja")
            extra.forEach { CarrierRow(it, primary.rat, state) }
        }

        val shown = extra.mapNotNull { it.cell }.toSet()
        val neighbours = sim.neighbours.filter { it !in shown }
        if (neighbours.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            SectionTitle("Sąsiednie · ${neighbours.size}")
            neighbours.take(10).forEach { NeighbourRow(it, state) }
        }
    }
}

@Composable
private fun OperatorHeader(sim: SimState, brand: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(
                Operators.displayName(sim.registeredPlmn, sim.networkOperatorName).uppercase(),
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp),
            )
            Box(
                Modifier
                    .padding(top = 2.dp)
                    .size(width = 44.dp, height = 3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(brand),
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(sim.registeredPlmn?.toString() ?: "—", style = MaterialTheme.typography.bodyMedium, color = Btmap.colors.muted)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Tag("SIM ${sim.slotIndex + 1}")
                if (sim.isDataSim) Tag("DANE")
                if (sim.isRoaming) Tag("ROAMING", Btmap.colors.poor)
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: Color = Btmap.colors.muted) {
    Text(
        text,
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Btmap.colors.cardRaised)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall, color = color,
    )
}

@Composable
private fun ModeRow(sim: SimState) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(sim.mode.label, style = MaterialTheme.typography.headlineMedium, color = Btmap.colors.accent)
        val total = sim.totalBandwidthKhz
        if (total > 0) {
            Spacer(Modifier.width(10.dp))
            Text(
                (if (total % 1000 == 0) "${total / 1000}" else "%.1f".format(total / 1000.0)) + " MHz",
                style = MaterialTheme.typography.titleMedium, color = Btmap.colors.muted,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        if (sim.nrAvailable) {
            Spacer(Modifier.weight(1f))
            Pill("5G gotowe", Btmap.colors.muted, style = PillStyle.DASHED, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

@Composable
private fun CarrierPill(carrier: Carrier, brand: Color) {
    val band = carrier.band
    val label = buildString {
        append(if (carrier.rat == Rat.NR) "5G " else "")
        append(band?.label ?: "?")
        carrier.bandwidthLabel?.let { append(" · $it MHz") }
    }
    val style = when {
        carrier.isPrimary -> PillStyle.FILLED
        carrier.source == CarrierSource.UNKNOWN -> PillStyle.DASHED
        else -> PillStyle.OUTLINED
    }
    val color = if (carrier.isPrimary) brand else if (carrier.source == CarrierSource.UNKNOWN) Btmap.colors.muted else MaterialTheme.colorScheme.onSurface
    Pill(label, color, style = style) {
        band?.let {
            Text(" ${it.shortName}", style = MaterialTheme.typography.labelSmall, color = Btmap.colors.muted)
        }
    }
}

// ---- Signal --------------------------------------------------------------------------------

@Composable
private fun SignalHero(cell: Cell) {
    val s = cell.signal
    val (label, value) = when (cell.rat) {
        Rat.NR -> "SS-RSRP" to s.ssRsrp
        Rat.LTE -> "RSRP" to s.rsrp
        Rat.WCDMA -> "RSCP" to s.rscp
        Rat.GSM -> "RSSI" to s.rssi
    }
    val quality = powerQuality(value, cell.rat)
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value?.toString() ?: "—", style = MaterialTheme.typography.displaySmall, color = quality.color())
            Text(" dBm", style = MaterialTheme.typography.titleMedium, color = Btmap.colors.muted, modifier = Modifier.padding(bottom = 6.dp))
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 4.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = Btmap.colors.muted)
                Text(quality?.label ?: "", style = MaterialTheme.typography.labelLarge, color = quality.color())
            }
        }
        Spacer(Modifier.height(8.dp))
        Meter(powerFraction(value), quality.color(), Modifier.fillMaxWidth(), height = 8.dp)
    }
}

@Composable
private fun MetricsRow(cell: Cell) {
    val s = cell.signal
    val metrics: List<Triple<String, Int?, Pair<String, Color>>> = when (cell.rat) {
        Rat.NR -> listOf(
            Triple("SS-RSRQ", s.ssRsrq, "dB" to rsrqQuality(s.ssRsrq).color()),
            Triple("SS-SINR", s.ssSinr, "dB" to sinrQuality(s.ssSinr).color()),
            Triple("CSI-RSRP", s.csiRsrp, "dBm" to powerQuality(s.csiRsrp, Rat.NR).color()),
        )
        Rat.LTE -> listOf(
            Triple("RSRQ", s.rsrq, "dB" to rsrqQuality(s.rsrq).color()),
            Triple("SNR", s.snr, "dB" to sinrQuality(s.snr).color()),
            Triple("RSSI", s.rssi, "dBm" to MaterialTheme.colorScheme.onSurface),
        )
        Rat.WCDMA -> listOf(Triple("Ec/No", s.ecno, "dB" to MaterialTheme.colorScheme.onSurface))
        Rat.GSM -> listOf(Triple("BER", s.ber, "" to MaterialTheme.colorScheme.onSurface))
    }
    val ta = s.timingAdvance ?: s.timingAdvanceMicros
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        metrics.forEach { (label, v, unitColor) ->
            Metric(label, v?.toString(), unitColor.first, unitColor.second, Modifier.weight(1f))
        }
        Metric(
            "TA" + (ta?.let { " · $it" } ?: ""),
            cell.taDistanceMeters?.let { if (it >= 1000) "%.1f".format(it / 1000.0) else it.toString() },
            cell.taDistanceMeters?.let { if (it >= 1000) "km" else "m" } ?: "",
            MaterialTheme.colorScheme.onSurface,
            Modifier.weight(1f),
        )
    }
}

// ---- Identity ------------------------------------------------------------------------------

@Composable
private fun IdentityGrid(cell: Cell) {
    val id = cell.identity
    val rows = listOfNotNull(
        (if (id.rat == Rat.NR) "gNB:CID" else if (id.rat == Rat.LTE) "eNB:CID" else "RNC:CID") to id.nodeLabel,
        (if (id.rat == Rat.NR) "NCI" else "CI") to id.cid?.toString(),
        (if (id.rat == Rat.LTE || id.rat == Rat.NR) "TAC" else "LAC") to id.areaCode?.toString(),
        physicalLabel(id.rat) to id.physicalId?.toString(),
        channelLabel(id.rat) to id.channel?.toString(),
        "DL" to id.band?.downlinkMhz?.let { "%.1f MHz".format(it) },
    ).filter { it.second != null }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.chunked(2).forEach { pair ->
            Row {
                pair.forEach { (label, value) ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.width(76.dp), style = MaterialTheme.typography.bodyMedium, color = Btmap.colors.muted)
                        Text(value!!, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StationBlock(cell: Cell, state: LiveState, stationUrl: (Station) -> String) {
    if (cell.rat != Rat.LTE && cell.rat != Rat.NR) return
    val station = state.stationOf(cell)
    val context = LocalContext.current
    Spacer(Modifier.height(16.dp))
    if (station == null) {
        Text(
            if (cell.identity.nodeId == null) "" else if (state.isLookedUp(cell)) "Stacja nieznana w BTMap" else "Szukam stacji…",
            style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted,
        )
        return
    }
    val fix = state.fix
    val dist = fix?.let { Geo.distanceMeters(it.lat, it.lng, station.lat, station.lng) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Btmap.colors.accent.copy(alpha = 0.08f))
            .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(stationUrl(station)))) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PinIcon(Btmap.colors.accent)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                station.address, color = Btmap.colors.accent, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(station.stationId, station.bands.takeIf { it.isNotEmpty() }?.joinToString(" · ")).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted,
            )
        }
        dist?.let {
            Spacer(Modifier.width(8.dp))
            Text(formatDistance(it), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

// ---- Lists ---------------------------------------------------------------------------------

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Btmap.colors.muted, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun CarrierRow(carrier: Carrier, primaryRat: Rat, state: LiveState) {
    val cell = carrier.cell
    val rat = carrier.rat
    val dbm = cell?.signal?.let { if (rat == Rat.NR) it.ssRsrp else it.rsrp }
    val q = powerQuality(dbm, rat)
    ListRow(
        badge = (if (rat == Rat.NR && primaryRat != Rat.NR) "5G " else "") + (carrier.band?.label ?: "?"),
        badgeStyle = if (carrier.source == CarrierSource.UNKNOWN) PillStyle.DASHED else PillStyle.OUTLINED,
        title = listOfNotNull(carrier.physicalId?.let { "PCI $it" }, cell?.identity?.nodeLabel).joinToString("  ").ifEmpty {
            when (carrier.source) {
                CarrierSource.UNKNOWN -> "Nieznana komórka"
                CarrierSource.STATION -> "Pasmo wg stacji BTMap"
                else -> "—"
            }
        },
        subtitle = cell?.let { state.stationOf(it)?.address },
        channel = carrier.channel,
        dbm = dbm,
        quality = q,
        trailing = carrier.bandwidthLabel?.let { "$it MHz" },
    )
}

@Composable
private fun NeighbourRow(cell: Cell, state: LiveState) {
    val id = cell.identity
    val dbm = cell.signal.mainDbm
    ListRow(
        badge = (if (id.rat == Rat.NR) "5G " else if (id.rat != Rat.LTE) id.rat.generation + " " else "") + (id.band?.label ?: "?"),
        badgeStyle = PillStyle.OUTLINED,
        title = listOfNotNull(id.physicalId?.let { "PCI $it" }, id.nodeLabel).joinToString("  "),
        subtitle = state.stationOf(cell)?.address,
        channel = id.channel,
        dbm = dbm,
        quality = powerQuality(dbm, id.rat),
        trailing = null,
    )
}

@Composable
private fun ListRow(
    badge: String,
    badgeStyle: PillStyle,
    title: String,
    subtitle: String?,
    channel: Int?,
    dbm: Int?,
    quality: Quality?,
    trailing: String?,
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(badge, Btmap.colors.muted, style = badgeStyle, modifier = Modifier.width(76.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1)
                val sub = listOfNotNull(channel?.toString(), trailing).joinToString("  ·  ")
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted)
            }
            Text(dbm?.toString() ?: "—", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = quality.color())
            Spacer(Modifier.width(8.dp))
            MiniBars(powerFraction(dbm), quality.color())
        }
        subtitle?.let {
            Text(
                it, style = MaterialTheme.typography.bodySmall, color = Btmap.colors.accent,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 86.dp, top = 2.dp),
            )
        }
    }
}

// ---- Helpers -------------------------------------------------------------------------------

fun formatDistance(m: Int): String = if (m >= 1000) "%.1f km".format(m / 1000.0) else "$m m"

private fun physicalLabel(rat: Rat) = when (rat) {
    Rat.LTE, Rat.NR -> "PCI"
    Rat.WCDMA -> "PSC"
    Rat.GSM -> "BSIC"
}

private fun channelLabel(rat: Rat) = when (rat) {
    Rat.LTE -> "EARFCN"
    Rat.NR -> "ARFCN"
    Rat.WCDMA -> "UARFCN"
    Rat.GSM -> "ARFCN"
}

@Composable
fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("BTMap", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "Do odczytu komórek potrzebny jest dostęp do lokalizacji (Android ukrywa bez niego ID komórek) " +
                "oraz do stanu telefonu (lista kart SIM).",
            color = Btmap.colors.muted,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("Nadaj uprawnienia") }
    }
}
