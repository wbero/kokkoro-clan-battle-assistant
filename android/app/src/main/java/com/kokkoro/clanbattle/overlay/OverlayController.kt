package com.kokkoro.clanbattle.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class OverlayController(
    private val context: Context,
    private val onPrepareBattle: () -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var textView: TextView? = null
    private var rootView: LinearLayout? = null

    fun show() {
        if (!Settings.canDrawOverlays(context) || textView != null) return
        mainHandler.post {
            if (textView != null || !Settings.canDrawOverlays(context)) return@post
            val status = TextView(context).apply {
                text = "可可萝助手：等待横屏"
                setTextColor(Color.WHITE)
                setBackgroundColor(0xcc202124.toInt())
                textSize = 15f
                setPadding(dp(10), dp(6), dp(10), dp(6))
            }
            val reset = Button(context).apply {
                text = "重置"
                isAllCaps = false
                textSize = 13f
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener { onPrepareBattle() }
            }
            val view = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(status)
                addView(reset, LinearLayout.LayoutParams(dp(72), dp(42)))
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dp(16)
                y = dp(100)
            }
            windowManager.addView(view, params)
            rootView = view
            textView = status
        }
    }

    fun update(text: String, success: Boolean) {
        mainHandler.post {
            textView?.apply {
                this.text = text
                setBackgroundColor(if (success) 0xcc1b5e20.toInt() else 0xcc8e0000.toInt())
            }
        }
    }

    fun hide() {
        mainHandler.post {
            rootView?.let { runCatching { windowManager.removeView(it) } }
            rootView = null
            textView = null
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
