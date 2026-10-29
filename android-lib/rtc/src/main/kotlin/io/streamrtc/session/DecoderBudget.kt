package io.streamrtc.session

/**
 * Caps the number of concurrently attached decoders (4 video tiles). When the
 * budget is exhausted, [tryAcquire] returns false and the caller leaves the tile
 * in [io.streamrtc.api.RemoteVideoState.Idle] instead of throwing.
 */
class DecoderBudget(private val max: Int) {
    private var used = 0

    fun tryAcquire(): Boolean {
        if (used >= max) return false
        used++
        return true
    }

    fun release() {
        if (used > 0) used--
    }
}
