package com.kokkoro.clanbattle.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class ActionExecutorSpacingTest {
    @Test fun `first queued tap has no delay`() {
        assertEquals(0L, remainingTapSpacingMs(null, nowMs = 1_000L, clickIntervalMs = 100))
    }

    @Test fun `separate queued taps still share axis click interval`() {
        assertEquals(100L, remainingTapSpacingMs(1_000L, nowMs = 1_000L, clickIntervalMs = 100))
        assertEquals(60L, remainingTapSpacingMs(1_000L, nowMs = 1_040L, clickIntervalMs = 100))
        assertEquals(0L, remainingTapSpacingMs(1_000L, nowMs = 1_100L, clickIntervalMs = 100))
    }

    @Test fun `zero configured interval still protects accessibility gesture lifetime`() {
        assertEquals(
            MIN_ACCESSIBILITY_TAP_SPACING_MS,
            remainingTapSpacingMs(2_000L, nowMs = 2_000L, clickIntervalMs = 0)
        )
        assertEquals(
            0L,
            remainingTapSpacingMs(
                2_000L,
                nowMs = 2_000L + MIN_ACCESSIBILITY_TAP_SPACING_MS,
                clickIntervalMs = 0
            )
        )
    }
}
