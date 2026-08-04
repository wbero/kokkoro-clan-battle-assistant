package com.kokkoro.clanbattle.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
            val created = DebugRegionView(context)
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
            val mapped = mapCaptureRect(box.rect, current.captureWidth, current.captureHeight, width, height)
            strokePaint.color = tintColor(box.tint)
            canvas.drawRect(mapped, strokePaint)
            drawLabel(canvas, box.label, mapped.left, mapped.top - 2f * density)
        }

        current.tpBars.forEach { bar ->
            drawTpBar(canvas, current, bar)
        }
    }

    private fun drawTpBar(canvas: Canvas, current: DebugOverlayFrame, bar: DebugTpBar) {
        val mapped = mapCaptureRect(bar.rect, current.captureWidth, current.captureHeight, width, height)
        // ROI 本身用细框标出，方便确认能量条裁剪位置是否对齐。
        strokePaint.color = 0x88ffffff.toInt()
        canvas.drawRect(mapped, strokePaint)

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
