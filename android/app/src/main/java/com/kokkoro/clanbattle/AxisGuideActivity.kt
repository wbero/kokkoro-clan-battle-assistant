package com.kokkoro.clanbattle

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** In-app authoring reference derived from docs/axis-format-standard.md. */
class AxisGuideActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "轴编写指南"
        setContentView(buildContent())
    }

    private fun buildContent() = ScrollView(this).apply {
        addView(LinearLayout(this@AxisGuideActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(32))

            addView(title("轴文件编写指南"))
            addView(body("本页用于帮助轴创造者写出可导入、可校验、行为明确的标准轴。完整规范来源：docs/axis-format-standard.md。"))

            addView(heading("1. 选择轴模式"))
            addView(body(
                "顺序轴：适合固定时间轴。每个动作依次进入队列；点击=角色N 会执行“SET 开启 → 等待该角色 UB → SET 关闭”的完整生命周期。\n\n" +
                    "开关轴：适合条件触发和状态切换。每个节点必须声明五名角色的完整 SET 状态与 AUTO 状态，程序只点击与目标不一致的按钮。"
            ))

            addView(heading("2. 通用书写规范"))
            addView(body(
                "• 使用 UTF-8 文本；空行忽略，# 开头为注释。\n" +
                    "• 字段使用半角 =，同一节点使用半角 | 分隔，列表使用半角逗号。\n" +
                    "• 时间必须为 M:SS，范围 0:00～1:30，例如 1:05、0:08。\n" +
                    "• SET 必须恰好包含五个“开/关”，从左到右对应角色1～角色5。\n" +
                    "• UB后=角色N、卡帧=角色N 只能使用角色1～角色5，不能使用别名。\n" +
                    "• 同一节点不能同时写 UB后 和 卡帧。\n" +
                    "• UB后=BOSS 必须同时声明 0～30 秒内的正数延迟。"
            ))

            addView(heading("3. 顺序轴标准"))
            addView(body(
                "必须填写 轴类型=顺序，并包含 [轴] 段。节点可以只写一个动作。建议在 1:30 声明开局 SET 与 AUTO。角色别名只适用于 点击=。"
            ))
            addView(code(SEQUENCE_EXAMPLE))
            addView(copyButton("复制顺序轴示例", SEQUENCE_EXAMPLE))

            addView(heading("4. 开关轴标准"))
            addView(body(
                "必须填写 轴类型=开关，并且必须且只能包含一个 [轴开局]。开局和每个后续节点都必须同时包含完整 SET 与 AUTO；开关轴不使用 [轴]。"
            ))
            addView(code(SWITCH_EXAMPLE))
            addView(copyButton("复制开关轴示例", SWITCH_EXAMPLE))

            addView(heading("5. 触发方式"))
            addView(body(
                "定时：1:20 | ...\n" +
                    "角色 UB 后：1:20 | UB后=角色3 | ...\n" +
                    "Boss 延迟：0:30 | UB后=BOSS | 延迟=1.20 | ...\n" +
                    "手动卡帧：1:10 | 卡帧=角色4 | ...\n\n" +
                    "注意：当前 UB后=BOSS 是节点到时后的最短延迟，并非 Boss UB 的视觉确认。"
            ))

            addView(heading("6. 常见校验错误"))
            addView(body(
                "• 顺序轴缺少 [轴]。\n" +
                    "• 开关轴缺少或重复 [轴开局]。\n" +
                    "• 时间写成 01:30、1:5 或超过 1:30。\n" +
                    "• SET 不是五个值，或 AUTO 不是“开/关”。\n" +
                    "• 开关轴节点没有同时填写完整 SET 与 AUTO。\n" +
                    "• UB后=BOSS 缺少延迟，或同一节点同时声明 UB后 与卡帧。"
            ))
            addView(body("推荐流程：复制标准示例 → 在应用中粘贴或导入 → 点击编辑修改 → 根据校验提示修正。"))
            addView(Button(this@AxisGuideActivity).apply {
                text = "返回"
                isAllCaps = false
                setOnClickListener { finish() }
            }, matchWidth())
        }, matchWidth())
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 24f
        setTextColor(Color.rgb(32, 33, 36))
        setPadding(0, 0, 0, dp(10))
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 19f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.rgb(38, 70, 120))
        setPadding(0, dp(18), 0, dp(8))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.rgb(55, 55, 55))
        setTextIsSelectable(true)
    }

    private fun code(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.MONOSPACE
        setTextColor(Color.rgb(30, 30, 30))
        setTextIsSelectable(true)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setBackgroundColor(Color.rgb(242, 244, 247))
    }

    private fun copyButton(label: String, example: String) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText(label, example)
            )
            Toast.makeText(this@AxisGuideActivity, "示例已复制，可返回后粘贴导入", Toast.LENGTH_SHORT).show()
        }
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val SEQUENCE_EXAMPLE = """
            # 顺序轴标准示例
            轴类型=顺序
            轴名称=顺序轴示例
            点击间隔=100
            角色1=角色A
            角色2=角色B

            [轴]
            1:30 | SET=关,关,关,关,关 | AUTO=开 | 提示=设置开局状态
            1:20 | 点击=角色1
            1:10 | UB后=角色3 | 点击=角色2
            1:00 | 卡帧=角色4 | 提示=确认动作帧
            0:45 | UB后=BOSS | 延迟=1.20 | 点击=AUTO
            0:20 | SET=开,关,开,关,关
        """.trimIndent()

        val SWITCH_EXAMPLE = """
            # 开关轴标准示例
            轴类型=开关
            轴名称=开关轴示例

            [轴开局] | SET=关,关,关,关,开 | AUTO=开 | 提示=设置开局状态
            1:12 | UB后=角色5 | SET=关,关,关,关,关 | AUTO=开
            0:57 | SET=开,关,开,开,开 | AUTO=开
            0:30 | UB后=BOSS | 延迟=1.20 | SET=关,开,关,关,开 | AUTO=开
            0:18 | 卡帧=角色3 | SET=开,关,开,关,开 | AUTO=开
        """.trimIndent()
    }
}
