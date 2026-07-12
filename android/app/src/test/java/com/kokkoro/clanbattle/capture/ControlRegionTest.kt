package com.kokkoro.clanbattle.capture

import com.kokkoro.clanbattle.recognition.CharacterRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlRegionTest {
    @Test
    fun `control regions match the 1920 by 1080 reference layout`() {
        assertEquals(ReferenceRegion(1740, 20, 170, 65), BattleReferenceRegions.MENU_BUTTON)
        assertEquals(ReferenceRegion(1760, 620, 140, 130), BattleReferenceRegions.GLOBAL_SET_BUTTON)
        assertEquals(ReferenceRegion(1760, 770, 140, 130), BattleReferenceRegions.AUTO_BUTTON)
        assertEquals(
            mapOf(
                CharacterRole.ROLE_1 to ReferenceRegion(550, 771, 54, 53),
                CharacterRole.ROLE_2 to ReferenceRegion(790, 771, 54, 53),
                CharacterRole.ROLE_3 to ReferenceRegion(1030, 771, 54, 53),
                CharacterRole.ROLE_4 to ReferenceRegion(1270, 771, 54, 53),
                CharacterRole.ROLE_5 to ReferenceRegion(1510, 771, 54, 53)
            ),
            BattleReferenceRegions.ROLE_SET_BADGES
        )
    }

    @Test
    fun `control regions remain in bounds at reference and proportional sizes`() {
        val regions = listOf(
            BattleReferenceRegions.MENU_BUTTON,
            BattleReferenceRegions.GLOBAL_SET_BUTTON,
            BattleReferenceRegions.AUTO_BUTTON
        ) + BattleReferenceRegions.ROLE_SET_BADGES.values

        assertRegionsInBounds(regions, 1920, 1080)
        assertRegionsInBounds(regions.map { it.scaledBy(1, 2) }, 960, 540)
    }

    private fun assertRegionsInBounds(regions: Collection<ReferenceRegion>, width: Int, height: Int) {
        regions.forEach { region ->
            assertTrue(region.x >= 0 && region.y >= 0)
            assertTrue(region.width > 0 && region.height > 0)
            assertTrue(region.x + region.width <= width)
            assertTrue(region.y + region.height <= height)
        }
    }

    private fun ReferenceRegion.scaledBy(numerator: Int, denominator: Int) = ReferenceRegion(
        x = x * numerator / denominator,
        y = y * numerator / denominator,
        width = width * numerator / denominator,
        height = height * numerator / denominator
    )
}
