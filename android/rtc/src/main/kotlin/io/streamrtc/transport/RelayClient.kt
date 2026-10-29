package io.streamrtc.transport

import io.streamrtc.api.Limits
import io.streamrtc.api.RtcLogger
import io.streamrtc.protocol.ClientMessages
import io.streamrtc.protocol.FrameCodec
import io.streamrtc.protocol.Messages
import io.streamrtc.session.PeerInfo
import io.streamrtc.session.RelayEvent
import io.streamrtc.session.RelayLink
import io.streamrtc.session.RelayState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The media-relay connection: one WebSocket at a time, registered with a room
 * token, carrying JSON control text and binary media frames. Implements
 * [RelayLink].
 *
 * Transport faults (1006/1011/1012, 1013, unknown codes) are retried by this
 * client with the same credentials, on [scope] with [delay] so reconnect
 * timing is virtual-time testable — never [Thread.sleep]. A token rejection
 * (close 4401) is only reported: this client has no token source, so the
 * session fetches a fresh one and calls [connect] again, which always builds
 * a fresh transport. Terminal codes end the link in [RelayState.Failed].
 *
 * [lock] is the single writer for [RelayState]: [close] takes it too, and the
 * attempt generation it bumps makes any in-flight close callback stale, so a
 * state is never published after `Idle`. Transport ownership and the attempt
 * generation are guarded by the same lock — the same discipline as
 * [ControlClient].
 *
 * [transportFactory] receives the URL passed to [connect] so the real factory
 * can build an `OkHttpRelayTransport` per relay host; the relay URL is only
 * known once the control plane has minted the room token.
 */
class RelayClient(
    private val transportFactory: (url: String) -> RelayTransport,
    private val scope: CoroutineScope,
    private val logger: RtcLogger = RtcLogger.None,
    private val backoff: Backoff = Backoff(),
) : RelayLink {
    private val lock = Any()

    private val _state = MutableStateFlow<RelayState>(RelayState.Idle)
    override val state: StateFlow<RelayState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RelayEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<RelayEvent> = _events.asSharedFlow()

    // The fields below are guarded by [lock].
    private var attemptJob: Job? = null
    private var transport: RelayTransport? = null
    private var generation = 0L
    private var attempt = 0
    private var token: String = ""
    private var url: String = ""

    /**
     * Opens a relay connection, registering with [token] against [url]. While
     * a connection is live this supersedes it: the previous transport and
     * attempt are retired, so a fresh-token retry always starts clean.
     */
    override fun connect(token: String, url: String) {
        var expected = 0L
        var previousJob: Job? = null
        var previousSocket: RelayTransport? = null
        synchronized(lock) {
            expected = ++generation
            this.token = token
            this.url = url
            attempt = 0
            backoff.reset()
            previousJob = attemptJob
            attemptJob = null
            previousSocket = transport
            transport = null
            _state.value = RelayState.Connecting
        }
        previousJob?.cancel()
        previousSocket?.close()
        launchAttempt(expected, token, url, delayMs = null)
    }

    /** Sends one binary media frame; false when no socket is connected. */
    override fun send(bytes: ByteArray): Boolean {
        val socket = synchronized(lock) { transport } ?: return false
        logger.d(TAG, "Sending binary media frame (${bytes.size} bytes)")
        return socket.send(bytes)
    }

    /** Asks the relay to request a keyframe from [slot]. */
    override fun requestKeyframe(slot: Int) {
        val socket = synchronized(lock) { transport } ?: return
        socket.send(ClientMessages.keyframe(slot))
    }

    /** Bytes buffered in the transport's outgoing queue, for quality laddering. */
    override fun queuedBytes(): Long {
        val socket = synchronized(lock) { transport } ?: return 0L
        return socket.queuedBytes()
    }

    /** Closes the connection and stops retrying. Never reports a failure after. */
    override fun close() {
        var job: Job? = null
        var socket: RelayTransport? = null
        synchronized(lock) {
            // The generation bump makes any in-flight close callback stale:
            // no state is published after Idle.
            generation++
            job = attemptJob
            attemptJob = null
            socket = transport
            transport = null
            attempt = 0
            _state.value = RelayState.Idle
        }
        job?.cancel()
        socket?.close()
    }

    /**
     * Runs one connection attempt after an optional backoff delay. The job
     * lives until the socket closes or [close] / a newer [connect] cancels it.
     * Started [CoroutineStart.UNDISPATCHED] so the transport is created and
     * wired synchronously for the caller.
     */
    private fun launchAttempt(expected: Long, token: String, url: String, delayMs: Long?) {
        var previous: Job? = null
        synchronized(lock) {
            if (generation != expected) return
            previous = attemptJob
            attemptJob = null
        }
        previous?.cancel()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val self = currentCoroutineContext()[Job]
            synchronized(lock) {
                if (generation != expected) return@launch
                attemptJob = self
            }
            if (delayMs != null) delay(delayMs)
            synchronized(lock) {
                if (generation != expected) return@launch
            }
            connectTransport(expected, token, url)
        }
    }

    private suspend fun connectTransport(expected: Long, token: String, url: String) {
        var socket: RelayTransport? = null
        val active = AtomicBoolean(true)
        try {
            val created = transportFactory(url)
            socket = created
            val owned = synchronized(lock) {
                if (generation != expected) {
                    false
                } else {
                    transport = created
                    true
                }
            }
            if (!owned) {
                created.close()
                return
            }
            created.connect(
                onOpen = {
                    if (active.get()) {
                        val shouldSend = synchronized(lock) {
                            generation == expected && transport === created
                        }
                        if (shouldSend) created.send(ClientMessages.register(token))
                    }
                },
                onText = { text ->
                    if (active.get()) handleText(created, expected, text)
                },
                onBinary = { bytes ->
                    if (active.get()) handleBinary(created, expected, bytes)
                },
                onClosed = { code, reason ->
                    if (active.compareAndSet(true, false)) {
                        synchronized(lock) {
                            if (transport === created) transport = null
                        }
                        created.close()
                        onTransportClosed(expected, code, reason)
                    }
                },
            )
            awaitCancellation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Malformed URL, a factory throw, or a synchronous connect failure.
            // Retrying a construction bug would spin forever, so this is terminal.
            logger.e(TAG, "relay transport failed", e)
            fail(expected)
        } finally {
            active.set(false)
            val created = socket
            if (created != null) {
                val owned = synchronized(lock) {
                    if (transport === created) {
                        transport = null
                        true
                    } else {
                        false
                    }
                }
                if (owned) created.close()
            }
        }
    }

    private fun onTransportClosed(expected: Long, code: Int, reason: String) {
        val action = actionForCloseCode(code)
        if (action == CloseAction.TERMINAL) {
            logger.d(TAG, "terminal close $code: $reason")
        }

        var job: Job? = null
        var retryDelayMs: Long? = null
        var retryToken = ""
        var retryUrl = ""
        synchronized(lock) {
            // close() or a newer connect() may have completed while this
            // callback was in flight.
            if (generation != expected) return
            // The socket closed: surface it so the session can react (notably
            // a fresh-token retry after 4401, which it owns in Task 18).
            _events.tryEmit(RelayEvent.Closed(code, reason))
            if (generation != expected) return
            when (action) {
                CloseAction.TERMINAL -> {
                    generation++
                    job = attemptJob
                    attemptJob = null
                    _state.value = RelayState.Failed(code)
                }

                CloseAction.REFRESH_TOKEN -> {
                    // No token source here: report Reconnecting and let the
                    // session fetch a fresh token and call connect() again.
                    attempt++
                    job = attemptJob
                    attemptJob = null
                    _state.value = RelayState.Reconnecting(attempt)
                }

                CloseAction.RECONNECT, CloseAction.REDUCE_BITRATE -> {
                    attempt++
                    _state.value = RelayState.Reconnecting(attempt)
                    retryDelayMs = backoff.nextDelayMs()
                    retryToken = token
                    retryUrl = url
                }
            }
        }
        job?.cancel()
        val delayMs = retryDelayMs
        if (delayMs != null) launchAttempt(expected, retryToken, retryUrl, delayMs)
    }

    private fun fail(expected: Long) {
        var job: Job? = null
        synchronized(lock) {
            // A host close() wins: never publish Failed after Idle.
            if (generation != expected) return
            generation++
            job = attemptJob
            attemptJob = null
            _state.value = RelayState.Failed(ABNORMAL_CLOSURE)
        }
        job?.cancel()
    }

    private fun handleText(socket: RelayTransport, expected: Long, text: String) {
        val event = parseRelayText(text)
        if (event == null) {
            logger.d(TAG, "dropping malformed relay message")
            return
        }
        synchronized(lock) {
            // A superseded socket or a completed close() must not publish
            // events or state (notably a late `registered` after a reconnect).
            if (generation != expected || transport !== socket) return
            _events.tryEmit(event)
            if (generation != expected || transport !== socket) return
            if (event is RelayEvent.Registered) {
                attempt = 0
                backoff.reset()
                _state.value = RelayState.Active
            }
        }
    }

    private fun handleBinary(socket: RelayTransport, expected: Long, bytes: ByteArray) {
        val frame = FrameCodec.decodeRelay(bytes)
        if (frame == null) {
            logger.d(TAG, "dropping undecodable relay frame")
            return
        }
        logger.d(TAG, "Received binary media frame: senderSlot=${frame.senderSlot}, type=${frame.type}, size=${frame.payload.size} bytes")
        synchronized(lock) {
            if (generation != expected || transport !== socket) return
            _events.tryEmit(RelayEvent.Media(frame))
        }
    }

    private companion object {
        const val TAG = "RelayClient"

        /** OkHttp reports a failed socket (or a failed construction) as 1006. */
        const val ABNORMAL_CLOSURE = 1006
    }
}

/**
 * Parses a relay text message. The relay's `registered`, `peer_joined` and
 * `peer_left` carry their fields at the top level (next to `t`), unlike the
 * control plane's `d`-shaped payloads, so [Messages.parseServer] only gates
 * the message (size/shape) and the fields are read from the root object.
 * Returns null for anything unrecognized — including `error`, which has no
 * consumer yet — so callers can drop it silently.
 */
private fun parseRelayText(text: String): RelayEvent? {
    val gate = Messages.parseServer(text) ?: return null
    val root = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (_: Exception) {
        return null
    }
    return when (gate.t) {
        "registered" -> {
            val slot = root.intField("slot") ?: return null
            val limitsObject = root["limits"] as? JsonObject
            RelayEvent.Registered(
                slot = slot,
                peers = root.intArrayField("peers"),
                limits = Limits(
                    maxParticipants = limitsObject?.intField("maxParticipants") ?: 0,
                    maxVideo = limitsObject?.intField("maxVideoParticipants") ?: 0,
                ),
                peerInfo = root.peerInfoField(),
            )
        }

        "peer_joined" -> RelayEvent.PeerJoined(
            slot = root.intField("slot") ?: return null,
            userId = root.stringField("userId") ?: return null,
        )

        "peer_left" -> RelayEvent.PeerLeft(
            slot = root.intField("slot") ?: return null,
        )

        // The slot is not in the payload: it asks the sender of the last frame
        // for a keyframe, so the event carries no fields.
        "need_keyframe" -> RelayEvent.KeyframeNeeded

        else -> null
    }
}

private fun JsonObject.intField(name: String): Int? =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()

private fun JsonObject.stringField(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.intArrayField(name: String): List<Int> =
    (this[name] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
        ?: emptyList()

private fun JsonObject.peerInfoField(): List<PeerInfo> =
    (this["peerInfo"] as? JsonArray)
        ?.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val slot = entry.intField("slot") ?: return@mapNotNull null
            val userId = entry.stringField("userId") ?: return@mapNotNull null
            PeerInfo(slot, userId)
        }
        ?: emptyList()
