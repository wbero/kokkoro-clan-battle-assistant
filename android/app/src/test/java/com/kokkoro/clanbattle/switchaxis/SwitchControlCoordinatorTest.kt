package com.kokkoro.clanbattle.switchaxis

import com.kokkoro.clanbattle.axis.AxisToggleState
import com.kokkoro.clanbattle.axis.CharacterUbTrigger
import com.kokkoro.clanbattle.axis.SwitchAxisNode
import com.kokkoro.clanbattle.axis.SwitchControlTarget
import com.kokkoro.clanbattle.axis.SwitchNodeTrigger
import com.kokkoro.clanbattle.axis.TimedTrigger
import com.kokkoro.clanbattle.control.BattleControlObservation
import com.kokkoro.clanbattle.control.BattleControlStateMachine
import com.kokkoro.clanbattle.control.ControlAction
import com.kokkoro.clanbattle.control.ControlSafetyState
import com.kokkoro.clanbattle.control.ToggleObservation
import com.kokkoro.clanbattle.control.VisualToggleState
import com.kokkoro.clanbattle.recognition.CharacterRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun `safety pause freezes runtime and preserves the original reason`() {
        val machine = BattleControlStateMachine()
        val coordinator = coordinator(machine, node("first", 60, target(auto = AxisToggleState.ON)))
        machine.forceSafety("control-recognition-failed:raw_untrustworthy")

        val result = coordinator.update(frame(60), machine.snapshot("control-recognition-failed:raw_untrustworthy"))

        assertEquals(ControlSafetyState.SAFETY_PAUSING, result.controlStep.safety)
        assertEquals("control-recognition-failed:raw_untrustworthy", result.controlStep.reason)
        assertFalse(result.busy)
        assertNull(result.activeNodeId)
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

     @Test fun `role ub pulse during a safety pause is not lost once safety recovers`() {
        val machine = BattleControlStateMachine()
        val coordinator = coordinator(
            machine,
            node("role1", 60, target(), CharacterUbTrigger(CharacterRole.ROLE_1, "角色1"))
        )
        // 先让节点进入 Armed 状态，且这一帧不带触发，排除 armedNow 的干扰
        coordinator.update(frame(60), machine.update(observation(), 0))

        // 安全门误报期间，恰好这一帧收到了角色1的UB脉冲
        machine.forceSafety("control-recognition-failed:raw_untrustworthy")
        val duringSafety = coordinator.update(
            frame(60, triggered = setOf(CharacterRole.ROLE_1)),
            machine.snapshot("control-recognition-failed:raw_untrustworthy")
        )
        assertEquals(ControlSafetyState.SAFETY_PAUSING, duringSafety.controlStep.safety)

        // safety 恢复 RUNNING 之后，这次脉冲不应该已经彻底丢失
        val recovered = coordinator.update(frame(60), machine.update(observation(), 100))
        assertEquals("role1", recovered.activeNodeId)
        assertTrue(recovered.busy)
    }

    @Test fun `consuming one character ub clears ambiguous same-pulse roles`() {
        val machine = BattleControlStateMachine()
        val coordinator = coordinator(
            machine,
            node(
                "role4",
                14,
                target(auto = AxisToggleState.ON),
                CharacterUbTrigger(CharacterRole.ROLE_4, "角色4")
            ),
            node(
                "role3",
                13,
                target(auto = AxisToggleState.OFF),
                CharacterUbTrigger(CharacterRole.ROLE_3, "角色3")
            )
        )
        val observed = machine.update(observation(), 0)
        coordinator.update(frame(14), observed)

        val consumed = coordinator.update(
            frame(14, triggered = setOf(CharacterRole.ROLE_3, CharacterRole.ROLE_4)),
            observed
        )
        assertEquals("role4", consumed.activeNodeId)

        val click = machine.update(observation(), 10)
        coordinator.update(frame(13), click)
        val autoOn = observation(auto = VisualToggleState.ON)
        coordinator.update(frame(13), machine.update(autoOn, 20))
        val next = coordinator.update(frame(13), machine.update(autoOn, 30))

        assertEquals("role3", next.activeNodeId)
        assertNull(machine.snapshot().desired)
    }

    private fun coordinator(
        machine: BattleControlStateMachine,
        vararg nodes: SwitchAxisNode
    ) = SwitchControlCoordinator(machine, opening = null, nodes = nodes.toList())

    private fun node(
        id: String,
        time: Int,
        target: SwitchControlTarget,
        trigger: SwitchNodeTrigger = TimedTrigger
    ) = SwitchAxisNode(id, 1, time, trigger, target)

    private fun target(auto: AxisToggleState = AxisToggleState.OFF) = SwitchControlTarget(
        auto = auto,
        roles = CharacterRole.entries.associateWith { AxisToggleState.OFF },
        rawAuto = if (auto == AxisToggleState.ON) "开" else "关",
        rawRoles = List(5) { "关" }
    )

    private fun frame(
        clock: Int,
        trustworthy: Boolean = true,
        triggered: Set<CharacterRole> = emptySet()
    ) = SwitchFrameInput(
        clockSeconds = clock,
        triggeredRoles = triggered,
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
