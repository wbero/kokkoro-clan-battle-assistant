package com.kokkoro.clanbattle.capture

import com.kokkoro.clanbattle.recognition.PixelImage
import java.util.IdentityHashMap
import kotlin.math.sqrt

object FixedTemplateMatcher {
    private data class TemplateStats(
        val luminance: DoubleArray,
        val mean: Double,
        val denominator: Double
    )

    private val templateStatsCache = IdentityHashMap<PixelImage, TemplateStats>()

    fun matches(image: PixelImage, template: PixelImage, threshold: Double): Boolean =
        score(image, template) >= threshold

    fun score(image: PixelImage, template: PixelImage): Double {
        val count = template.width * template.height
        var sumImage = 0.0
        val stats = templateStats(template)
        repeat(template.height) { y ->
            repeat(template.width) { x ->
                sumImage += luminance(image[map(x, template.width, image.width), map(y, template.height, image.height)])
            }
        }
        val meanImage = sumImage / count
        var numerator = 0.0
        var imageDenominator = 0.0
        repeat(template.height) { y ->
            repeat(template.width) { x ->
                val imageDelta = luminance(
                    image[map(x, template.width, image.width), map(y, template.height, image.height)]
                ) - meanImage
                val templateDelta = stats.luminance[y * template.width + x] - stats.mean
                numerator += imageDelta * templateDelta
                imageDenominator += imageDelta * imageDelta
            }
        }
        if (imageDenominator == 0.0 || stats.denominator == 0.0) return 0.0
        return numerator / sqrt(imageDenominator * stats.denominator)
    }

    /**
     * Finds a template whose rendered size and position can change inside [image].
     * This is intended for animated HUD badges; fixed controls should keep using [score].
     */
    fun bestScaleScore(
        image: PixelImage,
        template: PixelImage,
        scales: DoubleArray = doubleArrayOf(1.0, 0.94, 1.08, 0.86, 1.16, 0.78, 1.24)
    ): Double {
        val stats = templateStats(template)
        val templateMean = stats.mean
        val templateDenominator = stats.denominator
        if (templateDenominator == 0.0) return 0.0
        var best = -1.0
        scales.forEach { scale ->
            val width = (template.width * scale).toInt().coerceIn(2, image.width)
            val height = (template.height * scale).toInt().coerceIn(2, image.height)
            val centerX = (image.width - width) / 2
            val centerY = (image.height - height) / 2
            LOCAL_OFFSETS.forEach { (offsetX, offsetY) ->
                val left = (centerX + offsetX).coerceIn(0, image.width - width)
                val top = (centerY + offsetY).coerceIn(0, image.height - height)
                best = maxOf(
                    best,
                    scoreWindow(
                        image, left, top, width, height, template, stats.luminance,
                        templateMean, templateDenominator
                    )
                )
            }
        }
        return best
    }

    /**
     * Combines glyph correlation with the cyan fill of the animated SET badge.
     * The fill remains stable while the glyph changes size during its breathing animation.
     */
    fun animatedBadgeScore(image: PixelImage, template: PixelImage): Double {
        val coverage = cyanCoverage(image)
        if (coverage <= MAX_CLEAR_OFF_COVERAGE) return 0.0
        val resolutionScale = if (image.width == template.width && image.height == template.height) {
            1.0
        } else {
            minOf(
                image.width.toDouble() / REFERENCE_BADGE_CROP_WIDTH,
                image.height.toDouble() / REFERENCE_BADGE_CROP_HEIGHT
            )
        }
        val structureScore = bestScaleScore(
            image,
            template,
            BADGE_ANIMATION_SCALES.map { it * resolutionScale }.toDoubleArray()
        )
        if (structureScore < MIN_BADGE_STRUCTURE_SCORE) return structureScore
        return if (coverage >= MIN_CYAN_ON_COVERAGE) {
            maxOf(structureScore, minOf(1.0, 0.75 + coverage - MIN_CYAN_ON_COVERAGE))
        } else {
            structureScore
        }
    }

    private fun cyanCoverage(image: PixelImage): Double {
        val cyanPixels = image.pixels.count { color ->
            val red = color shr 16 and 0xff
            val green = color shr 8 and 0xff
            val blue = color and 0xff
            green > 110 && blue > 110 &&
                green - red > 35 && blue - red > 35 &&
                kotlin.math.abs(green - blue) < 100
        }
        return cyanPixels.toDouble() / image.pixels.size
    }

    private fun scoreWindow(
        image: PixelImage,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        template: PixelImage,
        templateLuminance: DoubleArray,
        templateMean: Double,
        templateDenominator: Double
    ): Double {
        val count = template.width * template.height
        var sumImage = 0.0
        repeat(template.height) { y ->
            val imageY = top + map(y, template.height, height)
            repeat(template.width) { x ->
                val imageX = left + map(x, template.width, width)
                sumImage += luminance(image[imageX, imageY])
            }
        }
        val meanImage = sumImage / count
        var numerator = 0.0
        var imageDenominator = 0.0
        repeat(template.height) { y ->
            val imageY = top + map(y, template.height, height)
            repeat(template.width) { x ->
                val imageX = left + map(x, template.width, width)
                val imageDelta = luminance(image[imageX, imageY]) - meanImage
                val templateDelta = templateLuminance[y * template.width + x] - templateMean
                numerator += imageDelta * templateDelta
                imageDenominator += imageDelta * imageDelta
            }
        }
        if (imageDenominator == 0.0) return 0.0
        return numerator / sqrt(imageDenominator * templateDenominator)
    }

    private val LOCAL_OFFSETS = arrayOf(0 to 0, -2 to 0, 2 to 0, 0 to -2, 0 to 2)
    private val BADGE_ANIMATION_SCALES = doubleArrayOf(1.0, 0.94, 1.08, 0.86, 1.16, 0.78, 1.24)
    private const val REFERENCE_BADGE_CROP_WIDTH = 74.0
    private const val REFERENCE_BADGE_CROP_HEIGHT = 73.0
    private const val MIN_CYAN_ON_COVERAGE = 0.63
    private const val MAX_CLEAR_OFF_COVERAGE = 0.45
    private const val MIN_BADGE_STRUCTURE_SCORE = 0.35

    private fun map(value: Int, sourceSize: Int, targetSize: Int): Int =
        (value * targetSize / sourceSize).coerceAtMost(targetSize - 1)

    private fun luminance(color: Int): Double {
        val red = color shr 16 and 0xff
        val green = color shr 8 and 0xff
        val blue = color and 0xff
        return 0.299 * red + 0.587 * green + 0.114 * blue
    }

    @Synchronized
    private fun templateStats(template: PixelImage): TemplateStats =
        templateStatsCache[template] ?: run {
            val values = DoubleArray(template.pixels.size) { index -> luminance(template.pixels[index]) }
            val mean = values.average()
            val denominator = values.sumOf { value ->
                val delta = value - mean
                delta * delta
            }
            TemplateStats(values, mean, denominator).also { templateStatsCache[template] = it }
        }
}
