package io.streamrtc

import android.content.Context
import android.provider.Settings
import io.streamrtc.api.Call
import io.streamrtc.api.CallMedia
import io.streamrtc.api.Calls
import io.streamrtc.api.ConnectionState
import io.streamrtc.api.IncomingCall
import io.streamrtc.api.RtcLogger
import io.streamrtc.api.StreamRtcConfig
import io.streamrtc.media.audio.DefaultAudioDevice
import io.streamrtc.service.CallServiceControl
import io.streamrtc.service.CallServiceHooks
import io.streamrtc.session.SessionManager
import io.streamrtc.transport.ControlClient
import io.streamrtc.transport.OkHttpControlTransport
import io.streamrtc.transport.OkHttpRelayTransport
import io.streamrtc.transport.RelayClient
import io.streamrtc.util.AndroidLogger
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * The SDK entry point: owns the transport stack, the audio device and the
 * session manager for the process.
 *
 * ```kotlin
 * val rtc = StreamRtc.get(context, StreamRtcConfig(
 *     controlUrl = "https://rtc.example.com",
 *     tokenProvider = { api.sessionToken() },   // called off the main thread
 *     displayName = "Ada",
 * ))
 * rtc.start()
 * val call = rtc.calls.dial("bob")
 * ```
 *
 * [get] returns one instance per process; the first configuration wins (a later
 * call with a different configuration is ignored and logged). [start] is
 * idempotent; [stop] shuts the stack down and the instance is not reusable —
 * call [get] again for a fresh one.
 *
 * The SDK runs its work on one confined Default dispatcher, never on the main
 * thread: `tokenProvider` and all signalling callbacks are invoked off-main, and
 * the public API only mutates conflated flows.
 */
class StreamRtc private constructor(
    private val context: Context,
    private val config: StreamRtcConfig,
) {
    private val logger: RtcLogger = AndroidLogger()

    // Two clients per spec §7.1: shared dispatcher and connection pool, one
    // socket family each, so a stalled relay never delays signalling.
    private val controlHttp = OkHttpClient.Builder()
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val relayHttp = controlHttp.newBuilder().build()

    private val deviceId: String by lazy { readDeviceId() }

    private val lock = Any()
    private var sessionScope: CoroutineScope? = null
    private var manager: SessionManager? = null

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)

    /** The control-plane lifecycle, mirrored from [ControlClient]. */
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _active = MutableStateFlow<Call?>(null)
    private val _incoming = MutableSharedFlow<IncomingCall>(extraBufferCapacity = 8)

    /** The 1:1 calling surface. */
    val calls: Calls = FacadeCalls()

    /**
     * Opens the control socket. [scope] optionally supplies the parent job: if
     * the host cancels it, the SDK's work is cancelled too. The SDK still runs
     * its callbacks on its own confined Default dispatcher, never on the host's
     * dispatcher (in particular never on main).
     */
    fun start(scope: CoroutineScope? = null) {
        synchronized(lock) {
            if (manager != null) {
                manager?.start()
                return
            }

            val parent = scope?.coroutineContext?.get(Job) ?: SupervisorJob()
            val errors = CoroutineExceptionHandler { _, t ->
                logger.e(TAG, "session scope failure", t)
            }
            val dispatcher = Dispatchers.Default.limitedParallelism(1)
            val sessionScope = CoroutineScope(parent + dispatcher + errors)
            this.sessionScope = sessionScope

            val audio = DefaultAudioDevice(context, logger) {
                manager?.onFocusLost()
            }
            // ControlClient's first fetch starts UNDISPATCHED, i.e. on the
            // thread that called start(). Hop to the session dispatcher first so
            // a host token provider never runs inline on the caller (main)
            // thread, per the Task 7 threading ruling.
            val control = ControlClient(
                transportFactory = {
                    OkHttpControlTransport(controlHttp, controlSocketUrl(config.controlUrl))
                },
                tokenProvider = { withContext(dispatcher) { config.tokenProvider() } },
                scope = sessionScope,
                deviceId = deviceId,
                appVersion = config.appVersion,
                logger = logger,
            )
            val manager = SessionManager(
                config = config,
                control = control,
                relayFactory = {
                    RelayClient(
                        transportFactory = { url -> OkHttpRelayTransport(relayHttp, url) },
                        scope = sessionScope,
                        logger = logger,
                    )
                },
                audio = audio,
                service = CallServiceControl(context),
                scope = sessionScope,
                clock = { System.currentTimeMillis() },
                logger = logger,
            )
            this.manager = manager

            // The service's notification actions end and mute through these
            // callbacks; they are the only Android-to-manager bridge.
            CallServiceHooks.onEnd = { manager.endAll() }
            CallServiceHooks.onToggleMute = { manager.toggleMute() }

            sessionScope.launch(Dispatchers.Unconfined) {
                control.state.collect { _connection.value = it }
            }
            sessionScope.launch(Dispatchers.Unconfined) {
                manager.active.collect { _active.value = it }
            }
            sessionScope.launch(Dispatchers.Unconfined) {
                manager.incoming.collect { _incoming.tryEmit(it) }
            }

            manager.start()
        }
    }

    /**
     * Stops the call, closes the control socket and releases the audio device.
     * The instance is not restartable; call [get] again for a new one.
     */
    fun stop() {
        synchronized(lock) {
            CallServiceHooks.onEnd = null
            CallServiceHooks.onToggleMute = null

            val manager = this.manager
            this.manager = null
            runCatching { manager?.stop() }.onFailure {
                logger.e(TAG, "session manager stop failed", it)
            }
            sessionScope?.cancel()
            sessionScope = null

            _connection.value = ConnectionState.Idle
            _active.value = null
        }
    }

    private inner class FacadeCalls : Calls {
        override val incoming: SharedFlow<IncomingCall> = _incoming.asSharedFlow()
        override val active: StateFlow<Call?> = _active.asStateFlow()

        override suspend fun dial(userId: String, media: CallMedia): Call {
            val manager = synchronized(lock) { this@StreamRtc.manager }
                ?: throw IllegalStateException("StreamRtc.start() must be called before dialing")
            return manager.dial(userId, media)
        }
    }

    private fun readDeviceId(): String {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        return androidId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
    }

    /** Maps the host-facing base URL to its websocket endpoint (`…/ws`). */
    private fun controlSocketUrl(base: String): String {
        val socket = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> base
        }
        val trimmed = socket.trimEnd('/')
        return if (trimmed.endsWith("/ws")) trimmed else "$trimmed/ws"
    }

    companion object {
        private const val TAG = "StreamRtc"
        private const val PING_INTERVAL_SECONDS = 20L

        @Volatile
        private var instance: StreamRtc? = null

        /** Returns the process-wide SDK instance, creating it on first use. */
        @JvmStatic
        fun get(context: Context, config: StreamRtcConfig): StreamRtc {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: StreamRtc(context.applicationContext, config).also { instance = it }
            }
        }
    }
}
