package com.kokkoro.clanbattle.capture

import com.kokkoro.clanbattle.control.ControlAction
import com.kokkoro.clanbattle.control.ControlSafetyState
import com.kokkoro.clanbattle.control.ControlStep
import com.kokkoro.clanbattle.control.VisualToggleState
import com.kokkoro.clanbattle.recognition.CharacterRole

object ControlStatusFormatter {
    fun format(step: ControlStep, showOpeningConfirmed: Boolean = false): String {
        if (step.safety == ControlSafetyState.SAFETY_PAUSED) {
            return "游戏已暂停：${step.reason}\n请手动点击菜单外区域恢复"
        }
        if (step.safety == ControlSafetyState.SAFETY_PAUSING) {
            return "正在安全暂停：${step.reason}"
        }

        val desired = step.desired
        val observed = step.observed
        val targetLine = buildString {
            append("目标 AUTO:").append(desired?.auto.symbol())
            append(" 角色:").append(desired?.roles.roleSymbols())
        }
        val currentLine = buildString {
            append("当前 AUTO:").append(observed?.auto.symbol())
            append(" 全体UB:").append(observed?.globalSet.symbol())
            append(" 角色:").append(observed?.roles.roleSymbols())
            if (step.action != ControlAction.None) append("  调整:").append(step.action.label())
            else if (showOpeningConfirmed) append("  开局已确认")
        }
        return "$targetLine\n$currentLine"
    }

    private fun VisualToggleState?.symbol(): String = when (this) {
        VisualToggleState.ON -> "开"
        VisualToggleState.OFF -> "关"
        VisualToggleState.UNKNOWN, null -> "?"
    }

    private fun Map<CharacterRole, VisualToggleState>?.roleSymbols(): String =
        CharacterRole.entries.joinToString(separator = "") { role ->
            when (this?.get(role)) {
                VisualToggleState.ON -> "O"
                VisualToggleState.OFF -> "X"
                VisualToggleState.UNKNOWN, null -> "?"
            }
        }

    private fun ControlAction.label(): String = when (this) {
        ControlAction.TapAuto -> "AUTO"
        ControlAction.TapGlobalSet -> "全体UB"
        is ControlAction.TapRole -> "角色${role.ordinal + 1}"
        ControlAction.TapMenu -> "菜单"
        ControlAction.None -> "-"
    }
}
