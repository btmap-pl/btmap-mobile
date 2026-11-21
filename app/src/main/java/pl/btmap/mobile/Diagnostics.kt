package pl.btmap.mobile

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Snapshot of what the modem reports and what the app made of it, saved to
 * Download/BTMap-diag-<time>.txt so problems can be investigated on phones without adb.
 * Contains no position beyond 3 decimals (~100 m).
 */
object Diagnostics {
    fun save(context: Context): String? {
        val app = context.applicationContext as BtmapApp
        val core = app.core
        val state = core.state.value
        val settings = app.settings.settings.value
        val text = buildString {
            appendLine("BTMap ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Czas: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())}")
            appendLine("Baza: ${settings.database} · info=${core.stations.info(settings.database)} · operatorzy=${core.stations.cellsPerOperator(settings.database)}")
            appendLine("Modem (DUMP): ${core.physicalChannels.isAvailable} · ostatni odczyt: ${core.physicalChannels.lastRead}")
            appendLine("GPS: ${state.fix?.let { "%.3f, %.3f ±%s m %s".format(Locale.ROOT, it.lat, it.lng, it.accuracyMeters, it.provider) } ?: "brak"}")
            appendLine("Stacje: ${state.stations.size} · sąsiedzi rozpoznani: ${state.neighbours.count { it.value != null }}/${state.neighbours.size}")
            for (sim in state.snapshot.sims) {
                appendLine()
                appendLine("== SIM ${sim.slotIndex + 1} ${sim.registeredPlmn} ${sim.mode} z modemu=${sim.carriersFromModem} bw=${sim.carrierBandwidthsKhz}")
                appendLine("-- nośne:")
                sim.carriers.forEach { c ->
                    appendLine("  ${if (c.isPrimary) "P" else "S"} ${c.rat} band=${c.band?.shortName}/${c.band?.label} ch=${c.channel} pci=${c.physicalId} bw=${c.bandwidthKhz} src=${c.source} id=${c.identity?.nodeLabel} pomiar=${c.cell?.signal?.mainDbm} wiek=${c.signalAgeMs}")
                }
                appendLine("-- komórki od modemu:")
                sim.cells.forEach { cell ->
                    val id = cell.identity
                    val n = state.neighbourOf(cell, sim.registeredPlmn ?: sim.simPlmn)
                    appendLine(
                        "  ${cell.role} ${id.rat} plmn=${id.plmn} ch=${id.channel} pci=${id.physicalId} cid=${id.cid} node=${id.nodeLabel} " +
                            "tac=${id.areaCode} band=${id.band?.shortName} learned=${id.learned} dbm=${cell.signal.mainDbm} rsrq=${cell.signal.rsrq} snr=${cell.signal.snr} " +
                            "stacja=${state.stationOf(cell)?.stationId ?: n?.station?.stationId ?: "-"}",
                    )
                }
            }
        }
        val name = "BTMap-diag-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())}.txt"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        return name
    }
}
