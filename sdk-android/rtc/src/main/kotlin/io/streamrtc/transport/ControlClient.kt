package io.streamrtc.transport

import io.streamrtc.api.ConnectionState
import io.streamrtc.api.RtcLogger
import io.streamrtc.protocol.ClientMessages
import io.streamrtc.protocol.Messages
import io.streamrtc.protocol.ServerEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
 */
class ControlClient(
    private val transportFactory: () -> ControlTransport,
    private val tokenProvider: suspend () -> String,
    private val scope: CoroutineScope,
    private val deviceId: String,
    private val appVersion: String?,
    private val logger: RtcLogger = RtcLogger.None,
    private val backoff: Backoff = Backoff(),
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    /** Wall-clock receipt time of the most recent `pong`; 0 before the first. */
    @Volatile
    var lastPongMs: Long = 0L
        private set

    @Volatile
    private var started = false

    @Volatile
    private var attemptJob: Job? = null

    @Volatile
    private var transport: ControlTransport? = null

    private val generation = AtomicLong()

    @Volatile
    private var attempt = 0

    @Volatile
    private var tokenRejections = 0

    /** Starts the connection. Repeated calls while running are no-ops. */
    fun start() {
        if (started) return
        started = true
        attempt = 0
        tokenRejections = 0
        _state.value = ConnectionState.Connecting
        launchAttempt(delayMs = null)
    }

    /** Sends a control message; false when no socket is connected. */
    fun send(text: String): Boolean = transport?.send(text) ?: false

    /** Stops reconnecting and closes the socket. Never reports [ConnectionState.Failed]. */
    fun stop() {
        started = false
        generation.incrementAndGet()
        val job = attemptJob
        attemptJob = null
        job?.cancel()
        val socket = transport
        transport = null
        socket?.close()
        _state.value = ConnectionState.Idle
    }

    /**
     * Runs one connection attempt after an optional backoff delay. The job
     * lives until the socket closes or [stop] cancels it.
     */
    private fun launchAttempt(delayMs: Long?) {
        val expected = generation.incrementAndGet()
        attemptJob?.cancel()
        attemptJob = null
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (!started || generation.get() != expected) return@launch
            attemptJob = currentCoroutineContext()[Job]
            if (delayMs != null) delay(delayMs)
            if (!started || generation.get() != expected) return@launch

            val token = try {
                tokenProvider()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "token provider failed", e)
                giveUp(TOKEN_FAILED)
                return@launch
            }
            if (!started || generation.get() != expected) return@launch
            connect(expected, token)
        }
    }

    private suspend fun connect(expected: Long, token: String) {
        val socket = transportFactory()
        transport = socket
        val active = AtomicBoolean(true)
        socket.connect(
            onOpen = {
                if (active.get() && started) socket.send(hello(token))
            },
            onMessage = { text ->
                if (active.get() && started) handleMessage(text)
            },
            onClosed = { code, reason ->
                if (active.compareAndSet(true, false)) {
                    if (transport === socket) transport = null
                    socket.close()
                    onTransportClosed(expected, code, reason)
                }
            },
        )
        try {
            awaitCancellation()
        } finally {
            active.set(false)
            // Close the socket unless a newer attempt already owns it (the
            // factory may return the same object in tests). A stop during
            // connect() still leaves us owning it, so close in that case too.
            val superseded = generation.get() != expected && started
            if (transport === socket && !superseded) {
                transport = null
                socket.close()
            }
        }
    }

    private fun onTransportClosed(expected: Long, code: Int, reason: String) {
        if (!started || generation.get() != expected) return
        when (actionForCloseCode(code)) {
            CloseAction.TERMINAL -> {
                logger.d(TAG, "terminal close $code: $reason")
                giveUp(failureCode(code))
            }

            CloseAction.REFRESH_TOKEN -> {
                tokenRejections++
                if (tokenRejections >= MAX_TOKEN_REJECTIONS) {
                    giveUp(TOKEN_REJECTED)
                    return
                }
                // The network is fine; only the token was stale. Retry at once.
                attempt++
                _state.value = ConnectionState.Reconnecting(attempt)
                launchAttempt(delayMs = null)
            }

            CloseAction.RECONNECT, CloseAction.REDUCE_BITRATE -> {
                attempt++
                _state.value = ConnectionState.Reconnecting(attempt)
                launchAttempt(delayMs = backoff.nextDelayMs())
            }
        }
    }

    private fun giveUp(code: String) {
        started = false
        generation.incrementAndGet()
        val job = attemptJob
        attemptJob = null
        job?.cancel()
        _state.value = ConnectionState.Failed(code)
    }

    private fun handleMessage(text: String) {
        val event = Messages.parseServer(text)
        if (event == null) {
            logger.d(TAG, "dropping malformed server message")
            return
        }
        _events.tryEmit(event)
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
        else -> "closed"
    }

    private companion object {
        const val TAG = "ControlClient"
        const val PLATFORM = "android"
        const val AUDIO_CODEC = "pcm16"
        const val VIDEO_CODEC = "h264"
        const val TOKEN_FAILED = "token"
        const val TOKEN_REJECTED = "token_rejected"
        const val MAX_TOKEN_REJECTIONS = 2
    }
}
