package com.kokkoro.clanbattle.control

import com.kokkoro.clanbattle.axis.ActionType
import com.kokkoro.clanbattle.axis.AxisAction
import com.kokkoro.clanbattle.axis.AxisEvent
import com.kokkoro.clanbattle.axis.PauseFrameTarget
import com.kokkoro.clanbattle.recognition.CharacterRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedActionCoordinatorTest {
    @Test fun `role lifecycle waits indefinitely for matching ub then turns set off`() {
        val machine = BattleControlStateMachine()
        val coordinator = coordinator(machine)
        coordinator.enqueue(listOf(eventAt(63, AxisAction(ActionType.CLICK_ROLE, role = "角色3"))))

        val setOn = coordinator.update(machine.snapshot(), 10, clockSeconds = 63)
        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_3), setOn.newControlAction)
        assertEquals("WAITING_ROLE_UB", setOn.phase)

        val wrongRole = coordinator.update(
            machine.snapshot(),
            20,
            triggeredRoles = setOf(CharacterRole.ROLE_2),
            clockSeconds = 62
        )
        assertEquals(ControlAction.None, wrongRole.newControlAction)
        assertTrue(wrongRole.busy)

        // Written time is long gone, but sequence semantics require waiting for
        // ROLE3 itself rather than timing out or accepting TP fallback evidence.
        val muchLater = coordinator.update(machine.snapshot(), 30_000, clockSeconds = 20)
        assertEquals(ControlAction.None, muchLater.newControlAction)
        assertEquals("WAITING_ROLE_UB", muchLater.phase)
        assertTrue(muchLater.busy)

        val released = coordinator.update(
            machine.snapshot(),
            30_010,
            triggeredRoles = setOf(CharacterRole.ROLE_3),
            clockSeconds = 20
        )
        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_3), released.newControlAction)
        assertFalse(released.busy)
    }

    @Test fun `next role cannot start before previous role ub`() {
        val machine = BattleControlStateMachine()
        val coordinator = coordinator(machine)
        coordinator.enqueue(
            listOf(
                eventAt(60, AxisAction(ActionType.CLICK_ROLE, role = "角色2")),
                eventAt(60, AxisAction(ActionType.CLICK_ROLE, role = "角色3"))
            )
        )

        assertEquals(
            ControlAction.TapRole(CharacterRole.ROLE_2),
            coordinator.update(machine.snapshot(), 10).newControlAction
        )
        repeat(5) { index ->
            val waiting = coordinator.update(machine.snapshot(), 100L + index)
            assertEquals(ControlAction.None, waiting.newControlAction)
            assertEquals("WAITING_ROLE_UB", waiting.phase)
        }

        val role2Off = coordinator.update(
            machine.snapshot(),
            200,
            triggeredRoles = setOf(CharacterRole.ROLE_2)
        )
        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_2), role2Off.newControlAction)
        assertTrue(role2Off.busy)

        val role3On = coordinator.update(machine.snapshot(), 210)
        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_3), role3On.newControlAction)
        assertEquals("WAITING_ROLE_UB", role3On.phase)
    }

    @Test fun `fresh same-role lifecycle cannot reuse ub event from frame that armed set`() {
        val machine = BattleControlStateMachine()
        val coordinator = coordinator(machine)

        coordinator.enqueue(listOf(eventAt(51, AxisAction(ActionType.CLICK_ROLE, role = "角色5"))))
        assertEquals(
            ControlAction.TapRole(CharacterRole.ROLE_5),
            coordinator.update(machine.snapshot(), 10, clockSeconds = 51).newControlAction
        )

        val firstRelease = coordinator.update(
            machine.snapshot(),
            20,
            triggeredRoles = setOf(CharacterRole.ROLE_5),
            clockSeconds = 51
        )
        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_5), firstRelease.newControlAction)
        assertFalse(firstRelease.busy)

        // The next plain ROLE5 line may be chained immediately while the first
        // UB event is still present in this frame.  It must only arm SET here;
        // the old ROLE5 event cannot satisfy the new lifecycle.
        coordinator.enqueue(listOf(eventAt(48, AxisAction(ActionType.CLICK_ROLE, role = "角色5"))))
        val rearmed = coordinator.update(
            machine.snapshot(),
            20,
            triggeredRoles = setOf(CharacterRole.ROLE_5),
            clockSeconds = 51
        )
        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_5), rearmed.newControlAction)
        assertTrue(rearmed.busy)
        assertEquals("WAITING_ROLE_UB", rearmed.phase)

        val staleGone = coordinator.update(
            machine.snapshot(),
            21,
            triggeredRoles = emptySet(),
            clockSeconds = 51
        )
        assertEquals(ControlAction.None, staleGone.newControlAction)
        assertTrue(staleGone.busy)

        val secondRelease = coordinator.update(
            machine.snapshot(),
            100,
            triggeredRoles = setOf(CharacterRole.ROLE_5),
            clockSeconds = 48
        )
        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_5), secondRelease.newControlAction)
        assertFalse(secondRelease.busy)
    }

    @Test fun `role already on internally waits without another on tap`() {
        val machine = BattleControlStateMachine()
        val authority = AuthoritativeControlState().apply {
            seedIfAbsent(state(role2 = VisualToggleState.ON))
        }
        val coordinator = VerifiedActionCoordinator(machine, authority)
        coordinator.enqueue(listOf(event(AxisAction(ActionType.CLICK_ROLE, role = "角色2"))))

        val waiting = coordinator.update(machine.snapshot(), 10)
        assertEquals(ControlAction.None, waiting.newControlAction)
        assertEquals("WAITING_ROLE_UB", waiting.phase)

        val cleanup = coordinator.update(
            machine.snapshot(),
            20,
            triggeredRoles = setOf(CharacterRole.ROLE_2)
        )
        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_2), cleanup.newControlAction)
    }

    @Test fun `pause frame preset role skips set on and waits for ub`() {
        val machine = BattleControlStateMachine()
        val authority = AuthoritativeControlState().apply {
            seedIfAbsent(state())
            applyPauseFrameTarget(PauseFrameTarget.Role(CharacterRole.ROLE_4))
        }
        val coordinator = VerifiedActionCoordinator(machine, authority)
        coordinator.enqueue(
            listOf(eventAt(59, AxisAction(ActionType.CLICK_ROLE, role = "角色4"))),
            rolesAlreadySet = setOf(CharacterRole.ROLE_4),
            nowMs = 10
        )

        val waiting = coordinator.update(machine.snapshot(), 20, clockSeconds = 59)
        assertEquals(ControlAction.None, waiting.newControlAction)
        assertEquals("WAITING_ROLE_UB", waiting.phase)

        val cleanup = coordinator.update(
            machine.snapshot(),
            30,
            triggeredRoles = setOf(CharacterRole.ROLE_4),
            clockSeconds = 58
        )
        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_4), cleanup.newControlAction)
    }

    @Test fun `recent ub can close a just confirmed pause frame lifecycle`() {
        val machine = BattleControlStateMachine()
        val authority = AuthoritativeControlState().apply {
            seedIfAbsent(state())
            applyPauseFrameTarget(PauseFrameTarget.Role(CharacterRole.ROLE_1))
        }
        val coordinator = VerifiedActionCoordinator(machine, authority)
        coordinator.observeFrame(setOf(CharacterRole.ROLE_1), clockSeconds = 60, nowMs = 100)
        coordinator.enqueue(
            listOf(eventAt(60, AxisAction(ActionType.CLICK_ROLE, role = "角色1"))),
            rolesAlreadySet = setOf(CharacterRole.ROLE_1),
            nowMs = 110
        )

        val cleanup = coordinator.update(machine.snapshot(), 120, clockSeconds = 59)
        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_1), cleanup.newControlAction)
        assertFalse(cleanup.busy)
    }

    @Test fun `clearing pause evidence prevents old ub from closing lifecycle`() {
        val machine = BattleControlStateMachine()
        val authority = AuthoritativeControlState().apply {
            seedIfAbsent(state())
            applyPauseFrameTarget(PauseFrameTarget.Role(CharacterRole.ROLE_1))
        }
        val coordinator = VerifiedActionCoordinator(machine, authority)
        coordinator.observeFrame(setOf(CharacterRole.ROLE_1), clockSeconds = 60, nowMs = 100)
        coordinator.clearRecentRoleUb(CharacterRole.ROLE_1)
        coordinator.enqueue(
            listOf(eventAt(60, AxisAction(ActionType.CLICK_ROLE, role = "角色1"))),
            rolesAlreadySet = setOf(CharacterRole.ROLE_1),
            nowMs = 110
        )

        val waiting = coordinator.update(machine.snapshot(), 120, clockSeconds = 59)
        assertEquals(ControlAction.None, waiting.newControlAction)
        assertTrue(waiting.busy)
    }

    @Test fun `set target uses authoritative state without visual confirmation`() {
        val machine = BattleControlStateMachine()
        val authority = AuthoritativeControlState().apply { seedIfAbsent(state()) }
        val coordinator = VerifiedActionCoordinator(machine, authority)
        coordinator.enqueue(
            listOf(
                event(
                    AxisAction(
                        ActionType.SET_ROLES,
                        values = listOf("关", "开", "开", "关", "关")
                    )
                )
            )
        )

        val role2 = coordinator.update(machine.snapshot(), 10)
        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_2), role2.newControlAction)
        assertTrue(role2.busy)

        // Still pass the same stale visual snapshot. Internal state must plan the
        // second delta instead of trying ROLE2 again.
        val role3 = coordinator.update(machine.snapshot(), 20)
        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_3), role3.newControlAction)
        assertFalse(role3.busy)
    }

    @Test fun `direct auto toggle updates internal state without visual confirmation`() {
        val machine = BattleControlStateMachine()
        val authority = AuthoritativeControlState().apply { seedIfAbsent(state()) }
        val coordinator = VerifiedActionCoordinator(machine, authority)
        coordinator.enqueue(listOf(event(AxisAction(ActionType.CLICK_AUTO))))

        val toggled = coordinator.update(machine.snapshot(), 10)
        assertEquals(ControlAction.TapAuto, toggled.newControlAction)
        assertFalse(toggled.busy)
        assertEquals(VisualToggleState.ON, authority.snapshot()?.auto)
    }

    @Test fun `visual contradiction never causes an automatic corrective tap`() {
        val machine = BattleControlStateMachine()
        val authority = AuthoritativeControlState().apply {
            seedIfAbsent(state(role3 = VisualToggleState.ON))
        }
        val coordinator = VerifiedActionCoordinator(machine, authority)
        coordinator.enqueue(listOf(event(AxisAction(ActionType.CLICK_ROLE, role = "角色3"))))

        // Internal state says ROLE3 is already ON. A stale OFF visual snapshot is
        // deliberately not allowed to overwrite that truth or trigger another tap.
        machine.update(observation(), 0)
        val waiting = coordinator.update(machine.snapshot(), 10)
        assertEquals(ControlAction.None, waiting.newControlAction)
        assertEquals("WAITING_ROLE_UB", waiting.phase)
    }

    @Test fun `normal actions keep source order before verified control`() {
        val machine = BattleControlStateMachine()
        val coordinator = coordinator(machine)
        coordinator.enqueue(
            listOf(
                event(
                    AxisAction(ActionType.NOTIFY, message = "first"),
                    AxisAction(ActionType.BOSS),
                    AxisAction(ActionType.CLICK_AUTO)
                )
            )
        )

        val result = coordinator.update(machine.snapshot(), 10)
        assertEquals(
            listOf(ActionType.NOTIFY, ActionType.BOSS),
            result.immediateEvents.map { it.actions.single().type }
        )
        assertEquals(ControlAction.TapAuto, result.newControlAction)
    }

    @Test fun `configured sequence alias resolves to role`() {
        val machine = BattleControlStateMachine()
        val coordinator = coordinator(machine)
        coordinator.configureRoleAliases(mapOf("角色3" to "原晶"))
        coordinator.enqueue(listOf(event(AxisAction(ActionType.CLICK_ROLE, role = "原晶"))))

        val step = coordinator.update(machine.snapshot(), 10)
        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_3), step.newControlAction)
    }

    @Test fun `manual recognition pause forgets ub evidence but keeps deterministic control state`() {
        val machine = BattleControlStateMachine()
        val authority = AuthoritativeControlState().apply { seedIfAbsent(state()) }
        val coordinator = VerifiedActionCoordinator(machine, authority)
        coordinator.enqueue(listOf(event(AxisAction(ActionType.CLICK_ROLE, role = "角色2"))))
        assertEquals(
            ControlAction.TapRole(CharacterRole.ROLE_2),
            coordinator.update(machine.snapshot(), 10).newControlAction
        )

        coordinator.observeFrame(setOf(CharacterRole.ROLE_2), 60, 20)
        coordinator.restartAfterRecognitionPause()

        // Restart sees ROLE2 already ON in authoritative state, so it waits for
        // a new UB instead of duplicating the SET-on tap or consuming old evidence.
        val waiting = coordinator.update(machine.snapshot(), 30)
        assertEquals(ControlAction.None, waiting.newControlAction)
        assertEquals("WAITING_ROLE_UB", waiting.phase)
        assertTrue(waiting.busy)
    }

    private fun coordinator(machine: BattleControlStateMachine): VerifiedActionCoordinator {
        val authority = AuthoritativeControlState().apply { seedIfAbsent(state()) }
        return VerifiedActionCoordinator(machine, authority)
    }

    private fun event(vararg actions: AxisAction) = eventAt(60, *actions)

    private fun eventAt(timeSeconds: Int, vararg actions: AxisAction) =
        AxisEvent("event", 1, timeSeconds, actions.toList())

    private fun state(
        auto: VisualToggleState = VisualToggleState.OFF,
        role2: VisualToggleState = VisualToggleState.OFF,
        role3: VisualToggleState = VisualToggleState.OFF
    ) = observation(auto, role2, role3).toControlState()

    private fun observation(
        auto: VisualToggleState = VisualToggleState.OFF,
        role2: VisualToggleState = VisualToggleState.OFF,
        role3: VisualToggleState = VisualToggleState.OFF
    ) = BattleControlObservation(
        auto = ToggleObservation(auto, 0.9),
        globalSet = ToggleObservation(VisualToggleState.OFF, 0.9),
        roles = CharacterRole.entries.associateWith { role ->
            val state = when (role) {
                CharacterRole.ROLE_2 -> role2
                CharacterRole.ROLE_3 -> role3
                else -> VisualToggleState.OFF
            }
            ToggleObservation(state, 0.9)
        },
        consistent = true
    )
}
