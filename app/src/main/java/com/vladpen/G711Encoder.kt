package com.vladpen

/**
 * G.711 audio encoder for RTSP backchannel audio.
 * Converts 16-bit linear PCM samples to 8-bit μ-law or A-law.
 */
object G711Encoder {

    private const val ULAW_BIAS = 0x84
    private const val ULAW_CLIP = 32635

    /**
     * Encode a 16-bit linear PCM sample to 8-bit G.711 μ-law.
     */
    fun linearToUlaw(sample: Short): Byte {
        var pcm = sample.toInt()
        val sign: Int
        if (pcm < 0) {
            sign = 0x80
            pcm = -pcm
        } else {
            sign = 0
        }
        if (pcm > ULAW_CLIP) pcm = ULAW_CLIP
        pcm += ULAW_BIAS

        val exponent = ULAW_EXPONENT_TABLE[(pcm shr 8) and 0x7F].toInt()
        val mantissa = (pcm shr (exponent + 3)) and 0x0F
        return (sign or (exponent shl 4) or mantissa).inv().toByte()
    }

    /**
     * Encode a 16-bit linear PCM sample to 8-bit G.711 A-law.
     */
    fun linearToAlaw(sample: Short): Byte {
        var pcm = sample.toInt()
        val sign: Int
        if (pcm >= 0) {
            sign = 0xD5
        } else {
            sign = 0x55
            pcm = -pcm
        }
        if (pcm > 32767) pcm = 32767

        val compressedByte: Int = if (pcm >= 256) {
            val exponent = ALAW_EXPONENT_TABLE[(pcm shr 8) and 0x7F].toInt()
            val mantissa = (pcm shr (exponent + 3)) and 0x0F
            (exponent shl 4) or mantissa
        } else {
            pcm shr 4
        }
        return (compressedByte xor sign).toByte()
    }

    /**
     * Encode a buffer of 16-bit PCM samples to G.711 μ-law.
     */
    fun encodeUlaw(pcm: ShortArray, offset: Int = 0, length: Int = pcm.size): ByteArray {
        val encoded = ByteArray(length)
        for (i in 0 until length) {
            encoded[i] = linearToUlaw(pcm[offset + i])
        }
        return encoded
    }

    /**
     * Encode a buffer of 16-bit PCM samples to G.711 A-law.
     */
    fun encodeAlaw(pcm: ShortArray, offset: Int = 0, length: Int = pcm.size): ByteArray {
        val encoded = ByteArray(length)
        for (i in 0 until length) {
            encoded[i] = linearToAlaw(pcm[offset + i])
        }
        return encoded
    }

    private val ULAW_EXPONENT_TABLE = byteArrayOf(
        0,0,1,1,2,2,2,2,3,3,3,3,3,3,3,3,
        4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,
        5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,
        5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,
        6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,
        6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,
        6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,
        6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6
    )

    private val ALAW_EXPONENT_TABLE = byteArrayOf(
        1,1,2,2,3,3,3,3,4,4,4,4,4,4,4,4,
        5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,
        6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,
        6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,
        7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,
        7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,
        7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,
        7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7
    )
}
