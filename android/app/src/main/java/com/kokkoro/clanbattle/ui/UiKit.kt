package com.kokkoro.clanbattle.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** 全应用共用的颜色令牌与代码构建控件工厂；本项目不引入 AndroidX，样式全部在此集中。 */
object UiKit {
    val TEXT_PRIMARY = Color.rgb(32, 33, 36)
    val TEXT_SECONDARY = Color.rgb(95, 100, 104)
    val ACCENT = Color.rgb(25, 118, 210)
    val ACCENT_DARK = Color.rgb(17, 82, 147)
    val SUCCESS = Color.rgb(27, 94, 32)
    val ERROR = Color.rgb(183, 28, 28)
    val WARNING = Color.rgb(141, 90, 0)
    val CARD_FILL = Color.argb(0xF2, 0xFF, 0xFF, 0xFF)
    val CARD_STROKE = Color.rgb(220, 230, 245)
    val DIVIDER = Color.rgb(228, 234, 242)
    private val DISABLED_FILL = Color.rgb(224, 228, 234)
    private val DISABLED_TEXT = Color.rgb(148, 154, 162)

    fun cardBackground(context: Context): GradientDrawable = GradientDrawable().apply {
        setColor(CARD_FILL)
        cornerRadius = dp(context, 12).toFloat()
        setStroke(dp(context, 1), CARD_STROKE)
    }

    /** 白色圆角卡片容器，垂直排列，自带内边距。 */
    fun card(context: Context, paddingDp: Int = 14): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = cardBackground(context)
        val padding = dp(context, paddingDp)
        setPadding(padding, padding, padding, padding)
    }

    /** 主操作按钮：强调色填充、白字、按压波纹。 */
    fun primaryButton(context: Context, label: String, onClick: () -> Unit): Button =
        flatButton(context, label, ACCENT, Color.WHITE, strokeColor = null, onClick = onClick)

    /** 次级操作按钮：浅底描边、强调色文字。 */
    fun secondaryButton(context: Context, label: String, onClick: () -> Unit): Button =
        flatButton(context, label, Color.WHITE, ACCENT_DARK, strokeColor = CARD_STROKE, onClick = onClick)

    private fun flatButton(
        context: Context,
        label: String,
        fillColor: Int,
        textColor: Int,
        strokeColor: Int?,
        onClick: () -> Unit
    ): Button = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        stateListAnimator = null
        minHeight = dp(context, 46)
        minimumHeight = dp(context, 46)
        setTextColor(disabledAwareColors(textColor))
        background = rippleOver(context, fillColor, strokeColor)
        setPadding(dp(context, 16), 0, dp(context, 16), 0)
        setOnClickListener { onClick() }
    }

    /** 小圆角标签，用于“使用中”“顺序/开关”“无效”等状态。 */
    fun chip(context: Context, label: String, color: Int): TextView = TextView(context).apply {
        text = label
        textSize = 11f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(color)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(Color.argb(0x1E, Color.red(color), Color.green(color), Color.blue(color)))
            cornerRadius = dp(context, 10).toFloat()
        }
        setPadding(dp(context, 8), dp(context, 2), dp(context, 8), dp(context, 2))
    }

    /** 1dp 分隔线。 */
    fun hairline(context: Context) = android.view.View(context).apply {
        setBackgroundColor(DIVIDER)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            maxOf(1, dp(context, 1))
        )
    }

    private fun rippleOver(context: Context, fillColor: Int, strokeColor: Int?): RippleDrawable {
        fun rounded(color: Int, stroke: Int?) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(context, 10).toFloat()
            stroke?.let { setStroke(dp(context, 1), it) }
        }
        val content = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), rounded(DISABLED_FILL, null))
            addState(intArrayOf(), rounded(fillColor, strokeColor))
        }
        val ripple = ColorStateList.valueOf(Color.argb(0x33, 0, 0, 0))
        return RippleDrawable(ripple, content, rounded(Color.WHITE, null))
    }

    private fun disabledAwareColors(enabledColor: Int) = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(DISABLED_TEXT, enabledColor)
    )

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
