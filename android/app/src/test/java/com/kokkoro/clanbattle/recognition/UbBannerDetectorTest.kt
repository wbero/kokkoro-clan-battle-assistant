package com.kokkoro.clanbattle.recognition

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UbBannerDetectorTest {
    @Test
    fun `fixed blue background activates even with a large text gap`() {
        val detector = UbBannerDetector(minPresentFrames = 2, minAbsentFrames = 2)
        val banner = bannerImage(withTextGap = true)

        val candidate = detector.detect(banner)
        val active = detector.detect(banner)

        assertTrue(candidate.rawPresent)
        assertFalse(candidate.active)
        assertTrue(active.rawPresent)
        assertTrue(active.active)
    }

    @Test
    fun `white battle background does not look like a skill banner`() {
        val detector = UbBannerDetector(minPresentFrames = 1)
        val blank = PixelImage(100, 30, IntArray(3_000) { rgb(245, 248, 250) })

        val result = detector.detect(blank)

        assertFalse(result.rawPresent)
        assertFalse(result.active)
    }

    @Test
    fun `active banner needs consecutive absent frames before clearing`() {
        val detector = UbBannerDetector(minPresentFrames = 1, minAbsentFrames = 2)
        val banner = bannerImage(withTextGap = false)
        val blank = PixelImage(100, 30, IntArray(3_000) { rgb(245, 248, 250) })
        assertTrue(detector.detect(banner).active)

        assertTrue(detector.detect(blank).active)
        assertFalse(detector.detect(blank).active)
    }

    @Test
    fun `two wave ornaments detect a banner even when its blue background is gone`() {
        val left = ornamentTemplate(seed = 1, width = 75, height = 57)
        val right = ornamentTemplate(seed = 2, width = 67, height = 58)
        val detector = UbBannerDetector(left, right, minPresentFrames = 1)
        val image = blankReferenceBanner().withTemplate(left, x = 32, y = 28)
            .withTemplate(right, x = 700, y = 28)

        val result = detector.detect(image)

        assertTrue("left=${result.leftScore}", result.leftScore >= 0.70)
        assertTrue("right=${result.rightScore}", result.rightScore >= 0.70)
        assertTrue(result.rawPresent)
        assertTrue(result.active)
    }

    @Test
    fun `one strong ornament plus blue background survives partial ornament occlusion`() {
        val left = ornamentTemplate(seed = 1, width = 75, height = 57)
        val right = ornamentTemplate(seed = 2, width = 67, height = 58)
        val detector = UbBannerDetector(left, right, minPresentFrames = 1)
        var image = bannerReferenceImage()
        image = image.withTemplate(right, x = 700, y = 28)

        val result = detector.detect(image)

        assertTrue(result.colorScore >= 0.72)
        assertTrue("right=${result.rightScore}", result.rightScore >= 0.80)
        assertTrue(result.rawPresent)
    }

    @Test
    fun `empty frame without ornaments remains absent`() {
        val detector = UbBannerDetector(
            ornamentTemplate(seed = 1, width = 75, height = 57),
            ornamentTemplate(seed = 2, width = 67, height = 58),
            minPresentFrames = 1
        )

        val result = detector.detect(blankReferenceBanner())

        assertFalse(result.rawPresent)
        assertFalse(result.active)
    }

    private fun bannerImage(withTextGap: Boolean): PixelImage {
        val width = 100
        val height = 30
        val pixels = IntArray(width * height) { rgb(245, 248, 250) }
        for (y in 8..21) {
            for (x in 4..95) {
                if (withTextGap && x in 42..58) continue
                pixels[y * width + x] = rgb(132, 148, 181)
            }
        }
        return PixelImage(width, height, pixels)
    }

    private fun blankReferenceBanner(): PixelImage =
        PixelImage(800, 110, IntArray(800 * 110) { rgb(245, 248, 250) })

    private fun bannerReferenceImage(): PixelImage {
        val pixels = IntArray(800 * 110) { rgb(245, 248, 250) }
        for (y in 28..81) {
            for (x in 20..779) pixels[y * 800 + x] = rgb(132, 148, 181)
        }
        return PixelImage(800, 110, pixels)
    }

    private fun ornamentTemplate(seed: Int, width: Int, height: Int): PixelImage {
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val blockX = x * 8 / width
            val blockY = y * 6 / height
            val bright =
                (blockX + blockY + seed) % 4 == 0 ||
                    kotlin.math.abs(blockX - blockY - seed) <= 1
            if (bright) rgb(248, 250, 252) else rgb(70, 86, 130)
        }
        return PixelImage(width, height, pixels)
    }

    private fun PixelImage.withTemplate(template: PixelImage, x: Int, y: Int): PixelImage {
        val copy = pixels.copyOf()
        repeat(template.height) { row ->
            repeat(template.width) { column ->
                copy[(y + row) * width + x + column] = template[column, row]
            }
        }
        return PixelImage(width, height, copy)
    }

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        (0xff shl 24) or (red shl 16) or (green shl 8) or blue
}
