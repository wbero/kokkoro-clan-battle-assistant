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

        if (!document.hasAxisSection) {
            issues += issue(null, "missing-axis-section", "缺少 [轴] 段")
        }
        if (document.header["轴类型"] !in setOf("顺序", "开关")) {
            issues += issue(null, "invalid-axis-type", "轴类型必须为顺序或开关")
        }
        if (document.clickIntervalMs !in 1..5_000) {
            issues += issue(null, "invalid-click-interval", "点击间隔必须在 1..5000 毫秒")
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
            if (event.actions.isEmpty()) {
                issues += issue(event.sourceLine, "empty-actions", "事件没有动作")
            }

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

        return AxisValidationResult(issues)
    }

    private fun issue(line: Int?, code: String, message: String) =
        AxisValidationIssue(line, code, message)
}
