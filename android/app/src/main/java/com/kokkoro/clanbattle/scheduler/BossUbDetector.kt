package com.kokkoro.clanbattle.scheduler

import com.kokkoro.clanbattle.recognition.CharacterRole
import java.util.ArrayDeque

data class BossUbEvent(
    val heldClockSeconds: Int,
    val detectedAtWallMs: Long,
    val holdDurationMs: Long,
    val early: Boolean = false
)

/**
 * Detects a Boss UB from an abnormal clock hold for both sequence and switch
 * axes. A credible character TP release moves the Boss-candidate start to that
 * release instead of suppressing the entire clock hold forever. TP frames
 * explicitly marked as visual obstruction are ignored for character attribution.
 */
class BossUbDetector(
    private val holdMarginMs: Long = 700,
    private val fallbackSecondMs: Long = 1_000,
    private val eventRetentionMs: Long = 30_000,
    private val maxObservationGapMs: Long = 5_000,
    private var earlyConfirmationHoldMs: Long = 7_000
) {
    private val normalSecondDurations = ArrayDeque<Long>()
    private var clockSeconds: Int? = null
    private var clockStartedAtWallMs: Long? = null
    private var lastObservedAtWallMs: Long? = null
    private var lastCharacterUbObservedAtWallMs: Long? = null
    private var earlyEventEmittedForHold = false
    private var latestEvent: BossUbEvent? = null

    init {
        require(holdMarginMs > 0)
        require(fallbackSecondMs > 0)
        require(eventRetentionMs > 0)
        require(maxObservationGapMs > 0)
        require(earlyConfirmationHoldMs > 0)
    }

    fun update(
        clockSeconds: Int,
        triggeredRoles: Set<CharacterRole>,
        nowMs: Long,
        visualObstruction: Boolean = false
    ): BossUbEvent? {
        val previousClock = this.clockSeconds
        val startedAt = clockStartedAtWallMs
        if (previousClock == null || startedAt == null) {
            anchor(clockSeconds, nowMs, triggeredRoles, visualObstruction)
            return null
        }
        val lastObservedAt = lastObservedAtWallMs
        if (lastObservedAt == null || nowMs - lastObservedAt > maxObservationGapMs) {
            anchor(clockSeconds, nowMs, triggeredRoles, visualObstruction)
            return null
        }
        lastObservedAtWallMs = nowMs

        val durationMs = (nowMs - startedAt).coerceAtLeast(0)
        observeTpEvidence(triggeredRoles, nowMs, visualObstruction)
        if (clockSeconds == previousClock) {
            val candidateStartedAt = bossCandidateStartedAt(startedAt)
            if (
                !earlyEventEmittedForHold &&
                nowMs - candidateStartedAt >= earlyConfirmationHoldMs
            ) {
                earlyEventEmittedForHold = true
                return BossUbEvent(previousClock, nowMs, durationMs, early = true)
                    .also { latestEvent = it }
            }
            return null
        }

        val sequentialTick = previousClock - clockSeconds == 1
        val candidateStartedAt = bossCandidateStartedAt(startedAt)
        val thresholdMs = if (lastCharacterUbObservedAtWallMs != null) {
            earlyConfirmationHoldMs
        } else {
            normalSecondMs() + holdMarginMs
        }
        val detected = if (
            sequentialTick &&
            nowMs - candidateStartedAt >= thresholdMs
        ) {
            BossUbEvent(previousClock, nowMs, durationMs).also { latestEvent = it }
        } else {
            null
        }

        if (sequentialTick && durationMs in MIN_NORMAL_SECOND_MS..MAX_NORMAL_SECOND_MS) {
            normalSecondDurations.addLast(durationMs)
            while (normalSecondDurations.size > MAX_NORMAL_SAMPLES) {
                normalSecondDurations.removeFirst()
            }
        }
        anchor(clockSeconds, nowMs, triggeredRoles, visualObstruction)
        return detected
    }

    fun latestEvent(nowMs: Long): BossUbEvent? = latestEvent?.takeIf {
        nowMs - it.detectedAtWallMs in 0..eventRetentionMs
    }

    fun configureEarlyConfirmationHoldMs(value: Long) {
        require(value > 0)
        earlyConfirmationHoldMs = value
    }

    /** Drops the current hold around menus while retaining learned normal cadence. */
    fun suspend() {
        clockSeconds = null
        clockStartedAtWallMs = null
        lastObservedAtWallMs = null
        lastCharacterUbObservedAtWallMs = null
        earlyEventEmittedForHold = false
    }

    fun reset() {
        suspend()
        normalSecondDurations.clear()
        latestEvent = null
    }

    private fun anchor(
        clockSeconds: Int,
        nowMs: Long,
        triggeredRoles: Set<CharacterRole>,
        visualObstruction: Boolean
    ) {
        this.clockSeconds = clockSeconds
        clockStartedAtWallMs = nowMs
        lastObservedAtWallMs = nowMs
        lastCharacterUbObservedAtWallMs = null
        observeTpEvidence(triggeredRoles, nowMs, visualObstruction)
        earlyEventEmittedForHold = false
    }

    private fun observeTpEvidence(
        triggeredRoles: Set<CharacterRole>,
        nowMs: Long,
        visualObstruction: Boolean
    ) {
        if (!visualObstruction && triggeredRoles.isNotEmpty()) {
            // Repeated releases by the same role are valid. Each credible UB
            // simply restarts the conservative Boss hold window.
            lastCharacterUbObservedAtWallMs = nowMs
        }
    }

    private fun bossCandidateStartedAt(clockStartedAt: Long): Long =
        lastCharacterUbObservedAtWallMs ?: clockStartedAt

    private fun normalSecondMs(): Long {
        if (normalSecondDurations.isEmpty()) return fallbackSecondMs
        val sorted = normalSecondDurations.sorted()
        return sorted[sorted.size / 2]
    }

    private companion object {
        const val MAX_NORMAL_SAMPLES = 15
        const val MIN_NORMAL_SECOND_MS = 400L
        const val MAX_NORMAL_SECOND_MS = 1_600L
    }
}
