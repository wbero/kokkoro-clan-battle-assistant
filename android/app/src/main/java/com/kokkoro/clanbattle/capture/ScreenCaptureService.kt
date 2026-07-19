package com.kokkoro.clanbattle.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.kokkoro.clanbattle.MainActivity
import com.kokkoro.clanbattle.R
import com.kokkoro.clanbattle.automation.KokkoroAccessibilityService
import com.kokkoro.clanbattle.axis.AndroidAxisRepository
import com.kokkoro.clanbattle.axis.AxisLibrary
import com.kokkoro.clanbattle.config.AppPreferences
import com.kokkoro.clanbattle.control.ControlSafetyState
import com.kokkoro.clanbattle.overlay.OverlayActions
import com.kokkoro.clanbattle.overlay.OverlayController
import com.kokkoro.clanbattle.overlay.resolveOverlayUiState
import com.kokkoro.clanbattle.pauseframe.AndroidOverlayFocusPort
import com.kokkoro.clanbattle.pauseframe.PauseFrameScheduler
import com.kokkoro.clanbattle.pauseframe.PauseFrameSession
import com.kokkoro.clanbattle.pauseframe.PauseFrameState
import com.kokkoro.clanbattle.recognition.CharacterRole

class ScreenCaptureService : Service(), DisplayManager.DisplayListener {
    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var displayManager: DisplayManager
    private lateinit var overlay: OverlayController
    private lateinit var axisLibrary: AxisLibrary
    private lateinit var pauseFrameSession: PauseFrameSession
    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var frameProcessor: FrameProcessor? = null
    private var captureWidth = 0
    private var captureHeight = 0
    private var lastProcessedNanos = 0L
    private var battleLocked = false
    private val captureSessionGate = CaptureSessionGate()
    @Volatile private var pauseFrameRole: CharacterRole? = null
    // Keep recognition paused until the confirmation menu interaction has fully
    // completed; the menu covers the TP HUD and can look like a false UB.
    @Volatile private var pauseFrameProcessingBlocked = false
    @Volatile private var stopRequested = false
    private var latestFrameStatus: FrameStatus? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            captureSessionGate.clear()
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        captureThread = HandlerThread("kokkoro-capture").apply { start() }
        captureHandler = Handler(captureThread.looper)
        displayManager = getSystemService(DisplayManager::class.java)
        displayManager.registerDisplayListener(this, captureHandler)
        axisLibrary = AxisLibrary(AndroidAxisRepository(this))
        overlay = OverlayController(
            context = this,
            axesProvider = axisLibrary::list,
            actions = OverlayActions(
                selectAxis = { id -> captureHandler.post { selectAxis(id) } },
                releaseA = { requestRelease(AppPreferences.pauseFramePresetA(this)) },
                releaseB = { requestRelease(AppPreferences.pauseFramePresetB(this)) },
                confirm = ::confirmPauseFrame,
                safetyMenu = ::requestSafetyMenu,
                reset = { captureHandler.post { prepareNewBattle() } }
            )
        )
        pauseFrameSession = PauseFrameSession(
            focusPort = AndroidOverlayFocusPort(
                context = this,
                overlay = overlay,
                dimensions = { captureWidth to captureHeight }
            ),
            scheduler = PauseFrameScheduler { delayMs, action -> mainHandler.postDelayed(action, delayMs) },
            perFrameMs = AppPreferences.pauseFrameMs(this).toLong(),
            menuSettleMs = AppPreferences.pauseFrameMenuWaitMs(this).toLong(),
            diagnosticCallback = { event ->
                Log.i(
                    PAUSE_FRAME_LOG_TAG,
                    "node=${event.nodeId} role=${event.role} action=${event.action} result=${event.result}"
                )
                captureHandler.post { frameProcessor?.recordPauseFrameDiagnostic(event) }
            }
        )
        frameProcessor = FrameProcessor(
            context = this,
            statusCallback = ::publishStatus,
            pauseFrameCallback = ::onPauseFrameRequested,
            battleLockCallback = ::lockBattle,
            messageCallback = overlay::showPrompt
        )
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // 先关闸再广播最终状态：采集线程可能还有最后一帧在路上，
            // 不能让它把“已停止”覆盖回帧状态文本。
            stopRequested = true
            publishStatus(FrameStatus("已停止", false, 0, captureWidth, captureHeight), force = true)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PREPARE_BATTLE) {
            captureHandler.post { prepareNewBattle() }
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY

        stopRequested = false
        startCaptureForeground()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            publishStatus(FrameStatus("截图授权无效", false, 0, 0, 0))
            stopSelf()
            return START_NOT_STICKY
        }
        val sessionId = intent.getLongExtra(EXTRA_CAPTURE_SESSION_ID, 0L)
        if (sessionId <= 0L) {
            publishStatus(FrameStatus("截图会话无效，请重新授权", false, 0, captureWidth, captureHeight))
            stopSelf()
            return START_NOT_STICKY
        }
        if (!captureSessionGate.begin(sessionId)) return START_NOT_STICKY

        captureHandler.post { startCaptureSession(resultCode, data, sessionId) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        displayManager.unregisterDisplayListener(this)
        captureHandler.post {
            releaseDisplay()
            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
            projection = null
            captureSessionGate.clear()
        }
        frameProcessor?.close()
        frameProcessor = null
        axisLibrary.unlock()
        pauseFrameSession.reset()
        overlay.hide()
        captureThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) = Unit

    override fun onDisplayChanged(displayId: Int) {
        if (displayId == Display.DEFAULT_DISPLAY) captureHandler.post { recreateVirtualDisplay(force = false) }
    }

    private fun startCaptureSession(resultCode: Int, data: Intent, sessionId: Long) {
        val newProjection = try {
            getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(resultCode, data)
                .also { it.registerCallback(projectionCallback, captureHandler) }
        } catch (error: RuntimeException) {
            captureSessionGate.fail(sessionId)
            publishStatus(FrameStatus("截图授权启动失败，请重新授权", false, 0, captureWidth, captureHeight))
            return
        }

        releaseDisplay()
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = newProjection
        if (recreateVirtualDisplay(force = true)) {
            // A battle reset belongs to a successfully established capture session.
            // Keeping it after display creation prevents an invalid/short-lived
            // authorization from wiping an already running battle state.
            lastProcessedNanos = 0L
            prepareNewBattle()
            mainHandler.post { overlay.show() }
            renderOverlay()
            captureSessionGate.activate(sessionId)
        } else {
            captureSessionGate.fail(sessionId)
        }
    }

    private fun recreateVirtualDisplay(force: Boolean): Boolean {
        val mediaProjection = projection ?: return false
        val metrics = realDisplayMetrics()
        if (!force && metrics.widthPixels == captureWidth && metrics.heightPixels == captureHeight) return true

        val newWidth = metrics.widthPixels
        val newHeight = metrics.heightPixels
        val reader = ImageReader.newInstance(newWidth, newHeight, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (!captureProcessingAllowed(pauseFrameRole, pauseFrameProcessingBlocked)) {
                    return@setOnImageAvailableListener
                }
                val now = SystemClock.elapsedRealtimeNanos()
                if (now - lastProcessedNanos < FRAME_INTERVAL_NANOS) return@setOnImageAvailableListener
                lastProcessedNanos = now
                frameProcessor?.process(image)
            } finally {
                image.close()
            }
        }, captureHandler)

        val previousReader = imageReader
        try {
            val display = virtualDisplay
            if (display == null) {
                virtualDisplay = mediaProjection.createVirtualDisplay(
                    "KokkoroCapture",
                    newWidth,
                    newHeight,
                    metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    captureHandler
                )
            } else {
                display.resize(newWidth, newHeight, metrics.densityDpi)
                display.surface = reader.surface
            }
            imageReader = reader
            captureWidth = newWidth
            captureHeight = newHeight
            previousReader?.setOnImageAvailableListener(null, null)
            previousReader?.close()
        } catch (error: SecurityException) {
            reader.setOnImageAvailableListener(null, null)
            reader.close()
            publishStatus(FrameStatus("截图授权已失效，请返回助手重新授权", false, 0, captureWidth, captureHeight))
            stopSelf()
            return false
        }
        publishStatus(FrameStatus("捕获 ${captureWidth}×${captureHeight}", captureWidth > captureHeight, 0, captureWidth, captureHeight))
        return true
    }

    private fun releaseDisplay() {
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        imageReader?.close()
        virtualDisplay = null
        imageReader = null
    }

    @Suppress("DEPRECATION")
    private fun realDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    private fun publishStatus(status: FrameStatus, force: Boolean = false) {
        if (stopRequested && !force) return
        latestFrameStatus = status
        renderOverlay(status.controlSafety, status)
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS_TEXT, status.text)
                .putExtra(EXTRA_STATUS_SUCCESS, status.success)
                .putExtra(EXTRA_PROCESSING_MS, status.processingMs)
        )
    }

    private fun prepareNewBattle() {
        battleLocked = false
        pauseFrameRole = null
        pauseFrameProcessingBlocked = false
        mainHandler.post { pauseFrameSession.reset() }
        axisLibrary.unlock()
        val selected = axisLibrary.selectedDocument()
        if (selected != null) frameProcessor?.prepareNewBattle(selected) else frameProcessor?.prepareNewBattle()
        renderOverlay()
        publishStatus(FrameStatus("已重置，等待战斗开始按钮", false, 0, captureWidth, captureHeight))
    }

    private fun selectAxis(id: String) {
        if (battleLocked || !axisLibrary.select(id)) return
        val selected = axisLibrary.selected()
        val text = axisLibrary.selectedText()
        if (selected != null && text != null) AppPreferences.saveAxis(this, selected.name, text)
        axisLibrary.selectedDocument()?.let { frameProcessor?.prepareNewBattle(it) }
        renderOverlay()
    }

    private fun lockBattle() {
        if (battleLocked) return
        battleLocked = true
        axisLibrary.lock()
        renderOverlay()
    }

    private fun requestRelease(frameCount: Int) {
        pauseFrameSession.release(frameCount)
        renderOverlay()
    }

    private fun requestSafetyMenu() {
        pauseFrameSession.reset()
        pauseFrameRole = null
        pauseFrameProcessingBlocked = false
        captureHandler.post { frameProcessor?.requestSafetyPause() }
        renderOverlay()
    }

    private fun confirmPauseFrame() {
        val accepted = pauseFrameSession.confirm { result ->
            // Serialize reopening capture with the runtime transition so no menu
            // transition image can feed the energy detector first.
            captureHandler.post {
                if (result.readyForConvergence && result.nodeId != null) {
                    frameProcessor?.confirmPauseFrame(result.nodeId)
                } else {
                    frameProcessor?.requestSafetyPause("pause-frame-confirm-failed")
                }
                pauseFrameProcessingBlocked = false
            }
            pauseFrameRole = null
            renderOverlay()
        }
        if (!accepted.accepted) {
            if (accepted.state == PauseFrameState.FAILED) {
                pauseFrameRole = null
                pauseFrameProcessingBlocked = false
                captureHandler.post { frameProcessor?.requestSafetyPause("pause-frame-confirm-failed") }
            }
            return
        }
        pauseFrameRole = null
        renderOverlay()
    }

    private fun onPauseFrameRequested(nodeId: String, role: CharacterRole) {
        mainHandler.post {
            pauseFrameProcessingBlocked = true
            pauseFrameRole = role
            val entered = pauseFrameSession.enter(nodeId, role)
            if (!entered.accepted) {
                pauseFrameRole = null
                pauseFrameProcessingBlocked = false
                captureHandler.post { frameProcessor?.requestSafetyPause("pause-frame-focus-failed") }
            }
            renderOverlay()
        }
    }

    private fun renderOverlay(
        safety: ControlSafetyState? = latestFrameStatus?.controlSafety,
        status: FrameStatus? = latestFrameStatus
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { renderOverlay(safety, status) }
            return
        }
        val name = axisLibrary.selected()?.name
        val executionWarning = status?.executionWarning ?: actionExecutionBlockReason(
            dryRun = AppPreferences.dryRun(this),
            accessibilityConnected = KokkoroAccessibilityService.instance != null
        )
        val idlePreview = if (!battleLocked) {
            axisLibrary.selectedDocument()?.let { buildActionPreview(it, activeNodeId = null, clockSeconds = null) }
        } else {
            null
        }
        val statusText = listOfNotNull(executionWarning, status?.text)
            .joinToString("\n")
            .ifBlank { "等待截图状态" }
        val currentAction = idlePreview?.current ?: status?.currentAction ?: "当前：等待触发"
        val nextAction = idlePreview?.next ?: status?.nextAction ?: "下一：无"
        overlay.render(
            resolveOverlayUiState(
                axisName = name,
                battleLocked = battleLocked,
                pauseFrameRoleLabel = pauseFrameRole?.let { "角色${it.ordinal + 1}" },
                safetyPaused = safety == ControlSafetyState.SAFETY_PAUSED,
                statusText = statusText,
                currentAction = currentAction,
                nextAction = nextAction,
                presetA = AppPreferences.pauseFramePresetA(this),
                presetB = AppPreferences.pauseFramePresetB(this)
            )
        )
    }

    private fun startCaptureForeground() {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText("等待游戏横屏")
            .setContentIntent(openApp)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.capture_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val ACTION_START = "com.kokkoro.clanbattle.action.START_CAPTURE"
        const val ACTION_STOP = "com.kokkoro.clanbattle.action.STOP_CAPTURE"
        const val ACTION_PREPARE_BATTLE = "com.kokkoro.clanbattle.action.PREPARE_BATTLE"
        const val ACTION_STATUS = "com.kokkoro.clanbattle.action.CAPTURE_STATUS"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_CAPTURE_SESSION_ID = "capture_session_id"
        const val EXTRA_STATUS_TEXT = "status_text"
        const val EXTRA_STATUS_SUCCESS = "status_success"
        const val EXTRA_PROCESSING_MS = "processing_ms"
        private const val CHANNEL_ID = "kokkoro_capture"
        private const val NOTIFICATION_ID = 1001
        private const val FRAME_INTERVAL_NANOS = 50_000_000L
        private const val PAUSE_FRAME_LOG_TAG = "KokkoroPauseFrame"
    }
}

fun captureProcessingAllowed(
    pauseFrameRole: CharacterRole?,
    pauseFrameProcessingBlocked: Boolean = false
): Boolean = pauseFrameRole == null && !pauseFrameProcessingBlocked
