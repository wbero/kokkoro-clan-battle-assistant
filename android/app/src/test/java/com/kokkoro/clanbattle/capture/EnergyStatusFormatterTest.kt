package com.kokkoro.clanbattle.capture

import com.kokkoro.clanbattle.recognition.CharacterEnergyState
import com.kokkoro.clanbattle.recognition.CharacterRole
import com.kokkoro.clanbattle.recognition.EnergyDetectionResult
import com.kokkoro.clanbattle.scheduler.GameState
import org.junit.Assert.assertEquals
import org.junit.Test

class EnergyStatusFormatterTest {
    @Test fun `formats role one through five percentages from left to right`() {
        val result = EnergyDetectionResult(
            characters = CharacterRole.entries.associateWith { role ->
                CharacterEnergyState((role.ordinal + 1) / 10f, false, null, false)
            },
            energyDelta = null,
            triggeredRoles = emptySet()
        )

        assertEquals("TP 1:10 2:20 3:30 4:40 5:50", EnergyStatusFormatter.format(result))
    }

    @Test fun `formats missing detection explicitly`() {
        assertEquals("TP --", EnergyStatusFormatter.format(null))
    }

    @Test fun `appends game state and scheduler reason when available`() {
        assertEquals(
            "TP --  STATE:UB_ANIMATION/ub-animation-frozen",
            EnergyStatusFormatter.format(null, GameState.UB_ANIMATION, "ub-animation-frozen")
        )
    }
}
