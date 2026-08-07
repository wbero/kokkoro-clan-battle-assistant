package com.kokkoro.clanbattle.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class GestureDispatchStatus {
    COMPLETED,
    CANCELLED,
    REJECTED,
    SERVICE_UNAVAILABLE,
    SUBMISSION_TIMEOUT
}

class KokkoroAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var connected = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        registry.connect(this)
        Log.i(LOG_TAG, "connected instance=${System.identityHashCode(this)}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() {
        Log.w(LOG_TAG, "interrupted instance=${System.identityHashCode(this)}")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect("unbind")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect("destroy")
        super.onDestroy()
    }

    fun tap(
        x: Float,
        y: Float,
        durationMs: Long = 20L,
        resultCallback: (GestureDispatchStatus) -> Unit = {}
    ): Boolean {
        if (!isCurrentConnection()) {
            resultCallback(GestureDispatchStatus.SERVICE_UNAVAILABLE)
            return false
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return dispatchTap(x, y, durationMs, resultCallback)
        }

        val submitted = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val posted = mainHandler.post {
            submitted.set(dispatchTap(x, y, durationMs, resultCallback))
            latch.countDown()
        }
        if (!posted || !latch.await(SUBMISSION_WAIT_MS, TimeUnit.MILLISECONDS)) {
            disconnect("gesture-submission-timeout")
            resultCallback(GestureDispatchStatus.SUBMISSION_TIMEOUT)
            return false
        }
        return submitted.get()
    }

    private fun dispatchTap(
        x: Float,
        y: Float,
        durationMs: Long,
        resultCallback: (GestureDispatchStatus) -> Unit
    ): Boolean {
        if (!isCurrentConnection()) {
            resultCallback(GestureDispatchStatus.SERVICE_UNAVAILABLE)
            return false
        }
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.i(LOG_TAG, "gesture-completed x=$x y=$y")
                resultCallback(GestureDispatchStatus.COMPLETED)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(LOG_TAG, "gesture-cancelled x=$x y=$y")
                resultCallback(GestureDispatchStatus.CANCELLED)
            }
        }
        val submitted = dispatchGesture(gesture, callback, mainHandler)
        Log.i(
            LOG_TAG,
            "gesture-submitted=$submitted x=$x y=$y instance=${System.identityHashCode(this)}"
        )
        if (!submitted) {
            disconnect("gesture-rejected")
            resultCallback(GestureDispatchStatus.REJECTED)
        }
        return submitted
    }

    fun sendBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    private fun isCurrentConnection(): Boolean =
        connected && registry.current() === this

    private fun disconnect(reason: String) {
        connected = false
        registry.disconnect(this)
        Log.w(LOG_TAG, "$reason instance=${System.identityHashCode(this)}")
    }

    companion object {
        private const val LOG_TAG = "KokkoroAccessibility"
        private const val SUBMISSION_WAIT_MS = 500L
        private val registry = AccessibilityServiceRegistry<KokkoroAccessibilityService>()

        val instance: KokkoroAccessibilityService?
            get() = registry.current()?.takeIf { it.connected }

        fun isConnected(): Boolean = instance != null
    }
}
