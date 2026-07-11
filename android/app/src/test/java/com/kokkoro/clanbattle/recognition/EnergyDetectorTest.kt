package com.kokkoro.clanbattle.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyDetectorTest {
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
}
