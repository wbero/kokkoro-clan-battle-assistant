package com.kokkoro.clanbattle.control

import com.kokkoro.clanbattle.axis.ActionType
import com.kokkoro.clanbattle.axis.AxisAction
import com.kokkoro.clanbattle.axis.AxisEvent
import com.kokkoro.clanbattle.recognition.CharacterRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedActionCoordinatorTest {
    @Test fun `role click waits for matching ub then turns set off before following action`() {
        val machine = BattleControlStateMachine()
        val coordinator = VerifiedActionCoordinator(machine)
        machine.update(observation(), 0)
        coordinator.enqueue(
            listOf(event(
                AxisAction(ActionType.CLICK_ROLE, role = "角色2"),
                AxisAction(ActionType.NOTIFY, message = "after")
            ))
        )

        val click = coordinator.update(machine.snapshot(), 10)

        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_2), click.controlStep.action)
        assertTrue(click.busy)
        assertTrue(click.immediateEvents.isEmpty())

        val changed = observation(role2 = VisualToggleState.ON)
        assertTrue(coordinator.update(machine.update(changed, 20), 20).busy)
        val setOn = coordinator.update(machine.update(changed, 30), 30)
        assertTrue(setOn.busy)
        assertEquals("WAITING_ROLE_UB", setOn.phase)

        val noUb = coordinator.update(machine.snapshot(), 40)
        assertTrue(noUb.busy)
        assertTrue(noUb.immediateEvents.isEmpty())

        val wrongUb = coordinator.update(
            machine.snapshot(),
            50,
            triggeredRoles = setOf(CharacterRole.ROLE_3)
        )
        assertTrue(wrongUb.busy)

        val clearSet = coordinator.update(
            machine.snapshot(),
            60,
            triggeredRoles = setOf(CharacterRole.ROLE_2)
        )
        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_2), clearSet.newControlAction)
        assertEquals("CONFIRMING_ROLE_OFF", clearSet.phase)

        val changedOff = observation(role2 = VisualToggleState.OFF)
        coordinator.update(machine.update(changedOff, 70), 70)
        val cleared = coordinator.update(machine.update(changedOff, 80), 80)
        assertTrue(cleared.busy)

        val after = coordinator.update(machine.snapshot(), 90)
        assertEquals(ActionType.NOTIFY, after.immediateEvents.single().actions.single().type)
        assertFalse(after.busy)
    }

    @Test fun `target action converges before following event is released`() {
        val machine = BattleControlStateMachine()
        val coordinator = VerifiedActionCoordinator(machine)
        val off = observation(auto = VisualToggleState.OFF)
        machine.update(off, 0)
        coordinator.enqueue(
            listOf(event(
                AxisAction(ActionType.TOGGLE_AUTO, value = "on"),
                AxisAction(ActionType.BOSS)
            ))
        )

        val targetQueued = coordinator.update(machine.snapshot(), 10)
        assertTrue(targetQueued.busy)
        assertEquals(ControlAction.None, targetQueued.controlStep.action)
        assertEquals(ControlAction.TapAuto, machine.update(off, 20).action)

        val on = observation(auto = VisualToggleState.ON)
        coordinator.update(machine.update(on, 30), 30)
        val confirmed = coordinator.update(machine.update(on, 40), 40)
        assertTrue(confirmed.busy)

        val after = coordinator.update(machine.snapshot(), 50)
        assertEquals(ActionType.BOSS, after.immediateEvents.single().actions.single().type)
        assertFalse(after.busy)
        assertEquals(null, machine.snapshot().desired)
    }

    @Test fun `normal actions before a control action keep their order`() {
        val machine = BattleControlStateMachine()
        val coordinator = VerifiedActionCoordinator(machine)
        machine.update(observation(), 0)
        coordinator.enqueue(
            listOf(event(
                AxisAction(ActionType.NOTIFY, message = "first"),
                AxisAction(ActionType.BOSS),
                AxisAction(ActionType.CLICK_AUTO)
            ))
        )

        val result = coordinator.update(machine.snapshot(), 10)

        assertEquals(listOf(ActionType.NOTIFY, ActionType.BOSS), result.immediateEvents.map { it.actions.single().type })
        assertEquals(ControlAction.TapAuto, result.controlStep.action)
        assertTrue(result.busy)
    }

    @Test fun `manual recovery retries an unconfirmed direct toggle from fresh observation`() {
        val machine = BattleControlStateMachine()
        val coordinator = VerifiedActionCoordinator(machine)
        val unchanged = observation()
        machine.update(unchanged, 0)
        coordinator.enqueue(listOf(event(AxisAction(ActionType.CLICK_ROLE, role = "角色2"))))
        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_2), coordinator.update(machine.snapshot(), 10).newControlAction)

        machine.forceSafety("test")
        coordinator.update(machine.snapshot(), 20)
        coordinator.update(machine.updateMenu(0.9), 30)
        coordinator.update(machine.updateRecovery(0.9, unchanged, 40), 40)
        val recovered = machine.updateRecovery(0.9, unchanged, 50)

        val retry = coordinator.update(recovered, 50)

        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_2), retry.newControlAction)
        assertTrue(retry.busy)
    }

    @Test fun `scheduled role already on still waits for ub and clears set`() {
        val machine = BattleControlStateMachine()
        val coordinator = VerifiedActionCoordinator(machine)
        val role2On = observation(role2 = VisualToggleState.ON)
        machine.update(role2On, 0)
        coordinator.enqueue(
            listOf(event(
                AxisAction(ActionType.CLICK_ROLE, role = "角色2"),
                AxisAction(ActionType.NOTIFY, message = "after")
            ))
        )

        val roleSet = coordinator.update(machine.snapshot(), 10)
        assertEquals(ControlAction.None, roleSet.newControlAction)
        assertEquals("WAITING_ROLE_UB", roleSet.phase)

        val clearing = coordinator.update(
            roleSet.controlStep,
            20,
            triggeredRoles = setOf(CharacterRole.ROLE_2)
        )

        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_2), clearing.newControlAction)
        assertTrue(clearing.busy)
    }

    @Test fun `pause-frame role skips set-on click and closes after ub`() {
        val machine = BattleControlStateMachine()
        val coordinator = VerifiedActionCoordinator(machine)
        machine.update(observation(), 0)
        coordinator.enqueue(
            listOf(event(AxisAction(ActionType.CLICK_ROLE, role = "角色2"))),
            rolesAlreadySet = setOf(CharacterRole.ROLE_2)
        )

        val waiting = coordinator.update(machine.snapshot(), 10, clockSeconds = 60)
        assertEquals(ControlAction.None, waiting.newControlAction)
        assertEquals("WAITING_ROLE_UB", waiting.phase)

        val cleanup = coordinator.update(
            machine.snapshot(),
            20,
            triggeredRoles = setOf(CharacterRole.ROLE_2),
            clockSeconds = 60
        )

        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_2), cleanup.newControlAction)
        assertEquals("CONFIRMING_ROLE_OFF", cleanup.phase)
    }

    @Test fun `pause-frame fallback waits until preset set is visually on`() {
        val machine = BattleControlStateMachine()
        val coordinator = VerifiedActionCoordinator(machine, roleSetFallbackGraceMs = 0)
        machine.update(observation(), 0)
        coordinator.enqueue(
            listOf(event(AxisAction(ActionType.CLICK_ROLE, role = "角色1"))),
            rolesAlreadySet = setOf(CharacterRole.ROLE_1)
        )

        val firstMenuTransitionFrame = coordinator.update(
            machine.snapshot(),
            10,
            clockSeconds = 59,
            tpBelowThresholdRoles = setOf(CharacterRole.ROLE_1)
        )
        coordinator.update(
            machine.snapshot(),
            11,
            clockSeconds = 59,
            tpBelowThresholdRoles = setOf(CharacterRole.ROLE_1)
        )
        val stillWaiting = coordinator.update(
            machine.snapshot(),
            12,
            clockSeconds = 59,
            tpBelowThresholdRoles = setOf(CharacterRole.ROLE_1)
        )

        assertEquals(ControlAction.None, firstMenuTransitionFrame.newControlAction)
        assertEquals(ControlAction.None, stillWaiting.newControlAction)
        assertEquals("WAITING_ROLE_UB", stillWaiting.phase)

        val role1On = observation().copy(
            roles = CharacterRole.entries.associateWith { role ->
                ToggleObservation(
                    if (role == CharacterRole.ROLE_1) VisualToggleState.ON else VisualToggleState.OFF,
                    0.9
                )
            }
        )
        val cleanup = coordinator.update(
            machine.update(role1On, 13),
            13,
            clockSeconds = 59,
            tpBelowThresholdRoles = setOf(CharacterRole.ROLE_1)
        )

        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_1), cleanup.newControlAction)
        assertEquals("CONFIRMING_ROLE_OFF", cleanup.phase)
    }

    @Test fun `written time passing clears a stale role set when tp trigger is missed`() {
        val machine = BattleControlStateMachine()
        val coordinator = VerifiedActionCoordinator(machine)
        val role2On = observation(role2 = VisualToggleState.ON)
        machine.update(role2On, 0)
        coordinator.enqueue(listOf(event(AxisAction(ActionType.CLICK_ROLE, role = "角色2"))))

        val waiting = coordinator.update(machine.snapshot(), 10, clockSeconds = 60)
        assertEquals("WAITING_ROLE_UB", waiting.phase)

        val clearing = coordinator.update(
            machine.snapshot(),
            20,
            triggeredRoles = emptySet(),
            clockSeconds = 59,
            tpBelowThresholdRoles = setOf(CharacterRole.ROLE_2)
        )
        coordinator.update(machine.snapshot(), 21, clockSeconds = 59, tpBelowThresholdRoles = setOf(CharacterRole.ROLE_2))

        val confirmed = coordinator.update(
            machine.snapshot(),
            22,
            clockSeconds = 59,
            tpBelowThresholdRoles = setOf(CharacterRole.ROLE_2)
        )
        assertEquals(ControlAction.None, clearing.newControlAction)
        assertTrue(clearing.busy)
        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_2), confirmed.newControlAction)
        assertEquals("CONFIRMING_ROLE_OFF", confirmed.phase)
    }

    @Test fun `tp drop takes precedence over configured fallback grace`() {
        val machine = BattleControlStateMachine()
        val coordinator = VerifiedActionCoordinator(machine, roleSetFallbackGraceMs = 500)
        val role2On = observation(role2 = VisualToggleState.ON)
        machine.update(role2On, 0)
        coordinator.enqueue(listOf(event(AxisAction(ActionType.CLICK_ROLE, role = "角色2"))))
        coordinator.update(machine.snapshot(), 10, clockSeconds = 60)

        val started = coordinator.update(
            machine.snapshot(),
            20,
            clockSeconds = 59,
            tpBelowThresholdRoles = setOf(CharacterRole.ROLE_2)
        )
        coordinator.update(machine.snapshot(), 30, clockSeconds = 59, tpBelowThresholdRoles = setOf(CharacterRole.ROLE_2))
        val beforeDeadline = coordinator.update(
            machine.snapshot(),
            519,
            clockSeconds = 59,
            tpBelowThresholdRoles = setOf(CharacterRole.ROLE_2)
        )
        val atDeadline = coordinator.update(
            machine.snapshot(),
            520,
            clockSeconds = 59,
            tpBelowThresholdRoles = setOf(CharacterRole.ROLE_2)
        )

        assertEquals(ControlAction.None, started.newControlAction)
        assertTrue(started.busy)
        assertEquals(ControlAction.None, beforeDeadline.newControlAction)
        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_2), atDeadline.newControlAction)
        assertEquals("CONFIRMING_ROLE_OFF", atDeadline.phase)
    }

    @Test fun `fallback never closes set while target tp remains full`() {
        val machine = BattleControlStateMachine()
        val coordinator = VerifiedActionCoordinator(machine, roleSetFallbackGraceMs = 150)
        val role2On = observation(role2 = VisualToggleState.ON)
        machine.update(role2On, 0)
        coordinator.enqueue(listOf(event(AxisAction(ActionType.CLICK_ROLE, role = "角色2"))))
        coordinator.update(machine.snapshot(), 10, clockSeconds = 60)

        coordinator.update(machine.snapshot(), 20, clockSeconds = 59)
        val stillWaiting = coordinator.update(
            machine.snapshot(),
            1_000,
            triggeredRoles = setOf(CharacterRole.ROLE_4),
            clockSeconds = 59
        )

        assertEquals(ControlAction.None, stillWaiting.newControlAction)
        assertEquals("WAITING_ROLE_UB", stillWaiting.phase)
        assertTrue(stillWaiting.busy)
    }

    @Test fun `recent ub observed before pause lifecycle closes role set immediately`() {
        val machine = BattleControlStateMachine()
        val coordinator = VerifiedActionCoordinator(machine, roleSetFallbackGraceMs = 150)
        val role1On = observation(role2 = VisualToggleState.OFF, role3 = VisualToggleState.OFF)
            .copy(roles = CharacterRole.entries.associateWith { role ->
                ToggleObservation(if (role == CharacterRole.ROLE_1) VisualToggleState.ON else VisualToggleState.OFF, 0.9)
            })
        machine.update(role1On, 0)
        coordinator.observeFrame(
            triggeredRoles = setOf(CharacterRole.ROLE_1),
            clockSeconds = 69,
            nowMs = 5
        )
        coordinator.enqueue(
            listOf(event(AxisAction(ActionType.CLICK_ROLE, role = "角色1"))),
            rolesAlreadySet = setOf(CharacterRole.ROLE_1),
            nowMs = 10
        )

        val cleanup = coordinator.update(
            machine.snapshot(),
            20,
            clockSeconds = 69
        )

        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_1), cleanup.newControlAction)
        assertEquals("CONFIRMING_ROLE_OFF", cleanup.phase)
    }

    @Test fun `late set click is not timed out before set-on is visually confirmed`() {
        val machine = BattleControlStateMachine()
        val coordinator = VerifiedActionCoordinator(machine, roleSetFallbackGraceMs = 150)
        machine.update(observation(), 0)
        coordinator.enqueue(listOf(event(AxisAction(ActionType.CLICK_ROLE, role = "角色2"))))

        val click = coordinator.update(machine.snapshot(), 10, clockSeconds = 59)
        val stillConfirming = coordinator.update(machine.snapshot(), 1_000, clockSeconds = 59)

        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_2), click.newControlAction)
        assertEquals(ControlAction.None, stillConfirming.newControlAction)
        assertEquals("CONFIRMING_ROLE_ON", stillConfirming.phase)
        assertTrue(stillConfirming.busy)
    }

    @Test fun `role ub observed on a clock failure frame is consumed by the next update`() {
        val machine = BattleControlStateMachine()
        val coordinator = VerifiedActionCoordinator(machine)
        machine.update(observation(), 0)
        coordinator.enqueue(listOf(event(AxisAction(ActionType.CLICK_ROLE, role = "角色2"))))
        coordinator.update(machine.snapshot(), 10, clockSeconds = 60)
        val role2On = observation(role2 = VisualToggleState.ON)
        coordinator.update(machine.update(role2On, 20), 20, clockSeconds = 60)
        coordinator.update(machine.update(role2On, 30), 30, clockSeconds = 60)

        coordinator.observeFrame(
            triggeredRoles = setOf(CharacterRole.ROLE_2),
            clockSeconds = null,
            nowMs = 40
        )
        val cleanup = coordinator.update(
            machine.snapshot(),
            50,
            triggeredRoles = emptySet(),
            clockSeconds = 60
        )

        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_2), cleanup.newControlAction)
        assertEquals("CONFIRMING_ROLE_OFF", cleanup.phase)
    }

    @Test fun `queued overdue role starts a watchdog only when its lifecycle begins`() {
        val machine = BattleControlStateMachine()
        val coordinator = VerifiedActionCoordinator(machine, roleSetFallbackGraceMs = 500)
        val bothOn = observation(
            role2 = VisualToggleState.ON,
            role3 = VisualToggleState.ON
        )
        machine.update(bothOn, 0)
        coordinator.enqueue(
            listOf(event(
                AxisAction(ActionType.CLICK_ROLE, role = "角色2"),
                AxisAction(ActionType.CLICK_ROLE, role = "角色3")
            ))
        )
        coordinator.update(machine.snapshot(), 10, clockSeconds = 60)
        val role2Cleanup = coordinator.update(
            machine.snapshot(),
            20,
            triggeredRoles = setOf(CharacterRole.ROLE_2),
            clockSeconds = 59,
            tpBelowThresholdRoles = emptySet()
        )

        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_2), role2Cleanup.newControlAction)

        val role2Off = observation(
            role2 = VisualToggleState.OFF,
            role3 = VisualToggleState.ON
        )
        coordinator.update(machine.update(role2Off, 30), 30, clockSeconds = 59)
        coordinator.update(machine.update(role2Off, 40), 40, clockSeconds = 59)

        val role3Waiting = coordinator.update(machine.snapshot(), 50, clockSeconds = 59)
        coordinator.update(
            machine.snapshot(),
            698,
            clockSeconds = 59,
            tpBelowThresholdRoles = setOf(CharacterRole.ROLE_3)
        )
        val beforeRole3Deadline = coordinator.update(
            machine.snapshot(),
            699,
            clockSeconds = 59,
            tpBelowThresholdRoles = setOf(CharacterRole.ROLE_3)
        )
        val role3Cleanup = coordinator.update(
            machine.snapshot(),
            700,
            clockSeconds = 59,
            tpBelowThresholdRoles = setOf(CharacterRole.ROLE_3)
        )

        assertEquals(ControlAction.None, role3Waiting.newControlAction)
        assertEquals("WAITING_ROLE_UB", role3Waiting.phase)
        assertEquals(ControlAction.None, beforeRole3Deadline.newControlAction)
        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_3), role3Cleanup.newControlAction)
        assertEquals("CONFIRMING_ROLE_OFF", role3Cleanup.phase)
    }

    @Test fun `tp and watchdog race requests role off only once`() {
        val machine = BattleControlStateMachine()
        val coordinator = VerifiedActionCoordinator(machine)
        val role2On = observation(role2 = VisualToggleState.ON)
        machine.update(role2On, 0)
        coordinator.enqueue(listOf(event(AxisAction(ActionType.CLICK_ROLE, role = "角色2"))))
        coordinator.update(machine.snapshot(), 10, clockSeconds = 60)

        val cleanup = coordinator.update(
            machine.snapshot(),
            20,
            triggeredRoles = setOf(CharacterRole.ROLE_2),
            clockSeconds = 59,
            tpBelowThresholdRoles = setOf(CharacterRole.ROLE_2)
        )
        val confirming = coordinator.update(
            machine.snapshot(),
            21,
            triggeredRoles = setOf(CharacterRole.ROLE_2),
            clockSeconds = 59
        )

        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_2), cleanup.newControlAction)
        assertEquals(ControlAction.None, confirming.newControlAction)
        assertEquals("CONFIRMING_ROLE_OFF", confirming.phase)
    }

    @Test fun `ub during set-on confirmation immediately starts cleanup without safety pause`() {
        val machine = BattleControlStateMachine()
        val coordinator = VerifiedActionCoordinator(machine)
        machine.update(observation(), 0)
        coordinator.enqueue(listOf(event(AxisAction(ActionType.CLICK_ROLE, role = "角色2"))))
        coordinator.update(machine.snapshot(), 10, clockSeconds = 60)

        val cleanup = coordinator.update(
            machine.snapshot(),
            1_200,
            triggeredRoles = setOf(CharacterRole.ROLE_2),
            clockSeconds = 60
        )

        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_2), cleanup.newControlAction)
        assertEquals("CONFIRMING_ROLE_OFF", cleanup.phase)
        assertEquals(ControlSafetyState.RUNNING, cleanup.controlStep.safety)
    }

    @Test fun `time passing does not skip an unconfirmed set-on click`() {
        val machine = BattleControlStateMachine()
        val coordinator = VerifiedActionCoordinator(machine)
        machine.update(observation(), 0)
        coordinator.enqueue(listOf(event(AxisAction(ActionType.CLICK_ROLE, role = "角色2"))))
        coordinator.update(machine.snapshot(), 10, clockSeconds = 60)

        val stillConfirming = coordinator.update(
            machine.snapshot(),
            1_200,
            triggeredRoles = emptySet(),
            clockSeconds = 59
        )

        assertEquals(ControlAction.None, stillConfirming.newControlAction)
        assertTrue(stillConfirming.busy)
        assertEquals("CONFIRMING_ROLE_ON", stillConfirming.phase)
        assertEquals(ControlSafetyState.RUNNING, stillConfirming.controlStep.safety)
    }

    @Test fun `time fallback turns off a visually enabled role after a missed tp trigger`() {
        val machine = BattleControlStateMachine()
        val coordinator = VerifiedActionCoordinator(machine)
        machine.update(observation(), 0)
        coordinator.enqueue(listOf(event(AxisAction(ActionType.CLICK_ROLE, role = "角色2"))))
        coordinator.update(machine.snapshot(), 10, clockSeconds = 60)
        val role2On = observation(role2 = VisualToggleState.ON)
        val firstVisible = machine.update(role2On, 1_200)
        val confirming = coordinator.update(
            firstVisible,
            1_200,
            clockSeconds = 59,
            tpBelowThresholdRoles = setOf(CharacterRole.ROLE_2)
        )
        assertEquals(ControlAction.None, confirming.newControlAction)
        val confirmed = machine.update(role2On, 1_210)
        coordinator.update(
            confirmed,
            1_210,
            clockSeconds = 59,
            tpBelowThresholdRoles = setOf(CharacterRole.ROLE_2)
        )
        val cleanup = coordinator.update(
            confirmed,
            1_220,
            clockSeconds = 59,
            tpBelowThresholdRoles = setOf(CharacterRole.ROLE_2)
        )
        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_2), cleanup.newControlAction)
        assertEquals("CONFIRMING_ROLE_OFF", cleanup.phase)
        assertEquals(ControlSafetyState.RUNNING, confirming.controlStep.safety)
    }

    @Test fun `transient low tp does not end a role lifecycle before a real ub`() {
        val machine = BattleControlStateMachine()
        val coordinator = VerifiedActionCoordinator(machine, roleSetFallbackGraceMs = 150)
        machine.update(observation(), 0)
        coordinator.enqueue(listOf(event(AxisAction(ActionType.CLICK_ROLE, role = "角色2"))))
        coordinator.update(machine.snapshot(), 10, clockSeconds = 60)

        val role2On = observation(role2 = VisualToggleState.ON)
        coordinator.update(machine.update(role2On, 20), 20, clockSeconds = 60)
        coordinator.update(machine.update(role2On, 30), 30, clockSeconds = 60)

        val low = observation(role2 = VisualToggleState.OFF)
        val firstLow = coordinator.update(
            machine.update(low, 200),
            200,
            clockSeconds = 59,
            tpBelowThresholdRoles = setOf(CharacterRole.ROLE_2)
        )
        val secondLow = coordinator.update(
            machine.snapshot(),
            201,
            clockSeconds = 59,
            tpBelowThresholdRoles = setOf(CharacterRole.ROLE_2)
        )
        val fullAgain = coordinator.update(
            machine.update(role2On, 202),
            202,
            clockSeconds = 59
        )

        assertTrue(firstLow.busy)
        assertTrue(secondLow.busy)
        assertEquals(ControlAction.None, fullAgain.newControlAction)
        assertEquals("WAITING_ROLE_UB", fullAgain.phase)

        val cleanup = coordinator.update(
            machine.snapshot(),
            203,
            clockSeconds = 59,
            triggeredRoles = setOf(CharacterRole.ROLE_2)
        )
        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_2), cleanup.newControlAction)
        assertEquals("CONFIRMING_ROLE_OFF", cleanup.phase)
    }

    @Test fun `fallback forces off tap when animation hides an enabled set badge`() {
        val machine = BattleControlStateMachine()
        val coordinator = VerifiedActionCoordinator(machine, roleSetFallbackGraceMs = 0)
        machine.update(observation(), 0)
        coordinator.enqueue(listOf(event(AxisAction(ActionType.CLICK_ROLE, role = "角色2"))))
        coordinator.update(machine.snapshot(), 10, clockSeconds = 60)

        val role2On = observation(role2 = VisualToggleState.ON)
        coordinator.update(machine.update(role2On, 20), 20, clockSeconds = 60)
        coordinator.update(machine.update(role2On, 30), 30, clockSeconds = 60)

        val obscuredOff = observation(role2 = VisualToggleState.OFF)
        coordinator.update(
            machine.update(obscuredOff, 40),
            40,
            clockSeconds = 59,
            tpBelowThresholdRoles = setOf(CharacterRole.ROLE_2)
        )
        coordinator.update(
            machine.snapshot(),
            41,
            clockSeconds = 59,
            tpBelowThresholdRoles = setOf(CharacterRole.ROLE_2)
        )
        val cleanup = coordinator.update(
            machine.snapshot(),
            42,
            clockSeconds = 59,
            tpBelowThresholdRoles = setOf(CharacterRole.ROLE_2)
        )

        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_2), cleanup.newControlAction)
        assertEquals("CONFIRMING_ROLE_OFF", cleanup.phase)
    }

    @Test fun `same time role actions stay serialized in source order`() {
        val machine = BattleControlStateMachine()
        val coordinator = VerifiedActionCoordinator(machine)
        machine.update(observation(), 0)
        coordinator.enqueue(
            listOf(event(
                AxisAction(ActionType.CLICK_ROLE, role = "角色2"),
                AxisAction(ActionType.CLICK_ROLE, role = "角色3")
            ))
        )

        assertEquals(
            ControlAction.TapRole(CharacterRole.ROLE_2),
            coordinator.update(machine.snapshot(), 10).newControlAction
        )
        val role2On = observation(role2 = VisualToggleState.ON)
        coordinator.update(machine.update(role2On, 20), 20)
        coordinator.update(machine.update(role2On, 30), 30)
        assertEquals(
            ControlAction.TapRole(CharacterRole.ROLE_2),
            coordinator.update(
                machine.snapshot(),
                40,
                triggeredRoles = setOf(CharacterRole.ROLE_2)
            ).newControlAction
        )
        coordinator.update(machine.update(observation(), 50), 50)
        coordinator.update(machine.update(observation(), 60), 60)

        val role3 = coordinator.update(machine.snapshot(), 70)

        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_3), role3.newControlAction)
    }

    @Test fun `configured sequence alias resolves to its role`() {
        val machine = BattleControlStateMachine()
        val coordinator = VerifiedActionCoordinator(machine)
        coordinator.configureRoleAliases(mapOf("角色3" to "原晶"))
        machine.update(observation(), 0)
        coordinator.enqueue(listOf(event(AxisAction(ActionType.CLICK_ROLE, role = "原晶"))))

        val step = coordinator.update(machine.snapshot(), 10)

        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_3), step.newControlAction)
    }

    private fun event(vararg actions: AxisAction) = AxisEvent("event", 1, 60, actions.toList())

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
