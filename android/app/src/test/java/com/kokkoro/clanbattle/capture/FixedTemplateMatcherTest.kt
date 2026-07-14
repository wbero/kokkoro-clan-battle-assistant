package com.kokkoro.clanbattle.capture

import com.kokkoro.clanbattle.recognition.PixelImage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixedTemplateMatcherTest {
    @Test
    fun `matches the same luminance pattern and rejects a different pattern`() {
        val template = imageOf(0, 40, 80, 120, 160, 200)
        val same = imageOf(0, 40, 80, 120, 160, 200)
        val different = imageOf(0, 200, 20, 180, 40, 160)

        assertTrue(FixedTemplateMatcher.matches(same, template, 0.95))
        assertFalse(FixedTemplateMatcher.matches(different, template, 0.95))
    }

    @Test
    fun `best scale score finds a resized and shifted badge`() {
        val template = imageOf(0, 40, 80, 120, 160, 200)
        val background = gray(17)
        val canvas = IntArray(10 * 8) { background }
        repeat(4) { y ->
            repeat(6) { x ->
                canvas[(y + 3) * 10 + x + 2] = template[x / 2, y / 2]
            }
        }

        val score = FixedTemplateMatcher.bestScaleScore(
            PixelImage(10, 8, canvas),
            template,
            scales = doubleArrayOf(2.0)
        )

        assertTrue("score=$score", score >= 0.99)
    }

    private fun imageOf(vararg gray: Int): PixelImage = PixelImage(
        width = 3,
        height = 2,
        pixels = gray.map(::gray).toIntArray()
    )

    private fun gray(value: Int): Int =
        (0xff shl 24) or (value shl 16) or (value shl 8) or value
}
