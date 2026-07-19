package com.kokkoro.clanbattle.axis

data class AxisValidationIssue(
    val line: Int?,
    val code: String,
    val message: String
)

data class AxisValidationResult(val issues: List<AxisValidationIssue>) {
    val isValid: Boolean get() = issues.isEmpty()
}

object AxisValidator {
    private val roleKeys = (1..5).map { "角色$it" }.toSet()

    fun validate(document: AxisDocument): AxisValidationResult {
        val issues = mutableListOf<AxisValidationIssue>()

        if (document.type == AxisType.SWITCH) {
            validateSwitch(document, issues)
            if (document.events.isNotEmpty()) validateSequence(document, issues)
        } else {
            validateSequence(document, issues)
        }
        if (document.header["轴类型"] !in setOf("顺序", "开关") && !document.hasSwitchSection) {
            issues += issue(null, "invalid-axis-type", "轴类型必须为顺序或开关")
        }
        if (document.clickIntervalMs !in 1..5_000) {
            issues += issue(null, "invalid-click-interval", "点击间隔必须在 1..5000 毫秒")
        }

        return AxisValidationResult(issues)
    }

    private fun validateSequence(
        document: AxisDocument,
        issues: MutableList<AxisValidationIssue>
    ) {
        if (!document.hasAxisSection) {
            issues += issue(null, "missing-axis-section", "缺少 [轴] 段")
        }

        val aliases = roleKeys.mapNotNull(document.header::get).filter(String::isNotBlank).toSet()
        val acceptedRoles = roleKeys + aliases
        val seenIds = mutableSetOf<String>()

        document.events.forEach { event ->
            if (!seenIds.add(event.id)) {
                issues += issue(event.sourceLine, "duplicate-event-id", "事件 ID 重复：${event.id}")
            }
            if (event.timeSeconds !in 0..90) {
                issues += issue(event.sourceLine, "time-out-of-range", "时间必须在 0:00..1:30")
            }
            if (event.actions.isEmpty() && event.trigger !is PauseFrameTrigger) {
                issues += issue(event.sourceLine, "empty-actions", "事件没有动作")
            }

            validateSequenceTrigger(event, issues)

            event.actions.forEach { action ->
                when (action.type) {
                    ActionType.CLICK_ROLE -> if (action.role !in acceptedRoles) {
                        issues += issue(event.sourceLine, "unknown-role", "未知角色或别名：${action.role.orEmpty()}")
                    }
                    ActionType.TOGGLE_AUTO -> if (action.rawValue !in setOf("开", "关")) {
                        issues += issue(event.sourceLine, "invalid-auto-value", "AUTO 只能为开或关")
                    }
                    ActionType.SET_ROLES -> if (
                        action.values.size != 5 || action.values.any { it !in setOf("开", "关") }
                    ) {
                        issues += issue(event.sourceLine, "invalid-set-values", "SET 必须包含五个开或关")
                    }
                    else -> Unit
                }
            }
        }

    }

    private fun validateSequenceTrigger(
        event: AxisEvent,
        issues: MutableList<AxisValidationIssue>
    ) {
        when (val trigger = event.trigger) {
            is CharacterUbTrigger -> if (trigger.role == null) {
                issues += issue(event.sourceLine, "invalid-character-ub-role", "UB后必须指定角色1到角色5")
            }
            is BossDelayTrigger -> if (
                trigger.rawDelay != null &&
                (trigger.minimumDelayMs == null || trigger.minimumDelayMs !in 0..30_000)
            ) {
                issues += issue(
                    event.sourceLine,
                    "invalid-boss-delay",
                    "Boss延迟必须为0到30秒"
                )
            }
            is PauseFrameTrigger -> if (trigger.role == null) {
                issues += issue(event.sourceLine, "invalid-pause-frame-role", "卡帧必须指定角色1到角色5")
            }
            is ConflictingSwitchTrigger -> issues += issue(
                event.sourceLine,
                "conflicting-sequence-triggers",
                "同一顺序节点不能同时声明UB后和卡帧"
            )
            TimedTrigger -> Unit
        }
    }

    private fun validateSwitch(
        document: AxisDocument,
        issues: MutableList<AxisValidationIssue>
    ) {
        when (document.switchOpenings.size) {
            0 -> issues += issue(null, "missing-switch-opening", "开关轴缺少 [轴开局]")
            1 -> if (!document.switchOpenings.single().target.isComplete()) {
                issues += issue(
                    document.switchOpenings.single().sourceLine,
                    "switch-target-required",
                    "轴开局必须包含五个 SET 状态和 AUTO 状态"
                )
            }
            else -> issues += issue(null, "duplicate-switch-opening", "开关轴只能包含一个 [轴开局]")
        }

        val seenIds = mutableSetOf<String>()
        document.switchNodes.forEach { node ->
            if (!seenIds.add(node.id)) {
                issues += issue(node.sourceLine, "duplicate-event-id", "事件 ID 重复：${node.id}")
            }
            if (node.timeSeconds !in 0..90) {
                issues += issue(node.sourceLine, "time-out-of-range", "时间必须在 0:00..1:30")
            }

            when (val trigger = node.trigger) {
                is CharacterUbTrigger -> if (trigger.role == null) {
                    issues += issue(node.sourceLine, "invalid-character-ub-role", "UB后必须指定角色1到角色5")
                }
                is BossDelayTrigger -> if (
                    trigger.rawDelay != null &&
                    (trigger.minimumDelayMs == null || trigger.minimumDelayMs !in 0..30_000)
                ) {
                    issues += issue(
                        node.sourceLine,
                        "invalid-boss-delay",
                        "Boss延迟必须为0到30秒"
                    )
                }
                is ConflictingSwitchTrigger -> issues += issue(
                    node.sourceLine,
                    "conflicting-switch-triggers",
                    "同一节点不能同时声明UB后和卡帧"
                )
                is PauseFrameTrigger -> if (trigger.role == null || !node.target.isComplete()) {
                    issues += issue(
                        node.sourceLine,
                        "pause-frame-target-required",
                        "卡帧节点必须指定一个角色及完整SET/AUTO目标"
                    )
                }
                TimedTrigger -> Unit
            }

            if (node.trigger !is PauseFrameTrigger && !node.target.isComplete()) {
                issues += issue(
                    node.sourceLine,
                    "switch-target-required",
                    "开关轴节点必须包含五个 SET 状态和 AUTO 状态"
                )
            }
        }
    }

    private fun SwitchControlTarget.isComplete(): Boolean =
        rawAuto in setOf("开", "关") &&
            rawRoles.size == 5 &&
            rawRoles.all { it in setOf("开", "关") }

    private fun issue(line: Int?, code: String, message: String) =
        AxisValidationIssue(line, code, message)
}
