package com.kokkoro.clanbattle.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnergyThresholdParsingTest {
    @Test fun `accepts valid percentages and trims whitespace`() {
        assertEquals(EnergyThresholdPercents(97, 30), parseEnergyThresholdPercents(" 97 ", "30"))
    }

    @Test fun `accepts the tightest allowed gap`() {
        assertEquals(EnergyThresholdPercents(50, 45), parseEnergyThresholdPercents("50", "45"))
    }

    @Test fun `rejects non-numeric input`() {
        assertNull(parseEnergyThresholdPercents("满", "30"))
        assertNull(parseEnergyThresholdPercents("97", ""))
    }

    @Test fun `rejects out-of-range full threshold`() {
        assertNull(parseEnergyThresholdPercents("49", "30"))
        assertNull(parseEnergyThresholdPercents("101", "30"))
    }

    @Test fun `rejects out-of-range drop threshold`() {
        assertNull(parseEnergyThresholdPercents("97", "0"))
        assertNull(parseEnergyThresholdPercents("97", "96"))
    }

    @Test fun `rejects an insufficient hysteresis gap`() {
        assertNull(parseEnergyThresholdPercents("60", "56"))
    }
}
