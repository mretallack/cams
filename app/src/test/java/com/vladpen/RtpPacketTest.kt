package com.vladpen

import org.junit.Test
import org.junit.Assert.*

class RtpPacketTest {

    @Test
    fun headerVersion() {
        val rtp = RtpPacket(payloadType = 0, ssrc = 1234)
        val packet = rtp.build(ByteArray(160))
        // V=2 (bits 6-7 of byte 0)
        assertEquals(2, (packet[0].toInt() and 0xFF) shr 6)
    }

    @Test
    fun headerPayloadTypePCMU() {
        val rtp = RtpPacket(payloadType = 0, ssrc = 1234)
        val packet = rtp.build(ByteArray(160))
        assertEquals(0, packet[1].toInt() and 0x7F)
    }

    @Test
    fun headerPayloadTypePCMA() {
        val rtp = RtpPacket(payloadType = 8, ssrc = 1234)
        val packet = rtp.build(ByteArray(160))
        assertEquals(8, packet[1].toInt() and 0x7F)
    }

    @Test
    fun sequenceNumberIncrement() {
        val rtp = RtpPacket(payloadType = 0, ssrc = 1234)
        val p1 = rtp.build(ByteArray(160))
        val p2 = rtp.build(ByteArray(160))
        val seq1 = ((p1[2].toInt() and 0xFF) shl 8) or (p1[3].toInt() and 0xFF)
        val seq2 = ((p2[2].toInt() and 0xFF) shl 8) or (p2[3].toInt() and 0xFF)
        assertEquals(0, seq1)
        assertEquals(1, seq2)
    }

    @Test
    fun sequenceNumberWrapsAt65535() {
        val rtp = RtpPacket(payloadType = 0, ssrc = 1234)
        // Build 65535 packets to get to max
        repeat(65535) { rtp.build(ByteArray(1)) }
        assertEquals(65535, rtp.getSequenceNumber())
        // Next packet should wrap to 0
        rtp.build(ByteArray(1))
        assertEquals(0, rtp.getSequenceNumber())
    }

    @Test
    fun timestampIncrementByPayloadSize() {
        val rtp = RtpPacket(payloadType = 0, ssrc = 1234)
        val p1 = rtp.build(ByteArray(160))
        val ts1 = ((p1[4].toInt() and 0xFF).toLong() shl 24) or
                  ((p1[5].toInt() and 0xFF).toLong() shl 16) or
                  ((p1[6].toInt() and 0xFF).toLong() shl 8) or
                  (p1[7].toInt() and 0xFF).toLong()
        assertEquals(0L, ts1)
        assertEquals(160L, rtp.getTimestamp())

        val p2 = rtp.build(ByteArray(160))
        val ts2 = ((p2[4].toInt() and 0xFF).toLong() shl 24) or
                  ((p2[5].toInt() and 0xFF).toLong() shl 16) or
                  ((p2[6].toInt() and 0xFF).toLong() shl 8) or
                  (p2[7].toInt() and 0xFF).toLong()
        assertEquals(160L, ts2)
        assertEquals(320L, rtp.getTimestamp())
    }

    @Test
    fun ssrcField() {
        val ssrc = 0x12345678
        val rtp = RtpPacket(payloadType = 0, ssrc = ssrc)
        val packet = rtp.build(ByteArray(160))
        val readSsrc = ((packet[8].toInt() and 0xFF) shl 24) or
                       ((packet[9].toInt() and 0xFF) shl 16) or
                       ((packet[10].toInt() and 0xFF) shl 8) or
                       (packet[11].toInt() and 0xFF)
        assertEquals(ssrc, readSsrc)
    }

    @Test
    fun payloadCopied() {
        val rtp = RtpPacket(payloadType = 0, ssrc = 1234)
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val packet = rtp.build(payload)
        assertEquals(16, packet.size) // 12 header + 4 payload
        assertEquals(0x01.toByte(), packet[12])
        assertEquals(0x02.toByte(), packet[13])
        assertEquals(0x03.toByte(), packet[14])
        assertEquals(0x04.toByte(), packet[15])
    }

    @Test
    fun packetSize() {
        val rtp = RtpPacket(payloadType = 0, ssrc = 1234)
        val packet = rtp.build(ByteArray(160))
        assertEquals(172, packet.size) // 12 + 160
    }

    @Test
    fun interleavedFraming() {
        val rtp = RtpPacket(payloadType = 0, ssrc = 1234)
        val rtpPacket = rtp.build(ByteArray(160))
        val frame = rtp.wrapInterleaved(rtpPacket, channel = 0)
        assertEquals(176, frame.size) // 4 + 172
        assertEquals(0x24.toByte(), frame[0]) // '$'
        assertEquals(0x00.toByte(), frame[1]) // channel 0
        // Length = 172 big-endian
        assertEquals(0x00.toByte(), frame[2])
        assertEquals(0xAC.toByte(), frame[3]) // 172
    }

    @Test
    fun interleavedFramingChannel2() {
        val rtp = RtpPacket(payloadType = 0, ssrc = 1234)
        val rtpPacket = rtp.build(ByteArray(10))
        val frame = rtp.wrapInterleaved(rtpPacket, channel = 2)
        assertEquals(0x02.toByte(), frame[1])
        val len = ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
        assertEquals(22, len) // 12 + 10
    }

    @Test
    fun noPaddingNoExtensionNoCSRC() {
        val rtp = RtpPacket(payloadType = 0, ssrc = 1234)
        val packet = rtp.build(ByteArray(160))
        val byte0 = packet[0].toInt() and 0xFF
        assertEquals(0, (byte0 shr 5) and 1) // P=0
        assertEquals(0, (byte0 shr 4) and 1) // X=0
        assertEquals(0, byte0 and 0x0F)       // CC=0
    }

    @Test
    fun markerBitNotSet() {
        val rtp = RtpPacket(payloadType = 0, ssrc = 1234)
        val packet = rtp.build(ByteArray(160))
        assertEquals(0, (packet[1].toInt() and 0xFF) shr 7) // M=0
    }
}
