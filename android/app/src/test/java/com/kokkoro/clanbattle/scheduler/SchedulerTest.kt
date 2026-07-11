package com.kokkoro.clanbattle.scheduler

import com.kokkoro.clanbattle.axis.ActionType
import com.kokkoro.clanbattle.axis.AxisAction
import com.kokkoro.clanbattle.axis.AxisEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class SchedulerTest {
    @Test
    fun `catches events when recognized clock skips a second`() {
        val scheduler = Scheduler(
            listOf(
                AxisEvent("a", 1, 76, listOf(AxisAction(ActionType.CLICK_ROLE, role = "角色3"))),
                AxisEvent("b", 2, 75, listOf(AxisAction(ActionType.CLICK_AUTO)))
            )
        )

        assertEquals(1, scheduler.update(GameState.RUNNING, 76).events.size)
        val skipped = scheduler.update(GameState.RUNNING, 74)
        assertEquals(1, skipped.events.size)
        assertEquals("b", skipped.events[0].id)
    }

    @Test
    fun `does not repeat same second`() {
        val scheduler = Scheduler(listOf(AxisEvent("a", 1, 60, emptyList())))
        scheduler.update(GameState.RUNNING, 60)
        assertEquals("same-second", scheduler.update(GameState.RUNNING, 60).reason)
    }
}
