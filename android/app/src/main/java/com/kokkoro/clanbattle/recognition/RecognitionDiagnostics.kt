package com.kokkoro.clanbattle.recognition

enum class DigitSlot {
    MINUTE,
    SECOND_TENS,
    SECOND_ONES
}

data class DigitRecognitionTrace(
    val slot: DigitSlot,
    val crop: PixelImage,
    val allowedRange: IntRange,
    val decisionScores: Map<Int, Double>,
    val nccScores: Map<Int, Double>,
    val scoreKind: ScoreKind,
    val rawTop1: Int,
    val rawTop2: Int,
    val rawMargin: Double,
    val chosen: Int,
    val chosenScore: Double,
    val decisionMargin: Double,
    val decisionRule: String
) {
    @Deprecated("Use decisionScores")
    val scores: Map<Int, Double> get() = decisionScores
}

data class ClockRecognitionTrace(
    val groups: List<IntRange>,
    val digits: List<DigitRecognitionTrace>
)
