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
    @Test fun `role click blocks following action until two visual confirmations`() {
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
        val confirmed = coordinator.update(machine.update(changed, 30), 30)
        assertTrue(confirmed.busy)
        assertTrue(confirmed.immediateEvents.isEmpty())

        val after = coordinator.update(machine.snapshot(), 40)
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

    @Test fun `scheduled role set already on releases the following action without a tap`() {
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
        val completed = coordinator.update(roleSet.controlStep, 20)
        val after = coordinator.update(completed.controlStep, 30)

        assertEquals(ControlAction.None, roleSet.newControlAction)
        assertEquals(ActionType.NOTIFY, after.immediateEvents.single().actions.single().type)
        assertEquals(false, after.busy)
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
