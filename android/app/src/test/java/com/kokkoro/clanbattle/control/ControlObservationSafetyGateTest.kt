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
        assertEquals(0, hold.consecutiveUntrustedFrames)
        assertEquals(ControlObservationSafetyDecision.USE, resumed.decision)
        assertEquals(0, resumed.consecutiveUntrustedFrames)
    }

    @Test
    fun `pending confirmation does not erase or advance an existing failure streak`() {
        val gate = ControlObservationSafetyGate(maxUntrustedFrames = 3)

        gate.evaluate(filtered(ControlObservationStatus.RAW_UNTRUSTWORTHY))
        val pending = gate.evaluate(filtered(ControlObservationStatus.PENDING_CONFIRMATION))
        val second = gate.evaluate(filtered(ControlObservationStatus.RAW_UNTRUSTWORTHY))

        assertEquals(1, pending.consecutiveUntrustedFrames)
        assertEquals(ControlObservationSafetyDecision.HOLD, pending.decision)
        assertEquals(2, second.consecutiveUntrustedFrames)
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

    @Test
    fun `busy verified action holds transient raw frames without pausing`() {
        val gate = ControlObservationSafetyGate(maxUntrustedFrames = 2)

        val first = gate.evaluate(
            filtered(ControlObservationStatus.RAW_UNTRUSTWORTHY),
            holdWhileActionBusy = true
        )
        val second = gate.evaluate(
            filtered(ControlObservationStatus.RAW_UNTRUSTWORTHY),
            holdWhileActionBusy = true
        )

        assertEquals(ControlObservationSafetyDecision.HOLD, first.decision)
        assertEquals(ControlObservationSafetyDecision.HOLD, second.decision)
        assertEquals(0, second.consecutiveUntrustedFrames)
    }

    @Test
    fun `busy animation clears a partial failure streak before recognition resumes`() {
        val gate = ControlObservationSafetyGate(maxUntrustedFrames = 3)

        gate.evaluate(filtered(ControlObservationStatus.RAW_UNTRUSTWORTHY))
        gate.evaluate(filtered(ControlObservationStatus.RAW_UNTRUSTWORTHY), holdWhileActionBusy = true)
        val after = gate.evaluate(filtered(ControlObservationStatus.RAW_UNTRUSTWORTHY))

        assertEquals(ControlObservationSafetyDecision.HOLD, after.decision)
        assertEquals(1, after.consecutiveUntrustedFrames)
    }

    @Test
    fun `forced animation hold ignores even a plausible trusted observation`() {
        val gate = ControlObservationSafetyGate(maxUntrustedFrames = 2)

        val held = gate.evaluate(
            filtered(ControlObservationStatus.TRUSTWORTHY),
            forceHold = true
        )

        assertEquals(ControlObservationSafetyDecision.HOLD, held.decision)
        assertEquals(0, held.consecutiveUntrustedFrames)
    }

    @Test
    fun `axis mode never pauses only because recognition remains untrusted`() {
        val gate = ControlObservationSafetyGate(maxUntrustedFrames = 2)

        repeat(20) {
            val held = gate.evaluate(
                filtered(ControlObservationStatus.RAW_UNTRUSTWORTHY),
                pauseOnUntrusted = false
            )
            assertEquals(ControlObservationSafetyDecision.HOLD, held.decision)
            assertEquals(0, held.consecutiveUntrustedFrames)
        }
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
