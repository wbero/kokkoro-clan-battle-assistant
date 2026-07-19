package com.kokkoro.clanbattle.axis

import com.kokkoro.clanbattle.recognition.CharacterRole

enum class VisualSwitchTrigger { TIMED, CHARACTER_UB, BOSS_DELAY, PAUSE_FRAME }

data class VisualSwitchTarget(
    val rolesOn: List<Boolean> = List(5) { false },
    val autoOn: Boolean = true
) {
    init { require(rolesOn.size == 5) }
}

data class VisualSwitchNode(
    val timeSeconds: Int,
    val trigger: VisualSwitchTrigger = VisualSwitchTrigger.TIMED,
    val triggerRoleIndex: Int = 0,
    val bossDelayMs: Long = 1_200,
    val target: VisualSwitchTarget = VisualSwitchTarget(),
    val message: String = ""
) {
    init {
        require(timeSeconds in 0..90)
        require(triggerRoleIndex in 0..4)
    }
}

data class SwitchAxisVisualDraft(
    val name: String = "新建开关轴",
    val roleNames: List<String> = List(5) { "角色${it + 1}" },
    val opening: VisualSwitchTarget = VisualSwitchTarget(),
    val openingMessage: String = "",
    val nodes: List<VisualSwitchNode> = emptyList()
) {
    init { require(roleNames.size == 5) }

    fun toStandardText(): String = buildList {
        add("轴类型=开关")
        add("轴名称=${name.ifBlank { "未命名开关轴" }}")
        roleNames.forEachIndexed { index, roleName ->
            if (roleName.isNotBlank() && roleName != "角色${index + 1}") add("角色${index + 1}=$roleName")
        }
        add("")
        add("[轴开局] | ${opening.fields()}${openingMessage.messageField()}")
        nodes.forEach { node ->
            val trigger = node.triggerFields().takeIf(String::isNotEmpty)?.let { "$it | " }.orEmpty()
            add("${VisualAxisTime.format(node.timeSeconds)} | $trigger" +
                "${node.target.fields()}${node.message.messageField()}")
        }
    }.joinToString("\n")

    companion object {
        fun from(document: AxisDocument): SwitchAxisVisualDraft? {
            if (document.type != AxisType.SWITCH || document.switchOpenings.size != 1) return null
            val opening = document.switchOpenings.single().target.toVisualTarget() ?: return null
            val nodes = document.switchNodes.map { node ->
                val target = node.target.toVisualTarget() ?: return null
                val trigger = when (node.trigger) {
                    TimedTrigger -> VisualSwitchTrigger.TIMED
                    is CharacterUbTrigger -> VisualSwitchTrigger.CHARACTER_UB
                    is BossDelayTrigger -> VisualSwitchTrigger.BOSS_DELAY
                    is PauseFrameTrigger -> VisualSwitchTrigger.PAUSE_FRAME
                    is ConflictingSwitchTrigger -> return null
                }
                val role = when (val source = node.trigger) {
                    is CharacterUbTrigger -> source.role?.ordinal ?: return null
                    is PauseFrameTrigger -> source.role?.ordinal ?: return null
                    else -> 0
                }
                VisualSwitchNode(
                    timeSeconds = node.timeSeconds,
                    trigger = trigger,
                    triggerRoleIndex = role,
                    bossDelayMs = (node.trigger as? BossDelayTrigger)?.minimumDelayMs ?: 0,
                    target = target,
                    message = node.target.message.orEmpty()
                )
            }
            return SwitchAxisVisualDraft(
                name = document.header["轴名称"].orEmpty().ifBlank { "未命名开关轴" },
                roleNames = (1..5).map { document.header["角色$it"].orEmpty().ifBlank { "角色$it" } },
                opening = opening,
                openingMessage = document.switchOpenings.single().target.message.orEmpty(),
                nodes = nodes
            )
        }
    }
}

object VisualAxisTime {
    fun parse(raw: String): Int? {
        val value = raw.trim()
        Regex("^(\\d):([0-5]\\d)$").matchEntire(value)?.let { match ->
            return (match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()).takeIf { it <= 90 }
        }
        if (Regex("^\\d{1,2}$").matches(value)) return value.toInt().takeIf { it <= 90 }
        Regex("^(\\d)([0-5]\\d)$").matchEntire(value)?.let { match ->
            return (match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()).takeIf { it <= 90 }
        }
        return null
    }

    fun format(seconds: Int): String {
        require(seconds in 0..90)
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }
}

private fun VisualSwitchTarget.fields(): String =
    "SET=${rolesOn.joinToString(",") { if (it) "开" else "关" }} | AUTO=${if (autoOn) "开" else "关"}"

private fun VisualSwitchNode.triggerFields(): String = when (trigger) {
    VisualSwitchTrigger.TIMED -> ""
    VisualSwitchTrigger.CHARACTER_UB -> "UB后=角色${triggerRoleIndex + 1}"
    VisualSwitchTrigger.BOSS_DELAY -> "UB后=BOSS | 延迟=${"%.2f".format(java.util.Locale.US, bossDelayMs / 1_000.0)}"
    VisualSwitchTrigger.PAUSE_FRAME -> "卡帧=角色${triggerRoleIndex + 1}"
}

private fun String.messageField(): String = if (isBlank()) "" else " | 提示=${trim().replace('|', '｜')}"

private fun SwitchControlTarget.toVisualTarget(): VisualSwitchTarget? {
    val states = CharacterRole.entries.map { roles[it] ?: return null }
    val autoState = auto ?: return null
    return VisualSwitchTarget(states.map { it == AxisToggleState.ON }, autoState == AxisToggleState.ON)
}
