package com.kokkoro.clanbattle.control

class RoleSetFallbackWatchdog(graceMs: Long = 0) {
    private var graceMs = graceMs
    private var lowestClockSeconds: Int? = null
    private var activeEventTimeSeconds: Int? = null
    private var deadlineWallMs: Long? = null

    init {
        require(graceMs >= 0)
    }

    fun configureGraceMs(value: Long) {
        require(value >= 0)
        graceMs = value
    }

    fun observeClock(clockSeconds: Int?, nowMs: Long) {
        if (clockSeconds == null) return
        val previous = lowestClockSeconds
        lowestClockSeconds = previous?.let { minOf(it, clockSeconds) } ?: clockSeconds
        startDeadlineIfEligible(nowMs)
    }

    fun arm(eventTimeSeconds: Int, nowMs: Long) {
        if (activeEventTimeSeconds != null) return
        activeEventTimeSeconds = eventTimeSeconds
        startDeadlineIfEligible(nowMs)
    }

    fun isDue(nowMs: Long): Boolean {
        val deadline = deadlineWallMs ?: return false
        return nowMs >= deadline
    }

    fun cancel() {
        activeEventTimeSeconds = null
        deadlineWallMs = null
    }

    fun reset() {
        lowestClockSeconds = null
        cancel()
    }

    private fun startDeadlineIfEligible(nowMs: Long) {
        if (deadlineWallMs != null) return
        val eventTime = activeEventTimeSeconds ?: return
        val currentClock = lowestClockSeconds ?: return
        if (currentClock < eventTime) deadlineWallMs = nowMs + graceMs
    }
}
