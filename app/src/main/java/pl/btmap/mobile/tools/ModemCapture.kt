package pl.btmap.mobile.tools

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.btmap.core.diag.DiagLogCodes
import pl.btmap.mobile.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the modem's radio logs (root, Qualcomm) to Downloads, two ways one after the other:
 * through diag_socket_log (BTMap talks to the modem itself) and through diag_mdlog (the vendor's
 * own logger with a mask file). Each produces .qmdl data; a summary says what came back.
 */
object ModemCapture {
    data class Result(val files: List<Uri>, val summary: String)

    suspend fun record(context: Context, seconds: Int, onSecond: (Int) -> Unit): Result? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val half = seconds / 2
        val files = mutableListOf<Uri>()

        val qmdl = create(context, "BTMap-modem-$stamp.qmdl", "application/octet-stream") ?: return@withContext null
        files += qmdl
        val socket = context.contentResolver.openOutputStream(qmdl)!!.buffered().use { out ->
            DiagSocket.capture(half, out, onSecond)
        }

        val mdlog = runCatching {
            DiagMdlog.capture(context, seconds - half, socket.lastItem ?: 0x9FF) { s -> onSecond(half + s) }
        }.getOrElse { DiagMdlog.Result(emptyList(), "błąd: ${it.message}") }
        for (f in mdlog.files) {
            create(context, "BTMap-mdlog-$stamp-${f.name}", "application/octet-stream")?.let { uri ->
                context.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                files += uri
            }
            f.delete()
        }

        val summary = buildString {
            appendLine("BTMap ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · ${Build.getRadioVersion() ?: "?"}")
            appendLine()
            appendLine("== 1. diag_socket_log ($half s): ${socket.bytes} B · ramek ${socket.frames} · błędnych ${socket.badFrames} · zdarzeń ${socket.events}")
            appendLine("Ostatni element maski LTE/NR: ${socket.lastItem?.let { "0x%X".format(it) } ?: "?"}")
            socket.error?.let { appendLine("Błąd: $it") }
            socket.steps.forEach { appendLine("  $it") }
            appendLine("Pakiety logów:")
            DiagLogCodes.NAMES.forEach { (code, name) -> appendLine("  0x%04X %-40s %d".format(code, name, socket.perCode[code] ?: 0)) }
            (socket.perCode.keys - DiagLogCodes.NAMES.keys).sorted().forEach { appendLine("  0x%04X (inne) %d".format(it, socket.perCode[it])) }
            appendLine()
            appendLine("== 2. diag_mdlog (${seconds - half} s): plików ${mdlog.files.size} · ${mdlog.files.sumOf { it.length() }} B")
            appendLine(mdlog.log)
        }
        create(context, "BTMap-modem-$stamp.txt", "text/plain")?.let { uri ->
            context.contentResolver.openOutputStream(uri)?.use { it.write(summary.toByteArray()) }
            files += uri
        }
        Result(files, summary)
    }

    private fun create(context: Context, name: String, mime: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }
}
