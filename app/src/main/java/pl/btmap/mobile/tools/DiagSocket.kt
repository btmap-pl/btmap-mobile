package pl.btmap.mobile.tools

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

/**
 * Talks to the modem's diagnostic interface through Qualcomm's `diag_socket_log`, which on newer
 * Snapdragons forwards diag-router's DIAG stream to a TCP address (what QXDM uses over a network)
 * and passes commands back. BTMap listens on 127.0.0.1, starts the tool as root pointed at that
 * port, and exchanges HDLC-framed DIAG packets with the modem.
 */
object DiagSocket {

    private val TOOLS = listOf("/vendor/bin/diag_socket_log", "/system/bin/diag_socket_log")
    private const val LOG = "/data/local/tmp/btmap_diag_socket.log"

    /** "Version" (0x00) and "extended build ID" (0x7C) requests, HDLC-framed with their CRC. */
    val VERSION_REQUEST = byteArrayOf(0x00, 0x78, 0xF0.toByte(), 0x7E)
    val BUILD_ID_REQUEST = byteArrayOf(0x7C, 0x93.toByte(), 0x49, 0x7E)

    /**
     * Read-only check for the diagnostics report: connects, asks the modem for its version and
     * build ID, prints the replies, and stops the tool again. Nothing in the modem is changed.
     */
    fun versionTest(): String {
        val tool = TOOLS.firstOrNull { RootShell.exec("sh", "-c", "[ -x $it ] && echo yes").second.contains("yes") }
            ?: return "brak diag_socket_log"
        val out = StringBuilder()
        runCatching {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 10_000
                val port = server.localPort
                RootShell.exec("sh", "-c", "$tool -k >/dev/null 2>&1; (nohup $tool -a 127.0.0.1 -p $port -r 3 >$LOG 2>&1 &)")
                out.appendLine("$tool → 127.0.0.1:$port")
                val socket = try {
                    server.accept()
                } catch (e: SocketTimeoutException) {
                    out.appendLine("brak połączenia w 10 s")
                    return@use
                }
                socket.use { s ->
                    s.soTimeout = 400
                    out.appendLine("połączono")
                    for ((name, request) in listOf("wersja (00)" to VERSION_REQUEST, "build ID (7C)" to BUILD_ID_REQUEST)) {
                        s.getOutputStream().apply { write(request); flush() }
                        val reply = readFor(s.getInputStream(), 3_000)
                        out.appendLine("$name → ${reply.size} B")
                        if (reply.isNotEmpty()) {
                            out.appendLine("  hex: ${hex(reply, 160)}")
                            out.appendLine("  tekst: ${printable(reply, 160)}")
                        }
                    }
                }
            }
        }.onFailure { out.appendLine("błąd: ${it.javaClass.simpleName}: ${it.message}") }
        out.appendLine("-- log narzędzia:")
        out.append(RootShell.exec("sh", "-c", "$tool -k >/dev/null 2>&1; pkill -f diag_socket_log; sleep 1; head -c 1500 $LOG; rm -f $LOG").second.trim())
        return out.toString().trim()
    }

    /** What a capture recorded, for the summary next to the .qmdl file. */
    data class CaptureSummary(
        val bytes: Long,
        val frames: Int,
        val badFrames: Int,
        val perCode: Map<Int, Int>,
        val lastItem: Int?,
        val maskStatus: Int?,
        val error: String?,
    )

    /**
     * Records the modem's LTE / NR5G radio logs for [seconds] into [out] as a raw DIAG stream (the
     * .qmdl format QXDM, QCSuper and SCAT read): switches on only [DiagLogCodes.NAMES], records,
     * then switches logging off again and stops the tool. [onSecond] reports progress.
     */
    fun capture(seconds: Int, out: java.io.OutputStream, onSecond: (Int) -> Unit = {}): CaptureSummary {
        val tool = TOOLS.firstOrNull { RootShell.exec("sh", "-c", "[ -x $it ] && echo yes").second.contains("yes") }
            ?: return CaptureSummary(0, 0, 0, emptyMap(), null, null, "brak diag_socket_log")
        val decoder = pl.btmap.core.diag.Hdlc.Decoder()
        val perCode = HashMap<Int, Int>()
        var frames = 0
        var bytes = 0L
        var ranges: IntArray? = null
        var maskStatus: Int? = null
        var error: String? = null
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 10_000
                RootShell.exec("sh", "-c", "$tool -k >/dev/null 2>&1; (nohup $tool -a 127.0.0.1 -p ${server.localPort} -r 3 >$LOG 2>&1 &)")
                server.accept().use { s ->
                    s.soTimeout = 300
                    val input = s.getInputStream()
                    val output = s.getOutputStream()
                    val buf = ByteArray(16_384)
                    fun pump(millis: Long, tick: Boolean = false) {
                        val start = System.currentTimeMillis()
                        var lastSecond = -1
                        while (System.currentTimeMillis() - start < millis) {
                            val n = try { input.read(buf) } catch (_: SocketTimeoutException) { 0 }
                            if (n < 0) break
                            if (n > 0) {
                                out.write(buf, 0, n)
                                bytes += n
                                decoder.feed(buf, n) { f ->
                                    frames++
                                    pl.btmap.core.diag.DiagCommands.logCode(f)?.let { perCode[it] = (perCode[it] ?: 0) + 1 }
                                    if ((f[0].toInt() and 0xFF) == pl.btmap.core.diag.DiagCommands.LOG_CONFIG && f.size >= 12) {
                                        pl.btmap.core.diag.DiagCommands.parseIdRanges(f)?.let { ranges = it }
                                        val op = java.nio.ByteBuffer.wrap(f).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(4)
                                        if (op == 3) maskStatus = java.nio.ByteBuffer.wrap(f).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(8)
                                    }
                                }
                            }
                            if (tick) {
                                val sec = ((System.currentTimeMillis() - start) / 1000).toInt()
                                if (sec != lastSecond) { lastSecond = sec; onSecond(sec) }
                            }
                        }
                    }
                    fun send(cmd: ByteArray) = output.apply { write(pl.btmap.core.diag.Hdlc.encode(cmd)); flush() }
                    try {
                        send(pl.btmap.core.diag.DiagCommands.retrieveIdRanges)
                        pump(3_000)
                        val last = ranges?.getOrNull(pl.btmap.core.diag.DiagCommands.EQUIP_LTE_NR)?.takeIf { it > 0 } ?: 0xFFF
                        send(pl.btmap.core.diag.DiagCommands.setMask(pl.btmap.core.diag.DiagCommands.EQUIP_LTE_NR, last, pl.btmap.core.diag.DiagLogCodes.NAMES.keys))
                        pump(seconds * 1_000L, tick = true)
                    } finally {
                        // Logging off again, whatever happened.
                        runCatching { send(pl.btmap.core.diag.DiagCommands.disableAll); pump(1_500) }
                    }
                }
            }
        } catch (e: Exception) {
            error = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            RootShell.exec("sh", "-c", "$tool -k >/dev/null 2>&1; pkill -f diag_socket_log; rm -f $LOG")
        }
        return CaptureSummary(bytes, frames, decoder.badFrames, perCode, ranges?.getOrNull(pl.btmap.core.diag.DiagCommands.EQUIP_LTE_NR), maskStatus, error)
    }

    /** Collects whatever arrives within [millis]. */
    fun readFor(input: java.io.InputStream, millis: Long): ByteArray {
        val buf = ByteArray(4096)
        val got = ByteArrayOutputStream()
        val end = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < end && got.size() < 65_536) {
            val n = try { input.read(buf) } catch (_: SocketTimeoutException) { continue }
            if (n < 0) break
            got.write(buf, 0, n)
        }
        return got.toByteArray()
    }

    fun hex(bytes: ByteArray, max: Int) =
        bytes.take(max).joinToString(" ") { "%02x".format(it) } + if (bytes.size > max) " …" else ""

    fun printable(bytes: ByteArray, max: Int) =
        bytes.take(max).map { b -> val c = b.toInt() and 0xFF; if (c in 32..126) c.toChar() else '.' }.joinToString("")
}
