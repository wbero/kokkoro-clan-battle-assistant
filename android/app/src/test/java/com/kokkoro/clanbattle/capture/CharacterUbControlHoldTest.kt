package com.kokkoro.clanbattle.capture

import com.kokkoro.clanbattle.scheduler.GameState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterUbControlHoldTest {
    @Test
    fun `character ub keeps control recognition failures on hold before deadline`() {
        assertTrue(
            shouldHoldControlRecognitionForCharacterUb(
                GameState.CHARACTER_UB,
                nowMs = 2_450L,
                holdUntilMs = 8_000L
            )
        )
        assertTrue(
            shouldHoldControlRecognitionForCharacterUb(
                GameState.UB_ANIMATION,
                nowMs = 7_999L,
                holdUntilMs = 8_000L
            )
        )
    }

    @Test
    fun `control recognition safety resumes when ub ends or maximum hold expires`() {
        assertFalse(
            shouldHoldControlRecognitionForCharacterUb(
                GameState.UB_JUST_ENDED,
                nowMs = 2_450L,
                holdUntilMs = 8_000L
            )
        )
        assertFalse(
            shouldHoldControlRecognitionForCharacterUb(
                GameState.RUNNING,
                nowMs = 2_450L,
                holdUntilMs = 8_000L
            )
        )
        assertFalse(
            shouldHoldControlRecognitionForCharacterUb(
                GameState.UB_ANIMATION,
                nowMs = 8_000L,
                holdUntilMs = 8_000L
            )
        )
    }
}
