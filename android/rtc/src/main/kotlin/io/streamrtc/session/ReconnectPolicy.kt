package io.streamrtc.session

import io.streamrtc.transport.Backoff
import io.streamrtc.transport.CloseAction
import io.streamrtc.transport.actionForCloseCode

sealed interface ReconnectDecision {
    data class Retry(
        val delayMs: Long,
        val refreshToken: Boolean,
        val reduceQuality: Boolean,
    ) : ReconnectDecision

    data class GiveUp(val code: String) : ReconnectDecision
}

class ReconnectPolicy(private val backoff: Backoff) {
    fun next(closeCode: Int, attempt: Int): ReconnectDecision {
        return when (actionForCloseCode(closeCode)) {
            CloseAction.REFRESH_TOKEN -> ReconnectDecision.Retry(
                delayMs = 0L,
                refreshToken = true,
                reduceQuality = false,
            )

            CloseAction.REDUCE_BITRATE -> ReconnectDecision.Retry(
                delayMs = backoff.nextDelayMs(),
                refreshToken = false,
                reduceQuality = true,
            )

            CloseAction.TERMINAL -> ReconnectDecision.GiveUp(codeFor(closeCode))

            CloseAction.RECONNECT -> ReconnectDecision.Retry(
                delayMs = backoff.nextDelayMs(),
                refreshToken = false,
                reduceQuality = false,
            )
        }
    }

    fun reset() = backoff.reset()

    private fun codeFor(closeCode: Int): String = when (closeCode) {
        4400 -> "invalid_token"
        4403 -> "forbidden"
        4404 -> "not_found"
        4405 -> "auth_failed"
        4409 -> "room_unavailable"
        1008 -> "duplicate_session"
        1009 -> "frame_too_large"
        else -> "terminal_$closeCode"
    }
}
