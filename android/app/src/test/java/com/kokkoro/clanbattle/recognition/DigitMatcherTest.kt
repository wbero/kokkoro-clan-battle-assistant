package com.kokkoro.clanbattle.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DigitMatcherTest {
    private val templates = DigitTemplates((0..9).associateWith(::solidImage))

    @Test
    fun `templates are normalized once and each crop is normalized once`() {
        var normalizations = 0
        val matcher = DigitMatcher(
            templates = templates,
            normalizer = { image ->
                normalizations += 1
                BinaryImage(1, 1, booleanArrayOf(image.pixels[0] != 0))
            },
            decisionScorer = { _, digit -> digit.toDouble() }
        )

        assertEquals(10, normalizations)
        matcher.match(solidImage(99), 0..5, includeDiagnostics = false)
        assertEquals(11, normalizations)
        matcher.match(solidImage(100), 0..5, includeDiagnostics = true)
        assertEquals(12, normalizations)
    }

    @Test
    fun `diagnostics add full ncc scores without changing structural decision`() {
        val matcher = DigitMatcher(
            templates = templates,
            normalizer = { BinaryImage(1, 1, booleanArrayOf(true)) },
            decisionScorer = { _, digit -> digit / 10.0 },
            nccScorer = { _, template -> template.pixels[0] / 100.0 }
        )

        val result = matcher.match(solidImage(99), 0..5, includeDiagnostics = true)

        assertEquals(5, result.digit)
        assertEquals(0.5, result.score, 0.0)
        assertEquals((0..9).toSet(), requireNotNull(result.decisionScores).keys)
        assertEquals((0..9).toSet(), requireNotNull(result.nccScores).keys)
        assertEquals(ScoreKind.STRUCTURAL_IOU, result.scoreKind)
    }

    @Test
    fun `structural confidence is high for reviewed lower bound and rejects empty evidence`() {
        assertTrue(DigitMatcher.structuralConfidence(0.767, 0.110) >= 0.8)
        assertEquals(0.0, DigitMatcher.structuralConfidence(0.0, 0.0), 0.0)
    }

    @Test
    fun `template digits have high confidence and a uniform crop is rejected`() {
        val realTemplates = DigitTemplates((0..9).associateWith { digit -> loadPngResource("templates/$digit.png") })
        val matcher = DigitMatcher(realTemplates)

        realTemplates.digits.forEach { (digit, image) ->
            val result = matcher.match(image, 0..9, includeDiagnostics = false)
            assertEquals(digit, result.digit)
            assertTrue("digit=$digit confidence=${result.confidence}", result.confidence >= 0.8)
        }

        val uniform = matcher.match(PixelImage(8, 8, IntArray(64) { 0xff777777.toInt() }), 0..9, false)
        assertEquals(0.0, uniform.confidence, 0.0)
    }

    private fun solidImage(value: Int) = PixelImage(1, 1, intArrayOf(value))
}
