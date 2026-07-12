package com.kokkoro.clanbattle.switchaxis

import com.kokkoro.clanbattle.axis.AxisToggleState
import com.kokkoro.clanbattle.axis.SwitchAxisNode
import com.kokkoro.clanbattle.axis.SwitchControlTarget
import com.kokkoro.clanbattle.axis.TimedTrigger
import com.kokkoro.clanbattle.control.BattleControlObservation
import com.kokkoro.clanbattle.control.BattleControlStateMachine
import com.kokkoro.clanbattle.control.ControlAction
import com.kokkoro.clanbattle.control.ToggleObservation
import com.kokkoro.clanbattle.control.VisualToggleState
import com.kokkoro.clanbattle.recognition.CharacterRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchControlCoordinatorTest {
    @Test fun `timed node installs the complete desired control target`() {
        val machine = BattleControlStateMachine()
        val coordinator = coordinator(machine, node("first", 60, target(auto = AxisToggleState.ON)))
        val observed = machine.update(observation(), 0)

        val result = coordinator.update(frame(60), observed)

        assertEquals("first", result.activeNodeId)
        assertTrue(result.busy)
        assertEquals(VisualToggleState.ON, machine.snapshot().desired?.auto)
        assertEquals(all(VisualToggleState.OFF), machine.snapshot().desired?.roles)
    }

    @Test fun `later node remains blocked until every target control is visually confirmed`() {
        val machine = BattleControlStateMachine()
        val coordinator = coordinator(
            machine,
            node("first", 60, target(auto = AxisToggleState.ON)),
            node("second", 59, target(auto = AxisToggleState.OFF))
        )
        coordinator.update(frame(60), machine.update(observation(), 0))

        val click = machine.update(observation(), 10)
        assertEquals(ControlAction.TapAuto, click.action)
        assertEquals("first", coordinator.update(frame(59), click).activeNodeId)

        val autoOn = observation(auto = VisualToggleState.ON)
        assertEquals("first", coordinator.update(frame(59), machine.update(autoOn, 20)).activeNodeId)
        val completed = coordinator.update(frame(59), machine.update(autoOn, 30))

        assertEquals("second", completed.activeNodeId)
        assertEquals(VisualToggleState.OFF, machine.snapshot().desired?.auto)
    }

    @Test fun `untrustworthy controls do not arm a timed convergence`() {
        val machine = BattleControlStateMachine()
        val coordinator = coordinator(machine, node("first", 60, target(auto = AxisToggleState.ON)))

        val result = coordinator.update(frame(60, trustworthy = false), machine.snapshot())

        assertTrue(result.busy)
        assertEquals("first", result.activeNodeId)
        assertNull(machine.snapshot().desired)
    }

    @Test fun `reset replaces pending nodes with the new axis`() {
        val machine = BattleControlStateMachine()
        val coordinator = coordinator(machine, node("old", 60, target()))
        coordinator.update(frame(60), machine.update(observation(), 0))

        coordinator.reset(opening = null, nodes = listOf(node("new", 50, target())))
        val result = coordinator.update(frame(50), machine.update(observation(), 10))

        assertEquals("new", result.activeNodeId)
    }

    private fun coordinator(
        machine: BattleControlStateMachine,
        vararg nodes: SwitchAxisNode
    ) = SwitchControlCoordinator(machine, opening = null, nodes = nodes.toList())

    private fun node(id: String, time: Int, target: SwitchControlTarget) =
        SwitchAxisNode(id, 1, time, TimedTrigger, target)

    private fun target(auto: AxisToggleState = AxisToggleState.OFF) = SwitchControlTarget(
        auto = auto,
        roles = CharacterRole.entries.associateWith { AxisToggleState.OFF },
        rawAuto = if (auto == AxisToggleState.ON) "开" else "关",
        rawRoles = List(5) { "关" }
    )

    private fun frame(clock: Int, trustworthy: Boolean = true) = SwitchFrameInput(
        clockSeconds = clock,
        triggeredRoles = emptySet(),
        controlsTrustworthy = trustworthy,
        wallMs = 0
    )

    private fun observation(auto: VisualToggleState = VisualToggleState.OFF) = BattleControlObservation(
        auto = ToggleObservation(auto, 1.0),
        globalSet = ToggleObservation(VisualToggleState.OFF, 1.0),
        roles = CharacterRole.entries.associateWith {
            ToggleObservation(VisualToggleState.OFF, 1.0)
        },
        consistent = true
    )

    private fun all(state: VisualToggleState) = CharacterRole.entries.associateWith { state }
}
