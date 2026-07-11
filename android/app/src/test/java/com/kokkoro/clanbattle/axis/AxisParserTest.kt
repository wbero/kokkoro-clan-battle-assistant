package com.kokkoro.clanbattle.axis

import org.junit.Assert.assertEquals
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
}
