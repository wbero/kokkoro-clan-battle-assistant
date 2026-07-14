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

    @Test fun `pause frame blocks later nodes until confirmation`() {
        val pause = event("pause", 60, PauseFrameTrigger(CharacterRole.ROLE_4, "角色4"), actions = emptyList())
        val later = event("later", 59, TimedTrigger)
        val runtime = SequenceAxisRuntime(listOf(pause, later))

        val enter = runtime.update(frame(60)) as SequenceRuntimeCommand.EnterPauseFrame
        assertEquals(CharacterRole.ROLE_4, enter.role)
        assertEquals(SequenceRuntimeCommand.None, runtime.update(frame(59)))

        runtime.confirmPauseFrame("pause")
        assertEquals(SequenceRuntimeCommand.None, runtime.update(frame(59)))
        assertEquals(later, (runtime.update(frame(59)) as SequenceRuntimeCommand.Dispatch).event)
    }

    @Test fun `pause frame dispatches remaining actions after confirmation`() {
        val pause = event("pause", 60, PauseFrameTrigger(CharacterRole.ROLE_4, "角色4"))
        val runtime = SequenceAxisRuntime(listOf(pause))
        runtime.update(frame(60))

        runtime.confirmPauseFrame("pause")

        assertEquals(pause, (runtime.update(frame(60)) as SequenceRuntimeCommand.Dispatch).event)
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
