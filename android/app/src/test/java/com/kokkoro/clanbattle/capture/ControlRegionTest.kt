package com.kokkoro.clanbattle.capture

import com.kokkoro.clanbattle.recognition.CharacterRole
import com.kokkoro.clanbattle.recognition.loadPngResource
import com.kokkoro.clanbattle.control.loadBmpResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlRegionTest {
    @Test
    fun `control regions match the 1920 by 1080 reference layout`() {
        assertEquals(ReferenceRegion(1761, 33, 87, 37), BattleReferenceRegions.MENU_BUTTON)
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
            assertEquals(880, left)
            assertEquals(16, top)
            assertEquals(924, right)
            assertEquals(35, bottom)
        }
        scaled.forEach { region ->
            assertTrue(region.left >= 0 && region.top >= 0)
            assertTrue(region.right - region.left > 0 && region.bottom - region.top > 0)
            assertTrue(region.right <= 960)
            assertTrue(region.bottom <= 540)
        }
    }

    @Test
    fun `menu region matches the supplied menu template`() {
        val fixture = loadPngResource("control/set_on_off_on_off_on.png")
        val region = BattleReferenceRegions.MENU_BUTTON
        val crop = fixture.crop(region.x, region.y, region.width, region.height)

        assertTrue(FixedTemplateMatcher.score(crop, loadBmpResource("control/templates/menu.bmp")) > 0.99)
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
