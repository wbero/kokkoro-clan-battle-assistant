package com.kokkoro.clanbattle.axis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchAxisVisualDraftTest {
    @Test fun `time accepts colon seconds and compact formats`() {
        assertEquals(76, VisualAxisTime.parse("1:16"))
        assertEquals(90, VisualAxisTime.parse("90"))
        assertEquals(76, VisualAxisTime.parse("76"))
        assertEquals(76, VisualAxisTime.parse("116"))
        assertEquals(59, VisualAxisTime.parse("059"))
        assertEquals("1:16", VisualAxisTime.format(76))
    }

    @Test fun `time rejects malformed and out of range values`() {
        listOf("", "1:5", "91", "160", "1:60", "01:30").forEach { assertNull(it, VisualAxisTime.parse(it)) }
    }

    @Test fun `draft serializes all four row types to a valid switch axis`() {
        val target = VisualSwitchTarget(listOf(true, false, true, false, true), autoOn = true)
        val draft = SwitchAxisVisualDraft(
            name = "可视化示例",
            roleNames = listOf("A", "B", "C", "D", "E"),
            opening = target,
            nodes = listOf(
                VisualSwitchNode(80, target = target),
                VisualSwitchNode(76, VisualSwitchTrigger.CHARACTER_UB, 2, target = target),
                VisualSwitchNode(30, VisualSwitchTrigger.BOSS_DELAY, bossDelayMs = 1_200, target = target),
                VisualSwitchNode(18, VisualSwitchTrigger.PAUSE_FRAME, 4, target = target)
            )
        )

        val text = draft.toStandardText()
        val parsed = AxisParser.parse(text)

        assertTrue(AxisValidator.validate(parsed).issues.toString(), AxisValidator.validate(parsed).isValid)
        assertTrue(text.contains("1:16 | UB后=角色3"))
        assertTrue(text.contains("0:30 | UB后=BOSS | 延迟=1.20"))
        assertTrue(text.contains("0:18 | 卡帧=角色5"))
        assertNotNull(SwitchAxisVisualDraft.from(parsed))
    }
}
