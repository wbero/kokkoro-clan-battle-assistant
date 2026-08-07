package com.kokkoro.clanbattle.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleUbBannerGateTest {
    @Test
    fun `portrait flash overrides a false tp candidate before the same banner`() {
        val gate = RoleUbBannerGate(maxConfirmationDelayNanos = 1_000L)

        assertTrue(
            gate.update(
                candidateTimesNanos = mapOf(CharacterRole.ROLE_5 to 150L),
                flashRoleTimesNanos = mapOf(CharacterRole.ROLE_3 to 100L),
                bannerRawPresent = false,
                bannerActive = false,
                bannerFrameTimestampNanos = 150L
            ).isEmpty()
        )

        assertEquals(
            mapOf(CharacterRole.ROLE_3 to 100L),
            gate.update(
                candidateTimesNanos = emptyMap(),
                bannerRawPresent = true,
                bannerActive = false,
                bannerFrameTimestampNanos = 200L
            )
        )
    }

    @Test
    fun `portrait flash confirms every role through the same generic path`() {
        CharacterRole.entries.forEach { role ->
            val gate = RoleUbBannerGate(maxConfirmationDelayNanos = 1_000L)

            gate.update(
                candidateTimesNanos = emptyMap(),
                flashRoleTimesNanos = mapOf(role to 100L),
                bannerRawPresent = false,
                bannerActive = false,
                bannerFrameTimestampNanos = 100L
            )

            assertEquals(
                mapOf(role to 100L),
                gate.update(
                    candidateTimesNanos = emptyMap(),
                    bannerRawPresent = true,
                    bannerActive = false,
                    bannerFrameTimestampNanos = 200L
                )
            )
        }
    }

    @Test
    fun `boss banner without portrait flash or tp candidate confirms no role`() {
        val gate = RoleUbBannerGate(maxConfirmationDelayNanos = 1_000L)

        assertTrue(
            gate.update(
                candidateTimesNanos = emptyMap(),
                bannerRawPresent = true,
                bannerActive = false,
                bannerFrameTimestampNanos = 200L
            ).isEmpty()
        )
    }

    @Test
    fun `tp candidate followed by a new banner confirms the role`() {
        val gate = RoleUbBannerGate(maxConfirmationDelayNanos = 1_000L)

        assertTrue(gate.update(mapOf(CharacterRole.ROLE_4 to 100L), false, false, 100L).isEmpty())
        assertEquals(
            mapOf(CharacterRole.ROLE_4 to 100L),
            gate.update(emptyMap(), true, false, 200L)
        )
    }

    @Test
    fun `single raw low sample that recovers before banner is not confirmed`() {
        CharacterRole.entries.forEach { role ->
            val gate = RoleUbBannerGate(
                maxConfirmationDelayNanos = 2_000L,
                quickRecoveryCancellationNanos = 500L
            )

            assertTrue(
                gate.update(
                    candidateTimesNanos = mapOf(role to 100L),
                    currentlyFullRoles = emptySet(),
                    bannerRawPresent = false,
                    bannerActive = false,
                    bannerFrameTimestampNanos = 100L
                ).isEmpty()
            )
            assertTrue(
                gate.update(
                    candidateTimesNanos = emptyMap(),
                    currentlyFullRoles = setOf(role),
                    bannerRawPresent = false,
                    bannerActive = false,
                    bannerFrameTimestampNanos = 300L
                ).isEmpty()
            )
            assertTrue(
                gate.update(
                    candidateTimesNanos = emptyMap(),
                    currentlyFullRoles = setOf(role),
                    bannerRawPresent = true,
                    bannerActive = false,
                    bannerFrameTimestampNanos = 400L
                ).isEmpty()
            )
        }
    }

    @Test
    fun `new banner confirms every role without role-specific handling`() {
        CharacterRole.entries.forEach { role ->
            val gate = RoleUbBannerGate(maxConfirmationDelayNanos = 1_000L)

            assertTrue(gate.update(mapOf(role to 100L), false, false, 100L).isEmpty())
            assertEquals(
                "$role should be confirmed by the same generic gate path",
                mapOf(role to 100L),
                gate.update(emptyMap(), true, false, 200L)
            )
        }
    }

    @Test
    fun `late delivered pre-banner candidate is accepted for every role`() {
        CharacterRole.entries.forEach { role ->
            val gate = RoleUbBannerGate(maxConfirmationDelayNanos = 1_000L)

            assertTrue(gate.update(emptyMap(), true, false, 200L).isEmpty())
            assertEquals(
                "$role timestamped before the banner must survive one-frame delivery delay",
                mapOf(role to 150L),
                gate.update(mapOf(role to 150L), true, true, 250L)
            )
        }
    }

    @Test
    fun `banner before tp candidate never confirms that candidate`() {
        val gate = RoleUbBannerGate(maxConfirmationDelayNanos = 1_000L)

        assertTrue(gate.update(emptyMap(), true, false, 100L).isEmpty())
        assertTrue(gate.update(mapOf(CharacterRole.ROLE_3 to 150L), true, true, 200L).isEmpty())
        assertTrue(gate.update(emptyMap(), false, true, 300L).isEmpty())
        assertTrue(gate.update(emptyMap(), false, false, 400L).isEmpty())
        assertTrue(gate.update(emptyMap(), true, false, 500L).isEmpty())
    }

    @Test
    fun `tp drop during an existing banner is discarded`() {
        val gate = RoleUbBannerGate(maxConfirmationDelayNanos = 1_000L)

        gate.update(emptyMap(), true, false, 100L)
        gate.update(mapOf(CharacterRole.ROLE_2 to 150L), true, true, 200L)
        gate.update(emptyMap(), false, false, 300L)

        assertTrue(gate.update(emptyMap(), true, false, 400L).isEmpty())
    }

    @Test
    fun `pre-banner candidate delivered after cycle opens still confirms`() {
        val gate = RoleUbBannerGate(maxConfirmationDelayNanos = 1_000L)

        assertTrue(gate.update(emptyMap(), true, false, 200L).isEmpty())

        assertEquals(
            mapOf(CharacterRole.ROLE_3 to 150L),
            gate.update(
                candidateTimesNanos = mapOf(CharacterRole.ROLE_3 to 150L),
                currentlyFullRoles = emptySet(),
                bannerRawPresent = true,
                bannerActive = true,
                bannerFrameTimestampNanos = 250L
            )
        )
    }

    @Test
    fun `several roles before one banner are ambiguous and confirm none`() {
        val gate = RoleUbBannerGate(maxConfirmationDelayNanos = 1_000L)

        gate.update(
            mapOf(CharacterRole.ROLE_3 to 100L, CharacterRole.ROLE_4 to 120L),
            false,
            false,
            150L
        )

        assertTrue(gate.update(emptyMap(), true, false, 200L).isEmpty())
    }

    @Test
    fun `false tp drop can recover and disambiguate during the same moving banner cycle`() {
        val gate = RoleUbBannerGate(
            maxConfirmationDelayNanos = 2_000L,
            quickRecoveryCancellationNanos = 500L
        )

        gate.update(
            candidateTimesNanos = mapOf(
                CharacterRole.ROLE_1 to 100L,
                CharacterRole.ROLE_2 to 100L
            ),
            currentlyFullRoles = emptySet(),
            bannerRawPresent = false,
            bannerActive = false,
            bannerFrameTimestampNanos = 100L
        )

        // The normal blue wave becomes detectable while the initial portrait
        // flash still makes both TP bars plausible, so confirmation must wait.
        assertTrue(
            gate.update(
                candidateTimesNanos = emptyMap(),
                currentlyFullRoles = emptySet(),
                bannerRawPresent = true,
                bannerActive = false,
                bannerFrameTimestampNanos = 300L
            ).isEmpty()
        )

        // ROLE_2 returns to full while the same banner is still moving. ROLE_1
        // remains low and is now the only valid UB source.
        assertEquals(
            mapOf(CharacterRole.ROLE_1 to 100L),
            gate.update(
                candidateTimesNanos = emptyMap(),
                currentlyFullRoles = setOf(CharacterRole.ROLE_2),
                bannerRawPresent = true,
                bannerActive = true,
                bannerFrameTimestampNanos = 420L
            )
        )

        // A banner cycle may confirm at most once.
        assertTrue(
            gate.update(
                candidateTimesNanos = emptyMap(),
                currentlyFullRoles = setOf(CharacterRole.ROLE_2),
                bannerRawPresent = true,
                bannerActive = true,
                bannerFrameTimestampNanos = 450L
            ).isEmpty()
        )
    }

    @Test
    fun `quickly recovered false candidate is removed before banner`() {
        val gate = RoleUbBannerGate(
            maxConfirmationDelayNanos = 10_000L,
            quickRecoveryCancellationNanos = 500L
        )

        gate.update(
            candidateTimesNanos = mapOf(
                CharacterRole.ROLE_1 to 100L,
                CharacterRole.ROLE_2 to 100L
            ),
            currentlyFullRoles = emptySet(),
            bannerRawPresent = false,
            bannerActive = false,
            bannerFrameTimestampNanos = 100L
        )
        gate.update(
            candidateTimesNanos = emptyMap(),
            currentlyFullRoles = setOf(CharacterRole.ROLE_2),
            bannerRawPresent = false,
            bannerActive = false,
            bannerFrameTimestampNanos = 350L
        )

        assertEquals(
            mapOf(CharacterRole.ROLE_1 to 100L),
            gate.update(
                candidateTimesNanos = emptyMap(),
                currentlyFullRoles = emptySet(),
                bannerRawPresent = true,
                bannerActive = false,
                bannerFrameTimestampNanos = 8_000L
            )
        )
    }

    @Test
    fun `late recovery does not erase a real candidate`() {
        val gate = RoleUbBannerGate(
            maxConfirmationDelayNanos = 10_000L,
            quickRecoveryCancellationNanos = 500L
        )

        gate.update(
            candidateTimesNanos = mapOf(CharacterRole.ROLE_1 to 100L),
            currentlyFullRoles = emptySet(),
            bannerRawPresent = false,
            bannerActive = false,
            bannerFrameTimestampNanos = 100L
        )
        gate.update(
            candidateTimesNanos = emptyMap(),
            currentlyFullRoles = setOf(CharacterRole.ROLE_1),
            bannerRawPresent = false,
            bannerActive = false,
            bannerFrameTimestampNanos = 700L
        )

        assertEquals(
            mapOf(CharacterRole.ROLE_1 to 100L),
            gate.update(emptyMap(), true, false, 8_000L)
        )
    }

    @Test
    fun `default window covers an immediately following character ub banner`() {
        val gate = RoleUbBannerGate()
        val candidateAt = 100_000_000L
        val bannerAt = candidateAt + 1_500_000_000L

        gate.update(
            candidateTimesNanos = mapOf(CharacterRole.ROLE_1 to candidateAt),
            currentlyFullRoles = emptySet(),
            bannerRawPresent = false,
            bannerActive = false,
            bannerFrameTimestampNanos = candidateAt
        )

        assertEquals(
            mapOf(CharacterRole.ROLE_1 to candidateAt),
            gate.update(emptyMap(), true, false, bannerAt)
        )
    }

    @Test
    fun `default window rejects a stale candidate before a later banner`() {
        val gate = RoleUbBannerGate()
        val candidateAt = 100_000_000L
        val bannerAt = candidateAt + 2_000_000_001L

        gate.update(
            candidateTimesNanos = mapOf(CharacterRole.ROLE_1 to candidateAt),
            currentlyFullRoles = emptySet(),
            bannerRawPresent = false,
            bannerActive = false,
            bannerFrameTimestampNanos = candidateAt
        )

        assertTrue(gate.update(emptyMap(), true, false, bannerAt).isEmpty())
    }

    @Test
    fun `candidate after the banner frame timestamp cannot confirm that banner`() {
        val gate = RoleUbBannerGate(maxConfirmationDelayNanos = 1_000L)

        assertTrue(
            gate.update(
                mapOf(CharacterRole.ROLE_5 to 250L),
                bannerRawPresent = true,
                bannerActive = false,
                bannerFrameTimestampNanos = 200L
            ).isEmpty()
        )
    }

    @Test
    fun `expired candidate is not confirmed`() {
        val gate = RoleUbBannerGate(maxConfirmationDelayNanos = 100L)

        gate.update(mapOf(CharacterRole.ROLE_1 to 100L), false, false, 100L)

        assertTrue(gate.update(emptyMap(), true, false, 201L).isEmpty())
    }

    @Test
    fun `full battle fourteen banner starts produce exactly ten character ub events`() {
        val gate = RoleUbBannerGate(maxConfirmationDelayNanos = 2_000_000_000L)
        val flashEvents = listOf(
            8.806 to CharacterRole.ROLE_3,
            16.039 to CharacterRole.ROLE_3,
            30.850 to CharacterRole.ROLE_1,
            37.036 to CharacterRole.ROLE_2,
            42.791 to CharacterRole.ROLE_4,
            55.955 to CharacterRole.ROLE_5,
            62.463 to CharacterRole.ROLE_3,
            69.927 to CharacterRole.ROLE_3,
            82.239 to CharacterRole.ROLE_2,
            92.812 to CharacterRole.ROLE_4
        )
        val bannerStarts = listOf(
            9.021, 16.267, 31.053, 38.092,
            43.026, 43.176,
            56.182, 56.487,
            62.714,
            70.156, 74.798,
            83.314,
            93.089, 93.232
        )
        val expected = listOf(
            CharacterRole.ROLE_3,
            CharacterRole.ROLE_3,
            CharacterRole.ROLE_1,
            CharacterRole.ROLE_2,
            CharacterRole.ROLE_4,
            CharacterRole.ROLE_5,
            CharacterRole.ROLE_3,
            CharacterRole.ROLE_3,
            CharacterRole.ROLE_2,
            CharacterRole.ROLE_4
        )

        fun ns(seconds: Double) = (seconds * 1_000_000_000L).toLong()

        val confirmed = mutableListOf<CharacterRole>()
        var flashIndex = 0
        bannerStarts.forEach { bannerAt ->
            while (flashIndex < flashEvents.size && flashEvents[flashIndex].first <= bannerAt) {
                val (flashAt, role) = flashEvents[flashIndex]
                gate.update(
                    candidateTimesNanos = emptyMap(),
                    flashRoleTimesNanos = mapOf(role to ns(flashAt)),
                    bannerRawPresent = false,
                    bannerActive = false,
                    bannerFrameTimestampNanos = ns(flashAt)
                )
                flashIndex++
            }

            confirmed += gate.update(
                candidateTimesNanos = emptyMap(),
                bannerRawPresent = true,
                bannerActive = false,
                bannerFrameTimestampNanos = ns(bannerAt)
            ).keys

            // Force a closed cycle before the next raw-present edge so the
            // three known split banner starts exercise duplicate prevention.
            gate.update(
                candidateTimesNanos = emptyMap(),
                bannerRawPresent = false,
                bannerActive = false,
                bannerFrameTimestampNanos = ns(bannerAt + 0.05)
            )
        }

        assertEquals(expected, confirmed)
    }
}
