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
 * Detects a Boss UB from a completed abnormal clock hold. A single character TP
 * drop during the hold attributes it to a character UB instead. Multiple
 * simultaneous drops are ignored because Boss animations can obscure the HUD.
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
    private var characterUbObserved = false
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
        if (triggeredRoles.size == 1) characterUbObserved = true
        if (clockSeconds == previousClock) {
            if (
                !earlyEventEmittedForHold &&
                !characterUbObserved &&
                durationMs >= earlyConfirmationHoldMs
            ) {
                earlyEventEmittedForHold = true
                return BossUbEvent(previousClock, nowMs, durationMs, early = true)
                    .also { latestEvent = it }
            }
            return null
        }

        val sequentialTick = previousClock - clockSeconds == 1
        val thresholdMs = normalSecondMs() + holdMarginMs
        val detected = if (
            sequentialTick &&
            durationMs >= thresholdMs &&
            !characterUbObserved
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
        characterUbObserved = triggeredRoles.size == 1
        earlyEventEmittedForHold = false
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
