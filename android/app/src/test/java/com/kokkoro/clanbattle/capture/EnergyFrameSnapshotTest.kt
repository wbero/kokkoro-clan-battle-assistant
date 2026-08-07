package com.kokkoro.clanbattle.capture

import com.kokkoro.clanbattle.recognition.CharacterEnergyState
import com.kokkoro.clanbattle.recognition.CharacterRole
import com.kokkoro.clanbattle.recognition.EnergyDetectionResult
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class EnergyFrameSnapshotTest {
    @Test
    fun `captured empty snapshot remains empty instead of falling through to future samples`() {
        val frozen = EnergyFrameSnapshot(null)

        assertNull(
            resolveEnergyForRetainedFrame(
                externalEnergySampling = true,
                frozenEnergy = frozen,
                liveTake = { throw AssertionError("frozen empty snapshot must not read future samples") }
            )
        )
    }

    @Test
    fun `captured snapshot wins over a newer live sample`() {
        val captured = sample()
        val newer = sample()

        assertSame(
            captured,
            resolveEnergyForRetainedFrame(
                externalEnergySampling = true,
                frozenEnergy = EnergyFrameSnapshot(captured),
                liveTake = { newer }
            )
        )
    }

    @Test
    fun `direct callers without a frozen snapshot may use the live buffer`() {
        val live = sample()

        assertSame(
            live,
            resolveEnergyForRetainedFrame(
                externalEnergySampling = true,
                frozenEnergy = null,
                liveTake = { live }
            )
        )
    }

    private fun sample(): EnergyDetectionResult = EnergyDetectionResult(
        characters = CharacterRole.entries.associateWith {
            CharacterEnergyState(blueRatio = 0.5f, isFull = false, delta = null, triggered = false)
        },
        energyDelta = null,
        triggeredRoles = emptySet()
    )
}
