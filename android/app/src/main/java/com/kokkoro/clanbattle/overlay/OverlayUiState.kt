package com.kokkoro.clanbattle.overlay

data class OverlayButtonState(
    val label: String,
    val enabled: Boolean
)

enum class OverlayPanelColor { GRAY, GREEN, AMBER, RED }

enum class ManualPauseUiMode { INACTIVE, STEPPING, MENU_HANDOFF }

data class OverlayUiState(
    val selectAxis: OverlayButtonState,
    val releaseA: OverlayButtonState,
    val releaseB: OverlayButtonState,
    val confirm: OverlayButtonState,
    val safetyMenu: OverlayButtonState,
    val minimize: OverlayButtonState,
    val reset: OverlayButtonState,
    val panelColor: OverlayPanelColor,
    val statusText: String,
    val currentAction: String,
    val nextAction: String,
    val manualPause: OverlayButtonState = OverlayButtonState("手动卡帧", false)
) {
    companion object {
        fun idle(
            axisName: String?,
            statusText: String = "等待开始",
            currentAction: String = "当前：等待触发",
            nextAction: String = "下一：无",
            presetA: Int = 5,
            presetB: Int = 20
        ) = state(
            axisName = axisName,
            selectionEnabled = true,
            pauseFrameEnabled = false,
            safetyEnabled = false,
            manualPauseEnabled = true,
            panelColor = OverlayPanelColor.GRAY,
            statusText = statusText,
            currentAction = currentAction,
            nextAction = nextAction,
            presetA = presetA,
            presetB = presetB
        )

        fun running(
            axisName: String?,
            statusText: String = "运行中",
            currentAction: String = "当前：等待触发",
            nextAction: String = "下一：无",
            presetA: Int = 5,
            presetB: Int = 20
        ) = state(
            axisName = axisName,
            selectionEnabled = false,
            pauseFrameEnabled = false,
            safetyEnabled = true,
            manualPauseEnabled = true,
            panelColor = OverlayPanelColor.GREEN,
            statusText = statusText,
            currentAction = currentAction,
            nextAction = nextAction,
            presetA = presetA,
            presetB = presetB
        )

        fun pauseFrame(
            axisName: String?,
            roleLabel: String,
            statusText: String = "卡帧中",
            currentAction: String = "当前：确认目标帧",
            nextAction: String = "下一：确认后收敛控制状态",
            presetA: Int = 5,
            presetB: Int = 20
        ) = state(
            axisName = axisName,
            selectionEnabled = false,
            pauseFrameEnabled = true,
            safetyEnabled = true,
            manualPauseEnabled = false,
            manualPauseLabel = "卡帧中",
            confirmLabel = "确定：$roleLabel",
            panelColor = OverlayPanelColor.AMBER,
            statusText = statusText,
            currentAction = currentAction,
            nextAction = nextAction,
            presetA = presetA,
            presetB = presetB
        )

        fun manualPause(
            axisName: String?,
            statusText: String = "手动卡帧中",
            currentAction: String = "当前：暂停识别",
            nextAction: String = "下一：逐帧观察或进入菜单",
            presetA: Int = 5,
            presetB: Int = 20
        ) = state(
            axisName = axisName,
            selectionEnabled = false,
            pauseFrameEnabled = true,
            safetyEnabled = true,
            manualPauseEnabled = false,
            manualPauseLabel = "卡帧中",
            confirmLabel = "进入菜单",
            panelColor = OverlayPanelColor.AMBER,
            statusText = statusText,
            currentAction = currentAction,
            nextAction = nextAction,
            presetA = presetA,
            presetB = presetB
        )

        fun manualMenu(
            axisName: String?,
            statusText: String = "菜单操作中（识别已暂停）",
            currentAction: String = "当前：请在游戏菜单中手动操作",
            nextAction: String = "下一：关闭菜单后点击恢复识别",
            presetA: Int = 5,
            presetB: Int = 20
        ) = state(
            axisName = axisName,
            selectionEnabled = false,
            pauseFrameEnabled = false,
            safetyEnabled = false,
            manualPauseEnabled = true,
            manualPauseLabel = "恢复识别",
            panelColor = OverlayPanelColor.AMBER,
            statusText = statusText,
            currentAction = currentAction,
            nextAction = nextAction,
            presetA = presetA,
            presetB = presetB
        )

        fun safetyPaused(
            axisName: String?,
            statusText: String = "安全暂停",
            currentAction: String = "当前：动作已冻结",
            nextAction: String = "下一：人工恢复后重新规划",
            presetA: Int = 5,
            presetB: Int = 20
        ) = state(
            axisName = axisName,
            selectionEnabled = false,
            pauseFrameEnabled = false,
            safetyEnabled = true,
            safetyLabel = "恢复识别",
            manualPauseEnabled = false,
            panelColor = OverlayPanelColor.RED,
            statusText = statusText,
            currentAction = currentAction,
            nextAction = nextAction,
            presetA = presetA,
            presetB = presetB
        )

        private fun state(
            axisName: String?,
            selectionEnabled: Boolean,
            pauseFrameEnabled: Boolean,
            safetyEnabled: Boolean,
            safetyLabel: String = "安全菜单",
            manualPauseEnabled: Boolean,
            manualPauseLabel: String = "手动卡帧",
            confirmLabel: String = "确定",
            panelColor: OverlayPanelColor,
            statusText: String,
            currentAction: String,
            nextAction: String,
            presetA: Int,
            presetB: Int
        ) = OverlayUiState(
            selectAxis = OverlayButtonState("选择轴：${axisName ?: "未选择"}", selectionEnabled),
            releaseA = OverlayButtonState("释放${presetA}帧", pauseFrameEnabled),
            releaseB = OverlayButtonState("释放${presetB}帧", pauseFrameEnabled),
            confirm = OverlayButtonState(confirmLabel, pauseFrameEnabled),
            manualPause = OverlayButtonState(manualPauseLabel, manualPauseEnabled),
            safetyMenu = OverlayButtonState(safetyLabel, safetyEnabled),
            minimize = OverlayButtonState("最小化", true),
            reset = OverlayButtonState("重置", true),
            panelColor = panelColor,
            statusText = statusText,
            currentAction = currentAction,
            nextAction = nextAction
        )
    }
}

fun resolveOverlayUiState(
    axisName: String?,
    battleLocked: Boolean,
    pauseFrameRoleLabel: String?,
    safetyPaused: Boolean,
    statusText: String,
    currentAction: String,
    nextAction: String,
    presetA: Int = 5,
    presetB: Int = 20,
    manualPauseMode: ManualPauseUiMode = ManualPauseUiMode.INACTIVE
): OverlayUiState = when {
    pauseFrameRoleLabel != null -> OverlayUiState.pauseFrame(
        axisName,
        pauseFrameRoleLabel,
        statusText,
        currentAction,
        nextAction,
        presetA,
        presetB
    )
    manualPauseMode == ManualPauseUiMode.STEPPING -> OverlayUiState.manualPause(
        axisName,
        statusText,
        currentAction,
        nextAction,
        presetA,
        presetB
    )
    manualPauseMode == ManualPauseUiMode.MENU_HANDOFF -> OverlayUiState.manualMenu(
        axisName,
        statusText,
        currentAction,
        nextAction,
        presetA,
        presetB
    )
    safetyPaused -> OverlayUiState.safetyPaused(axisName, statusText, currentAction, nextAction, presetA, presetB)
    battleLocked -> OverlayUiState.running(axisName, statusText, currentAction, nextAction, presetA, presetB)
    else -> OverlayUiState.idle(axisName, statusText, currentAction, nextAction, presetA, presetB)
}
