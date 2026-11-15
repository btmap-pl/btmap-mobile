package pl.btmap.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.btmap.core.LiveState
import pl.btmap.core.api.Station
import pl.btmap.core.location.Geo
import pl.btmap.core.model.Cell
import pl.btmap.core.model.CellRole
import pl.btmap.core.model.Rat
import pl.btmap.core.model.SimState
import pl.btmap.core.radio.Operators
import pl.btmap.mobile.ui.theme.Btmap

/**
 * Developer "Live" screen used to verify the core on a device. The production UI comes later;
 * this one only has to show every value the core produces, legibly, in both themes.
 */
@Composable
fun LiveScreen(state: LiveState) {
    val sims = state.snapshot.sims
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header(state) }
        if (sims.isEmpty()) item { Empty(if (state.running) "Czekam na dane z modemu…" else "Monitor zatrzymany") }
        items(sims, key = { it.subscriptionId }) { sim -> SimCard(sim, state) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Header(state: LiveState) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Live", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (state.running) Btmap.colors.good else Btmap.colors.muted))
        }
        val fix = state.fix
        Text(
            text = if (fix == null) "Brak lokalizacji"
            else "%.6f° %.6f° · ±%s m · %s".format(
                fix.lat, fix.lng, fix.accuracyMeters?.toInt() ?: "?", fix.provider ?: "",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = Btmap.colors.muted,
            fontFamily = FontFamily.Monospace,
        )
        state.apiError?.let {
            Text("BTMap API: $it", style = MaterialTheme.typography.bodySmall, color = Btmap.colors.bad)
        }
    }
}

@Composable
private fun Empty(text: String) {
    Text(text, Modifier.padding(20.dp), color = Btmap.colors.muted)
}

@Composable
private fun SimCard(sim: SimState, state: LiveState) {
    val operatorColor = sim.operator?.let { Color(it.color) } ?: MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Btmap.colors.card)
            .border(1.dp, Btmap.colors.divider, RoundedCornerShape(24.dp))
            .padding(20.dp),
    ) {
        // Operator header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(operatorColor))
            Spacer(Modifier.width(10.dp))
            Text(
                Operators.displayName(sim.registeredPlmn, sim.networkOperatorName).uppercase(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.5.sp,
            )
            if (sim.simName != null && sim.simName != sim.networkOperatorName) {
                Spacer(Modifier.width(8.dp))
                Text(sim.simName!!, color = Btmap.colors.muted, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(sim.registeredPlmn?.toString() ?: "—", color = Btmap.colors.muted, fontFamily = FontFamily.Monospace)
                Text(
                    buildString {
                        append("SIM ${sim.slotIndex + 1}")
                        if (sim.isDataSim) append(" · dane")
                        if (sim.isRoaming) append(" · roaming")
                    },
                    color = Btmap.colors.muted, style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        sim.error?.let { Text(it, color = Btmap.colors.bad, style = MaterialTheme.typography.bodySmall) }

        Spacer(Modifier.height(12.dp))
        Chip(sim.mode.label, MaterialTheme.colorScheme.primary)
        if (sim.carrierBandwidthsKhz.isNotEmpty()) {
            Text(
                "BW " + sim.carrierBandwidthsKhz.joinToString(" + ") { (it / 1000).toString() } + " MHz",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp),
            )
        }

        val serving = sim.cells.filter { it.role != CellRole.NEIGHBOUR }
        serving.forEach { cell ->
            Spacer(Modifier.height(14.dp))
            ServingCell(cell, state.stationOf(cell), state.isLookedUp(cell), state)
        }

        val neighbours = sim.neighbours
        if (neighbours.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Sąsiednie (${neighbours.size})", style = MaterialTheme.typography.labelLarge, color = Btmap.colors.muted)
            neighbours.take(12).forEach { NeighbourRow(it) }
        }
    }
}

@Composable
private fun ServingCell(cell: Cell, station: Station?, lookedUp: Boolean, state: LiveState) {
    val id = cell.identity
    val s = cell.signal
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Chip(if (cell.role == CellRole.PRIMARY) "PCell" else "SCell", Btmap.colors.muted)
            Spacer(Modifier.width(8.dp))
            Text(
                "${id.rat.generation} · ${id.band?.let { "${it.shortName} ${it.label}" } ?: id.rat.label}" +
                    (id.bandwidthKhz?.let { " · ${it / 1000} MHz" } ?: ""),
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row {
            Column(Modifier.weight(1f)) {
                Field(if (id.rat == Rat.NR) "gNb:CID" else if (id.rat == Rat.LTE) "eNb:CID" else "RNC:CID", id.nodeLabel)
                Field(if (id.rat == Rat.NR) "NCI" else "CI", id.cid?.toString())
                Field(if (id.rat == Rat.LTE || id.rat == Rat.NR) "TAC" else "LAC", id.areaCode?.toString())
                Field(physicalLabel(id.rat), id.physicalId?.toString())
                Field(channelLabel(id.rat), id.channel?.toString())
                Field("MHz", id.band?.downlinkMhz?.toString())
            }
            Column(Modifier.weight(1f)) {
                when (id.rat) {
                    Rat.NR -> {
                        SignalField("SS-RSRP", s.ssRsrp, "dBm", rsrpQuality(s.ssRsrp))
                        SignalField("SS-RSRQ", s.ssRsrq, "dB", null)
                        SignalField("SS-SINR", s.ssSinr, "dB", sinrQuality(s.ssSinr))
                        SignalField("CSI-RSRP", s.csiRsrp, "dBm", null)
                    }
                    Rat.LTE -> {
                        SignalField("RSSI", s.rssi, "dBm", null)
                        SignalField("RSRP", s.rsrp, "dBm", rsrpQuality(s.rsrp))
                        SignalField("RSRQ", s.rsrq, "dB", null)
                        SignalField("SNR", s.snr, "dB", sinrQuality(s.snr))
                        SignalField("CQI", s.cqi, "", null)
                    }
                    Rat.WCDMA -> {
                        SignalField("RSCP", s.rscp, "dBm", null)
                        SignalField("Ec/No", s.ecno, "dB", null)
                    }
                    Rat.GSM -> {
                        SignalField("RSSI", s.rssi, "dBm", null)
                        SignalField("BER", s.ber, "", null)
                    }
                }
                val ta = s.timingAdvance ?: s.timingAdvanceMicros
                if (ta != null) Field("TA", "$ta" + (cell.taDistanceMeters?.let { " · $it m" } ?: ""))
            }
        }
        StationLine(cell, station, lookedUp, state)
    }
}

@Composable
private fun StationLine(cell: Cell, station: Station?, lookedUp: Boolean, state: LiveState) {
    if (cell.rat != Rat.LTE && cell.rat != Rat.NR) return
    Spacer(Modifier.height(8.dp))
    when {
        station != null -> {
            val fix = state.fix
            val dist = fix?.let { Geo.distanceMeters(it.lat, it.lng, station.lat, station.lng) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Chip(if (station.nodeLinked) "BTMap ✓" else "BTMap ?", Btmap.colors.station)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        station.address, color = Btmap.colors.station, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            station.stationId,
                            station.bands.takeIf { it.isNotEmpty() }?.joinToString("/"),
                            dist?.let { "$it m" },
                        ).joinToString(" · "),
                        color = Btmap.colors.muted, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        lookedUp && cell.identity.nodeId != null ->
            Text("Brak stacji w BTMap dla tego eNB/gNB", color = Btmap.colors.muted, style = MaterialTheme.typography.bodySmall)
        cell.identity.nodeId != null ->
            Text("Szukam w BTMap…", color = Btmap.colors.muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NeighbourRow(cell: Cell) {
    val id = cell.identity
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Chip("${id.rat.generation} ${id.band?.label ?: "?"}", Btmap.colors.muted)
        Spacer(Modifier.width(8.dp))
        Text(
            listOfNotNull(id.physicalId?.toString(), id.nodeLabel).joinToString("  "),
            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f),
        )
        Text(id.channel?.toString() ?: "", color = Btmap.colors.muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Spacer(Modifier.width(10.dp))
        val dbm = cell.signal.mainDbm
        Text(dbm?.toString() ?: "—", fontFamily = FontFamily.Monospace, color = rsrpQuality(dbm) ?: MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Field(label: String, value: String?) {
    if (value == null) return
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(label, Modifier.width(78.dp), color = Btmap.colors.muted, style = MaterialTheme.typography.bodyMedium)
        Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SignalField(label: String, value: Int?, unit: String, quality: Color?) {
    if (value == null) return
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(78.dp), color = Btmap.colors.muted, style = MaterialTheme.typography.bodyMedium)
        Text("$value", fontFamily = FontFamily.Monospace, color = quality ?: MaterialTheme.colorScheme.onSurface)
        if (unit.isNotEmpty()) Text(" $unit", color = Btmap.colors.muted, fontSize = 11.sp)
    }
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        color = color,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun rsrpQuality(dbm: Int?): Color? = when {
    dbm == null -> null
    dbm >= -90 -> Btmap.colors.good
    dbm >= -105 -> Btmap.colors.fair
    dbm >= -115 -> Btmap.colors.poor
    else -> Btmap.colors.bad
}

@Composable
private fun sinrQuality(db: Int?): Color? = when {
    db == null -> null
    db >= 13 -> Btmap.colors.good
    db >= 5 -> Btmap.colors.fair
    db >= 0 -> Btmap.colors.poor
    else -> Btmap.colors.bad
}

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
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("BTMap", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
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
