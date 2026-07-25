package com.kokkoro.clanbattle.automation

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.widget.Toast
import com.kokkoro.clanbattle.axis.ActionType
import com.kokkoro.clanbattle.axis.AxisEvent
import com.kokkoro.clanbattle.config.AppPreferences
import com.kokkoro.clanbattle.recognition.CharacterRole
import java.util.concurrent.atomic.AtomicBoolean

class ActionExecutor(
    private val context: Context,
    private val messagePresenter: (String) -> Unit = { message ->
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
) {
    private val thread = HandlerThread("kokkoro-actions").apply { start() }
    private val handler = Handler(thread.looper)
    private val closed = AtomicBoolean(false)

    fun execute(events: List<AxisEvent>, frameWidth: Int, frameHeight: Int, clickIntervalMs: Int) {
        if (events.isEmpty()) return
        if (AppPreferences.dryRun(context)) return
        val actions = events.flatMap { it.actions }
        val commands = actions.mapNotNull { action ->
            when (action.type) {
                ActionType.CLICK_ROLE -> ActionCoordinates.role(action.role)?.let { point ->
                    QueuedCommand.Tap(point, HorizontalAnchor.CENTER)
                }
                ActionType.CLICK_AUTO ->
                    QueuedCommand.Tap(ActionCoordinates.autoButton, HorizontalAnchor.RIGHT)
                ActionType.NOTIFY -> QueuedCommand.Notify(action.message.orEmpty())
                ActionType.BOSS -> QueuedCommand.Notify("BOSS UB")
                ActionType.TOGGLE_AUTO, ActionType.SET_ROLES -> null
            }
        }
        if (commands.isEmpty()) return

        enqueue {
            var previousWasTap = false
            commands.forEach { command ->
                if (closed.get()) return@enqueue
                when (command) {
                    is QueuedCommand.Tap -> {
                        if (previousWasTap && !sleepUntilNextAction(clickIntervalMs.toLong())) {
                            return@enqueue
                        }
                        tapNow(command.point, frameWidth, frameHeight, command.anchor)
                        previousWasTap = true
                    }
                    is QueuedCommand.Notify -> showToast(command.message)
                }
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    fun tapAuto(width: Int, height: Int) =
        enqueueTap(ActionCoordinates.autoButton, width, height, HorizontalAnchor.RIGHT)

    fun tapGlobalSet(width: Int, height: Int) =
        enqueueTap(ActionCoordinates.globalSet, width, height, HorizontalAnchor.RIGHT)

    fun tapRole(role: CharacterRole, width: Int, height: Int) =
        enqueueTap(ActionCoordinates.role(role), width, height, HorizontalAnchor.CENTER)

    fun tapMenu(width: Int, height: Int) =
        enqueueTap(ActionCoordinates.menu, width, height, HorizontalAnchor.RIGHT)

    /**
     * All automatic battle gestures share one action looper. Calls return
     * immediately so frame recognition can continue, while physical gestures
     * retain their required order. The pause-frame menu has its own explicit
     * focus sequence and is intentionally not mixed into this queue.
     */
    private fun enqueueTap(point: ReferencePoint, width: Int, height: Int, anchor: HorizontalAnchor) {
        enqueue { tapNow(point, width, height, anchor) }
    }

    private fun enqueue(action: () -> Unit) {
        if (closed.get()) return
        handler.post {
            if (!closed.get()) action()
        }
    }

    private fun tapNow(point: ReferencePoint, width: Int, height: Int, anchor: HorizontalAnchor) {
        if (!AppPreferences.dryRun(context)) {
            tapScaledNow(point.x, point.y, width, height, anchor)
        }
    }

    private fun tapScaledNow(
        referenceX: Int,
        referenceY: Int,
        width: Int,
        height: Int,
        anchor: HorizontalAnchor
    ) {
        if (AppPreferences.dryRun(context)) return
        val x = GameCoordinateMapper.mapX(referenceX, width, height, anchor)
        val y = GameCoordinateMapper.mapY(referenceY, width, height)
        val dispatched = KokkoroAccessibilityService.instance?.tap(x, y) == true
        Log.i(
            ACTION_LOG_TAG,
            "tap ref=$referenceX,$referenceY mapped=$x,$y size=${width}x$height " +
                "anchor=$anchor dispatched=$dispatched thread=${Thread.currentThread().name}"
        )
    }

    private fun sleepUntilNextAction(delayMs: Long): Boolean {
        if (delayMs <= 0L) return !closed.get()
        return try {
            Thread.sleep(delayMs)
            !closed.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun showToast(message: String) {
        Handler(context.mainLooper).post { messagePresenter(message) }
    }

    private sealed interface QueuedCommand {
        data class Tap(val point: ReferencePoint, val anchor: HorizontalAnchor) : QueuedCommand
        data class Notify(val message: String) : QueuedCommand
    }

    private companion object {
        const val ACTION_LOG_TAG = "KokkoroAction"
    }

}
