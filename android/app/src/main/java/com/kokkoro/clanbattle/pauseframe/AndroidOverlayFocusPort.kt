package com.kokkoro.clanbattle.pauseframe

import android.content.Context
import com.kokkoro.clanbattle.automation.ActionCoordinates
import com.kokkoro.clanbattle.automation.GameCoordinateMapper
import com.kokkoro.clanbattle.automation.HorizontalAnchor
import com.kokkoro.clanbattle.automation.KokkoroAccessibilityService
import com.kokkoro.clanbattle.automation.ReferencePoint
import com.kokkoro.clanbattle.config.AppPreferences
import com.kokkoro.clanbattle.overlay.OverlayController
import com.kokkoro.clanbattle.recognition.CharacterRole

class AndroidOverlayFocusPort(
    context: Context,
    private val overlay: OverlayController,
    private val dimensions: () -> Pair<Int, Int>
) : OverlayFocusPort {
    private val appContext = context.applicationContext

    override fun acquireFocus(): Boolean = overlay.acquireGamePauseFocus()

    override fun releaseFocus(): Boolean = overlay.releaseGamePauseFocus()

    override fun sendBack(): Boolean = KokkoroAccessibilityService.instance?.sendBack() == true

    override fun tapMenuRole(role: CharacterRole): Boolean = tapCentered(ActionCoordinates.menuRole(role))

    override fun dismissMenu(): Boolean = tapCentered(ActionCoordinates.menuReturnButton)

    // 主菜单居中对话框：用 CENTER 锚点（含跨分辨率的 centerDeltaX 校准）映射参考坐标。
    private fun tapCentered(point: ReferencePoint): Boolean {
        if (AppPreferences.dryRun(appContext)) return false
        val service = KokkoroAccessibilityService.instance ?: return false
        val (width, height) = dimensions()
        if (width <= 0 || height <= 0) return false
        val x = GameCoordinateMapper.mapX(point.x, width, height, HorizontalAnchor.CENTER)
        val y = GameCoordinateMapper.mapY(point.y, width, height)
        return service.tap(x, y)
    }
}
