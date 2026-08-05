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
 * axes. A credible single-character TP release attributes the hold to a
 * character UB. Repeated same-role releases or simultaneous releases downgrade
 * TP evidence as HUD obstruction, allowing a later conservative Boss recovery.
 */
class BossUbDetector(
    private val holdMarginMs: Long = 700,
    private val fallbackSecondMs: Long = 1_000,
    private val eventRetentionMs: Long = 30_000,
    private val maxObservationGapMs: Long = 5_000,
    private var earlyConfirmationHoldMs: Long = 7_000,
    private val repeatedRoleArtifactWindowMs: Long = 1_500
) {
    private val normalSecondDurations = ArrayDeque<Long>()
    private var clockSeconds: Int? = null
    private var clockStartedAtWallMs: Long? = null
    private var lastObservedAtWallMs: Long? = null
    private var characterUbObserved = false
    private var firstCharacterUbObservedAtWallMs: Long? = null
    private var lastSingleRole: CharacterRole? = null
    private var lastSingleRoleObservedAtWallMs: Long? = null
    private var tpEvidenceUntrustworthy = false
    private var earlyEventEmittedForHold = false
    private var latestEvent: BossUbEvent? = null

    init {
        require(holdMarginMs > 0)
        require(fallbackSecondMs > 0)
        require(eventRetentionMs > 0)
        require(maxObservationGapMs > 0)
        require(earlyConfirmationHoldMs > 0)
        require(repeatedRoleArtifactWindowMs > 0)
    }

    fun update(
        clockSeconds: Int,
        triggeredRoles: Set<CharacterRole>,
        nowMs: Long
    ): BossUbEvent? {
        val previousClock = this.clockSeconds
        val startedAt = clockStartedAtWallMs
        if (previousClock == null || startedAt == null) {
            anchor(clockSeconds, nowMs, triggeredRoles)
            return null
        }
        val lastObservedAt = lastObservedAtWallMs
        if (lastObservedAt == null || nowMs - lastObservedAt > maxObservationGapMs) {
            anchor(clockSeconds, nowMs, triggeredRoles)
            return null
        }
        lastObservedAtWallMs = nowMs

        val durationMs = (nowMs - startedAt).coerceAtLeast(0)
        observeTpEvidence(triggeredRoles, nowMs)
        if (clockSeconds == previousClock) {
            val candidateStartedAt = bossCandidateStartedAt(startedAt)
            if (
                !earlyEventEmittedForHold &&
                candidateStartedAt != null &&
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
        val thresholdMs = if (characterUbObserved && tpEvidenceUntrustworthy) {
            earlyConfirmationHoldMs
        } else {
            normalSecondMs() + holdMarginMs
        }
        val detected = if (
            sequentialTick &&
            candidateStartedAt != null &&
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
        anchor(clockSeconds, nowMs, triggeredRoles)
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
        characterUbObserved = false
        firstCharacterUbObservedAtWallMs = null
        lastSingleRole = null
        lastSingleRoleObservedAtWallMs = null
        tpEvidenceUntrustworthy = false
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
        triggeredRoles: Set<CharacterRole>
    ) {
        this.clockSeconds = clockSeconds
        clockStartedAtWallMs = nowMs
        lastObservedAtWallMs = nowMs
        characterUbObserved = false
        firstCharacterUbObservedAtWallMs = null
        lastSingleRole = null
        lastSingleRoleObservedAtWallMs = null
        tpEvidenceUntrustworthy = false
        observeTpEvidence(triggeredRoles, nowMs)
        earlyEventEmittedForHold = false
    }

    /**
     * A credible single-role TP release suppresses Boss attribution for the
     * current hold.  During Boss animations the TP HUD can flash all-full and
     * then generate repeated or simultaneous fake releases.  Once that pattern
     * appears, the TP evidence is no longer allowed to suppress the hold
     * forever; instead a fresh, conservative [earlyConfirmationHoldMs] segment
     * must elapse after the first single-role release before Boss UB is emitted.
     */
    private fun observeTpEvidence(triggeredRoles: Set<CharacterRole>, nowMs: Long) {
        when {
            triggeredRoles.size > 1 -> {
                tpEvidenceUntrustworthy = true
            }

            triggeredRoles.size == 1 -> {
                val role = triggeredRoles.single()
                val repeatedSameRole = lastSingleRole == role &&
                    lastSingleRoleObservedAtWallMs?.let { nowMs - it in 0..repeatedRoleArtifactWindowMs } == true

                if (repeatedSameRole) tpEvidenceUntrustworthy = true

                if (!tpEvidenceUntrustworthy && !characterUbObserved) {
                    characterUbObserved = true
                    firstCharacterUbObservedAtWallMs = nowMs
                }

                lastSingleRole = role
                lastSingleRoleObservedAtWallMs = nowMs
            }
        }
    }

    private fun bossCandidateStartedAt(clockStartedAt: Long): Long? = when {
        !characterUbObserved -> clockStartedAt
        tpEvidenceUntrustworthy -> firstCharacterUbObservedAtWallMs ?: clockStartedAt
        else -> null
    }

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
