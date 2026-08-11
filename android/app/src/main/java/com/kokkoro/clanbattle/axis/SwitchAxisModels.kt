package com.kokkoro.clanbattle.axis

import com.kokkoro.clanbattle.recognition.CharacterRole

enum class AxisToggleState { ON, OFF }

sealed interface SwitchNodeTrigger

data object TimedTrigger : SwitchNodeTrigger

data class CharacterUbTrigger(
    val role: CharacterRole?,
    val rawRole: String
) : SwitchNodeTrigger

data class BossDelayTrigger(
    val minimumDelayMs: Long?,
    val rawDelay: String?
) : SwitchNodeTrigger

sealed interface PauseFrameTarget {
    data class Role(val role: CharacterRole) : PauseFrameTarget
    data object Auto : PauseFrameTarget
}

data class PauseFrameTrigger(
    val role: CharacterRole?,
    val rawRole: String
) : SwitchNodeTrigger {
    val target: PauseFrameTarget?
        get() = role?.let(PauseFrameTarget::Role)
            ?: PauseFrameTarget.Auto.takeIf { rawRole.equals("AUTO", ignoreCase = true) }
}

fun PauseFrameTarget.label(): String = when (this) {
    is PauseFrameTarget.Role -> "角色${role.ordinal + 1}"
    PauseFrameTarget.Auto -> "AUTO"
}

data class ConflictingSwitchTrigger(
    val ubAfter: String,
    val pauseFrame: String
) : SwitchNodeTrigger

data class SwitchControlTarget(
    val auto: AxisToggleState?,
    val roles: Map<CharacterRole, AxisToggleState?>,
    val rawAuto: String?,
    val rawRoles: List<String>,
    val message: String? = null
)

data class SwitchAxisOpening(
    val sourceLine: Int,
    val target: SwitchControlTarget
)

data class SwitchAxisNode(
    val id: String,
    val sourceLine: Int,
    val timeSeconds: Int,
    val trigger: SwitchNodeTrigger,
    val target: SwitchControlTarget
)
