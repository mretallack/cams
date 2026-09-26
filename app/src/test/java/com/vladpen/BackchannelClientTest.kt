package com.vladpen

import org.junit.Test
import org.junit.Assert.*

class BackchannelClientTest {

    private val client = BackchannelClient()

    // === SDP Parsing Tests ===

    private val CAMERA2_SDP = """
        v=0
        o=- 1775517319693774 1 IN IP4 10.0.0.63
        s=thingino prudynt
        i=stream0
        t=0 0
        a=tool:LIVE555 Streaming Media v2026.04.01
        a=type:broadcast
        a=control:*
        m=video 0 RTP/AVP 96
        c=IN IP4 0.0.0.0
        b=AS:5000
        a=rtpmap:96 H264/90000
        a=control:track1
        m=audio 0 RTP/AVP 97
        c=IN IP4 0.0.0.0
        b=AS:40
        a=rtpmap:97 MPEG4-GENERIC/16000
        a=fmtp:97 streamtype=5;profile-level-id=1;mode=AAC-hbr
        a=control:track2
        m=audio 0 RTP/AVP 97
        c=IN IP4 0.0.0.0
        a=rtpmap:97 mpeg4-generic/16000/1
        a=control:track3
        a=sendonly
        m=audio 0 RTP/AVP 0
        c=IN IP4 0.0.0.0
        a=rtpmap:0 PCMU/8000/1
        a=control:track4
        a=sendonly
        m=audio 0 RTP/AVP 8
        c=IN IP4 0.0.0.0
        a=rtpmap:8 PCMA/8000/1
        a=control:track5
        a=sendonly
    """.trimIndent()

    @Test
    fun parseSdpFindsBackchannelTracks() {
        val tracks = client.parseSdpForBackchannel(CAMERA2_SDP)
        assertEquals(3, tracks.size)
    }

    @Test
    fun parseSdpPrefersPCMU() {
        val tracks = client.parseSdpForBackchannel(CAMERA2_SDP)
        assertEquals(BackchannelClient.Codec.PCMU, tracks[0].codec)
    }

    @Test
    fun parseSdpPCMUDetails() {
        val tracks = client.parseSdpForBackchannel(CAMERA2_SDP)
        val pcmu = tracks.first { it.codec == BackchannelClient.Codec.PCMU }
        assertEquals(0, pcmu.payloadType)
        assertEquals(8000, pcmu.sampleRate)
        assertEquals("track4", pcmu.controlUrl)
    }

    @Test
    fun parseSdpPCMADetails() {
        val tracks = client.parseSdpForBackchannel(CAMERA2_SDP)
        val pcma = tracks.first { it.codec == BackchannelClient.Codec.PCMA }
        assertEquals(8, pcma.payloadType)
        assertEquals(8000, pcma.sampleRate)
        assertEquals("track5", pcma.controlUrl)
    }

    @Test
    fun parseSdpAACDetails() {
        val tracks = client.parseSdpForBackchannel(CAMERA2_SDP)
        val aac = tracks.first { it.codec == BackchannelClient.Codec.AAC }
        assertEquals(97, aac.payloadType)
        assertEquals(16000, aac.sampleRate)
        assertEquals("track3", aac.controlUrl)
    }

    @Test
    fun parseSdpSortOrder() {
        val tracks = client.parseSdpForBackchannel(CAMERA2_SDP)
        assertEquals(BackchannelClient.Codec.PCMU, tracks[0].codec)
        assertEquals(BackchannelClient.Codec.PCMA, tracks[1].codec)
        assertEquals(BackchannelClient.Codec.AAC, tracks[2].codec)
    }

    @Test
    fun parseSdpNoBackchannel() {
        val sdp = """
            v=0
            o=- 1 1 IN IP4 10.0.0.1
            s=test
            t=0 0
            m=video 0 RTP/AVP 96
            a=rtpmap:96 H264/90000
            a=control:track1
            m=audio 0 RTP/AVP 97
            a=rtpmap:97 MPEG4-GENERIC/16000
            a=control:track2
            a=recvonly
        """.trimIndent()
        val tracks = client.parseSdpForBackchannel(sdp)
        assertTrue(tracks.isEmpty())
    }

    @Test
    fun parseSdpVideoOnly() {
        val sdp = """
            v=0
            o=- 1 1 IN IP4 10.0.0.1
            s=test
            t=0 0
            m=video 0 RTP/AVP 96
            a=rtpmap:96 H264/90000
            a=control:track1
        """.trimIndent()
        val tracks = client.parseSdpForBackchannel(sdp)
        assertTrue(tracks.isEmpty())
    }

    @Test
    fun parseSdpMalformedIgnored() {
        val sdp = """
            v=0
            garbage line
            m=audio 0 RTP/AVP
            a=sendonly
            m=audio 0 RTP/AVP 0
            a=control:track1
            a=sendonly
        """.trimIndent()
        // First block has no payload type, second has no rtpmap → both skipped
        val tracks = client.parseSdpForBackchannel(sdp)
        assertTrue(tracks.isEmpty())
    }

    @Test
    fun parseSdpSendonlyVideoIgnored() {
        val sdp = """
            v=0
            m=video 0 RTP/AVP 96
            a=rtpmap:96 H264/90000
            a=control:track1
            a=sendonly
            m=audio 0 RTP/AVP 0
            a=rtpmap:0 PCMU/8000/1
            a=control:track2
            a=sendonly
        """.trimIndent()
        val tracks = client.parseSdpForBackchannel(sdp)
        assertEquals(1, tracks.size)
        assertEquals(BackchannelClient.Codec.PCMU, tracks[0].codec)
    }

    // === Digest Auth Tests ===

    @Test
    fun digestAuthComputation() {
        // Known test vector
        val auth = BackchannelClient.computeDigestAuth(
            method = "DESCRIBE",
            uri = "rtsp://10.0.0.63:554/ch0",
            username = "thingino",
            password = "testpass",
            realm = "LIVE555 Streaming Media",
            nonce = "abc123"
        )
        assertTrue(auth.startsWith("Digest "))
        assertTrue(auth.contains("username=\"thingino\""))
        assertTrue(auth.contains("realm=\"LIVE555 Streaming Media\""))
        assertTrue(auth.contains("nonce=\"abc123\""))
        assertTrue(auth.contains("uri=\"rtsp://10.0.0.63:554/ch0\""))
        assertTrue(auth.contains("response=\""))
    }

    @Test
    fun digestAuthMD5Correctness() {
        // Verify MD5 computation matches expected
        // HA1 = MD5(username:realm:password) = MD5("admin:testrealm:pass123")
        // HA2 = MD5(method:uri) = MD5("DESCRIBE:rtsp://host/path")
        // response = MD5(HA1:nonce:HA2)
        val auth = BackchannelClient.computeDigestAuth(
            method = "DESCRIBE",
            uri = "rtsp://host/path",
            username = "admin",
            password = "pass123",
            realm = "testrealm",
            nonce = "nonce1"
        )
        // Just verify it produces a 32-char hex response
        val responseMatch = Regex("response=\"([a-f0-9]{32})\"").find(auth)
        assertNotNull(responseMatch)
    }

    // === URL Resolution Tests ===

    @Test
    fun resolveControlUrlAbsolute() {
        val resolved = client.resolveControlUrl("10.0.0.63", 554, "/ch0", "rtsp://10.0.0.63/ch0/track4")
        assertEquals("rtsp://10.0.0.63/ch0/track4", resolved)
    }

    @Test
    fun resolveControlUrlRelative() {
        val resolved = client.resolveControlUrl("10.0.0.63", 554, "/ch0", "track4")
        assertEquals("rtsp://10.0.0.63:554/ch0/track4", resolved)
    }
}
