package com.kokkoro.clanbattle.recognition

import kotlin.math.min
import kotlin.math.roundToInt

object DigitNormalizer {
    const val DEFAULT_CANVAS_SIZE = 24
    const val DEFAULT_PADDING = 2

    fun normalize(
        image: PixelImage,
        canvasSize: Int = DEFAULT_CANVAS_SIZE,
        padding: Int = DEFAULT_PADDING
    ): BinaryImage {
        require(canvasSize > 0)
        require(padding >= 0 && padding * 2 < canvasSize)

        val grayscale = grayscale(image)
        val threshold = otsuThreshold(grayscale)
            ?: return BinaryImage(canvasSize, canvasSize, BooleanArray(canvasSize * canvasSize))
        val binary = binarizeDark(image.width, image.height, grayscale, threshold)
        val bounds = binary.foregroundBounds()
            ?: return BinaryImage(canvasSize, canvasSize, BooleanArray(canvasSize * canvasSize))
        val available = canvasSize - padding * 2
        val scale = min(available.toDouble() / bounds.width, available.toDouble() / bounds.height)
        val targetWidth = (bounds.width * scale).roundToInt().coerceIn(1, available)
        val targetHeight = (bounds.height * scale).roundToInt().coerceIn(1, available)
        val offsetX = (canvasSize - targetWidth) / 2
        val offsetY = (canvasSize - targetHeight) / 2
        val output = BooleanArray(canvasSize * canvasSize)

        repeat(targetHeight) { targetY ->
            val sourceY = bounds.top + targetY * bounds.height / targetHeight
            repeat(targetWidth) { targetX ->
                val sourceX = bounds.left + targetX * bounds.width / targetWidth
                if (binary[sourceX, sourceY]) {
                    output[(offsetY + targetY) * canvasSize + offsetX + targetX] = true
                }
            }
        }
        return BinaryImage(canvasSize, canvasSize, output)
    }

    fun grayscale(image: PixelImage): IntArray = IntArray(image.pixels.size) { index ->
        val color = image.pixels[index]
        val red = (color ushr 16) and 0xff
        val green = (color ushr 8) and 0xff
        val blue = color and 0xff
        ((red * 299 + green * 587 + blue * 114 + 500) / 1000).coerceIn(0, 255)
    }

    fun otsuThreshold(grayscale: IntArray): Int? {
        require(grayscale.isNotEmpty())
        val histogram = IntArray(256)
        var minimum = 255
        var maximum = 0
        grayscale.forEach { value ->
            require(value in 0..255)
            histogram[value] += 1
            if (value < minimum) minimum = value
            if (value > maximum) maximum = value
        }
        if (minimum == maximum) return null

        val total = grayscale.size
        var totalSum = 0L
        histogram.forEachIndexed { value, count -> totalSum += value.toLong() * count }
        var backgroundCount = 0
        var backgroundSum = 0L
        var bestVariance = -1.0
        var bestThreshold = 0

        for (threshold in 0 until 255) {
            val count = histogram[threshold]
            backgroundCount += count
            backgroundSum += threshold.toLong() * count
            if (backgroundCount == 0) continue
            val foregroundCount = total - backgroundCount
            if (foregroundCount == 0) break

            val backgroundMean = backgroundSum.toDouble() / backgroundCount
            val foregroundMean = (totalSum - backgroundSum).toDouble() / foregroundCount
            val meanDifference = backgroundMean - foregroundMean
            val variance = backgroundCount.toDouble() * foregroundCount * meanDifference * meanDifference
            if (variance > bestVariance) {
                bestVariance = variance
                bestThreshold = threshold
            }
        }
        return bestThreshold
    }

    fun binarizeDark(
        width: Int,
        height: Int,
        grayscale: IntArray,
        threshold: Int
    ): BinaryImage {
        require(width > 0 && height > 0)
        require(grayscale.size == width * height)
        require(threshold in 0..255)
        return BinaryImage(width, height, BooleanArray(grayscale.size) { grayscale[it] <= threshold })
    }
}
