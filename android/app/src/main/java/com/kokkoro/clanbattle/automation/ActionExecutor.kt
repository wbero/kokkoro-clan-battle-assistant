package com.kokkoro.clanbattle.automation

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.widget.Toast
import com.kokkoro.clanbattle.axis.ActionType
import com.kokkoro.clanbattle.axis.AxisEvent
import com.kokkoro.clanbattle.config.AppPreferences
import com.kokkoro.clanbattle.recognition.CharacterRole

class ActionExecutor(private val context: Context) {
    private val thread = HandlerThread("kokkoro-actions").apply { start() }
    private val handler = Handler(thread.looper)

    fun execute(events: List<AxisEvent>, frameWidth: Int, frameHeight: Int, clickIntervalMs: Int) {
        if (events.isEmpty()) return
        if (AppPreferences.dryRun(context)) return

        handler.post {
            events.flatMap { it.actions }.forEach { action ->
                when (action.type) {
                    ActionType.CLICK_ROLE -> ActionCoordinates.role(action.role)?.let {
                        tapScaled(it.x, it.y, frameWidth, frameHeight)
                    }
                    ActionType.CLICK_AUTO -> tapAuto(frameWidth, frameHeight)
                    ActionType.TOGGLE_AUTO -> Unit
                    ActionType.NOTIFY -> showToast(action.message.orEmpty())
                    ActionType.BOSS -> showToast("BOSS UB")
                    ActionType.SET_ROLES -> Unit
                }
                Thread.sleep(clickIntervalMs.toLong())
            }
        }
    }

    fun close() {
        thread.quitSafely()
    }

    fun tapAuto(width: Int, height: Int) = tapIfEnabled(ActionCoordinates.autoButton, width, height)

    fun tapGlobalSet(width: Int, height: Int) = tapIfEnabled(ActionCoordinates.globalSet, width, height)

    fun tapRole(role: CharacterRole, width: Int, height: Int) = tapIfEnabled(ActionCoordinates.role(role), width, height)

    fun tapMenu(width: Int, height: Int) = tapIfEnabled(ActionCoordinates.menu, width, height)

    private fun tapIfEnabled(point: ReferencePoint, width: Int, height: Int) {
        if (!AppPreferences.dryRun(context)) tap(point, width, height)
    }

    private fun tap(point: ReferencePoint, width: Int, height: Int) =
        tapScaled(point.x, point.y, width, height)

    private fun tapScaled(referenceX: Int, referenceY: Int, width: Int, height: Int) {
        val x = (referenceX * width / 1920f)
        val y = (referenceY * height / 1080f)
        KokkoroAccessibilityService.instance?.tap(x, y)
    }

    private fun showToast(message: String) {
        Handler(context.mainLooper).post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

}
