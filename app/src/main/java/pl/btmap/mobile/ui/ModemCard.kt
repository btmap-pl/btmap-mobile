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
import androidx.compose.foundation.layout.width
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
import pl.btmap.mobile.i18n.tr
import pl.btmap.mobile.tools.DiagLive
import pl.btmap.mobile.ui.theme.Btmap

/**
 * Compact modem readout for the Live screen (root, Qualcomm DIAG): downlink throughput, MCS, BLER,
 * MIMO rank and the modulation mix, refreshed twice a second while the card is on screen.
 */
@Composable
fun ModemCard(modifier: Modifier = Modifier) {
    val colors = Btmap.colors
    DisposableEffect(Unit) {
        DiagLive.start()
        onDispose { DiagLive.stop() }
    }
    val state by DiagLive.state.collectAsStateWithLifecycle()
    val stats = (state as? DiagLive.State.Running)?.stats
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
            Text(tr("MODEM · LTE DL", "MODEM · LTE DL"), style = MaterialTheme.typography.labelMedium, color = colors.muted, modifier = Modifier.weight(1f))
            PulseDot(if (stats != null) colors.good else colors.faint, active = state is DiagLive.State.Running)
            Text(
                when (state) {
                    is DiagLive.State.Running -> if (stats != null) tr("na żywo", "live") else tr("czekam na dane", "waiting for data")
                    is DiagLive.State.Connecting -> tr("łączę…", "connecting…")
                    is DiagLive.State.Failed -> tr("błąd", "error")
                    DiagLive.State.Off -> tr("wyłączone", "off")
                },
                style = MaterialTheme.typography.labelSmall, color = colors.faint,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(stats?.let { "%.1f".format(it.mbps) } ?: "—", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(" Mb/s", style = MaterialTheme.typography.titleSmall, color = colors.muted, modifier = Modifier.padding(bottom = 4.dp))
            Spacer(Modifier.weight(1f))
            Figure("MCS", stats?.let { "%.0f".format(it.mcsAvg) })
            Spacer(Modifier.width(14.dp))
            Figure("BLER", stats?.let { "%.0f%%".format(it.bler * 100) })
            Spacer(Modifier.width(14.dp))
            Figure("Rank", stats?.let { "%.1f".format(it.layersAvg) })
            Spacer(Modifier.width(14.dp))
            Figure("RB", stats?.let { "%.0f".format(it.rbsAvg) })
        }
        Spacer(Modifier.height(10.dp))
        val mods = listOf(2, 4, 6, 8)
        val modColors = listOf(colors.bad, colors.poor, colors.fair, colors.good)
        Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.meterTrack)) {
            mods.forEachIndexed { i, q ->
                val share = stats?.modulation?.get(q) ?: 0.0
                if (share > 0) Box(Modifier.weight(share.toFloat()).fillMaxHeight().background(modColors[i]))
            }
            val rest = 1.0 - (stats?.modulation?.values?.sum() ?: 0.0)
            if (rest > 0.0001) Box(Modifier.weight(rest.toFloat()).fillMaxHeight())
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("QPSK", "16QAM", "64QAM", "256QAM").forEachIndexed { i, name ->
                val share = stats?.modulation?.get(mods[i])
                Text("$name ${share?.let { "%.0f%%".format(it * 100) } ?: ""}", style = MaterialTheme.typography.labelSmall, color = modColors[i].copy(alpha = 0.9f))
            }
        }
    }
}

@Composable
private fun Figure(label: String, value: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value ?: "—", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Btmap.colors.muted)
    }
}
