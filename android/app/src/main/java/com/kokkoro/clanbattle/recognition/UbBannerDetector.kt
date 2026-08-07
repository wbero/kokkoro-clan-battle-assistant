package com.kokkoro.clanbattle.recognition

import com.kokkoro.clanbattle.capture.FixedTemplateMatcher
import kotlin.math.roundToInt

data class UbBannerDetection(
    val active: Boolean,
    val score: Double,
    val rawPresent: Boolean,
    val colorScore: Double = score,
    val leftScore: Double = 0.0,
    val rightScore: Double = 0.0
)

/**
 * Detects the fixed blue-gray skill-name banner while deliberately ignoring
 * the changing text in its centre. The banner is used as a generic UB animation
 * boundary, not as a character identity signal.
 */
class UbBannerDetector(
    private val leftTemplate: PixelImage? = null,
    private val rightTemplate: PixelImage? = null,
    private val minPresentFrames: Int = 2,
    private val minAbsentFrames: Int = 3,
    private val colorThreshold: Double = 0.72,
    private val minSideScore: Double = 0.60,
    private val pairedStrongSideScore: Double = 0.70,
    private val strongSideScore: Double = 0.80
) {
    private val compactLeftTemplate = leftTemplate?.let(::compactTemplate)
    private val compactRightTemplate = rightTemplate?.let(::compactTemplate)
    private var presentFrames = 0
    private var absentFrames = 0
    private var active = false

    init {
        require(minPresentFrames >= 1)
        require(minAbsentFrames >= 1)
        require(colorThreshold in 0.0..1.0)
        require(minSideScore in 0.0..1.0)
        require(pairedStrongSideScore in minSideScore..1.0)
        require(strongSideScore in pairedStrongSideScore..1.0)
        require((leftTemplate == null) == (rightTemplate == null))
    }

    fun detect(image: PixelImage): UbBannerDetection {
        val searchImage = downsample(image, SEARCH_DOWNSAMPLE_FACTOR)
        val colorScore = colorScore(searchImage)
        val leftScore = compactLeftTemplate?.let { template ->
            bestWaveScore(
                searchImage,
                template,
                referenceTemplateWidth = requireNotNull(leftTemplate).width,
                referenceTemplateHeight = leftTemplate.height,
                referenceXRange = LEFT_X_RANGE
            )
        } ?: 0.0
        val rightScore = compactRightTemplate?.let { template ->
            bestWaveScore(
                searchImage,
                template,
                referenceTemplateWidth = requireNotNull(rightTemplate).width,
                referenceTemplateHeight = rightTemplate.height,
                referenceXRange = RIGHT_X_RANGE
            )
        } ?: 0.0
        val hasTemplates = leftTemplate != null && rightTemplate != null
        val bothSidesPresent = minOf(leftScore, rightScore) >= minSideScore &&
            maxOf(leftScore, rightScore) >= pairedStrongSideScore
        val strongSideWithColor = colorScore >= colorThreshold &&
            maxOf(leftScore, rightScore) >= strongSideScore
        val rawPresent = if (hasTemplates) {
            bothSidesPresent || strongSideWithColor
        } else {
            colorScore >= colorThreshold
        }
        val score = if (hasTemplates) {
            maxOf(
                minOf(leftScore, rightScore),
                (colorScore + maxOf(leftScore, rightScore)) / 2.0
            ).coerceIn(0.0, 1.0)
        } else {
            colorScore
        }

        updateState(rawPresent)

        return UbBannerDetection(
            active = active,
            score = score,
            rawPresent = rawPresent,
            colorScore = colorScore,
            leftScore = leftScore,
            rightScore = rightScore
        )
    }

    private fun colorScore(image: PixelImage): Double {
        val rowRatios = DoubleArray(image.height) { y ->
            var matching = 0
            repeat(image.width) { x ->
                if (isBannerBlue(image[x, y])) matching += 1
            }
            matching.toDouble() / image.width
        }
        val qualifying = rowRatios.map { it >= MIN_BLUE_ROW_RATIO }
        var longestRun = 0
        var currentRun = 0
        qualifying.forEach { matches ->
            currentRun = if (matches) currentRun + 1 else 0
            longestRun = maxOf(longestRun, currentRun)
        }

        val maxRowRatio = rowRatios.maxOrNull() ?: 0.0
        val verticalCoverage = longestRun.toDouble() / image.height
        val score = (
            (verticalCoverage / EXPECTED_VERTICAL_COVERAGE).coerceIn(0.0, 1.0) * 0.55 +
                (maxRowRatio / EXPECTED_HORIZONTAL_COVERAGE).coerceIn(0.0, 1.0) * 0.45
            ).coerceIn(0.0, 1.0)
        return score
    }

    private fun updateState(rawPresent: Boolean) {
        if (rawPresent) {
            presentFrames += 1
            absentFrames = 0
            if (presentFrames >= minPresentFrames) active = true
        } else {
            absentFrames += 1
            presentFrames = 0
            if (absentFrames >= minAbsentFrames) active = false
        }
    }

    private fun bestWaveScore(
        image: PixelImage,
        template: PixelImage,
        referenceTemplateWidth: Int,
        referenceTemplateHeight: Int,
        referenceXRange: IntRange
    ): Double {
        var best = 0.0
        val scaledTemplateBaseWidth =
            (referenceTemplateWidth * image.width.toDouble() / REFERENCE_WIDTH).roundToInt().coerceAtLeast(2)
        val scaledTemplateBaseHeight =
            (referenceTemplateHeight * image.height.toDouble() / REFERENCE_HEIGHT).roundToInt().coerceAtLeast(2)

        for (referenceX in referenceXRange step SEARCH_X_STEP) {
            val left = referenceX * image.width / REFERENCE_WIDTH
            for (referenceY in SEARCH_Y_RANGE step SEARCH_Y_STEP) {
                val top = referenceY * image.height / REFERENCE_HEIGHT
                for (scale in TEMPLATE_SCALES) {
                    val width = (scaledTemplateBaseWidth * scale).roundToInt().coerceAtLeast(2)
                    val height = (scaledTemplateBaseHeight * scale).roundToInt().coerceAtLeast(2)
                    if (left + width > image.width || top + height > image.height) continue
                    best = maxOf(
                        best,
                        FixedTemplateMatcher.scoreWindow(
                            image = image,
                            left = left,
                            top = top,
                            width = width,
                            height = height,
                            template = template
                        )
                    )
                }
            }
        }
        return best
    }

    private fun compactTemplate(template: PixelImage): PixelImage {
        val targetHeight = minOf(COMPACT_TEMPLATE_HEIGHT, template.height)
        val targetWidth = (template.width * targetHeight.toDouble() / template.height)
            .roundToInt()
            .coerceAtLeast(2)
        return resizeNearest(template, targetWidth, targetHeight)
    }

    private fun downsample(image: PixelImage, factor: Int): PixelImage {
        if (factor <= 1 || image.width < factor * 2 || image.height < factor * 2) return image
        val width = image.width / factor
        val height = image.height / factor
        val pixels = IntArray(width * height)
        repeat(height) { y ->
            repeat(width) { x ->
                var red = 0
                var green = 0
                var blue = 0
                var alpha = 0
                repeat(factor) { dy ->
                    repeat(factor) { dx ->
                        val color = image[x * factor + dx, y * factor + dy]
                        alpha += color ushr 24 and 0xff
                        red += color ushr 16 and 0xff
                        green += color ushr 8 and 0xff
                        blue += color and 0xff
                    }
                }
                val count = factor * factor
                pixels[y * width + x] =
                    ((alpha / count) shl 24) or
                    ((red / count) shl 16) or
                    ((green / count) shl 8) or
                    (blue / count)
            }
        }
        return PixelImage(width, height, pixels)
    }

    private fun resizeNearest(image: PixelImage, width: Int, height: Int): PixelImage {
        val pixels = IntArray(width * height)
        repeat(height) { y ->
            val sourceY = (y * image.height / height).coerceAtMost(image.height - 1)
            repeat(width) { x ->
                val sourceX = (x * image.width / width).coerceAtMost(image.width - 1)
                pixels[y * width + x] = image[sourceX, sourceY]
            }
        }
        return PixelImage(width, height, pixels)
    }

    fun reset() {
        presentFrames = 0
        absentFrames = 0
        active = false
    }

    companion object {
        private const val MIN_BLUE_ROW_RATIO = 0.42
        private const val EXPECTED_VERTICAL_COVERAGE = 0.36
        private const val EXPECTED_HORIZONTAL_COVERAGE = 0.72
        private const val REFERENCE_WIDTH = 800
        private const val REFERENCE_HEIGHT = 110
        private const val SEARCH_DOWNSAMPLE_FACTOR = 2
        private const val COMPACT_TEMPLATE_HEIGHT = 19
        private val LEFT_X_RANGE = 8..72
        private val RIGHT_X_RANGE = 648..733
        private val SEARCH_Y_RANGE = 20..40
        private const val SEARCH_X_STEP = 4
        private const val SEARCH_Y_STEP = 4
        private val TEMPLATE_SCALES = doubleArrayOf(0.96, 1.0, 1.04)

        fun isBannerBlue(color: Int): Boolean {
            val red = color ushr 16 and 0xff
            val green = color ushr 8 and 0xff
            val blue = color and 0xff
            return blue >= red + 24 &&
                blue >= green + 14 &&
                red in 70..190 &&
                green in 80..210 &&
                blue in 120..235
        }
    }
}
