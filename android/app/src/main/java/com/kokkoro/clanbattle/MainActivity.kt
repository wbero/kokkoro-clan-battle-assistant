package com.kokkoro.clanbattle

import android.Manifest
import android.app.Activity
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
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.kokkoro.clanbattle.capture.ScreenCaptureService
import com.kokkoro.clanbattle.config.AppPreferences

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var axisView: TextView
    private lateinit var dryRunCheckBox: CheckBox
    private lateinit var projectionManager: MediaProjectionManager
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
        setContentView(buildContent())
        refreshAxisLabel()
    }

    override fun onStart() {
        super.onStart()
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

    private fun buildContent(): ScrollView {
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

        content.addView(button("选择轴文件") {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                },
                REQUEST_AXIS
            )
        })

        dryRunCheckBox = CheckBox(this).apply {
            text = "只识别，不执行点击"
            isChecked = AppPreferences.dryRun(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> AppPreferences.setDryRun(this@MainActivity, checked) }
        }
        content.addView(dryRunCheckBox)

        if (BuildConfig.DEBUG) content.addView(CheckBox(this).apply {
            text = "记录识别诊断（时钟＋能量）"
            isChecked = AppPreferences.clockDebugEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> AppPreferences.setClockDebugEnabled(this@MainActivity, checked) }
        })

        content.addView(button("启用无障碍点击") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        content.addView(button("授予悬浮窗权限") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
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
        AppPreferences.saveAxis(this, name, text)
        refreshAxisLabel()
    }

    private fun refreshAxisLabel() {
        axisView.text = "轴文件：${AppPreferences.axisName(this)}"
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_CAPTURE = 100
        const val REQUEST_AXIS = 101
        const val REQUEST_NOTIFICATIONS = 102
        const val GAME_PACKAGE = "com.bilibili.priconne"
    }
}
