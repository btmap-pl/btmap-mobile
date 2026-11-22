package pl.btmap.core.diag

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Qualcomm DIAG protocol: HDLC-like framing (CRC-16/X.25, 0x7D escaping, 0x7E terminator) and
 * the few commands BTMap needs to switch modem logging on and off.
 */
object Hdlc {
    fun crc16(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in from until to) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8408 else crc ushr 1 }
        }
        return crc.inv() and 0xFFFF
    }

    /** Payload + CRC, escaped, terminated with 0x7E. */
    fun encode(payload: ByteArray): ByteArray {
        val crc = crc16(payload)
        val raw = payload + byteArrayOf((crc and 0xFF).toByte(), (crc ushr 8).toByte())
        val out = ByteArrayOutputStream(raw.size + 4)
        for (b in raw) {
            val v = b.toInt() and 0xFF
            if (v == 0x7E || v == 0x7D) { out.write(0x7D); out.write(v xor 0x20) } else out.write(v)
        }
        out.write(0x7E)
        return out.toByteArray()
    }

    /** Splits a byte stream into frames; returns payloads with a valid CRC (escaping removed). */
    class Decoder {
        private val current = ByteArrayOutputStream()
        private var escape = false
        var badFrames = 0; private set

        fun feed(bytes: ByteArray, length: Int = bytes.size, onFrame: (ByteArray) -> Unit) {
            for (i in 0 until length) {
                val v = bytes[i].toInt() and 0xFF
                when {
                    v == 0x7E -> {
                        val frame = current.toByteArray()
                        current.reset(); escape = false
                        if (frame.size >= 3) {
                            val body = frame.copyOfRange(0, frame.size - 2)
                            val crc = (frame[frame.size - 2].toInt() and 0xFF) or ((frame[frame.size - 1].toInt() and 0xFF) shl 8)
                            if (crc16(body) == crc) onFrame(body) else badFrames++
                        }
                    }
                    v == 0x7D -> escape = true
                    escape -> { current.write(v xor 0x20); escape = false }
                    else -> current.write(v)
                }
            }
        }
    }
}

object DiagCommands {
    const val LOG = 0x10
    const val LOG_CONFIG = 0x73
    private const val OP_DISABLE = 0
    private const val OP_RETRIEVE_ID_RANGES = 1
    private const val OP_SET_MASK = 3

    /** Equipment ID of log codes 0xB000–0xBFFF (LTE and NR5G). */
    const val EQUIP_LTE_NR = 0x0B

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    val disableAll: ByteArray get() = le(8).put(LOG_CONFIG.toByte()).put(0).put(0).put(0).putInt(OP_DISABLE).array()
    val retrieveIdRanges: ByteArray get() = le(8).put(LOG_CONFIG.toByte()).put(0).put(0).put(0).putInt(OP_RETRIEVE_ID_RANGES).array()

    /** Enables exactly [codes] (all within one equipment ID), whose highest item is [lastItem]. */
    fun setMask(equipId: Int, lastItem: Int, codes: Collection<Int>): ByteArray {
        val items = lastItem + 1
        val mask = ByteArray((items + 7) / 8)
        for (code in codes) {
            val item = code and 0xFFF
            if ((code ushr 12) == equipId && item < items) mask[item / 8] = (mask[item / 8].toInt() or (1 shl (item % 8))).toByte()
        }
        return le(16 + mask.size).put(LOG_CONFIG.toByte()).put(0).put(0).put(0)
            .putInt(OP_SET_MASK).putInt(equipId).putInt(items).put(mask).array()
    }

    /** Last item per equipment ID from a RETRIEVE_ID_RANGES reply, or null if it is not one. */
    fun parseIdRanges(reply: ByteArray): IntArray? {
        if (reply.size < 12 + 16 * 4 || (reply[0].toInt() and 0xFF) != LOG_CONFIG) return null
        val b = ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(4) != OP_RETRIEVE_ID_RANGES) return null
        return IntArray(16) { b.getInt(12 + it * 4) }
    }

    /** Log code of a DIAG log packet (command 0x10), or null. */
    fun logCode(frame: ByteArray): Int? {
        if (frame.size < 8 || (frame[0].toInt() and 0xFF) != LOG) return null
        return (frame[6].toInt() and 0xFF) or ((frame[7].toInt() and 0xFF) shl 8)
    }
}

/** Log codes BTMap records: what Network Signal Guru-style screens are built from. */
object DiagLogCodes {
    val NAMES: Map<Int, String> = linkedMapOf(
        0xB0C0 to "LTE RRC OTA",
        0xB063 to "LTE MAC DL TB",
        0xB064 to "LTE MAC UL TB",
        0xB139 to "LTE PHY PUSCH Tx",
        0xB14D to "LTE PHY PUCCH CSF (CQI/RI)",
        0xB173 to "LTE PHY PDSCH stat (MCS/mod/TBS/CRC)",
        0xB193 to "LTE ML1 serving cell meas",
        0xB821 to "NR5G RRC OTA",
        0xB883 to "NR5G MAC UL phys ch schedule",
        0xB887 to "NR5G MAC PDSCH status",
        0xB8A7 to "NR5G MAC CSF report",
        0xB97F to "NR5G ML1 searcher meas",
    )
}
