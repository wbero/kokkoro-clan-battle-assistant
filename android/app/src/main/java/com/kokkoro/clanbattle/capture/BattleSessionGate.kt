package com.kokkoro.clanbattle.capture

class BattleSessionGate(
    private val openingSeconds: IntRange = 88..90,
    private val requiredBattleHudMatches: Int = 2
) {
    private var stage = Stage.RUNNING
    private var battleHudMatches = 0

    fun prepare() {
        stage = Stage.WAIT_START
        battleHudMatches = 0
    }

    fun onStartMatched() {
        if (stage == Stage.WAIT_START) {
            stage = Stage.WAIT_LOADING
            battleHudMatches = 0
        }
    }

    fun onLoadingMatched() {
        if (stage == Stage.WAIT_LOADING) {
            stage = Stage.WAIT_CLOCK
            battleHudMatches = 0
        }
    }

    fun observeBattleHud(trusted: Boolean) {
        if (stage != Stage.WAIT_LOADING) return
        battleHudMatches = if (trusted) battleHudMatches + 1 else 0
        if (battleHudMatches >= requiredBattleHudMatches) onLoadingMatched()
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
