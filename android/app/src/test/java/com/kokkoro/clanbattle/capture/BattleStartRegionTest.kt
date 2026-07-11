package com.kokkoro.clanbattle.capture

import com.kokkoro.clanbattle.recognition.PixelImage
import ar.com.hjg.pngj.PngReaderInt
import org.junit.Assert.assertTrue
import org.junit.Test

class BattleStartRegionTest {
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
