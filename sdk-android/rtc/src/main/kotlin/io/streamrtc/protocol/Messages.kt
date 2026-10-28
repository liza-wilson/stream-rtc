package io.streamrtc.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A server-originated message: a type tag plus its payload object. */
data class ServerEvent(val t: String, val d: JsonObject)

/** Parsing of server messages and encoding of client messages. */
object Messages {
    const val MAX_MESSAGE_BYTES = 64 * 1024

    /**
     * Parses a control-plane/server message. Returns null when the text exceeds
     * [MAX_MESSAGE_BYTES] UTF-8 bytes, is not a JSON object, or has no non-empty
     * string `t`. A missing `d` defaults to an empty object; a non-object `d` is
     * rejected, matching the server schema.
     */
    fun parseServer(text: String): ServerEvent? {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_BYTES) return null
        val obj = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            return null
        }
        val tElement = obj["t"] as? JsonPrimitive ?: return null
        if (!tElement.isString) return null
        val t = tElement.content
        if (t.isEmpty()) return null
        val d = when (val dElement = obj["d"]) {
            null -> JsonObject(emptyMap())
            is JsonObject -> dElement
            else -> return null
        }
        return ServerEvent(t, d)
    }
}

/** Builders for the client-to-server messages accepted by the zod schemas. */
object ClientMessages {
    fun hello(
        token: String,
        deviceId: String,
        platform: String,
        appVersion: String? = null,
        audioCodecs: List<String>,
        videoCodecs: List<String>,
    ): String = buildJsonObject {
        put("t", "hello")
        putJsonObject("d") {
            put("token", token)
            put("deviceId", deviceId)
            put("platform", platform)
            if (appVersion != null) put("appVersion", appVersion)
            putJsonObject("codecs") {
                putJsonArray("audio") { audioCodecs.forEach { add(it) } }
                putJsonArray("video") { videoCodecs.forEach { add(it) } }
            }
        }
    }.toString()

    fun invite(
        toUserId: String,
        roomId: String,
        audio: Boolean,
        video: Boolean,
    ): String = buildJsonObject {
        put("t", "call.invite")
        putJsonObject("d") {
            put("toUserId", toUserId)
            put("roomId", roomId)
            putJsonObject("media") {
                put("audio", audio)
                put("video", video)
            }
        }
    }.toString()

    fun accept(callId: String, reason: String? = null): String =
        callRef("call.accept", callId, reason)

    fun reject(callId: String, reason: String? = null): String =
        callRef("call.reject", callId, reason)

    fun end(callId: String, reason: String? = null): String =
        callRef("call.end", callId, reason)

    fun callToken(callId: String): String = buildJsonObject {
        put("t", "call.token")
        putJsonObject("d") { put("callId", callId) }
    }.toString()

    /**
     * The media relay reads the room token at the top level:
     * `{"t":"register","token":"<room token>"}`.
     */
    fun register(token: String): String = buildJsonObject {
        put("t", "register")
        put("token", token)
    }.toString()

    fun keyframe(slot: Int): String = buildJsonObject {
        put("t", "keyframe")
        putJsonObject("d") { put("slot", slot) }
    }.toString()

    fun ping(ts: Long = System.currentTimeMillis()): String = buildJsonObject {
        put("t", "ping")
        putJsonObject("d") { put("ts", ts) }
    }.toString()

    private fun callRef(t: String, callId: String, reason: String?): String =
        buildJsonObject {
            put("t", t)
            putJsonObject("d") {
                put("callId", callId)
                if (reason != null) put("reason", reason)
            }
        }.toString()
}
