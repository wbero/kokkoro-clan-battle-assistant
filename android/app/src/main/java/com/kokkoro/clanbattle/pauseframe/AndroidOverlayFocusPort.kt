package com.kokkoro.clanbattle.pauseframe

import android.content.Context
import com.kokkoro.clanbattle.automation.ActionCoordinates
import com.kokkoro.clanbattle.automation.KokkoroAccessibilityService
import com.kokkoro.clanbattle.config.AppPreferences
import com.kokkoro.clanbattle.overlay.OverlayController
import com.kokkoro.clanbattle.recognition.CharacterRole

class AndroidOverlayFocusPort(
    context: Context,
    private val overlay: OverlayController,
    private val dimensions: () -> Pair<Int, Int>,
    private val roleTapSafe: () -> Boolean
) : OverlayFocusPort {
    private val appContext = context.applicationContext

    override fun acquireFocus(): Boolean = overlay.acquireGamePauseFocus()

    override fun releaseFocus(): Boolean = overlay.releaseGamePauseFocus()

    override fun sendBack(): Boolean = KokkoroAccessibilityService.instance?.sendBack() == true

    override fun tapRole(role: CharacterRole): Boolean {
        if (AppPreferences.dryRun(appContext)) return false
        if (!roleTapSafe()) return false
        val service = KokkoroAccessibilityService.instance ?: return false
        val (width, height) = dimensions()
        if (width <= 0 || height <= 0) return false
        val point = ActionCoordinates.role(role)
        return service.tap(point.x * width / 1920f, point.y * height / 1080f)
    }
}
