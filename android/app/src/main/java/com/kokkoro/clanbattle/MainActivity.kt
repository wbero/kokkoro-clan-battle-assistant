package com.kokkoro.clanbattle

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.kokkoro.clanbattle.capture.ScreenCaptureService
import com.kokkoro.clanbattle.axis.AndroidAxisRepository
import com.kokkoro.clanbattle.axis.AxisLibrary
import com.kokkoro.clanbattle.axis.AxisType
import com.kokkoro.clanbattle.config.AppPreferences

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var axisView: TextView
    private lateinit var dryRunCheckBox: CheckBox
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var axisLibrary: AxisLibrary
    private lateinit var axisList: LinearLayout
    private lateinit var pageHost: FrameLayout
    private lateinit var pages: List<View>
    private lateinit var navigationButtons: List<Button>
    private var receiverRegistered = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(ScreenCaptureService.EXTRA_STATUS_TEXT) ?: return
            val success = intent.getBooleanExtra(ScreenCaptureService.EXTRA_STATUS_SUCCESS, false)
            statusView.text = text
            statusView.setTextColor(if (success) Color.rgb(27, 94, 32) else Color.rgb(183, 28, 28))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        axisLibrary = AxisLibrary(AndroidAxisRepository(this))
        migrateLegacyAxis()
        setContentView(buildContent())
        refreshAxisLabel()
    }

    override fun onStart() {
        super.onStart()
        refreshAxisLabel()
        if (!receiverRegistered) {
            val filter = IntentFilter(ScreenCaptureService.ACTION_STATUS)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") registerReceiver(statusReceiver, filter)
            receiverRegistered = true
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    @Deprecated("Uses platform activity results to avoid an AndroidX runtime dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CAPTURE -> {
                if (resultCode != RESULT_OK || data == null) {
                    statusView.text = "截图授权取消"
                    return
                }
                val serviceIntent = Intent(this, ScreenCaptureService::class.java)
                    .setAction(ScreenCaptureService.ACTION_START)
                    .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
                statusView.text = "已授权，打开游戏等待横屏"
            }

            REQUEST_AXIS -> if (resultCode == RESULT_OK && data?.data != null) loadAxis(data.data!!)
        }
    }

    private fun buildContent(): View {
        val battlePage = buildBattlePage()
        val axisPage = buildAxisPage()
        val settingsPage = buildSettingsPage()
        pages = listOf(battlePage, axisPage, settingsPage)
        pageHost = FrameLayout(this).apply { addView(battlePage, frameMatch()) }
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
            setBackgroundColor(Color.rgb(245, 247, 250))
        }
        navigationButtons = listOf("战斗", "轴库", "设置").mapIndexed { index, label ->
            Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { showPage(index) }
                navigation.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(pageHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(navigation, matchWidth())
        }
        showPage(0)
        return root
    }

    private fun buildBattlePage(): ScrollView {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }

        content.addView(TextView(this).apply {
            text = "可可萝自动会战助手"
            textSize = 24f
            setTextColor(Color.rgb(32, 33, 36))
        })
        content.addView(TextView(this).apply {
            text = "原生截图 · 50ms 最新帧 · 无障碍执行"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(4), 0, dp(16))
        })

        statusView = TextView(this).apply {
            text = "未启动"
            textSize = 18f
            setTextColor(Color.rgb(183, 28, 28))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(245, 245, 245))
        }
        content.addView(statusView, matchWidth())

        axisView = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(16), 0, dp(8))
        }
        content.addView(axisView)

        dryRunCheckBox = CheckBox(this).apply {
            text = "只识别，不执行点击"
            isChecked = AppPreferences.dryRun(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> AppPreferences.setDryRun(this@MainActivity, checked) }
        }
        content.addView(dryRunCheckBox)
        content.addView(button("开始原生截图") { requestCapture() })
        content.addView(button("准备新战斗") {
            startService(
                Intent(this, ScreenCaptureService::class.java)
                    .setAction(ScreenCaptureService.ACTION_PREPARE_BATTLE)
            )
            statusView.text = "已重置，请打开游戏开始战斗"
            statusView.setTextColor(Color.rgb(183, 28, 28))
        })
        content.addView(button("停止识别") {
            startService(Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
            statusView.text = "已停止"
        })
        content.addView(button("打开公主连结") {
            packageManager.getLaunchIntentForPackage(GAME_PACKAGE)?.let(::startActivity)
                ?: Toast.makeText(this, "未找到游戏", Toast.LENGTH_SHORT).show()
        })

        return ScrollView(this).apply { addView(content) }
    }

    private fun buildAxisPage(): ScrollView {
        val content = sectionContent("轴库", "导入、创建和维护战斗轴；战斗运行期间轴库会锁定。")
        content.addView(button("可视化制作开关轴") {
            startActivity(Intent(this, SwitchAxisEditorActivity::class.java))
        })
        content.addView(button("导入轴文件") {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                },
                REQUEST_AXIS
            )
        })
        content.addView(button("粘贴轴文本") { showPasteAxisDialog() })
        content.addView(button("轴编写指南与标准示例") {
            startActivity(Intent(this, AxisGuideActivity::class.java))
        })
        axisList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(axisList, matchWidth())
        return ScrollView(this).apply { addView(content) }
    }

    private fun buildSettingsPage(): ScrollView {
        val content = sectionContent("设置", "配置点击权限、悬浮窗和开发诊断功能。")
        content.addView(button("启用无障碍点击") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        content.addView(button("授予悬浮窗权限") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        if (BuildConfig.DEBUG) content.addView(CheckBox(this).apply {
            text = "记录识别诊断（时钟＋能量）"
            isChecked = AppPreferences.clockDebugEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> AppPreferences.setClockDebugEnabled(this@MainActivity, checked) }
        })
        content.addView(TextView(this).apply {
            text = "建议首次使用时依次授予无障碍和悬浮窗权限，然后回到“战斗”页开始截图。"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(16), 0, 0)
        })
        return ScrollView(this).apply { addView(content) }
    }

    private fun sectionContent(title: String, subtitle: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(28))
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 24f
            setTextColor(Color.rgb(32, 33, 36))
        })
        addView(TextView(this@MainActivity).apply {
            text = subtitle
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(4), 0, dp(12))
        })
    }

    private fun showPage(index: Int) {
        pageHost.removeAllViews()
        pageHost.addView(pages[index], frameMatch())
        navigationButtons.forEachIndexed { buttonIndex, button ->
            button.isEnabled = buttonIndex != index
            button.setTextColor(if (buttonIndex == index) Color.rgb(30, 80, 150) else Color.DKGRAY)
        }
        if (index == 1) refreshAxisLabel()
    }

    private fun requestCapture() {
        if (!Settings.canDrawOverlays(this)) {
            statusView.text = "请先授予悬浮窗权限，再点开始"
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    private fun loadAxis(uri: Uri) {
        val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: return
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else uri.lastPathSegment
        } ?: uri.lastPathSegment.orEmpty()
        importAxis(name, text)
    }

    private fun showPasteAxisDialog() {
        val clipboardText = getSystemService(ClipboardManager::class.java)
            ?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            .orEmpty()
        showAxisEditor(
            title = "粘贴轴文本",
            actionLabel = "导入",
            initialName = "",
            initialText = clipboardText
        ) { sourceName, text ->
            importAxis(sourceName, text)
            true
        }
    }

    private fun showEditAxisDialog(axisId: String, sourceName: String) {
        val originalText = axisLibrary.text(axisId) ?: return
        showAxisEditor(
            title = "编辑轴",
            actionLabel = "保存",
            initialName = sourceName,
            initialText = originalText
        ) { editedName, editedText ->
            val replaced = axisLibrary.replace(axisId, editedName, editedText)
            when {
                replaced == null -> {
                    Toast.makeText(this, "战斗中已锁定，暂时不能编辑", Toast.LENGTH_SHORT).show()
                    false
                }
                !replaced.valid -> {
                    Toast.makeText(this, "轴无效：${replaced.validationMessage}", Toast.LENGTH_LONG).show()
                    false
                }
                else -> {
                    syncLegacySelectedAxis()
                    refreshAxisLabel()
                    Toast.makeText(this, "已保存：${replaced.name}", Toast.LENGTH_SHORT).show()
                    true
                }
            }
        }
    }

    private fun showAxisEditor(
        title: String,
        actionLabel: String,
        initialName: String,
        initialText: String,
        onSave: (sourceName: String, text: String) -> Boolean
    ) {
        val nameInput = EditText(this).apply {
            hint = "来源名称（可选）"
            setSingleLine(true)
            setText(initialName)
        }
        val textInput = EditText(this).apply {
            hint = "在这里粘贴完整轴文本"
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = Gravity.TOP or Gravity.START
            minLines = 10
            maxLines = 18
            setText(initialText)
            setSelection(text.length)
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            addView(nameInput, matchWidth())
            addView(textInput, matchWidth())
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("支持顺序轴和开关轴；内容会经过与文件导入相同的解析和校验。")
            .setView(fields)
            .setNegativeButton("取消", null)
            .setPositiveButton(actionLabel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = textInput.text.toString()
                if (text.isBlank()) {
                    textInput.error = "请粘贴轴文本"
                    return@setOnClickListener
                }
                val sourceName = nameInput.text.toString().trim().ifBlank { "粘贴轴.txt" }
                if (onSave(sourceName, text)) dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun importAxis(name: String, text: String) {
        val imported = axisLibrary.import(name, text)
        if (imported.valid) {
            axisLibrary.select(imported.id)
            syncLegacySelectedAxis()
            Toast.makeText(this, "已导入并选择：${imported.name}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "轴无效：${imported.validationMessage}", Toast.LENGTH_LONG).show()
        }
        refreshAxisLabel()
    }

    private fun refreshAxisLabel() {
        val selected = axisLibrary.selected()
        val locked = axisLibrary.isLocked()
        axisView.text = (selected?.let { "当前轴：${it.name}（${it.type}，${it.eventCount}节点）" }
            ?: "当前轴：未选择") + if (locked) "；战斗中已锁定" else ""
        if (!::axisList.isInitialized) return
        axisList.removeAllViews()
        axisLibrary.list().forEach { axis ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Button(this).apply {
                isAllCaps = false
                isEnabled = axis.valid && !locked
                text = buildString {
                    if (selected?.id == axis.id) append("✓ ")
                    append(axis.name).append(" [").append(axis.type).append("]")
                    if (!axis.valid) append(" 无效：").append(axis.validationMessage)
                }
                setOnClickListener {
                    if (axisLibrary.select(axis.id)) {
                        syncLegacySelectedAxis()
                        refreshAxisLabel()
                    }
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (axis.type == AxisType.SWITCH && axis.valid) row.addView(Button(this).apply {
                text = "制轴"
                isAllCaps = false
                isEnabled = !locked
                setOnClickListener {
                    startActivity(
                        Intent(this@MainActivity, SwitchAxisEditorActivity::class.java)
                            .putExtra(SwitchAxisEditorActivity.EXTRA_AXIS_ID, axis.id)
                    )
                }
            })
            row.addView(Button(this).apply {
                text = "编辑"
                isAllCaps = false
                isEnabled = !locked
                setOnClickListener { showEditAxisDialog(axis.id, axis.sourceName) }
            })
            row.addView(Button(this).apply {
                text = "删除"
                isAllCaps = false
                isEnabled = !locked
                setOnClickListener {
                    axisLibrary.remove(axis.id)
                    syncLegacySelectedAxis()
                    refreshAxisLabel()
                }
            })
            axisList.addView(row, matchWidth())
        }
    }

    private fun migrateLegacyAxis() {
        if (axisLibrary.list().isNotEmpty()) return
        val text = AppPreferences.axisText(this)
        if (text.isBlank()) return
        val imported = axisLibrary.import(AppPreferences.axisName(this), text)
        if (imported.valid) axisLibrary.select(imported.id)
    }

    private fun syncLegacySelectedAxis() {
        val selected = axisLibrary.selected()
        val text = axisLibrary.selectedText()
        if (selected != null && text != null) AppPreferences.saveAxis(this, selected.name, text)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = matchWidth().apply { topMargin = dp(8) }
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun frameMatch() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_CAPTURE = 100
        const val REQUEST_AXIS = 101
        const val REQUEST_NOTIFICATIONS = 102
        const val GAME_PACKAGE = "com.bilibili.priconne"
    }
}
