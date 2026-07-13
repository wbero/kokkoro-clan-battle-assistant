package com.kokkoro.clanbattle.overlay

data class OverlayButtonState(
    val label: String,
    val enabled: Boolean
)

enum class OverlayPanelColor { GRAY, GREEN, AMBER, RED }

data class OverlayUiState(
    val selectAxis: OverlayButtonState,
    val nextFrame: OverlayButtonState,
    val confirm: OverlayButtonState,
    val safetyMenu: OverlayButtonState,
    val minimize: OverlayButtonState,
    val reset: OverlayButtonState,
    val panelColor: OverlayPanelColor,
    val statusText: String,
    val currentAction: String,
    val nextAction: String
) {
    companion object {
        fun idle(
            axisName: String?,
            statusText: String = "等待开始",
            currentAction: String = "当前：等待触发",
            nextAction: String = "下一：无"
        ) = state(
            axisName = axisName,
            selectionEnabled = true,
            pauseFrameEnabled = false,
            safetyEnabled = false,
            panelColor = OverlayPanelColor.GRAY,
            statusText = statusText,
            currentAction = currentAction,
            nextAction = nextAction
        )

        fun running(
            axisName: String?,
            statusText: String = "运行中",
            currentAction: String = "当前：等待触发",
            nextAction: String = "下一：无"
        ) = state(
            axisName = axisName,
            selectionEnabled = false,
            pauseFrameEnabled = false,
            safetyEnabled = true,
            panelColor = OverlayPanelColor.GREEN,
            statusText = statusText,
            currentAction = currentAction,
            nextAction = nextAction
        )

        fun pauseFrame(
            axisName: String?,
            roleLabel: String,
            statusText: String = "卡帧中",
            currentAction: String = "当前：确认目标帧",
            nextAction: String = "下一：确认后收敛控制状态"
        ) = state(
            axisName = axisName,
            selectionEnabled = false,
            pauseFrameEnabled = true,
            safetyEnabled = true,
            confirmLabel = "确定：$roleLabel",
            panelColor = OverlayPanelColor.AMBER,
            statusText = statusText,
            currentAction = currentAction,
            nextAction = nextAction
        )

        fun safetyPaused(
            axisName: String?,
            statusText: String = "安全暂停",
            currentAction: String = "当前：动作已冻结",
            nextAction: String = "下一：人工恢复后重新规划"
        ) = state(
            axisName = axisName,
            selectionEnabled = false,
            pauseFrameEnabled = false,
            safetyEnabled = false,
            panelColor = OverlayPanelColor.RED,
            statusText = statusText,
            currentAction = currentAction,
            nextAction = nextAction
        )

        private fun state(
            axisName: String?,
            selectionEnabled: Boolean,
            pauseFrameEnabled: Boolean,
            safetyEnabled: Boolean,
            confirmLabel: String = "确定",
            panelColor: OverlayPanelColor,
            statusText: String,
            currentAction: String,
            nextAction: String
        ) = OverlayUiState(
            selectAxis = OverlayButtonState("选择轴：${axisName ?: "未选择"}", selectionEnabled),
            nextFrame = OverlayButtonState("下一帧", pauseFrameEnabled),
            confirm = OverlayButtonState(confirmLabel, pauseFrameEnabled),
            safetyMenu = OverlayButtonState("安全菜单", safetyEnabled),
            minimize = OverlayButtonState("最小化", true),
            reset = OverlayButtonState("重置", true),
            panelColor = panelColor,
            statusText = statusText,
            currentAction = currentAction,
            nextAction = nextAction
        )
    }
}
