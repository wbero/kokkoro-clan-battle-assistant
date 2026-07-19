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

    fun evaluate(
        observation: FilteredControlObservation,
        holdWhileActionBusy: Boolean = false
    ): ControlObservationSafetyResult {
        if (observation.trustworthy && observation.observation != null) {
            consecutiveUntrustedFrames = 0
            return ControlObservationSafetyResult(
                ControlObservationSafetyDecision.USE,
                consecutiveUntrustedFrames,
                observation.status
            )
        }

        // SET/UB confirmation can temporarily hide every badge under the UB
        // animation. The verified coordinator owns that lifecycle, so keep the
        // frame on hold instead of turning an expected transient into SAFETY_PAUSED.
        if (holdWhileActionBusy) {
            return ControlObservationSafetyResult(
                ControlObservationSafetyDecision.HOLD,
                consecutiveUntrustedFrames,
                observation.status
            )
        }

        // A plausible transition is already visible, but the observation filter
        // needs one more frame before it becomes authoritative. It is not a
        // recognition failure and must not race the confirmation frame into a
        // safety pause.
        if (observation.status == ControlObservationStatus.PENDING_CONFIRMATION) {
            return ControlObservationSafetyResult(
                ControlObservationSafetyDecision.HOLD,
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
