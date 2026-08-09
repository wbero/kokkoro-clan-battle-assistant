package com.kokkoro.clanbattle.switchaxis

import com.kokkoro.clanbattle.axis.AxisToggleState
import com.kokkoro.clanbattle.axis.CharacterUbTrigger
import com.kokkoro.clanbattle.axis.SwitchAxisNode
import com.kokkoro.clanbattle.axis.SwitchControlTarget
import com.kokkoro.clanbattle.axis.SwitchNodeTrigger
import com.kokkoro.clanbattle.axis.TimedTrigger
import com.kokkoro.clanbattle.control.AuthoritativeControlState
import com.kokkoro.clanbattle.control.BattleControlObservation
import com.kokkoro.clanbattle.control.BattleControlStateMachine
import com.kokkoro.clanbattle.control.ControlAction
import com.kokkoro.clanbattle.control.ControlSafetyState
import com.kokkoro.clanbattle.control.ToggleObservation
import com.kokkoro.clanbattle.control.VisualToggleState
import com.kokkoro.clanbattle.control.toControlState
import com.kokkoro.clanbattle.recognition.CharacterRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchControlCoordinatorTest {
    @Test fun `timed node plans delta from authoritative state and completes immediately`() {
        val machine = BattleControlStateMachine()
        val coordinator = coordinator(machine, node("first", 60, target(auto = AxisToggleState.ON)))

        val result = coordinator.update(frame(60), machine.snapshot())

        assertEquals(listOf(ControlAction.TapAuto), result.controlActions)
        assertFalse(result.busy)
        assertNull(result.activeNodeId)
        assertNull(machine.snapshot().desired)
    }

    @Test fun `later node uses predicted state even when visual remains stale`() {
        val machine = BattleControlStateMachine()
        val coordinator = coordinator(
            machine,
            node("first", 60, target(auto = AxisToggleState.ON)),
            node("second", 59, target(auto = AxisToggleState.OFF))
        )

        assertEquals(
            listOf(ControlAction.TapAuto),
            coordinator.update(frame(60), machine.snapshot()).controlActions
        )

        // BattleControlStateMachine still carries the original OFF visual state.
        // The second node must nevertheless know the first TapAuto predicted ON.
        assertEquals(
            listOf(ControlAction.TapAuto),
            coordinator.update(frame(59), machine.snapshot()).controlActions
        )
    }

    @Test fun `timed node still waits for trustworthy control timing gate`() {
        val machine = BattleControlStateMachine()
        val coordinator = coordinator(machine, node("first", 60, target(auto = AxisToggleState.ON)))

        val held = coordinator.update(frame(60, trustworthy = false), machine.snapshot())
        assertTrue(held.controlActions.isEmpty())
        assertTrue(held.busy)

        val dispatched = coordinator.update(frame(60, trustworthy = true), machine.snapshot())
        assertEquals(listOf(ControlAction.TapAuto), dispatched.controlActions)
    }

    @Test fun `character ub executes target inside ub even when templates are untrustworthy`() {
        val machine = BattleControlStateMachine()
        val coordinator = coordinator(
            machine,
            node(
                "057",
                57,
                target(auto = AxisToggleState.ON),
                CharacterUbTrigger(CharacterRole.ROLE_2, "角色2")
            )
        )

        coordinator.update(frame(57), machine.snapshot()) // arm first
        val triggered = coordinator.update(
            frame(57, trustworthy = false, triggered = setOf(CharacterRole.ROLE_2), wallMs = 10),
            machine.snapshot("control-hold-trustworthy")
        )

        assertEquals(listOf(ControlAction.TapAuto), triggered.controlActions)
        assertFalse(triggered.busy)
    }

    @Test fun `046 polluted visual cannot add role5 tap`() {
        val machine = BattleControlStateMachine()
        val clean = observation(
            auto = VisualToggleState.OFF,
            roles = mask("XOOXO")
        )
        val authority = AuthoritativeControlState().apply { seedIfAbsent(clean.toControlState()) }
        val coordinator = SwitchControlCoordinator(
            stateMachine = machine,
            opening = null,
            nodes = listOf(
                node(
                    "046",
                    46,
                    target(
                        auto = AxisToggleState.ON,
                        roles = axisMask("XOXOO")
                    ),
                    CharacterUbTrigger(CharacterRole.ROLE_5, "角色5")
                )
            ),
            authoritativeControls = authority
        )
        machine.update(clean, 0)
        coordinator.update(frame(46), machine.snapshot())

        // The animation-polluted visual says ROLE5 is OFF (XOOXX), but planning
        // must remain based on clean authoritative XOOXO.
        val polluted = observation(auto = VisualToggleState.OFF, roles = mask("XOOXX"))
        val triggered = coordinator.update(
            frame(46, trustworthy = false, triggered = setOf(CharacterRole.ROLE_5), wallMs = 20),
            machine.update(polluted, 20)
        )

        assertEquals(
            listOf(
                ControlAction.TapRole(CharacterRole.ROLE_3),
                ControlAction.TapRole(CharacterRole.ROLE_4),
                ControlAction.TapAuto
            ),
            triggered.controlActions
        )
        assertTrue(ControlAction.TapRole(CharacterRole.ROLE_5) !in triggered.controlActions)
    }

    @Test fun `visual contradiction never causes automatic retry`() {
        val machine = BattleControlStateMachine()
        val coordinator = coordinator(
            machine,
            node("first", 60, target(roles = axisMask("XXOXX")))
        )

        val first = coordinator.update(frame(60), machine.snapshot())
        assertEquals(listOf(ControlAction.TapRole(CharacterRole.ROLE_3)), first.controlActions)

        repeat(5) { index ->
            val staleOff = machine.update(observation(), 100L + index)
            val later = coordinator.update(frame(59, wallMs = 100L + index), staleOff)
            assertTrue(later.controlActions.isEmpty())
        }
    }

    @Test fun `missed exact-clock character ub still enters safety`() {
        val machine = BattleControlStateMachine()
        val coordinator = coordinator(
            machine,
            node(
                "103",
                63,
                target(),
                CharacterUbTrigger(CharacterRole.ROLE_3, "角色3")
            )
        )
        coordinator.update(frame(63, wallMs = 0), machine.snapshot())

        val missed = coordinator.update(frame(62, wallMs = 100), machine.snapshot())

        assertEquals(ControlSafetyState.SAFETY_PAUSING, missed.controlStep.safety)
        assertTrue(missed.controlStep.reason.orEmpty().startsWith("switch-character-ub-missed:103:ROLE_3:63->62"))
        assertTrue(missed.controlActions.isEmpty())
    }

    @Test fun `cached ub from earlier game second cannot satisfy newly armed node`() {
        val machine = BattleControlStateMachine()
        val coordinator = coordinator(
            machine,
            node(
                "103",
                63,
                target(auto = AxisToggleState.ON),
                CharacterUbTrigger(CharacterRole.ROLE_3, "角色3")
            )
        )

        coordinator.update(
            frame(64, triggered = setOf(CharacterRole.ROLE_3), wallMs = 0),
            machine.snapshot()
        )
        val armed = coordinator.update(frame(63, wallMs = 100), machine.snapshot())
        val stillWaiting = coordinator.update(frame(63, wallMs = 200), machine.snapshot())

        assertTrue(armed.controlActions.isEmpty())
        assertTrue(stillWaiting.controlActions.isEmpty())
        assertEquals("103", stillWaiting.activeNodeId)
    }

    @Test fun `safety pause freezes switch runtime`() {
        val machine = BattleControlStateMachine()
        val coordinator = coordinator(machine, node("first", 60, target(auto = AxisToggleState.ON)))
        machine.forceSafety("test-safety")

        val held = coordinator.update(frame(60), machine.snapshot("test-safety"))

        assertEquals(ControlSafetyState.SAFETY_PAUSING, held.controlStep.safety)
        assertTrue(held.controlActions.isEmpty())
    }

    @Test fun `reset clears predicted state and uses new seed`() {
        val machine = BattleControlStateMachine()
        val authority = AuthoritativeControlState().apply { seedIfAbsent(observation().toControlState()) }
        val coordinator = SwitchControlCoordinator(
            stateMachine = machine,
            opening = null,
            nodes = listOf(node("old", 60, target(auto = AxisToggleState.ON))),
            authoritativeControls = authority
        )
        coordinator.update(frame(60), machine.snapshot())

        coordinator.reset(
            opening = null,
            nodes = listOf(node("new", 50, target(auto = AxisToggleState.OFF)))
        )
        val newSeed = observation(auto = VisualToggleState.ON)
        coordinator.seedControlState(newSeed.toControlState())

        val result = coordinator.update(frame(50), machine.snapshot())
        assertEquals(listOf(ControlAction.TapAuto), result.controlActions)
    }

    private fun coordinator(
        machine: BattleControlStateMachine,
        vararg nodes: SwitchAxisNode
    ): SwitchControlCoordinator {
        val authority = AuthoritativeControlState().apply { seedIfAbsent(observation().toControlState()) }
        return SwitchControlCoordinator(
            machine,
            opening = null,
            nodes = nodes.toList(),
            authoritativeControls = authority
        )
    }

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
        wallMs = wallMs,
        triggeredRoleClockSeconds = triggered.associateWith { clock }
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

    private fun mask(value: String): List<VisualToggleState> =
        value.map { if (it == 'O') VisualToggleState.ON else VisualToggleState.OFF }

    private fun axisMask(value: String): List<AxisToggleState> =
        value.map { if (it == 'O') AxisToggleState.ON else AxisToggleState.OFF }
}
