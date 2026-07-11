package com.kokkoro.clanbattle.capture

class BattleSessionGate(
    private val openingSeconds: IntRange = 84..90
) {
    private var stage = Stage.RUNNING

    fun prepare() {
        stage = Stage.WAIT_START
    }

    fun onStartMatched() {
        if (stage == Stage.WAIT_START) stage = Stage.WAIT_LOADING
    }

    fun onLoadingMatched() {
        if (stage == Stage.WAIT_LOADING) stage = Stage.WAIT_CLOCK
    }

    fun shouldEvaluate(timeSeconds: Int?): Boolean =
        stage == Stage.RUNNING || (stage == Stage.WAIT_CLOCK && timeSeconds in openingSeconds)

    fun onAccepted(timeSeconds: Int?): Boolean {
        if (stage == Stage.RUNNING) return true
        if (stage != Stage.WAIT_CLOCK) return false
        if (timeSeconds !in openingSeconds) return false
        stage = Stage.RUNNING
        return true
    }

    fun isWaiting(): Boolean = stage != Stage.RUNNING
    fun isWaitingForStart(): Boolean = stage == Stage.WAIT_START
    fun isWaitingForLoading(): Boolean = stage == Stage.WAIT_LOADING
    fun isWaitingForClock(): Boolean = stage == Stage.WAIT_CLOCK
    fun debugState(): String = stage.name

    private enum class Stage {
        WAIT_START,
        WAIT_LOADING,
        WAIT_CLOCK,
        RUNNING
    }
}
