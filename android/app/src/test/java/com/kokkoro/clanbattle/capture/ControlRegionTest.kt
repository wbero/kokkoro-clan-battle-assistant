package com.kokkoro.clanbattle.capture

import com.kokkoro.clanbattle.recognition.CharacterRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlRegionTest {
    @Test
    fun `control regions match the 1920 by 1080 reference layout`() {
        assertEquals(ReferenceRegion(1740, 20, 170, 65), BattleReferenceRegions.MENU_BUTTON)
        assertEquals(ReferenceRegion(1788, 644, 87, 86), BattleReferenceRegions.GLOBAL_SET_BUTTON)
        assertEquals(ReferenceRegion(1783, 795, 95, 78), BattleReferenceRegions.AUTO_BUTTON)
        assertEquals(CharacterRole.entries.toSet(), BattleReferenceRegions.ROLE_SET_BADGES.keys)
        CharacterRole.entries.forEach { role ->
            assertEquals(
                ReferenceRegion(550 + role.ordinal * 240, 771, 54, 53),
                BattleReferenceRegions.ROLE_SET_BADGES.getValue(role)
            )
        }
    }

    @Test
    fun `control regions remain in bounds at reference and proportional sizes`() {
        val regions = listOf(
            BattleReferenceRegions.MENU_BUTTON,
            BattleReferenceRegions.GLOBAL_SET_BUTTON,
            BattleReferenceRegions.AUTO_BUTTON
        ) + BattleReferenceRegions.ROLE_SET_BADGES.values

        assertRegionsInBounds(regions, 1920, 1080)

        val scaled = regions.map { region ->
            ImageRoiExtractor.scaleRegion(
                width = 960,
                height = 540,
                x = region.x,
                y = region.y,
                regionWidth = region.width,
                regionHeight = region.height
            )
        }
        with(scaled.first()) {
            assertEquals(870, left)
            assertEquals(10, top)
            assertEquals(955, right)
            assertEquals(42, bottom)
        }
        scaled.forEach { region ->
            assertTrue(region.left >= 0 && region.top >= 0)
            assertTrue(region.right - region.left > 0 && region.bottom - region.top > 0)
            assertTrue(region.right <= 960)
            assertTrue(region.bottom <= 540)
        }
    }

    private fun assertRegionsInBounds(regions: Collection<ReferenceRegion>, width: Int, height: Int) {
        regions.forEach { region ->
            assertTrue(region.x >= 0 && region.y >= 0)
            assertTrue(region.width > 0 && region.height > 0)
            assertTrue(region.x + region.width <= width)
            assertTrue(region.y + region.height <= height)
        }
    }

}
