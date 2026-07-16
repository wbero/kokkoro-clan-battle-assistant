package com.kokkoro.clanbattle.recognition

import ar.com.hjg.pngj.PngReaderInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.IdentityHashMap

class ClockRecognizerTest {
    private val templates = DigitTemplates(
        (0..9).associateWith { digit -> loadImage("templates/$digit.png") }
    )
    private val recognizer = ClockRecognizer(templates)

    @Test
    fun `structural production scoring recognizes clock fixtures at default confidence`() {
        mapOf(
            "clock_1_19.png" to 79,
            "clock_0_39.png" to 39,
            "clock_0_09.png" to 9,
            "clock_0_06.png" to 6
        ).forEach { (name, expected) ->
            val result = recognizer.recognize(loadImage("clock_top2/$name"))
            assertTrue("$name: $result", result.ok)
            assertEquals("$name", expected, result.timeSeconds)
        }
    }

    @Test
    fun `recognizes ultrawide real device clock crops`() {
        mapOf(
            "clock_ultrawide/clock_1_30_full.png" to 90,
            "clock_ultrawide/clock_1_25.png" to 85,
            "clock_ultrawide/clock_1_15.png" to 75
        ).forEach { (name, expected) ->
            val result = recognizer.recognize(loadImage(name))
            assertTrue("$name: $result", result.ok)
            assertEquals(name, expected, result.timeSeconds)
        }
    }

    @Test
    fun `top two combinations contain correct time for ambiguous digits`() {
        val cases = listOf(
            "clock_1_19.png" to 79,
            "clock_0_39.png" to 39,
            "clock_0_09.png" to 9,
            "clock_0_06.png" to 6
        )

        cases.forEach { (name, expected) ->
            val result = recognizer.recognize(loadImage("clock_top2/$name"), minConfidence = 0.0)
            val candidate = result.candidates.firstOrNull { it.timeSeconds == expected }
            assertNotNull("$name should contain $expected in candidates: $result", candidate)
            assertTrue("$name candidate score should be usable", candidate!!.score >= 0.55)
        }
    }

    @Test
    fun `third ranked ones digit remains available for sequential recovery`() {
        val slotByCrop = IdentityHashMap<BinaryImage, Int>()
        var nextSlot = 0
        val controlledRecognizer = ClockRecognizer(templates, decisionScorer = { crop, digit ->
            val slot = slotByCrop[crop] ?: nextSlot.also {
                slotByCrop[crop] = it
                nextSlot += 1
            }
            when (slot) {
                0 -> if (digit == 0) 0.95 else 0.10
                1 -> when (digit) {
                    1 -> 0.90
                    0 -> 0.80
                    2 -> 0.70
                    3 -> 0.60
                    else -> 0.10 - digit * 0.001
                }
                else -> when (digit) {
                    0 -> 0.90
                    5 -> 0.80
                    6 -> 0.70
                    1 -> 0.60
                    else -> 0.10 - digit * 0.001
                }
            }
        })
        val recognition = controlledRecognizer.recognize(
            loadImage("clock_top2/clock_0_06.png"),
            minConfidence = 0.0
        )

        val six = recognition.candidates.singleOrNull { it.timeSeconds == 16 }
        assertNotNull("third-ranked 6 should remain in clock candidates: $recognition", six)
        assertEquals(0.70, requireNotNull(six).score, 0.0)
        assertTrue(recognition.candidates.size <= 9)
        assertEquals(
            recognition.candidates.size,
            recognition.candidates.map(ClockCandidate::timeSeconds).distinct().size
        )
        assertTrue(recognition.candidates.zipWithNext().all { (first, second) -> first.score >= second.score })
        assertEquals(1, recognition.candidates.count(ClockCandidate::isPrimary))
        assertEquals(10, recognition.candidates.single(ClockCandidate::isPrimary).timeSeconds)
        assertFalse(recognition.candidates.any { it.timeSeconds % 10 == 1 })
        assertFalse(recognition.candidates.any { it.timeSeconds / 10 == 3 })

        val filter = RecognitionFilter(minAlternativeScore = 0.55)
        assertTrue(filter.update(RecognitionResult.ok(17, "0:17", 0.95), 1_000).accepted)
        val recovered = filter.update(recognition, 1_200)

        assertTrue(recovered.accepted)
        assertEquals(16, recovered.timeSeconds)
        assertEquals(ReadingSource.ALTERNATIVE, recovered.source)
    }

    @Test
    fun `candidate combinations exclude clocks above one minute thirty`() {
        val slotByCrop = IdentityHashMap<BinaryImage, Int>()
        var nextSlot = 0
        val controlledRecognizer = ClockRecognizer(templates, decisionScorer = { crop, digit ->
            val slot = slotByCrop[crop] ?: nextSlot.also {
                slotByCrop[crop] = it
                nextSlot += 1
            }
            when (slot) {
                0 -> if (digit == 1) 0.95 else 0.10
                1 -> when (digit) {
                    3 -> 0.90
                    2 -> 0.80
                    1 -> 0.70
                    else -> 0.10 - digit * 0.001
                }
                else -> when (digit) {
                    1 -> 0.90
                    0 -> 0.80
                    2 -> 0.70
                    else -> 0.10 - digit * 0.001
                }
            }
        })

        val recognition = controlledRecognizer.recognize(
            loadImage("clock_top2/clock_1_19.png"),
            minConfidence = 0.0
        )

        assertEquals(91, recognition.timeSeconds)
        assertEquals("out-of-range", recognition.reason)
        assertFalse(recognition.candidates.any { it.timeSeconds >= 91 })
        assertTrue(recognition.candidates.all { it.timeSeconds in 1..90 })
    }

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
