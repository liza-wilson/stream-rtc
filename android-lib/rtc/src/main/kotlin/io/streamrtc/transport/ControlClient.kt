package io.streamrtc.transport

import io.streamrtc.api.ConnectionState
import io.streamrtc.api.RtcLogger
import io.streamrtc.protocol.ClientMessages
import io.streamrtc.protocol.Messages
import io.streamrtc.protocol.ServerEvent
import io.streamrtc.session.ControlLink
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

/**
 * The control-plane connection: one WebSocket at a time, carrying `hello`,
 * call signalling, and presence. Reconnects until stopped, with close-code
 * policy from [actionForCloseCode] and delays from [Backoff].
 *
 * Each attempt owns a fresh transport from [transportFactory]; the close
 * callback decides what happens next. That keeps token refresh immediate
 * while transport faults wait on [delay] in the caller's scope, so reconnect
 * timing is virtual-time testable.
 *
 * [lock] is the single writer for [ConnectionState]: [stop] takes it too, so a
 * close callback racing with a host stop can never publish a state after
 * `Idle`, and a stale socket cannot publish `Online`. Transport ownership and
 * the attempt generation are guarded by the same lock.
 *
 * Implements [ControlLink]: the session manager drives it through that seam.
 */
class ControlClient(
    private val transportFactory: () -> ControlTransport,
    private val tokenProvider: suspend () -> String,
    private val scope: CoroutineScope,
    private val deviceId: String,
    private val appVersion: String?,
    private val logger: RtcLogger = RtcLogger.None,
    private val backoff: Backoff = Backoff(),
) : ControlLink {
    private val lock = Any()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    /** Wall-clock receipt time of the most recent `pong`; 0 before the first. */
    @Volatile
    var lastPongMs: Long = 0L
        private set

    // The fields below are guarded by [lock].
    private var started = false
    private var attemptJob: Job? = null
    private var transport: ControlTransport? = null
    private var generation = 0L
    private var attempt = 0
    private var tokenRejections = 0

    /** Starts the connection. Repeated calls while running are no-ops. */
    override fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            attempt = 0
            tokenRejections = 0
            _state.value = ConnectionState.Connecting
        }
        launchAttempt(delayMs = null)
    }

    /** Sends a control message; false when no socket is connected. */
    override fun send(text: String): Boolean {
        val socket = synchronized(lock) { transport } ?: return false
        return socket.send(text)
    }

    /** Stops reconnecting and closes the socket. Never reports [ConnectionState.Failed]. */
    override fun stop() {
        var job: Job? = null
        var socket: ControlTransport? = null
        synchronized(lock) {
            started = false
            generation++
            job = attemptJob
            attemptJob = null
            socket = transport
            transport = null
            _state.value = ConnectionState.Idle
        }
        job?.cancel()
        socket?.close()
    }

    /**
     * Runs one connection attempt after an optional backoff delay. The job
     * lives until the socket closes or [stop] cancels it.
     */
    private fun launchAttempt(delayMs: Long?) {
        var expected = 0L
        var previous: Job? = null
        synchronized(lock) {
            if (!started) return
            expected = ++generation
            previous = attemptJob
            attemptJob = null
        }
        previous?.cancel()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val self = currentCoroutineContext()[Job]
            synchronized(lock) {
                if (!started || generation != expected) return@launch
                attemptJob = self
            }
            if (delayMs != null) delay(delayMs)
            synchronized(lock) {
                if (!started || generation != expected) return@launch
            }

            val token = try {
                tokenProvider()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "token provider failed", e)
                giveUp(expected, TOKEN_FAILED)
                return@launch
            }
            synchronized(lock) {
                if (!started || generation != expected) return@launch
            }
            connect(expected, token)
        }
    }

    private suspend fun connect(expected: Long, token: String) {
        var socket: ControlTransport? = null
        val active = AtomicBoolean(true)
        try {
            val created = transportFactory()
            socket = created
            val owned = synchronized(lock) {
                if (!started || generation != expected) {
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
                            started && generation == expected && transport === created
                        }
                        if (shouldSend) created.send(hello(token))
                    }
                },
                onMessage = { text ->
                    if (active.get()) handleMessage(created, expected, text)
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
            logger.e(TAG, "control transport failed", e)
            giveUp(expected, CLOSED)
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
        var retry = false
        var retryDelayMs: Long? = null
        synchronized(lock) {
            // stop() may have completed while this callback was in flight.
            if (!started || generation != expected) return
            when (action) {
                CloseAction.TERMINAL -> {
                    started = false
                    generation++
                    job = attemptJob
                    attemptJob = null
                    _state.value = ConnectionState.Failed(failureCode(code))
                }

                CloseAction.REFRESH_TOKEN -> {
                    tokenRejections++
                    if (tokenRejections >= MAX_TOKEN_REJECTIONS) {
                        started = false
                        generation++
                        job = attemptJob
                        attemptJob = null
                        _state.value = ConnectionState.Failed(TOKEN_REJECTED)
                    } else {
                        // The network is fine; only the token was stale. Retry at once.
                        attempt++
                        _state.value = ConnectionState.Reconnecting(attempt)
                        retry = true
                    }
                }

                CloseAction.RECONNECT, CloseAction.REDUCE_BITRATE -> {
                    attempt++
                    _state.value = ConnectionState.Reconnecting(attempt)
                    retry = true
                    retryDelayMs = backoff.nextDelayMs()
                }
            }
        }
        val toCancel = job
        toCancel?.cancel()
        if (retry) launchAttempt(retryDelayMs)
    }

    private fun giveUp(expected: Long, code: String) {
        var job: Job? = null
        synchronized(lock) {
            // A host stop() wins: never publish Failed after Idle.
            if (!started || generation != expected) return
            started = false
            generation++
            job = attemptJob
            attemptJob = null
            _state.value = ConnectionState.Failed(code)
        }
        job?.cancel()
    }

    private fun handleMessage(socket: ControlTransport, expected: Long, text: String) {
        val event = Messages.parseServer(text)
        if (event == null) {
            logger.d(TAG, "dropping malformed server message")
            return
        }
        synchronized(lock) {
            // A superseded socket or a completed stop() must not publish events
            // or state (notably a late hello.ok from the previous connection).
            if (!started || generation != expected || transport !== socket) return
            _events.tryEmit(event)
            if (!started || generation != expected || transport !== socket) return
            when (event.t) {
                "hello.ok" -> {
                    // A healthy handshake starts a new failure episode: the next
                    // token expiry gets its own single refresh, however late it comes.
                    attempt = 0
                    tokenRejections = 0
                    backoff.reset()
                    _state.value = ConnectionState.Online
                }

                "pong" -> lastPongMs = System.currentTimeMillis()
            }
        }
    }

    private fun hello(token: String): String = ClientMessages.hello(
        token = token,
        deviceId = deviceId,
        platform = PLATFORM,
        appVersion = appVersion,
        audioCodecs = listOf(AUDIO_CODEC),
        videoCodecs = listOf(VIDEO_CODEC),
    )

    private fun failureCode(code: Int): String = when (code) {
        4409 -> "room_unavailable"
        1008 -> "duplicate_session"
        else -> CLOSED
    }

    private companion object {
        const val TAG = "ControlClient"
        const val PLATFORM = "android"
        const val AUDIO_CODEC = "pcm16"
        const val VIDEO_CODEC = "h264"
        const val TOKEN_FAILED = "token"
        const val TOKEN_REJECTED = "token_rejected"
        const val CLOSED = "closed"
        const val MAX_TOKEN_REJECTIONS = 2
    }
}
