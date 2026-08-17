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
import com.kokkoro.clanbattle.axis.PauseFrameTarget
import com.kokkoro.clanbattle.axis.label
import com.kokkoro.clanbattle.config.AppPreferences
import com.kokkoro.clanbattle.control.ControlSafetyState
import com.kokkoro.clanbattle.overlay.DebugOverlayFrame
import com.kokkoro.clanbattle.overlay.DebugRegionOverlay
import com.kokkoro.clanbattle.overlay.OverlayActions
import com.kokkoro.clanbattle.overlay.OverlayController
import com.kokkoro.clanbattle.overlay.ManualPauseUiMode
import com.kokkoro.clanbattle.overlay.resolveOverlayUiState
import com.kokkoro.clanbattle.pauseframe.AndroidOverlayFocusPort
import com.kokkoro.clanbattle.pauseframe.PauseFrameScheduler
import com.kokkoro.clanbattle.pauseframe.PauseFrameSession
import com.kokkoro.clanbattle.pauseframe.PauseFrameState

class ScreenCaptureService : Service(), DisplayManager.DisplayListener {
    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler
    private lateinit var energyThread: HandlerThread
    private lateinit var energyHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var displayManager: DisplayManager
    private lateinit var overlay: OverlayController
    private lateinit var debugRegionOverlay: DebugRegionOverlay
    private var debugRegionOverlayShown = false
    private lateinit var axisLibrary: AxisLibrary
    private lateinit var pauseFrameSession: PauseFrameSession
    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var frameProcessor: FrameProcessor? = null
    private var captureWidth = 0
    private var captureHeight = 0
    private var captureGeneration = 0L
    private var loggedPlaneGeneration = -1L
    private val frameDispatchGate = CaptureFrameDispatchGate(FRAME_INTERVAL_NANOS)
    private var battleLocked = false
    private val captureSessionGate = CaptureSessionGate()
    @Volatile private var captureState = CaptureState.IDLE
    @Volatile private var pauseFrameTarget: PauseFrameTarget? = null
    // Keep recognition paused until the confirmation menu interaction has fully
    // completed; the menu covers the TP HUD and can look like a false UB.
    @Volatile private var pauseFrameProcessingBlocked = false
    @Volatile private var manualPauseMode = ManualPauseUiMode.INACTIVE
    @Volatile private var stopRequested = false
    @Volatile private var latestFrameStatus: FrameStatus? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w("KokkoroCapture", "MediaProjection stopped by system")
            captureState = CaptureState.STOPPED
            captureHandler.post { handleProjectionStopped() }
            mainHandler.post {
                overlay.hide()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun handleProjectionStopped() {
        releaseDisplay()
        val proj = projection
        proj?.unregisterCallback(projectionCallback)
        proj?.stop()
        projection = null
        captureSessionGate.clear()
    }

    override fun onCreate() {
        super.onCreate()
        captureThread = HandlerThread("kokkoro-capture").apply { start() }
        captureHandler = Handler(captureThread.looper)
        energyThread = HandlerThread("kokkoro-tp").apply { start() }
        energyHandler = Handler(energyThread.looper)
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
                reset = { captureHandler.post { prepareNewBattle() } },
                manualPause = ::requestManualPause
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
                    "node=${event.nodeId} target=${event.target?.label()} role=${event.role} " +
                        "action=${event.action} result=${event.result}"
                )
                captureHandler.post { frameProcessor?.recordPauseFrameDiagnostic(event) }
            }
        )
        debugRegionOverlay = DebugRegionOverlay(this)
        frameProcessor = FrameProcessor(
            context = this,
            statusCallback = ::publishStatus,
            pauseFrameCallback = ::onPauseFrameRequested,
            battleLockCallback = ::lockBattle,
            messageCallback = overlay::showPrompt,
            debugOverlayCallback = ::publishDebugOverlay
        )
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("KokkoroCapture", "onStartCommand action=${intent?.action} state=$captureState")
        if (intent?.action == ACTION_STOP) {
            // 先关闸再广播最终状态：采集线程可能还有最后一帧在路上，
            // 不能让它把“已停止”覆盖回帧状态文本。
            stopRequested = true
            captureState = CaptureState.STOPPED
            publishStatus(FrameStatus("已停止", false, 0, captureWidth, captureHeight), force = true)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PREPARE_BATTLE) {
            captureHandler.post { prepareNewBattle() }
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY

        if (captureState != CaptureState.IDLE) {
            Log.w("KokkoroCapture", "Ignoring duplicate capture start: $captureState")
            return START_NOT_STICKY
        }
        captureState = CaptureState.STARTING

        stopRequested = false
        if (!startCaptureForeground()) {
            captureState = CaptureState.IDLE
            return START_NOT_STICKY
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            publishStatus(FrameStatus("截图授权无效", false, 0, 0, 0))
            captureState = CaptureState.IDLE
            stopSelf()
            return START_NOT_STICKY
        }
        val sessionId = intent.getLongExtra(EXTRA_CAPTURE_SESSION_ID, 0L)
        if (sessionId <= 0L) {
            publishStatus(FrameStatus("截图会话无效，请重新授权", false, 0, captureWidth, captureHeight))
            captureState = CaptureState.IDLE
            stopSelf()
            return START_NOT_STICKY
        }
        if (!captureSessionGate.begin(sessionId)) {
            captureState = CaptureState.IDLE
            return START_NOT_STICKY
        }

        captureHandler.post { startCaptureSession(resultCode, data, sessionId) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.w("KokkoroCapture", "onDestroy state=$captureState")
        captureState = CaptureState.STOPPED
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
        debugRegionOverlayShown = false
        debugRegionOverlay.hide()
        overlay.hide()
        captureThread.quitSafely()
        energyThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) = Unit

    override fun onDisplayChanged(displayId: Int) {
        if (displayId == Display.DEFAULT_DISPLAY) captureHandler.post { recreateVirtualDisplay(force = false) }
    }

    private fun startCaptureSession(resultCode: Int, data: Intent, sessionId: Long) {
        Log.i("KokkoroCapture", "startCaptureSession resultCode=$resultCode sessionId=$sessionId")
        val newProjection = try {
            getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(resultCode, data)
                .also { it.registerCallback(projectionCallback, captureHandler) }
        } catch (error: RuntimeException) {
            Log.e("KokkoroCapture", "getMediaProjection failed", error)
            captureSessionGate.fail(sessionId)
            publishStatus(FrameStatus("截图授权启动失败，请重新授权", false, 0, captureWidth, captureHeight))
            return
        }
        Log.i("KokkoroCapture", "projection obtained, creating virtual display")

        releaseDisplay()
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = newProjection
        if (recreateVirtualDisplay(force = true)) {
            // A battle reset belongs to a successfully established capture session.
            // Keeping it after display creation prevents an invalid/short-lived
            // authorization from wiping an already running battle state.
            prepareNewBattle()
            Log.i("KokkoroCapture", "virtual display created, showing overlay")
            captureState = CaptureState.ACTIVE
            mainHandler.post { overlay.show() }
            renderOverlay()
            captureSessionGate.activate(sessionId)
        } else {
            captureState = CaptureState.IDLE
            captureSessionGate.fail(sessionId)
        }
    }

    private fun recreateVirtualDisplay(force: Boolean): Boolean {
        val mediaProjection = projection ?: return false
        val metrics = realDisplayMetrics()
        if (!force && metrics.widthPixels == captureWidth && metrics.heightPixels == captureHeight) return true

        val newWidth = metrics.widthPixels
        val newHeight = metrics.heightPixels
        frameDispatchGate.reset()
        val generation = ++captureGeneration
        val reader = ImageReader.newInstance(
            newWidth,
            newHeight,
            PixelFormat.RGBA_8888,
            IMAGE_READER_MAX_IMAGES
        )
        reader.setOnImageAvailableListener(
            { source -> drainCapturedFrames(source, generation) },
            energyHandler
        )

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
        frameProcessor?.setExternalEnergySampling(enabled = true, reset = true)
        Log.i(
            ENERGY_LOG_TAG,
            "same-display TP fast path enabled maxImages=$IMAGE_READER_MAX_IMAGES"
        )
        publishStatus(FrameStatus("捕获 ${captureWidth}×${captureHeight}", captureWidth > captureHeight, 0, captureWidth, captureHeight))
        return true
    }

    /**
     * Drain frames in presentation order. Every frame is sampled for TP on the
     * lightweight TP thread. At most one image is retained and handed to the
     * original capture thread for full recognition, so slow OCR cannot block TP
     * acquisition or create a growing queue.
     */
    private fun drainCapturedFrames(source: ImageReader, generation: Long) {
        while (true) {
            val image = try {
                source.acquireNextImage()
            } catch (error: IllegalStateException) {
                Log.w(ENERGY_LOG_TAG, "unable to acquire next capture frame", error)
                return
            } ?: return

            if (loggedPlaneGeneration != generation) {
                loggedPlaneGeneration = generation
                val plane = image.planes.firstOrNull()
                Log.i(
                    "KokkoroCapture",
                    "capture-plane generation=$generation image=${image.width}x${image.height} " +
                        "format=${image.format} planes=${image.planes.size} " +
                        "rowStride=${plane?.rowStride} pixelStride=${plane?.pixelStride} " +
                        "bufferLimit=${plane?.buffer?.limit()} bufferCapacity=${plane?.buffer?.capacity()}"
                )
            }

            var handedToSlowProcessor = false
            try {
                if (!captureProcessingAllowed(pauseFrameTarget, pauseFrameProcessingBlocked)) {
                    continue
                }

                // This only copies/scans the narrow TP HUD and is designed to run
                // for every delivered display frame.
                frameProcessor?.sampleEnergy(image)

                val now = SystemClock.elapsedRealtimeNanos()
                val slowFrameLease = frameDispatchGate.tryBeginSlowFrame(now) ?: continue
                // Freeze TP evidence before posting the slow task. The capture
                // thread continues sampling later display frames while OCR is
                // queued/running, so reading the buffer inside process(image)
                // would pair an old banner screenshot with future TP samples.
                val frozenEnergy = frameProcessor?.freezeEnergyForSlowFrame()

                handedToSlowProcessor = captureHandler.post {
                    try {
                        if (
                            generation == captureGeneration &&
                            captureProcessingAllowed(pauseFrameTarget, pauseFrameProcessingBlocked)
                        ) {
                            frameProcessor?.process(image, frozenEnergy)
                        }
                    } catch (error: RuntimeException) {
                        Log.e("KokkoroCapture", "full-frame recognition failed", error)
                    } finally {
                        image.close()
                        frameDispatchGate.completeSlowFrame(slowFrameLease)
                    }
                }
                if (!handedToSlowProcessor) frameDispatchGate.completeSlowFrame(slowFrameLease)
            } finally {
                if (!handedToSlowProcessor) image.close()
            }
        }
    }

    private fun releaseDisplay() {
        ++captureGeneration
        frameDispatchGate.reset()
        frameProcessor?.setExternalEnergySampling(false)
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

    /** 识别线程回调：null 表示叠加层已关闭，需要移除窗口。 */
    private fun publishDebugOverlay(frame: DebugOverlayFrame?) {
        if (frame == null) {
            if (debugRegionOverlayShown) {
                debugRegionOverlayShown = false
                debugRegionOverlay.hide()
            }
            return
        }
        if (!debugRegionOverlayShown) {
            debugRegionOverlayShown = true
            debugRegionOverlay.show()
        }
        debugRegionOverlay.render(frame)
    }

    private fun prepareNewBattle() {
        battleLocked = false
        pauseFrameTarget = null
        pauseFrameProcessingBlocked = false
        manualPauseMode = ManualPauseUiMode.INACTIVE
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
        // In the latched safety state the same overlay button becomes the
        // explicit recovery action.  Recognition is never resumed merely
        // because a trustworthy frame happened to arrive.
        if (latestFrameStatus?.controlSafety == ControlSafetyState.SAFETY_PAUSED) {
            captureHandler.post {
                frameProcessor?.requestSafetyRecovery()
                mainHandler.post { renderOverlay() }
            }
            return
        }
        pauseFrameSession.reset()
        pauseFrameTarget = null
        pauseFrameProcessingBlocked = false
        manualPauseMode = ManualPauseUiMode.INACTIVE
        captureHandler.post { frameProcessor?.requestSafetyPause() }
        renderOverlay()
    }

    private fun confirmPauseFrame() {
        if (manualPauseMode == ManualPauseUiMode.STEPPING) {
            confirmManualPause()
            return
        }
        val accepted = pauseFrameSession.confirm { result ->
            // Serialize reopening capture with the runtime transition so no menu
            // transition image can feed the energy detector first.
            captureHandler.post {
                if (
                    result.readyForConvergence &&
                    result.nodeId != null &&
                    result.confirmedTarget != null
                ) {
                    frameProcessor?.confirmPauseFrame(result.nodeId, result.confirmedTarget)
                } else {
                    frameProcessor?.requestSafetyPause("pause-frame-confirm-failed")
                }
                pauseFrameProcessingBlocked = false
            }
            pauseFrameTarget = null
            renderOverlay()
        }
        if (!accepted.accepted) {
            if (accepted.state == PauseFrameState.FAILED) {
                pauseFrameTarget = null
                pauseFrameProcessingBlocked = false
                captureHandler.post { frameProcessor?.requestSafetyPause("pause-frame-confirm-failed") }
            }
            return
        }
        pauseFrameTarget = null
        renderOverlay()
    }

    private fun requestManualPause() {
        when (manualPauseMode) {
            ManualPauseUiMode.INACTIVE -> enterManualPause()
            ManualPauseUiMode.MENU_HANDOFF -> resumeManualPause()
            ManualPauseUiMode.STEPPING -> Unit
        }
    }

    private fun enterManualPause() {
        if (
            pauseFrameProcessingBlocked ||
            pauseFrameTarget != null ||
            pauseFrameSession.snapshot().state != PauseFrameState.IDLE
        ) return
        pauseFrameProcessingBlocked = true
        val entered = pauseFrameSession.enterManual()
        if (!entered.accepted) {
            pauseFrameSession.reset()
            pauseFrameProcessingBlocked = false
            manualPauseMode = ManualPauseUiMode.INACTIVE
            overlay.showPrompt("手动卡帧失败：无法获取悬浮窗焦点")
            renderOverlay()
            return
        }
        manualPauseMode = ManualPauseUiMode.STEPPING
        renderOverlay()
    }

    private fun confirmManualPause() {
        val confirmed = pauseFrameSession.confirmManual()
        if (!confirmed.accepted) {
            if (confirmed.state == PauseFrameState.FAILED) {
                pauseFrameSession.reset()
                manualPauseMode = ManualPauseUiMode.INACTIVE
                overlay.showPrompt("进入游戏菜单失败，识别已恢复")
                captureHandler.post {
                    frameProcessor?.resumeAfterManualPause()
                    pauseFrameProcessingBlocked = false
                }
            }
            renderOverlay()
            return
        }
        // Keep capture blocked while the menu covers TP/SET. The user performs
        // every menu click and explicitly resumes recognition afterwards.
        manualPauseMode = ManualPauseUiMode.MENU_HANDOFF
        renderOverlay()
    }

    private fun resumeManualPause() {
        val resumed = pauseFrameSession.resumeManual()
        if (!resumed.accepted) return
        manualPauseMode = ManualPauseUiMode.INACTIVE
        captureHandler.post {
            frameProcessor?.resumeAfterManualPause()
            pauseFrameProcessingBlocked = false
        }
        renderOverlay()
    }

    private fun onPauseFrameRequested(nodeId: String, target: PauseFrameTarget) {
        mainHandler.post {
            if (manualPauseMode != ManualPauseUiMode.INACTIVE) {
                pauseFrameSession.reset()
                manualPauseMode = ManualPauseUiMode.INACTIVE
            }
            pauseFrameProcessingBlocked = true
            pauseFrameTarget = target
            val entered = pauseFrameSession.enter(nodeId, target)
            if (!entered.accepted) {
                pauseFrameTarget = null
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
            accessibilityConnected = KokkoroAccessibilityService.isConnected()
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
        val displayStatus = when (manualPauseMode) {
            ManualPauseUiMode.STEPPING -> "手动卡帧中（识别已暂停）"
            ManualPauseUiMode.MENU_HANDOFF -> "菜单操作中（识别已暂停）"
            ManualPauseUiMode.INACTIVE -> statusText
        }
        val displayCurrent = when (manualPauseMode) {
            ManualPauseUiMode.STEPPING -> "当前：逐帧观察"
            ManualPauseUiMode.MENU_HANDOFF -> "当前：请在游戏菜单中手动操作"
            ManualPauseUiMode.INACTIVE -> currentAction
        }
        val displayNext = when (manualPauseMode) {
            ManualPauseUiMode.STEPPING -> "下一：点击进入菜单后由你操作"
            ManualPauseUiMode.MENU_HANDOFF -> "下一：关闭菜单后点击恢复识别"
            ManualPauseUiMode.INACTIVE -> nextAction
        }
        val statusVisible = AppPreferences.clockDebugEnabled(this) ||
            executionWarning != null ||
            safety == ControlSafetyState.SAFETY_PAUSING ||
            safety == ControlSafetyState.SAFETY_PAUSED ||
            manualPauseMode != ManualPauseUiMode.INACTIVE ||
            captureState != CaptureState.ACTIVE
        overlay.render(
            resolveOverlayUiState(
                axisName = name,
                battleLocked = battleLocked,
                pauseFrameRoleLabel = pauseFrameTarget?.label(),
                manualPauseMode = manualPauseMode,
                safetyPaused = safety == ControlSafetyState.SAFETY_PAUSED,
                statusText = displayStatus,
                currentAction = displayCurrent,
                nextAction = displayNext,
                presetA = AppPreferences.pauseFramePresetA(this),
                presetB = AppPreferences.pauseFramePresetB(this)
            ).copy(statusVisible = statusVisible)
        )
    }

    private fun startCaptureForeground(): Boolean {
        try {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                Log.i(
                    "KokkoroCapture",
                    "startForeground fgsType=$fgsType (0x${fgsType.toString(16)}) sdk=${Build.VERSION.SDK_INT}"
                )
                startForeground(NOTIFICATION_ID, notification, fgsType)
                Log.i(
                    "KokkoroCapture",
                    "startForeground succeeded, actualFgsType=0x${foregroundServiceType.toString(16)}"
                )
            } else {
                Log.i("KokkoroCapture", "startForeground legacy sdk=${Build.VERSION.SDK_INT}")
                startForeground(NOTIFICATION_ID, notification)
            }
            return true
        } catch (e: Exception) {
            Log.e("KokkoroCapture", "Failed to start foreground service", e)
            publishStatus(FrameStatus("录屏服务启动失败: ${e.message}", false, 0, 0, 0))
            stopSelf()
            return false
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
        private const val IMAGE_READER_MAX_IMAGES = 4
        private const val PAUSE_FRAME_LOG_TAG = "KokkoroPauseFrame"
        private const val ENERGY_LOG_TAG = "KokkoroEnergy"
    }
}

private enum class CaptureState { IDLE, STARTING, ACTIVE, STOPPED }

fun captureProcessingAllowed(
    pauseFrameTarget: PauseFrameTarget?,
    pauseFrameProcessingBlocked: Boolean = false
): Boolean = pauseFrameTarget == null && !pauseFrameProcessingBlocked
