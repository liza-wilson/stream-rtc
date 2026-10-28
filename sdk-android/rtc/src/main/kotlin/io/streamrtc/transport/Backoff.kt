package io.streamrtc.transport

import kotlin.random.Random

/**
 * Exponential backoff for transport reconnects: `250 ms · 2^n` capped at 8 s,
 * ±20 % jitter, reset after a successful `hello.ok` / `registered`.
 *
 * The attempt counter saturates at [MAX_ATTEMPT] so a long-lived reconnect loop
 * cannot overflow the shift or the counter; from there on every delay is the cap.
 */
class Backoff(
    private val baseMs: Long = 250,
    private val maxMs: Long = 8_000,
    private val jitter: Double = 0.2,
    private val random: Random = Random.Default,
) {
    private var attempt = 0

    /** Next delay in milliseconds, then advances the attempt counter. */
    fun nextDelayMs(): Long {
        val delay = cappedDelayMs()
        attempt = minOf(attempt + 1, MAX_ATTEMPT)
        if (jitter <= 0.0) return delay
        val scaled = (delay * (1.0 + random.nextDouble(-jitter, jitter))).toLong()
        return scaled.coerceAtLeast(1L)
    }

    /** Back to the base delay after a successful handshake. */
    fun reset() {
        attempt = 0
    }

    private fun cappedDelayMs(): Long {
        if (attempt >= MAX_ATTEMPT) return maxMs
        val factor = 1L shl attempt
        // baseMs > maxMs / factor also covers baseMs * factor overflowing Long.
        return if (baseMs > maxMs / factor) maxMs else baseMs * factor
    }

    private companion object {
        /** Largest shift that keeps `1L shl attempt` positive. */
        const val MAX_ATTEMPT = 63
    }
}
