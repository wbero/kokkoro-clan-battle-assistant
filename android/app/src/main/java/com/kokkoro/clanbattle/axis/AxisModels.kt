package com.kokkoro.clanbattle.axis

enum class AxisType { SEQUENCE, SWITCH }

enum class ActionType {
    CLICK_ROLE,
    CLICK_AUTO,
    TOGGLE_AUTO,
    NOTIFY,
    BOSS,
    SET_ROLES
}

data class AxisAction(
    val type: ActionType,
    val role: String? = null,
    val value: String? = null,
    val rawValue: String? = null,
    val message: String? = null,
    val values: List<String> = emptyList()
)

data class AxisEvent(
    val id: String,
    val sourceLine: Int,
    val timeSeconds: Int,
    val actions: List<AxisAction>
)

data class AxisDocument(
    val type: AxisType,
    val clickIntervalMs: Int,
    val header: Map<String, String>,
    val events: List<AxisEvent>,
    val hasAxisSection: Boolean = false,
    val switchOpenings: List<SwitchAxisOpening> = emptyList(),
    val switchNodes: List<SwitchAxisNode> = emptyList(),
    val hasSwitchSection: Boolean = false
)
