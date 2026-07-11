package com.kokkoro.clanbattle.recognition

import ar.com.hjg.pngj.PngReaderInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.IdentityHashMap

class ClockRecognizerDiagnosticsTest {
    private val templates = DigitTemplates(
        (0..9).associateWith { digit -> loadImage("templates/$digit.png") }
    )
    private val recognizer = ClockRecognizer(templates)

    @Test
    fun `diagnostics are absent by default`() {
        val result = recognizer.recognize(loadImage("clock_top2/clock_0_39.png"), minConfidence = 0.0)

        assertNull(result.debugTrace)
    }

    @Test
    fun `diagnostics contain three digit traces with ten finite scores`() {
        val result = recognizer.recognize(
            loadImage("clock_top2/clock_1_19.png"),
            minConfidence = 0.0,
            includeDiagnostics = true
        )

        val trace = requireNotNull(result.debugTrace)
        assertTrue(trace.groups.size >= 4)
        assertEquals(
            listOf(DigitSlot.MINUTE, DigitSlot.SECOND_TENS, DigitSlot.SECOND_ONES),
            trace.digits.map(DigitRecognitionTrace::slot)
        )
        trace.digits.forEach { digit ->
            assertEquals(ScoreKind.STRUCTURAL_IOU, digit.scoreKind)
            assertEquals((0..9).toSet(), digit.decisionScores.keys)
            assertEquals((0..9).toSet(), digit.nccScores.keys)
            assertTrue(digit.decisionScores.values.all(Double::isFinite))
            assertTrue(digit.nccScores.values.all(Double::isFinite))
            assertTrue(digit.rawMargin.isFinite())
            assertTrue(digit.chosenScore.isFinite())
            assertTrue(digit.decisionMargin.isFinite())
            assertTrue(digit.chosen in digit.allowedRange)

            val rawRanking = digit.decisionScores.entries.sortedByDescending(Map.Entry<Int, Double>::value)
            assertEquals(rawRanking[0].key, digit.rawTop1)
            assertEquals(rawRanking[1].key, digit.rawTop2)
            assertEquals(rawRanking[0].value - rawRanking[1].value, digit.rawMargin, 0.0)

            val allowedRanking = rawRanking.filter { it.key in digit.allowedRange }
            assertEquals(allowedRanking[0].key, digit.chosen)
            assertEquals(allowedRanking[0].value, digit.chosenScore, 0.0)
            assertEquals(allowedRanking[0].value - allowedRanking[1].value, digit.decisionMargin, 0.0)
            assertFalse(digit.decisionRule.isBlank())
        }
    }

    @Test
    fun `diagnostics do not change recognition output`() {
        listOf(
            "clock_1_19.png",
            "clock_0_39.png",
            "clock_0_09.png",
            "clock_0_06.png"
        ).forEach { name ->
            val image = loadImage("clock_top2/$name")
            val regular = recognizer.recognize(image, minConfidence = 0.0)
            val diagnostic = recognizer.recognize(image, minConfidence = 0.0, includeDiagnostics = true)

            assertEquals(regular, diagnostic.copy(debugTrace = null))
        }
    }

    @Test
    fun `scoring work is limited to allowed ranges unless diagnostics are enabled`() {
        assertEquals(ScoreCalls(listOf(2, 4, 10), emptyList()), scorerCallCounts(includeDiagnostics = false))
        assertEquals(ScoreCalls(listOf(10, 10, 10), listOf(10, 10, 10)), scorerCallCounts(includeDiagnostics = true))
    }

    @Test
    fun `raw ranking can differ from allowed range decision`() {
        val scores = mapOf(
            9 to 0.90,
            8 to 0.80,
            1 to 0.60,
            0 to 0.20
        )
        val controlledRecognizer = ClockRecognizer(
            templates,
            decisionScorer = { _, digit -> scores[digit] ?: 0.0 }
        )

        val result = controlledRecognizer.recognize(
            loadImage("clock_top2/clock_1_19.png"),
            minConfidence = 0.0,
            includeDiagnostics = true
        )

        val minute = requireNotNull(result.debugTrace).digits.single { it.slot == DigitSlot.MINUTE }
        assertEquals(9, minute.rawTop1)
        assertEquals(8, minute.rawTop2)
        assertEquals(0.10, minute.rawMargin, 1e-12)
        assertEquals(1, minute.chosen)
        assertEquals(0.60, minute.chosenScore, 0.0)
        assertEquals(0.40, minute.decisionMargin, 1e-12)
    }

    @Test
    fun `diagnostic crops are snapshots of scorer input`() {
        var capturedMinuteCrop: PixelImage? = null
        val snapshotRecognizer = ClockRecognizer(templates, nccScorer = { crop, _ ->
            if (capturedMinuteCrop == null) capturedMinuteCrop = crop
            0.5
        })
        val result = snapshotRecognizer.recognize(
            loadImage("clock_top2/clock_0_39.png"),
            minConfidence = 0.0,
            includeDiagnostics = true
        )
        val minuteCrop = requireNotNull(result.debugTrace).digits.first().crop
        val expectedPixels = minuteCrop.pixels.copyOf()

        requireNotNull(capturedMinuteCrop).pixels.fill(0)

        assertTrue(expectedPixels.contentEquals(minuteCrop.pixels))
    }

    private fun scorerCallCounts(includeDiagnostics: Boolean): ScoreCalls {
        val decisionCallsByCrop = IdentityHashMap<BinaryImage, Int>()
        val nccCallsByCrop = IdentityHashMap<PixelImage, Int>()
        val countingRecognizer = ClockRecognizer(
            templates,
            decisionScorer = { crop, digit ->
                decisionCallsByCrop[crop] = (decisionCallsByCrop[crop] ?: 0) + 1
                if (digit == 1) 1.0 else 0.0
            },
            nccScorer = { crop, _ ->
                nccCallsByCrop[crop] = (nccCallsByCrop[crop] ?: 0) + 1
                0.0
            }
        )

        countingRecognizer.recognize(
            loadImage("clock_top2/clock_1_19.png"),
            minConfidence = 0.0,
            includeDiagnostics = includeDiagnostics
        )

        return ScoreCalls(decisionCallsByCrop.values.sorted(), nccCallsByCrop.values.sorted())
    }

    private data class ScoreCalls(val decision: List<Int>, val ncc: List<Int>)

    private fun loadImage(name: String): PixelImage {
        val reader = PngReaderInt(requireNotNull(javaClass.classLoader?.getResourceAsStream(name)))
        val pixels = IntArray(reader.imgInfo.cols * reader.imgInfo.rows)
        repeat(reader.imgInfo.rows) { y ->
            val line = reader.readRowInt()
            repeat(reader.imgInfo.cols) { x ->
                val offset = x * reader.imgInfo.channels
                val red: Int
                val green: Int
                val blue: Int
                val alpha: Int
                if (reader.imgInfo.greyscale) {
                    red = line.scanline[offset]
                    green = red
                    blue = red
                    alpha = if (reader.imgInfo.alpha) line.scanline[offset + 1] else 255
                } else {
                    red = line.scanline[offset]
                    green = line.scanline[offset + 1]
                    blue = line.scanline[offset + 2]
                    alpha = if (reader.imgInfo.alpha) line.scanline[offset + 3] else 255
                }
                pixels[y * reader.imgInfo.cols + x] =
                    (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        reader.end()
        return PixelImage(reader.imgInfo.cols, reader.imgInfo.rows, pixels)
    }
}
