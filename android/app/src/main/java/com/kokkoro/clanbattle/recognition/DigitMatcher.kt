package com.kokkoro.clanbattle.recognition

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class ScoreKind {
    STRUCTURAL_IOU
}

data class DigitMatchResult(
    val digit: Int,
    val score: Double,
    val confidence: Double,
    val margin: Double,
    val secondDigit: Int,
    val secondScore: Double,
    val thirdDigit: Int,
    val thirdScore: Double,
    val decisionScores: Map<Int, Double>?,
    val nccScores: Map<Int, Double>?,
    val scoreKind: ScoreKind = ScoreKind.STRUCTURAL_IOU
)

class DigitMatcher(
    private val templates: DigitTemplates,
    private val normalizer: (PixelImage) -> BinaryImage = { DigitNormalizer.normalize(it) },
    private val decisionScorer: ((BinaryImage, Int) -> Double)? = null,
    private val nccScorer: ((PixelImage, PixelImage) -> Double)? = null
) {
    private data class RankedDigit(val digit: Int, val score: Double)

    private val normalizedTemplates = templates.digits.mapValues { (_, image) -> normalizer(image) }

    fun match(image: PixelImage, allowedRange: IntRange, includeDiagnostics: Boolean): DigitMatchResult {
        val normalized = normalizer(image)
        val decisionDigits = if (includeDiagnostics) 0..9 else allowedRange
        val decisionScores = decisionDigits.associateWith { digit ->
            decisionScorer?.invoke(normalized, digit)
                ?: StructuralMatcher.iou(normalized, normalizedTemplates.getValue(digit))
        }
        val allowedRanking = decisionScores.entries
            .asSequence()
            .filter { it.key in allowedRange }
            .map { RankedDigit(it.key, it.value) }
            .sortedByDescending(RankedDigit::score)
            .toList()
        require(allowedRanking.isNotEmpty()) { "allowedRange must contain at least one digit" }
        val best = allowedRanking[0]
        val second = allowedRanking.getOrElse(1) { RankedDigit(best.digit, 0.0) }
        val third = allowedRanking.getOrElse(2) { second }
        val margin = best.score - second.score
        val diagnosticNcc = if (includeDiagnostics) {
            (0..9).associateWith { digit ->
                nccScorer?.invoke(image, templates.digits.getValue(digit))
                    ?: ncc(image, templates.digits.getValue(digit))
            }
        } else null
        return DigitMatchResult(
            digit = best.digit,
            score = best.score,
            confidence = structuralConfidence(best.score, margin),
            margin = margin,
            secondDigit = second.digit,
            secondScore = second.score,
            thirdDigit = third.digit,
            thirdScore = third.score,
            decisionScores = decisionScores.takeIf { includeDiagnostics },
            nccScores = diagnosticNcc
        )
    }

    private fun ncc(first: PixelImage, second: PixelImage): Double {
        val gridWidth = max(first.width, second.width)
        val gridHeight = max(first.height, second.height)
        val count = gridWidth * gridHeight
        var sumFirst = 0.0
        var sumSecond = 0.0
        repeat(gridHeight) { y ->
            repeat(gridWidth) { x ->
                sumFirst += sampleLuminance(first, x, y, gridWidth, gridHeight)
                sumSecond += sampleLuminance(second, x, y, gridWidth, gridHeight)
            }
        }
        val meanFirst = sumFirst / count
        val meanSecond = sumSecond / count
        var numerator = 0.0
        var denominatorFirst = 0.0
        var denominatorSecond = 0.0
        repeat(gridHeight) { y ->
            repeat(gridWidth) { x ->
                val firstDelta = sampleLuminance(first, x, y, gridWidth, gridHeight) - meanFirst
                val secondDelta = sampleLuminance(second, x, y, gridWidth, gridHeight) - meanSecond
                numerator += firstDelta * secondDelta
                denominatorFirst += firstDelta * firstDelta
                denominatorSecond += secondDelta * secondDelta
            }
        }
        if (denominatorFirst == 0.0 || denominatorSecond == 0.0) return 0.0
        return numerator / sqrt(denominatorFirst * denominatorSecond)
    }

    private fun sampleLuminance(image: PixelImage, x: Int, y: Int, gridWidth: Int, gridHeight: Int): Double {
        val sourceX = min(image.width - 1, x * image.width / gridWidth)
        val sourceY = min(image.height - 1, y * image.height / gridHeight)
        val color = image[sourceX, sourceY]
        return (((color shr 16) and 0xff) + ((color shr 8) and 0xff) + (color and 0xff)).toDouble()
    }

    companion object {
        fun structuralConfidence(score: Double, margin: Double): Double {
            val scoreConfidence = (score - 0.30) / 0.40
            val marginConfidence = margin / 0.12
            return min(scoreConfidence, marginConfidence).coerceIn(0.0, 1.0)
        }
    }
}
