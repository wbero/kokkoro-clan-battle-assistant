package com.kokkoro.clanbattle.axis

import com.kokkoro.clanbattle.recognition.CharacterRole
import java.util.Locale

enum class VisualSequenceTriggerType { TIMED, CHARACTER_UB, BOSS_DELAY, PAUSE_FRAME }

data class VisualSequenceTrigger(
    val type: VisualSequenceTriggerType = VisualSequenceTriggerType.TIMED,
    val roleIndex: Int? = null,
    val pauseAuto: Boolean = false,
    val bossDelayMs: Long? = null
) {
    init {
        require(roleIndex == null || roleIndex in 0..4)
        require(type != VisualSequenceTriggerType.CHARACTER_UB || roleIndex != null)
        require(type != VisualSequenceTriggerType.PAUSE_FRAME || pauseAuto || roleIndex != null)
    }
}

sealed interface VisualSequenceAction {
    data class RoleLifecycle(val roleIndex: Int) : VisualSequenceAction {
        init { require(roleIndex in 0..4) }
    }
    data object ClickAuto : VisualSequenceAction
    data class SetAuto(val on: Boolean) : VisualSequenceAction
    data class SetRoleStates(val rolesOn: List<Boolean>) : VisualSequenceAction {
        init { require(rolesOn.size == 5) }
    }
    data class Notify(val message: String) : VisualSequenceAction
    data object BossMarker : VisualSequenceAction
}

data class VisualSequenceNode(
    val timeSeconds: Int,
    val trigger: VisualSequenceTrigger = VisualSequenceTrigger(),
    val actions: List<VisualSequenceAction> = emptyList()
) {
    init { require(timeSeconds in 0..90) }
}

data class SequenceAxisVisualDraft(
    val name: String = "新建顺序轴",
    val clickIntervalMs: Int = 100,
    val roleNames: List<String> = List(5) { "角色${it + 1}" },
    val roleUbSkillNames: List<String> = List(5) { "" },
    val nodes: List<VisualSequenceNode> = emptyList()
) {
    init {
        require(roleNames.size == 5)
        require(roleUbSkillNames.size == 5)
    }

    fun toStandardText(): String = buildList {
        add("轴类型=顺序")
        add("轴名称=${name.ifBlank { "未命名顺序轴" }}")
        add("点击间隔=$clickIntervalMs")
        roleNames.forEachIndexed { index, roleName ->
            add("角色${index + 1}=${roleName.trim().ifBlank { "角色${index + 1}" }}")
        }
        roleUbSkillNames.forEachIndexed { index, skillName ->
            if (skillName.isNotBlank()) add("角色${index + 1}UB=${skillName.trim()}")
        }
        add("")
        add("[轴]")
        nodes.forEach { node ->
            val fields = buildList {
                addAll(node.trigger.fields())
                node.actions.forEach { add(it.field()) }
            }
            add("${VisualAxisTime.format(node.timeSeconds)} | ${fields.joinToString(" | ")}")
        }
    }.joinToString("\n")

    companion object {
        fun from(document: AxisDocument): SequenceAxisVisualDraft? {
            if (document.type != AxisType.SEQUENCE) return null
            val roleNames = (1..5).map { index ->
                document.header["角色$index"].orEmpty().ifBlank { "角色$index" }
            }
            val nodes = document.events.map { event ->
                val trigger = event.trigger.toVisualTrigger() ?: return null
                val actions = event.actions.map { action ->
                    action.toVisualAction(roleNames) ?: return null
                }
                VisualSequenceNode(event.timeSeconds, trigger, actions)
            }
            return SequenceAxisVisualDraft(
                name = document.header["轴名称"].orEmpty().ifBlank { "未命名顺序轴" },
                clickIntervalMs = document.clickIntervalMs,
                roleNames = roleNames,
                roleUbSkillNames = (1..5).map { document.header["角色${it}UB"].orEmpty() },
                nodes = nodes
            )
        }
    }
}

private fun SwitchNodeTrigger.toVisualTrigger(): VisualSequenceTrigger? {
    return when (this) {
        TimedTrigger -> VisualSequenceTrigger()
        is CharacterUbTrigger -> {
            val index = role?.ordinal ?: return null
            VisualSequenceTrigger(
                type = VisualSequenceTriggerType.CHARACTER_UB,
                roleIndex = index
            )
        }
        is BossDelayTrigger -> VisualSequenceTrigger(
            type = VisualSequenceTriggerType.BOSS_DELAY,
            bossDelayMs = minimumDelayMs
        )
        is PauseFrameTrigger -> when (val pauseTarget = target ?: return null) {
            is PauseFrameTarget.Role -> VisualSequenceTrigger(
                type = VisualSequenceTriggerType.PAUSE_FRAME,
                roleIndex = pauseTarget.role.ordinal
            )
            PauseFrameTarget.Auto -> VisualSequenceTrigger(
                type = VisualSequenceTriggerType.PAUSE_FRAME,
                pauseAuto = true
            )
        }
        is ConflictingSwitchTrigger -> null
    }
}

private fun AxisAction.toVisualAction(roleNames: List<String>): VisualSequenceAction? {
    return when (type) {
        ActionType.CLICK_ROLE -> {
            val index = resolveRoleIndex(role.orEmpty(), roleNames) ?: return null
            VisualSequenceAction.RoleLifecycle(index)
        }
        ActionType.CLICK_AUTO -> VisualSequenceAction.ClickAuto
        ActionType.TOGGLE_AUTO -> when (rawValue) {
            "开" -> VisualSequenceAction.SetAuto(true)
            "关" -> VisualSequenceAction.SetAuto(false)
            else -> null
        }
        ActionType.SET_ROLES -> {
            if (values.size != 5 || values.any { it !in setOf("开", "关") }) return null
            VisualSequenceAction.SetRoleStates(values.map { it == "开" })
        }
        ActionType.NOTIFY -> VisualSequenceAction.Notify(message.orEmpty())
        ActionType.BOSS -> VisualSequenceAction.BossMarker
    }
}

private fun resolveRoleIndex(raw: String, roleNames: List<String>): Int? {
    Regex("^角色([1-5])$").matchEntire(raw)?.let { return it.groupValues[1].toInt() - 1 }
    return roleNames.indexOf(raw).takeIf { it >= 0 }
}

private fun VisualSequenceTrigger.fields(): List<String> = when (type) {
    VisualSequenceTriggerType.TIMED -> emptyList()
    VisualSequenceTriggerType.CHARACTER_UB -> listOf("UB后=角色${roleIndex!! + 1}")
    VisualSequenceTriggerType.BOSS_DELAY -> buildList {
        add("UB后=BOSS")
        bossDelayMs?.let { add("延迟=${formatDelay(it)}") }
    }
    VisualSequenceTriggerType.PAUSE_FRAME -> listOf(
        if (pauseAuto) "卡帧=AUTO" else "卡帧=角色${roleIndex!! + 1}"
    )
}

private fun VisualSequenceAction.field(): String = when (this) {
    is VisualSequenceAction.RoleLifecycle -> "点击=角色${roleIndex + 1}"
    VisualSequenceAction.ClickAuto -> "点击=AUTO"
    is VisualSequenceAction.SetAuto -> "AUTO=${if (on) "开" else "关"}"
    is VisualSequenceAction.SetRoleStates -> "SET=${rolesOn.joinToString(",") { if (it) "开" else "关" }}"
    is VisualSequenceAction.Notify -> "提示=${message.trim().replace('|', '｜')}"
    VisualSequenceAction.BossMarker -> "点击=BOSS"
}

private fun formatDelay(delayMs: Long): String =
    String.format(Locale.US, "%.3f", delayMs / 1_000.0).trimEnd('0').trimEnd('.')
