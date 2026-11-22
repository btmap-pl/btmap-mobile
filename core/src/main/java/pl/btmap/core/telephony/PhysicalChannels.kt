package pl.btmap.core.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import pl.btmap.core.model.Rat
import java.util.concurrent.TimeUnit

/**
 * One component carrier exactly as the modem configured it (Android's PhysicalChannelConfig):
 * PCell, every CA secondary and the NR leg of EN-DC, with band, channel, PCI and bandwidth.
 */
data class PhysicalChannel(
    val primary: Boolean,
    val rat: Rat,
    val band: Int?,
    val channel: Int?,
    val pci: Int?,
    val bandwidthKhz: Int?,
    val downlinkKhz: Long?,
)

/**
 * Reads the modem's carrier configuration for every SIM.
 *
 * Regular apps cannot subscribe to PhysicalChannelConfig (it needs a privileged permission), but
 * the telephony registry publishes the same data in its dump. With `android.permission.DUMP`,
 * which a user can grant once over adb (`pm grant pl.btmap.mobile android.permission.DUMP`), the
 * app reads it directly — the bands come from the modem, not from guesses.
 */
class PhysicalChannelReader(context: Context) {
    private val appContext = context.applicationContext

    val isAvailable: Boolean
        get() = ContextCompat.checkSelfPermission(appContext, Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED

    /** Result of the last [read]: carriers per SIM, or null if it failed; for diagnostics. */
    @Volatile var lastRead: Map<Int, List<PhysicalChannel>>? = null
        private set

    /** Carriers per phone id (= SIM slot), or null when the dump cannot be read. */
    fun read(): Map<Int, List<PhysicalChannel>>? = readDump().also { lastRead = it }

    private fun readDump(): Map<Int, List<PhysicalChannel>>? {
        if (!isAvailable) return null
        val process = runCatching { ProcessBuilder("dumpsys", "telephony.registry").redirectErrorStream(true).start() }
            .getOrNull() ?: return null
        return try {
            // The per-phone sections come first; stop reading once past them (the rest is history logs).
            val head = StringBuilder()
            process.inputStream.bufferedReader().useLines { lines ->
                var seenConfigs = 0
                for (line in lines) {
                    head.append(line).append('\n')
                    if (line.contains("mPhysicalChannelConfigs=")) seenConfigs++
                    if (line.contains("local logs:") || head.length > 200_000 || (seenConfigs >= 2 && line.isBlank())) break
                }
            }
            if (head.contains("Permission Denial")) null else parse(head.toString())
        } catch (e: Exception) {
            null
        } finally {
            runCatching { process.destroy() }
            runCatching { process.waitFor(1, TimeUnit.SECONDS) }
        }
    }

    companion object {
        private val PHONE = Regex("""^\s*Phone Id=(\d+)""")
        private val ENTRY = Regex("""\{([^{}]*)\}""")
        private val FIELD = Regex("""(m\w+)=([^,}]*)""")

        /** Parses the `Phone Id=N` sections of `dumpsys telephony.registry`. */
        fun parse(dump: String): Map<Int, List<PhysicalChannel>> {
            val result = mutableMapOf<Int, List<PhysicalChannel>>()
            var phone = -1
            for (line in dump.lineSequence()) {
                PHONE.find(line)?.let { phone = it.groupValues[1].toInt() }
                val idx = line.indexOf("mPhysicalChannelConfigs=")
                if (idx < 0 || phone < 0) continue
                val list = ENTRY.findAll(line.substring(idx)).mapNotNull { parseEntry(it.groupValues[1]) }.toList()
                result[phone] = list.sortedByDescending { it.primary }
            }
            return result
        }

        private fun parseEntry(body: String): PhysicalChannel? {
            val f = FIELD.findAll(body).associate { it.groupValues[1] to it.groupValues[2].trim() }
            val rat = when (f["mNetworkType"]?.uppercase()) {
                "LTE", "LTE_CA" -> Rat.LTE
                "NR" -> Rat.NR
                "UMTS", "HSPA", "HSPAP", "HSDPA", "HSUPA" -> Rat.WCDMA
                "GSM", "EDGE", "GPRS" -> Rat.GSM
                else -> return null
            }
            fun int(key: String) = f[key]?.toIntOrNull()?.takeIf { it >= 0 && it != Int.MAX_VALUE }
            return PhysicalChannel(
                primary = f["mConnectionStatus"]?.contains("Primary", ignoreCase = true) == true,
                rat = rat,
                band = int("mBand")?.takeIf { it > 0 },
                // Samsung modems report 0 for carriers whose channel they do not fill in. Channel 0 is
                // the very edge of band 1 (LTE) and not a usable NR channel, so it means "unknown".
                channel = int("mDownlinkChannelNumber")?.takeIf { it > 0 },
                pci = int("mPhysicalCellId"),
                bandwidthKhz = int("mCellBandwidthDownlinkKhz")?.takeIf { it > 0 },
                downlinkKhz = f["mDownlinkFrequency"]?.toLongOrNull()?.takeIf { it > 0 },
            )
        }
    }
}
