package com.kokkoro.clanbattle.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BinaryImageTest {
    @Test
    fun `bounding box encloses only foreground pixels`() {
        val pixels = BooleanArray(6 * 5)
        pixels[1 * 6 + 2] = true
        pixels[3 * 6 + 4] = true

        val bounds = requireNotNull(BinaryImage(6, 5, pixels).foregroundBounds())

        assertEquals(BinaryBounds(left = 2, top = 1, rightExclusive = 5, bottomExclusive = 4), bounds)
        assertEquals(3, bounds.width)
        assertEquals(3, bounds.height)
    }

    @Test
    fun `bounding box is absent when foreground is empty`() {
        val image = BinaryImage(4, 3, BooleanArray(12))

        assertNull(image.foregroundBounds())
        assertEquals(0, image.foregroundCount)
        assertTrue(image.pixels.none { it })
    }
}
