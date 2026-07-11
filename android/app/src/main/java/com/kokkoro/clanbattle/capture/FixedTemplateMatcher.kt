package com.kokkoro.clanbattle.capture

import com.kokkoro.clanbattle.recognition.PixelImage
import kotlin.math.sqrt

object FixedTemplateMatcher {
    fun matches(image: PixelImage, template: PixelImage, threshold: Double): Boolean =
        score(image, template) >= threshold

    fun score(image: PixelImage, template: PixelImage): Double {
        val count = template.width * template.height
        var sumImage = 0.0
        var sumTemplate = 0.0
        repeat(template.height) { y ->
            repeat(template.width) { x ->
                sumImage += luminance(image[map(x, template.width, image.width), map(y, template.height, image.height)])
                sumTemplate += luminance(template[x, y])
            }
        }
        val meanImage = sumImage / count
        val meanTemplate = sumTemplate / count
        var numerator = 0.0
        var imageDenominator = 0.0
        var templateDenominator = 0.0
        repeat(template.height) { y ->
            repeat(template.width) { x ->
                val imageDelta = luminance(
                    image[map(x, template.width, image.width), map(y, template.height, image.height)]
                ) - meanImage
                val templateDelta = luminance(template[x, y]) - meanTemplate
                numerator += imageDelta * templateDelta
                imageDenominator += imageDelta * imageDelta
                templateDenominator += templateDelta * templateDelta
            }
        }
        if (imageDenominator == 0.0 || templateDenominator == 0.0) return 0.0
        return numerator / sqrt(imageDenominator * templateDenominator)
    }

    private fun map(value: Int, sourceSize: Int, targetSize: Int): Int =
        (value * targetSize / sourceSize).coerceAtMost(targetSize - 1)

    private fun luminance(color: Int): Double {
        val red = color shr 16 and 0xff
        val green = color shr 8 and 0xff
        val blue = color and 0xff
        return 0.299 * red + 0.587 * green + 0.114 * blue
    }
}
