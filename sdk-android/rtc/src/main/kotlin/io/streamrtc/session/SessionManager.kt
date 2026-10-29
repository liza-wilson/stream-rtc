package io.streamrtc.session

import io.streamrtc.api.AudioRoute
import io.streamrtc.api.Call
import io.streamrtc.api.CallMedia
import io.streamrtc.api.CallState
import io.streamrtc.api.EndReason
import io.streamrtc.api.IncomingCall
import io.streamrtc.api.RtcLogger
import io.streamrtc.api.SessionAudio
import io.streamrtc.api.StreamRtcConfig
import io.streamrtc.api.VideoConfig
import io.streamrtc.media.audio.AudioDevice
import io.streamrtc.protocol.ClientMessages
import io.streamrtc.protocol.FrameCodec
import io.streamrtc.session.RemoteVideoImpl
import io.streamrtc.protocol.ServerEvent
import io.streamrtc.service.ServiceControl
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Owns the live 1:1 call: control-plane routing, relay credentials, audio
 * capture/playback and the call foreground service.
 *
 * **Serialization.** Every signalling and media-lifecycle transition runs
 * through [gate]. A control or relay event arrives on a transport thread; the
 * gate runs the handler there if it is free, otherwise queues it behind the
 * handler that is already running. A host call ([dial], [endAll], [toggleMute])
 * goes through the same gate. The result is one serial order for every
 * [CallSession] call, `StateFlow` publication, capture start/stop and service
 * transition, with no lock held while the handler runs — a transport lock can
 * therefore never deadlock against the session. The audio capture callback is
 * deliberately *not* gated: it encodes and sends inline at 50 fps and drops the
 * frame when the relay is down ([droppedAudioFrames]).
 *
 * **Teardown.** Spec §7.8 fixes the order: encoder stop → camera unbind → audio
 * capture stop → relay close → decoders stop → audio tracks stop → FGS stop →
 * state `Ended`. Encoder, camera and decoders do not exist yet, so [teardown]
 * implements the subset that exists, in the same relative order: capture stop →
 * relay close → audio slot release → service stop → `Ended`. It is idempotent.
 * [stop] additionally closes the shared [AudioDevice] because the manager (and
 * the façade that owns it) is done; a call that merely ends leaves the device
 * open for the next call.
 *
 * [scope] hosts the transport and event collectors. The manager itself is
 * Android-free so it runs under plain JVM tests.
 */
class SessionManager(
    private val config: StreamRtcConfig,
    private val control: ControlLink,
    private val relayFactory: () -> RelayLink,
    private val audio: AudioDevice,
    private val service: ServiceControl,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    @Suppress("unused") private val onQualityChange: (VideoConfig) -> Unit = {},
    private val logger: RtcLogger = RtcLogger.None,
) {
    private val gate = SerialGate()

    private val _incoming = MutableSharedFlow<IncomingCall>(extraBufferCapacity = 8)
    val incoming: SharedFlow<IncomingCall> = _incoming.asSharedFlow()

    private val _active = MutableStateFlow<Call?>(null)
    val active: StateFlow<Call?> = _active.asStateFlow()

    private val sessionAudio = SessionAudioImpl(audio)

    @Volatile
    private var running = false

    @Volatile
    private var relay: RelayLink? = null

    @Volatile
    private var droppedAudioFrames = 0L

    private val collectors = mutableListOf<Job>()
    private val pending = LinkedHashMap<String, PendingCall>()
    private val remoteSlots = LinkedHashSet<Int>()
    private val remoteVideos = mutableMapOf<Int, RemoteVideoImpl>()

    // Guarded by [gate].
    private var activeSession: CallSession? = null
    private var activeCall: CallImpl? = null
    private var captureStarted = false
    private var serviceStarted = false

    /** Opens the control socket and starts routing events. Idempotent until [stop]. */
    fun start() {
        gate.exec {
            ensureRunning()
        }
    }

    /**
     * Ends the current call (if any), closes the relay, releases audio slots and
     * stops the foreground service. The shared audio device stays open, so the
     * next call can start capture again.
     */
    fun endAll() {
        gate.exec {
            endPendingIncoming()
            val session = activeSession
            if (session != null && session.state.value !is CallState.Ended) session.end()
            val reason = endedReason(session)
            teardown(reason, closeDevice = false, force = session != null)
        }
    }

    /**
     * Full shutdown: ends the call, cancels the event collectors, closes the
     * audio device and stops the control link. The manager stays usable only
     * for [start] after the façade rebuilds it (the façade does).
     */
    fun stop() {
        gate.exec {
            running = false
            collectors.forEach { it.cancel() }
            collectors.clear()

            endPendingIncoming()
            val session = activeSession
            if (session != null && session.state.value !is CallState.Ended) session.end()
            teardown(endedReason(session), closeDevice = true, force = true)
            relay = null
            control.stop()
        }
    }

    /**
     * Invites [userId]. Throws [IllegalStateException] when a call is already
     * live, because one relay connection per session serves one call at a time.
     */
    fun dial(userId: String, media: CallMedia = CallMedia.audioVideo): Call = gate.value {
        ensureRunning()
        val live = activeSession
        if (live != null && live.state.value !is CallState.Ended) {
            throw IllegalStateException("a call is already in progress")
        }
        if (pending.isNotEmpty()) {
            throw IllegalStateException("an incoming call is ringing; answer or reject it first")
        }

        val session = CallSession(
            callId = null,
            peerUserId = userId,
            media = media,
            sink = control,
            now = clock,
        )
        val call = CallImpl(session, userId, "call-" + UUID.randomUUID())
        activeSession = session
        activeCall = call
        _active.value = call
        session.dial("room-" + UUID.randomUUID())
        call.publish(session.state.value)
        call
    }

    /** Answers the ringing [callId]; throws when it is no longer ringing. */
    fun acceptIncoming(callId: String): Call = gate.value {
        requireRunning()
        val call = pending.remove(callId)
            ?: throw IllegalStateException("call $callId is not ringing")
        call.session.accept()
        activeSession = call.session
        activeCall = call.call
        _active.value = call.call
        call.call.publish(call.session.state.value)
        call.call
    }

    /** Declines the ringing [callId]; a no-op when it is already gone. */
    fun rejectIncoming(callId: String, reason: String? = null) {
        gate.exec {
            val call = pending.remove(callId) ?: return@exec
            call.session.reject(reason)
            call.call.publish(call.session.state.value)
        }
    }

    /** Flips the microphone mute for the current and future calls. */
    fun toggleMute() {
        gate.exec {
            sessionAudio.muted = !sessionAudio.muted
        }
    }

    /**
     * Platform audio focus was taken for good: end the call instead of talking
     * over the other app. Wired by the façade to `DefaultAudioDevice`.
     */
    fun onFocusLost() {
        gate.exec {
            val session = activeSession ?: return@exec
            if (session.state.value is CallState.Ended) return@exec
            session.end(AUDIO_FOCUS_REASON)
            teardown(endedReason(session), closeDevice = false)
        }
    }

    // ------------------------------------------------------------------
    // Wiring
    // ------------------------------------------------------------------

    /** Subscribes the control and relay streams once. Must run inside [gate]. */
    private fun ensureCollectors() {
        if (collectors.isNotEmpty()) return

        collectors += scope.launch(Dispatchers.Unconfined) {
            control.events.collect { event ->
                gate.exec {
                    try {
                        onControlEvent(event)
                    } catch (t: Throwable) {
                        logger.e(TAG, "control event ${event.t} failed", t)
                    }
                }
            }
        }

        val link = ensureRelay() ?: return
        collectors += scope.launch(Dispatchers.Unconfined) {
            link.events.collect { event ->
                gate.exec {
                    try {
                        onRelayEvent(event)
                    } catch (t: Throwable) {
                        logger.e(TAG, "relay event failed", t)
                    }
                }
            }
        }
    }

    private fun ensureRunning() {
        if (running) return
        running = true
        ensureCollectors()
        control.start()
    }

    private fun requireRunning() {
        if (!running) ensureRunning()
    }

    private fun ensureRelay(): RelayLink? {
        relay?.let { return it }
        return try {
            relayFactory().also { relay = it }
        } catch (e: Exception) {
            logger.e(TAG, "relay factory failed", e)
            null
        }
    }

    // ------------------------------------------------------------------
    // Control plane
    // ------------------------------------------------------------------

    private fun onControlEvent(event: ServerEvent) {
        if (!running) return
        when (event.t) {
            "call.incoming" -> onIncomingEvent(event)
            "room.token" -> onRoomToken(event)
            "call.accepted", "call.rejected", "call.ended", "callee_offline", "error" ->
                routeToSession(event)

            else -> logger.d(TAG, "ignoring ${event.t}")
        }
    }

    private fun onIncomingEvent(event: ServerEvent) {
        val d = event.d
        val callId = d.stringField("callId") ?: return
        if (pending.containsKey(callId)) return

        val live = activeSession
        if (live != null && live.state.value !is CallState.Ended) {
            // One call at a time: decline politely instead of dropping the invite.
            control.send(ClientMessages.reject(callId, BUSY_REASON))
            return
        }

        val fromUserId = d.stringField("fromUserId") ?: return
        val roomId = d.stringField("roomId") ?: return
        val media = d.mediaField()
        val displayName = d.stringField("displayName")

        val session = CallSession(callId, fromUserId, media, control, clock)
        val call = CallImpl(session, fromUserId, callId)
        session.onIncoming(event)
        call.publish(session.state.value)

        val incoming = IncomingCall(
            callId = callId,
            fromUserId = fromUserId,
            displayName = displayName,
            roomId = roomId,
            media = media,
            acceptFn = { acceptIncoming(callId) },
            rejectFn = { reason -> rejectIncoming(callId, reason) },
        )
        pending[callId] = PendingCall(session, call)
        if (!_incoming.tryEmit(incoming)) {
            logger.d(TAG, "no collector for incoming call $callId")
        }
    }

    private fun onRoomToken(event: ServerEvent) {
        val session = activeSession ?: return
        if (session.state.value is CallState.Ended) return
        session.onServerEvent(event)
        if (session.state.value is CallState.Ended) {
            teardown(endedReason(session), closeDevice = false)
            return
        }
        val token = session.token.value ?: return

        val call = activeCall
        if (call != null && call.state.value !is CallState.Active) {
            call.publish(CallState.Connecting)
        }
        val link = ensureRelay() ?: return
        link.connect(token.token, token.relayUrl)
    }

    /**
     * Routes a call-scoped event to the one live session. `callee_offline` and
     * `error` carry no call id and are only meaningful for the active outgoing
     * call; `call.ended`/`call.rejected` can also retire an unanswered invite.
     */
    private fun routeToSession(event: ServerEvent) {
        val id = event.d.stringField("callId")
        if (event.t == "call.ended" || event.t == "call.rejected") {
            val ringingId = id
            if (ringingId != null) {
                val ringing = pending.values.firstOrNull { it.session.callId == ringingId }
                if (ringing != null) {
                    applySessionEvent(ringing.session, event)
                    return
                }
            }
        }
        val active = activeSession
        if (active != null && active.state.value !is CallState.Ended && owns(active, id)) {
            applySessionEvent(active, event)
            return
        }
        if (event.t == "call.ended" || event.t == "call.rejected") {
            val ringing = pending.values.firstOrNull { owns(it.session, id) } ?: return
            applySessionEvent(ringing.session, event)
        }
    }

    private fun applySessionEvent(session: CallSession, event: ServerEvent) {
        val before = session.state.value
        session.onServerEvent(event)
        val state = session.state.value
        val call = session.let { candidate ->
            activeCall?.takeIf { it.session === candidate } ?: pending.values.firstOrNull { it.session === candidate }?.call
        }

        if (state is CallState.Ended) {
            if (call != null && call !== activeCall) {
                pending.remove(session.callId)
                call.publish(state)
            } else {
                teardown(state.reason, closeDevice = false)
            }
            return
        }
        if (state != before) call?.publish(state)
    }

    private fun owns(session: CallSession, callId: String?): Boolean {
        if (callId == null) return true
        val mine = session.callId
        return mine == null || mine == callId
    }

    // ------------------------------------------------------------------
    // Relay
    // ------------------------------------------------------------------

    private fun onRelayEvent(event: RelayEvent) {
        if (!running) return
        when (event) {
            is RelayEvent.Registered -> {
                logger.d(
                    TAG,
                    "registered slot=${event.slot} peers=${event.peers.size} limits=${event.limits}",
                )
                val call = activeCall
                if (call != null && call.state.value !is CallState.Ended) {
                    call.publish(CallState.Active)
                }
                startCallMedia()
            }

            is RelayEvent.Media -> {
                val frame = event.frame
                if (frame.type == FrameCodec.TYPE_PCM16) {
                    remoteSlots.add(frame.senderSlot)
                    audio.write(frame.senderSlot, frame.payload)
                    remoteVideos[frame.senderSlot]?.onAudioFrame(frame.mediaTs, System.currentTimeMillis())
                }
                // Video decoders arrive with Task 16.
            }

            is RelayEvent.PeerLeft -> {
                remoteSlots.remove(event.slot)
                audio.removeSlot(event.slot)
            }

            is RelayEvent.PeerJoined -> Unit

            is RelayEvent.KeyframeNeeded -> {
                // Task 14 asks the encoder for a keyframe here.
            }

            is RelayEvent.Closed -> {
                // A 4401 (token rejected) is retried after a fresh `call.token`
                // by Task 18; a transport fault is already being retried by
                // RelayClient itself.
                logger.d(TAG, "relay closed code=${event.code} reason=${event.reason}")
            }
        }
    }

    private fun startCallMedia() {
        if (config.foregroundService && !serviceStarted) {
            serviceStarted = true
            service.start()
        }
        if (!captureStarted && shouldCaptureAudio()) {
            captureStarted = true
            audio.startCapture(::onCapturedAudio)
        }
    }

    /** A video-only call must not open the microphone. */
    private fun shouldCaptureAudio(): Boolean = activeSession?.media?.audio ?: true

    /**
     * Capture thread: 50 frames/s. Encode and send inline; a frame that cannot
     * be sent is dropped and counted, never queued and never re-dispatched.
     */
    private fun onCapturedAudio(bytes: ByteArray, mediaTs: Long) {
        if (sessionAudio.muted) return
        val link = relay ?: return
        val frame = FrameCodec.encodeClient(FrameCodec.TYPE_PCM16, 0, mediaTs, bytes)
        if (!link.send(frame)) droppedAudioFrames++
    }

    // ------------------------------------------------------------------
    // Teardown
    // ------------------------------------------------------------------

    private fun endPendingIncoming() {
        for (call in pending.values.toList()) {
            call.session.reject()
            call.call.publish(call.session.state.value)
        }
        pending.clear()
    }

    private fun endedReason(session: CallSession?): EndReason {
        val state = session?.state?.value
        return if (state is CallState.Ended) state.reason else EndReason.LocalEnded
    }

    /**
     * Spec §7.8 subset, in order: capture stop → relay close → audio slots stop
     * → foreground service stop → state `Ended`. Idempotent: once the flags are
     * cleared a second call is a no-op unless [force] is set.
     */
    /** Registers a [RemoteVideoImpl] for [slot] so its A/V gate receives audio. */
    fun registerRemoteVideo(slot: Int, remote: RemoteVideoImpl) {
        remoteVideos[slot] = remote
    }

    /** Unregisters the [RemoteVideoImpl] for [slot]. */
    fun unregisterRemoteVideo(slot: Int) {
        remoteVideos.remove(slot)
    }

    private fun teardown(reason: EndReason, closeDevice: Boolean, force: Boolean = false) {
        val call = activeCall
        val hadWork = force || call != null || captureStarted || serviceStarted ||
            relay?.state?.value != RelayState.Idle
        if (!hadWork) {
            if (closeDevice) runCatching { audio.close() }
            return
        }

        // 1. Stop capture first: no new frames can enter the relay queue.
        if (captureStarted) {
            captureStarted = false
            runCatching { audio.stopCapture() }
        }

        // 2. Close the relay socket and stop its retries.
        relay?.let { link -> runCatching { link.close() } }

        // 3. decoders do not exist yet; stop every remote audio track.
        remoteSlots.forEach { slot -> runCatching { audio.removeSlot(slot) } }
        remoteSlots.clear()
        if (closeDevice) runCatching { audio.close() }

        // 4. The notification goes away with the call.
        if (serviceStarted) {
            serviceStarted = false
            runCatching { service.stop() }
        }

        // 5. Publish the terminal state last.
        call?.publish(CallState.Ended(reason))
        activeCall = null
        activeSession = null
        _active.value = null
    }

    private class PendingCall(val session: CallSession, val call: CallImpl)

    /** One call handle; its state is manager-published so it can reach Active. */
    private inner class CallImpl(
        val session: CallSession,
        override val peerUserId: String,
        private val localId: String,
    ) : Call {
        private val _state = MutableStateFlow(session.state.value)
        override val state: StateFlow<CallState> = _state.asStateFlow()

        override val id: String
            get() = session.callId ?: localId

        override val audio: SessionAudio
            get() = sessionAudio

        fun publish(value: CallState) {
            _state.value = value
        }

        override suspend fun end(reason: String?) {
            gate.value<Unit> {
                session.end(reason)
                if (this@SessionManager.activeCall === this) {
                    teardown(endedReason(session), closeDevice = false)
                } else {
                    publish(session.state.value)
                }
            }
        }
    }

    private companion object {
        const val TAG = "SessionManager"
        const val BUSY_REASON = "busy"
        const val AUDIO_FOCUS_REASON = "audio_focus"
    }
}

/**
 * Mute/speaker state for the live call. The device has no route flow yet, so
 * [route] follows the speakerphone request; real device-route observation is
 * wired when the media pipeline lands. Mapping between the internal media route
 * enum and [AudioRoute] stays in this package so no public type references
 * `io.streamrtc.media`.
 */
internal class SessionAudioImpl(private val device: AudioDevice) : SessionAudio {
    private val _route = MutableStateFlow(AudioRoute.Earpiece)
    override val route: StateFlow<AudioRoute> = _route.asStateFlow()

    @Volatile
    override var muted: Boolean = false

    @Volatile
    private var speaker = false

    override var speakerphone: Boolean
        get() = speaker
        set(value) {
            speaker = value
            device.setSpeakerphone(value)
            _route.value = if (value) AudioRoute.Speaker else AudioRoute.Earpiece
        }
}

/**
 * A minimal serial executor that runs on the posting thread when it is free and
 * queues otherwise. It never holds a session lock while a task runs, so a
 * transport lock taken by [SessionManager.teardown] cannot deadlock with an
 * event being emitted from inside that transport.
 */
private class SerialGate {
    private val lock = Any()
    private val tasks = ArrayDeque<() -> Unit>()
    private var draining = false
    private var drainer: Thread? = null

    /** Fire-and-forget: runs inline or queues behind the current task. */
    fun exec(block: () -> Unit) {
        if (drainer === Thread.currentThread()) {
            block()
            return
        }
        val start = synchronized(lock) {
            tasks.addLast(block)
            if (draining) {
                false
            } else {
                draining = true
                drainer = Thread.currentThread()
                true
            }
        }
        if (start) drain()
    }

    /** Runs [block] serially and returns its value (or rethrows its failure). */
    fun <T> value(block: () -> T): T {
        if (drainer === Thread.currentThread()) return block()

        val result = Result<T>()
        val monitor = Object()
        exec {
            try {
                result.value = block()
            } catch (t: Throwable) {
                result.error = t
            } finally {
                synchronized(monitor) {
                    result.done = true
                    monitor.notifyAll()
                }
            }
        }
        synchronized(monitor) {
            while (!result.done) {
                try {
                    monitor.wait()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IllegalStateException("interrupted while waiting for the session", e)
                }
            }
        }
        result.error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result.value as T
    }

    private fun drain() {
        while (true) {
            val next = synchronized(lock) {
                val task = tasks.removeFirstOrNull()
                if (task == null) {
                    draining = false
                    drainer = null
                }
                task
            } ?: return
            // Tasks own their error handling; a failed handler must not stall
            // the serial queue for every later event.
            runCatching { next() }
        }
    }

    private class Result<T> {
        var value: T? = null
        var error: Throwable? = null

        @Volatile
        var done = false
    }
}

private fun JsonObject.stringField(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.mediaField(): CallMedia {
    val media = this["media"] as? JsonObject
    val audio = media?.boolField("audio") ?: true
    val video = media?.boolField("video") ?: false
    return CallMedia(audio = audio, video = video)
}

private fun JsonObject.boolField(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
