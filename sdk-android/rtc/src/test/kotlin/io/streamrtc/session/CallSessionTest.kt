package io.streamrtc.session

import io.streamrtc.api.CallMedia
import io.streamrtc.api.CallState
import io.streamrtc.api.EndReason
import io.streamrtc.protocol.Messages
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSessionTest {
    private val sink = RecordingSink()
    private fun session(callId: String? = "c1") =
        CallSession(callId, "peer", CallMedia.audioVideo, sink, now = { 1_000 })

    private fun event(json: String) = Messages.parseServer(json)!!

    @Test fun dialSendsInviteAndSurfacesCalleeOffline() = runTest {
        val s = session(callId = null)
        s.dial("r1")
        assertEquals(CallState.Dialing, s.state.value)
        assertTrue(sink.single().contains("\"t\":\"call.invite\""))
        s.onServerEvent(event("""{"t":"callee_offline","d":{"toUserId":"peer"}}"""))
        assertEquals(CallState.Ended(EndReason.CalleeOffline), s.state.value)
    }

    @Test fun callerMovesToConnectingOnAcceptedThenReceivesAToken() = runTest {
        val s = session()
        s.dial("r1")
        s.onServerEvent(event("""{"t":"call.accepted","d":{"callId":"c1","byUserId":"peer","roomId":"r1"}}"""))
        assertEquals(CallState.Connecting, s.state.value)
        s.onServerEvent(event("""{"t":"room.token","d":{"token":"jwt","relayUrl":"wss://r","roomId":"r1","role":"host","expiresIn":300,"peers":[]}}"""))
        assertEquals("jwt", s.token.value?.token)
        assertEquals("host", s.token.value?.role)
    }

    @Test fun calleeAcceptsAndRejects() = runTest {
        val s = session()
        s.onIncoming(event("""{"t":"call.incoming","d":{"callId":"c1","fromUserId":"peer","roomId":"r1","media":{"audio":true,"video":true}}}"""))
        assertEquals(CallState.Ringing, s.state.value)
        s.accept()
        assertTrue(sink.single().contains("\"t\":\"call.accept\""))
        assertEquals(CallState.Connecting, s.state.value)

        val other = session()
        other.onIncoming(event("""{"t":"call.incoming","d":{"callId":"c1","fromUserId":"peer","roomId":"r1","media":{"audio":true,"video":true}}}"""))
        other.reject("busy")
        assertEquals(CallState.Ended(EndReason.Rejected), other.state.value)
    }

    @Test fun remoteEndWins() = runTest {
        val s = session()
        s.dial("r1")
        s.onServerEvent(event("""{"t":"call.ended","d":{"callId":"c1","reason":"ended"}}"""))
        assertEquals(CallState.Ended(EndReason.RemoteEnded), s.state.value)
    }

    @Test fun errorsMapToTypedEndReasons() = runTest {
        val s = session(callId = null)
        s.dial("r1")
        s.onServerEvent(event("""{"t":"error","d":{"code":"self_call"}}"""))
        assertEquals(CallState.Ended(EndReason.Error("self_call")), s.state.value)
    }

    private class RecordingSink : MessageSink {
        val sent = mutableListOf<String>()
        override fun send(text: String): Boolean { sent.add(text); return true }
        fun single() = sent.single()
    }
}
