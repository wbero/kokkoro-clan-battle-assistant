package com.kokkoro.clanbattle.sequenceaxis

import com.kokkoro.clanbattle.axis.ActionType
import com.kokkoro.clanbattle.axis.AxisAction
import com.kokkoro.clanbattle.axis.AxisEvent
import com.kokkoro.clanbattle.axis.BossDelayTrigger
import com.kokkoro.clanbattle.axis.CharacterUbTrigger
import com.kokkoro.clanbattle.axis.PauseFrameTrigger
import com.kokkoro.clanbattle.axis.TimedTrigger
import com.kokkoro.clanbattle.recognition.CharacterRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SequenceAxisRuntimeTest {
    @Test fun `character ub trigger waits for a new matching ub after arming`() {
        val event = event("role3", 60, CharacterUbTrigger(CharacterRole.ROLE_3, "角色3"))
        val runtime = SequenceAxisRuntime(listOf(event))

        assertEquals(
            SequenceRuntimeCommand.None,
            runtime.update(frame(60, triggered = setOf(CharacterRole.ROLE_3)))
        )
        assertEquals(
            SequenceRuntimeCommand.None,
            runtime.update(frame(60, triggered = setOf(CharacterRole.ROLE_2)))
        )
        val command = runtime.update(frame(59, triggered = setOf(CharacterRole.ROLE_3)))

        assertEquals(event, (command as SequenceRuntimeCommand.Dispatch).event)
    }

    @Test fun `pause frame starts a preset role lifecycle after confirmation`() {
        val pause = event("pause", 60, PauseFrameTrigger(CharacterRole.ROLE_4, "角色4"), actions = emptyList())
        val later = event("later", 59, TimedTrigger)
        val runtime = SequenceAxisRuntime(listOf(pause, later))

        val enter = runtime.update(frame(60)) as SequenceRuntimeCommand.EnterPauseFrame
        assertEquals(CharacterRole.ROLE_4, enter.role)
        assertEquals(SequenceRuntimeCommand.None, runtime.update(frame(59)))

        runtime.confirmPauseFrame("pause")
        val lifecycle = runtime.update(frame(59)) as SequenceRuntimeCommand.Dispatch
        assertEquals(roleClick("角色4"), lifecycle.event.actions)
        assertEquals(setOf(CharacterRole.ROLE_4), lifecycle.rolesAlreadySet)
        assertEquals(later, (runtime.update(frame(59)) as SequenceRuntimeCommand.Dispatch).event)
    }

    @Test fun `pause frame dispatches remaining actions after confirmation`() {
        val pause = event("pause", 60, PauseFrameTrigger(CharacterRole.ROLE_4, "角色4"))
        val runtime = SequenceAxisRuntime(listOf(pause))
        runtime.update(frame(60))

        runtime.confirmPauseFrame("pause")

        val command = runtime.update(frame(60)) as SequenceRuntimeCommand.Dispatch
        assertEquals(
            roleClick("角色4") + pause.actions,
            command.event.actions
        )
        assertEquals(setOf(CharacterRole.ROLE_4), command.rolesAlreadySet)
    }

    @Test fun `pause frame deduplicates an explicit click of the same role`() {
        val pause = event(
            "pause",
            60,
            PauseFrameTrigger(CharacterRole.ROLE_4, "角色4"),
            actions = roleClick("角色4") + roleClick("角色2")
        )
        val runtime = SequenceAxisRuntime(listOf(pause))
        runtime.update(frame(60))
        runtime.confirmPauseFrame("pause")

        val command = runtime.update(frame(60)) as SequenceRuntimeCommand.Dispatch

        assertEquals(roleClick("角色4") + roleClick("角色2"), command.event.actions)
    }

    @Test fun `clock skip preserves source order`() {
        val first = event("first", 60, TimedTrigger)
        val second = event("second", 59, TimedTrigger)
        val runtime = SequenceAxisRuntime(listOf(first, second))

        assertEquals(first, (runtime.update(frame(58)) as SequenceRuntimeCommand.Dispatch).event)
        assertEquals(second, (runtime.update(frame(58)) as SequenceRuntimeCommand.Dispatch).event)
    }

    @Test fun `boss delay does not dispatch before deadline`() {
        val boss = event("boss", 30, BossDelayTrigger(1_200, "1.20"))
        val runtime = SequenceAxisRuntime(listOf(boss))
        runtime.update(frame(30, wallMs = 10_000))

        assertEquals(SequenceRuntimeCommand.None, runtime.update(frame(29, wallMs = 11_199)))
        assertTrue(runtime.update(frame(29, wallMs = 11_200)) is SequenceRuntimeCommand.Dispatch)
    }

    @Test fun `plain role click chains early after the previous role`() {
        val role3 = event("role3", 76, TimedTrigger, actions = roleClick("角色3"))
        val role2 = event("role2", 69, TimedTrigger, actions = roleClick("角色2"))
        val runtime = SequenceAxisRuntime(listOf(role3, role2))

        assertEquals(role3, (runtime.update(frame(76)) as SequenceRuntimeCommand.Dispatch).event)
        // clock 74 is still above role2's 69, so without chaining it would not be due yet.
        assertEquals(role2, (runtime.update(frame(74)) as SequenceRuntimeCommand.Dispatch).event)
    }

    @Test fun `first role in a chain still waits for its written time`() {
        val role3 = event("role3", 76, TimedTrigger, actions = roleClick("角色3"))
        val runtime = SequenceAxisRuntime(listOf(role3))

        assertEquals(SequenceRuntimeCommand.None, runtime.update(frame(80)))
        assertEquals(role3, (runtime.update(frame(76)) as SequenceRuntimeCommand.Dispatch).event)
    }

    @Test fun `a non-role timed line breaks the chain`() {
        val role3 = event("role3", 76, TimedTrigger, actions = roleClick("角色3"))
        val auto = event("auto", 70, TimedTrigger, actions = listOf(AxisAction(ActionType.CLICK_AUTO)))
        val role2 = event("role2", 69, TimedTrigger, actions = roleClick("角色2"))
        val runtime = SequenceAxisRuntime(listOf(role3, auto, role2))

        assertEquals(role3, (runtime.update(frame(76)) as SequenceRuntimeCommand.Dispatch).event)
        // Head of the queue is the AUTO line, so role2 must not jump ahead of it.
        assertEquals(SequenceRuntimeCommand.None, runtime.update(frame(74)))
        assertEquals(auto, (runtime.update(frame(70)) as SequenceRuntimeCommand.Dispatch).event)
        // AUTO reset the chain, so role2 waits for its own written time again.
        assertEquals(SequenceRuntimeCommand.None, runtime.update(frame(70)))
        assertEquals(role2, (runtime.update(frame(69)) as SequenceRuntimeCommand.Dispatch).event)
    }

    @Test fun `a character ub trigger does not get pulled in early by chaining`() {
        val role3 = event("role3", 76, TimedTrigger, actions = roleClick("角色3"))
        val ubGated = event("ub", 70, CharacterUbTrigger(CharacterRole.ROLE_5, "角色5"), actions = roleClick("角色2"))
        val runtime = SequenceAxisRuntime(listOf(role3, ubGated))

        assertEquals(role3, (runtime.update(frame(76)) as SequenceRuntimeCommand.Dispatch).event)
        // Special trigger keeps its gating even though the previous dispatch was a role.
        assertEquals(SequenceRuntimeCommand.None, runtime.update(frame(74)))
        assertEquals(SequenceRuntimeCommand.None, runtime.update(frame(70)))
        assertEquals(
            ubGated,
            (runtime.update(frame(69, triggered = setOf(CharacterRole.ROLE_5))) as SequenceRuntimeCommand.Dispatch).event
        )
    }

    private fun roleClick(role: String) = listOf(AxisAction(ActionType.CLICK_ROLE, role = role))

    private fun event(
        id: String,
        time: Int,
        trigger: com.kokkoro.clanbattle.axis.SwitchNodeTrigger,
        actions: List<AxisAction> = listOf(AxisAction(ActionType.CLICK_AUTO))
    ) = AxisEvent(id, 1, time, actions, trigger)

    private fun frame(
        clock: Int,
        wallMs: Long = 0,
        triggered: Set<CharacterRole> = emptySet(),
        trustworthy: Boolean = true,
        schedulingAllowed: Boolean = true
    ) = SequenceFrameInput(clock, triggered, trustworthy, wallMs, schedulingAllowed)
}
