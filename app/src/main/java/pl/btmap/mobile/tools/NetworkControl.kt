package pl.btmap.mobile.tools

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import pl.btmap.core.BtmapCore
import pl.btmap.core.model.Rat

/** Which radio technologies a SIM may use (Android's allowed network types). */
enum class NetworkModePreset(val label: String, val description: String, val mask: Long) {
    AUTO("Auto", "Wszystko, co obsługuje telefon", -1),
    NR_LTE("5G + 4G", "Bez 3G i 2G", Types.LTE or Types.LTE_CA or Types.NR),
    LTE("Tylko 4G", "Bez 5G, 3G i 2G", Types.LTE or Types.LTE_CA),
    NR("Tylko 5G (SA)", "Wymaga 5G SA w tym miejscu", Types.NR),
    NO_NR("4G + 3G + 2G", "Bez 5G", Types.LTE or Types.LTE_CA or Types.UMTS_ALL or Types.GSM_ALL),
    UMTS("Tylko 3G", "3G i 2G", Types.UMTS_ALL or Types.GSM_ALL),
    GSM("Tylko 2G", "GSM / EDGE", Types.GSM_ALL);
}

/** Bits of TelephonyManager.NETWORK_TYPE_BITMASK_* (1 << (networkType - 1)). */
private object Types {
    private fun bit(type: Int) = 1L shl (type - 1)
    val GSM_ALL = bit(16) or bit(1) or bit(2) // GSM, GPRS, EDGE
    val UMTS_ALL = bit(3) or bit(8) or bit(9) or bit(10) or bit(15) or bit(17) // UMTS, HSDPA, HSUPA, HSPA, HSPA+, TD-SCDMA
    val LTE = bit(13)
    val LTE_CA = bit(19)
    val NR = bit(20)

    private val NAMES = mapOf(
        "GPRS" to bit(1), "EDGE" to bit(2), "UMTS" to bit(3), "CDMA" to bit(4), "CDMA - EvDo rev. 0" to bit(5),
        "CDMA - EvDo rev. A" to bit(6), "CDMA - 1xRTT" to bit(7), "HSDPA" to bit(8), "HSUPA" to bit(9), "HSPA" to bit(10),
        "CDMA - EvDo rev. B" to bit(12), "LTE" to bit(13), "CDMA - eHRPD" to bit(14), "HSPA+" to bit(15), "GSM" to bit(16),
        "TD_SCDMA" to bit(17), "IWLAN" to bit(18), "LTE_CA" to bit(19), "NR" to bit(20),
    )

    /** Parses `cmd phone get-allowed-network-types-for-users` output ("GPRS|EDGE|...|NR"). */
    fun parse(text: String): Long? {
        val names = text.trim().lines().lastOrNull { it.contains('|') || NAMES.containsKey(it.trim()) } ?: return null
        return names.split('|').mapNotNull { NAMES[it.trim()] }.fold(0L) { acc, b -> acc or b }.takeIf { it != 0L }
    }
}

/** What the modem reports about bands for one SIM. */
data class ModemBands(
    /** True when bands are locked directly in a MediaTek modem (AT+EPBSEH). */
    val mediatek: Boolean,
    /** Supported bands from the modem; empty when the modem does not tell. */
    val supportedLte: Set<Int>,
    val supportedNr: Set<Int>,
    /** Current restriction read from the modem (MediaTek) — empty sets = all bands. */
    val current: BandSelection?,
)

/** How the modem treats band locking, learned by trying it once on this phone. */
enum class BandLockSupport { UNKNOWN, WORKS, IGNORED }

/** Outcome of a band lock attempt after the modem re-selected the network. */
sealed interface BandLockOutcome {
    /** The SIM is now on an allowed band. */
    data class Applied(val band: String) : BandLockOutcome
    /** The modem accepted the request but stayed on a blocked band. */
    data class Ignored(val band: String) : BandLockOutcome
    /** No LTE/NR service on the allowed bands here. */
    data object NoService : BandLockOutcome
    data object Restored : BandLockOutcome
    data class Failed(val message: String) : BandLockOutcome
}

/**
 * Network mode and band locking through Shizuku.
 *
 * - Network mode uses Android's allowed network types (`cmd phone set-allowed-network-types-for-users`),
 *   which every Android 12+ phone honours.
 * - Band locking uses `setSystemSelectionChannels`. Modems differ: some lock the band, others only
 *   limit background scans. After applying, the SIM is briefly switched to 2G/3G so the modem selects
 *   a cell again, and the band it lands on shows whether the lock works on this phone; that result is
 *   remembered.
 */
class NetworkControl(context: Context, private val shizuku: ShizukuBridge, private val core: BtmapCore) {
    private val prefs = context.applicationContext.getSharedPreferences("network_control", Context.MODE_PRIVATE)
    private val _support = MutableStateFlow(
        runCatching { BandLockSupport.valueOf(prefs.getString(supportKey(), null)!!) }.getOrDefault(BandLockSupport.UNKNOWN),
    )
    val bandLockSupport: StateFlow<BandLockSupport> = _support.asStateFlow()

    // v2: verdicts from the first (single-sample) check were unreliable and are not reused.
    private fun supportKey() = "support_v2_${Build.MANUFACTURER}_${Build.MODEL}_${Build.VERSION.SDK_INT}"

    private companion object {
        /** The SIM must stay on allowed bands this long before the lock counts as working. */
        const val STABLE_MS = 25_000L
    }

    /** Allowed network types now set for [slot], or null when unreadable. */
    suspend fun currentMask(slot: Int): Long? = withContext(Dispatchers.IO) {
        val (code, out) = shizuku.exec("cmd", "phone", "get-allowed-network-types-for-users", "-s", slot.toString())
        if (code != 0) null else Types.parse(out)
    }

    /** Remembers the phone's own setting the first time we change it, to restore "Auto". */
    private suspend fun originalMask(slot: Int): Long? {
        val key = "original_$slot"
        if (prefs.contains(key)) return prefs.getLong(key, 0)
        val mask = currentMask(slot) ?: return null
        prefs.edit().putLong(key, mask).apply()
        return mask
    }

    private suspend fun setMask(slot: Int, mask: Long): Boolean = withContext(Dispatchers.IO) {
        val (code, out) = shizuku.exec(
            "cmd", "phone", "set-allowed-network-types-for-users", "-s", slot.toString(), java.lang.Long.toBinaryString(mask),
        )
        code == 0 && out.contains("completed")
    }

    /** Sets the network mode of [slot]. Returns null on success or an error message. */
    suspend fun setMode(slot: Int, preset: NetworkModePreset): String? {
        val original = originalMask(slot) ?: return "Nie udało się odczytać ustawień sieci"
        // Leaving 5G SA on MediaTek: give the modem its LTE bands back first.
        restoreSaBands(slot)
        if (preset == NetworkModePreset.NR && MtkBandControl.supported(slot) != null) {
            forceSa(slot)?.let { return it }
            prefs.edit().putString("mode_$slot", preset.name).apply()
            return null
        }
        val mask = if (preset == NetworkModePreset.AUTO) original else preset.mask
        if (!setMask(slot, mask)) return "Telefon odrzucił zmianę trybu sieci"
        prefs.edit().putString("mode_$slot", preset.name).apply()
        if (preset == NetworkModePreset.AUTO) prefs.edit().remove("original_$slot").apply()
        return null
    }

    /**
     * 5G SA on MediaTek. The RIL turns an "NR only" request into LTE+NR, so instead LTE is kept out
     * at the band level: only 4G/5G are allowed and the modem may use just one LTE band nobody uses
     * in Poland (B66), which leaves 5G SA as the only way in. If the SIM does not register on NR,
     * everything is put back and the reason returned.
     */
    private suspend fun forceSa(slot: Int): String? {
        val supported = MtkBandControl.supported(slot) ?: return "Modem nie podał obsługiwanych pasm"
        val current = MtkBandControl.current(slot) ?: supported
        val unusedLte = (supported.lteBands - BandLock.POLISH_LTE.toSet()).maxOrNull() ?: return "Modem nie ma pasma LTE do wyłączenia"
        val previousMask = currentMask(slot) ?: return "Nie udało się odczytać ustawień sieci"
        prefs.edit().putString("sa_lte_$slot", current.lte).putLong("sa_mask_$slot", previousMask).apply()

        if (!setMask(slot, NetworkModePreset.NR_LTE.mask)) return "Telefon odrzucił zmianę trybu sieci"
        val saLte = MtkBandControl.mask(setOf(unusedLte), supported.lte.length)
        if (!MtkBandControl.write(slot, current.copy(lte = saLte))) {
            restoreSaBands(slot)
            return "Modem odrzucił ustawienie 5G SA"
        }
        val deadline = System.currentTimeMillis() + 40_000
        while (System.currentTimeMillis() < deadline) {
            delay(2_000)
            val pcell = core.state.value.snapshot.sims.firstOrNull { it.slotIndex == slot }?.primary
            if (pcell?.rat == Rat.NR) return null
        }
        restoreSaBands(slot)
        return "W tym miejscu nie ma 5G SA operatora — przywrócono poprzedni tryb"
    }

    /** Undoes [forceSa] if it is active on [slot]. */
    private suspend fun restoreSaBands(slot: Int) {
        val lte = prefs.getString("sa_lte_$slot", null) ?: return
        val mask = prefs.getLong("sa_mask_$slot", 0)
        MtkBandControl.current(slot)?.let { MtkBandControl.write(slot, it.copy(lte = lte)) }
        if (mask != 0L) setMask(slot, mask)
        prefs.edit().remove("sa_lte_$slot").remove("sa_mask_$slot").putString("mode_$slot", NetworkModePreset.AUTO.name).apply()
    }

    fun mode(slot: Int): NetworkModePreset =
        runCatching { NetworkModePreset.valueOf(prefs.getString("mode_$slot", null)!!) }.getOrDefault(NetworkModePreset.AUTO)

    /** Bands the modem of [slot] supports and allows now; MediaTek modems answer directly. */
    suspend fun modemBands(slot: Int): ModemBands {
        val supported = MtkBandControl.supported(slot)
        if (supported != null) {
            val current = MtkBandControl.current(slot)
            val sel = current?.let {
                BandSelection(
                    lte = if (it.lteBands.containsAll(supported.lteBands)) emptySet() else it.lteBands,
                    nr = if (it.nrBands.containsAll(supported.nrBands)) emptySet() else it.nrBands,
                )
            }
            return ModemBands(true, supported.lteBands, supported.nrBands, sel)
        }
        return ModemBands(false, emptySet(), emptySet(), null)
    }

    /**
     * Factory band settings for [slot]: every supported band on MediaTek, and no system-selection
     * restriction on any phone.
     */
    suspend fun resetBands(slot: Int, subId: Int): BandLockOutcome {
        val mtk = if (MtkBandControl.supported(slot) != null) MtkBandControl.reset(slot) else null
        runCatching { BandLock.apply(subId, BandSelection()) }
        return if (mtk == false) BandLockOutcome.Failed("Modem nie przyjął domyślnych pasm") else BandLockOutcome.Restored
    }

    /**
     * Applies [selection] to the SIM in [slot] / [subId]. MediaTek modems lock the bands directly;
     * elsewhere Android's system-selection channels are used. Either way the band the SIM ends up
     * on is checked.
     */
    suspend fun applyBands(slot: Int, subId: Int, selection: BandSelection): BandLockOutcome {
        if (MtkBandControl.supported(slot) != null) return applyMediatek(slot, selection)
        return applyAndroid(slot, subId, selection)
    }

    private suspend fun applyMediatek(slot: Int, selection: BandSelection): BandLockOutcome {
        if (!MtkBandControl.apply(slot, selection)) return BandLockOutcome.Failed("Modem odrzucił konfigurację pasm")
        if (selection.isUnrestricted) return BandLockOutcome.Restored
        // The modem moves on its own; wait until the SIM sits on an allowed band.
        val deadline = System.currentTimeMillis() + 45_000
        var label: String? = null
        while (System.currentTimeMillis() < deadline) {
            delay(1_000)
            val pcell = core.state.value.snapshot.sims.firstOrNull { it.slotIndex == slot }?.primary ?: continue
            val band = pcell.identity.band ?: continue
            if (pcell.rat != Rat.LTE && pcell.rat != Rat.NR) continue
            val allowed = if (pcell.rat == Rat.LTE) selection.lte else selection.nr
            label = (if (pcell.rat == Rat.NR) "n" else "B") + band.number + " · " + band.label
            if (allowed.isEmpty() || band.number in allowed) {
                remember(BandLockSupport.WORKS)
                return BandLockOutcome.Applied(label)
            }
        }
        return if (label == null) BandLockOutcome.NoService else BandLockOutcome.Applied("$label (modem jeszcze przełącza)")
    }

    private suspend fun applyAndroid(slot: Int, subId: Int, selection: BandSelection): BandLockOutcome {
        val sent = runCatching { BandLock.apply(subId, selection) }
        if (sent.isFailure) return BandLockOutcome.Failed(sent.exceptionOrNull()?.message ?: "Błąd Shizuku")
        if (sent.getOrNull() == false) return BandLockOutcome.Failed("Modem odrzucił konfigurację")
        if (selection.isUnrestricted) {
            reselect(slot)
            return BandLockOutcome.Restored
        }
        if (!reselect(slot)) return BandLockOutcome.Failed("Nie udało się wymusić ponownego wyboru sieci")

        // Watch the SIM after it is back on LTE/NR. Some modems land on an allowed band first and
        // return to a blocked one moments later (the lock only limited the scan), so the verdict
        // needs the SIM to stay on allowed bands for a while.
        var lastLabel: String? = null
        var firstSeenAt = 0L
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            delay(1_000)
            val pcell = core.state.value.snapshot.sims.firstOrNull { it.slotIndex == slot }?.primary ?: continue
            val band = pcell.identity.band ?: continue
            if (pcell.rat != Rat.LTE && pcell.rat != Rat.NR) continue
            val allowed = if (pcell.rat == Rat.LTE) selection.lte else selection.nr
            val label = (if (pcell.rat == Rat.NR) "n" else "B") + band.number + " · " + band.label
            if (allowed.isNotEmpty() && band.number !in allowed) {
                remember(BandLockSupport.IGNORED)
                return BandLockOutcome.Ignored(label)
            }
            if (firstSeenAt == 0L) firstSeenAt = System.currentTimeMillis()
            lastLabel = label
            if (System.currentTimeMillis() - firstSeenAt >= STABLE_MS) {
                remember(BandLockSupport.WORKS)
                return BandLockOutcome.Applied(label)
            }
        }
        return lastLabel?.let { BandLockOutcome.Applied(it) } ?: BandLockOutcome.NoService
    }

    /**
     * Briefly limits the SIM to 2G/3G and restores its mode, which makes the modem search for a
     * cell again under the new band rules (a few seconds without LTE/5G on that SIM).
     */
    private suspend fun reselect(slot: Int): Boolean {
        val restore = currentMask(slot) ?: return false
        if (!setMask(slot, NetworkModePreset.UMTS.mask)) return false
        delay(6_000)
        return setMask(slot, restore)
    }

    private fun remember(support: BandLockSupport) {
        _support.value = support
        prefs.edit().putString(supportKey(), support.name).apply()
    }
}
