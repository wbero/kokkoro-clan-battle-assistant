package com.kokkoro.clanbattle

import android.app.Activity
import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.kokkoro.clanbattle.axis.AndroidAxisRepository
import com.kokkoro.clanbattle.axis.AxisLibrary
import com.kokkoro.clanbattle.axis.AxisParser
import com.kokkoro.clanbattle.axis.AxisValidator
import com.kokkoro.clanbattle.axis.SwitchAxisVisualDraft
import com.kokkoro.clanbattle.axis.VisualAxisTime
import com.kokkoro.clanbattle.axis.VisualSwitchNode
import com.kokkoro.clanbattle.axis.VisualSwitchTarget
import com.kokkoro.clanbattle.axis.VisualSwitchTrigger
import com.kokkoro.clanbattle.character.AndroidCharacterLibrary
import com.kokkoro.clanbattle.character.CharacterLibraryEntry
import com.kokkoro.clanbattle.character.CharacterPickerDialog
import com.kokkoro.clanbattle.character.CharacterSelection
import com.kokkoro.clanbattle.config.AppPreferences
import com.kokkoro.clanbattle.ui.UiKit

class SwitchAxisEditorActivity : Activity() {
    private data class RoleSlot(
        val name: String,
        val ubName: String,
        val entry: CharacterLibraryEntry? = null,
        val sixStar: Boolean = false
    )

    private lateinit var library: AxisLibrary
    private val characterLibrary by lazy { AndroidCharacterLibrary.load(this) }
    private lateinit var nameInput: EditText
    private lateinit var roleButtons: List<Button>
    private lateinit var headerRoleCells: List<TextView>
    private lateinit var table: LinearLayout
    private lateinit var openingRow: GridRow
    private val roleSlots = MutableList(5) { index -> RoleSlot("角色${index + 1}", "") }
    private val rows = mutableListOf<GridRow>()
    private var editingAxisId: String? = null
    private var initialText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "表格制轴"
        window.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        library = AxisLibrary(AndroidAxisRepository(this))
        editingAxisId = intent.getStringExtra(EXTRA_AXIS_ID)
        val draft = loadDraft() ?: return
        initialText = draft.toStandardText()
        applyDraft(draft)
        setContentView(buildContent(draft))
    }

    @Deprecated("Uses the platform activity API used by the rest of the app")
    override fun onBackPressed() {
        val current = buildDraft(false)?.toStandardText()
        if (current == null || current == initialText) return super.onBackPressed()
        AlertDialog.Builder(this)
            .setTitle("放弃未保存修改？")
            .setMessage("返回后，本次表格编辑内容不会保存。")
            .setNegativeButton("继续编辑", null)
            .setPositiveButton("放弃") { _, _ -> super.onBackPressed() }
            .show()
    }

    private fun loadDraft(): SwitchAxisVisualDraft? {
        val id = editingAxisId ?: return SwitchAxisVisualDraft()
        val text = library.text(id)
        val draft = text?.let { runCatching { SwitchAxisVisualDraft.from(AxisParser.parse(it)) }.getOrNull() }
        if (draft == null) {
            Toast.makeText(this, "该轴无法转换为表格格式，请使用源码编辑", Toast.LENGTH_LONG).show()
            finish()
            return null
        }
        return draft
    }

    private fun chooseRole(index: Int) {
        CharacterPickerDialog.show(this, characterLibrary, roleSlots[index].name.takeUnless { it.startsWith("角色") }.orEmpty()) { selection ->
            if (roleSlots.withIndex().any { (otherIndex, slot) -> otherIndex != index && slot.entry?.unitId == selection.entry.unitId }) {
                Toast.makeText(this, "同一队伍不能重复选择同一角色", Toast.LENGTH_SHORT).show()
                return@show
            }
            val knownUbName = selection.ubName
            if (!knownUbName.isNullOrBlank()) {
                applyRoleSelection(index, selection, knownUbName)
                return@show
            }

            val ubInput = edit("游戏内显示的UB技能名", "")
            val dialog = AlertDialog.Builder(this)
                .setTitle("${selection.entry.name} · 补充UB技能名")
                .setMessage("角色已收录，但当前数据库没有该角色的UB数据。填写一次游戏内显示的UB技能名即可用于本轴识别。")
                .setView(ubInput)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val ubName = ubInput.text.toString().trim()
                    if (ubName.isBlank()) {
                        ubInput.error = "UB技能名不能为空"
                        return@setOnClickListener
                    }
                    applyRoleSelection(index, selection, ubName)
                    dialog.dismiss()
                }
            }
            dialog.show()
        }
    }

    private fun applyRoleSelection(index: Int, selection: CharacterSelection, ubName: String) {
        roleSlots[index] = RoleSlot(selection.entry.name, ubName, selection.entry, selection.sixStar)
        updateRoleButton(index)
        if (::headerRoleCells.isInitialized) headerRoleCells[index].text = selection.entry.name
    }

    private fun updateRoleButton(index: Int) {
        val slot = roleSlots[index]
        roleButtons[index].apply {
            text = buildString {
                append(slot.name)
                if (slot.entry?.ubPlus != null) append(if (slot.sixStar) "\n6★" else "\n普通UB")
                else if (slot.ubName.isNotBlank()) append("\nUB已配置")
            }
            setCompoundDrawables(null, null, null, null)
            slot.entry?.iconAsset?.let { path ->
                runCatching { assets.open(path).use(BitmapFactory::decodeStream) }.getOrNull()?.let { bitmap ->
                    val size = dp(48)
                    val drawable = BitmapDrawable(resources, bitmap).apply { setBounds(0, 0, size, size) }
                    setCompoundDrawables(null, drawable, null, null)
                    compoundDrawablePadding = dp(2)
                }
            }
        }
    }

    private fun applyDraft(draft: SwitchAxisVisualDraft) {
        draft.roleNames.forEachIndexed { index, name ->
            val ubName = draft.roleUbSkillNames[index]
            val entry = characterLibrary.search(name, 100).firstOrNull { candidate ->
                candidate.name == name || name in candidate.aliases
            }
            roleSlots[index] = RoleSlot(
                name = name,
                ubName = ubName,
                entry = entry,
                sixStar = entry?.ubPlus?.name == ubName && ubName.isNotBlank()
            )
        }
    }

    private fun buildContent(draft: SwitchAxisVisualDraft) = ScrollView(this).apply {
        addView(LinearLayout(this@SwitchAxisEditorActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(12), dp(6), dp(28))

            addView(UiKit.pageHeader(this@SwitchAxisEditorActivity, "开关轴表格编辑器") {
                onBackPressedDispatcherCompat()
            })
            addView(note("共用表头，勾选表示 SET/AUTO 开。角色从角色库选择并自动带入头像、昵称与UB名；点击每行时间格可设置定时、角色 UB 后、Boss 延迟或卡帧。"))
            nameInput = edit("轴名称", draft.name)
            addView(nameInput, matchWidth())

            addView(note("五个角色都从角色库选择后会自动写入角色NUB，运行时直接启用UB技能名识别。六星角色可选择普通UB或UB+。旧轴如果不重新选角色，会保留原有角色名和UB配置。"))
            roleButtons = (0 until 5).map { index ->
                Button(this@SwitchAxisEditorActivity).apply {
                    isAllCaps = false
                    textSize = 11f
                    minWidth = 0
                    setPadding(dp(2), dp(4), dp(2), dp(4))
                    setOnClickListener { chooseRole(index) }
                }
            }
            addView(LinearLayout(this@SwitchAxisEditorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                roleButtons.forEach { addView(it, LinearLayout.LayoutParams(0, dp(92), 1f)) }
            }, matchWidth())
            roleButtons.indices.forEach(::updateRoleButton)

            table = LinearLayout(this@SwitchAxisEditorActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                addView(headerRow())
            }
            openingRow = GridRow(null, draft.opening, draft.openingMessage, deletable = false)
            table.addView(openingRow.view)
            draft.nodes.forEach { addRow(it) }
            addView(table, matchWidth())

            addView(button("＋ 新增行") {
                val previous = rows.lastOrNull()?.target() ?: openingRow.target()
                addRow(VisualSwitchNode(90, target = previous))
            })
            addView(button("预览标准轴文本") { preview() })
            addView(button("保存") { save(overwrite = true) })
            addView(button("另存为") { requestSaveAs() })
        }, matchWidth())
    }

    private fun headerRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(Color.rgb(225, 230, 237))
        addView(cell("时间/触发", TIME_WEIGHT))
        headerRoleCells = roleSlots.map { slot -> cell(slot.name, ROLE_WEIGHT).apply { textSize = 9f } }
        headerRoleCells.forEach(::addView)
        addView(cell("AUTO", AUTO_WEIGHT))
        addView(cell("备注", NOTE_WEIGHT))
        addView(cell("删", ACTION_WEIGHT))
    }

    private fun addRow(node: VisualSwitchNode) {
        val row = GridRow(node, deletable = true)
        rows += row
        table.addView(row.view)
    }

    private fun removeRow(row: GridRow) {
        rows.remove(row)
        table.removeView(row.view)
    }

    private fun buildDraft(showErrors: Boolean): SwitchAxisVisualDraft? {
        val nodes = rows.map { it.node(showErrors) ?: return null }
        val draft = SwitchAxisVisualDraft(
            name = nameInput.text.toString().trim().ifBlank { "未命名开关轴" },
            roleNames = roleSlots.mapIndexed { index, slot -> slot.name.trim().ifBlank { "角色${index + 1}" } },
            roleUbSkillNames = roleSlots.map { it.ubName.trim() },
            opening = openingRow.target(),
            openingMessage = openingRow.message(),
            nodes = nodes
        )
        val validation = runCatching { AxisValidator.validate(AxisParser.parse(draft.toStandardText())) }.getOrNull()
        if (validation == null || !validation.isValid) {
            if (showErrors) Toast.makeText(this, validation?.issues?.joinToString("；") { it.message }
                ?: "生成的轴文本无法解析", Toast.LENGTH_LONG).show()
            return null
        }
        return draft
    }

    private fun preview() {
        val text = buildDraft(true)?.toStandardText() ?: return
        AlertDialog.Builder(this)
            .setTitle("标准轴文本预览")
            .setView(TextView(this).apply {
                this.text = text
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(dp(18), dp(12), dp(18), dp(12))
            })
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun save(overwrite: Boolean, overrideName: String? = null) {
        if (library.isLocked()) {
            Toast.makeText(this, "战斗中已锁定，不能保存轴", Toast.LENGTH_SHORT).show()
            return
        }
        overrideName?.let(nameInput::setText)
        val draft = buildDraft(true) ?: return
        val text = draft.toStandardText()
        val sourceName = "${draft.name}.txt"
        val currentId = editingAxisId
        val saved = if (overwrite && currentId != null) library.replace(currentId, sourceName, text)
            else library.import(sourceName, text)
        if (saved == null || !saved.valid) {
            Toast.makeText(this, "保存失败：${saved?.validationMessage.orEmpty()}", Toast.LENGTH_LONG).show()
            return
        }
        library.select(saved.id)
        editingAxisId = saved.id
        initialText = text
        AppPreferences.saveAxis(this, saved.name, text)
        Toast.makeText(this, "已保存并选择：${saved.name}", Toast.LENGTH_SHORT).show()
    }

    private fun requestSaveAs() {
        val input = edit("新轴名称", "${nameInput.text}-副本")
        AlertDialog.Builder(this)
            .setTitle("另存为")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ -> save(false, input.text.toString()) }
            .show()
    }

    private inner class GridRow(node: VisualSwitchNode?, target: VisualSwitchTarget = node?.target
        ?: VisualSwitchTarget(), message: String = node?.message.orEmpty(), deletable: Boolean) {
        private var timeSeconds = node?.timeSeconds
        private var trigger = node?.trigger ?: VisualSwitchTrigger.TIMED
        private var triggerRoleIndex = node?.triggerRoleIndex ?: 0
        private var bossDelayMs = node?.bossDelayMs ?: 1_200L
        private val triggerCell: View = if (deletable) Button(this@SwitchAxisEditorActivity).apply {
            isAllCaps = false
            textSize = 10f
            minWidth = 0
            setPadding(0, 0, 0, 0)
            layoutParams = column(TIME_WEIGHT)
            setOnClickListener { showTriggerDialog() }
        } else cell("开局", TIME_WEIGHT)
        private val roleChecks = target.rolesOn.map { enabled ->
            CheckBox(this@SwitchAxisEditorActivity).apply {
                isChecked = enabled
                gravity = Gravity.CENTER
                minWidth = 0
                minHeight = 0
                setPadding(0, 0, 0, 0)
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.rgb(198, 55, 55))
                layoutParams = column(ROLE_WEIGHT)
            }
        }
        private val auto = CheckBox(this@SwitchAxisEditorActivity).apply {
            isChecked = target.autoOn
            gravity = Gravity.CENTER
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            layoutParams = column(AUTO_WEIGHT)
        }
        private val note = edit("备注", message).apply {
            textSize = 11f
            setPadding(dp(2), 0, dp(2), 0)
            layoutParams = column(NOTE_WEIGHT)
        }
        val view = LinearLayout(this@SwitchAxisEditorActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(if (deletable) Color.WHITE else Color.rgb(242, 245, 249))
            addView(triggerCell)
            roleChecks.forEach(::addView)
            addView(auto)
            addView(note)
            addView(if (deletable) Button(this@SwitchAxisEditorActivity).apply {
                text = "×"
                isAllCaps = false
                textSize = 18f
                minWidth = 0
                setPadding(0, 0, 0, 0)
                setOnClickListener { removeRow(this@GridRow) }
                layoutParams = column(ACTION_WEIGHT)
            } else cell("—", ACTION_WEIGHT))
            updateTriggerLabel()
        }

        fun target() = VisualSwitchTarget(roleChecks.map(CheckBox::isChecked), auto.isChecked)
        fun message() = note.text.toString().trim()
        fun node(showErrors: Boolean): VisualSwitchNode? {
            val seconds = timeSeconds
            if (seconds == null) {
                if (showErrors) Toast.makeText(this@SwitchAxisEditorActivity, "请设置节点时间", Toast.LENGTH_SHORT).show()
                return null
            }
            return VisualSwitchNode(seconds, trigger, triggerRoleIndex, bossDelayMs, target(), message())
        }

        private fun updateTriggerLabel() {
            if (triggerCell !is Button || timeSeconds == null) return
            val marker = when (trigger) {
                VisualSwitchTrigger.TIMED -> "定时"
                VisualSwitchTrigger.CHARACTER_UB -> "UB${triggerRoleIndex + 1}"
                VisualSwitchTrigger.BOSS_DELAY -> "B+${"%.2f".format(java.util.Locale.US, bossDelayMs / 1_000.0)}"
                VisualSwitchTrigger.PAUSE_FRAME -> if (triggerRoleIndex == 5) "卡AUTO" else "卡${triggerRoleIndex + 1}"
            }
            triggerCell.text = "${VisualAxisTime.format(timeSeconds!!)}\n$marker"
        }

        private fun showTriggerDialog() {
            val timeInput = edit("时间：1:16 / 76 / 116", timeSeconds?.let(VisualAxisTime::format).orEmpty())
            val typeInput = Spinner(this@SwitchAxisEditorActivity).apply {
                adapter = ArrayAdapter(this@SwitchAxisEditorActivity, android.R.layout.simple_spinner_dropdown_item,
                    listOf("普通定时", "角色 UB 后", "Boss 延迟", "卡帧"))
                setSelection(trigger.ordinal)
            }
            val roleInput = Spinner(this@SwitchAxisEditorActivity).apply {
                adapter = ArrayAdapter(this@SwitchAxisEditorActivity, android.R.layout.simple_spinner_dropdown_item,
                    (1..5).map { "角色$it" } + "AUTO")
                setSelection(triggerRoleIndex)
            }
            val delayInput = edit("Boss 延迟秒数", "%.2f".format(java.util.Locale.US, bossDelayMs / 1_000.0))
            val fields = LinearLayout(this@SwitchAxisEditorActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), 0, dp(20), 0)
                addView(timeInput, matchWidth())
                addView(typeInput, matchWidth())
                addView(roleInput, matchWidth())
                addView(delayInput, matchWidth())
            }
            fun updateFields() {
                val selected = VisualSwitchTrigger.entries[typeInput.selectedItemPosition]
                roleInput.visibility = if (selected in setOf(VisualSwitchTrigger.CHARACTER_UB, VisualSwitchTrigger.PAUSE_FRAME)) View.VISIBLE else View.GONE
                delayInput.visibility = if (selected == VisualSwitchTrigger.BOSS_DELAY) View.VISIBLE else View.GONE
            }
            typeInput.onItemSelectedListener = SimpleItemSelected(::updateFields)
            updateFields()
            val dialog = AlertDialog.Builder(this@SwitchAxisEditorActivity)
                .setTitle("设置时间和触发方式")
                .setView(fields)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val parsedTime = VisualAxisTime.parse(timeInput.text.toString())
                    if (parsedTime == null) {
                        timeInput.error = "支持 1:16、76、116，范围 0:00～1:30"
                        return@setOnClickListener
                    }
                    val selected = VisualSwitchTrigger.entries[typeInput.selectedItemPosition]
                    val parsedDelay = delayInput.text.toString().toDoubleOrNull()?.let { (it * 1_000).toLong() }
                    if (selected == VisualSwitchTrigger.BOSS_DELAY && (parsedDelay == null || parsedDelay !in 0..30_000)) {
                        delayInput.error = "延迟必须在 0～30 秒之间"
                        return@setOnClickListener
                    }
                    if (selected == VisualSwitchTrigger.CHARACTER_UB && roleInput.selectedItemPosition == 5) {
                        Toast.makeText(this@SwitchAxisEditorActivity, "角色 UB 后不能选择 AUTO", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    timeSeconds = parsedTime
                    trigger = selected
                    triggerRoleIndex = roleInput.selectedItemPosition
                    if (parsedDelay != null) bossDelayMs = parsedDelay
                    updateTriggerLabel()
                    dialog.dismiss()
                }
            }
            dialog.show()
        }
    }

    private class SimpleItemSelected(private val action: () -> Unit) : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = action()
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text; textSize = 24f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.rgb(32, 33, 36))
    }
    private fun note(text: String) = TextView(this).apply {
        this.text = text; textSize = 14f; setTextColor(Color.DKGRAY); setPadding(0, dp(6), 0, dp(8))
    }
    private fun cell(text: String, weight: Float) = TextView(this).apply {
        this.text = text; textSize = 10f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD); layoutParams = column(weight)
    }
    private fun edit(hint: String, value: String) = EditText(this).apply { this.hint = hint; setText(value); setSingleLine(true) }
    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; setOnClickListener { action() }; layoutParams = matchWidth().apply { topMargin = dp(8) }
    }
    private fun column(weight: Float) = LinearLayout.LayoutParams(0, dp(48), weight)
    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** Keep the existing unsaved-draft confirmation when the in-page back button is used. */
    private fun onBackPressedDispatcherCompat() = onBackPressed()

    companion object {
        const val EXTRA_AXIS_ID = "axis_id"
        private const val TIME_WEIGHT = 1.55f
        private const val ROLE_WEIGHT = 1f
        private const val AUTO_WEIGHT = 1.05f
        private const val NOTE_WEIGHT = 1.9f
        private const val ACTION_WEIGHT = 0.75f
    }
}
