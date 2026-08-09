package com.kokkoro.clanbattle.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleUbFlashDetectorTest {
    @Test
    fun `real pre-flash video frame has no dominant role`() {
        val result = RoleUbFlashDetector().detect(
            loadPngResource("role_ub_flash/baseline.png")
        )

        assertNull(result.role)
        assertNull(result.rawRole)
    }

    @Test
    fun `real video frame locates role three despite role five false full tp`() {
        val result = RoleUbFlashDetector().detect(
            loadPngResource("role_ub_flash/role3_flash_role5_false_full.png")
        )

        assertEquals(CharacterRole.ROLE_3, result.role)
        assertEquals(CharacterRole.ROLE_3, result.rawRole)
        assertTrue(result.strongestScore >= 0.80f)
        assertTrue(result.margin >= 0.12f)
    }

    @Test
    fun `real video flash plus false role five tp confirms only role three`() {
        val detector = RoleUbFlashDetector()
        val gate = RoleUbBannerGate(maxConfirmationDelayNanos = 1_000L)
        detector.detect(loadPngResource("role_ub_flash/baseline.png"))
        val flash = detector.detect(
            loadPngResource("role_ub_flash/role3_flash_role5_false_full.png")
        )

        gate.update(
            candidateTimesNanos = mapOf(CharacterRole.ROLE_5 to 150L),
            flashRoleTimesNanos = mapOf(requireNotNull(flash.role) to 100L),
            bannerRawPresent = false,
            bannerActive = false,
            bannerFrameTimestampNanos = 150L
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
    fun `real role two origin is accepted but weak expanding tail is ignored`() {
        val detector = RoleUbFlashDetector()

        val origin = detector.detect(
            loadPngResource("role_ub_flash/role2_flash_origin.png")
        )
        val drift = detector.detect(
            loadPngResource("role_ub_flash/role2_flash_drift_role4.png")
        )
        val tail = detector.detect(
            loadPngResource("role_ub_flash/role2_flash_drift_tail.png")
        )

        assertEquals(CharacterRole.ROLE_2, origin.role)
        assertEquals(CharacterRole.ROLE_2, origin.rawRole)
        assertNull(drift.role)
        assertNull(drift.rawRole)
        assertNull(tail.role)
        assertNull(tail.rawRole)
    }

    @Test
    fun `independent strong flashes can identify different roles without a long lived lock`() {
        val detector = RoleUbFlashDetector()
        assertEquals(CharacterRole.ROLE_2, detector.detect(syntheticFlash(CharacterRole.ROLE_2)).role)
        assertNull(detector.detect(blankFlashFrame()).role)
        assertEquals(CharacterRole.ROLE_5, detector.detect(syntheticFlash(CharacterRole.ROLE_5)).role)
    }

    @Test
    fun `weak portrait glow is not treated as a character ub flash`() {
        val result = RoleUbFlashDetector().detect(syntheticFlash(CharacterRole.ROLE_4, fillFraction = 0.6f))

        assertNull(result.role)
        assertNull(result.rawRole)
        assertTrue(result.strongestScore < RoleUbFlashDetector.DEFAULT_MINIMUM_SCORE)
    }

    @Test
    fun `phone strength single frame flash can confirm immediately`() {
        val result = RoleUbFlashDetector().detect(
            syntheticFlash(CharacterRole.ROLE_5, fillFraction = 0.78f)
        )

        assertEquals(CharacterRole.ROLE_5, result.rawRole)
        assertEquals(CharacterRole.ROLE_5, result.role)
        assertTrue(result.strongestScore >= RoleUbFlashDetector.DEFAULT_IMMEDIATE_SCORE)
    }

    @Test
    fun `high strength narrow margin flash is exposed only as a corroboration hint`() {
        val result = RoleUbFlashDetector().detect(
            syntheticCompetingFlash(
                strongestRole = CharacterRole.ROLE_3,
                strongestFraction = 0.86f,
                neighbourRole = CharacterRole.ROLE_4,
                neighbourFraction = 0.75f
            )
        )

        assertNull(result.rawRole)
        assertNull(result.role)
        assertEquals(CharacterRole.ROLE_3, result.borderlineRole)
        assertTrue(result.strongestScore >= RoleUbFlashDetector.DEFAULT_IMMEDIATE_SCORE)
        assertTrue(result.margin >= RoleUbFlashDetector.DEFAULT_CORROBORATED_MINIMUM_MARGIN)
        assertTrue(result.margin < RoleUbFlashDetector.DEFAULT_MINIMUM_MARGIN)
    }

    @Test
    fun `same detector geometry works for every role slot`() {
        CharacterRole.entries.forEach { role ->
            val result = RoleUbFlashDetector().detect(syntheticFlash(role))

            assertEquals(role, result.role)
            assertEquals(role, result.rawRole)
        }
    }

    @Test
    fun `full battle video identifies all ten real character ub flashes`() {
        val cases = listOf(
            "119_role3.png" to CharacterRole.ROLE_3,
            "118_role3.png" to CharacterRole.ROLE_3,
            "109a_role1.png" to CharacterRole.ROLE_1,
            "109b_role2.png" to CharacterRole.ROLE_2,
            "108_role4.png" to CharacterRole.ROLE_4,
            "104a_role5.png" to CharacterRole.ROLE_5,
            "104b_role3.png" to CharacterRole.ROLE_3,
            "103_role3.png" to CharacterRole.ROLE_3,
            "057_role2.png" to CharacterRole.ROLE_2,
            "051_role4.png" to CharacterRole.ROLE_4
        )

        cases.forEach { (fileName, expectedRole) ->
            val result = RoleUbFlashDetector().detect(
                loadPngResource("role_ub_flash/full_battle/$fileName")
            )
            assertEquals(fileName, expectedRole, result.role)
            assertEquals(fileName, expectedRole, result.rawRole)
            assertTrue(fileName, result.strongestScore >= RoleUbFlashDetector.DEFAULT_MINIMUM_SCORE)
        }
    }

    @Test
    fun `full battle non character skill flash stays below strong ub threshold`() {
        val result = RoleUbFlashDetector().detect(
            loadPngResource("role_ub_flash/full_battle/non_role_103_extra.png")
        )

        assertNull(result.role)
        assertNull(result.rawRole)
        assertTrue(result.strongestScore < RoleUbFlashDetector.DEFAULT_MINIMUM_SCORE)
    }

    private fun syntheticFlash(role: CharacterRole, fillFraction: Float = 1f): PixelImage {
        val width = RoleUbFlashDetector.REFERENCE_WIDTH
        val height = 40
        val pixels = IntArray(width * height) { rgb(20, 20, 20) }
        val left = role.ordinal * RoleUbFlashDetector.ROLE_STRIDE
        val fillWidth = (RoleUbFlashDetector.ROLE_WIDTH * fillFraction).toInt()
        repeat(height) { y ->
            repeat(fillWidth) { offset ->
                pixels[y * width + left + offset] = rgb(255, 240, 120)
            }
        }
        return PixelImage(width, height, pixels)
    }

    private fun syntheticCompetingFlash(
        strongestRole: CharacterRole,
        strongestFraction: Float,
        neighbourRole: CharacterRole,
        neighbourFraction: Float
    ): PixelImage {
        val width = RoleUbFlashDetector.REFERENCE_WIDTH
        val height = 40
        val pixels = IntArray(width * height) { rgb(20, 20, 20) }
        listOf(
            strongestRole to strongestFraction,
            neighbourRole to neighbourFraction
        ).forEach { (role, fillFraction) ->
            val left = role.ordinal * RoleUbFlashDetector.ROLE_STRIDE
            val fillWidth = (RoleUbFlashDetector.ROLE_WIDTH * fillFraction).toInt()
            repeat(height) { y ->
                repeat(fillWidth) { offset ->
                    pixels[y * width + left + offset] = rgb(255, 240, 120)
                }
            }
        }
        return PixelImage(width, height, pixels)
    }

    private fun blankFlashFrame(): PixelImage = PixelImage(
        RoleUbFlashDetector.REFERENCE_WIDTH,
        40,
        IntArray(RoleUbFlashDetector.REFERENCE_WIDTH * 40) { rgb(20, 20, 20) }
    )

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        (0xff shl 24) or (red shl 16) or (green shl 8) or blue
}
