package com.kokkoro.clanbattle.pauseframe

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
import com.kokkoro.clanbattle.automation.KokkoroAccessibilityService
import com.kokkoro.clanbattle.config.AppPreferences
import com.kokkoro.clanbattle.config.StandalonePauseTier
import com.kokkoro.clanbattle.overlay.boundedOverlayPosition
import com.kokkoro.clanbattle.overlay.nextPanelScale
import com.kokkoro.clanbattle.overlay.panelScaleLabel
import com.kokkoro.clanbattle.overlay.resizedOverlayScale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MIN_PANEL_SCALE = 0.60f
private const val MAX_PANEL_SCALE = 1.25f
private const val DEFAULT_PANEL_SCALE = 0.72f

/**
 * 独立卡帧悬浮窗：与主控制面板解耦，不依赖截图识别服务。
 *
 * 面板只有三个「卡N帧」按钮（各自独立的帧率与帧数），点击行为：
 * 未卡帧时先进入卡帧，已卡帧时前进 N 帧后重新卡住。支持拖动、任意缩放、
 * 最小化为小图标。位置/缩放/最小化状态独立于主面板保存。
 */
class PauseFrameOverlay private constructor(context: Context) {
    private val context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var rootView: FrameLayout? = null
    private var rootParams: WindowManager.LayoutParams? = null
    private var tierButtons = listOf<Button>()
    private var resumeButton: Button? = null
    private var minimizeButton: Button? = null
    private var handleView: TextView? = null
    private var minimizedIcon: ImageButton? = null
    private var minimizedX: Int? = AppPreferences.standalonePauseMinimizedX(context)
    private var minimizedY: Int? = AppPreferences.standalonePauseMinimizedY(context)
    private var panelScale = AppPreferences.standalonePauseScale(context, DEFAULT_PANEL_SCALE)
        .coerceIn(MIN_PANEL_SCALE, MAX_PANEL_SCALE)
    private var promptView: TextView? = null
    private val hidePrompt = Runnable { removePrompt() }

    private val session = PauseFrameSession(
        focusPort = object : OverlayFocusPort {
            override fun acquireFocus(): Boolean = acquireWindowFocus()
            override fun releaseFocus(): Boolean = releaseWindowFocus()
            override fun sendBack(): Boolean = KokkoroAccessibilityService.instance?.sendBack() == true
            override fun tapMenuRole(role: com.kokkoro.clanbattle.recognition.CharacterRole): Boolean = false
            override fun tapMenuAuto(): Boolean = false
            override fun dismissMenu(): Boolean = false
        },
        scheduler = PauseFrameScheduler { delayMs, action -> mainHandler.postDelayed(action, delayMs) },
        diagnosticCallback = { event ->
            if (event.result == "failed") {
                showPrompt("卡帧失败：${event.action}（请检查无障碍服务）")
            }
        }
    )

    fun showNow() {
        if (!Settings.canDrawOverlays(context) || rootView != null) return
        mainHandler.post {
            if (rootView != null || !Settings.canDrawOverlays(context)) return@post
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                tierButtons = (0 until 3).map { index ->
                    button("").also { tierButton ->
                        tierButton.setOnClickListener { stepTier(index) }
                        addView(tierButton, weighted())
                    }
                }
                addView(button("恢复").also { resumeButton = it }.apply {
                    contentDescription = "恢复正常战斗"
                    background = overlayResumeBackground()
                    setOnClickListener {
                        session.reset()
                        renderTierLabels()
                        showPrompt("已恢复正常战斗")
                    }
                }, compact())
                addView(button("-").also { minimizeButton = it }.apply {
                    contentDescription = "最小化卡帧悬浮窗"
                    setOnClickListener { minimize() }
                }, compact())
            }
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                // 右下角为缩放把手，预留固定空间避免遮挡最小化按钮（把手不随面板缩放）。
                val padding = scaledDp(4)
                val gripReserve = dp(34)
                setPadding(padding, padding, padding + gripReserve, padding)
                addView(TextView(context).also { handleView = it }.apply {
                    text = "卡帧悬浮窗（拖动此标题）"
                    textSize = 12f * panelScale
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    installPanelDragHandle(this)
                }, matchWidth())
                addView(row, matchWidth().apply { topMargin = scaledDp(2) })
            }
            val view = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(0xdd202124.toInt())
                }
                addView(column)
                addView(resizeGrip(), FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END
                ))
            }
            val params = overlayParams(
                AppPreferences.standalonePauseX(context, dp(16)),
                AppPreferences.standalonePauseY(context, dp(180))
            )
            windowManager.addView(view, params)
            rootView = view
            rootParams = params
            applyPanelScale()
            renderTierLabels()
        }
    }

    fun hideNow() {
        mainHandler.post {
            session.reset()
            removePrompt()
            minimizedIcon?.let { runCatching { windowManager.removeView(it) } }
            minimizedIcon = null
            rootView?.let { runCatching { windowManager.removeView(it) } }
            rootView = null
            rootParams = null
            tierButtons = emptyList()
            resumeButton = null
            minimizeButton = null
            handleView = null
        }
    }

    fun refreshTierLabels() {
        mainHandler.post { renderTierLabels() }
    }

    private fun renderTierLabels() {
        if (tierButtons.isEmpty()) return
        val tiers = AppPreferences.standalonePauseTiers(context)
        listOf(tiers.tier1, tiers.tier2, tiers.tier3).forEachIndexed { index, tier ->
            tierButtons[index].text = "卡${tier.frames}帧"
            tierButtons[index].contentDescription = "卡 ${tier.frames} 帧（每帧 ${tier.rateMs}ms）"
        }
    }

    private fun stepTier(index: Int) {
        val tiers = AppPreferences.standalonePauseTiers(context)
        val tier: StandalonePauseTier = when (index) {
            0 -> tiers.tier1
            1 -> tiers.tier2
            else -> tiers.tier3
        }
        step(tier)
    }

    private fun step(tier: StandalonePauseTier) {
        val state = session.snapshot().state
        when (state) {
            PauseFrameState.IDLE -> {
                // 首次点击：仅进入卡帧，立即抢焦点冻结游戏，不前进。
                val entered = session.enterManual()
                if (!entered.accepted) {
                    session.reset()
                }
            }
            PauseFrameState.SOFT_PAUSED -> session.release(tier.frames, tier.rateMs.toLong())
            PauseFrameState.FAILED -> {
                session.reset()
                step(tier)
            }
            else -> Unit // ADVANCING/CONFIRMING/MANUAL_MENU：上一轮尚未完成，忽略本次点击。
        }
    }

    private fun acquireWindowFocus(): Boolean {
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

    private fun releaseWindowFocus(): Boolean {
        val view = rootView ?: return false
        val params = rootParams ?: return false
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        return runCatching {
            view.clearFocus()
            windowManager.updateViewLayout(view, params)
            true
        }.getOrDefault(false)
    }

    private fun resizeGrip() = TextView(context).apply {
        text = "◢"
        textSize = 14f
        includeFontPadding = false
        setTextColor(0x99ffffff.toInt())
        setPadding(dp(10), dp(10), dp(3), dp(3))
        contentDescription = "拖动调整悬浮窗大小"
        installPanelResizeHandle(this)
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

    private fun cyclePanelScale() {
        panelScale = nextPanelScale(panelScale)
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
    }

    private fun minimize() {
        mainHandler.post {
            if (minimizedIcon != null) return@post
            session.reset()
            rootView?.visibility = View.GONE
            val icon = ImageButton(context).apply {
                setImageResource(R.drawable.overlay_icon)
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = "恢复卡帧悬浮窗"
                setPadding(0, 0, 0, 0)
            }
            val iconSize = dp(60)
            val metrics = context.resources.displayMetrics
            val initial = boundedOverlayPosition(
                minimizedX ?: dp(16),
                minimizedY ?: dp(180),
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
        mainHandler.post {
            minimizedIcon?.let { runCatching { windowManager.removeView(it) } }
            minimizedIcon = null
            rootView?.visibility = View.VISIBLE
            renderTierLabels()
        }
    }

    private fun applyPanelScale() {
        tierButtons.forEach { button ->
            button.textSize = 12f * panelScale
            button.setPadding(scaledDp(6), 0, scaledDp(6), 0)
        }
        minimizeButton?.textSize = 12f * panelScale
        resumeButton?.textSize = 12f * panelScale
        handleView?.textSize = 12f * panelScale
        rootView?.requestLayout()
        mainHandler.post { clampPanelPosition() }
    }

    private fun clampPanelPosition() {
        val panel = rootView ?: return
        val params = rootParams ?: return
        updatePanelPosition(panel, params, params.x, params.y, 0, 0)
    }

    private fun showPrompt(message: String) {
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

    private fun removePrompt() {
        mainHandler.removeCallbacks(hidePrompt)
        promptView?.let { runCatching { windowManager.removeView(it) } }
        promptView = null
    }

    private fun button(label: String = "") = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 12f * panelScale
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        stateListAnimator = null
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = overlayButtonBackground()
        setPadding(scaledDp(6), 0, scaledDp(6), 0)
    }

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

    private fun overlayResumeBackground(): RippleDrawable {
        fun rounded(color: Int) = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(color)
        }
        return RippleDrawable(
            ColorStateList.valueOf(0x55ffffff),
            rounded(0x6627ae60.toInt()),
            rounded(0xd92c7a4b.toInt())
        )
    }

    private fun persistPanelState() {
        val params = rootParams ?: return
        AppPreferences.saveStandalonePausePanel(context, params.x, params.y, panelScale)
    }

    private fun persistMinimizedState() {
        val x = minimizedX ?: return
        val y = minimizedY ?: return
        AppPreferences.saveStandalonePauseMinimized(context, x, y)
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

    private fun compact() = LinearLayout.LayoutParams(scaledDp(48), scaledDp(40)).apply {
        rightMargin = scaledDp(2)
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
    private fun scaledDp(value: Int): Int =
        (value * context.resources.displayMetrics.density * panelScale).roundToInt()

    companion object {
        @Volatile
        private var instance: PauseFrameOverlay? = null

        fun show(context: Context) {
            val overlay = instance ?: synchronized(this) {
                instance ?: PauseFrameOverlay(context).also { instance = it }
            }
            AppPreferences.setStandalonePauseEnabled(context, true)
            overlay.showNow()
        }

        fun hide(context: Context) {
            instance?.hideNow()
            AppPreferences.setStandalonePauseEnabled(context, false)
        }

        fun refreshTiers() {
            instance?.refreshTierLabels()
        }

        private const val PROMPT_DURATION_MS = 2_500L
    }
}