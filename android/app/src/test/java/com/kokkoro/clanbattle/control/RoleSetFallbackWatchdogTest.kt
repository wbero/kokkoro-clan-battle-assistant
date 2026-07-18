package com.kokkoro.clanbattle.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleSetFallbackWatchdogTest {
    @Test fun `deadline is anchored when clock first crosses below event time`() {
        val watchdog = RoleSetFallbackWatchdog(graceMs = 500)
        watchdog.observeClock(60, 10)
        watchdog.arm(60, 10)
        watchdog.observeClock(59, 20)

        assertFalse(watchdog.isDue(519))
        assertTrue(watchdog.isDue(520))
    }

    @Test fun `late confirmed lifecycle uses wall-clock grace without waiting a game second`() {
        val watchdog = RoleSetFallbackWatchdog(graceMs = 500)
        watchdog.observeClock(60, 10)
        watchdog.observeClock(59, 20)
        watchdog.arm(60, 1_000)

        assertFalse(watchdog.isDue(1_499))
        assertTrue(watchdog.isDue(1_500))
    }

    @Test fun `reset clears clock history for a new battle`() {
        val watchdog = RoleSetFallbackWatchdog(graceMs = 0)
        watchdog.observeClock(60, 10)
        watchdog.arm(60, 10)
        watchdog.observeClock(59, 20)
        assertTrue(watchdog.isDue(20))

        watchdog.reset()

        assertFalse(watchdog.isDue(21))
    }
}
