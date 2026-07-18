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
            clockSeconds = 59
        )

        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_2), clearing.newControlAction)
        assertEquals("CONFIRMING_ROLE_OFF", clearing.phase)
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

    @Test fun `time passing during set-on confirmation cleans up a missed tp trigger`() {
        val machine = BattleControlStateMachine()
        val coordinator = VerifiedActionCoordinator(machine)
        machine.update(observation(), 0)
        coordinator.enqueue(listOf(event(AxisAction(ActionType.CLICK_ROLE, role = "角色2"))))
        coordinator.update(machine.snapshot(), 10, clockSeconds = 60)

        val cleanup = coordinator.update(
            machine.snapshot(),
            1_200,
            triggeredRoles = emptySet(),
            clockSeconds = 59
        )

        assertEquals(ControlAction.None, cleanup.newControlAction)
        assertFalse(cleanup.busy)
        assertEquals(ControlSafetyState.RUNNING, cleanup.controlStep.safety)
    }

    @Test fun `time fallback turns off a visually enabled role after a missed tp trigger`() {
        val machine = BattleControlStateMachine()
        val coordinator = VerifiedActionCoordinator(machine)
        machine.update(observation(), 0)
        coordinator.enqueue(listOf(event(AxisAction(ActionType.CLICK_ROLE, role = "角色2"))))
        coordinator.update(machine.snapshot(), 10, clockSeconds = 60)
        val role2On = observation(role2 = VisualToggleState.ON)
        val latest = machine.update(role2On, 1_200)

        val cleanup = coordinator.update(
            latest,
            1_200,
            triggeredRoles = emptySet(),
            clockSeconds = 59
        )

        assertEquals(ControlAction.TapRole(CharacterRole.ROLE_2), cleanup.newControlAction)
        assertEquals("CONFIRMING_ROLE_OFF", cleanup.phase)
        assertEquals(ControlSafetyState.RUNNING, cleanup.controlStep.safety)
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
        role2: VisualToggleState = VisualToggleState.OFF
    ) = BattleControlObservation(
        auto = ToggleObservation(auto, 0.9),
        globalSet = ToggleObservation(VisualToggleState.OFF, 0.9),
        roles = CharacterRole.entries.associateWith { role ->
            ToggleObservation(if (role == CharacterRole.ROLE_2) role2 else VisualToggleState.OFF, 0.9)
        },
        consistent = true
    )
}
