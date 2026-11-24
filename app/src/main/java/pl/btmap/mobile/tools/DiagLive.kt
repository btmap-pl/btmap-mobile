package pl.btmap.mobile.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.btmap.core.diag.DiagCommands
import pl.btmap.core.diag.DownlinkStats
import pl.btmap.core.diag.DownlinkWindow
import pl.btmap.core.diag.Hdlc
import pl.btmap.core.diag.LtePdsch
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Live modem readout (root, Qualcomm): a DIAG session through diag_socket_log that switches on the
 * LTE PDSCH statistics log, decodes it and publishes downlink statistics twice a second. Runs only
 * while a screen holds it; [stop] switches the log off again and stops the tool.
 *
 * The modem answers commands seconds late and drops a request sent while an earlier one is still
 * pending, so every command waits for its reply, the log mask is repeated until the modem confirms
 * it, and it is sent again whenever the data stops.
 */
object DiagLive {
    /** Where the session stands, shown on screen so a stall can be told apart. */
    enum class Stage { STARTING, RANGES, MASK, LISTENING }

    sealed interface State {
        data object Off : State
        data class Connecting(val stage: Stage, val attempt: Int = 0) : State
        data class Running(
            val stats: DownlinkStats?,
            val packets: Long,
            val since: Long,
            val maskConfirmed: Boolean,
            val maskAttempts: Int,
            /** Frames of any kind received, to tell "silent modem" from "no PDSCH log". */
            val frames: Long,
        ) : State
        data class Failed(val message: String) : State
    }

    private val TOOLS = listOf("/vendor/bin/diag_socket_log", "/system/bin/diag_socket_log")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    private var holders = 0

    /** Called by each screen that shows modem data; the session runs while at least one holds it. */
    @Synchronized
    fun start() {
        holders++
        if (job?.isActive == true) return
        _state.value = State.Connecting(Stage.STARTING)
        job = scope.launch { run() }
    }

    @Synchronized
    fun stop() {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders > 0) return
        job?.cancel()
        job = null
    }

    private class Link(private val input: InputStream, private val output: OutputStream) {
        val decoder = Hdlc.Decoder()
        private val buf = ByteArray(32_768)

        fun send(cmd: ByteArray) { output.write(Hdlc.encode(cmd)); output.flush() }

        /** Reads for up to [millis], handing every frame to [onFrame]; stops early when it returns true. */
        fun pump(millis: Long, onFrame: (ByteArray) -> Boolean): Boolean {
            val end = System.currentTimeMillis() + millis
            var done = false
            while (!done && System.currentTimeMillis() < end) {
                val n = try { input.read(buf) } catch (_: SocketTimeoutException) { 0 }
                if (n < 0) throw java.io.EOFException("diag_socket_log closed the connection")
                decoder.feed(buf, n) { f -> if (!done && onFrame(f)) done = true }
            }
            return done
        }
    }

    private fun logConfigOp(frame: ByteArray): Int? {
        val f = DiagCommands.unwrap(frame)
        if (f.size < 12 || (f[0].toInt() and 0xFF) != DiagCommands.LOG_CONFIG) return null
        return ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
    }

    private suspend fun run() {
        val tool = TOOLS.firstOrNull { RootShell.exec("sh", "-c", "[ -x $it ] && echo yes").second.contains("yes") }
        if (tool == null) {
            _state.value = State.Failed("diag_socket_log not found"); return
        }
        val window = DownlinkWindow(1_000)
        var packets = 0L
        var frames = 0L
        val since = System.currentTimeMillis()
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 10_000
                RootShell.exec("sh", "-c", "$tool -k >/dev/null 2>&1; (nohup $tool -a 127.0.0.1 -p ${server.localPort} -r 3 >/dev/null 2>&1 &)")
                val socket = try { server.accept() } catch (_: SocketTimeoutException) {
                    _state.value = State.Failed("no connection from diag_socket_log"); return
                }
                socket.use { s ->
                    s.soTimeout = 250
                    val link = Link(s.getInputStream(), s.getOutputStream())
                    val count: (ByteArray) -> Unit = { f ->
                        frames++
                        if (DiagCommands.logCode(f) == LtePdsch.CODE) {
                            packets++
                            DiagCommands.logPayload(f)?.let { window.add(LtePdsch.decode(it)) }
                        }
                    }

                    // 1. Item ranges: wait for the (slow) reply before sending anything else.
                    _state.value = State.Connecting(Stage.RANGES)
                    var ranges: IntArray? = null
                    link.send(DiagCommands.retrieveIdRanges)
                    link.pump(10_000) { f ->
                        count(f)
                        DiagCommands.parseIdRanges(DiagCommands.unwrap(f))?.let { ranges = it }
                        ranges != null
                    }
                    val last = ranges?.getOrNull(DiagCommands.EQUIP_LTE_NR)?.takeIf { it > 0 } ?: 0x9FF
                    val mask = DiagCommands.setMaskBits(DiagCommands.EQUIP_LTE_NR, last + 1, listOf(LtePdsch.CODE))

                    // 2. Log mask, repeated until the modem confirms it (or PDSCH packets arrive).
                    var confirmed = false
                    var attempts = 0
                    while (!confirmed && attempts < 6 && currentCoroutineContext().isActive) {
                        attempts++
                        _state.value = State.Connecting(Stage.MASK, attempts)
                        link.send(mask)
                        confirmed = link.pump(4_000) { f ->
                            count(f)
                            logConfigOp(f) == 3 || DiagCommands.logCode(f) == LtePdsch.CODE
                        }
                    }

                    // 3. Listen; if PDSCH data stops for a while, send the mask again.
                    var published = 0L
                    var lastPacketAt = System.currentTimeMillis()
                    var lastResend = System.currentTimeMillis()
                    try {
                        while (currentCoroutineContext().isActive) {
                            val before = packets
                            link.pump(250) { f -> count(f); false }
                            val now = System.currentTimeMillis()
                            if (packets > before) lastPacketAt = now
                            if (now - lastPacketAt > 8_000 && now - lastResend > 8_000) {
                                lastResend = now
                                attempts++
                                link.send(mask)
                            }
                            if (now - published >= 500) {
                                published = now
                                _state.value = State.Running(window.stats(now), packets, since, confirmed || packets > 0, attempts, frames)
                            }
                        }
                    } finally {
                        runCatching { link.send(DiagCommands.disableAll) }
                    }
                }
            }
            _state.value = State.Off
        } catch (e: Exception) {
            _state.value = if (e is kotlinx.coroutines.CancellationException) State.Off else State.Failed("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            RootShell.exec("sh", "-c", "$tool -k >/dev/null 2>&1; pkill -f diag_socket_log")
            if (_state.value !is State.Failed) _state.value = State.Off
        }
    }
}
