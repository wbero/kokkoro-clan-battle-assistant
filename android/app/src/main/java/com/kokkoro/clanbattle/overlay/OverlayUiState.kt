package com.kokkoro.clanbattle.overlay

data class OverlayButtonState(
    val label: String,
    val enabled: Boolean
)

data class OverlayUiState(
    val selectAxis: OverlayButtonState,
    val nextFrame: OverlayButtonState,
    val confirm: OverlayButtonState,
    val safetyMenu: OverlayButtonState,
    val reset: OverlayButtonState
) {
    companion object {
        fun idle(axisName: String?) = state(
            axisName = axisName,
            selectionEnabled = true,
            pauseFrameEnabled = false,
            safetyEnabled = false
        )

        fun running(axisName: String?) = state(
            axisName = axisName,
            selectionEnabled = false,
            pauseFrameEnabled = false,
            safetyEnabled = true
        )

        fun pauseFrame(axisName: String?, roleLabel: String) = state(
            axisName = axisName,
            selectionEnabled = false,
            pauseFrameEnabled = true,
            safetyEnabled = true,
            confirmLabel = "确定：$roleLabel"
        )

        fun safetyPaused(axisName: String?) = state(
            axisName = axisName,
            selectionEnabled = false,
            pauseFrameEnabled = false,
            safetyEnabled = false
        )

        private fun state(
            axisName: String?,
            selectionEnabled: Boolean,
            pauseFrameEnabled: Boolean,
            safetyEnabled: Boolean,
            confirmLabel: String = "确定"
        ) = OverlayUiState(
            selectAxis = OverlayButtonState("选择轴：${axisName ?: "未选择"}", selectionEnabled),
            nextFrame = OverlayButtonState("下一帧", pauseFrameEnabled),
            confirm = OverlayButtonState(confirmLabel, pauseFrameEnabled),
            safetyMenu = OverlayButtonState("安全菜单", safetyEnabled),
            reset = OverlayButtonState("重置", true)
        )
    }
}
