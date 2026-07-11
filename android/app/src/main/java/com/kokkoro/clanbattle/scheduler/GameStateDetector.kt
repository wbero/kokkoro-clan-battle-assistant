package com.kokkoro.clanbattle.scheduler

import com.kokkoro.clanbattle.recognition.EnergyDetectionResult

class GameStateDetector {
    private var active = false
    private var pendingTrigger = false
    private var triggerClockSeconds: Int? = null

    fun update(clockSeconds: Int?, energy: EnergyDetectionResult?): GameState {
        observeEnergy(energy)
        if (!active) {
            if (pendingTrigger) {
                active = true
                pendingTrigger = false
                triggerClockSeconds = clockSeconds
                return GameState.CHARACTER_UB
            }
            return GameState.RUNNING
        }

        val triggerClock = triggerClockSeconds
        if (triggerClock == null && clockSeconds != null) {
            triggerClockSeconds = clockSeconds
            return GameState.UB_ANIMATION
        }
        if (triggerClock != null && clockSeconds != null && clockSeconds < triggerClock) {
            active = false
            triggerClockSeconds = null
            return GameState.UB_JUST_ENDED
        }

        return GameState.UB_ANIMATION
    }

    fun observeEnergy(energy: EnergyDetectionResult?) {
        if (!active && energy?.triggeredRoles?.isNotEmpty() == true) pendingTrigger = true
    }

    fun reset() {
        active = false
        pendingTrigger = false
        triggerClockSeconds = null
    }
}
