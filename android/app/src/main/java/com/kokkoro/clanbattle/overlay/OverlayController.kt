package com.kokkoro.clanbattle.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.kokkoro.clanbattle.R
import com.kokkoro.clanbattle.axis.StoredAxis
import com.kokkoro.clanbattle.config.AppPreferences
import kotlin.math.abs
import kotlin.math.roundToInt

data class OverlayPosition(val x: Int, val y: Int)

private const val MIN_PANEL_SCALE = 0.60f
private const val MAX_PANEL_SCALE = 1.25f

/** 点按缩放按钮时循环的预设档位。 */
val PANEL_SCALE_PRESETS = listOf(0.60f, 0.72f, 0.85f, 1.00f, 1.15f)

fun nextPanelScale(current: Float, presets: List<Float> = PANEL_SCALE_PRESETS): Float =
    presets.firstOrNull { it > current + 0.01f } ?: presets.first()

fun panelScaleLabel(scale: Float): String = "缩放${(scale * 100).roundToInt()}%"

fun resizedOverlayScale(
    startScale: Float,
    deltaX: Int,
    deltaY: Int,
    fullScaleDragPixels: Float
): Float {
    if (fullScaleDragPixels <= 0f) return startScale.coerceIn(MIN_PANEL_SCALE, MAX_PANEL_SCALE)
    val drag = (deltaX + deltaY) / 2f
    return (startScale + drag / fullScaleDragPixels).coerceIn(MIN_PANEL_SCALE, MAX_PANEL_SCALE)
}

fun boundedOverlayPosition(
    startX: Int,
    startY: Int,
    deltaX: Int,
    deltaY: Int,
    screenWidth: Int,
    screenHeight: Int,
    itemWidth: Int,
    itemHeight: Int = itemWidth
) = OverlayPosition(
    x = (startX + deltaX).coerceIn(0, maxOf(0, screenWidth - itemWidth)),
    y = (startY + deltaY).coerceIn(0, maxOf(0, screenHeight - itemHeight))
)

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
    private var rootView: ViewGroup? = null
    private var panelColumn: LinearLayout? = null
    private var rootParams: WindowManager.LayoutParams? = null
    private var axisPanel: LinearLayout? = null
    private var axisPanelParams: WindowManager.LayoutParams? = null
    private var selectAxisButton: Button? = null
    private var nextFrameButton: Button? = null
    private var confirmButton: Button? = null
    private var safetyButton: Button? = null
    private var minimizeButton: Button? = null
    private var resetButton: Button? = null
    private var minimizedIcon: ImageButton? = null
    private var minimizedX: Int? = AppPreferences.overlayMinimizedX(context)
    private var minimizedY: Int? = AppPreferences.overlayMinimizedY(context)
    private var statusTextView: TextView? = null
    private var currentActionView: TextView? = null
    private var nextActionView: TextView? = null
    private var currentState = OverlayUiState.idle(null)
    private var panelScale = AppPreferences.overlayScale(context, DEFAULT_PANEL_SCALE)
        .coerceIn(MIN_PANEL_SCALE, MAX_PANEL_SCALE)
    private var panelBackground: GradientDrawable? = null
    private var promptView: TextView? = null
    private val hidePrompt = Runnable { removePrompt() }

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
                addView(button().also { minimizeButton = it }.apply {
                    setOnClickListener { minimize() }
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
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val padding = scaledDp(4)
                setPadding(padding, padding, padding, padding)
                addView(label().also {
                    statusTextView = it
                    it.setTypeface(it.typeface, Typeface.BOLD)
                    installPanelDragHandle(it)
                }, matchWidth())
                addView(label().also { currentActionView = it; installPanelDragHandle(it) }, matchWidth())
                addView(label(0xffb3e5fc.toInt()).also {
                    nextActionView = it
                    installPanelDragHandle(it)
                }, matchWidth())
                addView(firstRow, matchWidth().apply { topMargin = scaledDp(2) })
                addView(secondRow, matchWidth().apply { topMargin = scaledDp(2) })
            }
            val view = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(0xdd202124.toInt())
                }.also { panelBackground = it }
                addView(column)
                addView(resizeGrip(), FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END
                ))
            }
            val params = overlayParams(
                AppPreferences.overlayX(context, dp(16)),
                AppPreferences.overlayY(context, dp(100))
            )
            windowManager.addView(view, params)
            rootView = view
            panelColumn = column
            rootParams = params
            applyPanelScale()
            applyState(currentState)
        }
    }

    /** 右下角常驻缩放把手：按住自由拖拽缩放，点按在预设档位间切换；不随面板缩放，保证始终可抓。 */
    private fun resizeGrip() = TextView(context).apply {
        text = "◢"
        textSize = 14f
        includeFontPadding = false
        setTextColor(0x99ffffff.toInt())
        setPadding(dp(10), dp(10), dp(3), dp(3))
        contentDescription = "拖动调整面板大小"
        installPanelResizeHandle(this)
    }

    fun showPrompt(message: String) {
        if (message.isBlank() || !Settings.canDrawOverlays(context)) return
        mainHandler.post {
            removePrompt()
            val view = TextView(context).apply {
                text = message
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(8), dp(18), dp(8))
                background = GradientDrawable().apply {
                    setColor(0xdd202124.toInt())
                    cornerRadius = dp(10).toFloat()
                }
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(36)
            }
            runCatching { windowManager.addView(view, params) }
                .onSuccess {
                    promptView = view
                    mainHandler.postDelayed(hidePrompt, PROMPT_DURATION_MS)
                }
        }
    }

    fun render(state: OverlayUiState) {
        currentState = state
        mainHandler.post { applyState(state) }
    }

    fun hide() {
        mainHandler.post {
            removePrompt()
            hideAxisPanel()
            minimizedIcon?.let { runCatching { windowManager.removeView(it) } }
            minimizedIcon = null
            rootView?.let { runCatching { windowManager.removeView(it) } }
            rootView = null
            panelColumn = null
            rootParams = null
            panelBackground = null
            selectAxisButton = null
            nextFrameButton = null
            confirmButton = null
            safetyButton = null
            minimizeButton = null
            resetButton = null
            statusTextView = null
            currentActionView = null
            nextActionView = null
        }
    }

    private fun removePrompt() {
        mainHandler.removeCallbacks(hidePrompt)
        promptView?.let { runCatching { windowManager.removeView(it) } }
        promptView = null
    }

    fun acquireGamePauseFocus(): Boolean {
        hideAxisPanel()
        restoreNow()
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
        panelBackground?.setColor(
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
        apply(minimizeButton, state.minimize)
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

    private fun applyPanelScale() {
        val padding = scaledDp(4)
        panelColumn?.setPadding(padding, padding, padding, padding)
        listOfNotNull(
            selectAxisButton,
            nextFrameButton,
            confirmButton,
            safetyButton,
            minimizeButton,
            resetButton
        ).forEach { button ->
            button.textSize = 12f * panelScale
            button.setPadding(scaledDp(6), 0, scaledDp(6), 0)
        }
        selectAxisButton?.layoutParams = weighted()
        nextFrameButton?.layoutParams = weighted()
        confirmButton?.layoutParams = weighted()
        safetyButton?.layoutParams = compact()
        minimizeButton?.layoutParams = compact()
        resetButton?.layoutParams = compact()
        listOfNotNull(statusTextView, currentActionView, nextActionView).forEach { label ->
            label.textSize = 12f * panelScale
            label.maxWidth = scaledDp(420)
            label.setPadding(scaledDp(6), scaledDp(1), scaledDp(6), scaledDp(1))
        }
        statusTextView?.textSize = 13f * panelScale
        rootView?.requestLayout()
        mainHandler.post { clampPanelPosition() }
    }

    private fun installPanelResizeHandle(handle: View) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startScale = panelScale
        var resizing = false
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startScale = panelScale
                    resizing = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - downRawX).toInt()
                    val deltaY = (event.rawY - downRawY).toInt()
                    if (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop) resizing = true
                    if (resizing) {
                        panelScale = resizedOverlayScale(
                            startScale,
                            deltaX,
                            deltaY,
                            scaledDp(320).toFloat()
                        )
                        hideAxisPanel()
                        applyPanelScale()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (resizing) persistPanelState() else cyclePanelScale()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    /** 点按缩放把手：在预设档位间循环；按住拖动仍然可以连续微调。 */
    private fun cyclePanelScale() {
        panelScale = nextPanelScale(panelScale)
        hideAxisPanel()
        applyPanelScale()
        persistPanelState()
        showPrompt(panelScaleLabel(panelScale))
    }

    private fun installPanelDragHandle(handle: View) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        handle.setOnTouchListener { _, event ->
            val params = rootParams ?: return@setOnTouchListener false
            val panel = rootView ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - downRawX).toInt()
                    val deltaY = (event.rawY - downRawY).toInt()
                    if (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop) dragged = true
                    if (dragged) updatePanelPosition(panel, params, startX, startY, deltaX, deltaY)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragged) persistPanelState()
                    true
                }
                else -> false
            }
        }
    }

    private fun updatePanelPosition(
        panel: View,
        params: WindowManager.LayoutParams,
        startX: Int,
        startY: Int,
        deltaX: Int,
        deltaY: Int
    ) {
        val metrics = context.resources.displayMetrics
        val position = boundedOverlayPosition(
            startX,
            startY,
            deltaX,
            deltaY,
            metrics.widthPixels,
            metrics.heightPixels,
            panel.width.coerceAtLeast(1),
            panel.height.coerceAtLeast(1)
        )
        params.x = position.x
        params.y = position.y
        runCatching { windowManager.updateViewLayout(panel, params) }
        updateAxisPanelPosition()
    }

    private fun clampPanelPosition() {
        val panel = rootView ?: return
        val params = rootParams ?: return
        updatePanelPosition(panel, params, params.x, params.y, 0, 0)
    }

    private fun minimize() {
        mainHandler.post {
            if (minimizedIcon != null) return@post
            hideAxisPanel()
            rootView?.visibility = View.GONE
            val icon = ImageButton(context).apply {
                setImageResource(R.drawable.overlay_icon)
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = "恢复可可萝控制面板"
                setPadding(0, 0, 0, 0)
            }
            val iconSize = dp(60)
            val metrics = context.resources.displayMetrics
            val initial = boundedOverlayPosition(
                minimizedX ?: dp(16),
                minimizedY ?: dp(100),
                0,
                0,
                metrics.widthPixels,
                metrics.heightPixels,
                iconSize
            )
            val params = overlayParams(initial.x, initial.y).apply {
                width = iconSize
                height = iconSize
            }
            val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
            var downRawX = 0f
            var downRawY = 0f
            var startX = params.x
            var startY = params.y
            var dragged = false
            icon.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startX = params.x
                        startY = params.y
                        dragged = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = (event.rawX - downRawX).toInt()
                        val deltaY = (event.rawY - downRawY).toInt()
                        if (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop) dragged = true
                        val metrics = context.resources.displayMetrics
                        val position = boundedOverlayPosition(
                            startX,
                            startY,
                            deltaX,
                            deltaY,
                            metrics.widthPixels,
                            metrics.heightPixels,
                            iconSize
                        )
                        params.x = position.x
                        params.y = position.y
                        minimizedX = position.x
                        minimizedY = position.y
                        runCatching { windowManager.updateViewLayout(icon, params) }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (dragged) persistMinimizedState() else restore()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> true
                    else -> false
                }
            }
            windowManager.addView(icon, params)
            minimizedIcon = icon
        }
    }

    private fun restore() {
        mainHandler.post { restoreNow() }
    }

    private fun restoreNow() {
        minimizedIcon?.let { runCatching { windowManager.removeView(it) } }
        minimizedIcon = null
        rootView?.visibility = View.VISIBLE
        applyState(currentState)
    }

    private fun showAxisPanel() {
        if (!currentState.selectAxis.enabled || axisPanel != null) return
        val validAxes = axesProvider().filter { it.valid }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0xee303134.toInt())
            }
        }
        if (validAxes.isEmpty()) {
            panel.addView(button("没有已导入的有效轴").apply { isEnabled = false }, axisRowParams())
        } else {
            validAxes.forEach { axis ->
                panel.addView(button("${axis.name} [${axis.type}]").apply {
                    setOnClickListener {
                        actions.selectAxis(axis.id)
                        hideAxisPanel()
                    }
                }, axisRowParams())
            }
        }
        val root = rootParams
        val params = overlayParams(root?.x ?: dp(16), (root?.y ?: dp(100)) + (rootView?.height ?: dp(96)))
        windowManager.addView(panel, params)
        axisPanel = panel
        axisPanelParams = params
    }

    private fun hideAxisPanel() {
        axisPanel?.let { runCatching { windowManager.removeView(it) } }
        axisPanel = null
        axisPanelParams = null
    }

    private fun updateAxisPanelPosition() {
        val panel = axisPanel ?: return
        val params = axisPanelParams ?: return
        val root = rootParams ?: return
        params.x = root.x
        params.y = root.y + (rootView?.height ?: 0)
        runCatching { windowManager.updateViewLayout(panel, params) }
    }

    private fun button(label: String = "") = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 12f * panelScale
        minWidth = 0
        minimumWidth = 0
        stateListAnimator = null
        setTextColor(Color.WHITE)
        background = overlayButtonBackground()
        setPadding(scaledDp(6), 0, scaledDp(6), 0)
    }

    // 深色面板上的自绘扁平按钮：平台默认浅色按钮配白字几乎不可读。
    private fun overlayButtonBackground(): RippleDrawable {
        fun rounded(color: Int) = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(color)
        }
        return RippleDrawable(
            ColorStateList.valueOf(0x55ffffff),
            rounded(0x33ffffff),
            rounded(Color.WHITE)
        )
    }

    private fun persistPanelState() {
        val params = rootParams ?: return
        AppPreferences.saveOverlayPanel(context, params.x, params.y, panelScale)
    }

    private fun persistMinimizedState() {
        val x = minimizedX ?: return
        val y = minimizedY ?: return
        AppPreferences.saveOverlayMinimized(context, x, y)
    }

    private fun label(color: Int = Color.WHITE) = TextView(context).apply {
        setTextColor(color)
        textSize = 12f * panelScale
        maxWidth = scaledDp(420)
        setPadding(scaledDp(6), scaledDp(1), scaledDp(6), scaledDp(1))
    }

    private fun overlayParams(x: Int, y: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        this.x = x
        this.y = y
    }

    private fun weighted() = LinearLayout.LayoutParams(0, scaledDp(40), 1f).apply {
        rightMargin = scaledDp(2)
    }

    private fun compact() = LinearLayout.LayoutParams(scaledDp(84), scaledDp(40)).apply {
        rightMargin = scaledDp(2)
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun axisRowParams() = matchWidth().apply { topMargin = scaledDp(2) }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
    private fun scaledDp(value: Int): Int = (value * context.resources.displayMetrics.density * panelScale).roundToInt()

    private companion object {
        const val PROMPT_DURATION_MS = 2_500L
        const val DEFAULT_PANEL_SCALE = 0.72f
    }
}
