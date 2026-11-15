package pl.btmap.core.telephony

import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellIdentityNr
import android.telephony.CellSignalStrengthNr
import pl.btmap.core.model.Cell
import pl.btmap.core.model.CellIdentity
import pl.btmap.core.model.CellRole
import pl.btmap.core.model.CellSignal
import pl.btmap.core.model.Plmn
import pl.btmap.core.model.Rat
import pl.btmap.core.radio.Bands
import pl.btmap.core.radio.CellMath

/** Converts framework [CellInfo] objects into the core [Cell] model. */
internal object CellInfoParser {

    private const val UNAVAILABLE = CellInfo.UNAVAILABLE
    private const val UNAVAILABLE_LONG = CellInfo.UNAVAILABLE_LONG

    private fun Int.orNull(): Int? = takeIf { it != UNAVAILABLE }
    private fun Long.orNull(): Long? = takeIf { it != UNAVAILABLE_LONG }

    /** Some modems (MIUI/MediaTek) report the field maximum instead of UNAVAILABLE for neighbours. */
    private fun Int.orNullBelow(max: Int): Int? = orNull()?.takeIf { it in 0 until max }
    private fun Long.orNullBelow(max: Long): Long? = orNull()?.takeIf { it in 0 until max }

    fun parse(info: CellInfo): Cell? = when (info) {
        is CellInfoLte -> lte(info)
        is CellInfoNr -> nr(info)
        is CellInfoWcdma -> wcdma(info)
        is CellInfoGsm -> gsm(info)
        else -> null // CDMA / TD-SCDMA are not used in our region.
    }

    private fun role(info: CellInfo): CellRole = when (info.cellConnectionStatus) {
        CellInfo.CONNECTION_PRIMARY_SERVING -> CellRole.PRIMARY
        CellInfo.CONNECTION_SECONDARY_SERVING -> CellRole.SECONDARY
        else -> if (info.isRegistered) CellRole.PRIMARY else CellRole.NEIGHBOUR
    }

    private fun lte(info: CellInfoLte): Cell {
        val id = info.cellIdentity
        val s = info.cellSignalStrength
        val eci = id.ci.orNullBelow(268_435_455)?.toLong()
        val split = eci?.let(CellMath::splitEci)
        val earfcn = id.earfcn.orNull()
        val reportedBands = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) id.bands else null
        val ta = s.timingAdvance.orNullBelow(CellMath.MAX_LTE_TA)
        return Cell(
            identity = CellIdentity(
                rat = Rat.LTE,
                plmn = Plmn.of(id.mccString, id.mncString),
                cid = eci,
                nodeId = split?.first,
                localCellId = split?.second,
                areaCode = id.tac.orNullBelow(16_777_215)?.takeIf { it != 65_535 },
                physicalId = id.pci.orNull(),
                channel = earfcn,
                band = Bands.lte(earfcn, reportedBands),
                bandwidthKhz = id.bandwidth.orNull()?.takeIf { it > 0 },
                operatorAlpha = id.operatorAlphaLong?.toString(),
            ),
            signal = CellSignal(
                rssi = s.rssi.orNull(),
                rsrp = s.rsrp.orNull(),
                rsrq = s.rsrq.orNull(),
                snr = s.rssnr.orNull(),
                cqi = s.cqi.orNull(),
                timingAdvance = ta,
                level = s.level,
            ),
            role = role(info),
            taDistanceMeters = CellMath.lteTaMeters(ta),
            timestampNanos = info.timestampMillis * 1_000_000,
        )
    }

    private fun nr(info: CellInfoNr): Cell {
        val id = info.cellIdentity as CellIdentityNr
        val s = info.cellSignalStrength as CellSignalStrengthNr
        val nci = id.nci.orNullBelow(68_719_476_735L)
        val split = nci?.let(CellMath::splitNci)
        val arfcn = id.nrarfcn.orNull()
        val reportedBands = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) id.bands else null
        val taMicros = if (Build.VERSION.SDK_INT >= 34) s.timingAdvanceMicros.orNull() else null
        return Cell(
            identity = CellIdentity(
                rat = Rat.NR,
                plmn = Plmn.of(id.mccString, id.mncString),
                cid = nci,
                nodeId = split?.first,
                localCellId = split?.second,
                areaCode = id.tac.orNullBelow(16_777_215)?.takeIf { it != 65_535 },
                physicalId = id.pci.orNull(),
                channel = arfcn,
                band = Bands.nr(arfcn, reportedBands),
                operatorAlpha = id.operatorAlphaLong?.toString(),
            ),
            signal = nrSignal(s, taMicros),
            role = role(info),
            taDistanceMeters = CellMath.nrTaMeters(taMicros),
            timestampNanos = info.timestampMillis * 1_000_000,
        )
    }

    fun nrSignal(s: CellSignalStrengthNr, taMicros: Int? = null) = CellSignal(
        ssRsrp = s.ssRsrp.orNull(),
        ssRsrq = s.ssRsrq.orNull(),
        ssSinr = s.ssSinr.orNull(),
        csiRsrp = s.csiRsrp.orNull(),
        csiRsrq = s.csiRsrq.orNull(),
        csiSinr = s.csiSinr.orNull(),
        timingAdvanceMicros = taMicros,
        level = s.level,
    )

    private fun wcdma(info: CellInfoWcdma): Cell {
        val id = info.cellIdentity
        val s = info.cellSignalStrength
        val ci = id.cid.orNullBelow(268_435_455)?.toLong()
        val split = ci?.let(CellMath::splitUmtsCi)
        val uarfcn = id.uarfcn.orNull()
        return Cell(
            identity = CellIdentity(
                rat = Rat.WCDMA,
                plmn = Plmn.of(id.mccString, id.mncString),
                cid = ci,
                nodeId = split?.first,
                localCellId = split?.second,
                areaCode = id.lac.orNullBelow(65_535),
                physicalId = id.psc.orNull(),
                channel = uarfcn,
                band = Bands.umts(uarfcn),
                operatorAlpha = id.operatorAlphaLong?.toString(),
            ),
            signal = CellSignal(
                rscp = s.dbm.orNull(),
                ecno = s.ecNo.orNull(),
                level = s.level,
            ),
            role = role(info),
            taDistanceMeters = null,
            timestampNanos = info.timestampMillis * 1_000_000,
        )
    }

    private fun gsm(info: CellInfoGsm): Cell {
        val id = info.cellIdentity
        val s = info.cellSignalStrength
        val arfcn = id.arfcn.orNull()
        val ta = s.timingAdvance.orNull()
        return Cell(
            identity = CellIdentity(
                rat = Rat.GSM,
                plmn = Plmn.of(id.mccString, id.mncString),
                cid = id.cid.orNullBelow(65_535)?.toLong(),
                nodeId = null,
                localCellId = null,
                areaCode = id.lac.orNullBelow(65_535),
                physicalId = id.bsic.orNull(),
                channel = arfcn,
                band = Bands.gsm(arfcn),
                operatorAlpha = id.operatorAlphaLong?.toString(),
            ),
            signal = CellSignal(
                rssi = s.dbm.orNull(),
                ber = s.bitErrorRate.orNull(),
                timingAdvance = ta,
                level = s.level,
            ),
            role = role(info),
            taDistanceMeters = CellMath.gsmTaMeters(ta),
            timestampNanos = info.timestampMillis * 1_000_000,
        )
    }
}
