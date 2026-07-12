package com.kokkoro.clanbattle.control

import com.kokkoro.clanbattle.control.ControlAction.None
import com.kokkoro.clanbattle.control.ControlAction.TapAuto
import com.kokkoro.clanbattle.control.ControlAction.TapGlobalSet
import com.kokkoro.clanbattle.control.ControlAction.TapRole
import com.kokkoro.clanbattle.control.ControlAction.TapMenu
import com.kokkoro.clanbattle.control.ControlSafetyState.RUNNING
import com.kokkoro.clanbattle.control.ControlSafetyState.SAFETY_PAUSING
import com.kokkoro.clanbattle.control.ControlSafetyState.SAFETY_PAUSED
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

    @Test fun `two transient inconsistent frames do not pause opening convergence`() {
        val machine = machine(
            roles = roles(
                VisualToggleState.ON,
                VisualToggleState.OFF,
                VisualToggleState.ON,
                VisualToggleState.OFF,
                VisualToggleState.ON
            )
        )
        val transient = observation(
            global = VisualToggleState.ON,
            roles = all(VisualToggleState.OFF),
            consistent = false
        )

        assertEquals(RUNNING, machine.update(transient, 0).safety)
        assertEquals(RUNNING, machine.update(transient, 50).safety)
        val recovered = machine.update(
            observation(global = VisualToggleState.ON, roles = all(VisualToggleState.ON)),
            100
        )

        assertEquals(RUNNING, recovered.safety)
        assertEquals(TapRole(CharacterRole.ROLE_2), recovered.action)
    }

    @Test fun `three consecutive inconsistent frames enter safety pausing`() {
        val machine = machine(auto = VisualToggleState.ON)
        val inconsistent = observation(consistent = false)

        machine.update(inconsistent, 0)
        machine.update(inconsistent, 50)
        val failed = machine.update(inconsistent, 100)

        assertEquals(SAFETY_PAUSING, failed.safety)
        assertEquals("inconsistent", failed.reason)
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

    @Test fun `safety pausing clicks menu once only when menu is trustworthy`() {
        val machine = machine(auto = VisualToggleState.ON)
        machine.forceSafety("state-mismatch")

        val paused = machine.updateMenu(0.82)

        assertEquals(TapMenu, paused.action)
        assertEquals(SAFETY_PAUSED, paused.safety)
        assertEquals(None, machine.updateMenu(0.90).action)
    }

    @Test fun `untrusted menu freezes without guessing a click`() {
        val machine = machine(auto = VisualToggleState.ON)
        machine.forceSafety("state-mismatch")

        val step = machine.updateMenu(0.40)

        assertEquals(None, step.action)
        assertEquals(SAFETY_PAUSING, step.safety)
        assertEquals("menu-button-untrusted", step.reason)
    }

    @Test fun `manual recovery discards expected state and requires two trustworthy frames`() {
        val machine = machine(auto = VisualToggleState.ON)
        machine.update(observation(auto = VisualToggleState.OFF), 0)
        machine.forceSafety("state-mismatch")
        machine.updateMenu(0.82)
        val recovered = observation(auto = VisualToggleState.OFF)

        assertEquals(SAFETY_PAUSED, machine.updateRecovery(0.82, recovered, 1000).safety)
        val running = machine.updateRecovery(0.84, recovered, 1050)

        assertEquals(RUNNING, running.safety)
        assertEquals(TapAuto, running.action)
        assertEquals(VisualToggleState.ON, running.expected?.auto)
        assertEquals(0, running.retryCount)
    }

    @Test fun `unconditional role toggle records expected state and confirms from image`() {
        val machine = BattleControlStateMachine()
        val initial = observation(global = VisualToggleState.OFF, roles = all(VisualToggleState.OFF))
        machine.update(initial, 0)

        val click = machine.requestToggle(TapRole(CharacterRole.ROLE_3), 10)

        assertEquals(TapRole(CharacterRole.ROLE_3), click.action)
        assertEquals(VisualToggleState.ON, click.expected?.roles?.getValue(CharacterRole.ROLE_3))
        val changed = observation(
            global = VisualToggleState.OFF,
            roles = roles(
                VisualToggleState.OFF,
                VisualToggleState.OFF,
                VisualToggleState.ON,
                VisualToggleState.OFF,
                VisualToggleState.OFF
            )
        )
        assertEquals(false, machine.update(changed, 20).confirmed)
        assertEquals(true, machine.update(changed, 30).confirmed)
    }

    @Test fun `clearing desired target prevents a confirmed opening state from being restored`() {
        val machine = machine(auto = VisualToggleState.ON)
        val current = observation(auto = VisualToggleState.ON)
        assertEquals(true, machine.update(current, 0).confirmed)

        machine.clearDesired()

        assertNull(machine.snapshot().desired)
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
