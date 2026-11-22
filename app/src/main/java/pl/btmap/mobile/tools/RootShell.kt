package pl.btmap.mobile.tools

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Root access through `su` (Magisk, KernelSU, APatch). An alternative to Shizuku on rooted phones:
 * the same shell commands (`pm grant`, `cmd phone`) run as root. The first call makes the root
 * manager ask the user to allow BTMap.
 */
object RootShell {

    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/debug_ramdisk/su", "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su",
    )

    /** A `su` binary is present (says nothing about whether BTMap is allowed to use it). */
    fun isInstalled(): Boolean =
        SU_PATHS.any { File(it).exists() } ||
            (System.getenv("PATH") ?: "").split(':').any { File(it, "su").exists() }

    /** Asks for root (the root manager shows its prompt); true when BTMap runs commands as uid 0. */
    fun request(): Boolean {
        val (code, out) = exec("id", timeoutSeconds = 60)
        return code == 0 && out.contains("uid=0")
    }

    /** Runs a command as root; returns exit code and combined output (-1 when root is unavailable). */
    fun exec(vararg command: String, timeoutSeconds: Long = 20): Pair<Int, String> = runCatching {
        val process = ProcessBuilder("su").redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { w ->
            w.write(command.joinToString(" ") { quote(it) })
            w.write("\nexit\n")
        }
        collect(process, timeoutSeconds)
    }.getOrDefault(-1 to "")

    /**
     * Waits for [process] while reading its output. Reading only after it exits deadlocks on large
     * output (`getprop`, `dumpsys`): the process blocks on a full pipe and never exits.
     */
    fun collect(process: Process, timeoutSeconds: Long): Pair<Int, String> {
        val out = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(8192)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        synchronized(out) { if (out.length < MAX_OUTPUT) out.append(buf, 0, n) }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) process.destroy()
        reader.join(2_000)
        val text = synchronized(out) { out.toString() }
        return (if (finished) process.exitValue() else -1) to text
    }

    private const val MAX_OUTPUT = 2_000_000

    private fun quote(arg: String) =
        if (arg.all { it.isLetterOrDigit() || it in "-_./:=,@" }) arg else "'" + arg.replace("'", "'\\''") + "'"
}
