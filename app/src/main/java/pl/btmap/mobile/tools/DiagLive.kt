package pl.btmap.mobile.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

/**
 * Live modem readout (root, Qualcomm): a DIAG session through diag_socket_log that switches on the
 * LTE PDSCH statistics log, decodes it and publishes downlink statistics twice a second. Runs only
 * while a screen holds it; [stop] switches the log off again and stops the tool.
 */
object DiagLive {
    sealed interface State {
        data object Off : State
        data object Connecting : State
        data class Running(val stats: DownlinkStats?, val packets: Long, val since: Long) : State
        data class Failed(val message: String) : State
    }

    private val TOOLS = listOf("/vendor/bin/diag_socket_log", "/system/bin/diag_socket_log")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        _state.value = State.Connecting
        job = scope.launch { run() }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun run() {
        val tool = TOOLS.firstOrNull { RootShell.exec("sh", "-c", "[ -x $it ] && echo yes").second.contains("yes") }
        if (tool == null) {
            _state.value = State.Failed("diag_socket_log not found"); return
        }
        val window = DownlinkWindow(1_000)
        var packets = 0L
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
                    val input = s.getInputStream()
                    val output = s.getOutputStream()
                    fun send(cmd: ByteArray) { output.write(Hdlc.encode(cmd)); output.flush() }
                    val decoder = Hdlc.Decoder()
                    val buf = ByteArray(32_768)
                    var ranges: IntArray? = null
                    // Ranges first; the mask uses their item count + 1 (the form this modem accepts).
                    send(DiagCommands.retrieveIdRanges)
                    val deadline = System.currentTimeMillis() + 3_000
                    while (ranges == null && System.currentTimeMillis() < deadline) {
                        val n = try { input.read(buf) } catch (_: SocketTimeoutException) { 0 }
                        if (n < 0) break
                        decoder.feed(buf, n) { f -> DiagCommands.parseIdRanges(DiagCommands.unwrap(f))?.let { ranges = it } }
                    }
                    val last = ranges?.getOrNull(DiagCommands.EQUIP_LTE_NR)?.takeIf { it > 0 } ?: 0x9FF
                    send(DiagCommands.setMaskBits(DiagCommands.EQUIP_LTE_NR, last + 1, listOf(LtePdsch.CODE)))
                    _state.value = State.Running(null, 0, since)
                    var published = 0L
                    try {
                        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                            val n = try { input.read(buf) } catch (_: SocketTimeoutException) { 0 }
                            if (n < 0) break
                            decoder.feed(buf, n) { f ->
                                if (DiagCommands.logCode(f) == LtePdsch.CODE) {
                                    packets++
                                    DiagCommands.logPayload(f)?.let { window.add(LtePdsch.decode(it)) }
                                }
                            }
                            val now = System.currentTimeMillis()
                            if (now - published >= 500) {
                                published = now
                                _state.value = State.Running(window.stats(now), packets, since)
                            }
                        }
                    } finally {
                        runCatching { send(DiagCommands.disableAll) }
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
