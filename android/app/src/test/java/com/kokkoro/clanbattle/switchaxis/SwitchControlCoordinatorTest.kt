package com.kokkoro.clanbattle.switchaxis

import com.kokkoro.clanbattle.axis.AxisToggleState
import com.kokkoro.clanbattle.axis.CharacterUbTrigger
import com.kokkoro.clanbattle.axis.SwitchAxisNode
import com.kokkoro.clanbattle.axis.SwitchControlTarget
import com.kokkoro.clanbattle.axis.SwitchNodeTrigger
import com.kokkoro.clanbattle.axis.TimedTrigger
import com.kokkoro.clanbattle.control.BattleControlObservation
import com.kokkoro.clanbattle.control.BattleControlState
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
    @Test fun `timed node dispatches the target delta without arming state machine clicks`() {
        val machine = BattleControlStateMachine()
        val coordinator = coordinator(machine, node("first", 60, target(auto = AxisToggleState.ON)))
        val observed = machine.update(observation(), 0)

        val result = coordinator.update(frame(60), observed)

        assertEquals("first", result.activeNodeId)
        assertTrue(result.busy)
        assertEquals(listOf(ControlAction.TapAuto), result.controlActions)
        assertEquals(VisualToggleState.ON, result.controlStep.desired?.auto)
        assertEquals(all(VisualToggleState.OFF), result.controlStep.desired?.roles)
        assertNull(machine.snapshot().desired)
    }

    @Test fun `later node remains blocked until every target control is visually confirmed`() {
        val machine = BattleControlStateMachine()
        val coordinator = coordinator(
            machine,
            node("first", 60, target(auto = AxisToggleState.ON)),
            node("second", 59, target(auto = AxisToggleState.OFF))
        )
        val first = coordinator.update(frame(60, wallMs = 0), machine.update(observation(), 0))
        assertEquals(listOf(ControlAction.TapAuto), first.controlActions)

        val noExtraClick = machine.update(observation(), 10)
        assertEquals(ControlAction.None, noExtraClick.action)
        assertEquals("first", coordinator.update(frame(59, wallMs = 10), noExtraClick).activeNodeId)

        val autoOn = observation(auto = VisualToggleState.ON)
        val completed = coordinator.update(frame(59, wallMs = 20), machine.update(autoOn, 20))

        assertEquals("second", completed.activeNodeId)
        assertEquals(listOf(ControlAction.TapAuto), completed.controlActions)
        assertEquals(VisualToggleState.OFF, completed.controlStep.desired?.auto)
        assertNull(machine.snapshot().desired)
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

        coordinator.reset(
            opening = null,
            nodes = listOf(node("new", 50, target(auto = AxisToggleState.ON)))
        )
        val result = coordinator.update(frame(50), machine.update(observation(), 10))

        assertEquals("new", result.activeNodeId)
        assertEquals(listOf(ControlAction.TapAuto), result.controlActions)
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

        val autoOn = observation(auto = VisualToggleState.ON)
        val next = coordinator.update(frame(13, wallMs = 20), machine.update(autoOn, 20))

        assertEquals("role3", next.activeNodeId)
        assertNull(machine.snapshot().desired)
    }

    @Test fun `axis 223 node 057 batches both set changes before auto`() {
        val machine = BattleControlStateMachine()
        val coordinator = coordinator(
            machine,
            node(
                "057",
                57,
                target(
                    auto = AxisToggleState.ON,
                    roles = listOf(
                        AxisToggleState.OFF,
                        AxisToggleState.ON,
                        AxisToggleState.ON,
                        AxisToggleState.OFF,
                        AxisToggleState.ON
                    )
                ),
                CharacterUbTrigger(CharacterRole.ROLE_2, "角色2")
            )
        )
        val before = observation(
            auto = VisualToggleState.OFF,
            roles = listOf(
                VisualToggleState.OFF,
                VisualToggleState.ON,
                VisualToggleState.OFF,
                VisualToggleState.ON,
                VisualToggleState.ON
            )
        )
        machine.update(before, 0)
        coordinator.update(frame(57, wallMs = 0), machine.snapshot())

        val triggered = coordinator.update(
            frame(57, triggered = setOf(CharacterRole.ROLE_2), wallMs = 10),
            machine.snapshot("control-hold-trustworthy")
        )

        assertEquals(
            listOf(
                ControlAction.TapRole(CharacterRole.ROLE_3),
                ControlAction.TapRole(CharacterRole.ROLE_4),
                ControlAction.TapAuto
            ),
            triggered.controlActions
        )
        assertNull(machine.snapshot().desired)

        val finalTarget = observation(
            auto = VisualToggleState.ON,
            roles = listOf(
                VisualToggleState.OFF,
                VisualToggleState.ON,
                VisualToggleState.ON,
                VisualToggleState.OFF,
                VisualToggleState.ON
            )
        )
        val completed = coordinator.update(
            frame(56, wallMs = 300),
            machine.update(finalTarget, 300)
        )
        assertFalse(completed.busy)
        assertTrue(completed.controlActions.isEmpty())
    }

    @Test fun `switch batch never retries while ub visual hold is active`() {
        val machine = BattleControlStateMachine()
        val coordinator = coordinator(machine, node("first", 60, target(auto = AxisToggleState.ON)))
        machine.update(observation(), 0)
        coordinator.update(frame(60, wallMs = 0), machine.snapshot())

        val held = coordinator.update(
            frame(60, wallMs = 1500),
            machine.snapshot("control-hold-trustworthy")
        )
        assertTrue(held.controlActions.isEmpty())

        val retry = coordinator.update(
            frame(60, wallMs = 1510),
            machine.update(observation(), 1510)
        )
        assertEquals(listOf(ControlAction.TapAuto), retry.controlActions)
    }

    @Test fun `successful role tap is not repeated when ub effect briefly hides its set badge`() {
        val machine = BattleControlStateMachine()
        val targetRoles = listOf(
            VisualToggleState.OFF,
            VisualToggleState.OFF,
            VisualToggleState.ON,
            VisualToggleState.ON,
            VisualToggleState.OFF
        )
        val coordinator = coordinator(
            machine,
            node(
                "035",
                35,
                target(
                    auto = AxisToggleState.ON,
                    roles = listOf(
                        AxisToggleState.OFF,
                        AxisToggleState.OFF,
                        AxisToggleState.ON,
                        AxisToggleState.ON,
                        AxisToggleState.OFF
                    )
                ),
                CharacterUbTrigger(CharacterRole.ROLE_4, "角色4")
            )
        )
        val before = observation(
            auto = VisualToggleState.OFF,
            roles = listOf(
                VisualToggleState.OFF,
                VisualToggleState.ON,
                VisualToggleState.OFF,
                VisualToggleState.ON,
                VisualToggleState.OFF
            )
        )
        machine.update(before, 0)
        coordinator.update(frame(35, wallMs = 0), machine.snapshot())

        val triggered = coordinator.update(
            frame(35, triggered = setOf(CharacterRole.ROLE_4), wallMs = 10),
            machine.snapshot("control-hold-trustworthy"),
            trustworthyObservation = before
        )
        assertEquals(
            listOf(
                ControlAction.TapRole(CharacterRole.ROLE_2),
                ControlAction.TapRole(CharacterRole.ROLE_3),
                ControlAction.TapAuto
            ),
            triggered.controlActions
        )

        val reachedTarget = observation(auto = VisualToggleState.ON, roles = targetRoles)
        val oldObserved = machine.snapshot("control-hold-trustworthy")
        coordinator.update(
            frame(35, wallMs = 500),
            oldObserved,
            trustworthyObservation = reachedTarget
        )

        val badgeHiddenRoles = targetRoles.toMutableList().apply {
            this[CharacterRole.ROLE_3.ordinal] = VisualToggleState.OFF
        }
        val badgeHidden = observation(auto = VisualToggleState.ON, roles = badgeHiddenRoles)
        val badgeHiddenState = BattleControlState(
            auto = VisualToggleState.ON,
            globalSet = VisualToggleState.OFF,
            roles = CharacterRole.entries.zip(badgeHiddenRoles).toMap()
        )

        repeat(2) { index ->
            val result = coordinator.update(
                frame(35, wallMs = 1_300L + index * 100L),
                machine.snapshot("no-control-target").copy(observed = badgeHiddenState),
                trustworthyObservation = badgeHidden
            )
            assertTrue(result.controlActions.isEmpty())
        }

        val recovered = coordinator.update(
            frame(35, wallMs = 1_500),
            machine.snapshot("no-control-target").copy(observed = badgeHiddenState),
            trustworthyObservation = reachedTarget
        )
        assertTrue(recovered.controlActions.isEmpty())
    }

    @Test fun `confirmed role tap becomes retryable after persistent clean contradiction`() {
        val machine = BattleControlStateMachine()
        val coordinator = coordinator(
            machine,
            node(
                "first",
                60,
                target(
                    roles = listOf(
                        AxisToggleState.OFF,
                        AxisToggleState.OFF,
                        AxisToggleState.ON,
                        AxisToggleState.OFF,
                        AxisToggleState.OFF
                    )
                )
            )
        )
        val before = observation()
        machine.update(before, 0)
        val initial = coordinator.update(frame(60, wallMs = 0), machine.snapshot())
        assertEquals(listOf(ControlAction.TapRole(CharacterRole.ROLE_3)), initial.controlActions)

        val role3On = observation(
            roles = listOf(
                VisualToggleState.OFF,
                VisualToggleState.OFF,
                VisualToggleState.ON,
                VisualToggleState.OFF,
                VisualToggleState.OFF
            )
        )
        coordinator.update(
            frame(60, wallMs = 500),
            machine.snapshot("control-hold-trustworthy"),
            trustworthyObservation = role3On
        )

        val offState = BattleControlState(
            auto = VisualToggleState.OFF,
            globalSet = VisualToggleState.OFF,
            roles = all(VisualToggleState.OFF)
        )
        val firstContradiction = coordinator.update(
            frame(60, wallMs = 1_100),
            machine.snapshot("no-control-target").copy(observed = offState),
            trustworthyObservation = before
        )
        assertTrue(firstContradiction.controlActions.isEmpty())
        val secondContradiction = coordinator.update(
            frame(60, wallMs = 1_200),
            machine.snapshot("no-control-target").copy(observed = offState),
            trustworthyObservation = before
        )
        assertTrue(secondContradiction.controlActions.isEmpty())
        val thirdContradiction = coordinator.update(
            frame(60, wallMs = 1_300),
            machine.snapshot("no-control-target").copy(observed = offState),
            trustworthyObservation = before
        )
        assertEquals(listOf(ControlAction.TapRole(CharacterRole.ROLE_3)), thirdContradiction.controlActions)
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

    private fun target(
        auto: AxisToggleState = AxisToggleState.OFF,
        roles: List<AxisToggleState> = List(5) { AxisToggleState.OFF }
    ) = SwitchControlTarget(
        auto = auto,
        roles = CharacterRole.entries.zip(roles).toMap(),
        rawAuto = if (auto == AxisToggleState.ON) "开" else "关",
        rawRoles = roles.map { if (it == AxisToggleState.ON) "开" else "关" }
    )

    private fun frame(
        clock: Int,
        trustworthy: Boolean = true,
        triggered: Set<CharacterRole> = emptySet(),
        wallMs: Long = 0
    ) = SwitchFrameInput(
        clockSeconds = clock,
        triggeredRoles = triggered,
        controlsTrustworthy = trustworthy,
        wallMs = wallMs
    )

    private fun observation(
        auto: VisualToggleState = VisualToggleState.OFF,
        roles: List<VisualToggleState> = List(5) { VisualToggleState.OFF }
    ) = BattleControlObservation(
        auto = ToggleObservation(auto, 1.0),
        globalSet = ToggleObservation(
            if (roles.all { it == VisualToggleState.ON }) VisualToggleState.ON else VisualToggleState.OFF,
            1.0
        ),
        roles = CharacterRole.entries.zip(roles).associate { (role, state) ->
            role to ToggleObservation(state, 1.0)
        },
        consistent = true
    )

    private fun all(state: VisualToggleState) = CharacterRole.entries.associateWith { state }
   
}
