package io.streamrtc.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A media frame as sent by a client to the relay. */
data class ClientFrame(
    val type: Int,
    val flags: Int,
    val mediaTs: Long,
    val payload: ByteArray,
)

/** A media frame as relayed to peers, tagged with the sender's slot. */
data class RelayFrame(
    val type: Int,
    val flags: Int,
    val senderSlot: Int,
    val mediaTs: Long,
    val payload: ByteArray,
)

/**
 * Binary media framing shared with the TypeScript protocol.
 *
 * Client header: `[0x53, 0x01, type, flags, mediaTs BE u32]` (8 bytes).
 * Relay header: `[0x53, 0x01, type, flags, senderSlot, mediaTs BE u32]` (9 bytes).
 * Decoded timestamps are unsigned 32-bit values carried in a [Long].
 */
object FrameCodec {
    const val MAGIC = 0x53
    const val VERSION = 1

    const val HEADER_CLIENT = 8
    const val HEADER_RELAY = 9

    const val FLAG_KEYFRAME = 1
    const val FLAG_EOS = 2
    const val FLAG_SCREEN = 4

    const val TYPE_OPUS = 0x10
    const val TYPE_PCM16 = 0x11
    const val TYPE_H264 = 0x20

    const val MAX_SLOT = 15

    fun encodeClient(type: Int, flags: Int, mediaTs: Long, payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_CLIENT + payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(MAGIC.toByte())
        buffer.put(VERSION.toByte())
        buffer.put(type.toByte())
        buffer.put((flags and 0xff).toByte())
        buffer.putInt((mediaTs and 0xffffffffL).toInt())
        buffer.put(payload)
        return buffer.array()
    }

    fun decodeClient(bytes: ByteArray): ClientFrame? {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (bytes.size < HEADER_CLIENT) return null
        if ((buffer.get(0).toInt() and 0xff) != MAGIC) return null
        if ((buffer.get(1).toInt() and 0xff) != VERSION) return null
        val type = buffer.get(2).toInt() and 0xff
        if (!isMediaType(type)) return null
        val flags = buffer.get(3).toInt() and 0xff
        val mediaTs = buffer.getInt(4).toLong() and 0xffffffffL
        buffer.position(HEADER_CLIENT)
        val payload = ByteArray(buffer.remaining()).also { buffer.get(it) }
        return ClientFrame(type, flags, mediaTs, payload)
    }

    fun encodeRelay(
        type: Int,
        flags: Int,
        senderSlot: Int,
        mediaTs: Long,
        payload: ByteArray,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_RELAY + payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put(MAGIC.toByte())
        buffer.put(VERSION.toByte())
        buffer.put(type.toByte())
        buffer.put((flags and 0xff).toByte())
        buffer.put(senderSlot.toByte())
        buffer.putInt((mediaTs and 0xffffffffL).toInt())
        buffer.put(payload)
        return buffer.array()
    }

    fun decodeRelay(bytes: ByteArray): RelayFrame? {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (bytes.size < HEADER_RELAY) return null
        if ((buffer.get(0).toInt() and 0xff) != MAGIC) return null
        if ((buffer.get(1).toInt() and 0xff) != VERSION) return null
        val type = buffer.get(2).toInt() and 0xff
        if (!isMediaType(type)) return null
        val senderSlot = buffer.get(4).toInt() and 0xff
        if (senderSlot > MAX_SLOT) return null
        val flags = buffer.get(3).toInt() and 0xff
        val mediaTs = buffer.getInt(5).toLong() and 0xffffffffL
        buffer.position(HEADER_RELAY)
        val payload = ByteArray(buffer.remaining()).also { buffer.get(it) }
        return RelayFrame(type, flags, senderSlot, mediaTs, payload)
    }

    private fun isMediaType(type: Int) =
        type == TYPE_OPUS || type == TYPE_PCM16 || type == TYPE_H264
}
