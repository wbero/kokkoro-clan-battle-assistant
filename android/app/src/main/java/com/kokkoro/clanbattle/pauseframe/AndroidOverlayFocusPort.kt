package com.kokkoro.clanbattle.pauseframe

import android.content.Context
import android.util.Log
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
    private var battleDimensions: Pair<Int, Int>? = null

    override fun acquireFocus(): Boolean {
        battleDimensions = landscapeDimensions(dimensions())
        return overlay.acquireGamePauseFocus()
    }

    override fun releaseFocus(): Boolean = overlay.releaseGamePauseFocus()

    override fun sendBack(): Boolean = KokkoroAccessibilityService.instance?.sendBack() == true

    override fun tapMenuRole(role: CharacterRole): Boolean =
        tapCentered("menu-role:${role.name}", ActionCoordinates.menuRole(role))

    override fun dismissMenu(): Boolean = tapCentered("menu-dismiss", ActionCoordinates.menuOutside)

    // 主菜单始终相对屏幕居中，不使用战斗 HUD 的水平校准偏移。
    private fun tapCentered(action: String, point: ReferencePoint): Boolean {
        if (AppPreferences.dryRun(appContext)) return false
        val service = KokkoroAccessibilityService.instance ?: return false
        val (width, height) = battleDimensions ?: landscapeDimensions(dimensions())
        if (width <= 0 || height <= 0) return false
        val x = GameCoordinateMapper.mapX(
            point.x,
            width,
            height,
            HorizontalAnchor.CENTER,
            includeCalibration = false
        )
        val y = GameCoordinateMapper.mapY(point.y, width, height)
        val dispatched = service.tap(x, y)
        Log.i(TAG, "$action size=${width}x$height point=${point.x},${point.y} mapped=$x,$y dispatched=$dispatched")
        return dispatched
    }

    private fun landscapeDimensions(size: Pair<Int, Int>): Pair<Int, Int> =
        if (size.first >= size.second) size else size.second to size.first

    private companion object { const val TAG = "KokkoroPauseFrame" }
}
