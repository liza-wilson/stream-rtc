package io.streamrtc.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class CloseCodesTest {
    @Test
    fun mapsRelayAndControlCloses() {
        assertEquals(CloseAction.REFRESH_TOKEN, actionForCloseCode(4401))
        assertEquals(CloseAction.TERMINAL, actionForCloseCode(4409))
        assertEquals(CloseAction.TERMINAL, actionForCloseCode(1008))
        assertEquals(CloseAction.TERMINAL, actionForCloseCode(1009))
        assertEquals(CloseAction.REDUCE_BITRATE, actionForCloseCode(1013))
        assertEquals(CloseAction.RECONNECT, actionForCloseCode(1011))
        assertEquals(CloseAction.RECONNECT, actionForCloseCode(1006))

        assertEquals(CloseAction.TERMINAL, actionForCloseCode(4400))
        assertEquals(CloseAction.TERMINAL, actionForCloseCode(4403))
        assertEquals(CloseAction.TERMINAL, actionForCloseCode(4404))
        assertEquals(CloseAction.TERMINAL, actionForCloseCode(4405))
        assertEquals(CloseAction.RECONNECT, actionForCloseCode(1012))
        assertEquals(CloseAction.RECONNECT, actionForCloseCode(4321))
    }
}
