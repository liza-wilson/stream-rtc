package io.streamrtc.media.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JitterBufferTest {
    private var now = 0L
    private fun buf() = JitterBuffer(frameBytes = 4, frameMs = 20, capacityFrames = 10, clockMs = { now })

    @Test fun emitsNothingUntilTheTargetDepthIsReached() {
        val b = buf()
        b.push(0, byteArrayOf(1, 0, 0, 0))
        assertNull(b.poll())
        now = 40
        b.push(20, byteArrayOf(2, 0, 0, 0))
        b.push(40, byteArrayOf(3, 0, 0, 0))
        assertEquals(3, b.depthMs / 20)
        assertArrayEquals(byteArrayOf(1, 0, 0, 0), b.poll())
    }

    @Test fun dropsFramesOlderThanTheLastDeliveredOne() {
        val b = buf()
        repeat(5) { i -> b.push(i * 20L, byteArrayOf(i.toByte())) }
        now = 200
        b.poll()
        b.poll()
        b.push(10, byteArrayOf(99))
        var seen = mutableListOf<Byte>()
        while (true) { val f = b.poll() ?: break; seen.add(f[0]) }
        assertEquals(listOf<Byte>(2, 3, 4), seen)
    }

    @Test fun targetDepthGrowsWithArrivalJitter() {
        val b = buf()
        repeat(6) { i ->
            if (i % 2 == 0) now += 20 else now += 120   // alternating on-time and late
            b.push(i * 20L, ByteArray(4))
        }
        assertEquals(true, b.targetDepthMs > 40)
        assertEquals(true, b.targetDepthMs <= 200)
    }

    @Test fun resetClearsEverything() {
        val b = buf()
        repeat(5) { i -> b.push(i * 20L, ByteArray(4)) }
        now = 100
        b.reset()
        assertEquals(0, b.depthMs)
        assertNull(b.poll())
    }
}
