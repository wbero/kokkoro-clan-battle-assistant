package com.kokkoro.clanbattle.switchaxis

import com.kokkoro.clanbattle.axis.AxisToggleState
import com.kokkoro.clanbattle.axis.BossDelayTrigger
import com.kokkoro.clanbattle.axis.CharacterUbTrigger
import com.kokkoro.clanbattle.axis.PauseFrameTrigger
import com.kokkoro.clanbattle.axis.SwitchAxisNode
import com.kokkoro.clanbattle.axis.SwitchAxisOpening
import com.kokkoro.clanbattle.axis.SwitchControlTarget
import com.kokkoro.clanbattle.axis.TimedTrigger
import com.kokkoro.clanbattle.recognition.CharacterRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchAxisRuntimeTest {
    @Test fun `opening emits once from ninety through eighty eight`() {
        val runtime = runtime()

        val opening = runtime.update(frame(clock = 89)) as SwitchRuntimeCommand.Converge
        assertEquals("opening-1", opening.nodeId)
        runtime.confirmConvergence(opening.nodeId)

        assertEquals(SwitchRuntimeCommand.None, runtime.update(frame(clock = 89)))
    }

    @Test fun `clock skip queues crossed nodes in source order`() {
        val first = node("first", 72, TimedTrigger)
        val second = node("second", 71, TimedTrigger)
        val runtime = runtime(first, second).openedAt(90)

        val firstCommand = runtime.update(frame(clock = 70)) as SwitchRuntimeCommand.Converge
        assertEquals("first", firstCommand.nodeId)
        runtime.confirmConvergence("first")
        val secondCommand = runtime.update(frame(clock = 70)) as SwitchRuntimeCommand.Converge
        assertEquals("second", secondCommand.nodeId)
    }

    @Test fun `character ub before arming cannot satisfy node`() {
        val runtime = runtime(node("role4", 57, CharacterUbTrigger(CharacterRole.ROLE_4, "角色4"))).openedAt(90)

        runtime.update(frame(clock = 58, triggered = setOf(CharacterRole.ROLE_4)))
        assertEquals(SwitchRuntimeCommand.None, runtime.update(frame(clock = 57)))
        assertTrue(
            runtime.update(frame(clock = 57, triggered = setOf(CharacterRole.ROLE_4)))
                is SwitchRuntimeCommand.Converge
        )
    }

    @Test fun `character ub on arming frame cannot satisfy node`() {
        val runtime = runtime(node("role4", 57, CharacterUbTrigger(CharacterRole.ROLE_4, "角色4"))).openedAt(90)

        assertEquals(
            SwitchRuntimeCommand.None,
            runtime.update(frame(clock = 57, triggered = setOf(CharacterRole.ROLE_4)))
        )
        assertTrue(
            runtime.update(frame(clock = 57, triggered = setOf(CharacterRole.ROLE_4)))
                is SwitchRuntimeCommand.Converge
        )
    }

    @Test fun `wrong role cannot satisfy character ub node`() {
        val runtime = runtime(node("role4", 57, CharacterUbTrigger(CharacterRole.ROLE_4, "角色4"))).openedAt(90)
        runtime.update(frame(clock = 57))

        assertEquals(
            SwitchRuntimeCommand.None,
            runtime.update(frame(clock = 57, triggered = setOf(CharacterRole.ROLE_2)))
        )
    }

    @Test fun `boss node never emits before minimum wall delay`() {
        val runtime = runtime(node("boss", 26, BossDelayTrigger(1_200, "1.20"))).openedAt(90)
        runtime.update(frame(clock = 26, wallMs = 10_000))

        assertEquals(SwitchRuntimeCommand.None, runtime.update(frame(clock = 25, wallMs = 11_199)))
        assertTrue(runtime.update(frame(clock = 25, wallMs = 11_200)) is SwitchRuntimeCommand.Converge)
    }

    @Test fun `boss deadline waits for trustworthy controls`() {
        val runtime = runtime(node("boss", 26, BossDelayTrigger(1_200, "1.20"))).openedAt(90)
        runtime.update(frame(clock = 26, wallMs = 10_000))

        assertEquals(
            SwitchRuntimeCommand.None,
            runtime.update(frame(clock = 25, wallMs = 11_200, trustworthy = false))
        )
        assertTrue(
            runtime.update(frame(clock = 25, wallMs = 11_300, trustworthy = true))
                is SwitchRuntimeCommand.Converge
        )
    }

    @Test fun `pause frame blocks later nodes until manual confirmation and convergence`() {
        val pause = node("pause", 18, PauseFrameTrigger(CharacterRole.ROLE_3, "角色3"))
        val later = node("later", 17, TimedTrigger)
        val runtime = runtime(pause, later).openedAt(90)

        val enter = runtime.update(frame(clock = 18)) as SwitchRuntimeCommand.EnterPauseFrame
        assertEquals(CharacterRole.ROLE_3, enter.role)
        assertEquals(SwitchRuntimeCommand.None, runtime.update(frame(clock = 17)))

        runtime.confirmPauseFrame("pause")
        assertEquals("pause", (runtime.update(frame(clock = 17)) as SwitchRuntimeCommand.Converge).nodeId)
        runtime.confirmConvergence("pause")
        assertEquals("later", (runtime.update(frame(clock = 17)) as SwitchRuntimeCommand.Converge).nodeId)
    }

    private fun runtime(vararg nodes: SwitchAxisNode) = SwitchAxisRuntime(
        opening = SwitchAxisOpening(1, target()),
        nodes = nodes.toList()
    )

    private fun SwitchAxisRuntime.openedAt(clock: Int): SwitchAxisRuntime = apply {
        val command = update(frame(clock = clock)) as SwitchRuntimeCommand.Converge
        confirmConvergence(command.nodeId)
        update(frame(clock = clock))
    }

    private fun node(id: String, time: Int, trigger: com.kokkoro.clanbattle.axis.SwitchNodeTrigger) =
        SwitchAxisNode(id, 2, time, trigger, target())

    private fun target() = SwitchControlTarget(
        auto = AxisToggleState.ON,
        roles = CharacterRole.entries.associateWith { AxisToggleState.OFF },
        rawAuto = "开",
        rawRoles = List(5) { "关" }
    )

    private fun frame(
        clock: Int,
        wallMs: Long = 0,
        triggered: Set<CharacterRole> = emptySet(),
        trustworthy: Boolean = true
    ) = SwitchFrameInput(clock, triggered, trustworthy, wallMs)
}
