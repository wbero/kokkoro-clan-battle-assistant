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
    val reset: OverlayButtonState,
    val panelColor: OverlayPanelColor
) {
    companion object {
        fun idle(axisName: String?) = state(
            axisName = axisName,
            selectionEnabled = true,
            pauseFrameEnabled = false,
            safetyEnabled = false,
            panelColor = OverlayPanelColor.GRAY
        )

        fun running(axisName: String?) = state(
            axisName = axisName,
            selectionEnabled = false,
            pauseFrameEnabled = false,
            safetyEnabled = true,
            panelColor = OverlayPanelColor.GREEN
        )

        fun pauseFrame(axisName: String?, roleLabel: String) = state(
            axisName = axisName,
            selectionEnabled = false,
            pauseFrameEnabled = true,
            safetyEnabled = true,
            confirmLabel = "确定：$roleLabel",
            panelColor = OverlayPanelColor.AMBER
        )

        fun safetyPaused(axisName: String?) = state(
            axisName = axisName,
            selectionEnabled = false,
            pauseFrameEnabled = false,
            safetyEnabled = false,
            panelColor = OverlayPanelColor.RED
        )

        private fun state(
            axisName: String?,
            selectionEnabled: Boolean,
            pauseFrameEnabled: Boolean,
            safetyEnabled: Boolean,
            confirmLabel: String = "确定",
            panelColor: OverlayPanelColor
        ) = OverlayUiState(
            selectAxis = OverlayButtonState("选择轴：${axisName ?: "未选择"}", selectionEnabled),
            nextFrame = OverlayButtonState("下一帧", pauseFrameEnabled),
            confirm = OverlayButtonState(confirmLabel, pauseFrameEnabled),
            safetyMenu = OverlayButtonState("安全菜单", safetyEnabled),
            reset = OverlayButtonState("重置", true),
            panelColor = panelColor
        )
    }
}
