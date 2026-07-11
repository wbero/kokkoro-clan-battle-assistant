package com.kokkoro.clanbattle.scheduler

import com.kokkoro.clanbattle.axis.AxisEvent

enum class GameState {
    RUNNING,
    CHARACTER_UB,
    UB_ANIMATION,
    UB_JUST_ENDED
}

data class ScheduleResult(val events: List<AxisEvent>, val reason: String)

class Scheduler(private val events: List<AxisEvent>) {
    private val executed = mutableSetOf<String>()
    private var previous: Int? = null
    private var frozenClock: Int? = null

    fun update(state: GameState, clockSeconds: Int?): ScheduleResult {
        if (state == GameState.UB_ANIMATION || state == GameState.CHARACTER_UB) {
            if (clockSeconds != null && frozenClock == null) frozenClock = clockSeconds
            return ScheduleResult(emptyList(), if (state == GameState.CHARACTER_UB) "character-ub" else "ub-animation-frozen")
        }
        if (clockSeconds == null) return ScheduleResult(emptyList(), "no-clock-reading")
        if (state == GameState.UB_JUST_ENDED) return handleThaw(clockSeconds)
        return handleRunning(clockSeconds)
    }

    fun reset() {
        executed.clear()
        previous = null
        frozenClock = null
    }

    private fun handleRunning(current: Int): ScheduleResult {
        val last = previous
        val due = when {
            last == null -> dueAt(current)
            last == current -> return ScheduleResult(emptyList(), "same-second")
            last > current -> dueBetween(last, current)
            else -> return ScheduleResult(emptyList(), "time-increased")
        }
        previous = current
        frozenClock = null
        due.forEach { executed += it.id }
        return ScheduleResult(due, "scheduled")
    }

    private fun handleThaw(current: Int): ScheduleResult {
        val frozen = frozenClock
        val due = when {
            frozen == null -> emptyList()
            frozen > current -> dueBetween(frozen, current)
            frozen == current -> dueAt(current)
            else -> emptyList()
        }
        frozenClock = null
        previous = current
        due.forEach { executed += it.id }
        return ScheduleResult(due, if (due.isEmpty()) "thaw-no-missed" else "thaw-caught-up")
    }

    private fun dueAt(current: Int) = events.filter { it.id !in executed && it.timeSeconds == current }

    private fun dueBetween(previousTime: Int, currentTime: Int) = events.filter {
        it.id !in executed && it.timeSeconds <= previousTime && it.timeSeconds >= currentTime
    }
}
