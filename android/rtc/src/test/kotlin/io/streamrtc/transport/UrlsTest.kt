package io.streamrtc.transport

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The media relay is mounted at `/relay` on the same host as the control plane
 * (`backend/packages/control-plane/src/combined.ts` routes `/ws` upgrades to
 * control and everything else media-relay-bound); room sockets must therefore
 * derive the relay URL from the host-facing control base URL instead of trying
 * to upgrade on the control plane.
 */
class UrlsTest {
    @Test fun httpsBaseUrlBecomesWssRelayUrl() {
        assertEquals("wss://streamrtc-relay.onrender.com/relay", relaySocketUrl("https://streamrtc-relay.onrender.com"))
    }

    @Test fun httpBaseUrlBecomesWsRelayUrl() {
        assertEquals("ws://10.0.2.2:8080/relay", relaySocketUrl("http://10.0.2.2:8080/"))
    }

    @Test fun existingRelaySuffixIsKept() {
        assertEquals("wss://host/relay", relaySocketUrl("wss://host/relay"))
    }

    @Test fun trailingSlashIsTrimmedBeforeTheSuffix() {
        assertEquals("wss://host/relay", relaySocketUrl("wss://host/"))
    }

    @Test fun explicitWsAndHttpsSchemeAlmostEqualUrlIsMapped() {
        assertEquals("wss://host/relay", relaySocketUrl("https://host"))
    }
}
