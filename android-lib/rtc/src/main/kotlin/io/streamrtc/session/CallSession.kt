package io.streamrtc.session

import io.streamrtc.api.CallMedia
import io.streamrtc.api.CallState
import io.streamrtc.api.EndReason
import io.streamrtc.protocol.ClientMessages
import io.streamrtc.protocol.ServerEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The signalling state machine for one 1:1 call.
 *
 * It turns control-plane events into a typed [CallState] and sends the
 * corresponding client messages through [sink]. It is deliberately free of
 * relay, media, and Android types: the session's single-threaded executor owns
 * every call into it, so no locking is needed here. [state] and [token] are
 * flows so the host can collect them from any thread.
 *
 * A new session starts at [CallState.Dialing]; [callId] may be null for an
 * outgoing call until the server names it. An incoming session is moved to
 * [CallState.Ringing] by [onIncoming]. Reaching [CallState.Active] belongs to the
 * session manager when the relay reports `registered`, so `room.token` handling
 * only stores credentials here.
 *
 * [now] supplies wall-clock milliseconds for token bookkeeping, keeping the
 * machine deterministic in tests.
 */
class CallSession(
    callId: String?,
    val peerUserId: String,
    val media: CallMedia,
    private val sink: MessageSink,
    private val now: () -> Long,
) {
    /**
     * The server's call id; null while an outgoing invite is in flight. Once the
     * server names the call (accepted/rejected/ended/token, or [onIncoming]) the
     * id is adopted so later client messages can reference it.
     */
    var callId: String? = callId
        private set

    private val _state = MutableStateFlow<CallState>(CallState.Dialing)
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val _token = MutableStateFlow<RoomToken?>(null)
    val token: StateFlow<RoomToken?> = _token.asStateFlow()

    /** Wall-clock receipt time of the current [token]; 0 before the first. */
    var tokenReceivedAtMs: Long = 0L
        private set

    /** Sends the invite. Only meaningful before any other transition. */
    fun dial(roomId: String) {
        if (_state.value !is CallState.Dialing) return
        sink.send(ClientMessages.invite(peerUserId, roomId, media.audio, media.video))
    }

    /** Marks an incoming invite as ringing; ignored once the call has moved on. */
    fun onIncoming(event: ServerEvent) {
        if (_state.value is CallState.Ended) return
        val id = event.d.stringField("callId") ?: return
        if (!claim(id)) return
        when (_state.value) {
            CallState.Dialing, CallState.Ringing -> _state.value = CallState.Ringing
            else -> Unit
        }
    }

    /** Answers a ringing incoming call and starts waiting for the room token. */
    fun accept() {
        if (_state.value !is CallState.Ringing) return
        val id = callId ?: return
        sink.send(ClientMessages.accept(id))
        _state.value = CallState.Connecting
    }

    /** Declines a ringing incoming call. */
    fun reject(reason: String? = null) {
        if (_state.value !is CallState.Ringing) return
        val id = callId
        if (id != null) sink.send(ClientMessages.reject(id, reason))
        finish(EndReason.Rejected)
    }

    /** Hangs up locally from any live state; terminal and idempotent. */
    fun end(reason: String? = null) {
        if (_state.value is CallState.Ended) return
        val id = callId
        if (id != null) sink.send(ClientMessages.end(id, reason))
        finish(EndReason.LocalEnded)
    }

    /**
     * Applies a control-plane event for this call.
     *
     * Events carrying a `callId` for another call are ignored. Terminal events
     * are idempotent: the first one wins, and everything after it is dropped.
     * `callee_offline` and `error` have no callId in their payloads; they are
     * accepted as belonging to this session, and `callee_offline` is ignored
     * when it names a different callee.
     */
    fun onServerEvent(event: ServerEvent) {
        if (_state.value is CallState.Ended) return
        when (event.t) {
            "call.accepted" -> {
                if (!claimEvent(event)) return
                _state.value = CallState.Connecting
            }

            "room.token" -> {
                // The payload carries no callId: the server addresses it to this
                // participant's connection, so it belongs to this session.
                val d = event.d
                val token = d.stringField("token") ?: return
                val relayUrl = d.stringField("relayUrl") ?: return
                val roomId = d.stringField("roomId") ?: return
                val role = d.stringField("role") ?: return
                val expiresIn = d.intField("expiresIn") ?: return
                _token.value = RoomToken(token, relayUrl, roomId, role, expiresIn)
                tokenReceivedAtMs = now()
            }

            "call.rejected" -> {
                if (!claimEvent(event)) return
                finish(EndReason.Rejected)
            }

            "call.ended" -> {
                if (!claimEvent(event)) return
                val reason = if (event.d.stringField("reason") == "timeout") {
                    EndReason.NoAnswer
                } else {
                    EndReason.RemoteEnded
                }
                finish(reason)
            }

            "callee_offline" -> {
                val callee = event.d.stringField("toUserId")
                if (callee != null && callee != peerUserId) return
                finish(EndReason.CalleeOffline)
            }

            "error" -> {
                val code = event.d.stringField("code") ?: return
                finish(EndReason.Error(code))
            }
        }
    }

    /** Asks the control plane for a fresh room token (Task 18 on a relay 4401). */
    fun refreshToken() {
        if (_state.value is CallState.Ended) return
        val id = callId ?: return
        sink.send(ClientMessages.callToken(id))
    }

    /**
     * Returns true when [event]'s `callId` is this call's. When this call has no
     * id yet (the server has not named an outgoing invite), the event is treated
     * as ours and its id is adopted.
     */
    private fun claimEvent(event: ServerEvent): Boolean {
        val id = event.d.stringField("callId") ?: return false
        return claim(id)
    }

    private fun claim(id: String): Boolean {
        val mine = callId
        if (mine == null) {
            callId = id
            return true
        }
        return mine == id
    }

    private fun finish(reason: EndReason) {
        if (_state.value is CallState.Ended) return
        _state.value = CallState.Ended(reason)
    }
}

private fun JsonObject.stringField(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.intField(name: String): Int? =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
