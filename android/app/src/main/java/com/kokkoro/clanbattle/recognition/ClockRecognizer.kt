package com.kokkoro.clanbattle.recognition

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class ClockRecognizer(
    private val templates: DigitTemplates,
    private val scorer: ((PixelImage, PixelImage) -> Double)? = null
) {
    private data class ColumnGroup(val x: Int, val width: Int)
    private data class DigitScore(val digit: Int, val score: Double)
    private data class DigitMatch(
        val digit: Int,
        val score: Double,
        val confidence: Double,
        val margin: Double,
        val secondDigit: Int,
        val secondScore: Double,
        val thirdDigit: Int,
        val thirdScore: Double
    )
    private data class DiagnosticMatch(
        val match: DigitMatch,
        val trace: DigitRecognitionTrace
    )

    fun recognize(
        image: PixelImage,
        minConfidence: Double = 0.8,
        includeDiagnostics: Boolean = false
    ): RecognitionResult {
        val groups = foregroundColumnGroups(image)
        if (groups.size < 4) return RecognitionResult(false, reason = "clock-split-failed")

        val minuteImage = cropGroup(image, groups[0], 2)
        val tensImage = cropGroup(image, groups[2], 1)
        val onesImage = cropGroup(image, groups[3], 1)

        val minuteDiagnostic = if (includeDiagnostics) {
            diagnosticMatch(DigitSlot.MINUTE, minuteImage, 0..1)
        } else null
        val minuteMatch = minuteDiagnostic?.match ?: bestMatch(minuteImage, 0..1)
        val maxTens = if (minuteMatch.digit == 1) 3 else 5
        val tensDiagnostic = if (includeDiagnostics) {
            diagnosticMatch(DigitSlot.SECOND_TENS, tensImage, 0..maxTens)
        } else null
        val onesDiagnostic = if (includeDiagnostics) {
            diagnosticMatch(DigitSlot.SECOND_ONES, onesImage, 0..9)
        } else null
        val tensMatch = tensDiagnostic?.match ?: bestMatch(tensImage, 0..maxTens)
        val onesMatch = onesDiagnostic?.match ?: bestMatch(onesImage, 0..9)

        val seconds = tensMatch.digit * 10 + onesMatch.digit
        val totalSeconds = minuteMatch.digit * 60 + seconds
        val rawText = formatClock(totalSeconds)
        val confidence = min(minuteMatch.confidence, min(tensMatch.confidence, onesMatch.confidence))
        val inRange = totalSeconds in 1..90
        val candidates = buildCandidates(minuteMatch, tensMatch, onesMatch)

        return RecognitionResult(
            ok = inRange && confidence >= minConfidence,
            timeSeconds = totalSeconds,
            rawText = rawText,
            confidence = confidence,
            reason = when {
                !inRange -> "out-of-range"
                confidence < minConfidence -> "low-confidence"
                else -> null
            },
            candidates = candidates,
            debugTrace = if (includeDiagnostics) {
                ClockRecognitionTrace(
                    groups = groups.map { it.x until (it.x + it.width) },
                    digits = listOf(
                        requireNotNull(minuteDiagnostic).trace,
                        requireNotNull(tensDiagnostic).trace,
                        requireNotNull(onesDiagnostic).trace
                    )
                )
            } else null
        )
    }

    private fun diagnosticMatch(slot: DigitSlot, image: PixelImage, range: IntRange): DiagnosticMatch {
        val scores = (0..9).associateWith { digit ->
            score(image, requireNotNull(templates.digits[digit]))
        }
        val rawRanking = scores.entries.sortedByDescending(Map.Entry<Int, Double>::value)
        val allowedRanking = rawRanking.filter { it.key in range }
        val best = allowedRanking[0]
        val second = allowedRanking[1]
        val third = allowedRanking.getOrElse(2) { second }
        val margin = best.value - second.value
        return DiagnosticMatch(
            match = DigitMatch(
                digit = best.key,
                score = best.value,
                confidence = confidenceFromNcc(best.value, margin),
                margin = margin,
                secondDigit = second.key,
                secondScore = second.value,
                thirdDigit = third.key,
                thirdScore = third.value
            ),
            trace = DigitRecognitionTrace(
                slot = slot,
                crop = image.copy(pixels = image.pixels.copyOf()),
                allowedRange = range,
                scores = scores,
                rawTop1 = rawRanking[0].key,
                rawTop2 = rawRanking[1].key,
                rawMargin = rawRanking[0].value - rawRanking[1].value,
                chosen = best.key,
                chosenScore = best.value,
                decisionMargin = margin,
                decisionRule = "highest-score-in-allowed-range"
            )
        )
    }

    private fun buildCandidates(
        minuteMatch: DigitMatch,
        tensMatch: DigitMatch,
        onesMatch: DigitMatch
    ): List<ClockCandidate> {
        val tensOptions = distinctOptions(tensMatch)
        val onesOptions = distinctOptions(onesMatch)
        return buildList {
            for (tens in tensOptions) {
                for (ones in onesOptions) {
                    val seconds = tens.digit * 10 + ones.digit
                    val total = minuteMatch.digit * 60 + seconds
                    if (total !in 1..90) continue
                    add(
                        ClockCandidate(
                            timeSeconds = total,
                            rawText = formatClock(total),
                            score = min(minuteMatch.score, min(tens.score, ones.score)),
                            isPrimary = tens.digit == tensMatch.digit && ones.digit == onesMatch.digit
                        )
                    )
                }
            }
        }.sortedByDescending(ClockCandidate::score)
    }

    private fun distinctOptions(match: DigitMatch): List<DigitScore> = buildList {
        add(DigitScore(match.digit, match.score))
        if (match.secondDigit != match.digit) add(DigitScore(match.secondDigit, match.secondScore))
        if (match.thirdDigit != match.digit && match.thirdDigit != match.secondDigit) {
            add(DigitScore(match.thirdDigit, match.thirdScore))
        }
    }

    private fun bestMatch(image: PixelImage, range: IntRange): DigitMatch {
        val scores = range.map { digit ->
            DigitScore(digit, score(image, requireNotNull(templates.digits[digit])))
        }.sortedByDescending(DigitScore::score)
        val best = scores.first()
        val second = scores.getOrElse(1) { DigitScore(best.digit, 0.0) }
        val third = scores.getOrElse(2) { second }
        val margin = best.score - second.score
        return DigitMatch(
            digit = best.digit,
            score = best.score,
            confidence = confidenceFromNcc(best.score, margin),
            margin = margin,
            secondDigit = second.digit,
            secondScore = second.score,
            thirdDigit = third.digit,
            thirdScore = third.score
        )
    }

    private fun foregroundColumnGroups(image: PixelImage): List<ColumnGroup> {
        var sum = 0L
        image.pixels.forEach { sum += luminance(it) }
        val average = sum.toDouble() / image.pixels.size
        val threshold = average - 80.0
        val minDarkPixels = max(2, floor(image.height * 0.06).toInt())
        val darkCounts = IntArray(image.width)

        repeat(image.width) { x ->
            var count = 0
            repeat(image.height) { y ->
                if (luminance(image[x, y]) < threshold) count += 1
            }
            darkCounts[x] = count
        }

        val groups = mutableListOf<ColumnGroup>()
        var start = -1
        darkCounts.forEachIndexed { x, count ->
            if (count >= minDarkPixels && start < 0) start = x
            val ends = start >= 0 && (count < minDarkPixels || x == darkCounts.lastIndex)
            if (ends) {
                val end = if (count < minDarkPixels) x - 1 else x
                groups += ColumnGroup(start, end - start + 1)
                start = -1
            }
        }
        return groups
    }

    private fun cropGroup(image: PixelImage, group: ColumnGroup, padding: Int): PixelImage {
        val left = max(0, group.x - padding)
        val right = min(image.width, group.x + group.width + padding)
        return image.crop(left, 0, right - left, image.height)
    }

    private fun score(first: PixelImage, second: PixelImage): Double =
        scorer?.invoke(first, second) ?: ncc(first, second)

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
        return luminance(image[sourceX, sourceY]).toDouble()
    }

    private fun confidenceFromNcc(score: Double, margin: Double): Double {
        val scoreConfidence = (score - 0.05) / 0.30
        val marginConfidence = margin / 0.10
        return min(scoreConfidence, marginConfidence).coerceIn(0.0, 1.0)
    }

    private fun luminance(color: Int): Int =
        ((color shr 16) and 0xff) + ((color shr 8) and 0xff) + (color and 0xff)

    private fun formatClock(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

}
