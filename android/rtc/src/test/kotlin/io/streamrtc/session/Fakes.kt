package io.streamrtc.session

import io.streamrtc.api.ConnectionState
import io.streamrtc.media.audio.AudioDevice
import io.streamrtc.protocol.ServerEvent
import io.streamrtc.service.ServiceControl
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Control link double for session-manager tests. Events are emitted without
 * suspension so a collector subscribed with [kotlinx.coroutines.Dispatchers.Unconfined]
 * (as `SessionManager` does) sees them synchronously.
 */
class FakeControlLink : ControlLink {
    val sent = mutableListOf<String>()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    override fun send(text: String): Boolean {
        sent.add(text)
        return true
    }

    override fun start() {
        _state.value = ConnectionState.Connecting
    }

    override fun stop() {
        _state.value = ConnectionState.Idle
    }

    fun emit(event: ServerEvent) {
        _events.tryEmit(event)
    }
}

/** Relay link double that records credentials, binary frames and close state. */
class FakeRelayLink : RelayLink {
    var token: String? = null
    var url: String? = null
    var closed: Boolean = false

    /** Returned by [queuedBytes]; the quality ladder drains this in Task 18. */
    var queued: Long = 0L

    val sentBinary = mutableListOf<ByteArray>()
    val keyframeRequests = mutableListOf<Int>()

    private val _state = MutableStateFlow<RelayState>(RelayState.Idle)
    override val state: StateFlow<RelayState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RelayEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<RelayEvent> = _events.asSharedFlow()

    override fun connect(token: String, url: String) {
        this.token = token
        this.url = url
        closed = false
        _state.value = RelayState.Connecting
    }

    override fun send(bytes: ByteArray): Boolean {
        if (closed) return false
        sentBinary.add(bytes)
        return true
    }

    override fun requestKeyframe(slot: Int) {
        keyframeRequests.add(slot)
    }

    override fun queuedBytes(): Long = queued

    override fun close() {
        closed = true
        _state.value = RelayState.Idle
    }

    fun emit(event: RelayEvent) {
        _events.tryEmit(event)
    }

    fun emitClosed(code: Int, reason: String) {
        emit(RelayEvent.Closed(code, reason))
    }
}

/** Audio device double: records capture state, written slots and removals. */
class FakeAudioDevice : AudioDevice {
    var capturing: Boolean = false
        private set
    var closed: Boolean = false
        private set
    var speakerphone: Boolean = false
        private set

    /** Every payload written per slot, in arrival order. */
    val written = LinkedHashMap<Int, MutableList<ByteArray>>()
    val removed = mutableListOf<Int>()

    private var sink: ((ByteArray, Long) -> Unit)? = null

    override fun startCapture(onFrame: (ByteArray, Long) -> Unit) {
        sink = onFrame
        capturing = true
    }

    override fun stopCapture() {
        sink = null
        capturing = false
    }

    override fun write(slot: Int, payload: ByteArray) {
        written.getOrPut(slot) { mutableListOf() }.add(payload)
    }

    override fun removeSlot(slot: Int) {
        removed.add(slot)
        written.remove(slot)
    }

    override fun setSpeakerphone(on: Boolean) {
        speakerphone = on
    }

    override fun close() {
        sink = null
        capturing = false
        closed = true
    }

    /** Delivers one captured frame through the registered sink, inline. */
    fun emitFrame(payload: ByteArray, mediaTs: Long) {
        sink?.invoke(payload, mediaTs)
    }
}

/** Foreground-service double. */
class FakeServiceControl : ServiceControl {
    var starts: Int = 0
        private set
    var stops: Int = 0
        private set

    override fun start() {
        starts++
    }

    override fun stop() {
        stops++
    }
}
