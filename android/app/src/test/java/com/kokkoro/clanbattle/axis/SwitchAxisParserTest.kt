package com.kokkoro.clanbattle.axis

import com.kokkoro.clanbattle.recognition.CharacterRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchAxisParserTest {
    @Test fun `parses switch opening and all trigger forms`() {
        val axis = AxisParser.parse(
            """
            轴类型=开关
            轴名称=E5刀1
            [轴开局] | SET=关,关,关,关,开 | AUTO=开 | 提示=开局
            1:12 | UB后=角色5 | SET=关,关,关,关,关 | AUTO=开
            0:26 | UB后=BOSS | 延迟=1.20 | SET=关,开,关,关,开 | AUTO=开
            0:18 | 卡帧=角色3 | SET=开,关,开,关,开 | AUTO=开
            """.trimIndent()
        )

        assertEquals(AxisType.SWITCH, axis.type)
        assertEquals("E5刀1", axis.header["轴名称"])
        assertEquals(1, axis.switchOpenings.size)
        assertEquals(AxisToggleState.ON, axis.switchOpenings.single().target.auto)
        assertEquals(AxisToggleState.ON, axis.switchOpenings.single().target.roles.getValue(CharacterRole.ROLE_5))
        assertEquals(CharacterUbTrigger(CharacterRole.ROLE_5, "角色5"), axis.switchNodes[0].trigger)
        assertEquals(BossDelayTrigger(1_200L, "1.20"), axis.switchNodes[1].trigger)
        assertEquals(PauseFrameTrigger(CharacterRole.ROLE_3, "角色3"), axis.switchNodes[2].trigger)
        assertEquals(72, axis.switchNodes[0].timeSeconds)
    }

    @Test fun `opening marker infers switch type for legacy files without header`() {
        val axis = AxisParser.parse(
            """
            [轴开局] | SET=关,关,关,关,开 | AUTO=开
            1:12 | SET=关,关,关,关,关 | AUTO=开
            """.trimIndent()
        )

        assertEquals(AxisType.SWITCH, axis.type)
        assertEquals(TimedTrigger, axis.switchNodes.single().trigger)
        assertTrue(axis.hasSwitchSection)
    }
}
