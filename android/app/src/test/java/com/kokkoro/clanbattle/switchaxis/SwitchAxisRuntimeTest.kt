package com.kokkoro.clanbattle.switchaxis

import com.kokkoro.clanbattle.axis.AxisToggleState
import com.kokkoro.clanbattle.axis.BossDelayTrigger
import com.kokkoro.clanbattle.axis.CharacterUbTrigger
import com.kokkoro.clanbattle.axis.PauseFrameTrigger
import com.kokkoro.clanbattle.axis.PauseFrameTarget
import com.kokkoro.clanbattle.axis.SwitchAxisNode
import com.kokkoro.clanbattle.axis.SwitchAxisOpening
import com.kokkoro.clanbattle.axis.SwitchControlTarget
import com.kokkoro.clanbattle.axis.TimedTrigger
import com.kokkoro.clanbattle.recognition.CharacterRole
import com.kokkoro.clanbattle.scheduler.BossUbEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchAxisRuntimeTest {
    @Test fun `opening waits at ninety then emits from eighty nine`() {
        val runtime = runtime()

        assertEquals(SwitchRuntimeCommand.None, runtime.update(frame(clock = 90)))
        val opening = runtime.update(frame(clock = 89)) as SwitchRuntimeCommand.Converge
        assertEquals("opening-1", opening.nodeId)
        runtime.confirmConvergence(opening.nodeId)

        assertEquals(SwitchRuntimeCommand.None, runtime.update(frame(clock = 89)))
    }

    @Test fun `opening waits past the old window for the first trustworthy controls`() {
        val runtime = runtime(openingGraceSeconds = 5)

        assertEquals(
            SwitchRuntimeCommand.None,
            runtime.update(frame(clock = 90, trustworthy = false))
        )
        assertEquals(
            "opening-1",
            (runtime.update(frame(clock = 86, trustworthy = true)) as SwitchRuntimeCommand.Converge).nodeId
        )
    }

    @Test fun `pause frame auto enters menu then converges after confirmation`() {
        val pause = node("pause-auto", 18, PauseFrameTrigger(null, "AUTO"))
        val runtime = runtime(pause).openedAt(89)

        val enter = runtime.update(frame(clock = 18)) as SwitchRuntimeCommand.EnterPauseFrame
        assertEquals(PauseFrameTarget.Auto, enter.target)

        runtime.confirmPauseFrame("pause-auto")
        val converge = runtime.update(frame(clock = 18)) as SwitchRuntimeCommand.Converge
        assertEquals("pause-auto", converge.nodeId)
    }

    @Test fun `matching character ub advances every role node`() {
        CharacterRole.entries.forEach { role ->
            val nodeId = "${role.name.lowercase()}-node"
            val runtime = runtime(
                node(
                    nodeId,
                    57,
                    CharacterUbTrigger(role, "角色${role.ordinal + 1}")
                )
            ).openedAt(89)

            runtime.update(frame(clock = 57))
            val command = runtime.update(frame(clock = 57, triggered = setOf(role)))

            assertTrue("$role should advance its matching node", command is SwitchRuntimeCommand.Converge)
            assertEquals(nodeId, (command as SwitchRuntimeCommand.Converge).nodeId)
        }
    }

    @Test fun `clock skip queues crossed nodes in source order`() {
        val first = node("first", 72, TimedTrigger)
        val second = node("second", 71, TimedTrigger)
        val runtime = runtime(first, second).openedAt(89)

        val firstCommand = runtime.update(frame(clock = 70)) as SwitchRuntimeCommand.Converge
        assertEquals("first", firstCommand.nodeId)
        runtime.confirmConvergence("first")
        val secondCommand = runtime.update(frame(clock = 70)) as SwitchRuntimeCommand.Converge
        assertEquals("second", secondCommand.nodeId)
    }

    @Test fun `character ub before arming cannot satisfy node`() {
        val runtime = runtime(node("role4", 57, CharacterUbTrigger(CharacterRole.ROLE_4, "角色4"))).openedAt(89)

        runtime.update(frame(clock = 58, triggered = setOf(CharacterRole.ROLE_4)))
        assertEquals(SwitchRuntimeCommand.None, runtime.update(frame(clock = 57)))
        assertTrue(
            runtime.update(frame(clock = 57, triggered = setOf(CharacterRole.ROLE_4)))
                is SwitchRuntimeCommand.Converge
        )
    }

    @Test fun `time tagged character ub on arming frame satisfies its own node`() {
        val runtime = runtime(node("role4", 57, CharacterUbTrigger(CharacterRole.ROLE_4, "角色4"))).openedAt(89)

        assertTrue(
            runtime.update(frame(clock = 57, triggered = setOf(CharacterRole.ROLE_4)))
                is SwitchRuntimeCommand.Converge
        )
    }

    @Test fun `wrong role cannot satisfy character ub node`() {
        val runtime = runtime(node("role4", 57, CharacterUbTrigger(CharacterRole.ROLE_4, "角色4"))).openedAt(89)
        runtime.update(frame(clock = 57))

        assertEquals(
            SwitchRuntimeCommand.None,
            runtime.update(frame(clock = 57, triggered = setOf(CharacterRole.ROLE_2)))
        )
    }

    @Test fun `character ub node cannot consume a later ub from the same role`() {
        val runtime = runtime(node("role3", 63, CharacterUbTrigger(CharacterRole.ROLE_3, "角色3"))).openedAt(89)
        runtime.update(frame(clock = 63))

        val missed = runtime.update(
            frame(clock = 61, triggered = setOf(CharacterRole.ROLE_3))
        ) as SwitchRuntimeCommand.MissedCharacterUb

        assertEquals("role3", missed.nodeId)
        assertEquals(CharacterRole.ROLE_3, missed.role)
        assertEquals(63, missed.expectedClockSeconds)
        assertEquals(61, missed.observedClockSeconds)
    }

    @Test fun `character ub converges immediately inside animation without trustworthy controls`() {
        val runtime = runtime(node("role1", 69, CharacterUbTrigger(CharacterRole.ROLE_1, "角色1"))).openedAt(89)
        runtime.update(frame(clock = 69))

        assertTrue(
            runtime.update(
                frame(
                    clock = 69,
                    triggered = setOf(CharacterRole.ROLE_1),
                    trustworthy = false
                )
            ) is SwitchRuntimeCommand.Converge
        )
    }

    @Test fun `character ub is consumed at its exact second rather than deferred to later visual frame`() {
        val runtime = runtime(node("role1", 69, CharacterUbTrigger(CharacterRole.ROLE_1, "角色1"))).openedAt(89)
        runtime.update(frame(clock = 69))

        val command = runtime.update(
                frame(
                    clock = 69,
                    triggered = setOf(CharacterRole.ROLE_1),
                    trustworthy = false
                )
            ) as SwitchRuntimeCommand.Converge
        assertEquals("role1", command.nodeId)
    }

    @Test fun `manual recognition pause does not fabricate a character ub`() {
        val runtime = runtime(node("role1", 69, CharacterUbTrigger(CharacterRole.ROLE_1, "角色1"))).openedAt(89)
        runtime.update(frame(clock = 69))

        runtime.clearRecognitionEvidence()

        assertEquals(
            SwitchRuntimeCommand.None,
            runtime.update(frame(clock = 69, trustworthy = false))
        )
        assertTrue(
            runtime.update(
                frame(
                    clock = 69,
                    triggered = setOf(CharacterRole.ROLE_1),
                    trustworthy = false
                )
            ) is SwitchRuntimeCommand.Converge
        )
    }

    @Test fun `boss node never emits without a boss ub detection`() {
        val runtime = runtime(node("boss", 26, BossDelayTrigger(1_200, "1.20"))).openedAt(89)
        runtime.update(frame(clock = 26, wallMs = 10_000))

        assertEquals(SwitchRuntimeCommand.None, runtime.update(frame(clock = 25, wallMs = 20_000)))
    }

    @Test fun `boss delay starts at detection and waits for trustworthy controls`() {
        val runtime = runtime(node("boss", 26, BossDelayTrigger(1_200, "1.20"))).openedAt(89)
        runtime.update(frame(clock = 26, wallMs = 10_000))

        assertEquals(
            SwitchRuntimeCommand.None,
            runtime.update(frame(clock = 25, wallMs = 15_000, boss = bossEvent(26, 15_000)))
        )
        assertEquals(
            SwitchRuntimeCommand.None,
            runtime.update(frame(clock = 25, wallMs = 16_200, trustworthy = false))
        )
        assertTrue(
            runtime.update(frame(clock = 25, wallMs = 16_300, trustworthy = true))
                is SwitchRuntimeCommand.Converge
        )
    }

    @Test fun `manual recognition pause discards a pending switch boss delay`() {
        val runtime = runtime(node("boss", 26, BossDelayTrigger(1_200, "1.20"))).openedAt(89)
        runtime.update(frame(clock = 26, wallMs = 10_000))
        runtime.update(frame(clock = 25, wallMs = 15_000, boss = bossEvent(26, 15_000)))

        runtime.clearRecognitionEvidence()

        assertEquals(SwitchRuntimeCommand.None, runtime.update(frame(clock = 25, wallMs = 20_000)))
    }

    @Test fun `boss node without delay converges immediately after detection`() {
        val runtime = runtime(node("boss-immediate", 56, BossDelayTrigger(null, null))).openedAt(89)
        runtime.update(frame(clock = 56, wallMs = 10_000))

        assertTrue(
            runtime.update(frame(clock = 55, wallMs = 15_000, boss = bossEvent(56, 15_000)))
                is SwitchRuntimeCommand.Converge
        )
    }

    @Test fun `boss node without delay accepts early hold confirmation`() {
        val runtime = runtime(node("boss-early", 56, BossDelayTrigger(null, null))).openedAt(89)
        runtime.update(frame(clock = 56, wallMs = 10_000))

        assertTrue(
            runtime.update(
                frame(
                    clock = 56,
                    wallMs = 17_000,
                    boss = BossUbEvent(56, 17_000, 7_000, early = true)
                )
            ) is SwitchRuntimeCommand.Converge
        )
    }

    @Test fun `boss snapshot exposes deadline only after detection`() {
        val runtime = runtime(node("boss", 26, BossDelayTrigger(1_200, "1.20"))).openedAt(89)

        runtime.update(frame(clock = 26, wallMs = 10_000))
        val armed = runtime.snapshot()

        assertEquals("boss", armed.nodeId)
        assertEquals(2, armed.sourceLine)
        assertEquals("BOSS_DELAY", armed.triggerType)
        assertEquals("Armed", armed.runtimeState)
        assertEquals(10_000L, armed.eligibleWallMs)
        assertEquals(null, armed.deadlineWallMs)

        runtime.update(frame(clock = 25, wallMs = 15_000, boss = bossEvent(26, 15_000)))
        assertEquals(16_200L, runtime.snapshot().deadlineWallMs)
    }

    @Test fun `pause frame blocks later nodes until manual confirmation and convergence`() {
        val pause = node("pause", 18, PauseFrameTrigger(CharacterRole.ROLE_3, "角色3"))
        val later = node("later", 17, TimedTrigger)
        val runtime = runtime(pause, later).openedAt(89)

        val enter = runtime.update(frame(clock = 18)) as SwitchRuntimeCommand.EnterPauseFrame
        assertEquals(CharacterRole.ROLE_3, enter.role)
        assertEquals(SwitchRuntimeCommand.None, runtime.update(frame(clock = 17)))

        runtime.confirmPauseFrame("pause")
        assertEquals("pause", (runtime.update(frame(clock = 17)) as SwitchRuntimeCommand.Converge).nodeId)
        runtime.confirmConvergence("pause")
        assertEquals("later", (runtime.update(frame(clock = 17)) as SwitchRuntimeCommand.Converge).nodeId)
    }

    @Test fun `pause frame waits until controls are trustworthy`() {
        val pause = node("pause", 18, PauseFrameTrigger(CharacterRole.ROLE_3, "角色3"))
        val runtime = runtime(pause).openedAt(89)

        assertEquals(
            SwitchRuntimeCommand.None,
            runtime.update(frame(clock = 18, trustworthy = false))
        )
        assertTrue(
            runtime.update(frame(clock = 18, trustworthy = true))
                is SwitchRuntimeCommand.EnterPauseFrame
        )
    }

    private fun runtime(
        vararg nodes: SwitchAxisNode,
        openingGraceSeconds: Int = 0
    ) = SwitchAxisRuntime(
        opening = SwitchAxisOpening(1, target()),
        nodes = nodes.toList(),
        openingGraceSeconds = openingGraceSeconds
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
        trustworthy: Boolean = true,
        boss: BossUbEvent? = null
    ) = SwitchFrameInput(clock, triggered, trustworthy, wallMs, boss)

    private fun bossEvent(clock: Int, detectedAt: Long) =
        BossUbEvent(clock, detectedAt, holdDurationMs = 6_000)
}
