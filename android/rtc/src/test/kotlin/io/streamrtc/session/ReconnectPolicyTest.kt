package io.streamrtc.session

import io.streamrtc.transport.Backoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReconnectPolicyTest {
    private val policy = ReconnectPolicy(Backoff(250, 8000, 0.0, Random(1)))

    @Test fun tokenErrorsRetryImmediatelyWithAFreshToken() {
        val d = policy.next(4401, attempt = 1) as ReconnectDecision.Retry
        assertEquals(0L, d.delayMs)
        assertTrue(d.refreshToken)
        assertEquals(false, d.reduceQuality)
    }

    @Test fun slowConsumerLowersQuality() {
        val d = policy.next(1013, attempt = 0) as ReconnectDecision.Retry
        assertTrue(d.reduceQuality)
    }

    @Test fun terminalCodesGiveUp() {
        assertTrue(policy.next(4409, 0) is ReconnectDecision.GiveUp)
        assertTrue(policy.next(1008, 0) is ReconnectDecision.GiveUp)
    }

    @Test fun giveUpCarriesAStableCodeForTheHost() {
        assertEquals("room_unavailable", (policy.next(4409, 0) as ReconnectDecision.GiveUp).code)
        assertEquals("duplicate_session", (policy.next(1008, 0) as ReconnectDecision.GiveUp).code)
    }

    @Test fun transportErrorsBackOff() {
        assertEquals(250L, (policy.next(1011, 0) as ReconnectDecision.Retry).delayMs)
        assertEquals(500L, (policy.next(1011, 1) as ReconnectDecision.Retry).delayMs)
    }
}
