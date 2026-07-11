package com.kokkoro.clanbattle.recognition

data class ClockCandidate(
    val timeSeconds: Int,
    val rawText: String,
    val score: Double,
    val isPrimary: Boolean
)

data class RecognitionResult(
    val ok: Boolean,
    val timeSeconds: Int? = null,
    val rawText: String? = null,
    val confidence: Double = 0.0,
    val reason: String? = null,
    val candidates: List<ClockCandidate> = emptyList(),
    val debugTrace: ClockRecognitionTrace? = null
) {
    companion object {
        fun ok(timeSeconds: Int, rawText: String, confidence: Double) = RecognitionResult(
            ok = true,
            timeSeconds = timeSeconds,
            rawText = rawText,
            confidence = confidence
        )
    }
}

enum class ReadingSource {
    PRIMARY,
    ALTERNATIVE
}

data class FilterResult(
    val accepted: Boolean,
    val reason: String? = null,
    val timeSeconds: Int? = null,
    val rawText: String? = null,
    val source: ReadingSource? = null,
    val shouldPause: Boolean = false
)
