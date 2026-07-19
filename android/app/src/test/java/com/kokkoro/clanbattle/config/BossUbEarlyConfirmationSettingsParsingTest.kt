package com.kokkoro.clanbattle.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BossUbEarlyConfirmationSettingsParsingTest {
    @Test fun `parses valid millisecond threshold`() {
        assertEquals(3_000, parseBossUbEarlyConfirmationHoldMs(" 3000 "))
        assertEquals(7_000, parseBossUbEarlyConfirmationHoldMs("7000"))
        assertEquals(15_000, parseBossUbEarlyConfirmationHoldMs("15000"))
    }

    @Test fun `rejects unsafe millisecond threshold`() {
        assertNull(parseBossUbEarlyConfirmationHoldMs(""))
        assertNull(parseBossUbEarlyConfirmationHoldMs("2999"))
        assertNull(parseBossUbEarlyConfirmationHoldMs("15001"))
        assertNull(parseBossUbEarlyConfirmationHoldMs("七千"))
    }
}
