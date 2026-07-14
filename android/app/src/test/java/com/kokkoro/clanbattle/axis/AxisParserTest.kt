package com.kokkoro.clanbattle.axis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AxisParserTest {
    @Test
    fun `parses sequence axis`() {
        val axis = AxisParser.parse(
            """
            轴类型=顺序
            点击间隔=100
            角色3=原晶

            [轴]
            1:16 | 点击=角色3
            1:12 | 点击=AUTO | 提示=切换
            """.trimIndent()
        )

        assertEquals(AxisType.SEQUENCE, axis.type)
        assertEquals(100, axis.clickIntervalMs)
        assertEquals(2, axis.events.size)
        assertEquals(76, axis.events[0].timeSeconds)
        assertEquals(ActionType.CLICK_ROLE, axis.events[0].actions[0].type)
        assertEquals("角色3", axis.events[0].actions[0].role)
    }

    @Test
    fun `parses sequence ub after boss delay and pause frame triggers`() {
        val axis = AxisParser.parse(
            """
            轴类型=顺序
            [轴]
            1:20 | UB后=角色3 | 点击=角色2
            1:10 | 卡帧=角色4 | 提示=确认动作帧
            0:30 | UB后=BOSS | 延迟=1.20 | 点击=AUTO
            """.trimIndent()
        )

        assertEquals(
            CharacterUbTrigger(com.kokkoro.clanbattle.recognition.CharacterRole.ROLE_3, "角色3"),
            axis.events[0].trigger
        )
        assertEquals(
            PauseFrameTrigger(com.kokkoro.clanbattle.recognition.CharacterRole.ROLE_4, "角色4"),
            axis.events[1].trigger
        )
        assertEquals(BossDelayTrigger(1_200, "1.20"), axis.events[2].trigger)
        assertTrue(axis.events[1].actions.single().message == "确认动作帧")
    }
}
