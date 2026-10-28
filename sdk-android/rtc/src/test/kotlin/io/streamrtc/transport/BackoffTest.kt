package io.streamrtc.transport

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {
    @Test
    fun doublesUpToTheCap() {
        val b = Backoff(baseMs = 250, maxMs = 8000, jitter = 0.0, random = Random(1))
        assertEquals(listOf(250L, 500L, 1000L, 2000L, 4000L, 8000L, 8000L), List(7) { b.nextDelayMs() })
    }

    @Test
    fun jitterStaysWithinTwentyPercent() {
        val b = Backoff(baseMs = 250, maxMs = 8000, jitter = 0.2, random = Random(7))
        val unjittered = listOf(250L, 500L, 1000L, 2000L, 4000L, 8000L, 8000L)
        for (expected in unjittered) {
            val d = b.nextDelayMs()
            assertTrue("$d is outside ±20% of $expected", d in expected * 80 / 100..expected * 120 / 100)
        }
    }

    @Test
    fun resetStartsOver() {
        val b = Backoff(baseMs = 250, jitter = 0.0, random = Random(1))
        b.nextDelayMs(); b.nextDelayMs(); b.reset()
        assertEquals(250L, b.nextDelayMs())
    }
}
