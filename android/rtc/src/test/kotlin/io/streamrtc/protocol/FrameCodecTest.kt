package io.streamrtc.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameCodecTest {
    private val vectors = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("frames.json")!!.readText(),
    ).jsonArray

    @Test
    fun clientAndRelayHeadersMatchTheSharedVectors() {
        for (element in vectors) {
            val v = element.jsonObject
            fun str(key: String) = v.getValue(key).jsonPrimitive.content
            fun num(key: String) = v.getValue(key).jsonPrimitive.int
            fun ts(key: String) = v.getValue(key).jsonPrimitive.long
            val payload = hex(str("payloadHex"))
            val client = FrameCodec.encodeClient(num("type"), num("flags"), ts("mediaTs"), payload)
            assertEquals(str("name"), str("clientHex"), hex(client))
            val relay = FrameCodec.encodeRelay(num("type"), num("flags"), num("senderSlot"), ts("mediaTs"), payload)
            assertEquals(str("name"), str("relayHex"), hex(relay))
        }
    }

    @Test
    fun mediaTsRoundTripsAcrossTheFullUnsignedRange() {
        val big = FrameCodec.decodeRelay(
            FrameCodec.encodeRelay(FrameCodec.TYPE_H264, 0, 9, 4_294_967_295L, ByteArray(0)),
        )!!
        assertEquals(4_294_967_295L, big.mediaTs)
    }

    @Test
    fun decodesWhatItEncodesAndRejectsBadHeaders() {
        val payload = byteArrayOf(1, 2, 3)
        val client = FrameCodec.decodeClient(FrameCodec.encodeClient(FrameCodec.TYPE_PCM16, 0, 1234, payload))!!
        assertEquals(1234L, client.mediaTs)
        assertArrayEquals(payload, client.payload)

        val relay = FrameCodec.decodeRelay(FrameCodec.encodeRelay(FrameCodec.TYPE_H264, FrameCodec.FLAG_KEYFRAME, 15, 42, payload))!!
        assertEquals(15, relay.senderSlot)
        assertEquals(FrameCodec.FLAG_KEYFRAME, relay.flags)

        assertNull(FrameCodec.decodeClient(ByteArray(7)))
        assertNull(FrameCodec.decodeClient(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)))
        assertNull(FrameCodec.decodeRelay(byteArrayOf(0x53, 1, FrameCodec.TYPE_H264.toByte(), 0, 16, 0, 0, 0, 0)))
        assertNull(FrameCodec.decodeRelay(byteArrayOf(0x53, 1, 0x30, 0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun timestampsWrapAsUnsigned32() {
        val f = FrameCodec.decodeClient(FrameCodec.encodeClient(FrameCodec.TYPE_OPUS, 0, 0x1_0000_0005L, ByteArray(0)))!!
        assertEquals(5L, f.mediaTs)
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun hex(text: String) = if (text.isEmpty()) ByteArray(0) else ByteArray(text.length / 2) {
        text.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}
