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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.kokkoro.clanbattle.axis.AndroidAxisRepository
import com.kokkoro.clanbattle.axis.AxisLibrary
import com.kokkoro.clanbattle.axis.AxisParser
import com.kokkoro.clanbattle.axis.AxisValidator
import com.kokkoro.clanbattle.axis.SequenceAxisVisualDraft
import com.kokkoro.clanbattle.axis.VisualAxisTime
import com.kokkoro.clanbattle.axis.VisualSequenceAction
import com.kokkoro.clanbattle.axis.VisualSequenceNode
import com.kokkoro.clanbattle.axis.VisualSequenceTrigger
import com.kokkoro.clanbattle.axis.VisualSequenceTriggerType
import com.kokkoro.clanbattle.character.AndroidCharacterLibrary
import com.kokkoro.clanbattle.character.CharacterLibraryEntry
import com.kokkoro.clanbattle.character.CharacterPickerDialog
import com.kokkoro.clanbattle.character.CharacterSelection
import com.kokkoro.clanbattle.config.AppPreferences
import com.kokkoro.clanbattle.ui.UiKit

class SequenceAxisEditorActivity : Activity() {
    private data class RoleSlot(
        val name: String,
        val ubName: String,
        val entry: CharacterLibraryEntry? = null,
        val sixStar: Boolean = false
    )

    private enum class QuickMode { SET, AUTO, UB_AFTER, PAUSE_FRAME, BOSS_AFTER, NOTE }

    private lateinit var axisLibrary: AxisLibrary
    private val characterLibrary by lazy { AndroidCharacterLibrary.load(this) }
    private lateinit var nameInput: EditText
    private lateinit var clickIntervalInput: EditText
    private lateinit var roleButtons: List<Button>
    private lateinit var nodesContainer: LinearLayout
    private lateinit var timeInput: EditText
    private lateinit var modeButtons: Map<QuickMode, Button>
    private lateinit var composerBody: LinearLayout
    private lateinit var composerPreview: TextView
    private lateinit var commitButton: Button

    private val roleSlots = MutableList(5) { index -> RoleSlot("角色${index + 1}", "") }
    private val nodes = mutableListOf<VisualSequenceNode>()
    private var quickMode = QuickMode.SET
    private val composerActions = mutableListOf<VisualSequenceAction>()
    private var composerTrigger = VisualSequenceTrigger()
    private var composerNote = ""
    private var composerNoteInput: EditText? = null
    private var composerBossDelayInput: EditText? = null
    private var editingIndex: Int? = null
    private var editingAxisId: String? = null
    private var initialText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "顺序轴速录"
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        axisLibrary = AxisLibrary(AndroidAxisRepository(this))
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
            .setMessage("返回后，本次顺序轴编辑内容不会保存。")
            .setNegativeButton("继续编辑", null)
            .setPositiveButton("放弃") { _, _ -> super.onBackPressed() }
            .show()
    }

    private fun loadDraft(): SequenceAxisVisualDraft? {
        val id = editingAxisId ?: return SequenceAxisVisualDraft()
        val text = axisLibrary.text(id)
        val draft = text?.let { runCatching { SequenceAxisVisualDraft.from(AxisParser.parse(it)) }.getOrNull() }
        if (draft == null) {
            Toast.makeText(this, "该顺序轴无法无损转换为速录格式，请使用源码编辑", Toast.LENGTH_LONG).show()
            finish()
            return null
        }
        return draft
    }

    private fun applyDraft(draft: SequenceAxisVisualDraft) {
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
        nodes.clear()
        nodes.addAll(draft.nodes)
    }

    private fun buildContent(draft: SequenceAxisVisualDraft) = ScrollView(this).apply {
        addView(LinearLayout(this@SequenceAxisEditorActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(12), dp(10), dp(32))

            addView(UiKit.pageHeader(this@SequenceAxisEditorActivity, "顺序轴速录") { onBackPressed() })
            addView(note("角色从角色库选择，自动带入头像、别名和UB名。常用轴行直接用时间 + 类型 + 五角色按钮录入。"))

            nameInput = edit("轴名称", draft.name)
            addView(nameInput, matchWidth())
            clickIntervalInput = edit("点击间隔(ms)", draft.clickIntervalMs.toString())
            addView(clickIntervalInput, matchWidth())

            addView(sectionLabel("五角色"))
            roleButtons = (0 until 5).map { index ->
                Button(this@SequenceAxisEditorActivity).apply {
                    isAllCaps = false
                    textSize = 11f
                    minWidth = 0
                    setPadding(dp(2), dp(4), dp(2), dp(4))
                    setOnClickListener { chooseRole(index) }
                }
            }
            addView(LinearLayout(this@SequenceAxisEditorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                roleButtons.forEach { addView(it, LinearLayout.LayoutParams(0, dp(92), 1f)) }
            }, matchWidth())
            roleButtons.indices.forEach(::updateRoleButton)

            addView(sectionLabel("轴谱"))
            nodesContainer = LinearLayout(this@SequenceAxisEditorActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(nodesContainer, matchWidth())
            renderNodes()

            addView(sectionLabel("快速录入"))
            timeInput = edit("残秒：113 / 059 / 0:51", "")
            addView(timeInput, matchWidth())

            val firstModes = listOf(QuickMode.SET to "SET链", QuickMode.AUTO to "AUTO", QuickMode.UB_AFTER to "UB后")
            val secondModes = listOf(QuickMode.PAUSE_FRAME to "卡帧", QuickMode.BOSS_AFTER to "BOSS后", QuickMode.NOTE to "提示")
            val buttons = linkedMapOf<QuickMode, Button>()
            fun modeRow(items: List<Pair<QuickMode, String>>) = LinearLayout(this@SequenceAxisEditorActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                items.forEach { (mode, label) ->
                    val button = Button(this@SequenceAxisEditorActivity).apply {
                        text = label
                        isAllCaps = false
                        textSize = 12f
                        minWidth = 0
                        setOnClickListener { selectMode(mode) }
                    }
                    buttons[mode] = button
                    addView(button, LinearLayout.LayoutParams(0, dp(46), 1f))
                }
            }
            addView(modeRow(firstModes), matchWidth())
            addView(modeRow(secondModes), matchWidth())
            modeButtons = buttons

            composerBody = LinearLayout(this@SequenceAxisEditorActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, 0)
            }
            addView(composerBody, matchWidth())
            composerPreview = TextView(this@SequenceAxisEditorActivity).apply {
                textSize = 13f
                setTextColor(UiKit.TEXT_SECONDARY)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setBackgroundColor(Color.rgb(244, 247, 250))
            }
            addView(composerPreview, matchWidth())
            commitButton = button("添加此行") { commitComposer() }
            addView(commitButton)
            addView(button("清空当前录入") { resetComposer(keepTime = true) })

            addView(sectionLabel("保存"))
            addView(button("预览标准轴文本") { preview() })
            addView(button("保存") { save(overwrite = true) })
            addView(button("另存为") { requestSaveAs() })

            selectMode(QuickMode.SET)
        }, matchWidth())
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
                .setMessage("角色已收录，但当前国服数据库没有该角色的UB数据。填写一次游戏内显示的UB技能名即可用于本轴识别。")
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
        renderNodes()
        renderComposer()
    }

    private fun updateRoleButton(index: Int) {
        val slot = roleSlots[index]
        roleButtons[index].apply {
            text = buildString {
                append(slot.name)
                if (slot.entry?.ubPlus != null) append(if (slot.sixStar) "\n6★" else "\n普通UB")
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

    private fun renderNodes() {
        if (!::nodesContainer.isInitialized) return
        nodesContainer.removeAllViews()
        if (nodes.isEmpty()) {
            nodesContainer.addView(note("还没有节点。下面快速录入后会立即出现在这里。"))
            return
        }
        nodes.forEachIndexed { index, node ->
            nodesContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(2))
                addView(Button(this@SequenceAxisEditorActivity).apply {
                    isAllCaps = false
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    textSize = 12f
                    text = "${VisualAxisTime.format(node.timeSeconds)}  ${nodeSummary(node)}"
                    setOnClickListener { loadNodeForEdit(index) }
                }, LinearLayout.LayoutParams(0, dp(50), 1f))
                addView(smallButton("↑") { moveNode(index, -1) })
                addView(smallButton("↓") { moveNode(index, 1) })
                addView(smallButton("×") { removeNode(index) })
            }, matchWidth())
        }
    }

    private fun nodeSummary(node: VisualSequenceNode): String {
        val trigger = when (node.trigger.type) {
            VisualSequenceTriggerType.TIMED -> ""
            VisualSequenceTriggerType.CHARACTER_UB -> "${roleName(node.trigger.roleIndex!!)}UB后 → "
            VisualSequenceTriggerType.BOSS_DELAY -> "BOSS UB后${node.trigger.bossDelayMs?.let { "+${formatMs(it)}s" }.orEmpty()} → "
            VisualSequenceTriggerType.PAUSE_FRAME -> "卡帧${if (node.trigger.pauseAuto) "AUTO" else roleName(node.trigger.roleIndex!!)} → "
        }
        val actions = node.actions.joinToString(" → ") { actionLabel(it) }
        return (trigger + actions).ifBlank { "卡帧" }
    }

    private fun actionLabel(action: VisualSequenceAction): String = when (action) {
        is VisualSequenceAction.RoleLifecycle -> roleName(action.roleIndex)
        VisualSequenceAction.ClickAuto -> "AUTO反转"
        is VisualSequenceAction.SetAuto -> "AUTO${if (action.on) "开" else "关"}"
        is VisualSequenceAction.SetRoleStates -> "SET状态"
        is VisualSequenceAction.Notify -> "提示:${action.message}"
        VisualSequenceAction.BossMarker -> "BOSS"
    }

    private fun moveNode(index: Int, delta: Int) {
        val target = index + delta
        if (target !in nodes.indices) return
        val node = nodes.removeAt(index)
        nodes.add(target, node)
        renderNodes()
    }

    private fun removeNode(index: Int) {
        nodes.removeAt(index)
        if (editingIndex == index) resetComposer(keepTime = true)
        renderNodes()
    }

    private fun loadNodeForEdit(index: Int) {
        val node = nodes[index]
        editingIndex = index
        timeInput.setText(VisualAxisTime.format(node.timeSeconds))
        composerTrigger = node.trigger
        composerActions.clear()
        composerActions.addAll(node.actions)
        composerNote = (node.actions.singleOrNull() as? VisualSequenceAction.Notify)?.message.orEmpty()
        quickMode = when (node.trigger.type) {
            VisualSequenceTriggerType.CHARACTER_UB -> QuickMode.UB_AFTER
            VisualSequenceTriggerType.PAUSE_FRAME -> QuickMode.PAUSE_FRAME
            VisualSequenceTriggerType.BOSS_DELAY -> QuickMode.BOSS_AFTER
            VisualSequenceTriggerType.TIMED -> when {
                node.actions.singleOrNull() is VisualSequenceAction.Notify -> QuickMode.NOTE
                node.actions.singleOrNull() is VisualSequenceAction.SetAuto || node.actions.singleOrNull() == VisualSequenceAction.ClickAuto -> QuickMode.AUTO
                else -> QuickMode.SET
            }
        }
        renderComposerBody()
        renderComposer()
    }

    private fun selectMode(mode: QuickMode) {
        quickMode = mode
        composerActions.clear()
        composerTrigger = if (mode == QuickMode.BOSS_AFTER) {
            VisualSequenceTrigger(VisualSequenceTriggerType.BOSS_DELAY)
        } else {
            VisualSequenceTrigger()
        }
        composerNote = ""
        editingIndex = null
        renderComposerBody()
        renderComposer()
    }

    private fun renderComposerBody() {
        if (!::composerBody.isInitialized) return
        modeButtons.forEach { (mode, button) ->
            button.alpha = if (mode == quickMode) 1f else 0.58f
            button.setTypeface(button.typeface, if (mode == quickMode) Typeface.BOLD else Typeface.NORMAL)
        }
        composerBody.removeAllViews()
        composerNoteInput = null
        composerBossDelayInput = null
        when (quickMode) {
            QuickMode.SET -> {
                composerBody.addView(note("按执行顺序连续点角色；同一角色可以重复点。"))
                composerBody.addView(roleActionRow { roleIndex -> composerActions += VisualSequenceAction.RoleLifecycle(roleIndex); renderComposer() })
                composerBody.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(actionButton("AUTO反转") { composerActions += VisualSequenceAction.ClickAuto; renderComposer() })
                    addView(actionButton("AUTO开") { composerActions += VisualSequenceAction.SetAuto(true); renderComposer() })
                    addView(actionButton("AUTO关") { composerActions += VisualSequenceAction.SetAuto(false); renderComposer() })
                })
                composerBody.addView(undoRow())
            }
            QuickMode.AUTO -> {
                composerBody.addView(note("AUTO=开/关是目标状态；“反转”对应点击AUTO一次。"))
                composerBody.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(actionButton("设为开") { composerActions.clear(); composerActions += VisualSequenceAction.SetAuto(true); renderComposer() })
                    addView(actionButton("设为关") { composerActions.clear(); composerActions += VisualSequenceAction.SetAuto(false); renderComposer() })
                    addView(actionButton("反转") { composerActions.clear(); composerActions += VisualSequenceAction.ClickAuto; renderComposer() })
                })
            }
            QuickMode.UB_AFTER -> {
                composerBody.addView(note("第一行选“谁UB后触发”；第二行按顺序追加UB后的动作。"))
                composerBody.addView(roleActionRow { roleIndex ->
                    composerTrigger = VisualSequenceTrigger(VisualSequenceTriggerType.CHARACTER_UB, roleIndex = roleIndex)
                    renderComposer()
                })
                composerBody.addView(note("UB后动作："))
                composerBody.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(roleActionRow { roleIndex -> composerActions += VisualSequenceAction.RoleLifecycle(roleIndex); renderComposer() })
                    addView(LinearLayout(this@SequenceAxisEditorActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(actionButton("AUTO反转") { composerActions += VisualSequenceAction.ClickAuto; renderComposer() })
                        addView(actionButton("AUTO开") { composerActions += VisualSequenceAction.SetAuto(true); renderComposer() })
                        addView(actionButton("AUTO关") { composerActions += VisualSequenceAction.SetAuto(false); renderComposer() })
                    })
                })
                composerBody.addView(undoRow())
            }
            QuickMode.PAUSE_FRAME -> {
                composerBody.addView(note("选择菜单卡帧目标。角色卡帧或AUTO卡帧都直接生成现有语法。"))
                composerBody.addView(roleActionRow { roleIndex ->
                    composerTrigger = VisualSequenceTrigger(VisualSequenceTriggerType.PAUSE_FRAME, roleIndex = roleIndex)
                    renderComposer()
                })
                composerBody.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(actionButton("AUTO") {
                        composerTrigger = VisualSequenceTrigger(VisualSequenceTriggerType.PAUSE_FRAME, pauseAuto = true)
                        renderComposer()
                    })
                }, matchWidth())
            }
            QuickMode.BOSS_AFTER -> {
                composerBossDelayInput = edit(
                    "Boss UB后延迟秒数（可空）",
                    composerTrigger.bossDelayMs?.let { formatMs(it) }.orEmpty()
                )
                composerBody.addView(composerBossDelayInput, matchWidth())
                composerBody.addView(note("BOSS UB后动作："))
                composerBody.addView(roleActionRow { roleIndex -> composerActions += VisualSequenceAction.RoleLifecycle(roleIndex); renderComposer() })
                composerBody.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(actionButton("AUTO反转") { composerActions += VisualSequenceAction.ClickAuto; renderComposer() })
                    addView(actionButton("AUTO开") { composerActions += VisualSequenceAction.SetAuto(true); renderComposer() })
                    addView(actionButton("AUTO关") { composerActions += VisualSequenceAction.SetAuto(false); renderComposer() })
                })
                composerBody.addView(undoRow())
            }
            QuickMode.NOTE -> {
                composerNoteInput = EditText(this).apply {
                    hint = "原轴说明 / 手动操作提示"
                    setText(composerNote)
                    minLines = 2
                }
                composerBody.addView(composerNoteInput, matchWidth())
            }
        }
    }

    private fun roleActionRow(onRole: (Int) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        (0 until 5).forEach { index ->
            addView(Button(this@SequenceAxisEditorActivity).apply {
                isAllCaps = false
                text = roleSlots[index].name
                textSize = 10f
                minWidth = 0
                setPadding(0, 0, 0, 0)
                setOnClickListener { onRole(index) }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
    }

    private fun undoRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(actionButton("撤销一步") {
            if (composerActions.isNotEmpty()) composerActions.removeAt(composerActions.lastIndex)
            renderComposer()
        })
        addView(actionButton("清空动作") { composerActions.clear(); renderComposer() })
    }

    private fun renderComposer() {
        if (!::composerPreview.isInitialized) return
        val time = VisualAxisTime.parse(timeInput.text.toString())
        val prefix = time?.let(VisualAxisTime::format) ?: "未填时间"
        val temp = VisualSequenceNode(time ?: 0, composerTrigger, composerActions.toList())
        composerPreview.text = "$prefix  ${nodeSummary(temp)}"
        commitButton.text = if (editingIndex == null) "添加此行" else "更新第${editingIndex!! + 1}行"
    }

    private fun commitComposer() {
        if (quickMode == QuickMode.NOTE) {
            composerNote = composerNoteInput?.text?.toString().orEmpty().trim()
            composerActions.clear()
            if (composerNote.isNotBlank()) composerActions += VisualSequenceAction.Notify(composerNote)
        }
        if (quickMode == QuickMode.BOSS_AFTER) {
            val rawDelay = composerBossDelayInput?.text?.toString().orEmpty().trim()
            val delay = if (rawDelay.isBlank()) null else rawDelay.toDoubleOrNull()?.let { (it * 1_000).toLong() }
            if (rawDelay.isNotBlank() && (delay == null || delay !in 0..30_000)) {
                composerBossDelayInput?.error = "范围0～30秒"
                return
            }
            composerTrigger = VisualSequenceTrigger(VisualSequenceTriggerType.BOSS_DELAY, bossDelayMs = delay)
        }
        val seconds = VisualAxisTime.parse(timeInput.text.toString())
        if (seconds == null) {
            timeInput.error = "支持113、059、0:51，范围0:00～1:30"
            return
        }
        val triggerReady = when (quickMode) {
            QuickMode.UB_AFTER -> composerTrigger.type == VisualSequenceTriggerType.CHARACTER_UB
            QuickMode.PAUSE_FRAME -> composerTrigger.type == VisualSequenceTriggerType.PAUSE_FRAME
            QuickMode.BOSS_AFTER -> composerTrigger.type == VisualSequenceTriggerType.BOSS_DELAY
            else -> true
        }
        if (!triggerReady) {
            Toast.makeText(this, "请先选择触发条件", Toast.LENGTH_SHORT).show()
            return
        }
        if (quickMode != QuickMode.PAUSE_FRAME && composerActions.isEmpty()) {
            Toast.makeText(this, "请至少添加一个动作", Toast.LENGTH_SHORT).show()
            return
        }
        val node = VisualSequenceNode(seconds, composerTrigger, composerActions.toList())
        val index = editingIndex
        if (index == null) nodes += node else nodes[index] = node
        renderNodes()
        resetComposer(keepTime = true)
        timeInput.requestFocus()
        timeInput.selectAll()
    }

    private fun resetComposer(keepTime: Boolean) {
        if (!keepTime && ::timeInput.isInitialized) timeInput.setText("")
        composerActions.clear()
        composerTrigger = VisualSequenceTrigger()
        composerNote = ""
        editingIndex = null
        renderComposerBody()
        renderComposer()
    }

    private fun buildDraft(showErrors: Boolean): SequenceAxisVisualDraft? {
        val interval = clickIntervalInput.text.toString().toIntOrNull()
        if (interval == null || interval !in 1..5000) {
            if (showErrors) clickIntervalInput.error = "范围1～5000ms"
            return null
        }
        if (roleSlots.any { it.name.startsWith("角色") || it.ubName.isBlank() }) {
            if (showErrors) Toast.makeText(this, "请从角色库选择完整的五个角色", Toast.LENGTH_LONG).show()
            return null
        }
        val draft = SequenceAxisVisualDraft(
            name = nameInput.text.toString().trim().ifBlank { "未命名顺序轴" },
            clickIntervalMs = interval,
            roleNames = roleSlots.map { it.name },
            roleUbSkillNames = roleSlots.map { it.ubName },
            nodes = nodes.toList()
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
        if (axisLibrary.isLocked()) {
            Toast.makeText(this, "战斗中已锁定，不能保存轴", Toast.LENGTH_SHORT).show()
            return
        }
        overrideName?.let(nameInput::setText)
        val draft = buildDraft(true) ?: return
        val text = draft.toStandardText()
        val sourceName = "${draft.name}.txt"
        val currentId = editingAxisId
        val saved = if (overwrite && currentId != null) axisLibrary.replace(currentId, sourceName, text)
            else axisLibrary.import(sourceName, text)
        if (saved == null || !saved.valid) {
            Toast.makeText(this, "保存失败：${saved?.validationMessage.orEmpty()}", Toast.LENGTH_LONG).show()
            return
        }
        axisLibrary.select(saved.id)
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

    private fun roleName(index: Int): String = roleSlots.getOrNull(index)?.name ?: "角色${index + 1}"
    private fun formatMs(ms: Long): String = "%.3f".format(java.util.Locale.US, ms / 1000.0).trimEnd('0').trimEnd('.')

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(UiKit.TEXT_PRIMARY)
        setPadding(0, dp(14), 0, dp(6))
    }
    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(UiKit.TEXT_SECONDARY)
        setPadding(0, dp(4), 0, dp(6))
    }
    private fun edit(hint: String, value: String) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setSingleLine(true)
    }
    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { action() }
        layoutParams = matchWidth().apply { topMargin = dp(6) }
    }
    private fun actionButton(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 12f
        minWidth = 0
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
    }
    private fun smallButton(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        minWidth = 0
        setPadding(0, 0, 0, 0)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(50))
    }
    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_AXIS_ID = "axis_id"
    }
}
