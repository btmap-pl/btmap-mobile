package pl.btmap.mobile.tools

import android.os.Parcel
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/** Band masks of a MediaTek modem as `AT+EPBSEH` reports them (hex strings). */
data class MtkBandConfig(val gsm: String, val umts: String, val lte: String, val nr: String) {
    val lteBands: Set<Int> get() = MtkBandControl.bands(lte)
    val nrBands: Set<Int> get() = MtkBandControl.bands(nr)
}

/**
 * Real band locking on MediaTek modems: `AT+EPBSEH` sent through MediaTek's RIL passthrough
 * (`IMtkTelephonyEx.invokeOemRilRequestRawBySlot`) with Shizuku's shell identity. The RIL filters
 * AT commands on user builds, but the hex band-select command passes (verified on the POCO X6 Pro:
 * disabling B8 moved the SIM to B3/B20 within seconds). `AT+EPBSEH=?` also gives the bands the modem
 * supports — the real capability list, not a guess.
 *
 * Masks: 8-hex-digit words, word i bit b = band 32·i + b + 1 (LTE / NR band numbers).
 */
object MtkBandControl {
    private const val TAG = "BtmapMtkBands"
    private const val DESCRIPTOR = "com.mediatek.internal.telephony.IMtkTelephonyEx"
    private const val OUT_SIZE = 2048

    private val code: Int by lazy {
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions("Lcom/mediatek/internal/telephony/IMtkTelephonyEx")
            Class.forName("$DESCRIPTOR\$Stub").getDeclaredField("TRANSACTION_invokeOemRilRequestRawBySlot")
                .apply { isAccessible = true }.getInt(null)
        }.getOrDefault(41)
    }

    /** Whether this phone has the MediaTek passthrough service at all. */
    val hasService: Boolean
        get() = runCatching { SystemServiceHelper.getSystemService("phoneEx") != null }.getOrDefault(false)

    /** Sends one AT command to the modem of [slot]; returns its reply text, or null on failure. */
    suspend fun at(slot: Int, command: String): String? = withContext(Dispatchers.IO) {
        val service = runCatching { SystemServiceHelper.getSystemService("phoneEx") }.getOrNull() ?: return@withContext null
        val binder = ShizukuBinderWrapper(service)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(slot)
            data.writeByteArray((command + "\u0000").toByteArray(Charsets.US_ASCII))
            data.writeInt(OUT_SIZE)
            binder.transact(code, data, reply, 0)
            reply.readException()
            val length = reply.readInt()
            val out = ByteArray(OUT_SIZE)
            reply.readByteArray(out)
            if (length <= 0) {
                Log.w(TAG, "$command -> $length")
                null
            } else String(out, 0, length.coerceAtMost(OUT_SIZE), Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
        } catch (e: Throwable) {
            Log.e(TAG, "$command failed", e)
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private val QUOTED = Regex("\"([0-9A-Fa-f]+)\"")

    private fun parse(reply: String?): MtkBandConfig? {
        val line = reply?.lineSequence()?.firstOrNull { it.contains("+EPBSEH") } ?: return null
        val v = QUOTED.findAll(line).map { it.groupValues[1] }.toList()
        if (v.size < 4) return null
        return MtkBandConfig(v[0], v[1], v[2], v[3])
    }

    /** Bands the modem supports (`AT+EPBSEH=?`). */
    suspend fun supported(slot: Int): MtkBandConfig? = parse(at(slot, "AT+EPBSEH=?"))

    /** Bands the modem is allowed to use now (`AT+EPBSEH?`). */
    suspend fun current(slot: Int): MtkBandConfig? = parse(at(slot, "AT+EPBSEH?"))

    /** Writes the four masks; returns true when the modem answered OK. */
    suspend fun write(slot: Int, config: MtkBandConfig): Boolean {
        val reply = at(slot, "AT+EPBSEH=\"${config.gsm}\",\"${config.umts}\",\"${config.lte}\",\"${config.nr}\"")
        Log.i(TAG, "write slot=$slot lte=${config.lte} nr=${config.nr} -> $reply")
        return reply?.contains("OK") == true
    }

    /**
     * Allows only [selection] (empty set = every supported band of that technology). Bands the modem
     * does not support are ignored. GSM/UMTS stay as they are.
     */
    suspend fun apply(slot: Int, selection: BandSelection): Boolean {
        val supported = supported(slot) ?: return false
        val current = current(slot) ?: supported
        val lte = if (selection.lte.isEmpty()) supported.lte else mask(selection.lte intersect supported.lteBands, supported.lte.length)
        val nr = if (selection.nr.isEmpty()) supported.nr else mask(selection.nr intersect supported.nrBands, supported.nr.length)
        if (bands(lte).isEmpty() || bands(nr).isEmpty()) return false
        return write(slot, MtkBandConfig(current.gsm, current.umts, lte, nr))
    }

    /** Factory state: every band the modem supports, on every technology. */
    suspend fun reset(slot: Int): Boolean {
        val supported = supported(slot) ?: return false
        return write(slot, supported)
    }

    /** "080E00DF000081A000000002" → {1,2,3,4,5,7,8,18,19,20,28,38,40,41,48,66}. */
    fun bands(hex: String): Set<Int> {
        val out = mutableSetOf<Int>()
        hex.chunked(8).forEachIndexed { word, chunk ->
            val value = chunk.toLongOrNull(16) ?: return@forEachIndexed
            for (bit in 0 until 32) if (value and (1L shl bit) != 0L) out += word * 32 + bit + 1
        }
        return out
    }

    /** Inverse of [bands], with the same length as the modem's own mask. */
    fun mask(bands: Set<Int>, length: Int): String {
        val words = LongArray((length + 7) / 8)
        bands.forEach { b ->
            val i = (b - 1) / 32
            if (i in words.indices) words[i] = words[i] or (1L shl ((b - 1) % 32))
        }
        return words.joinToString("") { "%08X".format(it) }.take(length)
    }
}
