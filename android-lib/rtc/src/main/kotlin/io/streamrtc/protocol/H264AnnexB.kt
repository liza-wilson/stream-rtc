package io.streamrtc.protocol

/**
 * One H.264 NAL unit found in an Annex-B byte stream.
 *
 * @property type NAL type without the forbidden-zero bit (`first & 0x1f`).
 * @property offset Byte index of the first NAL payload byte, i.e. just past
 *   the start code it belongs to.
 * @property length Number of NAL payload bytes before the next start code.
 */
data class NalUnit(val type: Int, val offset: Int, val length: Int)

/**
 * The SPS/PPS pair a decoder needs before it can render a keyframe: each is
 * the start code followed by the NAL payload bytes (for example the 4-byte
 * `00 00 00 01` start code of an IDR pair).
 */
class H264Csd(val sps: ByteArray, val pps: ByteArray)

/**
 * Annex-B H.264 parsing: NAL unit splitting, SPS/PPS extraction and keyframe
 * detection. The relay/encoder emit baseline streams whose keyframes are IDR
 * NALs (type 5), so an IDR anywhere in the access unit marks a keyframe.
 */
object H264AnnexB {
    private const val IDR = 5
    private const val SPS = 7
    private const val PPS = 8

    private const val ZERO: Byte = 0.toByte()
    private const val ONE: Byte = 1.toByte()

    /** Splits [data] into NAL units. Empty when no start code is present. */
    fun nalUnits(data: ByteArray): List<NalUnit> {
        val codes = findStartCodes(data)
        return codes.mapIndexed { index, code ->
            val start = code.first + code.second
            val end = codes.getOrNull(index + 1)?.first ?: data.size
            NalUnit(
                type = data[start].toInt() and 0x1f,
                offset = start,
                length = (end - start).coerceAtLeast(0),
            )
        }
    }

    /** The first SPS/PPS pair, or null when either is missing. */
    fun csd(data: ByteArray): H264Csd? {
        val units = nalUnits(data)
        val sps = units.firstOrNull { it.type == SPS } ?: return null
        val pps = units.firstOrNull { it.type == PPS } ?: return null
        return H264Csd(
            sps = startCodePlusNal(data, sps),
            pps = startCodePlusNal(data, pps),
        )
    }

    /** True when any NAL in [data] is an IDR (type 5). */
    fun isKeyframe(data: ByteArray): Boolean = nalUnits(data).any { it.type == IDR }

    private fun startCodePlusNal(data: ByteArray, unit: NalUnit): ByteArray {
        val terminator = unit.offset - 1
        var zeros = 0
        var scan = terminator - 1
        while (scan >= 0 && data[scan] == ZERO) {
            zeros++
            scan--
        }
        val start = terminator - zeros
        return data.copyOfRange(start, unit.offset + unit.length)
    }

    /**
     * Scans for Annex-B start codes, preferring the longest (4-byte over
     * 3-byte) at the earliest position, and returns (offset, length) pairs.
     */
    private fun findStartCodes(data: ByteArray): List<Pair<Int, Int>> {
        val codes = mutableListOf<Pair<Int, Int>>()
        var index = 0
        while (index + 2 < data.size) {
            if (data[index] == ZERO && data[index + 1] == ZERO && data[index + 2] == ONE) {
                val length = if (index > 0 && data[index - 1] == ZERO) 4 else 3
                val offset = if (length == 4) index - 1 else index
                codes.add(Pair(offset, length))
                index = offset + length
            } else {
                index++
            }
        }
        return codes
    }
}
