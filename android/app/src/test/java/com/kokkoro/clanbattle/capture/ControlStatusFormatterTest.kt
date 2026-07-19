package com.kokkoro.clanbattle.capture

import com.kokkoro.clanbattle.control.BattleControlState
import com.kokkoro.clanbattle.control.ControlAction
import com.kokkoro.clanbattle.control.ControlSafetyState
import com.kokkoro.clanbattle.control.ControlStep
import com.kokkoro.clanbattle.control.OpeningControlTarget
import com.kokkoro.clanbattle.control.VisualToggleState
import com.kokkoro.clanbattle.recognition.CharacterRole
import org.junit.Assert.assertEquals
import org.junit.Test

class ControlStatusFormatterTest {
    @Test fun `formats target current and pending control action`() {
        val desired = OpeningControlTarget(
            auto = VisualToggleState.ON,
            roles = roles(VisualToggleState.ON, VisualToggleState.OFF, VisualToggleState.OFF, VisualToggleState.OFF, VisualToggleState.ON)
        )
        val observed = BattleControlState(
            auto = VisualToggleState.OFF,
            globalSet = VisualToggleState.OFF,
            roles = roles(VisualToggleState.ON, VisualToggleState.ON, VisualToggleState.ON, VisualToggleState.ON, VisualToggleState.ON)
        )
        val step = ControlStep(
            action = ControlAction.TapAuto,
            reason = "control-click",
            observed = observed,
            desired = desired,
            expected = observed.copy(auto = VisualToggleState.ON),
            safety = ControlSafetyState.RUNNING
        )

        assertEquals(
            "目标 AUTO:开 角色:OXXXO\n当前 AUTO:关 全体UB:关 角色:OOOOO  调整:AUTO",
            ControlStatusFormatter.format(step)
        )
    }

    @Test fun `formats manual safety pause`() {
        val step = ControlStep(
            action = ControlAction.None,
            reason = "控制状态连续不可信",
            observed = null,
            desired = null,
            expected = null,
            safety = ControlSafetyState.SAFETY_PAUSED
        )

        assertEquals(
            "游戏已暂停：控制状态连续不可信\n请手动点击菜单外区域恢复",
            ControlStatusFormatter.format(step)
        )
    }

    @Test fun `generic confirmed state does not flash opening confirmation`() {
        val observed = BattleControlState(
            auto = VisualToggleState.OFF,
            globalSet = VisualToggleState.OFF,
            roles = CharacterRole.entries.associateWith { VisualToggleState.OFF }
        )
        val step = ControlStep(
            action = ControlAction.None,
            reason = "no-control-target",
            observed = observed,
            desired = null,
            expected = null,
            safety = ControlSafetyState.RUNNING,
            confirmed = true
        )

        assertEquals(
            "目标 AUTO:? 角色:?????\n当前 AUTO:关 全体UB:关 角色:XXXXX",
            ControlStatusFormatter.format(step)
        )
        assertEquals(
            "目标 AUTO:? 角色:?????\n当前 AUTO:关 全体UB:关 角色:XXXXX  开局已确认",
            ControlStatusFormatter.format(step, showOpeningConfirmed = true)
        )
    }

    private fun roles(vararg states: VisualToggleState) = CharacterRole.entries.zip(states.asList()).toMap()
}
