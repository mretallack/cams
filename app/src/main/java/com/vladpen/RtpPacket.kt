package com.vladpen

/**
 * RTP packet builder for RTSP backchannel audio.
 * Constructs RTP packets and TCP interleaved frames.
 */
class RtpPacket(private val payloadType: Int, private val ssrc: Int) {

    private var sequenceNumber: Int = 0
    private var timestamp: Long = 0

    /**
     * Build an RTP packet with the given audio payload.
     * Returns the complete RTP packet (header + payload).
     */
    fun build(payload: ByteArray): ByteArray {
        val packet = ByteArray(12 + payload.size)
        // V=2, P=0, X=0, CC=0
        packet[0] = 0x80.toByte()
        // M=0, PT
        packet[1] = (payloadType and 0x7F).toByte()
        // Sequence number (big-endian)
        packet[2] = (sequenceNumber shr 8).toByte()
        packet[3] = (sequenceNumber and 0xFF).toByte()
        // Timestamp (big-endian)
        packet[4] = ((timestamp shr 24) and 0xFF).toByte()
        packet[5] = ((timestamp shr 16) and 0xFF).toByte()
        packet[6] = ((timestamp shr 8) and 0xFF).toByte()
        packet[7] = (timestamp and 0xFF).toByte()
        // SSRC (big-endian)
        packet[8] = (ssrc shr 24).toByte()
        packet[9] = ((ssrc shr 16) and 0xFF).toByte()
        packet[10] = ((ssrc shr 8) and 0xFF).toByte()
        packet[11] = (ssrc and 0xFF).toByte()
        // Payload
        System.arraycopy(payload, 0, packet, 12, payload.size)

        sequenceNumber = (sequenceNumber + 1) and 0xFFFF
        timestamp += payload.size
        return packet
    }

    /**
     * Wrap an RTP packet in TCP interleaved framing for RTSP.
     * Format: '$' + channel(1 byte) + length(2 bytes big-endian) + rtp_data
     */
    fun wrapInterleaved(rtpPacket: ByteArray, channel: Int = 0): ByteArray {
        val frame = ByteArray(4 + rtpPacket.size)
        frame[0] = 0x24 // '$'
        frame[1] = channel.toByte()
        frame[2] = (rtpPacket.size shr 8).toByte()
        frame[3] = (rtpPacket.size and 0xFF).toByte()
        System.arraycopy(rtpPacket, 0, frame, 4, rtpPacket.size)
        return frame
    }

    fun getSequenceNumber(): Int = sequenceNumber
    fun getTimestamp(): Long = timestamp
}
