package io.streamrtc.api

/** A member of the current call, keyed by its relay slot (0..15). */
data class Participant(
    val slot: Int,
    val userId: String?,
    val hasVideo: Boolean = false,
    val speaking: Boolean = false,
)
