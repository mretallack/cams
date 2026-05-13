package com.vladpen.cams

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vladpen.BackchannelClient
import com.vladpen.G711Encoder
import com.vladpen.RtpPacket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Integration test for RTSP backchannel audio.
 * Uses a mock RTSP server on loopback to verify the full handshake and RTP sending.
 */
@RunWith(AndroidJUnit4::class)
class BackchannelIntegrationTest {

    private val testSdp = """
        v=0
        o=- 1 1 IN IP4 127.0.0.1
        s=test
        t=0 0
        m=video 0 RTP/AVP 96
        a=rtpmap:96 H264/90000
        a=control:track1
        m=audio 0 RTP/AVP 0
        a=rtpmap:0 PCMU/8000/1
        a=control:track2
        a=sendonly
    """.trimIndent()

    @Test
    fun fullHandshakeWithMockServer() {
        val serverSocket = ServerSocket(0) // Random available port
        val port = serverSocket.localPort
        val receivedRtp = mutableListOf<ByteArray>()

        // Mock RTSP server
        val serverThread = thread {
            val client = serverSocket.accept()
            val input = client.getInputStream()
            val output = client.getOutputStream()
            val buffer = ByteArray(4096)

            // Read DESCRIBE (no auth)
            val n1 = input.read(buffer)
            val describe1 = String(buffer, 0, n1)
            assertTrue(describe1.contains("DESCRIBE"))
            assertTrue(describe1.contains("www.onvif.org/ver20/backchannel"))

            // Send 401
            val resp401 = "RTSP/1.0 401 Unauthorized\r\nCSeq: 1\r\nWWW-Authenticate: Digest realm=\"test\", nonce=\"abc123\"\r\n\r\n"
            output.write(resp401.toByteArray())

            // Read DESCRIBE with auth
            val n2 = input.read(buffer)
            val describe2 = String(buffer, 0, n2)
            assertTrue(describe2.contains("Authorization"))
            assertTrue(describe2.contains("Digest"))

            // Send 200 with SDP
            val sdpBytes = testSdp.toByteArray()
            val resp200 = "RTSP/1.0 200 OK\r\nCSeq: 2\r\nContent-Type: application/sdp\r\nContent-Length: ${sdpBytes.size}\r\n\r\n"
            output.write(resp200.toByteArray())
            output.write(sdpBytes)

            // Read SETUP
            val n3 = input.read(buffer)
            val setup = String(buffer, 0, n3)
            assertTrue(setup.contains("SETUP"))
            assertTrue(setup.contains("interleaved"))

            // Send 401 for SETUP
            val setupResp401 = "RTSP/1.0 401 Unauthorized\r\nCSeq: 3\r\nWWW-Authenticate: Digest realm=\"test\", nonce=\"abc123\"\r\n\r\n"
            output.write(setupResp401.toByteArray())

            // Read SETUP with auth
            val n3b = input.read(buffer)
            val setup2 = String(buffer, 0, n3b)
            assertTrue(setup2.contains("Authorization"))

            // Send 200 for SETUP
            val setupResp = "RTSP/1.0 200 OK\r\nCSeq: 4\r\nSession: TEST123\r\nTransport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n\r\n"
            output.write(setupResp.toByteArray())

            // Read PLAY
            val n4 = input.read(buffer)
            val play = String(buffer, 0, n4)
            assertTrue(play.contains("PLAY"))

            // Send 401 for PLAY
            val playResp401 = "RTSP/1.0 401 Unauthorized\r\nCSeq: 5\r\nWWW-Authenticate: Digest realm=\"test\", nonce=\"abc123\"\r\n\r\n"
            output.write(playResp401.toByteArray())

            // Read PLAY with auth
            val n4b = input.read(buffer)
            val play2 = String(buffer, 0, n4b)
            assertTrue(play2.contains("Authorization"))

            // Send 200 for PLAY
            val playResp = "RTSP/1.0 200 OK\r\nCSeq: 6\r\nSession: TEST123\r\n\r\n"
            output.write(playResp.toByteArray())

            // Read RTP packets (interleaved)
            Thread.sleep(200)
            while (input.available() > 0) {
                val rtpBuf = ByteArray(4096)
                val rtpN = input.read(rtpBuf)
                if (rtpN > 0) {
                    receivedRtp.add(rtpBuf.copyOf(rtpN))
                }
            }

            client.close()
        }

        // Client side
        Thread.sleep(100) // Let server start
        val client = BackchannelClient()
        val started = client.start("127.0.0.1", port, "/ch0", "user", "pass")
        assertTrue("Backchannel start failed", started)

        // Send some test audio
        val testAudio = G711Encoder.encodeUlaw(ShortArray(160) { 1000 })
        client.sendAudio(testAudio)
        client.sendAudio(testAudio)

        Thread.sleep(300)
        client.stop()
        serverThread.join(2000)
        serverSocket.close()

        // Verify RTP was received
        assertTrue("No RTP data received", receivedRtp.isNotEmpty())

        // Verify first RTP frame structure
        val firstFrame = receivedRtp[0]
        assertEquals(0x24.toByte(), firstFrame[0]) // '$' interleaved marker
        assertEquals(0x00.toByte(), firstFrame[1]) // channel 0
    }

    @Test
    fun detectBackchannelWithMockServer() {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val serverThread = thread {
            val client = serverSocket.accept()
            val input = client.getInputStream()
            val output = client.getOutputStream()
            val buffer = ByteArray(4096)

            // Read DESCRIBE
            input.read(buffer)
            // Send 401
            output.write("RTSP/1.0 401 Unauthorized\r\nCSeq: 1\r\nWWW-Authenticate: Digest realm=\"test\", nonce=\"nonce1\"\r\n\r\n".toByteArray())

            // Read DESCRIBE with auth
            input.read(buffer)
            // Send 200 with SDP
            val sdpBytes = testSdp.toByteArray()
            output.write("RTSP/1.0 200 OK\r\nCSeq: 2\r\nContent-Length: ${sdpBytes.size}\r\n\r\n".toByteArray())
            output.write(sdpBytes)

            Thread.sleep(500)
            client.close()
        }

        Thread.sleep(100)
        val client = BackchannelClient()
        val track = client.detectBackchannel("127.0.0.1", port, "/ch0", "user", "pass")

        assertNotNull("Should detect backchannel", track)
        assertEquals(BackchannelClient.Codec.PCMU, track!!.codec)
        assertEquals(0, track.payloadType)
        assertEquals(8000, track.sampleRate)

        serverThread.join(2000)
        serverSocket.close()
    }
}
