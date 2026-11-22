package pl.btmap.core.diag

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One downlink transport block from the modem's PDSCH statistics. */
data class PdschBlock(
    /** System frame × 10 + subframe, in milliseconds (wraps every 10.24 s). */
    val timeMs: Int,
    val layers: Int,
    val harq: Int,
    /** Redundancy version: 0 = first transmission. */
    val rv: Int,
    val crcOk: Boolean,
    /** Transport block size in bytes. */
    val tbsBytes: Int,
    val mcs: Int,
    val rbs: Int,
    /** Bits per symbol: 2 QPSK, 4 16QAM, 6 64QAM, 8 256QAM. */
    val modulation: Int,
)

/**
 * LTE PHY PDSCH Stat Indication (log 0xB173). Format version 50, as sent by the Snapdragon 8 Elite
 * modem (worked out from a recording; consistent with MobileInsight's v40 layout plus a second
 * transport block slot): header {version, records, 2 reserved}, then 40-byte records of
 * {sfn/subframe u16, layers, blocks, 8 more bytes, 2 × 12-byte transport blocks, 4 bytes}.
 * A transport block is {harq:4 rv:2 ndi:1 crc:1, rnti/tb index, 2, TBS u16, MCS, RBs, Qm, 3}.
 */
object LtePdsch {
    const val CODE = 0xB173
    private const val RECORD = 40

    fun decode(payload: ByteArray): List<PdschBlock> {
        if (payload.size < 4) return emptyList()
        val version = payload[0].toInt() and 0xFF
        if (version != 50) return emptyList()
        val count = payload[1].toInt() and 0xFF
        if (4 + count * RECORD > payload.size) return emptyList()
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<PdschBlock>(count * 2)
        for (i in 0 until count) {
            val r = 4 + i * RECORD
            val sf = b.getShort(r).toInt() and 0xFFFF
            val time = ((sf ushr 4) and 0x3FF) * 10 + (sf and 0xF)
            val layers = payload[r + 2].toInt() and 0xFF
            for (slot in 0 until 2) {
                val o = r + 12 + slot * 12
                if ((o until o + 12).all { payload[it].toInt() == 0 }) continue
                val h = payload[o].toInt() and 0xFF
                out += PdschBlock(
                    timeMs = time,
                    layers = layers,
                    harq = h and 0xF,
                    rv = (h ushr 4) and 3,
                    crcOk = (h ushr 7) and 1 == 1,
                    tbsBytes = b.getShort(o + 4).toInt() and 0xFFFF,
                    mcs = payload[o + 6].toInt() and 0xFF,
                    rbs = payload[o + 7].toInt() and 0xFF,
                    modulation = payload[o + 8].toInt() and 0xFF,
                )
            }
        }
        return out
    }
}

/** Downlink statistics over a sliding window, for a live screen. */
data class DownlinkStats(
    val mbps: Double,
    /** Share of first transmissions that failed CRC. */
    val bler: Double,
    val mcsAvg: Double,
    val mcsLast: Int,
    val rbsAvg: Double,
    val rbsMax: Int,
    /** Share of blocks per modulation: 2, 4, 6, 8 bits per symbol. */
    val modulation: Map<Int, Double>,
    /** Share of subframes with two layers (MIMO rank 2). */
    val rank2: Double,
    val retransmissions: Double,
    val blocksPerSecond: Double,
)

/** Keeps the last [windowMs] of blocks (by arrival time) and summarises them. */
class DownlinkWindow(private val windowMs: Long = 1_000) {
    private data class Entry(val at: Long, val block: PdschBlock)
    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun add(blocks: List<PdschBlock>, now: Long = System.currentTimeMillis()) {
        blocks.forEach { entries.addLast(Entry(now, it)) }
        trim(now)
    }

    @Synchronized
    fun stats(now: Long = System.currentTimeMillis()): DownlinkStats? {
        trim(now)
        if (entries.isEmpty()) return null
        val blocks = entries.map { it.block }
        val first = blocks.filter { it.rv == 0 }
        val seconds = windowMs / 1000.0
        val mods = blocks.groupingBy { it.modulation }.eachCount()
        return DownlinkStats(
            mbps = blocks.filter { it.crcOk }.sumOf { it.tbsBytes.toLong() * 8 } / seconds / 1_000_000.0,
            bler = if (first.isEmpty()) 0.0 else first.count { !it.crcOk }.toDouble() / first.size,
            mcsAvg = blocks.filter { it.rv == 0 }.map { it.mcs }.average().takeIf { !it.isNaN() } ?: 0.0,
            mcsLast = blocks.last { it.rv == 0 || it == blocks.last() }.mcs,
            rbsAvg = blocks.map { it.rbs }.average(),
            rbsMax = blocks.maxOf { it.rbs },
            modulation = listOf(2, 4, 6, 8).associateWith { (mods[it] ?: 0).toDouble() / blocks.size },
            rank2 = blocks.count { it.layers >= 2 }.toDouble() / blocks.size,
            retransmissions = blocks.count { it.rv != 0 }.toDouble() / blocks.size,
            blocksPerSecond = blocks.size / seconds,
        )
    }

    private fun trim(now: Long) {
        while (entries.isNotEmpty() && now - entries.first().at > windowMs) entries.removeFirst()
    }
}
