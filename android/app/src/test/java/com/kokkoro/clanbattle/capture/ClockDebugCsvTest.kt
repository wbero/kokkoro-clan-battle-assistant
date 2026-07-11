package com.kokkoro.clanbattle.capture

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

    @Test fun `digit header contains ten score columns`() {
        val columns = ClockDebugCsv.DIGIT_HEADER.split(',')
        assertEquals((0..9).map { "s$it" }, columns.filter { it.matches(Regex("s\\d")) })
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
