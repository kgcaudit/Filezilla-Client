package org.filezilla.ftp.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ServerCapabilitiesTest {

    private val caps = ServerCapabilities()
    private val server = ServerCapabilities.ServerKey("ftp.example.org", 21, "alice")

    @Test
    fun `unknown by default`() {
        assertEquals(Capability.UNKNOWN, caps.get(server, CapabilityName.REST_STREAM))
    }

    @Test
    fun `remembers a set capability per server`() {
        caps.set(server, CapabilityName.RESUME_4GB_BUG, Capability.YES)
        assertEquals(Capability.YES, caps.get(server, CapabilityName.RESUME_4GB_BUG))

        val other = server.copy(host = "ftp.other.org")
        assertEquals(Capability.UNKNOWN, caps.get(other, CapabilityName.RESUME_4GB_BUG))
    }

    @Test
    fun `applies a FEAT reply`() {
        caps.applyFeatures(
            server,
            listOf(
                " SIZE", " MDTM", " MFMT", " REST STREAM", " TVFS", " EPSV", " UTF8",
                " MLST type*;size*;modify*;", " MODE Z",
            ),
        )
        assertEquals(Capability.YES, caps.get(server, CapabilityName.SIZE_COMMAND))
        assertEquals(Capability.YES, caps.get(server, CapabilityName.MDTM_COMMAND))
        assertEquals(Capability.YES, caps.get(server, CapabilityName.MFMT_COMMAND))
        assertEquals(Capability.YES, caps.get(server, CapabilityName.REST_STREAM))
        assertEquals(Capability.YES, caps.get(server, CapabilityName.TVFS_SUPPORT))
        assertEquals(Capability.YES, caps.get(server, CapabilityName.EPSV_COMMAND))
        assertEquals(Capability.YES, caps.get(server, CapabilityName.UTF8_COMMAND))
        assertEquals(Capability.YES, caps.get(server, CapabilityName.MODE_Z_SUPPORT))
        assertEquals(Capability.YES, caps.get(server, CapabilityName.FEAT_COMMAND))
    }

    @Test
    fun `MLST carries its fact list and suppresses timezone guessing`() {
        caps.applyFeatures(server, listOf(" MLST type*;size*;modify*;"))
        val v = caps.getValue(server, CapabilityName.MLSD_COMMAND)
        assertEquals(Capability.YES, v.capability)
        assertEquals("type*;size*;modify*;", v.textOption)
        assertEquals(Capability.NO, caps.get(server, CapabilityName.INFERRED_TIMEZONE_OFFSET))
    }

    @Test
    fun `a server without REST STREAM stays unknown so uploads fall back to APPE`() {
        caps.applyFeatures(server, listOf(" SIZE", " MDTM"))
        assertEquals(Capability.UNKNOWN, caps.get(server, CapabilityName.REST_STREAM))
    }
}
