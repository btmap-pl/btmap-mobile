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
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroy()
            return@runCatching -1 to ""
        }
        process.exitValue() to process.inputStream.bufferedReader().readText()
    }.getOrDefault(-1 to "")

    private fun quote(arg: String) =
        if (arg.all { it.isLetterOrDigit() || it in "-_./:=,@" }) arg else "'" + arg.replace("'", "'\\''") + "'"
}
