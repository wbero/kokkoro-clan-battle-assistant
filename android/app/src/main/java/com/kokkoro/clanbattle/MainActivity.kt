package com.kokkoro.clanbattle

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
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
import android.view.WindowManager
import android.content.res.Configuration
import com.kokkoro.clanbattle.automation.KokkoroAccessibilityService
import com.kokkoro.clanbattle.capture.ScreenCaptureService
import com.kokkoro.clanbattle.axis.AndroidAxisRepository
import com.kokkoro.clanbattle.axis.AxisLibrary
import com.kokkoro.clanbattle.axis.AxisType
import com.kokkoro.clanbattle.config.AppPreferences
import com.kokkoro.clanbattle.config.parseBossUbEarlyConfirmationHoldMs
import com.kokkoro.clanbattle.config.parseEnergyThresholdPercents
import com.kokkoro.clanbattle.config.parsePauseFrameSettings
import com.kokkoro.clanbattle.config.parseStandalonePauseTier
import com.kokkoro.clanbattle.config.StandalonePauseSettings
import com.kokkoro.clanbattle.pauseframe.PauseFrameOverlay
import com.kokkoro.clanbattle.ui.UiKit

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var statusDot: View
    private lateinit var axisView: TextView
    private lateinit var dryRunCheckBox: CheckBox
    private lateinit var standalonePauseButton: Button
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var axisLibrary: AxisLibrary
    private lateinit var axisList: LinearLayout
    private lateinit var pageHost: FrameLayout
    private lateinit var pages: List<View>
    private lateinit var navigationButtons: List<Button>
    private var receiverRegistered = false
    private var pendingCaptureRequest = false
    private val permissionUpdaters = mutableListOf<() -> Unit>()

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(ScreenCaptureService.EXTRA_STATUS_TEXT) ?: return
            val success = intent.getBooleanExtra(ScreenCaptureService.EXTRA_STATUS_SUCCESS, false)
            setStatus(text, success)
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
        refreshPermissionStatus()
        refreshStandalonePauseButton()
        if (AppPreferences.standalonePauseEnabled(this)) PauseFrameOverlay.show(this)
        if (!receiverRegistered) {
            val filter = IntentFilter(ScreenCaptureService.ACTION_STATUS)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION", "UnspecifiedRegisterReceiverFlag") registerReceiver(statusReceiver, filter)
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
                    setStatus("截图授权取消", false)
                    return
                }
                val serviceIntent = Intent(this, ScreenCaptureService::class.java)
                    .setAction(ScreenCaptureService.ACTION_START)
                    .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                    .putExtra(ScreenCaptureService.EXTRA_CAPTURE_SESSION_ID, SystemClock.elapsedRealtimeNanos())
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
                setStatus("已授权，打开游戏等待横屏", true)
            }

            REQUEST_AXIS -> if (resultCode == RESULT_OK && data?.data != null) loadAxis(data.data!!)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS && pendingCaptureRequest) {
            pendingCaptureRequest = false
            launchCaptureIntent()
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
            setPadding(dp(8), dp(4), dp(8), dp(6))
            setBackgroundColor(Color.rgb(250, 251, 253))
        }
        navigationButtons = listOf("战斗", "轴库", "设置").mapIndexed { index, label ->
            Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 15f
                stateListAnimator = null
                setBackgroundResource(borderlessBackground())
                setOnClickListener { showPage(index) }
                navigation.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.app_background)
            addView(pageHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(UiKit.hairline(this@MainActivity))
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
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(UiKit.TEXT_PRIMARY)
        })
        content.addView(TextView(this).apply {
            text = "原生截图 · 50ms 最新帧 · 无障碍执行"
            textSize = 14f
            setTextColor(UiKit.TEXT_SECONDARY)
            setPadding(0, dp(4), 0, dp(14))
        })

        val statusCard = UiKit.card(this)
        statusDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(UiKit.ERROR)
            }
        }
        statusCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(6) })
            addView(caption("状态"))
        })
        statusView = TextView(this).apply {
            text = "未启动"
            textSize = 17f
            setTextColor(UiKit.ERROR)
            setPadding(0, dp(6), 0, 0)
        }
        statusCard.addView(statusView, matchWidth())
        axisView = TextView(this).apply {
            textSize = 14f
            setTextColor(UiKit.TEXT_SECONDARY)
            setPadding(0, dp(6), 0, 0)
        }
        statusCard.addView(axisView, matchWidth())
        content.addView(statusCard, matchWidth())

        content.addView(buildPermissionCard(), matchWidth(top = 12))

        dryRunCheckBox = CheckBox(this).apply {
            text = "只识别，不执行点击"
            setTextColor(UiKit.TEXT_PRIMARY)
            isChecked = AppPreferences.dryRun(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> AppPreferences.setDryRun(this@MainActivity, checked) }
        }
        content.addView(dryRunCheckBox, matchWidth(top = 12))
        content.addView(TextView(this).apply {
            text = "试轴或验证识别时使用；该模式不需要无障碍权限。"
            textSize = 12f
            setTextColor(UiKit.TEXT_SECONDARY)
            setPadding(dp(32), 0, 0, 0)
        })

        content.addView(primaryButton("开始原生截图") { requestCapture() }, matchWidth(top = 14))
        content.addView(secondaryButton("准备新战斗") {
            startService(
                Intent(this, ScreenCaptureService::class.java)
                    .setAction(ScreenCaptureService.ACTION_PREPARE_BATTLE)
            )
            setStatus("已重置，请打开游戏开始战斗", false)
        }, matchWidth(top = 8))
        content.addView(secondaryButton("停止识别") {
            startService(Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
            setStatus("已停止", false)
        }, matchWidth(top = 8))
        content.addView(secondaryButton("打开公主连结") {
            packageManager.getLaunchIntentForPackage(GAME_PACKAGE)?.let(::startActivity)
                ?: Toast.makeText(this, "未找到游戏", Toast.LENGTH_SHORT).show()
        }, matchWidth(top = 8))
        content.addView(secondaryButton("") { toggleStandalonePause() }.also {
            standalonePauseButton = it
            refreshStandalonePauseButton()
        }, matchWidth(top = 8))

        return ScrollView(this).apply { addView(content) }
    }

    private fun buildAxisPage(): ScrollView {
        val content = sectionContent("轴库", "导入、创建和维护战斗轴；战斗运行期间轴库会锁定。") {
            showPage(0)
        }
        content.addView(secondaryButton("可视化制作开关轴") {
            startActivity(Intent(this, SwitchAxisEditorActivity::class.java))
        }, matchWidth(top = 8))
        content.addView(secondaryButton("导入轴文件") {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                },
                REQUEST_AXIS
            )
        }, matchWidth(top = 8))
        content.addView(secondaryButton("粘贴轴文本") { showPasteAxisDialog() }, matchWidth(top = 8))
        content.addView(secondaryButton("轴编写指南与标准示例") {
            startActivity(Intent(this, AxisGuideActivity::class.java))
        }, matchWidth(top = 8))
        axisList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        content.addView(axisList, matchWidth())
        return ScrollView(this).apply { addView(content) }
    }

    private fun buildSettingsPage(): ScrollView {
        val content = sectionContent("设置", "配置点击权限、悬浮窗和开发诊断功能。") {
            showPage(0)
        }

        content.addView(buildPermissionCard(), matchWidth(top = 4))

        val diagnosticsCard = UiKit.card(this)
        diagnosticsCard.addView(caption("诊断"))
        diagnosticsCard.addView(CheckBox(this).apply {
            text = "记录识别诊断（时钟＋能量）"
            setTextColor(UiKit.TEXT_PRIMARY)
            isChecked = AppPreferences.clockDebugEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> AppPreferences.setClockDebugEnabled(this@MainActivity, checked) }
        })
        diagnosticsCard.addView(TextView(this).apply {
            text = "开启后会在应用外部目录写入识别诊断文件，仅排查问题时使用。"
            textSize = 12f
            setTextColor(UiKit.TEXT_SECONDARY)
        })
        diagnosticsCard.addView(CheckBox(this).apply {
            text = "显示识别区域与 TP 刻度"
            setTextColor(UiKit.TEXT_PRIMARY)
            isChecked = AppPreferences.regionOverlayEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                AppPreferences.setRegionOverlayEnabled(this@MainActivity, checked)
            }
        })
        diagnosticsCard.addView(TextView(this).apply {
            text = "在游戏画面上层描出时钟、AUTO、SET、TP 等识别框，并在每条 TP 下方画出识别到的能量刻度。开关立即生效，无需重启录屏。"
            textSize = 12f
            setTextColor(UiKit.TEXT_SECONDARY)
        })
        content.addView(diagnosticsCard, matchWidth(top = 12))

        content.addView(buildEnergyThresholdCard(), matchWidth(top = 12))

        content.addView(buildBossUbEarlyConfirmationCard(), matchWidth(top = 12))

        content.addView(buildPauseFrameCard(), matchWidth(top = 12))

        content.addView(buildStandalonePauseCard(), matchWidth(top = 12))

        val aboutCard = UiKit.card(this)
        aboutCard.addView(caption("关于"))
        aboutCard.addView(TextView(this).apply {
            text = "可可萝自动会战助手\n${installedVersionName()}\n作者：wbero"
            textSize = 15f
            setTextColor(UiKit.TEXT_PRIMARY)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(4), 0, 0)
        })
        content.addView(aboutCard, matchWidth(top = 12))
        return ScrollView(this).apply { addView(content) }
    }

    private fun installedVersionName(): String {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        return getString(R.string.about_version_format, versionName ?: BuildConfig.VERSION_NAME)
    }

    private fun buildEnergyThresholdCard(): LinearLayout {
        val card = UiKit.card(this)
        card.addView(caption("UB 识别阈值"))
        card.addView(TextView(this).apply {
            text = "某角色曾达到满 TP 后，后续掉到“释放后 TP”以下即判定释放 UB；中间一两帧动画抖动不会丢失。两值越接近越灵敏，但越易误判。"
            textSize = 12f
            setTextColor(UiKit.TEXT_SECONDARY)
            setPadding(0, dp(4), 0, dp(6))
        })
        val fullInput = thresholdInput(AppPreferences.energyFullPercent(this))
        val dropInput = thresholdInput(AppPreferences.energyDropPercent(this))
        card.addView(thresholdRow("满 TP 值", fullInput))
        card.addView(thresholdRow("释放后 TP", dropInput))
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
            addView(textButton("恢复默认", UiKit.TEXT_SECONDARY, enabled = true) {
                fullInput.setText(AppPreferences.DEFAULT_ENERGY_FULL_PERCENT.toString())
                dropInput.setText(AppPreferences.DEFAULT_ENERGY_DROP_PERCENT.toString())
            })
            addView(textButton("保存阈值", UiKit.ACCENT_DARK, enabled = true) {
                val parsed = parseEnergyThresholdPercents(fullInput.text.toString(), dropInput.text.toString())
                if (parsed == null) {
                    Toast.makeText(
                        this@MainActivity,
                        "满 TP 值 50~100、释放后 TP 1~95，且满 TP 值至少高出 5",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    AppPreferences.saveEnergyThresholds(this@MainActivity, parsed)
                    fullInput.setText(parsed.full.toString())
                    dropInput.setText(parsed.drop.toString())
                    Toast.makeText(this@MainActivity, "已保存，下一场战斗生效", Toast.LENGTH_SHORT).show()
                }
            })
        }, matchWidth())
        return card
    }

    private fun buildPauseFrameCard(): LinearLayout {
        val card = UiKit.card(this)
        card.addView(caption("卡帧步进"))
        card.addView(TextView(this).apply {
            text = "悬浮窗可随时手动卡帧，并用两个“释放N帧”按钮微调。手动卡帧的“进入菜单”只负责切换到游戏菜单，由你操作；关闭菜单后点击“恢复识别”。轴内卡帧仍按原逻辑执行指定角色。"
            textSize = 12f
            setTextColor(UiKit.TEXT_SECONDARY)
            setPadding(0, dp(4), 0, dp(6))
        })
        val msInput = thresholdInput(AppPreferences.pauseFrameMs(this))
        val aInput = thresholdInput(AppPreferences.pauseFramePresetA(this))
        val bInput = thresholdInput(AppPreferences.pauseFramePresetB(this))
        val menuWaitInput = thresholdInput(AppPreferences.pauseFrameMenuWaitMs(this))
        card.addView(thresholdRow("单帧时长", msInput, suffix = "ms"))
        card.addView(thresholdRow("档位A", aInput, suffix = "帧"))
        card.addView(thresholdRow("档位B", bInput, suffix = "帧"))
        card.addView(thresholdRow("菜单等待", menuWaitInput, suffix = "ms"))
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
            addView(textButton("恢复默认", UiKit.TEXT_SECONDARY, enabled = true) {
                msInput.setText(AppPreferences.DEFAULT_PAUSE_FRAME_MS.toString())
                aInput.setText(AppPreferences.DEFAULT_PAUSE_PRESET_A.toString())
                bInput.setText(AppPreferences.DEFAULT_PAUSE_PRESET_B.toString())
                menuWaitInput.setText(AppPreferences.DEFAULT_PAUSE_MENU_WAIT_MS.toString())
            })
            addView(textButton("保存卡帧", UiKit.ACCENT_DARK, enabled = true) {
                val parsed = parsePauseFrameSettings(
                    msInput.text.toString(),
                    aInput.text.toString(),
                    bInput.text.toString(),
                    menuWaitInput.text.toString()
                )
                if (parsed == null) {
                    Toast.makeText(
                        this@MainActivity,
                        "单帧时长 5~500ms，两档帧数各 1~600，菜单等待 100~3000ms",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    AppPreferences.savePauseFrameSettings(this@MainActivity, parsed)
                    msInput.setText(parsed.frameMs.toString())
                    aInput.setText(parsed.presetA.toString())
                    bInput.setText(parsed.presetB.toString())
                    menuWaitInput.setText(parsed.menuWaitMs.toString())
                    Toast.makeText(this@MainActivity, "已保存，重新开始截图后生效", Toast.LENGTH_SHORT).show()
                }
            })
        }, matchWidth())
        return card
    }

    private fun buildStandalonePauseCard(): LinearLayout {
        val card = UiKit.card(this)
        card.addView(caption("独立卡帧悬浮窗"))
        card.addView(TextView(this).apply {
            text = "在战斗页开启独立的卡帧悬浮窗，可在游戏中手动逐帧卡帧。三档各自设置帧率(ms/帧)与帧数，与上方主面板卡帧设置完全独立。悬浮窗仅需悬浮窗与无障碍权限，不依赖截图识别。"
            textSize = 12f
            setTextColor(UiKit.TEXT_SECONDARY)
            setPadding(0, dp(4), 0, dp(6))
        })
        val tiers = AppPreferences.standalonePauseTiers(this)
        val rateInputs = (1..3).map { i -> thresholdInput(standaloneTierRate(tiers, i)) }
        val framesInputs = (1..3).map { i -> thresholdInput(standaloneTierFrames(tiers, i)) }
        (1..3).forEach { i ->
            card.addView(standaloneTierRow("档位$i", rateInputs[i - 1], framesInputs[i - 1]))
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
            addView(textButton("恢复默认", UiKit.TEXT_SECONDARY, enabled = true) {
                rateInputs.forEach { it.setText(AppPreferences.DEFAULT_STANDALONE_PAUSE_RATE_MS.toString()) }
                framesInputs.forEach { it.setText(AppPreferences.DEFAULT_STANDALONE_PAUSE_FRAMES.toString()) }
            })
            addView(textButton("保存卡帧", UiKit.ACCENT_DARK, enabled = true) {
                val levels = (0 until 3).map { i ->
                    parseStandalonePauseTier(rateInputs[i].text.toString(), framesInputs[i].text.toString())
                }
                if (levels.any { it == null }) {
                    Toast.makeText(
                        this@MainActivity,
                        "每档帧率 5~500ms、帧数 1~600",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    val settings = StandalonePauseSettings(levels[0]!!, levels[1]!!, levels[2]!!)
                    AppPreferences.saveStandalonePauseTiers(this@MainActivity, settings)
                    PauseFrameOverlay.refreshTiers()
                    listOf(settings.tier1, settings.tier2, settings.tier3).forEachIndexed { index, tier ->
                        rateInputs[index].setText(tier.rateMs.toString())
                        framesInputs[index].setText(tier.frames.toString())
                    }
                    Toast.makeText(this@MainActivity, "已保存，悬浮窗按钮即时更新", Toast.LENGTH_SHORT).show()
                }
            })
        }, matchWidth())
        return card
    }

    private fun standaloneTierRate(tiers: StandalonePauseSettings, index: Int): Int = when (index) {
        1 -> tiers.tier1.rateMs
        2 -> tiers.tier2.rateMs
        else -> tiers.tier3.rateMs
    }

    private fun standaloneTierFrames(tiers: StandalonePauseSettings, index: Int): Int = when (index) {
        1 -> tiers.tier1.frames
        2 -> tiers.tier2.frames
        else -> tiers.tier3.frames
    }

    private fun standaloneTierRow(
        label: String,
        rateInput: EditText,
        framesInput: EditText
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 15f
            setTextColor(UiKit.TEXT_PRIMARY)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(rateInput)
        addView(TextView(this@MainActivity).apply {
            text = "ms/帧"
            textSize = 13f
            setTextColor(UiKit.TEXT_SECONDARY)
            setPadding(dp(4), 0, dp(8), 0)
        })
        addView(framesInput)
        addView(TextView(this@MainActivity).apply {
            text = "帧"
            textSize = 13f
            setTextColor(UiKit.TEXT_SECONDARY)
            setPadding(dp(4), 0, 0, 0)
        })
    }

    private fun toggleStandalonePause() {
        val enabling = !AppPreferences.standalonePauseEnabled(this)
        if (enabling && !Settings.canDrawOverlays(this)) {
            setStatus("请先授予悬浮窗权限，再开启卡帧悬浮窗", false)
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        if (enabling) {
            PauseFrameOverlay.show(this)
        } else {
            PauseFrameOverlay.hide(this)
        }
        refreshStandalonePauseButton()
    }

    private fun refreshStandalonePauseButton() {
        if (!::standalonePauseButton.isInitialized) return
        val enabled = AppPreferences.standalonePauseEnabled(this)
        standalonePauseButton.text = if (enabled) "关闭卡帧悬浮窗" else "开启卡帧悬浮窗"
    }

    private fun buildBossUbEarlyConfirmationCard(): LinearLayout {
        val card = UiKit.card(this)
        card.addView(caption("BOSS UB 提前确认"))
        card.addView(TextView(this).apply {
            text = "倒计时连续停留达到该时长时，提前确认无延迟 BOSS 节点。数值越小越快，但过小会增加误判风险。"
            textSize = 12f
            setTextColor(UiKit.TEXT_SECONDARY)
            setPadding(0, dp(4), 0, dp(6))
        })
        val holdInput = thresholdInput(AppPreferences.bossUbEarlyConfirmationHoldMs(this))
        card.addView(thresholdRow("停表时长", holdInput, suffix = "ms"))
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
            addView(textButton("恢复默认", UiKit.TEXT_SECONDARY, enabled = true) {
                holdInput.setText(AppPreferences.DEFAULT_BOSS_UB_EARLY_CONFIRMATION_HOLD_MS.toString())
            })
            addView(textButton("保存时长", UiKit.ACCENT_DARK, enabled = true) {
                val parsed = parseBossUbEarlyConfirmationHoldMs(holdInput.text.toString())
                if (parsed == null) {
                    Toast.makeText(
                        this@MainActivity,
                        "停表时长必须为 ${AppPreferences.MIN_BOSS_UB_EARLY_CONFIRMATION_HOLD_MS}~" +
                            "${AppPreferences.MAX_BOSS_UB_EARLY_CONFIRMATION_HOLD_MS}ms",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    AppPreferences.setBossUbEarlyConfirmationHoldMs(this@MainActivity, parsed)
                    holdInput.setText(parsed.toString())
                    Toast.makeText(this@MainActivity, "已保存，下一场战斗生效", Toast.LENGTH_SHORT).show()
                }
            })
        }, matchWidth())
        return card
    }

    private fun thresholdInput(value: Int) = EditText(this).apply {
        setText(value.toString())
        inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine(true)
        gravity = Gravity.CENTER
        textSize = 15f
        setTextColor(UiKit.TEXT_PRIMARY)
        layoutParams = LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun thresholdRow(label: String, input: EditText, suffix: String = "%"): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 15f
            setTextColor(UiKit.TEXT_PRIMARY)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(input)
        if (suffix.isNotEmpty()) addView(TextView(this@MainActivity).apply {
            text = suffix
            textSize = 15f
            setTextColor(UiKit.TEXT_SECONDARY)
            setPadding(dp(4), 0, 0, 0)
        })
    }

    private fun buildPermissionCard(): LinearLayout {
        val card = UiKit.card(this)
        card.addView(caption("权限状态"))
        card.addView(permissionRow(
            name = "悬浮窗权限",
            hint = "战斗控制面板需要显示在游戏上层",
            isGranted = { Settings.canDrawOverlays(this) }
        ) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) })
        card.addView(UiKit.hairline(this))
        card.addView(permissionRow(
            name = "无障碍点击服务",
            hint = "自动点击 SET、AUTO 等按钮需要",
            isGranted = { accessibilityEnabled() }
        ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        if (Build.VERSION.SDK_INT >= 33) {
            card.addView(UiKit.hairline(this))
            card.addView(permissionRow(
                name = "通知权限",
                hint = "识别运行时显示前台服务通知",
                isGranted = {
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                }
            ) {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            })
        }
        return card
    }

    private fun permissionRow(
        name: String,
        hint: String,
        isGranted: () -> Boolean,
        onClick: () -> Unit
    ): LinearLayout {
        val stateView = TextView(this).apply { textSize = 14f }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(borderlessBackground())
            setPadding(0, dp(10), 0, dp(10))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = name
                    textSize = 15f
                    setTextColor(UiKit.TEXT_PRIMARY)
                })
                addView(TextView(this@MainActivity).apply {
                    text = hint
                    textSize = 12f
                    setTextColor(UiKit.TEXT_SECONDARY)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(stateView)
            addView(TextView(this@MainActivity).apply {
                text = "›"
                textSize = 18f
                setTextColor(UiKit.TEXT_SECONDARY)
                setPadding(dp(8), 0, 0, 0)
            })
            setOnClickListener { onClick() }
        }
        val update = {
            val granted = isGranted()
            stateView.text = if (granted) "已开启" else "未开启"
            stateView.setTextColor(if (granted) UiKit.SUCCESS else UiKit.ERROR)
            stateView.setTypeface(stateView.typeface, if (granted) Typeface.NORMAL else Typeface.BOLD)
        }
        permissionUpdaters += update
        update()
        return row
    }

    private fun refreshPermissionStatus() {
        permissionUpdaters.forEach { it() }
    }

    private fun accessibilityEnabled(): Boolean {
        return KokkoroAccessibilityService.isConnected()
    }

    private fun setStatus(text: String, success: Boolean) {
        statusView.text = text
        statusView.setTextColor(if (success) UiKit.SUCCESS else UiKit.ERROR)
        (statusDot.background as? GradientDrawable)?.setColor(if (success) UiKit.SUCCESS else UiKit.ERROR)
    }

    private fun sectionContent(
        title: String,
        subtitle: String,
        onBack: (() -> Unit)? = null
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(28))
        if (onBack == null) {
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 24f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(UiKit.TEXT_PRIMARY)
            })
        } else {
            addView(UiKit.pageHeader(this@MainActivity, title, onBack))
        }
        addView(TextView(this@MainActivity).apply {
            text = subtitle
            textSize = 14f
            setTextColor(UiKit.TEXT_SECONDARY)
            setPadding(0, dp(4), 0, dp(12))
        })
    }

    private fun caption(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(UiKit.TEXT_SECONDARY)
    }

    private fun showPage(index: Int) {
        pageHost.removeAllViews()
        pageHost.addView(pages[index], frameMatch())
        navigationButtons.forEachIndexed { buttonIndex, button ->
            val selected = buttonIndex == index
            button.setTextColor(if (selected) UiKit.ACCENT else UiKit.TEXT_SECONDARY)
            button.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
        if (index == 1) refreshAxisLabel()
    }

    private fun requestCapture() {
        if (!Settings.canDrawOverlays(this)) {
            setStatus("请先授予悬浮窗权限，再点开始", false)
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingCaptureRequest = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        launchCaptureIntent()
    }

    private fun launchCaptureIntent() {
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
            // A fixed, bounded editor prevents the dialog's positive button from
            // being pushed below the viewport on short landscape screens.  The
            // editor itself remains vertically scrollable for long axes.
            minLines = 5
            maxLines = Int.MAX_VALUE
            isVerticalScrollBarEnabled = true
            setHorizontallyScrolling(false)
            setText(initialText)
            setSelection(text.length)
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            addView(nameInput, matchWidth())
            addView(textInput, matchWidth().apply {
                height = axisEditorTextHeight()
            })
        }
        val editorScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            addView(fields, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, axisEditorContentHeight())
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(editorScroll)
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
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        )
    }

    /** Height budget for the custom axis editor, leaving AlertDialog buttons visible. */
    private fun axisEditorContentHeight(): Int {
        val heightPx = resources.displayMetrics.heightPixels
        val fraction = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            0.42f
        } else {
            0.48f
        }
        val safeMax = minOf(dp(420), (heightPx * 0.65f).toInt())
        val safeMin = minOf(dp(150), safeMax)
        return (heightPx * fraction).toInt().coerceIn(safeMin, safeMax)
    }

    private fun axisEditorTextHeight(): Int =
        (axisEditorContentHeight() - dp(48)).coerceIn(dp(120), dp(360))

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
        axisView.text = (selected?.let { "当前轴：${it.name}（${it.type.label()}，${it.eventCount}节点）" }
            ?: "当前轴：未选择") + if (locked) "；战斗中已锁定" else ""
        if (!::axisList.isInitialized) return
        axisList.removeAllViews()
        val axes = axisLibrary.list()
        if (axes.isEmpty()) {
            axisList.addView(TextView(this).apply {
                text = "还没有轴，先导入或粘贴一个"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(UiKit.TEXT_SECONDARY)
                setPadding(0, dp(24), 0, dp(24))
            }, matchWidth())
            return
        }
        axes.forEach { axis ->
            axisList.addView(
                axisCard(axis, isSelected = selected?.id == axis.id, locked = locked),
                matchWidth(top = 8)
            )
        }
    }

    private fun axisCard(
        axis: com.kokkoro.clanbattle.axis.StoredAxis,
        isSelected: Boolean,
        locked: Boolean
    ): LinearLayout {
        val card = UiKit.card(this, paddingDp = 12)
        if (isSelected) (card.background as GradientDrawable).setStroke(dp(2), UiKit.ACCENT)
        val selectable = axis.valid && !locked

        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = axis.name
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(UiKit.TEXT_PRIMARY)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                UiKit.chip(this@MainActivity, axis.type.label(), UiKit.ACCENT_DARK),
                matchWrap().apply { leftMargin = dp(6) }
            )
            if (isSelected) addView(
                UiKit.chip(this@MainActivity, "使用中", UiKit.SUCCESS),
                matchWrap().apply { leftMargin = dp(6) }
            )
        }, matchWidth())

        card.addView(TextView(this).apply {
            text = if (axis.valid) "${axis.eventCount} 个节点" else "无效：${axis.validationMessage}"
            textSize = 13f
            setTextColor(if (axis.valid) UiKit.TEXT_SECONDARY else UiKit.ERROR)
            setPadding(0, dp(2), 0, 0)
        }, matchWidth())

        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
            if (axis.type == AxisType.SWITCH && axis.valid) addView(
                textButton("制轴", UiKit.ACCENT_DARK, enabled = !locked) {
                    startActivity(
                        Intent(this@MainActivity, SwitchAxisEditorActivity::class.java)
                            .putExtra(SwitchAxisEditorActivity.EXTRA_AXIS_ID, axis.id)
                    )
                }
            )
            addView(textButton("编辑", UiKit.ACCENT_DARK, enabled = !locked) {
                showEditAxisDialog(axis.id, axis.sourceName)
            })
            addView(textButton("删除", UiKit.ERROR, enabled = !locked) { confirmDeleteAxis(axis.id, axis.name) })
        }, matchWidth())

        card.isEnabled = selectable
        card.alpha = if (axis.valid) 1f else 0.75f
        if (selectable) card.setOnClickListener {
            if (axisLibrary.select(axis.id)) {
                syncLegacySelectedAxis()
                refreshAxisLabel()
            }
        }
        return card
    }

    private fun confirmDeleteAxis(axisId: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle("删除轴")
            .setMessage("删除「$name」？此操作不可恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                axisLibrary.remove(axisId)
                syncLegacySelectedAxis()
                refreshAxisLabel()
            }
            .show()
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

    private fun AxisType.label(): String = when (this) {
        AxisType.SEQUENCE -> "顺序"
        AxisType.SWITCH -> "开关"
    }

    private fun primaryButton(text: String, onClick: () -> Unit) = UiKit.primaryButton(this, text, onClick)

    private fun secondaryButton(text: String, onClick: () -> Unit) = UiKit.secondaryButton(this, text, onClick)

    private fun textButton(label: String, color: Int, enabled: Boolean, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = dp(36)
            stateListAnimator = null
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.4f
            setTextColor(color)
            setBackgroundResource(borderlessBackground())
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { onClick() }
        }

    private fun borderlessBackground(): Int {
        val attrs = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        val resource = attrs.getResourceId(0, 0)
        attrs.recycle()
        return resource
    }

    private fun matchWidth(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
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
