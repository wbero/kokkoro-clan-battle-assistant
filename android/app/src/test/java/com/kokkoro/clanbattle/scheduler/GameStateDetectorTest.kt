package com.kokkoro.clanbattle.scheduler

import com.kokkoro.clanbattle.recognition.CharacterRole
import com.kokkoro.clanbattle.recognition.EnergyDetectionResult
import org.junit.Assert.assertEquals
import org.junit.Test

class GameStateDetectorTest {
    @Test
    fun `character trigger freezes until clock decreases then emits one thaw frame`() {
        val detector = GameStateDetector()

        assertEquals(GameState.CHARACTER_UB, detector.update(76, energy(CharacterRole.ROLE_3)))
        assertEquals(GameState.UB_ANIMATION, detector.update(76, null))
        assertEquals(GameState.UB_ANIMATION, detector.update(null, energy()))
        assertEquals(GameState.UB_JUST_ENDED, detector.update(75, energy()))
        assertEquals(GameState.RUNNING, detector.update(75, energy()))
    }

    @Test
    fun `ordinary repeated clock stays running without an energy trigger`() {
        val detector = GameStateDetector()

        assertEquals(GameState.RUNNING, detector.update(76, null))
        assertEquals(GameState.RUNNING, detector.update(76, energy()))
        assertEquals(GameState.RUNNING, detector.update(76, null))
    }

    @Test
    fun `reset clears an active animation`() {
        val detector = GameStateDetector()
        detector.update(76, energy(CharacterRole.ROLE_2))

        detector.reset()

        assertEquals(GameState.RUNNING, detector.update(76, null))
    }

    @Test
    fun `multiple roles trigger one character ub transition`() {
        val detector = GameStateDetector()
        val simultaneous = energy(CharacterRole.ROLE_1, CharacterRole.ROLE_4)

        assertEquals(GameState.CHARACTER_UB, detector.update(60, simultaneous))
        assertEquals(GameState.UB_ANIMATION, detector.update(60, simultaneous))
        assertEquals(GameState.UB_JUST_ENDED, detector.update(59, simultaneous))
    }

    @Test
    fun `clock increase during an active animation fails closed`() {
        val detector = GameStateDetector()
        detector.update(60, energy(CharacterRole.ROLE_5))

        assertEquals(GameState.UB_ANIMATION, detector.update(61, energy()))
        assertEquals(GameState.UB_ANIMATION, detector.update(60, energy()))
        assertEquals(GameState.UB_JUST_ENDED, detector.update(59, energy()))
    }

    @Test
    fun `first valid clock after a clockless trigger becomes the animation anchor`() {
        val detector = GameStateDetector()

        assertEquals(GameState.CHARACTER_UB, detector.update(null, energy(CharacterRole.ROLE_1)))
        assertEquals(GameState.UB_ANIMATION, detector.update(90, energy()))
        assertEquals(GameState.UB_JUST_ENDED, detector.update(89, energy()))
    }

    @Test
    fun `energy trigger observed before a trusted clock is delivered once on update`() {
        val detector = GameStateDetector()

        detector.observeEnergy(energy(CharacterRole.ROLE_2))
        detector.observeEnergy(energy(CharacterRole.ROLE_2))

        assertEquals(GameState.CHARACTER_UB, detector.update(76, null))
        assertEquals(GameState.UB_ANIMATION, detector.update(76, null))
    }

    @Test
    fun `reset clears a pending energy trigger`() {
        val detector = GameStateDetector()
        detector.observeEnergy(energy(CharacterRole.ROLE_4))

        detector.reset()

        assertEquals(GameState.RUNNING, detector.update(76, null))
    }

    private fun energy(vararg roles: CharacterRole) = EnergyDetectionResult(
        characters = emptyMap(),
        energyDelta = null,
        triggeredRoles = roles.toSet()
    )
}
