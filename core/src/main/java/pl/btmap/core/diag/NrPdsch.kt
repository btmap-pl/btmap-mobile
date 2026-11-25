package pl.btmap.core.diag

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * NR5G MAC PDSCH Status (log 0xB887), format 3.13 as sent by the Snapdragon 8 Elite modem.
 * No public description exists; the layout was worked out from a 5G NSA recording on a Galaxy S25
 * and checked against the 3GPP TS 38.214 TBS calculation, which every data record matches:
 *
 * header {version u32 (minor u16, major u16), 3 bytes, records u8}, then 44-byte records of
 * {slot u16, frame u16, 4 bytes, packed frame/slot u32, carrier u32, TB u32, MCS/RB u32,
 * flags u32, 16 bytes}, where
 * - TB u32 at +16: TBS in bytes in bits 5–18;
 * - +19: MCS << 2; +20: resource blocks; +21: HARQ process << 3;
 * - +23 bits 5–6: layers − 1;
 * - +24 bit 0: CRC passed, bit 1: new transmission, bit 2: NDI; +25: redundancy version.
 *
 * MCS follows the 256QAM table (TS 38.214 table 5.1.3.1-2), which the TBS check confirmed.
 */
object NrPdsch {
    const val CODE = 0xB887
    private const val MAJOR = 3
    private const val HEADER = 8
    private const val RECORD = 44

    fun decode(payload: ByteArray): List<PdschBlock> {
        if (payload.size < HEADER) return emptyList()
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        if ((b.getShort(2).toInt() and 0xFFFF) != MAJOR) return emptyList()
        val count = minOf(payload[7].toInt() and 0xFF, (payload.size - HEADER) / RECORD)
        val out = ArrayList<PdschBlock>(count)
        for (i in 0 until count) {
            val r = HEADER + i * RECORD
            val rbs = payload[r + 20].toInt() and 0xFF
            if (rbs == 0) continue
            val slot = b.getShort(r).toInt() and 0xFFFF
            val frame = b.getShort(r + 2).toInt() and 0x3FF
            val flags = payload[r + 24].toInt() and 0xFF
            val first = flags and 2 != 0
            val mcs = (payload[r + 19].toInt() and 0xFF) ushr 2
            out += PdschBlock(
                timeMs = frame * 10 + slot,
                layers = ((payload[r + 23].toInt() ushr 5) and 3) + 1,
                harq = ((payload[r + 21].toInt() and 0xFF) ushr 3) and 0xF,
                rv = if (first) 0 else (payload[r + 25].toInt() and 3).coerceAtLeast(1),
                crcOk = flags and 1 != 0,
                tbsBytes = (b.getInt(r + 16) ushr 5) and 0x3FFF,
                mcs = mcs,
                rbs = rbs,
                modulation = modulation(mcs),
            )
        }
        return out
    }

    /** Bits per symbol for an MCS of the 256QAM table; 28–31 (retransmissions only) count as 256QAM. */
    fun modulation(mcs: Int): Int = when {
        mcs <= 4 -> 2
        mcs <= 10 -> 4
        mcs <= 19 -> 6
        else -> 8
    }
}
