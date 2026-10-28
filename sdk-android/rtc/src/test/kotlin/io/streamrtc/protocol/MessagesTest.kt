package io.streamrtc.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessagesTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun objOf(text: String) = obj(text).getValue("d").jsonObject

    @Test fun parsesHelloOkAndRoomToken() {
        val ok = Messages.parseServer("""{"t":"hello.ok","d":{"userId":"u1","appId":"a1","serverTime":5}}""")!!
        assertEquals("hello.ok", ok.t)
        assertEquals("u1", ok.d.getValue("userId").jsonPrimitive.content)

        val token = Messages.parseServer("""{"t":"room.token","d":{"token":"jwt","relayUrl":"wss://r","roomId":"r1","role":"host","expiresIn":300,"peers":[]}}""")!!
        assertEquals("wss://r", token.d.getValue("relayUrl").jsonPrimitive.content)
    }

    @Test fun returnsNullForGarbageUnknownAndOversizedMessages() {
        assertNull(Messages.parseServer("not json"))
        assertNull(Messages.parseServer("""{"t":""}"""))
        assertNull(Messages.parseServer("""{"d":{}}"""))
        assertNull(Messages.parseServer("""{"t":"pong","d":{"pad":"${"x".repeat(70_000)}"}}"""))
    }

    @Test fun buildsClientMessagesWithTheExactSchema() {
        val hello = obj(ClientMessages.hello("tok", "device-1", "android", "1.0", listOf("pcm16"), listOf("h264")))
        assertEquals("hello", hello.getValue("t").jsonPrimitive.content)
        assertEquals("tok", objOf(hello.toString()).getValue("token").jsonPrimitive.content)
        assertEquals("android", objOf(hello.toString()).getValue("platform").jsonPrimitive.content)

        val invite = obj(ClientMessages.invite("u2", "r1", audio = true, video = false))
        assertEquals("call.invite", invite.getValue("t").jsonPrimitive.content)
        val media = objOf(invite.toString()).getValue("media").jsonObject
        assertEquals(false, media.getValue("video").jsonPrimitive.boolean)

        assertEquals("call.token", obj(ClientMessages.callToken("c1")).getValue("t").jsonPrimitive.content)
        assertEquals(3, objOf(ClientMessages.keyframe(3)).getValue("slot").jsonPrimitive.int)
        assertEquals("register", obj(ClientMessages.register("jwt")).getValue("t").jsonPrimitive.content)
    }
}
