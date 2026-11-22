package pl.btmap.mobile.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Checks, on the user's phone, the ways BTMap could read detailed radio data (MCS, modulation,
 * BLER — what Network Signal Guru shows) from the modem: root, SELinux, the Qualcomm diagnostic
 * channel and the vendor daemon that owns it (diag-router on Snapdragon 8 Gen 2 and later), its
 * client tools and sockets, Samsung's modem log folders, and what the telephony registry reports.
 * The result goes into the diagnostics file a tester sends back.
 *
 * Read-only: nothing is written to the modem. Nothing identifying is collected: properties and
 * packages are filtered by name, and anything that looks like a serial number, IMEI, IMSI, ICCID
 * or phone number is left out.
 */
object ModemProbe {

    private val NODE_PATTERN = Regex("diag|mhi|smd|qmi|glink|at_", RegexOption.IGNORE_CASE)
    private val PROP_PATTERN = Regex(
        "usb\\.config|diag|^ril\\.|\\.ril\\.|^persist\\.radio\\.(block|def_network|multisim)|baseband|ril-impl|" +
            "ro\\.hardware|ro\\.board\\.platform|ro\\.soc|selinux|slog|silent|cplog|init\\.svc\\.(vendor\\.)?(diag|ril|qcom|cnss)",
        RegexOption.IGNORE_CASE,
    )
    private val NOISE_PATTERN = Regex("bluetooth|dhcp|nitz|svc_debug_pid|sms|esim|emer", RegexOption.IGNORE_CASE)
    private val PRIVATE_PATTERN = Regex("serial|imei|imsi|iccid|msisdn|meid|mac|number|phone_?num|sim_?id|subscriber|wifi|wlan|ssid|account|email", RegexOption.IGNORE_CASE)
    private val PACKAGE_PATTERN = Regex("servicemode|hiddenmenu|factory|sysscope|qualcomm|qti|\\.diag|ril|modem|networkdiag|cpcrash|silentlog|shizuku|magisk|kernelsu|apatch", RegexOption.IGNORE_CASE)

    suspend fun run(context: Context, root: Boolean): String = withContext(Dispatchers.IO) {
        val sh: (String) -> String = { cmd -> if (root) RootShell.exec("sh", "-c", cmd, timeoutSeconds = 20).second else plain(cmd) }
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
            appendLine("== Kanał DIAG modemu")
            appendLine(sh(DIAG_CHANNEL).trim().ifEmpty { "(brak)" })

            if (root) {
                appendLine()
                appendLine("== diag-router: narzędzia, gniazda, logi")
                appendLine(RootShell.exec("sh", "-c", DIAG_ROUTER, timeoutSeconds = 60).second.trim().ifEmpty { "(brak wyniku)" })
                appendLine()
                appendLine("== DIAG przez gniazdo (diag_socket_log)")
                appendLine(DiagSocket.versionTest())
            }

            appendLine()
            appendLine("== Urządzenia modemu w /dev")
            appendLine(filterLines(sh("ls -lZ /dev /dev/socket 2>&1"), NODE_PATTERN, max = 30))

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

    /** The classic /dev/diag, or the MHI pipe with the diag SELinux type, and who holds it. */
    private val DIAG_CHANNEL = """
        ls -lZ /dev/diag 2>&1
        for n in ${'$'}(ls -Z /dev 2>/dev/null | grep -E 'mhi_diag|:diag_device' | awk '{print ${'$'}NF}'); do
          d=/dev/${'$'}n; [ -c "${'$'}d" ] || continue
          ls -lZ "${'$'}d"
          h=${'$'}(for p in /proc/[0-9]*; do ls -l ${'$'}p/fd 2>/dev/null | grep -q "${'$'}d" && tr '\0' ' ' < ${'$'}p/cmdline; done | sort -u | head -5)
          echo "  używa: ${'$'}{h:-(nikt)}"
        done
    """.trimIndent()

    /**
     * On Snapdragon 8 Gen 2 and later the vendor daemon diag-router owns the modem's DIAG channel
     * and serves clients (USB, on-device loggers). This lists its client tools and their usage,
     * the sockets it listens on, what it has open, and the folders where Samsung's modem logging
     * (*#9900# → Silent Log / CP logging) writes .qmdl files. Read-only.
     */
    private val DIAG_ROUTER = """
        echo "-- narzędzia:"
        ls -l /vendor/bin /system/bin /system_ext/bin /product/bin /odm/bin 2>/dev/null | grep -iE 'diag|mdlog|qlog|slog|cplog|qxdm' | awk '{print ${'$'}1, ${'$'}NF}' | head -20
        for t in /vendor/bin/diag_mdlog /system/bin/diag_mdlog /vendor/bin/diag_socket_log /system/bin/diag_socket_log; do
          [ -x "${'$'}t" ] && { echo "-- ${'$'}t -h:"; timeout 3 "${'$'}t" -h 2>&1 | head -25; }
        done
        echo "-- gniazda:"
        grep -iE 'diag' /proc/net/unix 2>/dev/null | awk '{print ${'$'}NF}' | sort -u | head -10
        ls -la /dev/socket 2>/dev/null | grep -iE 'diag' | head -10
        pid=${'$'}(pidof diag-router)
        if [ -n "${'$'}pid" ]; then
          echo "-- diag-router (pid ${'$'}pid): ${'$'}(tr '\0' ' ' < /proc/${'$'}pid/cmdline)"
          ls -l /proc/${'$'}pid/fd 2>/dev/null | sed 's/.*-> //' | sed 's/socket:\[[0-9]*\]/socket/' | sort | uniq -c | sort -rn | head -15
          echo "-- kontekst: ${'$'}(cat /proc/${'$'}pid/attr/current 2>/dev/null)"
        fi
        echo "-- logi modemu:"
        for d in /data/slog /data/vendor/slog /data/log /data/vendor/log /data/vendor/diag /sdcard/log /sdcard/diag_logs /data/local/tmp/diag_logs; do
          [ -d "${'$'}d" ] && { echo "${'$'}d:"; ls -la "${'$'}d" 2>&1 | head -8; }
        done
        find /data/slog /data/vendor/slog /data/log /sdcard/log /sdcard/diag_logs -iname '*.qmdl*' 2>/dev/null | head -5
    """.trimIndent()

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
                PROP_PATTERN.containsMatchIn(key) && !NOISE_PATTERN.containsMatchIn(key) &&
                    !PRIVATE_PATTERN.containsMatchIn(key) && !PRIVATE_PATTERN.containsMatchIn(line.substringAfter("]:"))
            }
            .map { it.trim().take(160) }
            .take(60)
            .joinToString("\n")
            .ifEmpty { "(brak)" }

    /** Without root: an ordinary shell as the app (many checks are then denied, which is itself useful). */
    private fun plain(cmd: String): String = runCatching {
        RootShell.collect(ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start(), 15).second
    }.getOrDefault("")
}
