package com.kokkoro.clanbattle.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyDetectorTest {
    @Test
    fun `real partial bars report horizontal fill extent`() {
        val result = EnergyDetector(realEnergyRegions()).detect(
            loadPngResource("energy/partial.png")
        )

        val actual = rolesLeftToRight.map { role ->
            result.characters.getValue(role).blueRatio
        }
        val expected = listOf(0.909f, 0.386f, 0.688f, 0.847f, 0.812f)

        expected.zip(actual).forEachIndexed { index, (expectedRatio, actualRatio) ->
            assertEquals("bar $index", expectedRatio, actualRatio, 0.02f)
        }
    }

    @Test
    fun `real frame with one full bar reports the second bar as full`() {
        val result = EnergyDetector(realEnergyRegions()).detect(
            loadPngResource("energy/one_full.png")
        )

        assertEquals(1f, result.characters.getValue(CharacterRole.ROLE_4).blueRatio, 0.02f)
        assertTrue(result.characters.getValue(CharacterRole.ROLE_4).isFull)
    }

    @Test
    fun `real frame with four empty bars reports only the last bar near full`() {
        val result = EnergyDetector(realEnergyRegions()).detect(
            loadPngResource("energy/four_empty.png")
        )

        rolesLeftToRight.take(4).forEach { role ->
            assertEquals(0f, result.characters.getValue(role).blueRatio, 0.02f)
        }
        val lastBar = result.characters.getValue(CharacterRole.ROLE_1)
        assertTrue(lastBar.blueRatio > 0.94f)
        assertTrue(lastBar.isFull)
    }

    @Test
    fun `isolated blue column is removed by five column majority smoothing`() {
        val width = 10
        val height = 5
        val pixels = IntArray(width * height) { rgb(100, 100, 100) }
        for (y in 0 until height) {
            for (x in 0 until 5) pixels[y * width + x] = rgb(0, 0, 120)
            pixels[y * width + 9] = rgb(0, 0, 120)
        }
        val region = EnergyRegion(x = 0, y = 0, width = width, height = height)
        val detector = EnergyDetector(CharacterRole.entries.associateWith { region })

        val result = detector.detect(PixelImage(width, height, pixels))

        assertEquals(0.5f, result.characters.getValue(CharacterRole.ROLE_1).blueRatio, 0f)
    }

    @Test
    fun `blue pixel requires blue to exceed red green and minimum thresholds`() {
        assertTrue(EnergyDetector.isBluePixel(rgb(20, 30, 120)))
        assertFalse(EnergyDetector.isBluePixel(rgb(80, 30, 120)))
        assertFalse(EnergyDetector.isBluePixel(rgb(20, 90, 120)))
        assertFalse(EnergyDetector.isBluePixel(rgb(0, 0, 80)))
    }

    @Test
    fun `detect reports blue ratio for all five role crops`() {
        val detector = EnergyDetector(fiveSinglePixelRegions())
        val image = PixelImage(
            width = 5,
            height = 1,
            pixels = intArrayOf(
                rgb(0, 0, 120),
                rgb(90, 90, 90),
                rgb(10, 20, 100),
                rgb(100, 10, 100),
                rgb(20, 30, 120)
            )
        )

        val result = detector.detect(image)

        assertEquals(5, result.characters.size)
        assertEquals(1f, result.characters.getValue(CharacterRole.ROLE_1).blueRatio, 0f)
        assertEquals(0f, result.characters.getValue(CharacterRole.ROLE_2).blueRatio, 0f)
        assertEquals(1f, result.characters.getValue(CharacterRole.ROLE_3).blueRatio, 0f)
        assertEquals(0f, result.characters.getValue(CharacterRole.ROLE_4).blueRatio, 0f)
        assertEquals(1f, result.characters.getValue(CharacterRole.ROLE_5).blueRatio, 0f)
    }

    @Test
    fun `first frame has no delta and later frame reports per role and average delta`() {
        val detector = EnergyDetector(fiveSinglePixelRegions())

        val first = detector.detect(solidRolePixels(blueRoles = setOf(CharacterRole.ROLE_1)))
        val second = detector.detect(
            solidRolePixels(blueRoles = setOf(CharacterRole.ROLE_1, CharacterRole.ROLE_2))
        )

        assertNull(first.energyDelta)
        assertTrue(first.characters.values.all { it.delta == null })
        assertEquals(0f, second.characters.getValue(CharacterRole.ROLE_1).delta!!, 0f)
        assertEquals(1f, second.characters.getValue(CharacterRole.ROLE_2).delta!!, 0f)
        assertEquals(0.2f, second.energyDelta!!, 0f)
    }

    @Test
    fun `full to below thirty percent triggers only on the transition frame`() {
        val regions = CharacterRole.entries.associateWith { role ->
            EnergyRegion(x = role.ordinal * 10, y = 0, width = 10, height = 1)
        }
        val detector = EnergyDetector(regions)

        val full = detector.detect(roleOneRatio(9))
        val dropped = detector.detect(roleOneRatio(2))
        val remainsLow = detector.detect(roleOneRatio(1))

        assertTrue(full.characters.getValue(CharacterRole.ROLE_1).isFull)
        assertTrue(full.triggeredRoles.isEmpty())
        assertEquals(setOf(CharacterRole.ROLE_1), dropped.triggeredRoles)
        assertTrue(dropped.characters.getValue(CharacterRole.ROLE_1).triggered)
        assertTrue(remainsLow.triggeredRoles.isEmpty())
        assertFalse(remainsLow.characters.getValue(CharacterRole.ROLE_1).triggered)
    }

    @Test
    fun `reset makes next detection a first frame again`() {
        val detector = EnergyDetector(fiveSinglePixelRegions())
        detector.detect(solidRolePixels(blueRoles = setOf(CharacterRole.ROLE_1)))
        detector.reset()

        val result = detector.detect(solidRolePixels(blueRoles = emptySet()))

        assertNull(result.energyDelta)
        assertTrue(result.characters.values.all { it.delta == null })
        assertTrue(result.triggeredRoles.isEmpty())
    }

    private fun fiveSinglePixelRegions(): Map<CharacterRole, EnergyRegion> =
        CharacterRole.entries.associateWith { role ->
            EnergyRegion(x = role.ordinal, y = 0, width = 1, height = 1)
        }

    private fun realEnergyRegions(): Map<CharacterRole, EnergyRegion> = mapOf(
        CharacterRole.ROLE_5 to EnergyRegion(x = 8, y = 6, width = 176, height = 13),
        CharacterRole.ROLE_4 to EnergyRegion(x = 248, y = 6, width = 176, height = 13),
        CharacterRole.ROLE_3 to EnergyRegion(x = 488, y = 6, width = 176, height = 13),
        CharacterRole.ROLE_2 to EnergyRegion(x = 728, y = 6, width = 176, height = 13),
        CharacterRole.ROLE_1 to EnergyRegion(x = 968, y = 6, width = 176, height = 13)
    )

    private fun solidRolePixels(blueRoles: Set<CharacterRole>): PixelImage = PixelImage(
        width = 5,
        height = 1,
        pixels = CharacterRole.entries.map { role ->
            if (role in blueRoles) rgb(0, 0, 120) else rgb(100, 100, 100)
        }.toIntArray()
    )

    private fun roleOneRatio(bluePixelCount: Int): PixelImage {
        val pixels = IntArray(50) { rgb(100, 100, 100) }
        repeat(bluePixelCount) { pixels[it] = rgb(0, 0, 120) }
        return PixelImage(width = 50, height = 1, pixels = pixels)
    }

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        (0xff shl 24) or (red shl 16) or (green shl 8) or blue

    private companion object {
        val rolesLeftToRight = listOf(
            CharacterRole.ROLE_5,
            CharacterRole.ROLE_4,
            CharacterRole.ROLE_3,
            CharacterRole.ROLE_2,
            CharacterRole.ROLE_1
        )
    }
}
