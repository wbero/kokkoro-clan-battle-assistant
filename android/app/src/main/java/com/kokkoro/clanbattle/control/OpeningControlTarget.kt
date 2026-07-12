package com.kokkoro.clanbattle.control

import com.kokkoro.clanbattle.axis.ActionType
import com.kokkoro.clanbattle.axis.AxisAction
import com.kokkoro.clanbattle.axis.AxisDocument
import com.kokkoro.clanbattle.recognition.CharacterRole

data class OpeningControlTarget(
    val auto: VisualToggleState? = null,
    val roles: Map<CharacterRole, VisualToggleState>? = null
) {
    companion object {
        fun from(axis: AxisDocument): OpeningControlTarget? {
            val actions = axis.events
                .filter { it.timeSeconds == 90 }
                .flatMap { it.actions }
            val autoActions = actions.filter { it.type == ActionType.TOGGLE_AUTO }
            val setActions = actions.filter { it.type == ActionType.SET_ROLES }
            require(autoActions.size <= 1) { "1:30 只能声明一个 AUTO 目标" }
            require(setActions.size <= 1) { "1:30 只能声明一个 SET 目标" }

            val auto = autoActions.singleOrNull()?.value?.let(::parseState)
            val roles = setActions.singleOrNull()?.values?.let { values ->
                require(values.size == CharacterRole.entries.size) { "SET 必须包含五个角色状态" }
                CharacterRole.entries.zip(values.map(::parseChineseState)).toMap()
            }
            return if (auto == null && roles == null) null else OpeningControlTarget(auto, roles)
        }

        fun fromAction(action: AxisAction): OpeningControlTarget? = when (action.type) {
            ActionType.TOGGLE_AUTO -> OpeningControlTarget(
                auto = action.value?.let(::parseState)
            )
            ActionType.SET_ROLES -> {
                require(action.values.size == CharacterRole.entries.size) { "SET 必须包含五个角色状态" }
                OpeningControlTarget(
                    roles = CharacterRole.entries.zip(action.values.map(::parseChineseState)).toMap()
                )
            }
            else -> null
        }

        private fun parseState(value: String): VisualToggleState = when (value) {
            "on" -> VisualToggleState.ON
            "off" -> VisualToggleState.OFF
            else -> error("非法 AUTO 目标：$value")
        }

        private fun parseChineseState(value: String): VisualToggleState = when (value) {
            "开" -> VisualToggleState.ON
            "关" -> VisualToggleState.OFF
            else -> error("非法 SET 目标：$value")
        }
    }
}
