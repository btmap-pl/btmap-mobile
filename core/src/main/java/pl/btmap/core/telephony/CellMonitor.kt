package pl.btmap.core.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.CellInfo
import android.telephony.CellSignalStrengthNr
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.btmap.core.model.Cell
import pl.btmap.core.model.CellIdentity
import pl.btmap.core.model.CellRole
import pl.btmap.core.model.NetworkMode
import pl.btmap.core.model.NetworkSnapshot
import pl.btmap.core.model.Plmn
import pl.btmap.core.model.Rat
import pl.btmap.core.model.SimState
import pl.btmap.core.radio.CellMath
import pl.btmap.core.radio.Operators
import java.util.concurrent.Executors

/**
 * Live view of every active SIM: serving and neighbour cells, signal, network mode and carrier
 * aggregation. Cell info is pushed by the modem through [TelephonyCallback] and additionally
 * requested every [refreshIntervalMs], because many modems only report changes lazily.
 *
 * Requires READ_PHONE_STATE and ACCESS_FINE_LOCATION; without them the flow emits SIM states
 * carrying an [SimState.error].
 */
class CellMonitor(
    context: Context,
    private val refreshIntervalMs: Long = 2_000,
    val dictionary: CellDictionary = CellDictionary(),
) {
    private val appContext = context.applicationContext
    private val telephony = appContext.getSystemService(TelephonyManager::class.java)
    private val subscriptions = appContext.getSystemService(SubscriptionManager::class.java)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "btmap-cell-monitor") }

    fun hasPermissions(): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Emits a fresh [NetworkSnapshot] whenever any SIM changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun snapshots(): Flow<NetworkSnapshot> =
        activeSubscriptions()
            .flatMapLatest { subs ->
                if (subs.isEmpty()) flowOf(NetworkSnapshot(emptyList(), System.currentTimeMillis()))
                else combine(subs.map(::simStates)) { states ->
                    NetworkSnapshot(states.sortedBy { it.slotIndex }, System.currentTimeMillis())
                }
            }
            .conflate()

    @SuppressLint("MissingPermission")
    private fun activeSubscriptions(): Flow<List<SubscriptionInfo>> = callbackFlow {
        fun current(): List<SubscriptionInfo> =
            if (!hasPermissions()) emptyList()
            else runCatching { subscriptions.activeSubscriptionInfoList.orEmpty() }.getOrDefault(emptyList())

        val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                trySend(current())
            }
        }
        trySend(current())
        subscriptions.addOnSubscriptionsChangedListener(executor, listener)
        awaitClose { subscriptions.removeOnSubscriptionsChangedListener(listener) }
    }.distinctUntilChanged { a, b -> a.map { it.subscriptionId } == b.map { it.subscriptionId } }

    /** Raw inputs for one SIM, merged into a [SimState] on every change. */
    private class Inputs {
        var cellInfo: List<CellInfo> = emptyList()
        var signal: SignalStrength? = null
        var display: TelephonyDisplayInfo? = null
        var service: ServiceState? = null
        var error: String? = null
    }

    @SuppressLint("MissingPermission")
    private fun simStates(sub: SubscriptionInfo): Flow<SimState> = callbackFlow {
        val tm = telephony.createForSubscriptionId(sub.subscriptionId)
        val inputs = Inputs()
        val lock = Any()

        fun publish() {
            val state = synchronized(lock) { buildState(sub, tm, inputs) }
            trySend(state)
        }

        val callback = object : TelephonyCallback(),
            TelephonyCallback.CellInfoListener,
            TelephonyCallback.SignalStrengthsListener,
            TelephonyCallback.DisplayInfoListener,
            TelephonyCallback.ServiceStateListener {
            override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                synchronized(lock) { inputs.cellInfo = cellInfo.toList() }
                publish()
            }

            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                synchronized(lock) { inputs.signal = signalStrength }
                publish()
            }

            override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                synchronized(lock) { inputs.display = telephonyDisplayInfo }
                publish()
            }

            override fun onServiceStateChanged(serviceState: ServiceState) {
                synchronized(lock) { inputs.service = serviceState }
                publish()
            }
        }

        try {
            tm.registerTelephonyCallback(executor, callback)
        } catch (e: SecurityException) {
            synchronized(lock) { inputs.error = "Brak uprawnień: ${e.message}" }
            publish()
        }

        val poller = launch {
            while (isActive) {
                try {
                    tm.requestCellInfoUpdate(executor, object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                            synchronized(lock) {
                                inputs.cellInfo = cellInfo.toList()
                                inputs.error = null
                            }
                            publish()
                        }

                        override fun onError(errorCode: Int, detail: Throwable?) {
                            synchronized(lock) { inputs.error = "Modem: błąd $errorCode" }
                            publish()
                        }
                    })
                } catch (e: SecurityException) {
                    synchronized(lock) { inputs.error = "Brak uprawnień: ${e.message}" }
                    publish()
                }
                delay(refreshIntervalMs)
            }
        }

        awaitClose {
            poller.cancel()
            runCatching { tm.unregisterTelephonyCallback(callback) }
        }
    }.conflate()

    @SuppressLint("MissingPermission")
    private fun buildState(sub: SubscriptionInfo, tm: TelephonyManager, inputs: Inputs): SimState {
        val registeredPlmn = Plmn.parse(runCatching { tm.networkOperator }.getOrNull())
        val simPlmn = Plmn.of(sub.mccString, sub.mncString)

        val bandwidths = inputs.service?.cellBandwidths?.filter { it > 0 }.orEmpty()
        // ServiceState's first bandwidth is the PCell's; CellIdentity's value is often stale.
        val parsed = inputs.cellInfo.mapNotNull(CellInfoParser::parse).map { cell ->
            val bw = bandwidths.firstOrNull()
            if (cell.role == CellRole.PRIMARY && bw != null) cell.copy(identity = cell.identity.copy(bandwidthKhz = bw)) else cell
        }
        val plmn = registeredPlmn ?: simPlmn
        parsed.filter(Cell::isServing).forEach { dictionary.learn(plmn, it) }
        val enriched = parsed.map { if (it.isServing) it else dictionary.enrich(plmn, it) }
        val cells = withNsaLeg(enriched, inputs.signal, inputs.display, registeredPlmn)
            .sortedWith(compareBy<Cell>({ it.role.ordinal }, { -(it.signal.mainDbm ?: -200) }))

        val carriers = CarrierResolver.resolve(cells, bandwidths, plmn, dictionary)
        val hasNrLeg = cells.any { it.rat == Rat.NR && it.role != CellRole.NEIGHBOUR }
        val announced = networkMode(cells, inputs.display, bandwidths)
        // Android reports NR_NSA as soon as the anchor advertises EN-DC; only call it 5G with a live NR leg.
        val nrAvailable = !hasNrLeg && (announced == NetworkMode.NR_NSA || announced == NetworkMode.NR_NSA_MMWAVE)
        val mode = if (nrAvailable) (if (bandwidths.size > 1) NetworkMode.LTE_CA else NetworkMode.LTE) else announced

        return SimState(
            subscriptionId = sub.subscriptionId,
            slotIndex = sub.simSlotIndex,
            simName = sub.carrierName?.toString(),
            simPlmn = simPlmn,
            registeredPlmn = registeredPlmn,
            operator = Operators.byPlmn(registeredPlmn ?: simPlmn),
            networkOperatorName = runCatching { tm.networkOperatorName }.getOrNull()?.takeIf { it.isNotBlank() },
            mode = mode,
            nrAvailable = nrAvailable,
            isRoaming = inputs.service?.roaming ?: false,
            isDataSim = SubscriptionManager.getDefaultDataSubscriptionId() == sub.subscriptionId,
            cells = cells,
            carrierBandwidthsKhz = bandwidths,
            carriers = carriers,
            updatedAtMillis = System.currentTimeMillis(),
            error = inputs.error,
        )
    }

    /**
     * In EN-DC (5G NSA) most modems do not list the NR leg in CellInfo, but they do report its
     * signal through [SignalStrength]. Add it as a secondary NR cell so the UI can show SS-RSRP.
     */
    private fun withNsaLeg(
        cells: List<Cell>,
        signal: SignalStrength?,
        display: TelephonyDisplayInfo?,
        plmn: Plmn?,
    ): List<Cell> {
        if (cells.any { it.rat == Rat.NR && it.role != CellRole.NEIGHBOUR }) return cells
        val nrSignal = signal?.getCellSignalStrengths(CellSignalStrengthNr::class.java)?.firstOrNull()
            ?: return cells
        val parsed = CellInfoParser.nrSignal(nrSignal)
        if (parsed.ssRsrp == null && parsed.csiRsrp == null) return cells
        val nsa = display?.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
            display?.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED
        if (!nsa && cells.none { it.role == CellRole.PRIMARY && it.rat == Rat.LTE }) return cells
        // Best measured NR neighbour on the anchor's PLMN is usually the NSA leg; borrow its identity.
        val neighbour = cells.filter { it.rat == Rat.NR }.maxByOrNull { it.signal.ssRsrp ?: -200 }
        val leg = Cell(
            identity = neighbour?.identity ?: CellIdentity(
                rat = Rat.NR, plmn = plmn, cid = null, nodeId = null, localCellId = null,
                areaCode = null, physicalId = null, channel = null, band = null,
            ),
            signal = parsed,
            role = CellRole.SECONDARY,
            taDistanceMeters = CellMath.nrTaMeters(parsed.timingAdvanceMicros),
            timestampNanos = System.nanoTime(),
        )
        return cells.filterNot { it === neighbour } + leg
    }

    private fun networkMode(cells: List<Cell>, display: TelephonyDisplayInfo?, bandwidths: List<Int>): NetworkMode {
        val primary = cells.firstOrNull { it.role == CellRole.PRIMARY }
        when (display?.overrideNetworkType) {
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> return NetworkMode.NR_NSA
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED ->
                return if (primary?.rat == Rat.NR) NetworkMode.NR_SA else NetworkMode.NR_NSA_MMWAVE
        }
        return when (primary?.rat) {
            Rat.NR -> NetworkMode.NR_SA
            Rat.LTE -> if (bandwidths.size > 1 ||
                display?.overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA
            ) NetworkMode.LTE_CA else NetworkMode.LTE
            Rat.WCDMA -> NetworkMode.UMTS
            Rat.GSM -> NetworkMode.GSM
            null -> NetworkMode.NONE
        }
    }

    companion object {
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }
}
