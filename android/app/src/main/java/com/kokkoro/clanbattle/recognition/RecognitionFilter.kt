package com.kokkoro.clanbattle.recognition

class RecognitionFilter(
    private val minConfidence: Double = 0.8,
    private val minAlternativeScore: Double = 0.55,
    private val maxFailedReads: Int = 8,
    private val temporalSlackSeconds: Double = 1.5,
    private val minClockSeconds: Int = 1,
    private val maxClockSeconds: Int = 90,
    private val newBattleGapMs: Long = 5_000
) {
    private var lastAccepted: Int? = null
    private var lastTimestampMs: Long? = null
    private var failedReads = 0
    private var pendingLargeDrop: Int? = null

    private data class Checked(val seconds: Int, val rawText: String)

    private sealed interface TemporalStatus {
        data object Initial : TemporalStatus
        data object Same : TemporalStatus
        data object Accept : TemporalStatus
        data object Confirm : TemporalStatus
        data class Reject(val reason: String) : TemporalStatus
    }

    fun reset() {
        lastAccepted = null
        lastTimestampMs = null
        failedReads = 0
        pendingLargeDrop = null
    }

    fun update(result: RecognitionResult, nowMs: Long, isNewBattle: Boolean = false): FilterResult {
        if (isNewBattle) reset()
        var primaryReason = result.reason ?: "failed"

        if (result.ok && result.timeSeconds == maxClockSeconds) {
            val opening = validate(result.timeSeconds, result.rawText)
            val canAnchorOpening = lastAccepted == null || isStaleIncrease(maxClockSeconds, nowMs)
            if (opening != null && canAnchorOpening) {
                reset()
                return commit(opening, nowMs, ReadingSource.PRIMARY, TemporalStatus.Initial)
            }
        }

        if (result.ok && result.confidence >= minConfidence) {
            val checked = validate(result.timeSeconds, result.rawText)
            if (checked != null) {
                if (isStaleIncrease(checked.seconds, nowMs)) reset()
                when (val status = temporalStatus(checked.seconds, nowMs, true)) {
                    TemporalStatus.Initial,
                    TemporalStatus.Accept,
                    TemporalStatus.Same -> return commit(checked, nowMs, ReadingSource.PRIMARY, status)

                    TemporalStatus.Confirm -> {
                        pendingLargeDrop = checked.seconds
                        return FilterResult(false, reason = "large-drop-needs-confirmation")
                    }

                    is TemporalStatus.Reject -> primaryReason = status.reason
                }
            } else {
                primaryReason = validationReason(result.timeSeconds, result.rawText)
            }
        } else if (result.ok) {
            primaryReason = "low-confidence"
        }

        tryCandidates(result, nowMs)?.let { return it }
        if (primaryReason == "temporal-reject") pendingLargeDrop = null
        return reject(primaryReason)
    }

    private fun tryCandidates(result: RecognitionResult, nowMs: Long): FilterResult? {
        val previous = lastAccepted ?: return null
        val sorted = result.candidates.sortedByDescending { it.score }

        sorted.firstOrNull { candidate ->
            !candidate.isPrimary &&
                candidate.timeSeconds == previous - 1 &&
                candidate.score >= MIN_SEQUENTIAL_CANDIDATE_SCORE
        }?.let { candidate ->
            val checked = validate(candidate.timeSeconds, candidate.rawText)
            if (checked != null && temporalStatus(checked.seconds, nowMs, false) is TemporalStatus.Accept) {
                return commit(checked, nowMs, ReadingSource.ALTERNATIVE, TemporalStatus.Accept)
            }
        }

        for (candidate in sorted) {
            if (candidate.score < minAlternativeScore) continue
            if (candidate.isPrimary && candidate.timeSeconds != previous) continue

            val checked = validate(candidate.timeSeconds, candidate.rawText) ?: continue
            val status = temporalStatus(checked.seconds, nowMs, false)
            if (status is TemporalStatus.Initial || status is TemporalStatus.Accept || status is TemporalStatus.Same) {
                val source = if (candidate.isPrimary) ReadingSource.PRIMARY else ReadingSource.ALTERNATIVE
                return commit(checked, nowMs, source, status)
            }
        }
        return null
    }

    private fun commit(
        checked: Checked,
        nowMs: Long,
        source: ReadingSource,
        status: TemporalStatus
    ): FilterResult {
        if (status is TemporalStatus.Same) {
            lastTimestampMs = nowMs
            failedReads = 0
            return FilterResult(
                accepted = false,
                reason = "same-time",
                timeSeconds = checked.seconds,
                rawText = checked.rawText,
                source = source
            )
        }

        lastAccepted = checked.seconds
        lastTimestampMs = nowMs
        pendingLargeDrop = null
        failedReads = 0
        return FilterResult(
            accepted = true,
            timeSeconds = checked.seconds,
            rawText = checked.rawText,
            source = source
        )
    }

    private fun temporalStatus(value: Int, nowMs: Long, allowLargeDropConfirmation: Boolean): TemporalStatus {
        val previous = lastAccepted ?: return TemporalStatus.Initial
        if (value == previous) return TemporalStatus.Same
        if (value > previous) return TemporalStatus.Reject("time-increased")

        val drop = previous - value
        lastTimestampMs?.let { timestamp ->
            val elapsedSeconds = ((nowMs - timestamp).coerceAtLeast(0L)) / 1000.0
            if (drop > elapsedSeconds + temporalSlackSeconds) {
                return TemporalStatus.Reject("temporal-reject")
            }
        }

        if (drop in 1..3) return TemporalStatus.Accept
        if (allowLargeDropConfirmation) {
            return if (pendingLargeDrop == value) TemporalStatus.Accept else TemporalStatus.Confirm
        }
        return TemporalStatus.Reject("alternative-large-drop")
    }

    private fun isStaleIncrease(value: Int, nowMs: Long): Boolean {
        val previous = lastAccepted ?: return false
        val timestamp = lastTimestampMs ?: return false
        return value > previous && nowMs - timestamp > newBattleGapMs
    }

    private fun validate(seconds: Int?, rawText: String?): Checked? {
        if (seconds == null || seconds !in minClockSeconds..maxClockSeconds) return null
        val normalized = rawText?.replace('：', ':') ?: formatClock(seconds)
        val match = CLOCK_REGEX.matchEntire(normalized.trim()) ?: return null
        val parsed = match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
        if (parsed != seconds || parsed !in minClockSeconds..maxClockSeconds) return null
        return Checked(seconds, formatClock(seconds))
    }

    private fun validationReason(seconds: Int?, rawText: String?): String {
        if (seconds == null) return "invalid-time"
        if (seconds !in minClockSeconds..maxClockSeconds) return "out-of-range"
        val match = rawText?.replace('：', ':')?.trim()?.let(CLOCK_REGEX::matchEntire)
            ?: return "invalid-clock-text"
        val parsed = match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
        return if (parsed != seconds) "clock-text-mismatch" else "out-of-range"
    }

    private fun reject(reason: String): FilterResult {
        failedReads += 1
        return FilterResult(
            accepted = false,
            reason = reason,
            shouldPause = failedReads >= maxFailedReads
        )
    }

    private fun formatClock(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

    private companion object {
        val CLOCK_REGEX = Regex("^([01]):([0-5]\\d)$")
        const val MIN_SEQUENTIAL_CANDIDATE_SCORE = 0.35
    }
}
