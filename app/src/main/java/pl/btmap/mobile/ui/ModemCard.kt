package pl.btmap.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.btmap.core.diag.DownlinkStats
import pl.btmap.mobile.i18n.tr
import pl.btmap.mobile.tools.DiagLive
import pl.btmap.mobile.ui.theme.Btmap

/**
 * Compact modem readout for the Live screen (root, Qualcomm DIAG): downlink throughput, MCS, BLER,
 * MIMO rank, RBs and the modulation mix for LTE and, when the modem receives it, 5G NR, refreshed
 * twice a second while the card is on screen.
 */
@Composable
fun ModemCard(modifier: Modifier = Modifier) {
    val colors = Btmap.colors
    DisposableEffect(Unit) {
        DiagLive.start()
        onDispose { DiagLive.stop() }
    }
    val state by DiagLive.state.collectAsStateWithLifecycle()
    val running = state as? DiagLive.State.Running
    val hasData = running?.lte != null || running?.nr != null
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(colors.card)
            .border(1.dp, colors.divider, shape)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("MODEM · DL", style = MaterialTheme.typography.labelMedium, color = colors.muted, modifier = Modifier.weight(1f))
            PulseDot(if (hasData) colors.good else colors.faint, active = state is DiagLive.State.Running)
            Text(
                when (state) {
                    is DiagLive.State.Running -> if (hasData) tr("na żywo", "live") else tr("czekam na dane", "waiting for data")
                    is DiagLive.State.Connecting -> tr("łączę…", "connecting…")
                    is DiagLive.State.Failed -> tr("błąd", "error")
                    DiagLive.State.Off -> tr("wyłączone", "off")
                },
                style = MaterialTheme.typography.labelSmall, color = colors.faint,
            )
        }
        val sections = listOfNotNull(running?.lte?.let { "LTE" to it }, running?.nr?.let { "5G NR" to it })
        if (sections.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Radio("LTE", null)
        }
        sections.forEach { (name, stats) ->
            Spacer(Modifier.height(8.dp))
            Radio(name, stats)
        }
    }
}

/** One radio's downlink: name and Mb/s, then MCS, BLER, MIMO rank and RBs, then the modulation mix. */
@Composable
private fun Radio(name: String, stats: DownlinkStats?) {
    val colors = Btmap.colors
    Row(verticalAlignment = Alignment.Bottom) {
        Text(name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = colors.muted, modifier = Modifier.padding(bottom = 5.dp))
        Spacer(Modifier.weight(1f))
        Text(stats?.let { "%.1f".format(it.mbps) } ?: "—", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(" Mb/s", style = MaterialTheme.typography.titleSmall, color = colors.muted, modifier = Modifier.padding(bottom = 3.dp))
    }
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth()) {
        Figure("MCS", stats?.let { "%.0f".format(it.mcsAvg) }, Modifier.weight(1f))
        Figure("BLER", stats?.let { "%.0f%%".format(it.bler * 100) }, Modifier.weight(1f))
        Figure("MIMO rank", stats?.let { "%.1f".format(it.layersAvg) }, Modifier.weight(1f))
        Figure("RB", stats?.let { "%.0f".format(it.rbsAvg) }, Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
    val mods = listOf(2, 4, 6, 8)
    val modColors = listOf(colors.bad, colors.poor, colors.fair, colors.good)
    Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.meterTrack)) {
        mods.forEachIndexed { i, q ->
            val share = stats?.modulation?.get(q) ?: 0.0
            if (share > 0) Box(Modifier.weight(share.toFloat()).fillMaxHeight().background(modColors[i]))
        }
    }
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("QPSK", "16QAM", "64QAM", "256QAM").forEachIndexed { i, label ->
            val share = stats?.modulation?.get(mods[i])
            Text("$label ${share?.let { "%.0f%%".format(it * 100) } ?: ""}", style = MaterialTheme.typography.labelSmall, color = modColors[i].copy(alpha = 0.9f))
        }
    }
}

@Composable
private fun Figure(label: String, value: String?, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value ?: "—", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Btmap.colors.muted, maxLines = 1)
    }
}
