package com.kokkoro.clanbattle.capture

import com.kokkoro.clanbattle.recognition.DigitRecognitionTrace
import com.kokkoro.clanbattle.recognition.DigitSlot
import com.kokkoro.clanbattle.recognition.PixelImage
import com.kokkoro.clanbattle.recognition.ScoreKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

class ClockDebugCsvTest {
    @Test fun `frames header and values are escaped`() {
        val out = StringWriter()
        val csv = ClockDebugCsv(out, ClockDebugCsv.FRAME_HEADER)
        csv.write(listOf("1", "raw,clock", "reason \"quoted\""))
        assertEquals(
            "${ClockDebugCsv.FRAME_HEADER}\n1,\"raw,clock\",\"reason \"\"quoted\"\"\"\n",
            out.toString()
        )
    }

    @Test fun `digit header contains decision and ncc score columns`() {
        val columns = ClockDebugCsv.DIGIT_HEADER.split(',')
        assertEquals((0..9).map { "decision$it" }, columns.filter { it.matches(Regex("decision\\d")) })
        assertEquals((0..9).map { "ncc$it" }, columns.filter { it.matches(Regex("ncc\\d")) })
        assertTrue("scoreKind" in columns)
    }

    @Test fun `digit row exactly matches header order and column count`() {
        val trace = DigitRecognitionTrace(
            slot = DigitSlot.SECOND_ONES,
            crop = PixelImage(1, 1, intArrayOf(0)),
            allowedRange = 0..9,
            decisionScores = (0..9).associateWith { it + 0.1 },
            nccScores = (0..9).associateWith { it + 0.01 },
            scoreKind = ScoreKind.STRUCTURAL_IOU,
            rawTop1 = 6,
            rawTop2 = 0,
            rawMargin = 0.2,
            chosen = 6,
            chosenScore = 0.9,
            decisionMargin = 0.2,
            decisionRule = "test"
        )

        val columns = ClockDebugCsv.DIGIT_HEADER.split(',')
        val values = ClockDebugCsv.digitValues(12, 34, trace, "crop.png")
        val row = columns.zip(values.map(Any?::toString)).toMap()

        assertEquals(columns.size, values.size)
        assertEquals("STRUCTURAL_IOU", row.getValue("scoreKind"))
        (0..9).forEach { digit ->
            assertEquals((digit + 0.1).toString(), row.getValue("decision$digit"))
            assertEquals((digit + 0.01).toString(), row.getValue("ncc$digit"))
        }
        assertEquals("crop.png", row.getValue("cropFile"))
    }

    @Test fun `close prevents later writes`() {
        val out = StringWriter()
        val csv = ClockDebugCsv(out, "a")
        csv.close()
        assertFalse(csv.write(listOf("later")))
        assertEquals("a\n", out.toString())
        assertTrue(csv.isClosed)
    }
}
