package com.kokkoro.clanbattle.capture

import com.kokkoro.clanbattle.recognition.CharacterEnergyState
import com.kokkoro.clanbattle.recognition.CharacterRole
import com.kokkoro.clanbattle.recognition.EnergyDetectionResult
import com.kokkoro.clanbattle.recognition.RoleUbBannerGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
    fun `slow frame fallback recovers full to low role before following ub banner`() {
        val previous = energyStates(role1 = 1.0f, role4 = 0.78f)
        val current = energyStates(role1 = 0.0f, role4 = 1.0f)

        val fallback = slowFrameReleaseFallbackCandidates(
            previous = previous,
            current = current,
            dropThreshold = 0.30f,
            visualObstruction = false,
            captureTimestampNanos = 100L
        )

        assertEquals(mapOf(CharacterRole.ROLE_1 to 100L), fallback)

        val gate = RoleUbBannerGate()
        assertTrue(
            gate.update(
                candidateTimesNanos = fallback,
                bannerRawPresent = false,
                bannerActive = false,
                bannerFrameTimestampNanos = 100L
            ).isEmpty()
        )
        assertEquals(
            mapOf(CharacterRole.ROLE_1 to 100L),
            gate.update(
                candidateTimesNanos = emptyMap(),
                bannerRawPresent = true,
                bannerActive = false,
                bannerFrameTimestampNanos = 120L
            )
        )
    }

    @Test
    fun `slow frame fallback is disabled during visual obstruction`() {
        val fallback = slowFrameReleaseFallbackCandidates(
            previous = energyStates(role1 = 1.0f),
            current = energyStates(role1 = 0.0f),
            dropThreshold = 0.30f,
            visualObstruction = true,
            captureTimestampNanos = 100L
        )

        assertTrue(fallback.isEmpty())
    }

    @Test
    fun `near full slow frame fallback captures phone underread without accepting ordinary tp`() {
        val fallback = slowFrameNearFullReleaseFallbackCandidates(
            previous = energyStates(role1 = 0.78f, role4 = 0.899f),
            current = energyStates(role1 = 0.0f, role4 = 0.0f),
            dropThreshold = 0.30f,
            visualObstruction = false,
            captureTimestampNanos = 100L
        )

        assertEquals(mapOf(CharacterRole.ROLE_4 to 100L), fallback)
    }

    @Test
    fun `near full slow frame fallback is disabled during visual obstruction`() {
        val fallback = slowFrameNearFullReleaseFallbackCandidates(
            previous = energyStates(role4 = 0.91f),
            current = energyStates(role4 = 0.0f),
            dropThreshold = 0.30f,
            visualObstruction = true,
            captureTimestampNanos = 100L
        )

        assertTrue(fallback.isEmpty())
    }

    @Test
    fun `corroborated full to low role suppresses unrelated later detector candidate`() {
        val detector = mapOf(
            CharacterRole.ROLE_1 to 90L,
            CharacterRole.ROLE_2 to 100L
        )
        val strong = mapOf(CharacterRole.ROLE_1 to 110L)

        val prioritized = prioritizeRoleUbCandidates(detector, strong)

        assertEquals(mapOf(CharacterRole.ROLE_1 to 110L), prioritized)

        val gate = RoleUbBannerGate(maxConfirmationDelayNanos = 1_000L)
        assertTrue(
            gate.update(
                candidateTimesNanos = prioritized,
                bannerRawPresent = false,
                bannerActive = false,
                bannerFrameTimestampNanos = 110L
            ).isEmpty()
        )
        assertEquals(
            mapOf(CharacterRole.ROLE_1 to 110L),
            gate.update(
                candidateTimesNanos = emptyMap(),
                bannerRawPresent = true,
                bannerActive = false,
                bannerFrameTimestampNanos = 120L
            )
        )
    }

    @Test
    fun `uncorroborated full to low fallback still rescues detector miss`() {
        val detector = mapOf(CharacterRole.ROLE_2 to 90L)
        val strong = mapOf(CharacterRole.ROLE_1 to 100L)

        assertEquals(
            mapOf(CharacterRole.ROLE_1 to 100L),
            prioritizeRoleUbCandidates(detector, strong)
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

    private fun energyStates(
        role1: Float = 0.5f,
        role4: Float = 0.5f
    ): Map<CharacterRole, CharacterEnergyState> = CharacterRole.entries.associateWith { role ->
        val ratio = when (role) {
            CharacterRole.ROLE_1 -> role1
            CharacterRole.ROLE_4 -> role4
            else -> 0.5f
        }
        CharacterEnergyState(
            blueRatio = ratio,
            isFull = ratio >= 0.97f,
            delta = null,
            triggered = false
        )
    }
}
