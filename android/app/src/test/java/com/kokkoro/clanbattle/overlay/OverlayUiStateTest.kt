package com.kokkoro.clanbattle.overlay

import com.kokkoro.clanbattle.axis.AxisParser
import com.kokkoro.clanbattle.axis.ActionType
import com.kokkoro.clanbattle.axis.AxisAction
import com.kokkoro.clanbattle.axis.AxisEvent
import com.kokkoro.clanbattle.capture.actionExecutionBlockReason
import com.kokkoro.clanbattle.capture.buildActionPreview
import com.kokkoro.clanbattle.capture.buildSequenceProgressPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayUiStateTest {
    @Test fun `idle allows axis selection but not pause frame actions`() {
        val state = OverlayUiState.idle("E5刀1")

        assertEquals("选择轴：E5刀1", state.selectAxis.label)
        assertTrue(state.selectAxis.enabled)
        assertFalse(state.nextFrame.enabled)
        assertFalse(state.confirm.enabled)
        assertTrue(state.reset.enabled)
        assertEquals(OverlayPanelColor.GRAY, state.panelColor)
    }

    @Test fun `running locks axis selection and enables safety menu`() {
        val state = OverlayUiState.running(
            axisName = "E5刀1",
            statusText = "识别正常",
            currentAction = "当前：1:12 角色5 UB后 → AUTO开 SET:XXXXX",
            nextAction = "下一：0:26 BOSS UB后+1.20s → AUTO开 SET:XOXXO"
        )

        assertFalse(state.selectAxis.enabled)
        assertFalse(state.nextFrame.enabled)
        assertFalse(state.confirm.enabled)
        assertTrue(state.safetyMenu.enabled)
        assertEquals("最小化", state.minimize.label)
        assertTrue(state.minimize.enabled)
        assertEquals("识别正常", state.statusText)
        assertEquals("当前：1:12 角色5 UB后 → AUTO开 SET:XXXXX", state.currentAction)
        assertEquals("下一：0:26 BOSS UB后+1.20s → AUTO开 SET:XOXXO", state.nextAction)
        assertEquals(OverlayPanelColor.GREEN, state.panelColor)
    }

    @Test fun `missing accessibility blocks real execution with visible reason`() {
        assertEquals(
            "无障碍服务未启用，点击不会执行",
            actionExecutionBlockReason(dryRun = false, accessibilityConnected = false)
        )
        assertEquals(
            "只识别模式，不执行点击",
            actionExecutionBlockReason(dryRun = true, accessibilityConnected = true)
        )
        assertNull(actionExecutionBlockReason(dryRun = false, accessibilityConnected = true))
    }

    @Test fun `switch preview shows active and following node`() {
        val document = AxisParser.parse(
            """
            轴类型=开关
            [轴开局] | SET=关,关,关,关,开 | AUTO=开
            1:12 | UB后=角色5 | SET=关,关,关,关,关 | AUTO=开
            0:26 | UB后=BOSS | 延迟=1.20 | SET=关,开,关,关,开 | AUTO=开
            """.trimIndent()
        )

        val preview = buildActionPreview(
            document = document,
            activeNodeId = document.switchNodes.first().id,
            clockSeconds = 72
        )

        assertEquals("当前：1:12 角色5 UB后 → AUTO开 SET:XXXXX", preview.current)
        assertEquals("下一：0:26 BOSS UB后+1.20s → AUTO开 SET:XOXXO", preview.next)

        val openingPreview = buildActionPreview(document, activeNodeId = null, clockSeconds = 90)
        assertEquals("当前：等待触发", openingPreview.current)
        assertEquals("下一：开局 → AUTO开 SET:XXXXO", openingPreview.next)
    }

    @Test fun `sequence preview explains role ub lifecycle and next action`() {
        val current = AxisEvent(
            id = "line-1:0",
            sourceLine = 1,
            timeSeconds = 65,
            actions = listOf(AxisAction(ActionType.CLICK_ROLE, role = "角色2"))
        )
        val next = AxisEvent(
            id = "line-1:1",
            sourceLine = 1,
            timeSeconds = 65,
            actions = listOf(AxisAction(ActionType.CLICK_AUTO))
        )

        val preview = buildSequenceProgressPreview(current, "WAITING_ROLE_UB", next)

        assertEquals("当前：1:05 等待角色2 UB", preview.current)
        assertEquals("下一：1:05 点击AUTO", preview.next)
    }

    @Test fun `sequence preview shows ub after and pause frame triggers`() {
        val document = AxisParser.parse(
            """
            轴类型=顺序
            [轴]
            1:20 | UB后=角色3 | 点击=角色2
            1:10 | 卡帧=角色4
            """.trimIndent()
        )

        val preview = buildActionPreview(document, document.events.first().id, 80)

        assertEquals("当前：1:20 角色3 UB后 → 点击角色2", preview.current)
        assertEquals("下一：1:10 角色4 卡帧", preview.next)
    }

    @Test fun `minimized icon drag stays inside the screen`() {
        assertEquals(
            OverlayPosition(140, 70),
            boundedOverlayPosition(100, 50, 40, 20, 1080, 1920, 60)
        )
        assertEquals(
            OverlayPosition(0, 1860),
            boundedOverlayPosition(10, 1800, -100, 200, 1080, 1920, 60)
        )
    }

    @Test fun `pause frame enables manual advance and confirmation`() {
        val state = OverlayUiState.pauseFrame("E5刀1", "角色3")

        assertEquals("下一帧", state.nextFrame.label)
        assertTrue(state.nextFrame.enabled)
        assertEquals("确定：角色3", state.confirm.label)
        assertTrue(state.confirm.enabled)
        assertFalse(state.selectAxis.enabled)
        assertEquals(OverlayPanelColor.AMBER, state.panelColor)
    }

    @Test fun `safety paused disables actions until manual recovery or reset`() {
        val state = OverlayUiState.safetyPaused("E5刀1")

        assertFalse(state.selectAxis.enabled)
        assertFalse(state.nextFrame.enabled)
        assertFalse(state.confirm.enabled)
        assertFalse(state.safetyMenu.enabled)
        assertTrue(state.reset.enabled)
        assertEquals(OverlayPanelColor.RED, state.panelColor)
    }
}
