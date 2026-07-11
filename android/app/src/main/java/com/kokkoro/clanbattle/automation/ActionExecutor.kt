package com.kokkoro.clanbattle.automation

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.widget.Toast
import com.kokkoro.clanbattle.axis.ActionType
import com.kokkoro.clanbattle.axis.AxisEvent
import com.kokkoro.clanbattle.config.AppPreferences

class ActionExecutor(private val context: Context) {
    private val thread = HandlerThread("kokkoro-actions").apply { start() }
    private val handler = Handler(thread.looper)
    private var autoOn = false

    fun execute(events: List<AxisEvent>, frameWidth: Int, frameHeight: Int, clickIntervalMs: Int) {
        if (events.isEmpty()) return
        if (AppPreferences.dryRun(context)) return

        handler.post {
            events.flatMap { it.actions }.forEach { action ->
                when (action.type) {
                    ActionType.CLICK_ROLE -> rolePoint(action.role)?.let { tapScaled(it.first, it.second, frameWidth, frameHeight) }
                    ActionType.CLICK_AUTO -> {
                        tapScaled(1828, 845, frameWidth, frameHeight)
                        autoOn = !autoOn
                    }
                    ActionType.TOGGLE_AUTO -> {
                        val target = action.value == "on"
                        if (target != autoOn) {
                            tapScaled(1828, 845, frameWidth, frameHeight)
                            autoOn = target
                        }
                    }
                    ActionType.NOTIFY -> showToast(action.message.orEmpty())
                    ActionType.BOSS -> showToast("BOSS UB")
                    ActionType.SET_ROLES -> action.values.indices.take(5).forEach { index ->
                        tapScaled(ROLE_X[index], 910, frameWidth, frameHeight)
                        Thread.sleep(clickIntervalMs.toLong())
                    }
                }
                Thread.sleep(clickIntervalMs.toLong())
            }
        }
    }

    fun close() {
        thread.quitSafely()
    }

    private fun rolePoint(role: String?): Pair<Int, Int>? {
        val index = ROLE_NAMES.indexOf(role)
        return if (index >= 0) ROLE_X[index] to 845 else null
    }

    private fun tapScaled(referenceX: Int, referenceY: Int, width: Int, height: Int) {
        val x = (referenceX * width / 1920f)
        val y = (referenceY * height / 1080f)
        KokkoroAccessibilityService.instance?.tap(x, y)
    }

    private fun showToast(message: String) {
        Handler(context.mainLooper).post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    private companion object {
        val ROLE_NAMES = listOf("角色5", "角色4", "角色3", "角色2", "角色1")
        val ROLE_X = listOf(482, 716, 950, 1184, 1418)
    }
}
