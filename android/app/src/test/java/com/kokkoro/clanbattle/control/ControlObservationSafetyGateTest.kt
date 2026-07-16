package com.kokkoro.clanbattle.control

import com.kokkoro.clanbattle.recognition.CharacterRole
import org.junit.Assert.assertEquals
import org.junit.Test

class ControlObservationSafetyGateTest {
    @Test
    fun `single pending frame holds automation and a trusted frame resumes it`() {
        val gate = ControlObservationSafetyGate(maxUntrustedFrames = 3)

        val hold = gate.evaluate(filtered(ControlObservationStatus.PENDING_CONFIRMATION))
        val resumed = gate.evaluate(filtered(ControlObservationStatus.TRUSTWORTHY))

        assertEquals(ControlObservationSafetyDecision.HOLD, hold.decision)
        assertEquals(1, hold.consecutiveUntrustedFrames)
        assertEquals(ControlObservationSafetyDecision.USE, resumed.decision)
        assertEquals(0, resumed.consecutiveUntrustedFrames)
    }

    @Test
    fun `only consecutive untrusted frames trigger safety pause`() {
        val gate = ControlObservationSafetyGate(maxUntrustedFrames = 3)

        val first = gate.evaluate(filtered(ControlObservationStatus.RAW_UNTRUSTWORTHY))
        val second = gate.evaluate(filtered(ControlObservationStatus.MISSING))
        val third = gate.evaluate(filtered(ControlObservationStatus.IMPLAUSIBLE_TRANSITION))

        assertEquals(ControlObservationSafetyDecision.HOLD, first.decision)
        assertEquals(ControlObservationSafetyDecision.HOLD, second.decision)
        assertEquals(ControlObservationSafetyDecision.PAUSE, third.decision)
        assertEquals(3, third.consecutiveUntrustedFrames)
    }

    private fun filtered(status: ControlObservationStatus): FilteredControlObservation =
        FilteredControlObservation(
            observation = if (status == ControlObservationStatus.TRUSTWORTHY) observation() else null,
            status = status
        )

    private fun observation(): BattleControlObservation {
        val on = ToggleObservation(VisualToggleState.ON, onScore = 1.0)
        return BattleControlObservation(
            auto = on,
            globalSet = on,
            roles = CharacterRole.entries.associateWith { on },
            consistent = true
        )
    }
}
