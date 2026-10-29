package io.streamrtc.session

import io.streamrtc.api.EndReason
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorMapperTest {
    @Test fun cameraAndMicFailuresBecomePermissionDenied() {
        assertEquals(EndReason.PermissionDenied, ErrorMapper.toEndReason(SecurityException("mic")))
        assertEquals(EndReason.PermissionDenied, ErrorMapper.toEndReason(IllegalStateException("camera in use")))
    }

    @Test fun networkAndUnknownFailuresKeepTheirIdentity() {
        assertEquals(EndReason.NetworkFailed, ErrorMapper.toEndReason(IOException("reset")))
        assertEquals(
            EndReason.Error("IllegalArgumentException"),
            ErrorMapper.toEndReason(IllegalArgumentException("x")),
        )
    }
}
