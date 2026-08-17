package com.kokkoro.clanbattle.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** 调试框的语义状态，只影响描边颜色。 */
enum class DebugBoxTint { NEUTRAL, ON, OFF, UNKNOWN, ALERT }

/**
 * 一个识别区域的可视化描述。[rect] 使用截图像素坐标，与识别器实际裁剪的矩形完全一致。
 */
data class DebugRegionBox(
    val label: String,
    val rect: Rect,
    val tint: DebugBoxTint = DebugBoxTint.NEUTRAL
)

/**
 * 单个角色的 TP 刻度。[rect] 是该角色能量条在截图中的矩形，横杠画在它的正下方。
 */
data class DebugTpBar(
    val label: String,
    val rect: Rect,
    val ratio: Float,
    val full: Boolean,
    val triggered: Boolean
)

/**
 * 一帧的调试叠加内容。[captureWidth]/[captureHeight] 是识别用截图的像素尺寸，
 * 叠加层据此把截图坐标换算到自身窗口坐标。
 */
data class DebugOverlayFrame(
    val captureWidth: Int,
    val captureHeight: Int,
    val boxes: List<DebugRegionBox> = emptyList(),
    val tpBars: List<DebugTpBar> = emptyList(),
    val fullThreshold: Float = 0.97f,
    val dropThreshold: Float = 0.30f
)

data class ScaledBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * 截图像素坐标 → 叠加窗口坐标。两者通常同尺寸，但系统装饰或投屏缩放
 * 可能让它们不一致，因此始终按比例换算。纯计算，便于单元测试。
 */
fun scaleCaptureBox(
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    captureWidth: Int,
    captureHeight: Int,
    viewWidth: Int,
    viewHeight: Int
): ScaledBox {
    if (captureWidth <= 0 || captureHeight <= 0) return ScaledBox(0f, 0f, 0f, 0f)
    val scaleX = viewWidth.toFloat() / captureWidth
    val scaleY = viewHeight.toFloat() / captureHeight
    return ScaledBox(left * scaleX, top * scaleY, right * scaleX, bottom * scaleY)
}

/**
 * 截图像素坐标 → 真实显示屏坐标 → Overlay View 本地坐标。
 *
 * MediaProjection 使用完整显示区域；有刘海、挖孔或系统安全区时，
 * TYPE_APPLICATION_OVERLAY 的 View 可能被系统缩进。如果继续按 View 自身
 * 宽高缩放，会让调试框整体压缩/平移，而实际识别 ROI 并没有变化。
 */
fun scaleCaptureBoxToOverlay(
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    captureWidth: Int,
    captureHeight: Int,
    displayWidth: Int,
    displayHeight: Int,
    overlayScreenX: Int,
    overlayScreenY: Int
): ScaledBox {
    if (captureWidth <= 0 || captureHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) {
        return ScaledBox(0f, 0f, 0f, 0f)
    }
    val scaleX = displayWidth.toFloat() / captureWidth
    val scaleY = displayHeight.toFloat() / captureHeight
    return ScaledBox(
        left * scaleX - overlayScreenX,
        top * scaleY - overlayScreenY,
        right * scaleX - overlayScreenX,
        bottom * scaleY - overlayScreenY
    )
}

fun mapCaptureRect(
    rect: Rect,
    captureWidth: Int,
    captureHeight: Int,
    viewWidth: Int,
    viewHeight: Int
): RectF {
    val box = scaleCaptureBox(
        rect.left, rect.top, rect.right, rect.bottom,
        captureWidth, captureHeight, viewWidth, viewHeight
    )
    return RectF(box.left, box.top, box.right, box.bottom)
}

/** TP 横杠的填充宽度：比例超出 0~1 时钳制，避免异常值画到框外。 */
fun tpBarFillWidth(barWidth: Float, ratio: Float): Float =
    barWidth * ratio.coerceIn(0f, 1f)

fun debugOutlineBox(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    strokeWidth: Float,
    margin: Float = 1f
): ScaledBox {
    val outset = strokeWidth.coerceAtLeast(0f) / 2f + margin.coerceAtLeast(0f)
    return ScaledBox(
        left - outset,
        top - outset,
        right + outset,
        bottom + outset
    )
}

/**
 * 调试框必须画在识别 ROI 外侧。
 *
 * MediaProjection 会把 TYPE_APPLICATION_OVERLAY 一并录进截图。如果描边路径正好落在
 * ROI 边界上，STROKE 有一半像素会进入 ROI，下一帧模板匹配就会把调试框本身当成游戏
 * 画面。这里把路径向外扩半个描边宽度再留 [margin]，保证描边的内边缘也不进入 ROI。
 */
fun debugOutlineRect(rect: RectF, strokeWidth: Float, margin: Float = 1f): RectF {
    val box = debugOutlineBox(
        rect.left,
        rect.top,
        rect.right,
        rect.bottom,
        strokeWidth,
        margin
    )
    return RectF(box.left, box.top, box.right, box.bottom)
}

/**
 * 调试用识别区域叠加层：在游戏画面上层描出每个识别 ROI，并在 TP 条下方画出
 * 当前识别到的能量刻度。窗口不可触摸，不会拦截游戏操作。
 */
class DebugRegionOverlay(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: DebugRegionView? = null
    @Volatile private var latest: DebugOverlayFrame? = null

    fun show() {
        if (!Settings.canDrawOverlays(context)) return
        mainHandler.post {
            if (view != null || !Settings.canDrawOverlays(context)) return@post
            val created = DebugRegionView(context).apply {
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                // Android 12+ may reject touch-through when a full-screen
                // TYPE_APPLICATION_OVERLAY is considered too opaque, even with
                // FLAG_NOT_TOUCHABLE. Keep the debug layer comfortably below
                // that obscuring threshold so it remains diagnostic-only.
                alpha = 0.50f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    } else {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
            }
            runCatching { windowManager.addView(created, params) }
                .onSuccess {
                    view = created
                    latest?.let(created::submit)
                }
        }
    }

    fun hide() {
        mainHandler.post {
            view?.let { runCatching { windowManager.removeView(it) } }
            view = null
        }
    }

    /** 识别线程每帧调用；实际重绘投递到主线程。 */
    fun render(frame: DebugOverlayFrame) {
        latest = frame
        mainHandler.post { view?.submit(frame) }
    }
}

private class DebugRegionView(context: Context) : View(context) {
    private var frame: DebugOverlayFrame? = null
    private val density = context.resources.displayMetrics.density
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val displayMetrics = DisplayMetrics()
    private val screenLocation = IntArray(2)

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * density
        color = Color.WHITE
    }
    private val textBackdropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xaa000000.toInt()
    }

    fun submit(next: DebugOverlayFrame) {
        frame = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val current = frame ?: return
        if (width <= 0 || height <= 0) return

        current.boxes.forEach { box ->
            val mapped = mapToOverlay(box.rect, current)
            val outline = debugOutlineRect(mapped, strokePaint.strokeWidth, density)
            strokePaint.color = tintColor(box.tint)
            canvas.drawRect(outline, strokePaint)
            drawLabel(canvas, box.label, outline.left, outline.top - 2f * density)
        }

        current.tpBars.forEach { bar ->
            drawTpBar(canvas, current, bar)
        }
    }

    private fun drawTpBar(canvas: Canvas, current: DebugOverlayFrame, bar: DebugTpBar) {
        val mapped = mapToOverlay(bar.rect, current)
        // MediaProjection 会录到 Overlay；描边必须完全留在 TP ROI 外面，不能污染下一帧。
        val outline = debugOutlineRect(mapped, strokePaint.strokeWidth, density)
        strokePaint.color = 0x88ffffff.toInt()
        canvas.drawRect(outline, strokePaint)

        val barTop = mapped.bottom + 3f * density
        val barBottom = barTop + 7f * density
        val track = RectF(mapped.left, barTop, mapped.right, barBottom)
        fillPaint.color = 0x66000000
        canvas.drawRect(track, fillPaint)

        fillPaint.color = when {
            bar.triggered -> 0xffff5252.toInt()
            bar.full -> 0xffffd740.toInt()
            else -> 0xff40c4ff.toInt()
        }
        canvas.drawRect(
            RectF(
                track.left,
                track.top,
                track.left + tpBarFillWidth(track.width(), bar.ratio),
                track.bottom
            ),
            fillPaint
        )

        strokePaint.color = 0xccffffff.toInt()
        canvas.drawRect(track, strokePaint)
        drawThresholdTick(canvas, track, current.fullThreshold, 0xffffd740.toInt())
        drawThresholdTick(canvas, track, current.dropThreshold, 0xffff5252.toInt())

        val percent = (bar.ratio.coerceIn(0f, 1f) * 100).toInt()
        drawLabel(canvas, "${bar.label} $percent%", track.left, track.bottom + 11f * density)
    }

    @Suppress("DEPRECATION")
    private fun mapToOverlay(rect: Rect, current: DebugOverlayFrame): RectF {
        getLocationOnScreen(screenLocation)
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        val mapped = scaleCaptureBoxToOverlay(
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom,
            captureWidth = current.captureWidth,
            captureHeight = current.captureHeight,
            displayWidth = displayMetrics.widthPixels,
            displayHeight = displayMetrics.heightPixels,
            overlayScreenX = screenLocation[0],
            overlayScreenY = screenLocation[1]
        )
        return RectF(mapped.left, mapped.top, mapped.right, mapped.bottom)
    }

    private fun drawThresholdTick(canvas: Canvas, track: RectF, threshold: Float, color: Int) {
        val x = track.left + tpBarFillWidth(track.width(), threshold)
        strokePaint.color = color
        canvas.drawLine(x, track.top - 2f * density, x, track.bottom + 2f * density, strokePaint)
    }

    private fun drawLabel(canvas: Canvas, text: String, x: Float, baseline: Float) {
        val textWidth = textPaint.measureText(text)
        canvas.drawRect(
            x - 1f * density,
            baseline - textPaint.textSize,
            x + textWidth + 2f * density,
            baseline + 2f * density,
            textBackdropPaint
        )
        canvas.drawText(text, x, baseline, textPaint)
    }

    private fun tintColor(tint: DebugBoxTint): Int = when (tint) {
        DebugBoxTint.NEUTRAL -> 0xff80d8ff.toInt()
        DebugBoxTint.ON -> 0xff69f0ae.toInt()
        DebugBoxTint.OFF -> 0xffb0bec5.toInt()
        DebugBoxTint.UNKNOWN -> 0xffffab40.toInt()
        DebugBoxTint.ALERT -> 0xffff5252.toInt()
    }
}
