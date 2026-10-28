package io.streamrtc.transport

/** What a transport should do after the peer closed the socket. */
enum class CloseAction { RECONNECT, REFRESH_TOKEN, TERMINAL, REDUCE_BITRATE }

/**
 * Close-code policy shared by the control and relay transports (spec §7.1).
 *
 * | Code | Meaning | Action |
 * | --- | --- | --- |
 * | 4401 | bad/expired/replayed token | [CloseAction.REFRESH_TOKEN] |
 * | 4400, 4403, 4404, 4405 | invalid token, forbidden, not found, auth failure | [CloseAction.TERMINAL] |
 * | 4409 | room full / video slots full | [CloseAction.TERMINAL] |
 * | 1008 | policy violation, duplicate session | [CloseAction.TERMINAL] |
 * | 1009 | frame too large | [CloseAction.TERMINAL] |
 * | 1013 | slow consumer | [CloseAction.REDUCE_BITRATE] |
 * | 1006, 1011, 1012 | abnormal close, server error, restart | [CloseAction.RECONNECT] |
 *
 * Anything unknown reconnects: a transient network fault is the safe assumption.
 */
fun actionForCloseCode(code: Int): CloseAction = when (code) {
    4401 -> CloseAction.REFRESH_TOKEN
    4400, 4403, 4404, 4405, 4409, 1008, 1009 -> CloseAction.TERMINAL
    1013 -> CloseAction.REDUCE_BITRATE
    else -> CloseAction.RECONNECT
}
