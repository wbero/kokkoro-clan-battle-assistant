package com.kokkoro.clanbattle.control

import com.kokkoro.clanbattle.control.ControlAction.None
import com.kokkoro.clanbattle.control.ControlAction.TapAuto
import com.kokkoro.clanbattle.control.ControlAction.TapGlobalSet
import com.kokkoro.clanbattle.control.ControlAction.TapRole
import com.kokkoro.clanbattle.control.ControlSafetyState.RUNNING
import com.kokkoro.clanbattle.control.ControlSafetyState.SAFETY_PAUSING
import com.kokkoro.clanbattle.recognition.CharacterRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BattleControlStateMachineTest {
    @Test fun `all on target uses one global click`() {
        val machine = machine(auto = VisualToggleState.ON, roles = all(VisualToggleState.ON))
        val step = machine.update(observation(auto = VisualToggleState.ON, global = VisualToggleState.OFF, roles = all(VisualToggleState.OFF)), 0)
        assertEquals(TapGlobalSet, step.action)
    }

    @Test fun `mixed target with global on clicks first role that must turn off`() {
        val machine = machine(roles = roles(VisualToggleState.ON, VisualToggleState.OFF, VisualToggleState.ON, VisualToggleState.OFF, VisualToggleState.ON))
        val step = machine.update(observation(global = VisualToggleState.ON, roles = all(VisualToggleState.ON)), 0)
        assertEquals(TapRole(CharacterRole.ROLE_2), step.action)
    }

    @Test fun `unknown required state does not click`() {
        val machine = machine(auto = VisualToggleState.ON)
        val step = machine.update(observation(auto = VisualToggleState.UNKNOWN), 0)
        assertEquals(None, step.action)
        assertEquals("waiting-trustworthy-state", step.reason)
    }

    @Test fun `click needs two matching frames before next action`() {
        val machine = machine(
            auto = VisualToggleState.ON,
            roles = roles(VisualToggleState.ON, VisualToggleState.OFF, VisualToggleState.ON, VisualToggleState.OFF, VisualToggleState.ON)
        )
        val initial = observation(auto = VisualToggleState.OFF, global = VisualToggleState.OFF, roles = all(VisualToggleState.OFF))
        assertEquals(TapAuto, machine.update(initial, 0).action)
        val autoOn = initial.copy(auto = toggle(VisualToggleState.ON))
        assertEquals(None, machine.update(autoOn, 100).action)
        assertEquals(TapRole(CharacterRole.ROLE_1), machine.update(autoOn, 150).action)
    }

    @Test fun `unconfirmed click retries once after five hundred milliseconds`() {
        val machine = machine(auto = VisualToggleState.ON)
        val unchanged = observation(auto = VisualToggleState.OFF)
        assertEquals(TapAuto, machine.update(unchanged, 0).action)
        val retry = machine.update(unchanged, 501)
        assertEquals(TapAuto, retry.action)
        assertEquals(1, retry.retryCount)
    }

    @Test fun `second timeout enters safety pausing`() {
        val machine = machine(auto = VisualToggleState.ON)
        val unchanged = observation(auto = VisualToggleState.OFF)
        machine.update(unchanged, 0)
        machine.update(unchanged, 501)
        val failed = machine.update(unchanged, 1002)
        assertEquals(SAFETY_PAUSING, failed.safety)
        assertEquals(None, failed.action)
    }

    @Test fun `reset clears observed desired expected and retry`() {
        val machine = machine(auto = VisualToggleState.ON)
        machine.update(observation(auto = VisualToggleState.OFF), 0)
        machine.reset()
        val snapshot = machine.snapshot()
        assertNull(snapshot.observed)
        assertNull(snapshot.desired)
        assertNull(snapshot.expected)
        assertEquals(0, snapshot.retryCount)
        assertEquals(RUNNING, snapshot.safety)
    }

    private fun machine(
        auto: VisualToggleState? = null,
        roles: Map<CharacterRole, VisualToggleState>? = null
    ) = BattleControlStateMachine().apply { setDesired(OpeningControlTarget(auto, roles)) }

    private fun observation(
        auto: VisualToggleState = VisualToggleState.OFF,
        global: VisualToggleState = VisualToggleState.OFF,
        roles: Map<CharacterRole, VisualToggleState> = all(VisualToggleState.OFF),
        consistent: Boolean = true
    ) = BattleControlObservation(
        auto = toggle(auto),
        globalSet = toggle(global),
        roles = roles.mapValues { toggle(it.value) },
        consistent = consistent,
        reason = if (consistent) null else "inconsistent"
    )

    private fun toggle(state: VisualToggleState) = ToggleObservation(state, 1.0)

    private fun all(state: VisualToggleState) = CharacterRole.entries.associateWith { state }

    private fun roles(vararg states: VisualToggleState) = CharacterRole.entries.zip(states.asList()).toMap()
}
