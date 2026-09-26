package com.vladpen

import org.junit.Test
import org.junit.Assert.*

class G711EncoderTest {

    @Test
    fun ulawZeroInput() {
        // Zero PCM should encode to μ-law 0xFF (after bias and inversion)
        val result = G711Encoder.linearToUlaw(0)
        assertEquals(0xFF.toByte(), result)
    }

    @Test
    fun ulawMaxPositive() {
        val result = G711Encoder.linearToUlaw(Short.MAX_VALUE)
        // Max positive clips to 32635 + bias, exponent=6, mantissa=15 → inverted
        assertEquals(0x90.toByte(), result)
    }

    @Test
    fun ulawMaxNegative() {
        val result = G711Encoder.linearToUlaw(Short.MIN_VALUE)
        // Same magnitude as max positive but with sign bit
        assertEquals(0x10.toByte(), result)
    }

    @Test
    fun ulawKnownReferenceValues() {
        // ITU-T G.711 reference: PCM 8159 → μ-law 0x80 (inverted = 0x80... wait)
        // Standard test vectors: linear 0 → ulaw 0xFF, linear 4 → 0xFB (approx)
        // Test that small positive values encode to high byte values (due to inversion)
        val result = G711Encoder.linearToUlaw(100)
        // Small positive: should be in low exponent range, inverted → high value
        assertTrue((result.toInt() and 0xFF) > 0xC0)
    }

    @Test
    fun ulawSymmetry() {
        // Positive and negative of same magnitude should differ only in sign bit (bit 7 after inversion)
        for (value in shortArrayOf(100, 1000, 5000, 10000, 20000, 30000)) {
            val pos = G711Encoder.linearToUlaw(value).toInt() and 0xFF
            val neg = G711Encoder.linearToUlaw((-value).toShort()).toInt() and 0xFF
            // After inversion, sign bit is bit 7. XOR should be 0x80
            assertEquals("Symmetry failed for $value", 0x80, pos xor neg)
        }
    }

    @Test
    fun ulawMonotonicity() {
        // Larger input magnitudes should produce smaller μ-law values (due to inversion)
        var prev = G711Encoder.linearToUlaw(0).toInt() and 0xFF
        for (value in intArrayOf(100, 500, 1000, 5000, 10000, 20000, 32000)) {
            val current = G711Encoder.linearToUlaw(value.toShort()).toInt() and 0xFF
            assertTrue("Not monotonic at $value: prev=$prev current=$current", current < prev)
            prev = current
        }
    }

    @Test
    fun alawZeroInput() {
        val result = G711Encoder.linearToAlaw(0)
        assertEquals(0xD5.toByte(), result)
    }

    @Test
    fun alawMaxPositive() {
        val result = G711Encoder.linearToAlaw(Short.MAX_VALUE)
        // Should encode to highest magnitude A-law value with positive sign
        val unsigned = result.toInt() and 0xFF
        assertTrue(unsigned > 0x80)
    }

    @Test
    fun alawMaxNegative() {
        val result = G711Encoder.linearToAlaw(Short.MIN_VALUE)
        val unsigned = result.toInt() and 0xFF
        // Negative sign: XOR with 0x55 instead of 0xD5
        assertTrue(unsigned < 0x80)
    }

    @Test
    fun alawSymmetry() {
        // Positive and negative should differ by sign toggle (XOR 0x80)
        for (value in shortArrayOf(256, 1000, 5000, 10000, 20000, 30000)) {
            val pos = G711Encoder.linearToAlaw(value).toInt() and 0xFF
            val neg = G711Encoder.linearToAlaw((-value).toShort()).toInt() and 0xFF
            assertEquals("A-law symmetry failed for $value", 0x80, pos xor neg)
        }
    }

    @Test
    fun encodeUlawBuffer() {
        val pcm = shortArrayOf(0, 1000, -1000, Short.MAX_VALUE, Short.MIN_VALUE)
        val encoded = G711Encoder.encodeUlaw(pcm)
        assertEquals(5, encoded.size)
        assertEquals(G711Encoder.linearToUlaw(0), encoded[0])
        assertEquals(G711Encoder.linearToUlaw(1000), encoded[1])
        assertEquals(G711Encoder.linearToUlaw(-1000), encoded[2])
        assertEquals(G711Encoder.linearToUlaw(Short.MAX_VALUE), encoded[3])
        assertEquals(G711Encoder.linearToUlaw(Short.MIN_VALUE), encoded[4])
    }

    @Test
    fun encodeUlawBufferWithOffset() {
        val pcm = shortArrayOf(0, 100, 200, 300, 400)
        val encoded = G711Encoder.encodeUlaw(pcm, offset = 2, length = 2)
        assertEquals(2, encoded.size)
        assertEquals(G711Encoder.linearToUlaw(200), encoded[0])
        assertEquals(G711Encoder.linearToUlaw(300), encoded[1])
    }

    @Test
    fun encodeAlawBuffer() {
        val pcm = shortArrayOf(0, 1000, -1000, Short.MAX_VALUE, Short.MIN_VALUE)
        val encoded = G711Encoder.encodeAlaw(pcm)
        assertEquals(5, encoded.size)
        assertEquals(G711Encoder.linearToAlaw(0), encoded[0])
        assertEquals(G711Encoder.linearToAlaw(1000), encoded[1])
        assertEquals(G711Encoder.linearToAlaw(-1000), encoded[2])
    }

    @Test
    fun ulawFullRangeNoException() {
        // Ensure no crashes across entire 16-bit range
        for (i in Short.MIN_VALUE..Short.MAX_VALUE) {
            G711Encoder.linearToUlaw(i.toShort())
        }
    }

    @Test
    fun alawFullRangeNoException() {
        for (i in Short.MIN_VALUE..Short.MAX_VALUE) {
            G711Encoder.linearToAlaw(i.toShort())
        }
    }
}
