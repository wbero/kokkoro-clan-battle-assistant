package com.kokkoro.clanbattle.control

enum class ControlObservationSafetyDecision {
    USE,
    HOLD,
    PAUSE
}

data class ControlObservationSafetyResult(
    val decision: ControlObservationSafetyDecision,
    val consecutiveUntrustedFrames: Int,
    val status: ControlObservationStatus
)

class ControlObservationSafetyGate(
    private val maxUntrustedFrames: Int = DEFAULT_MAX_UNTRUSTED_FRAMES
) {
    private var consecutiveUntrustedFrames = 0

    init {
        require(maxUntrustedFrames >= 1)
    }

    fun evaluate(observation: FilteredControlObservation): ControlObservationSafetyResult {
        if (observation.trustworthy && observation.observation != null) {
            consecutiveUntrustedFrames = 0
            return ControlObservationSafetyResult(
                ControlObservationSafetyDecision.USE,
                consecutiveUntrustedFrames,
                observation.status
            )
        }

        consecutiveUntrustedFrames++
        val decision = if (consecutiveUntrustedFrames >= maxUntrustedFrames) {
            ControlObservationSafetyDecision.PAUSE
        } else {
            ControlObservationSafetyDecision.HOLD
        }
        return ControlObservationSafetyResult(decision, consecutiveUntrustedFrames, observation.status)
    }

    fun reset() {
        consecutiveUntrustedFrames = 0
    }

    companion object {
        const val DEFAULT_MAX_UNTRUSTED_FRAMES = 8
    }
}
