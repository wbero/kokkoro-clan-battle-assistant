package com.kokkoro.clanbattle.axis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SequenceAxisVisualDraftTest {
    @Test fun duplicateRoleLifecycleOrderIsPreserved() {
        val draft = SequenceAxisVisualDraft(
            name = "测试轴",
            roleNames = listOf("鹿妹", "圣莱", "火机", "维尔姆", "情姐"),
            roleUbSkillNames = listOf("UB1", "UB2", "UB3", "UB4", "UB5"),
            nodes = listOf(
                VisualSequenceNode(
                    timeSeconds = 59,
                    actions = listOf(
                        VisualSequenceAction.RoleLifecycle(2),
                        VisualSequenceAction.RoleLifecycle(2),
                        VisualSequenceAction.RoleLifecycle(4)
                    )
                )
            )
        )

        val document = AxisParser.parse(draft.toStandardText())
        assertEquals(listOf("角色3", "角色3", "角色5"), document.events.single().actions.map { it.role })
        assertEquals(3, document.events.single().actions.size)
    }

    @Test fun characterUbTriggerAndOrderedActionsRoundTrip() {
        val source = """
            轴类型=顺序
            轴名称=D4
            点击间隔=100
            角色1=涅妃
            角色2=若菜
            角色3=小凤
            角色4=水切噜
            角色5=花音
            角色1UB=UB1
            角色2UB=UB2
            角色3UB=UB3
            角色4UB=UB4
            角色5UB=UB5

            [轴]
            1:14 | UB后=角色1 | 点击=AUTO | 点击=角色5
        """.trimIndent()

        val draft = assertNotNullDraft(source)
        val node = draft.nodes.single()
        assertEquals(VisualSequenceTriggerType.CHARACTER_UB, node.trigger.type)
        assertEquals(0, node.trigger.roleIndex)
        assertEquals(listOf(VisualSequenceAction.ClickAuto, VisualSequenceAction.RoleLifecycle(4)), node.actions)

        val reparsed = AxisParser.parse(draft.toStandardText())
        assertEquals(CharacterUbTrigger(com.kokkoro.clanbattle.recognition.CharacterRole.ROLE_1, "角色1"), reparsed.events.single().trigger)
        assertEquals(listOf(ActionType.CLICK_AUTO, ActionType.CLICK_ROLE), reparsed.events.single().actions.map { it.type })
    }

    @Test fun pauseAutoAndMixedActionsAreRepresentable() {
        val draft = SequenceAxisVisualDraft(
            roleNames = List(5) { "角色${it + 1}" },
            nodes = listOf(
                VisualSequenceNode(
                    57,
                    VisualSequenceTrigger(VisualSequenceTriggerType.PAUSE_FRAME, pauseAuto = true),
                    listOf(VisualSequenceAction.Notify("普攻后卡帧"))
                ),
                VisualSequenceNode(
                    6,
                    actions = listOf(
                        VisualSequenceAction.RoleLifecycle(0),
                        VisualSequenceAction.SetAuto(true),
                        VisualSequenceAction.SetRoleStates(listOf(true, false, true, false, true))
                    )
                )
            )
        )
        val document = AxisParser.parse(draft.toStandardText())
        assertEquals(PauseFrameTarget.Auto, (document.events[0].trigger as PauseFrameTrigger).target)
        assertEquals(listOf(ActionType.CLICK_ROLE, ActionType.TOGGLE_AUTO, ActionType.SET_ROLES), document.events[1].actions.map { it.type })
    }


    @Test fun pauseAutoWithoutActionsRoundTripsToStandardText() {
        val draft = SequenceAxisVisualDraft(
            roleNames = List(5) { "角色${it + 1}" },
            nodes = listOf(
                VisualSequenceNode(
                    57,
                    VisualSequenceTrigger(VisualSequenceTriggerType.PAUSE_FRAME, pauseAuto = true),
                    emptyList()
                )
            )
        )

        val text = draft.toStandardText()
        org.junit.Assert.assertTrue(text.contains("0:57 | 卡帧=AUTO"))
        val document = AxisParser.parse(text)
        assertEquals(PauseFrameTarget.Auto, (document.events.single().trigger as PauseFrameTrigger).target)
        assertEquals(true, AxisValidator.validate(document).isValid)
    }

    private fun assertNotNullDraft(source: String): SequenceAxisVisualDraft =
        SequenceAxisVisualDraft.from(AxisParser.parse(source)).also { assertNotNull(it) }!!
}
