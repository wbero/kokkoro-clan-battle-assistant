package com.kokkoro.clanbattle.capture

import com.kokkoro.clanbattle.recognition.PixelImage
import ar.com.hjg.pngj.PngReaderInt
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class BattleStartRegionTest {
    @Test fun `energy HUD tightly contains all five relative energy regions`() {
        assertEquals(ReferenceRegion(385, 1027, 1152, 25), BattleReferenceRegions.ENERGY_HUD)
        assertEquals(
            mapOf(
                com.kokkoro.clanbattle.recognition.CharacterRole.ROLE_5 to com.kokkoro.clanbattle.recognition.EnergyRegion(8, 6, 176, 13),
                com.kokkoro.clanbattle.recognition.CharacterRole.ROLE_4 to com.kokkoro.clanbattle.recognition.EnergyRegion(248, 6, 176, 13),
                com.kokkoro.clanbattle.recognition.CharacterRole.ROLE_3 to com.kokkoro.clanbattle.recognition.EnergyRegion(488, 6, 176, 13),
                com.kokkoro.clanbattle.recognition.CharacterRole.ROLE_2 to com.kokkoro.clanbattle.recognition.EnergyRegion(728, 6, 176, 13),
                com.kokkoro.clanbattle.recognition.CharacterRole.ROLE_1 to com.kokkoro.clanbattle.recognition.EnergyRegion(968, 6, 176, 13)
            ),
            BattleReferenceRegions.ENERGY_REGIONS
        )
        BattleReferenceRegions.ENERGY_REGIONS.values.forEach { region ->
            assertTrue(region.x >= 0 && region.y >= 0)
            assertTrue(region.x + region.width <= BattleReferenceRegions.ENERGY_HUD.width)
            assertTrue(region.y + region.height <= BattleReferenceRegions.ENERGY_HUD.height)
        }
    }

    @Test fun `energy regions scale with extracted HUD dimensions and remain in bounds`() {
        val scaled = BattleReferenceRegions.energyRegionsForHud(576, 13)
        assertEquals(com.kokkoro.clanbattle.recognition.EnergyRegion(4, 3, 88, 6), scaled.getValue(com.kokkoro.clanbattle.recognition.CharacterRole.ROLE_5))
        scaled.values.forEach { region ->
            assertTrue(region.x + region.width <= 576)
            assertTrue(region.y + region.height <= 13)
        }
    }
    @Test
    fun `configured start region matches the real party screen`() {
        val screen = loadImage("battle/party_screen_1920x1080.png")
        val template = loadImage("battle/start_battle.png")
        val region = BattleReferenceRegions.START_BUTTON
        val crop = screen.crop(region.x, region.y, region.width, region.height)

        val score = FixedTemplateMatcher.score(crop, template)

        assertTrue("expected start button score >= 0.72 but was $score", score >= 0.72)
    }

    @Test
    fun `configured loading region matches stable loading text`() {
        val screen = loadImage("battle/loading_screen_1920x1080.png")
        val template = loadImage("battle/loading.png")
        val region = BattleReferenceRegions.LOADING
        val crop = screen.crop(region.x, region.y, region.width, region.height)

        val score = FixedTemplateMatcher.score(crop, template)

        assertTrue("expected loading text score >= 0.72 but was $score", score >= 0.72)
    }

    private fun loadImage(path: String): PixelImage {
        val reader = PngReaderInt(requireNotNull(javaClass.classLoader?.getResourceAsStream(path)))
        val pixels = IntArray(reader.imgInfo.cols * reader.imgInfo.rows)
        repeat(reader.imgInfo.rows) { y ->
            val line = reader.readRowInt()
            repeat(reader.imgInfo.cols) { x ->
                val offset = x * reader.imgInfo.channels
                val red = line.scanline[offset]
                val green = if (reader.imgInfo.greyscale) red else line.scanline[offset + 1]
                val blue = if (reader.imgInfo.greyscale) red else line.scanline[offset + 2]
                val alphaOffset = if (reader.imgInfo.greyscale) 1 else 3
                val alpha = if (reader.imgInfo.alpha) line.scanline[offset + alphaOffset] else 255
                pixels[y * reader.imgInfo.cols + x] =
                    (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        reader.end()
        return PixelImage(reader.imgInfo.cols, reader.imgInfo.rows, pixels)
    }
}
