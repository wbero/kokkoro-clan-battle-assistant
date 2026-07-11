package com.kokkoro.clanbattle.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DigitNormalizerTest {
    @Test
    fun `grayscale conversion stays in 0 to 255 and uses luminance weights`() {
        val image = PixelImage(
            width = 4,
            height = 1,
            pixels = intArrayOf(0xff000000.toInt(), 0xffffffff.toInt(), 0xffff0000.toInt(), 0xff00ff00.toInt())
        )

        assertTrue(DigitNormalizer.grayscale(image).contentEquals(intArrayOf(0, 255, 76, 150)))
    }

    @Test
    fun `otsu separates a dark foreground from a bright background`() {
        val grayscale = intArrayOf(12, 14, 13, 230, 235, 240)

        val threshold = requireNotNull(DigitNormalizer.otsuThreshold(grayscale))

        assertTrue("threshold=$threshold", threshold in 14 until 230)
    }

    @Test
    fun `otsu reports no threshold when crop has no contrast`() {
        assertNull(DigitNormalizer.otsuThreshold(IntArray(12) { 0 }))
        assertNull(DigitNormalizer.otsuThreshold(IntArray(12) { 127 }))
        assertNull(DigitNormalizer.otsuThreshold(IntArray(12) { 255 }))
    }

    @Test
    fun `normalization crops foreground and centers scaled digit with padding`() {
        val white = 0xffffffff.toInt()
        val black = 0xff000000.toInt()
        val pixels = IntArray(8 * 8) { white }
        for (y in 2 until 6) {
            for (x in 3 until 5) pixels[y * 8 + x] = black
        }

        val normalized = DigitNormalizer.normalize(PixelImage(8, 8, pixels))
        val bounds = requireNotNull(normalized.foregroundBounds())

        assertEquals(24, normalized.width)
        assertEquals(24, normalized.height)
        assertEquals(BinaryBounds(left = 7, top = 2, rightExclusive = 17, bottomExclusive = 22), bounds)
        assertEquals(200, normalized.foregroundCount)
    }

    @Test
    fun `uniform crops normalize to an empty canvas`() {
        listOf(0xff000000.toInt(), 0xff7f7f7f.toInt(), 0xffffffff.toInt()).forEach { color ->
            val uniform = PixelImage(7, 9, IntArray(63) { color })

            val normalized = DigitNormalizer.normalize(uniform)

            assertEquals(24, normalized.width)
            assertEquals(24, normalized.height)
            assertEquals(0, normalized.foregroundCount)
            assertFalse(normalized.pixels.any { it })
        }
    }
}
