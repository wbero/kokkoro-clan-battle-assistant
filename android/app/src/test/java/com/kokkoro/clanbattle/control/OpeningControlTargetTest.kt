package com.kokkoro.clanbattle.control

import com.kokkoro.clanbattle.axis.AxisParser
import com.kokkoro.clanbattle.axis.ActionType
import com.kokkoro.clanbattle.axis.AxisAction
import com.kokkoro.clanbattle.recognition.CharacterRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpeningControlTargetTest {
    @Test fun `extracts AUTO and SET from the ninety second event`() {
        val axis = AxisParser.parse(
            """
            轴类型=顺序
            [轴]
            1:30 | AUTO=开 | SET=开,关,开,关,开
            1:20 | 点击=角色3
            """.trimIndent()
        )

        val target = OpeningControlTarget.from(axis)

        assertEquals(VisualToggleState.ON, target?.auto)
        assertEquals(
            listOf(
                VisualToggleState.ON,
                VisualToggleState.OFF,
                VisualToggleState.ON,
                VisualToggleState.OFF,
                VisualToggleState.ON
            ),
            CharacterRole.entries.map { target?.roles?.getValue(it) }
        )
    }

    @Test fun `ignores control actions after ninety seconds`() {
        val axis = AxisParser.parse(
            """
            [轴]
            1:29 | AUTO=开 | SET=开,开,开,开,开
            """.trimIndent()
        )

        assertNull(OpeningControlTarget.from(axis))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects duplicate ninety second AUTO targets`() {
        val axis = AxisParser.parse(
            """
            [轴]
            1:30 | AUTO=开
            1:30 | AUTO=关
            """.trimIndent()
        )

        OpeningControlTarget.from(axis)
    }

    @Test fun `converts a scheduled SET action into a role target`() {
        val target = OpeningControlTarget.fromAction(
            AxisAction(ActionType.SET_ROLES, values = listOf("关", "开", "关", "开", "关"))
        )

        assertEquals(
            listOf(
                VisualToggleState.OFF,
                VisualToggleState.ON,
                VisualToggleState.OFF,
                VisualToggleState.ON,
                VisualToggleState.OFF
            ),
            CharacterRole.entries.map { target?.roles?.getValue(it) }
        )
    }
}
