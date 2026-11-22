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
 * Records the modem's radio logs (root, Qualcomm) to Downloads: a .qmdl file with the raw DIAG
 * stream and a short summary of what was captured, both ready to share.
 */
object ModemCapture {
    data class Result(val files: List<Uri>, val summary: String)

    suspend fun record(context: Context, seconds: Int, onSecond: (Int) -> Unit): Result? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val qmdl = create(context, "BTMap-modem-$stamp.qmdl", "application/octet-stream") ?: return@withContext null
        val result = context.contentResolver.openOutputStream(qmdl)!!.buffered().use { out ->
            DiagSocket.capture(seconds, out, onSecond)
        }
        val summary = buildString {
            appendLine("BTMap ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · ${Build.getRadioVersion() ?: "?"}")
            appendLine("Nagranie DIAG: $seconds s · ${result.bytes} B · ramek ${result.frames} · błędnych ${result.badFrames}")
            appendLine("Ostatni element maski LTE/NR: ${result.lastItem?.let { "0x%X".format(it) } ?: "?"} · status maski: ${result.maskStatus ?: "?"}")
            result.error?.let { appendLine("Błąd: $it") }
            appendLine("Pakiety logów:")
            DiagLogCodes.NAMES.forEach { (code, name) ->
                appendLine("  0x%04X %-40s %d".format(code, name, result.perCode[code] ?: 0))
            }
            (result.perCode.keys - DiagLogCodes.NAMES.keys).sorted().forEach { appendLine("  0x%04X (inne) %d".format(it, result.perCode[it])) }
        }
        val txt = create(context, "BTMap-modem-$stamp.txt", "text/plain")
        txt?.let { uri -> context.contentResolver.openOutputStream(uri)?.use { it.write(summary.toByteArray()) } }
        Result(listOfNotNull(qmdl, txt), summary)
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
