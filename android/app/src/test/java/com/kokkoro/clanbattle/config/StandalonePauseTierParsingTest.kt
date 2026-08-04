package com.kokkoro.clanbattle.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StandalonePauseTierParsingTest {
    @Test fun `accepts valid tier and trims whitespace`() {
        assertEquals(StandalonePauseTier(40, 20), parseStandalonePauseTier(" 40 ", "20"))
    }

    @Test fun `rejects non-numeric input`() {
        assertNull(parseStandalonePauseTier("四十", "20"))
        assertNull(parseStandalonePauseTier("40", ""))
    }

    @Test fun `rejects out-of-range frame rate`() {
        assertNull(parseStandalonePauseTier("4", "20"))
        assertNull(parseStandalonePauseTier("501", "20"))
    }

    @Test fun `rejects out-of-range frame count`() {
        assertNull(parseStandalonePauseTier("40", "0"))
        assertNull(parseStandalonePauseTier("40", "601"))
    }
}
