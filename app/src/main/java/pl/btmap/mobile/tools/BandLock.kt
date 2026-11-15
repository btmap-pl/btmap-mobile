package pl.btmap.mobile.tools

import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import android.telephony.AccessNetworkConstants
import android.telephony.RadioAccessSpecifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/** Bands to allow, per technology; an empty set means "no restriction" for that technology. */
data class BandSelection(val lte: Set<Int> = emptySet(), val nr: Set<Int> = emptySet()) {
    val isUnrestricted: Boolean get() = lte.isEmpty() && nr.isEmpty()
}

/**
 * Restricts which bands the modem may use, through Android's own
 * `ITelephony.setSystemSelectionChannels` — the call is made with Shizuku's (shell) identity, which
 * holds MODIFY_PHONE_STATE. Verified on the POCO X6 Pro: the MediaTek RIL handles
 * SET_SYSTEM_SELECTION_CHANNELS. An empty selection restores all bands.
 *
 * The restriction lives in the modem session; after a reboot or modem restart it is gone until
 * applied again.
 */
object BandLock {

    private const val TAG = "BtmapBandLock"
    private const val TELEPHONY = "com.android.internal.telephony.ITelephony"
    private const val BOOLEAN_CONSUMER = "com.android.internal.telephony.IBooleanConsumer"

    /** Transaction code differs between Android versions; read it, fall back to Android 16's value. */
    private val setCode: Int by lazy {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) HiddenApiBypass.addHiddenApiExemptions("Lcom/android/internal/telephony/ITelephony")
            Class.forName("$TELEPHONY\$Stub").getDeclaredField("TRANSACTION_setSystemSelectionChannels")
                .apply { isAccessible = true }.getInt(null)
        }.getOrDefault(273)
    }

    fun specifiers(selection: BandSelection): List<RadioAccessSpecifier> = buildList {
        if (selection.lte.isNotEmpty()) {
            add(RadioAccessSpecifier(AccessNetworkConstants.AccessNetworkType.EUTRAN, selection.lte.sorted().toIntArray(), null))
        }
        if (selection.nr.isNotEmpty()) {
            add(RadioAccessSpecifier(AccessNetworkConstants.AccessNetworkType.NGRAN, selection.nr.sorted().toIntArray(), null))
        }
    }

    /**
     * Applies [selection] to the SIM with subscription [subId]. Returns true when the modem
     * confirmed it, false when it refused, null when no answer came (still sent).
     */
    suspend fun apply(subId: Int, selection: BandSelection): Boolean? = withContext(Dispatchers.IO) {
        val result = CompletableDeferred<Boolean>()
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == FIRST_CALL_TRANSACTION) {
                    runCatching { data.enforceInterface(BOOLEAN_CONSUMER) }
                    result.complete(data.readInt() != 0)
                    return true
                }
                return super.onTransact(code, data, reply, flags)
            }
        }
        val binder: IBinder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("phone"))
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            Log.i(TAG, "setSystemSelectionChannels code=$setCode subId=$subId selection=$selection")
            data.writeInterfaceToken(TELEPHONY)
            data.writeTypedList(specifiers(selection))
            data.writeInt(subId)
            data.writeStrongBinder(callback)
            val sent = binder.transact(setCode, data, reply, 0)
            Log.i(TAG, "transact returned $sent")
            reply.readException()
        } catch (e: Throwable) {
            Log.e(TAG, "band lock failed", e)
            throw e
        } finally {
            data.recycle()
            reply.recycle()
        }
        withTimeoutOrNull(5_000) { result.await() }
    }

    /** Bands offered in the picker: everything Polish networks use. */
    val POLISH_LTE = listOf(1, 3, 7, 8, 20, 28, 32, 38)
    val POLISH_NR = listOf(1, 3, 7, 8, 20, 28, 38, 41, 75, 77, 78)

    /** Downlink frequency label (MHz) of a band, shared by LTE and NR numbering. */
    private fun mhz(band: Int): String? = when (band) {
        1 -> "2100"; 2 -> "1900"; 3 -> "1800"; 4 -> "1700/2100"; 5 -> "850"; 7 -> "2600"; 8 -> "900"
        12, 13, 14, 17 -> "700"; 18, 19, 20, 26 -> "800"; 25 -> "1900"; 28 -> "700"; 29 -> "700 SDL"
        30 -> "2300"; 32 -> "1500 SDL"; 34 -> "2000 TDD"; 38 -> "2600 TDD"; 39 -> "1900 TDD"; 40 -> "2300 TDD"
        41 -> "2500 TDD"; 42 -> "3500 TDD"; 43 -> "3700 TDD"; 46 -> "5200 TDD"; 48 -> "3600 TDD"; 66 -> "1700/2100"
        71 -> "600"; 75 -> "1500 SDL"; 77 -> "3700"; 78 -> "3500"; 79 -> "4700"
        else -> null
    }

    fun lteLabel(band: Int) = mhz(band) ?: "B$band"

    fun nrLabel(band: Int) = mhz(band) ?: "n$band"
}
