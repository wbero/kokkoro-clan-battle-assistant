package com.kokkoro.clanbattle.recognition

import java.text.Normalizer
import kotlin.math.max

data class UbSkillNameMatch(
    val role: CharacterRole?,
    val bestRole: CharacterRole,
    val recognizedText: String,
    val expectedName: String,
    val score: Double,
    val margin: Double,
    val roleScores: Map<CharacterRole, Double>
)

/**
 * Pure matcher used after OCR. Runtime only compares the banner text against
 * the at-most-five UB names declared by the selected axis, so OCR does not need
 * to solve an open-vocabulary character-name problem.
 */
class UbSkillNameMatcher(
    private val minimumScore: Double = DEFAULT_MINIMUM_SCORE,
    private val minimumMargin: Double = DEFAULT_MINIMUM_MARGIN
) {
    init {
        require(minimumScore in 0.0..1.0)
        require(minimumMargin in 0.0..1.0)
    }

    fun match(
        recognizedTexts: Collection<String>,
        expectedNames: Map<CharacterRole, String>
    ): UbSkillNameMatch? {
        if (recognizedTexts.isEmpty() || expectedNames.isEmpty()) return null

        val candidates = recognizedTexts
            .map(::normalize)
            .filter { it.length >= MIN_NORMALIZED_LENGTH }
            .distinct()
        if (candidates.isEmpty()) return null

        val roleScores = expectedNames.mapValues { (_, expected) ->
            val normalizedExpected = normalize(expected)
            if (normalizedExpected.length < MIN_NORMALIZED_LENGTH) {
                0.0
            } else {
                candidates.maxOfOrNull { candidate -> similarity(candidate, normalizedExpected) } ?: 0.0
            }
        }
        val ranked = roleScores.entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull() ?: return null
        val secondScore = ranked.getOrNull(1)?.value ?: 0.0
        val margin = best.value - secondScore
        val recognized = recognizedTexts.maxByOrNull { text ->
            similarity(normalize(text), normalize(expectedNames.getValue(best.key)))
        }.orEmpty()
        val role = best.key.takeIf { best.value >= minimumScore && margin >= minimumMargin }

        return UbSkillNameMatch(
            role = role,
            bestRole = best.key,
            recognizedText = recognized,
            expectedName = expectedNames.getValue(best.key),
            score = best.value,
            margin = margin,
            roleScores = roleScores
        )
    }

    internal fun normalize(text: String): String = buildString {
        Normalizer.normalize(text, Normalizer.Form.NFKC).forEach { ch ->
            if (Character.isLetterOrDigit(ch)) append(ch.lowercaseChar())
        }
    }

    internal fun similarity(left: String, right: String): Double {
        if (left.isEmpty() || right.isEmpty()) return 0.0
        if (left == right) return 1.0
        val distanceScore = 1.0 - levenshtein(left, right).toDouble() / max(left.length, right.length)
        val lcs = longestCommonSubsequence(left, right)
        val sequenceDice = 2.0 * lcs / (left.length + right.length).toDouble()
        return max(distanceScore, sequenceDice).coerceIn(0.0, 1.0)
    }

    private fun levenshtein(left: String, right: String): Int {
        var previous = IntArray(right.length + 1) { it }
        left.forEachIndexed { leftIndex, leftChar ->
            val current = IntArray(right.length + 1)
            current[0] = leftIndex + 1
            right.forEachIndexed { rightIndex, rightChar ->
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + if (leftChar == rightChar) 0 else 1
                )
            }
            previous = current
        }
        return previous[right.length]
    }

    private fun longestCommonSubsequence(left: String, right: String): Int {
        var previous = IntArray(right.length + 1)
        left.forEach { leftChar ->
            val current = IntArray(right.length + 1)
            right.forEachIndexed { rightIndex, rightChar ->
                current[rightIndex + 1] = if (leftChar == rightChar) {
                    previous[rightIndex] + 1
                } else {
                    maxOf(current[rightIndex], previous[rightIndex + 1])
                }
            }
            previous = current
        }
        return previous[right.length]
    }

    companion object {
        const val DEFAULT_MINIMUM_SCORE = 0.72
        const val DEFAULT_MINIMUM_MARGIN = 0.10
        private const val MIN_NORMALIZED_LENGTH = 2
    }
}
