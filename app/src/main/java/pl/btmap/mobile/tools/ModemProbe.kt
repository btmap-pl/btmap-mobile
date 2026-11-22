package pl.btmap.mobile.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Checks, on the user's phone, every way BTMap could reach the modem: root, SELinux, the Qualcomm
 * diagnostic interface (/dev/diag, what Network Signal Guru reads MCS, modulation and BLER from),
 * related device nodes and drivers, the USB mode, vendor service apps and what the telephony
 * registry reports. The result goes into the diagnostics file a tester sends back.
 *
 * Nothing identifying is collected: properties and packages are filtered by name, and anything
 * that looks like a serial number, IMEI, IMSI, ICCID or phone number is left out.
 */
object ModemProbe {

    private val NODE_PATTERN = Regex("diag|smd|qmi|ipa|rmnet|mhi|at_|atci|umts|ttyGS|ttyHS|ttyACM|ttyUSB|subsys|radio|modem|cp_|umts_|nr_|lte_", RegexOption.IGNORE_CASE)
    private val PROP_PATTERN = Regex("usb|diag|ril|radio|modem|baseband|ro\\.hardware|ro\\.board|ro\\.soc|ro\\.product\\.board|ro\\.boot\\.hardware|selinux|qcom|qti|cp_|carrierid", RegexOption.IGNORE_CASE)
    private val PRIVATE_PATTERN = Regex("serial|imei|imsi|iccid|msisdn|meid|mac|number|phone_?num|sim_?id|subscriber|bt\\.|wifi|wlan|ssid|account|email", RegexOption.IGNORE_CASE)
    private val PACKAGE_PATTERN = Regex("servicemode|hiddenmenu|factory|sysscope|telephonyui|qualcomm|qti|\\.diag|ril|modem|networkdiag|cpcrash|silentlog|shizuku|magisk|kernelsu|apatch", RegexOption.IGNORE_CASE)

    suspend fun run(context: Context, root: Boolean): String = withContext(Dispatchers.IO) {
        val sh: (String) -> String = { cmd -> if (root) RootShell.exec("sh", "-c", cmd, timeoutSeconds = 15).second else plain(cmd) }
        buildString {
            appendLine("== Urządzenie")
            appendLine("model: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) · board=${Build.BOARD} · hardware=${Build.HARDWARE}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) appendLine("SoC: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
            appendLine("baseband: ${Build.getRadioVersion() ?: "?"}")
            appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · patch ${Build.VERSION.SECURITY_PATCH} · ${Build.DISPLAY}")

            appendLine()
            appendLine("== Dostęp")
            appendLine("root (su): zainstalowany=${RootShell.isInstalled()} · używany=$root")
            if (root) appendLine("su id: ${RootShell.exec("id").second.trim()}")
            appendLine("DUMP: ${context.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED}")
            appendLine("SELinux: ${sh("getenforce 2>&1").trim().ifEmpty { "?" }}")

            appendLine()
            appendLine("== /dev/diag (Qualcomm DIAG)")
            appendLine(sh("ls -lZ /dev/diag 2>&1").trim())
            if (root) {
                // Opening without reading: tells "no driver" from "blocked by SELinux / permissions".
                appendLine("otwarcie: " + sh("if [ -c /dev/diag ]; then dd if=/dev/diag of=/dev/null bs=1 count=0 2>&1 && echo OK; else echo 'brak urządzenia'; fi").trim())
            }

            appendLine()
            appendLine("== Urządzenia modemu w /dev")
            appendLine(filterLines(sh("ls -lZ /dev /dev/socket 2>&1"), NODE_PATTERN, max = 60))

            appendLine()
            appendLine("== Sterowniki (/proc/devices, /sys)")
            appendLine(filterLines(sh("cat /proc/devices 2>&1"), Regex("diag|smd|qmi|mhi|glink|rmnet|ipa", RegexOption.IGNORE_CASE), max = 20))
            appendLine(sh("ls /sys/class/diag /sys/bus/platform/drivers 2>&1 | grep -iE 'diag|qmi|mhi|glink|smd|cpif|shannon|modem' | head -30").trim().ifEmpty { "(brak)" })

            appendLine()
            appendLine("== Właściwości systemu (filtrowane)")
            appendLine(props(sh("getprop 2>&1")))

            appendLine()
            appendLine("== Aplikacje serwisowe / modemu")
            appendLine(filterLines(sh("pm list packages 2>&1"), PACKAGE_PATTERN, max = 40))

            appendLine()
            appendLine("== Telephony registry (nośne z modemu)")
            val registry = sh("dumpsys telephony.registry 2>&1")
            appendLine(
                registry.lineSequence()
                    .filter { it.contains("mPhysicalChannelConfigs=") || it.contains("mTelephonyDisplayInfo") || it.contains("Phone Id=") }
                    .map { it.trim().take(1500) }
                    .take(12)
                    .joinToString("\n")
                    .ifEmpty { "(brak dostępu: ${registry.lineSequence().firstOrNull()?.take(120) ?: "?"})" },
            )
        }
    }

    private fun filterLines(text: String, pattern: Regex, max: Int): String =
        text.lineSequence()
            .filter { pattern.containsMatchIn(it) && !PRIVATE_PATTERN.containsMatchIn(it) }
            .map { it.trim().take(200) }
            .take(max)
            .joinToString("\n")
            .ifEmpty { "(brak)" }

    private fun props(text: String): String =
        text.lineSequence()
            .filter { line ->
                val key = line.substringBefore("]:").removePrefix("[")
                PROP_PATTERN.containsMatchIn(key) && !PRIVATE_PATTERN.containsMatchIn(key) && !PRIVATE_PATTERN.containsMatchIn(line.substringAfter("]:"))
            }
            .map { it.trim().take(160) }
            .take(120)
            .joinToString("\n")
            .ifEmpty { "(brak)" }

    /** Without root: an ordinary shell as the app (many checks are then denied, which is itself useful). */
    private fun plain(cmd: String): String = runCatching {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroy(); return@runCatching "(timeout)" }
        p.inputStream.bufferedReader().readText()
    }.getOrDefault("")
}
