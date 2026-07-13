package com.kokkoro.clanbattle.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.kokkoro.clanbattle.axis.StoredAxis

data class OverlayActions(
    val selectAxis: (String) -> Unit,
    val nextFrame: () -> Unit,
    val confirm: () -> Unit,
    val safetyMenu: () -> Unit,
    val reset: () -> Unit
)

class OverlayController(
    private val context: Context,
    private val axesProvider: () -> List<StoredAxis>,
    private val actions: OverlayActions
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var rootView: LinearLayout? = null
    private var rootParams: WindowManager.LayoutParams? = null
    private var axisPanel: LinearLayout? = null
    private var selectAxisButton: Button? = null
    private var nextFrameButton: Button? = null
    private var confirmButton: Button? = null
    private var safetyButton: Button? = null
    private var resetButton: Button? = null
    private var statusTextView: TextView? = null
    private var currentActionView: TextView? = null
    private var nextActionView: TextView? = null
    private var currentState = OverlayUiState.idle(null)

    fun show() {
        if (!Settings.canDrawOverlays(context) || rootView != null) return
        mainHandler.post {
            if (rootView != null || !Settings.canDrawOverlays(context)) return@post
            val firstRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(button().also { selectAxisButton = it }.apply {
                    setOnClickListener { toggleAxisPanel() }
                }, weighted())
                addView(button().also { safetyButton = it }.apply {
                    setOnClickListener { actions.safetyMenu() }
                }, compact())
                addView(button().also { resetButton = it }.apply {
                    setOnClickListener { actions.reset() }
                }, compact())
            }
            val secondRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(button().also { nextFrameButton = it }.apply {
                    setOnClickListener { actions.nextFrame() }
                }, weighted())
                addView(button().also { confirmButton = it }.apply {
                    setOnClickListener { actions.confirm() }
                }, weighted())
            }
            val view = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
                setBackgroundColor(0xdd202124.toInt())
                addView(label().also { statusTextView = it }, matchWidth())
                addView(label().also { currentActionView = it }, matchWidth())
                addView(label(0xffb3e5fc.toInt()).also { nextActionView = it }, matchWidth())
                addView(firstRow, matchWidth())
                addView(secondRow, matchWidth())
            }
            val params = overlayParams(dp(16), dp(100))
            windowManager.addView(view, params)
            rootView = view
            rootParams = params
            applyState(currentState)
        }
    }

    fun render(state: OverlayUiState) {
        currentState = state
        mainHandler.post { applyState(state) }
    }

    fun hide() {
        mainHandler.post {
            hideAxisPanel()
            rootView?.let { runCatching { windowManager.removeView(it) } }
            rootView = null
            rootParams = null
            selectAxisButton = null
            nextFrameButton = null
            confirmButton = null
            safetyButton = null
            resetButton = null
            statusTextView = null
            currentActionView = null
            nextActionView = null
        }
    }

    fun acquireGamePauseFocus(): Boolean {
        hideAxisPanel()
        val view = rootView ?: return false
        val params = rootParams ?: return false
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        return runCatching {
            windowManager.updateViewLayout(view, params)
            view.requestFocus()
        }.getOrDefault(false)
    }

    fun releaseGamePauseFocus(): Boolean {
        val view = rootView ?: return false
        val params = rootParams ?: return false
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        return runCatching {
            view.clearFocus()
            windowManager.updateViewLayout(view, params)
            true
        }.getOrDefault(false)
    }

    private fun applyState(state: OverlayUiState) {
        rootView?.setBackgroundColor(
            when (state.panelColor) {
                OverlayPanelColor.GRAY -> 0xdd3c4043.toInt()
                OverlayPanelColor.GREEN -> 0xdd1b5e20.toInt()
                OverlayPanelColor.AMBER -> 0xdd8d5a00.toInt()
                OverlayPanelColor.RED -> 0xdd8e0000.toInt()
            }
        )
        apply(selectAxisButton, state.selectAxis)
        apply(nextFrameButton, state.nextFrame)
        apply(confirmButton, state.confirm)
        apply(safetyButton, state.safetyMenu)
        apply(resetButton, state.reset)
        statusTextView?.text = state.statusText
        currentActionView?.text = state.currentAction
        nextActionView?.text = state.nextAction
        if (!state.selectAxis.enabled) hideAxisPanel()
    }

    private fun apply(button: Button?, state: OverlayButtonState) {
        button ?: return
        button.text = state.label
        button.isEnabled = state.enabled
        button.alpha = if (state.enabled) 1f else 0.45f
    }

    private fun toggleAxisPanel() {
        if (axisPanel == null) showAxisPanel() else hideAxisPanel()
    }

    private fun showAxisPanel() {
        if (!currentState.selectAxis.enabled || axisPanel != null) return
        val validAxes = axesProvider().filter { it.valid }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setBackgroundColor(0xee303134.toInt())
        }
        if (validAxes.isEmpty()) {
            panel.addView(button("没有已导入的有效轴").apply { isEnabled = false }, matchWidth())
        } else {
            validAxes.forEach { axis ->
                panel.addView(button("${axis.name} [${axis.type}]").apply {
                    setOnClickListener {
                        actions.selectAxis(axis.id)
                        hideAxisPanel()
                    }
                }, matchWidth())
            }
        }
        windowManager.addView(panel, overlayParams(dp(16), dp(196)))
        axisPanel = panel
    }

    private fun hideAxisPanel() {
        axisPanel?.let { runCatching { windowManager.removeView(it) } }
        axisPanel = null
    }

    private fun button(label: String = "") = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        minWidth = 0
        minimumWidth = 0
        setTextColor(Color.WHITE)
        setPadding(dp(6), 0, dp(6), 0)
    }

    private fun label(color: Int = Color.WHITE) = TextView(context).apply {
        setTextColor(color)
        textSize = 12f
        setPadding(dp(6), dp(2), dp(6), dp(2))
    }

    private fun overlayParams(x: Int, y: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        this.x = x
        this.y = y
    }

    private fun weighted() = LinearLayout.LayoutParams(0, dp(40), 1f)
    private fun compact() = LinearLayout.LayoutParams(dp(84), dp(40))
    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
