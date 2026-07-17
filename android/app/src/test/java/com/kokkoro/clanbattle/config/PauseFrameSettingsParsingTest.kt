package com.kokkoro.clanbattle.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PauseFrameSettingsParsingTest {
    @Test fun `accepts valid settings and trims whitespace`() {
        assertEquals(PauseFrameSettings(40, 5, 20, 700), parsePauseFrameSettings(" 40 ", "5", "20", "700"))
    }

    @Test fun `rejects non-numeric input`() {
        assertNull(parsePauseFrameSettings("四十", "5", "20", "700"))
        assertNull(parsePauseFrameSettings("40", "", "20", "700"))
    }

    @Test fun `rejects out-of-range frame duration`() {
        assertNull(parsePauseFrameSettings("4", "5", "20", "700"))
        assertNull(parsePauseFrameSettings("501", "5", "20", "700"))
    }

    @Test fun `rejects out-of-range preset counts`() {
        assertNull(parsePauseFrameSettings("40", "0", "20", "700"))
        assertNull(parsePauseFrameSettings("40", "5", "601", "700"))
    }

    @Test fun `rejects out-of-range menu wait`() {
        assertNull(parsePauseFrameSettings("40", "5", "20", "99"))
        assertNull(parsePauseFrameSettings("40", "5", "20", "3001"))
    }
}
