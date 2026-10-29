package io.streamrtc.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class H264AnnexBTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun splitsThreeAndFourByteStartCodes() {
        val data = bytes(0,0,0,1, 0x67,1,2, 0,0,1, 0x68,3, 0,0,0,1, 0x65,4,5,6)
        assertEquals(listOf(7, 8, 5), H264AnnexB.nalUnits(data).map { it.type })
    }

    @Test fun doesNotSplitOnEmulationPreventionBytes() {
        val data = bytes(0,0,0,1, 0x65, 0,0,3,1, 0,0,3,2)
        assertEquals(listOf(5), H264AnnexB.nalUnits(data).map { it.type })
    }

    @Test fun extractsSpsAndPpsWithStartCodes() {
        val data = bytes(0,0,0,1, 0x67, 0x42, 0, 0x1e, 0,0,0,1, 0x68, 0xce, 0,0,0,1, 0x65, 9)
        val csd = H264AnnexB.csd(data)!!
        assertEquals(listOf(0,0,0,1,0x67,0x42,0,0x1e), csd.sps.map { it.toInt() and 0xff })
        assertEquals(listOf(0,0,0,1,0x68,0xce), csd.pps.map { it.toInt() and 0xff })
    }

    @Test fun detectsKeyframesAndMissingCsd() {
        assertTrue(H264AnnexB.isKeyframe(bytes(0,0,0,1, 0x65, 1)))
        assertEquals(false, H264AnnexB.isKeyframe(bytes(0,0,0,1, 0x41, 1)))
        assertNull(H264AnnexB.csd(bytes(0,0,0,1, 0x41, 1)))
    }
}
