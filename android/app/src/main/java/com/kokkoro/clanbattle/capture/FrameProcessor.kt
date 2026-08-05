package com.kokkoro.clanbattle.capture

import android.content.Context
import android.media.Image
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.kokkoro.clanbattle.automation.ActionExecutor
import com.kokkoro.clanbattle.automation.GameCoordinateCalibration
import com.kokkoro.clanbattle.automation.GameCoordinateMapper
import com.kokkoro.clanbattle.automation.HorizontalAnchor
import com.kokkoro.clanbattle.automation.KokkoroAccessibilityService
import com.kokkoro.clanbattle.axis.AndroidAxisRepository
import com.kokkoro.clanbattle.axis.AxisDocument
import com.kokkoro.clanbattle.axis.AxisLibrary
import com.kokkoro.clanbattle.axis.AxisParser
import com.kokkoro.clanbattle.axis.AxisType
import com.kokkoro.clanbattle.axis.ActionType
import com.kokkoro.clanbattle.config.AppPreferences
import com.kokkoro.clanbattle.control.AndroidControlTemplateLoader
import com.kokkoro.clanbattle.control.BattleControlObservation
import com.kokkoro.clanbattle.control.BattleControlRecognizer
import com.kokkoro.clanbattle.control.BattleControlObservationFilter
import com.kokkoro.clanbattle.control.BattleControlStateMachine
import com.kokkoro.clanbattle.control.ControlAction
import com.kokkoro.clanbattle.control.ControlCrops
import com.kokkoro.clanbattle.control.ControlObservationSafetyDecision
import com.kokkoro.clanbattle.control.ControlObservationSafetyGate
import com.kokkoro.clanbattle.control.ControlSafetyState
import com.kokkoro.clanbattle.control.FilteredControlObservation
import com.kokkoro.clanbattle.control.ControlStep
import com.kokkoro.clanbattle.control.CoordinatedActionStep
import com.kokkoro.clanbattle.control.OpeningControlTarget
import com.kokkoro.clanbattle.control.VerifiedActionCoordinator
import com.kokkoro.clanbattle.control.VisualToggleState
import com.kokkoro.clanbattle.recognition.AndroidTemplateLoader
import com.kokkoro.clanbattle.recognition.ClockRecognizer
import com.kokkoro.clanbattle.recognition.EnergyDetector
import com.kokkoro.clanbattle.recognition.EnergyDetectionResult
import com.kokkoro.clanbattle.recognition.PixelImage
import com.kokkoro.clanbattle.recognition.RecognitionFilter
import com.kokkoro.clanbattle.recognition.RecognitionResult
import com.kokkoro.clanbattle.scheduler.GameStateDetector
import com.kokkoro.clanbattle.scheduler.GameState
import com.kokkoro.clanbattle.scheduler.BossUbDetector
import com.kokkoro.clanbattle.overlay.DebugBoxTint
import com.kokkoro.clanbattle.overlay.DebugOverlayFrame
import com.kokkoro.clanbattle.overlay.DebugRegionBox
import com.kokkoro.clanbattle.overlay.DebugTpBar
import com.kokkoro.clanbattle.pauseframe.PauseFrameDiagnosticEvent
import com.kokkoro.clanbattle.sequenceaxis.SequenceAxisRuntime
import com.kokkoro.clanbattle.sequenceaxis.SequenceFrameInput
import com.kokkoro.clanbattle.sequenceaxis.SequenceRuntimeCommand
import com.kokkoro.clanbattle.switchaxis.SwitchControlCoordinator
import com.kokkoro.clanbattle.switchaxis.SwitchFrameInput
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class FrameStatus(
    val text: String,
    val success: Boolean,
    val processingMs: Long,
    val frameWidth: Int,
    val frameHeight: Int,
    val controlSafety: ControlSafetyState? = null,
    val currentAction: String = "当前：等待触发",
    val nextAction: String = "下一：无",
    val executionWarning: String? = null
)

data class ActionPreview(val current: String, val next: String)

fun actionExecutionBlockReason(dryRun: Boolean, accessibilityConnected: Boolean): String? = when {
    dryRun -> "只识别模式，不执行点击"
    !accessibilityConnected -> "无障碍服务未启用，点击不会执行"
    else -> null
}

/** 等待有效开场 1:30 期间的悬浮窗状态行；原始 OCR 读数明确标注，避免被误解为已在运行。 */
fun openingWaitStatusText(rawClock: String?, elapsedMs: Long, energyText: String): String =
    "等待有效开场 1:30（原始 ${rawClock ?: "--:--"}）  ${elapsedMs}ms  $energyText"

fun buildActionPreview(
    document: AxisDocument,
    activeNodeId: String?,
    clockSeconds: Int?
): ActionPreview = if (document.type == AxisType.SWITCH) {
    val entries = buildList {
        document.switchOpenings.singleOrNull()?.let { opening ->
            add("opening-1" to "开局 → ${formatTarget(opening.target.rawAuto, opening.target.rawRoles)}")
        }
        document.switchNodes.forEach { node ->
            val trigger = when (val value = node.trigger) {
                com.kokkoro.clanbattle.axis.TimedTrigger -> "定时"
                is com.kokkoro.clanbattle.axis.CharacterUbTrigger -> "${value.rawRole} UB后"
                is com.kokkoro.clanbattle.axis.BossDelayTrigger -> {
                    val delay = value.rawDelay?.let { "+${it}s" }.orEmpty()
                    "BOSS UB后$delay"
                }
                is com.kokkoro.clanbattle.axis.PauseFrameTrigger -> "${value.rawRole} 卡帧"
                else -> "无效触发"
            }
            add(
                node.id to
                    "${formatTime(node.timeSeconds)} $trigger → ${formatTarget(node.target.rawAuto, node.target.rawRoles)}"
            )
        }
    }

    val activeIndex = entries.indexOfFirst { it.first == activeNodeId }
    if (activeIndex >= 0) {
        ActionPreview(
            current = "当前：${entries[activeIndex].second}",
            next = entries.getOrNull(activeIndex + 1)?.second?.let { "下一：$it" } ?: "下一：无"
        )
    } else {
        val upcomingEntry = when {
            document.switchOpenings.singleOrNull() != null &&
                (clockSeconds == null || clockSeconds in 88..90) -> entries.firstOrNull()
            else -> document.switchNodes.firstOrNull { node ->
                clockSeconds == null || node.timeSeconds < clockSeconds
            }?.let { node -> entries.first { it.first == node.id } }
        }
        ActionPreview(
            current = "当前：等待触发",
            next = upcomingEntry?.second?.let { "下一：$it" } ?: "下一：无"
        )
    }
} else {
    val activeIndex = document.events.indexOfFirst { it.id == activeNodeId }
    val activeEvent = document.events.getOrNull(activeIndex)
    val currentEvent = activeEvent ?: clockSeconds?.let { time ->
        document.events.firstOrNull { it.timeSeconds == time }
    }
    val nextEvent = if (activeIndex >= 0) {
        document.events.getOrNull(activeIndex + 1)
    } else {
        document.events.firstOrNull { event -> clockSeconds == null || event.timeSeconds < clockSeconds }
    }
    ActionPreview(
        current = currentEvent?.let { "当前：${formatSequenceEvent(it)}" } ?: "当前：等待触发",
        next = nextEvent?.let { "下一：${formatSequenceEvent(it)}" } ?: "下一：无"
    )
}

fun buildSequenceProgressPreview(
    activeEvent: com.kokkoro.clanbattle.axis.AxisEvent?,
    phase: String?,
    nextEvent: com.kokkoro.clanbattle.axis.AxisEvent?
): ActionPreview {
    val action = activeEvent?.actions?.singleOrNull()
    val current = when {
        activeEvent == null -> "当前：等待触发"
        action?.type == ActionType.CLICK_ROLE && phase in setOf("STARTING", "CONFIRMING_ROLE_ON") ->
            "当前：${formatTime(activeEvent.timeSeconds)} 开启${action.role} SET"
        action?.type == ActionType.CLICK_ROLE && phase == "WAITING_ROLE_UB" ->
            "当前：${formatTime(activeEvent.timeSeconds)} 等待${action.role} UB"
        action?.type == ActionType.CLICK_ROLE && phase == "CONFIRMING_ROLE_OFF" ->
            "当前：${formatTime(activeEvent.timeSeconds)} ${action.role} UB后关闭SET"
        else -> "当前：${formatSequenceEvent(activeEvent)}"
    }
    return ActionPreview(
        current = current,
        next = nextEvent?.let { "下一：${formatSequenceEvent(it)}" } ?: "下一：无"
    )
}

private fun formatTarget(auto: String?, roles: List<String>): String =
    "AUTO${auto ?: "?"} SET:${roles.joinToString("") { toggle ->
        when (toggle) {
            "开" -> "O"
            "关" -> "X"
            else -> "?"
        }
    }}"

private fun formatTime(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

private fun formatSequenceEvent(event: com.kokkoro.clanbattle.axis.AxisEvent): String {
    val trigger = when (val value = event.trigger) {
        com.kokkoro.clanbattle.axis.TimedTrigger -> null
        is com.kokkoro.clanbattle.axis.CharacterUbTrigger -> "${value.rawRole} UB后"
        is com.kokkoro.clanbattle.axis.BossDelayTrigger -> {
            val delay = value.rawDelay?.let { "+${it}s" }.orEmpty()
            "BOSS UB后$delay"
        }
        is com.kokkoro.clanbattle.axis.PauseFrameTrigger -> "${value.rawRole} 卡帧"
        else -> "无效触发"
    }
    val actions = event.actions.joinToString(" + ") { action ->
        when (action.type) {
            ActionType.CLICK_ROLE -> "点击${action.role}"
            ActionType.CLICK_AUTO -> "点击AUTO"
            ActionType.TOGGLE_AUTO -> "AUTO${action.rawValue}"
            ActionType.SET_ROLES -> "SET:${action.values.joinToString("") { if (it == "开") "O" else "X" }}"
            ActionType.NOTIFY -> "提示:${action.message}"
            ActionType.BOSS -> "BOSS"
        }
    }
    return "${formatTime(event.timeSeconds)} " + listOfNotNull(trigger, actions.takeIf(String::isNotEmpty))
        .joinToString(" → ")
}

private data class ControlDetection(
    val observation: BattleControlObservation,
    val crops: ControlCrops
)

private data class ParallelFrameRecognition(
    val clock: RecognitionResult,
    val energy: EnergyDetectionResult?,
    val controls: ControlDetection?
)

private data class SwitchDiagnosticContext(
    val clockSeconds: Int?,
    val triggeredRoles: Set<com.kokkoro.clanbattle.recognition.CharacterRole>,
    val controlsTrustworthy: Boolean,
    val coordinated: com.kokkoro.clanbattle.switchaxis.SwitchCoordinatorResult
)

class FrameProcessor(
    context: Context,
    private val statusCallback: (FrameStatus) -> Unit,
    private val pauseFrameCallback: (String, com.kokkoro.clanbattle.recognition.CharacterRole) -> Unit = { _, _ -> },
    private val battleLockCallback: () -> Unit = {},
    private val messageCallback: (String) -> Unit = {},
    private val debugOverlayCallback: (DebugOverlayFrame?) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val recognizer = ClockRecognizer(AndroidTemplateLoader.load(appContext))
    private val battleTemplates = BattleTemplateLoader.load(appContext)
    private val controlTemplates = AndroidControlTemplateLoader.load(appContext)
    private val controlRecognizer = BattleControlRecognizer(controlTemplates.controls)
    private val controlObservationFilter = BattleControlObservationFilter()
    private val controlObservationSafetyGate = ControlObservationSafetyGate()
    private val controlStateMachine = BattleControlStateMachine()
    private val actionCoordinator = VerifiedActionCoordinator(
        controlStateMachine,
        AppPreferences.roleSetFallbackGraceMs(appContext).toLong()
    )
    private val filter = RecognitionFilter(
        minConfidence = REAL_DEVICE_CLOCK_MIN_CONFIDENCE,
        minAlternativeScore = 0.55,
        maxFailedReads = 999
    )
    private var energyDetector: EnergyDetector? = null
    private var energyHudSize: Pair<Int, Int>? = null
    // TP 采样与完整识别解耦：满 TP 可能只存在一帧（快充 UB），
    // 而完整识别一轮约 60ms，靠它采样会漏掉大部分快充事件。
    private val energyLock = Any()
    @Volatile private var externalEnergySampling = false
    private val energySamples = EnergySampleBuffer()
    private var axis: AxisDocument = emptyAxis()
    private var activeAxisId: String = ""
    private var openingControlTarget: OpeningControlTarget? = null
    private var sequenceRuntime: SequenceAxisRuntime? = null
    private var switchCoordinator: SwitchControlCoordinator? = null
    private val gameStateDetector = GameStateDetector()
    private val bossUbDetector = BossUbDetector(
        earlyConfirmationHoldMs = AppPreferences.bossUbEarlyConfirmationHoldMs(appContext).toLong()
    )
    private val executor = ActionExecutor(appContext, messageCallback)
    private val recognitionThreadId = AtomicInteger(0)
    private val recognitionExecutor = Executors.newFixedThreadPool(RECOGNITION_WORKER_COUNT) { task ->
        Thread(task, "kokkoro-recognition-${recognitionThreadId.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
    private val closed = AtomicBoolean(false)
    // Loading/control confirmation can consume the first few countdown seconds on
    // slower devices. Keep evaluating long enough for the opening target to be
    // installed instead of leaving a switch axis permanently blocked at opening.
    private val sessionGate = BattleSessionGate(openingGraceSeconds = OPENING_GRACE_SECONDS)
    private var recorder: ClockDebugRecorder? = null
    private var frameId = 0L
    private var debugEnabled = false
    private var regionOverlayEnabled = false
    private var centerAnchorCalibrated = false
    private var rightAnchorCalibrated = false
    private var topHudAnchorCalibrated = false
    private var rightControlAnchorCalibrated = false
    private var loadingAnchorCalibrated = false
    private var lastDebugPreferenceCheckMs = Long.MIN_VALUE
    private var openingControlsConfirmed = true
    private var lastPauseFrameNodeId: String? = null
    private var lastSwitchDebugKey: String? = null
    private var lastSwitchDiagnosticContext: SwitchDiagnosticContext? = null
    private var lastPromptNodeId: String? = null
    // UB animation can cover all SET/AUTO badges for a short, expected window.
    // Hold the last trustworthy control state during that window instead of
    // turning the animation into an automatic safety pause.
    private var controlTransientHoldUntilMs = 0L
    @Volatile private var roleTapSafe = false
    // Tracks whether the switch-axis runtime is currently busy (node armed,
    // converging, or pause-frame entered) so that the safety gate can hold
    // instead of counting untrusted frames during a legitimate wait.
    private var switchAxisBusy = false

    init {
        installAxis(loadSelectedAxis())
        controlStateMachine.setDesired(openingControlTarget)
        openingControlsConfirmed = openingControlTarget == null
        lastPauseFrameNodeId = null
        lastSwitchDebugKey = null
        lastSwitchDiagnosticContext = null
        lastPromptNodeId = null
    }

    fun prepareNewBattle(document: AxisDocument = loadSelectedAxis()) {
        GameCoordinateCalibration.reset()
        centerAnchorCalibrated = false
        rightAnchorCalibrated = false
        topHudAnchorCalibrated = false
        rightControlAnchorCalibrated = false
        loadingAnchorCalibrated = false
        filter.reset()
        resetEnergySampling() // 置空强制重建，使新战斗读到最新 UB 阈值配置
        gameStateDetector.reset()
        bossUbDetector.configureEarlyConfirmationHoldMs(
            AppPreferences.bossUbEarlyConfirmationHoldMs(appContext).toLong()
        )
        bossUbDetector.reset()
        controlStateMachine.reset()
        controlObservationFilter.reset()
        controlObservationSafetyGate.reset()
        controlTransientHoldUntilMs = 0L
        actionCoordinator.configureRoleSetFallbackGraceMs(
            AppPreferences.roleSetFallbackGraceMs(appContext).toLong()
        )
        actionCoordinator.reset()
        installAxis(document)
        controlStateMachine.setDesired(openingControlTarget)
        openingControlsConfirmed = openingControlTarget == null
        lastPauseFrameNodeId = null
        lastPromptNodeId = null
        switchAxisBusy = false
        sessionGate.prepare()
        val wasDebugEnabled = debugEnabled
        refreshDebugPreference(SystemClock.elapsedRealtime(), force = true)
        if (debugEnabled && wasDebugEnabled) recorder().startSession()
    }

    fun process(image: Image) {
        if (closed.get()) return
        val start = SystemClock.elapsedRealtime()
        refreshDebugPreference(start)
        val currentFrameId = ++frameId
        if (image.width <= image.height) {
            roleTapSafe = false
            if (debugEnabled) recordEarlyFailure(currentFrameId, "portrait-frame")
            statusCallback(FrameStatus("等待游戏横屏 ${image.width}×${image.height}", false, 0, image.width, image.height))
            return
        }

        if (sessionGate.isWaitingForStart()) {
            roleTapSafe = false
            // 编组/结算等非战斗画面里能量条位置放的是别的 UI，采样线程读出的
            // 比例毫无意义。丢掉这期间累积的事件，避免开战第一帧收到一批假 UB。
            energySamples.reset()
            val battleScore = matchRegion(
                image, BattleReferenceRegions.START_BUTTON, battleTemplates.startBattle,
                HorizontalAnchor.CENTER,
                calibrate = true,
                calibrationThreshold = TEMPLATE_THRESHOLD
            )
            // 公会战更新后模拟战的按钮文字变成"模拟战开始"，正式战斗模板不再命中。
            // 正式模板没命中时再试模拟战文字模板，命中任意一个都算进入战斗。
            val simulationScore = if (battleScore >= TEMPLATE_THRESHOLD) {
                Double.NEGATIVE_INFINITY
            } else {
                matchRegion(
                    image,
                    BattleReferenceRegions.SIMULATION_START_BUTTON,
                    battleTemplates.simulationStartBattle,
                    HorizontalAnchor.CENTER,
                    calibrate = true,
                    calibrationThreshold = TEMPLATE_THRESHOLD
                )
            }
            val score = maxOf(battleScore, simulationScore)
            if (score >= TEMPLATE_THRESHOLD) {
                sessionGate.onStartMatched()
                battleLockCallback()
            }
            if (debugEnabled) {
                recordEarlyFailure(
                    currentFrameId,
                    "waiting-start-template score=${"%.4f".format(Locale.US, battleScore)}" +
                        " sim=${"%.4f".format(Locale.US, simulationScore)}"
                )
            }
            publishDebugOverlay(image, null, null)
            publishWaitingStatus("等待战斗开始按钮", score, start, image)
            return
        }
        if (sessionGate.isWaitingForLoading()) {
            roleTapSafe = false
            energySamples.reset()
            val score = matchRegion(
                image,
                BattleReferenceRegions.LOADING,
                battleTemplates.loading,
                HorizontalAnchor.LOADING,
                calibrate = true,
                calibrationThreshold = TEMPLATE_THRESHOLD
            )
            if (score >= TEMPLATE_THRESHOLD) sessionGate.onLoadingMatched()
            if (sessionGate.isWaitingForLoading()) {
                val menuScore = matchRegion(
                    image, BattleReferenceRegions.MENU_BUTTON, controlTemplates.menu,
                    HorizontalAnchor.TOP_HUD,
                    calibrate = true,
                    calibrationThreshold = MENU_TRUST_THRESHOLD
                )
                sessionGate.observeBattleHud(menuScore >= MENU_TRUST_THRESHOLD)
            }
            if (!sessionGate.isWaitingForLoading()) {
                // Continue with the same frame: the loading transition may have
                // completed between two 50 ms samples on faster devices.
            } else {
                if (debugEnabled) recordEarlyFailure(currentFrameId, "waiting-loading-template score=${"%.4f".format(Locale.US, score)}")
                publishDebugOverlay(image, null, null)
                publishWaitingStatus("等待加载界面", score, start, image)
                return
            }
        }

        val menuScore = matchRegion(
            image, BattleReferenceRegions.MENU_BUTTON, controlTemplates.menu,
            HorizontalAnchor.TOP_HUD,
            calibrate = true,
            calibrationThreshold = MENU_TRUST_THRESHOLD
        )
        calibrateRightControlAnchor(image)
        // Resolve the independent top-HUD anchor from MENU before cropping the
        // clock. Honor Win and MuMu place this HUD differently despite sharing
        // the same ultrawide aspect ratio.
        val parallelRecognition = recognizeFrameInParallel(image)
        if (closed.get()) return
        val recognition = parallelRecognition.clock
        val energy = if (externalEnergySampling) energySamples.take() else parallelRecognition.energy
        val controlDetection = parallelRecognition.controls
        val filteredControls = controlDetection?.observation?.let(controlObservationFilter::update)
            ?: controlObservationFilter.missing()
        val controls = filteredControls.observation
        val trustworthyControls = controls.takeIf { filteredControls.trustworthy }
        roleTapSafe = filteredControls.trustworthy && menuScore >= MENU_TRUST_THRESHOLD
        // 画未经跨帧过滤的原始观察：调试时需要看到识别器当帧真正读到什么，
        // 而不是被过滤器锁住的旧稳定状态。
        publishDebugOverlay(image, controlDetection?.observation, energy)
        if (!sessionGate.shouldEvaluate(recognition.timeSeconds)) {
            if (debugEnabled) recorder().record(currentFrameId, System.currentTimeMillis(), start, sessionGate.debugState(), recognition, null, energy)
            val elapsed = SystemClock.elapsedRealtime() - start
            statusCallback(
                FrameStatus(
                    openingWaitStatusText(recognition.rawText, elapsed, EnergyStatusFormatter.format(energy)),
                    false,
                    elapsed,
                    image.width,
                    image.height
                )
            )
            return
        }

        val filtered = filter.update(recognition, SystemClock.elapsedRealtime())
        if (debugEnabled) recorder().record(currentFrameId, System.currentTimeMillis(), start, sessionGate.debugState(), recognition, filtered, energy)
        val usable = filtered.accepted || filtered.reason == "same-time"
        val sessionReady = usable && sessionGate.onAccepted(filtered.timeSeconds)
        val battleRunning = !sessionGate.isWaiting()
        val triggeredRoles = energy?.triggeredRoles.orEmpty()
        val tpBelowThresholdRoles = energy?.characters
            ?.filterValues { it.blueRatio < AppPreferences.energyDropThreshold(appContext) }
            ?.keys
            .orEmpty()
        val tpFullRoles = energy?.characters
            ?.filterValues { it.isFull }
            ?.keys
            .orEmpty()
        val acceptedClockSeconds = filtered.timeSeconds.takeIf { usable }
        val gameState = if (battleRunning) {
            gameStateDetector.update(acceptedClockSeconds, energy)
        } else {
            null
        }
        // Arm the bounded recognition hold only on the first frame that starts a
        // character-UB lifecycle. Repeated noisy TP triggers during the same
        // animation must not keep extending the safety exemption forever.
        if (triggeredRoles.isNotEmpty() && gameState == GameState.CHARACTER_UB) {
            controlTransientHoldUntilMs = start + UB_CONTROL_MAX_HOLD_MS
        }
        val holdControlsForCharacterUb = shouldHoldControlRecognitionForCharacterUb(
            gameState,
            start,
            controlTransientHoldUntilMs
        )
        if (gameState != null && !gameState.isCharacterUbActive()) {
            controlTransientHoldUntilMs = 0L
        }
        if (battleRunning && axis.type == AxisType.SEQUENCE) {
            actionCoordinator.observeFrame(
                triggeredRoles,
                acceptedClockSeconds,
                start,
                tpBelowThresholdRoles,
                tpFullRoles,
                energy?.visualObstruction == true
            )
            sequenceRuntime?.observeRoleUbEvents(triggeredRoles)
        }
        if (menuScore < MENU_TRUST_THRESHOLD) bossUbDetector.suspend()
        val detectedBossUb = if (sessionReady && menuScore >= MENU_TRUST_THRESHOLD) {
            bossUbDetector.update(
                requireNotNull(filtered.timeSeconds),
                energy?.triggeredRoles.orEmpty(),
                start,
                energy?.visualObstruction == true
            )
        } else {
            null
        }
        detectedBossUb?.let { event ->
            Log.i(
                BOSS_UB_LOG_TAG,
                "detected clock=${event.heldClockSeconds} holdMs=${event.holdDurationMs} " +
                    "detectedAt=${event.detectedAtWallMs} early=${event.early}"
            )
        }
        val bossUbEvent = bossUbDetector.latestEvent(start)

        var scheduleReason: String? = null
        var controlStep: ControlStep = controlStateMachine.snapshot()
        var activeNodeId: String? = null
        var sequenceProgress: CoordinatedActionStep? = null
        var openingConfirmedThisFrame = false
        val executionWarning = actionExecutionBlockReason(
            dryRun = AppPreferences.dryRun(appContext),
            accessibilityConnected = KokkoroAccessibilityService.instance != null
        )
        if (battleRunning && executionWarning == null) {
            controlStep = updateControls(
                filteredControls,
                menuScore,
                start,
                image,
                holdControlsForCharacterUb
            )
            if (axis.type == AxisType.SWITCH) {
                if (sessionReady) {
                    val controlsTrustworthy = roleTapSafe &&
                        controlStep.safety == ControlSafetyState.RUNNING
                    val coordinated = requireNotNull(switchCoordinator).update(
                        SwitchFrameInput(
                            clockSeconds = filtered.timeSeconds,
                            triggeredRoles = triggeredRoles,
                            controlsTrustworthy = controlsTrustworthy,
                            wallMs = start,
                            bossUbEvent = bossUbEvent
                        ),
                        controlStep
                    )
                    switchAxisBusy = coordinated.busy
                    activeNodeId = coordinated.activeNodeId
                    activeNodeId?.let { nodeId ->
                        if (nodeId != lastPromptNodeId) {
                            val message = if (nodeId == "opening-1") {
                                axis.switchOpenings.singleOrNull()?.target?.message
                            } else {
                                axis.switchNodes.firstOrNull { it.id == nodeId }?.target?.message
                            }
                            message?.takeIf(String::isNotBlank)?.let(messageCallback)
                            lastPromptNodeId = nodeId
                        }
                    }
                    controlStep = coordinated.controlStep
                    coordinated.pauseFrame?.let { request ->
                        if (lastPauseFrameNodeId != request.nodeId) {
                            lastPauseFrameNodeId = request.nodeId
                            pauseFrameCallback(request.nodeId, request.role)
                        }
                    }
                    if (debugEnabled) {
                        recordSwitchTransition(
                            currentFrameId,
                            System.currentTimeMillis(),
                            filtered.timeSeconds,
                            triggeredRoles,
                            controlsTrustworthy,
                            coordinated
                        )
                    }
                    scheduleReason = when {
                        coordinated.pauseFrame != null -> "pause-frame:${coordinated.pauseFrame.role.name}"
                        coordinated.activeNodeId != null -> "switch-node:${coordinated.activeNodeId}"
                        else -> "switch-waiting"
                    }
                } else {
                    scheduleReason = "switch-clock-gate"
                }
            } else {
                if (controlStep.confirmed && !openingControlsConfirmed) {
                    openingConfirmedThisFrame = true
                    openingControlsConfirmed = true
                    controlStateMachine.setDesired(null)
                }
                if (openingControlsConfirmed && controlStep.safety == ControlSafetyState.RUNNING) {
                    var coordinated = actionCoordinator.update(
                        controlStep,
                        start,
                        triggeredRoles,
                        acceptedClockSeconds,
                        tpBelowThresholdRoles,
                        tpFullRoles,
                        energy?.visualObstruction == true
                    )
                    sequenceProgress = coordinated
                    executeControlAction(coordinated.newControlAction, image.width, image.height)
                    executor.execute(coordinated.immediateEvents, image.width, image.height, axis.clickIntervalMs)
                    controlStep = coordinated.controlStep

                    val runtime = requireNotNull(sequenceRuntime)
                    if (sessionReady) {
                        val command = runtime.update(
                            SequenceFrameInput(
                                clockSeconds = filtered.timeSeconds,
                                triggeredRoles = triggeredRoles,
                                controlsTrustworthy = roleTapSafe,
                                wallMs = start,
                                schedulingAllowed = !coordinated.busy &&
                                    gameState != GameState.CHARACTER_UB &&
                                    gameState != GameState.UB_ANIMATION,
                                bossUbEvent = bossUbEvent,
                                roleChainSchedulingAllowed = !coordinated.busy
                            )
                        )
                        activeNodeId = runtime.snapshot().activeEvent?.id
                        when (command) {
                            SequenceRuntimeCommand.None -> {
                                scheduleReason = when {
                                    coordinated.busy -> "verified-control-action"
                                    activeNodeId != null -> "sequence-trigger:$activeNodeId"
                                    else -> "sequence-waiting"
                                }
                            }
                            is SequenceRuntimeCommand.EnterPauseFrame -> {
                                activeNodeId = command.nodeId
                                actionCoordinator.clearRecentRoleUb(command.role)
                                if (lastPauseFrameNodeId != command.nodeId) {
                                    lastPauseFrameNodeId = command.nodeId
                                    pauseFrameCallback(command.nodeId, command.role)
                                }
                                scheduleReason = "pause-frame:${command.role.name}"
                            }
                            is SequenceRuntimeCommand.Dispatch -> {
                                actionCoordinator.enqueue(
                                    listOf(command.event),
                                    command.rolesAlreadySet,
                                    start
                                )
                                coordinated = actionCoordinator.update(
                                    controlStep,
                                    start,
                                    triggeredRoles,
                                    filtered.timeSeconds,
                                    tpBelowThresholdRoles,
                                    tpFullRoles,
                                    energy?.visualObstruction == true
                                )
                                sequenceProgress = coordinated
                                executeControlAction(coordinated.newControlAction, image.width, image.height)
                                executor.execute(
                                    coordinated.immediateEvents,
                                    image.width,
                                    image.height,
                                    axis.clickIntervalMs
                                )
                                controlStep = coordinated.controlStep
                                scheduleReason = if (coordinated.busy) {
                                    "verified-control-action"
                                } else {
                                    "sequence-dispatched:${command.event.id}"
                                }
                            }
                        }
                    } else {
                        activeNodeId = runtime.snapshot().activeEvent?.id
                        scheduleReason = if (coordinated.busy) {
                            "verified-control-action"
                        } else {
                            "sequence-clock-gate"
                        }
                    }
                } else {
                    scheduleReason = "control-state-gate"
                }
            }
        } else if (battleRunning) {
            controlStep = trustworthyControls?.let(controlStateMachine::observeOnly) ?: controlStateMachine.snapshot()
            scheduleReason = "execution-blocked"
        }

        val elapsed = SystemClock.elapsedRealtime() - start
        if (debugEnabled && battleRunning) {
            recorder().recordControls(
                currentFrameId,
                System.currentTimeMillis(),
                controls,
                controlStep,
                menuScore,
                controlDetection?.crops
            )
        }
        val source = filtered.source?.name?.lowercase() ?: "-"
        val energyText = EnergyStatusFormatter.format(energy, gameState, scheduleReason)
        val controlText = ControlStatusFormatter.format(controlStep, openingConfirmedThisFrame)
        val actionPreview = if (axis.type == AxisType.SEQUENCE) {
            val runtime = sequenceRuntime?.snapshot()
            val progress = sequenceProgress
            when {
                progress?.activeEvent != null -> buildSequenceProgressPreview(
                    progress.activeEvent,
                    progress.phase,
                    progress.nextEvent ?: runtime?.activeEvent ?: runtime?.nextEvent
                )
                runtime?.activeEvent != null -> ActionPreview(
                    current = "当前：${formatSequenceEvent(runtime.activeEvent)}",
                    next = runtime.nextEvent?.let { "下一：${formatSequenceEvent(it)}" } ?: "下一：无"
                )
                else -> ActionPreview(
                    current = "当前：等待触发",
                    next = runtime?.nextEvent?.let { "下一：${formatSequenceEvent(it)}" } ?: "下一：无"
                )
            }
        } else {
            buildActionPreview(axis, activeNodeId, filtered.timeSeconds)
        }
        val text = if (sessionReady) {
            "${filtered.rawText}  $source  ${elapsed}ms  $energyText\n$controlText"
        } else if (sessionGate.isWaiting()) {
            openingWaitStatusText(recognition.rawText, elapsed, energyText)
        } else {
            "FAIL ${filtered.reason ?: recognition.reason}  ${recognition.rawText ?: "--:--"}  ${elapsed}ms  $energyText"
        }
        statusCallback(
            FrameStatus(
                text,
                sessionReady,
                elapsed,
                image.width,
                image.height,
                controlStep.safety.takeIf { battleRunning },
                actionPreview.current,
                actionPreview.next,
                executionWarning.takeIf { battleRunning }
            )
        )
    }

    fun requestSafetyPause(reason: String = "manual-safety-menu") {
        Log.w(SAFETY_LOG_TAG, "source=request reason=$reason")
        controlStateMachine.forceSafety(reason)
    }

    /** Arms the latched safety pause for an explicit user recovery attempt. */
    fun requestSafetyRecovery(): Boolean = controlStateMachine.requestSafetyRecovery()

    fun confirmPauseFrame(nodeId: String) {
        switchCoordinator?.confirmPauseFrame(nodeId)
        sequenceRuntime?.confirmPauseFrame(nodeId)
    }

    /**
     * Rebuild recognition baselines after the user has operated the game menu
     * while manual card-frame mode was active. Axis runtimes stay in place, but
     * stale TP/control/menu evidence must not be interpreted as a new event.
     */
    fun resumeAfterManualPause() {
        filter.reset()
        resetEnergySampling()
        controlObservationFilter.reset()
        controlObservationSafetyGate.reset()
        controlTransientHoldUntilMs = 0L
        bossUbDetector.reset()
        gameStateDetector.reset()
        controlStateMachine.abandonPendingAction()
        actionCoordinator.restartAfterRecognitionPause()
        sequenceRuntime?.clearRecognitionEvidence()
        switchCoordinator?.clearRecognitionEvidence()
        switchAxisBusy = false
        roleTapSafe = false
    }

    fun recordPauseFrameDiagnostic(event: PauseFrameDiagnosticEvent) {
        if (!debugEnabled) return
        val context = lastSwitchDiagnosticContext ?: return
        writeSwitchDiagnostic(
            currentFrameId = frameId,
            wallMs = System.currentTimeMillis(),
            context = context,
            focusAction = event.action.takeIf {
                it == "focus-acquire" || it == "focus-release" || it == "back"
            }.orEmpty(),
            focusResult = event.result,
            pauseFrameAction = event.action.takeIf {
                it != "focus-acquire" && it != "focus-release" && it != "back"
            }.orEmpty(),
            targetRole = event.role
        )
    }

    fun isRoleTapSafe(): Boolean = roleTapSafe

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Let the at-most-three current ROI tasks finish. shutdownNow() can remove
        // a queued Future before it starts and leave the capture thread waiting on it.
        recognitionExecutor.shutdown()
        executor.close()
        recorder?.close()
        recorder = null
    }

    private fun recorder(): ClockDebugRecorder = recorder ?: ClockDebugRecorder(appContext).also { recorder = it }

    private fun loadSelectedAxis(): AxisDocument =
        AxisLibrary(AndroidAxisRepository(appContext)).selectedDocument()
            ?: runCatching { AxisParser.parse(AppPreferences.axisText(appContext)) }.getOrElse { emptyAxis() }

    private fun installAxis(document: AxisDocument) {
        axis = document
        actionCoordinator.configureRoleAliases(document.header)
        activeAxisId = AppPreferences.selectedAxisId(appContext).orEmpty()
        openingControlTarget = if (document.type == AxisType.SEQUENCE) {
            OpeningControlTarget.from(document)
        } else {
            null
        }
        sequenceRuntime = if (document.type == AxisType.SEQUENCE) {
            SequenceAxisRuntime(sequenceEvents(document))
        } else {
            null
        }
        switchCoordinator = if (document.type == AxisType.SWITCH) {
            SwitchControlCoordinator(
                stateMachine = controlStateMachine,
                opening = document.switchOpenings.singleOrNull(),
                nodes = document.switchNodes,
                openingGraceSeconds = OPENING_GRACE_SECONDS
            )
        } else {
            null
        }
    }

    private fun sequenceEvents(document: AxisDocument) = document.events.mapNotNull { event ->
        val actions = if (event.timeSeconds == 90) event.actions.filterNot {
            it.type == ActionType.TOGGLE_AUTO || it.type == ActionType.SET_ROLES
        } else {
            event.actions
        }
        if (actions.isEmpty() && event.trigger !is com.kokkoro.clanbattle.axis.PauseFrameTrigger) {
            null
        } else {
            event.copy(actions = actions)
        }
    }

    private fun refreshDebugPreference(nowMs: Long, force: Boolean = false) {
        if (!force && lastDebugPreferenceCheckMs != Long.MIN_VALUE && nowMs - lastDebugPreferenceCheckMs < DEBUG_PREFERENCE_POLL_MS) return
        lastDebugPreferenceCheckMs = nowMs
        val overlayEnabled = AppPreferences.regionOverlayEnabled(appContext)
        if (overlayEnabled != regionOverlayEnabled) {
            regionOverlayEnabled = overlayEnabled
            // 关闭时立刻清空，避免最后一帧的框停留在画面上。
            if (!overlayEnabled) debugOverlayCallback(null)
        }
        val enabled = AppPreferences.clockDebugEnabled(appContext)
        if (enabled == debugEnabled) return
        debugEnabled = enabled
        if (enabled) recorder().startSession() else {
            recorder?.close()
            recorder = null
        }
    }

    private fun recordEarlyFailure(currentFrameId: Long, reason: String) {
        recorder().record(
            currentFrameId,
            System.currentTimeMillis(),
            SystemClock.elapsedRealtime(),
            sessionGate.debugState(),
            RecognitionResult(ok = false, reason = reason),
            null
        )
    }

    private fun matchRegion(
        image: Image,
        region: ReferenceRegion,
        template: com.kokkoro.clanbattle.recognition.PixelImage,
        anchor: HorizontalAnchor,
        calibrate: Boolean = false,
        calibrationThreshold: Double = CALIBRATION_MIN_SCORE
    ): Double {
        val scaled = ImageRoiExtractor.scaleRegion(
            image.width,
            image.height,
            region.x,
            region.y,
            region.width,
            region.height,
            anchor
        )
        val alreadyCalibrated = when (anchor) {
            HorizontalAnchor.CENTER -> centerAnchorCalibrated
            HorizontalAnchor.RIGHT -> rightAnchorCalibrated
            HorizontalAnchor.TOP_HUD -> topHudAnchorCalibrated
            HorizontalAnchor.RIGHT_CONTROL -> rightControlAnchorCalibrated
            HorizontalAnchor.LOADING -> loadingAnchorCalibrated
        }
        if (!calibrate || alreadyCalibrated) {
            return FixedTemplateMatcher.score(ImageRoiExtractor.extract(image, scaled), template)
        }

        val radius = when (anchor) {
            HorizontalAnchor.CENTER -> CENTER_SEARCH_RADIUS
            HorizontalAnchor.RIGHT -> RIGHT_SEARCH_RADIUS
            HorizontalAnchor.TOP_HUD -> maxOf(
                TOP_HUD_MIN_SEARCH_RADIUS,
                (GameCoordinateMapper.viewport(image.width, image.height).spareX / 2f).toInt() +
                    TOP_HUD_SEARCH_MARGIN
            )
            HorizontalAnchor.RIGHT_CONTROL -> rightControlSearchRadius(image.width, image.height)
            HorizontalAnchor.LOADING -> maxOf(
                LOADING_MIN_SEARCH_RADIUS,
                (GameCoordinateMapper.viewport(image.width, image.height).spareX / 2f).toInt() +
                    LOADING_SEARCH_MARGIN
            )
        }
        var bestScore = Double.NEGATIVE_INFINITY
        var bestDelta = 0
        fun consider(delta: Int) {
            val candidate = Rect(scaled).apply { offset(delta, 0) }
            if (candidate.left < 0 || candidate.right > image.width) return
            val score = FixedTemplateMatcher.score(ImageRoiExtractor.extract(image, candidate), template)
            if (score > bestScore) {
                bestScore = score
                bestDelta = delta
            }
        }

        for (delta in -radius..radius step CALIBRATION_COARSE_STEP) consider(delta)
        val coarseBestDelta = bestDelta
        val fineStart = (coarseBestDelta - CALIBRATION_COARSE_STEP).coerceAtLeast(-radius)
        val fineEnd = (coarseBestDelta + CALIBRATION_COARSE_STEP).coerceAtMost(radius)
        for (delta in fineStart..fineEnd) consider(delta)

        if (bestScore >= calibrationThreshold) {
            val previous = GameCoordinateCalibration.horizontalDelta(anchor)
            GameCoordinateCalibration.update(anchor, previous + bestDelta)
            Log.i(
                CALIBRATION_LOG_TAG,
                "anchor=$anchor delta=${previous + bestDelta} step=$bestDelta " +
                    "score=${"%.4f".format(Locale.US, bestScore)} size=${image.width}x${image.height}"
            )
            when (anchor) {
                HorizontalAnchor.CENTER -> centerAnchorCalibrated = true
                HorizontalAnchor.RIGHT -> rightAnchorCalibrated = true
                HorizontalAnchor.TOP_HUD -> topHudAnchorCalibrated = true
                HorizontalAnchor.RIGHT_CONTROL -> rightControlAnchorCalibrated = true
                HorizontalAnchor.LOADING -> loadingAnchorCalibrated = true
            }
        }
        return bestScore
    }

    private fun calibrateRightControlAnchor(image: Image) {
        if (rightControlAnchorCalibrated) return

        val autoBase = ImageRoiExtractor.scaleRegion(
            image.width,
            image.height,
            BattleReferenceRegions.AUTO_BUTTON.x,
            BattleReferenceRegions.AUTO_BUTTON.y,
            BattleReferenceRegions.AUTO_BUTTON.width,
            BattleReferenceRegions.AUTO_BUTTON.height,
            HorizontalAnchor.RIGHT_CONTROL
        )
        val globalBase = ImageRoiExtractor.scaleRegion(
            image.width,
            image.height,
            BattleReferenceRegions.GLOBAL_SET_BUTTON.x,
            BattleReferenceRegions.GLOBAL_SET_BUTTON.y,
            BattleReferenceRegions.GLOBAL_SET_BUTTON.width,
            BattleReferenceRegions.GLOBAL_SET_BUTTON.height,
            HorizontalAnchor.RIGHT_CONTROL
        )

        var bestScore = Double.NEGATIVE_INFINITY
        var bestDelta = 0
        fun consider(delta: Int) {
            val autoCandidate = Rect(autoBase).apply { offset(delta, 0) }
            val globalCandidate = Rect(globalBase).apply { offset(delta, 0) }
            if (
                autoCandidate.left < 0 || autoCandidate.right > image.width ||
                globalCandidate.left < 0 || globalCandidate.right > image.width
            ) return

            val autoCrop = ImageRoiExtractor.extract(image, autoCandidate)
            val globalCrop = ImageRoiExtractor.extract(image, globalCandidate)
            val autoScore = maxOf(
                FixedTemplateMatcher.score(autoCrop, controlTemplates.controls.autoOn),
                FixedTemplateMatcher.score(autoCrop, controlTemplates.controls.autoOff)
            )
            val globalScore = maxOf(
                FixedTemplateMatcher.score(globalCrop, controlTemplates.controls.globalSetOn),
                FixedTemplateMatcher.score(globalCrop, controlTemplates.controls.globalSetOff)
            )
            val score = (autoScore + globalScore) / 2.0
            if (score > bestScore) {
                bestScore = score
                bestDelta = delta
            }
        }

        val searchRadius = rightControlSearchRadius(image.width, image.height)
        for (delta in -searchRadius..searchRadius step CALIBRATION_COARSE_STEP) {
            consider(delta)
        }
        val fineStart = (bestDelta - CALIBRATION_COARSE_STEP).coerceAtLeast(-searchRadius)
        val fineEnd = (bestDelta + CALIBRATION_COARSE_STEP).coerceAtMost(searchRadius)
        for (delta in fineStart..fineEnd) consider(delta)

        if (bestScore >= RIGHT_CONTROL_CALIBRATION_THRESHOLD) {
            GameCoordinateCalibration.update(HorizontalAnchor.RIGHT_CONTROL, bestDelta.toFloat())
            rightControlAnchorCalibrated = true
            Log.i(
                CALIBRATION_LOG_TAG,
                "anchor=${HorizontalAnchor.RIGHT_CONTROL} delta=$bestDelta " +
                    "score=${"%.4f".format(Locale.US, bestScore)} size=${image.width}x${image.height}"
            )
        }
    }

    private fun rightControlSearchRadius(width: Int, height: Int): Int = maxOf(
        RIGHT_CONTROL_MIN_SEARCH_RADIUS,
        (GameCoordinateMapper.viewport(width, height).spareX / 2f).toInt() +
            RIGHT_CONTROL_SEARCH_MARGIN
    )

    /**
     * Clock OCR, TP detection and SET/AUTO recognition only consume immutable ROI
     * copies. Run them on separate workers, then merge once on the ordered capture
     * thread so detector history and battle state can never be applied out of order.
     */
    private fun recognizeFrameInParallel(image: Image): ParallelFrameRecognition {
        val clockRegion = ImageRoiExtractor.scaleReferenceRegion(image.width, image.height)
        val clockImage = ImageRoiExtractor.extract(image, clockRegion)
        val energyHud = extractEnergyHud(image)
        val controlCrops = extractControlCrops(image)
        val includeDiagnostics = debugEnabled

        val clockFuture: Future<RecognitionResult>
        val energyFuture: Future<EnergyDetectionResult?>
        val controlsFuture: Future<ControlDetection?>
        try {
            clockFuture = recognitionExecutor.submit<RecognitionResult> {
                recognizer.recognize(
                    clockImage,
                    minConfidence = REAL_DEVICE_CLOCK_MIN_CONFIDENCE,
                    includeDiagnostics = includeDiagnostics
                )
            }
            energyFuture = recognitionExecutor.submit<EnergyDetectionResult?> {
                // 已有独立高频采样通道时不再重复检测，避免同一次 UB 被消费两次。
                if (externalEnergySampling) null else energyHud?.let(::detectEnergy)
            }
            controlsFuture = recognitionExecutor.submit<ControlDetection?> {
                controlCrops?.let(::detectControls)
            }
        } catch (_: RejectedExecutionException) {
            return ParallelFrameRecognition(
                clock = RecognitionResult(ok = false, reason = "recognition-closed"),
                energy = null,
                controls = null
            )
        }

        return ParallelFrameRecognition(
            clock = awaitOrNull(clockFuture)
                ?: RecognitionResult(ok = false, reason = "clock-worker-failed"),
            energy = awaitOrNull(energyFuture),
            controls = awaitOrNull(controlsFuture)
        )
    }

    private fun extractEnergyHud(image: Image): PixelImage? = runCatching {
        val region = BattleReferenceRegions.ENERGY_HUD
        val scaled = ImageRoiExtractor.scaleRegion(
            image.width, image.height, region.x, region.y, region.width, region.height
        )
        ImageRoiExtractor.extract(image, scaled)
    }.getOrNull()

    private fun detectEnergy(hud: PixelImage): EnergyDetectionResult? = runCatching {
        synchronized(energyLock) {
            val size = hud.width to hud.height
            if (energyDetector == null || energyHudSize != size) {
                energyDetector = EnergyDetector(
                    BattleReferenceRegions.energyRegionsForHud(hud.width, hud.height),
                    fullThreshold = AppPreferences.energyFullThreshold(appContext),
                    triggeredBelowThreshold = AppPreferences.energyDropThreshold(appContext)
                )
                energyHudSize = size
            }
            energyDetector!!.detect(hud)
        }
    }.getOrNull()

    /**
     * 独立 TP 采样通道的入口，由专用采集线程按显示帧率调用。
     * 只裁能量条并算填充比例，开销远低于完整识别，因此可以高频运行。
     */
    fun sampleEnergy(image: Image) {
        if (closed.get() || !externalEnergySampling) return
        if (image.width <= image.height) return
        val hud = extractEnergyHud(image) ?: return
        val result = detectEnergy(hud) ?: return
        energySamples.submit(result)
    }

    /**
     * 清空 TP 检测状态。采样线程与识别线程都会碰这些字段，必须在同一把锁下重置，
     * 否则重置期间到达的采样帧可能把上一场的比例带进新战斗。
     */
    private fun resetEnergySampling() {
        synchronized(energyLock) {
            energyDetector = null
            energyHudSize = null
        }
        energySamples.reset()
    }

    /** 启用后 [process] 不再自行检测 TP，改为消费 [sampleEnergy] 的结果。 */
    fun setExternalEnergySampling(enabled: Boolean, reset: Boolean = false) {
        if (externalEnergySampling == enabled && !reset) return
        externalEnergySampling = enabled
        // 采样通道切换意味着 HUD 尺寸与历史比例都不再可信，重建检测器。
        resetEnergySampling()
    }

    private fun extractControlCrops(image: Image): ControlCrops? = runCatching {
        ControlCrops(
            auto = extractRegion(image, BattleReferenceRegions.AUTO_BUTTON, HorizontalAnchor.RIGHT_CONTROL),
            globalSet = extractRegion(image, BattleReferenceRegions.GLOBAL_SET_BUTTON, HorizontalAnchor.RIGHT_CONTROL),
            roles = BattleReferenceRegions.ROLE_SET_BADGES.mapValues { (_, region) ->
                extractRegion(image, region)
            }
        )
    }.getOrNull()

    private fun detectControls(crops: ControlCrops): ControlDetection? = runCatching {
        ControlDetection(controlRecognizer.recognize(crops), crops)
    }.getOrNull()

    private fun <T> awaitOrNull(future: Future<T>): T? = try {
        future.get()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } catch (_: ExecutionException) {
        null
    } catch (_: CancellationException) {
        null
    }

    private fun updateControls(
        filteredControls: FilteredControlObservation,
        menuScore: Double,
        nowMs: Long,
        image: Image,
        holdControlsForCharacterUb: Boolean
    ): ControlStep {
        val before = controlStateMachine.snapshot()
        val controls = filteredControls.observation
        val step = when (before.safety) {
            ControlSafetyState.SAFETY_PAUSING -> controlStateMachine.updateMenu(menuScore)
            ControlSafetyState.SAFETY_PAUSED -> if (!filteredControls.trustworthy || controls == null) {
                controlStateMachine.holdSafetyPause()
            } else {
                val recovery = controlStateMachine.updateRecovery(menuScore, controls, nowMs)
                if (recovery.safety == ControlSafetyState.RUNNING) {
                    controlObservationSafetyGate.reset()
                    // 恢复时画面往往还盖着菜单，锁定的稳定状态可能已经过期。
                    // 一并清掉基线，让菜单关闭后的第一帧重新建立稳定状态。
                    controlObservationFilter.reset()
                }
                recovery
            }
            ControlSafetyState.RUNNING -> {
                val safety = controlObservationSafetyGate.evaluate(
                    filteredControls,
                    holdWhileActionBusy =
                        (axis.type == AxisType.SEQUENCE && actionCoordinator.isRoleLifecycleBusy()) ||
                        (axis.type == AxisType.SWITCH && switchAxisBusy) ||
                        holdControlsForCharacterUb
                )
                when (safety.decision) {
                    ControlObservationSafetyDecision.USE ->
                        controlStateMachine.update(requireNotNull(controls), nowMs)

                    ControlObservationSafetyDecision.HOLD -> controlStateMachine.snapshot(
                        "control-hold-${safety.status.name.lowercase()}-" +
                            "${safety.consecutiveUntrustedFrames}"
                    )

                    ControlObservationSafetyDecision.PAUSE -> {
                        val reason = "control-recognition-failed:${safety.status.name.lowercase()}"
                        Log.w(
                            SAFETY_LOG_TAG,
                            "source=control-gate reason=$reason " +
                                "untrustedFrames=${safety.consecutiveUntrustedFrames}"
                        )
                        controlStateMachine.forceSafety(reason)
                        controlStateMachine.snapshot(reason)
                    }
                }
            }
        }
        executeControlAction(step.action, image.width, image.height)
        return step
    }

    private fun recordSwitchTransition(
        currentFrameId: Long,
        wallMs: Long,
        clockSeconds: Int?,
        triggeredRoles: Set<com.kokkoro.clanbattle.recognition.CharacterRole>,
        controlsTrustworthy: Boolean,
        coordinated: com.kokkoro.clanbattle.switchaxis.SwitchCoordinatorResult
    ) {
        val context = SwitchDiagnosticContext(
            clockSeconds,
            triggeredRoles,
            controlsTrustworthy,
            coordinated
        )
        lastSwitchDiagnosticContext = context
        val desired = encodeTarget(coordinated.controlStep.desired)
        val observed = encodeControlState(coordinated.controlStep.observed)
        val expected = encodeControlState(coordinated.controlStep.expected)
        val runtime = coordinated.runtime
        val key = listOf(
            coordinated.activeNodeId,
            coordinated.pauseFrame?.role,
            coordinated.busy,
            runtime.runtimeState,
            runtime.deadlineWallMs,
            desired,
            observed,
            expected,
            coordinated.controlStep.safety,
            controlsTrustworthy,
            triggeredRoles.sortedBy { it.ordinal }.joinToString("|")
        ).joinToString("|")
        if (key == lastSwitchDebugKey) return
        lastSwitchDebugKey = key
        writeSwitchDiagnostic(currentFrameId, wallMs, context)
    }

    private fun writeSwitchDiagnostic(
        currentFrameId: Long,
        wallMs: Long,
        context: SwitchDiagnosticContext,
        focusAction: String = "",
        focusResult: String = "",
        pauseFrameAction: String = "",
        targetRole: com.kokkoro.clanbattle.recognition.CharacterRole? =
            context.coordinated.pauseFrame?.role
    ) {
        val coordinated = context.coordinated
        val runtime = coordinated.runtime
        val desired = encodeTarget(coordinated.controlStep.desired)
        val observed = encodeControlState(coordinated.controlStep.observed)
        val expected = encodeControlState(coordinated.controlStep.expected)
        recorder().recordSwitch(
            frameId = currentFrameId,
            wallMs = wallMs,
            axisId = activeAxisId,
            axisName = axis.header["轴名称"].orEmpty(),
            axisType = axis.type.name,
            nodeId = coordinated.activeNodeId,
            nodeSourceLine = runtime.sourceLine,
            triggerType = runtime.triggerType,
            runtimeState = runtime.runtimeState,
            eligibleWallMs = runtime.eligibleWallMs,
            deadlineWallMs = runtime.deadlineWallMs,
            clockSeconds = context.clockSeconds,
            triggeredRoles = context.triggeredRoles,
            controlsTrustworthy = context.controlsTrustworthy,
            busy = coordinated.busy,
            focusAction = focusAction,
            focusResult = focusResult,
            pauseFrameAction = pauseFrameAction,
            desired = desired,
            observed = observed,
            expected = expected,
            safetyState = coordinated.controlStep.safety,
            safetyReason = coordinated.controlStep.reason,
            targetRole = targetRole
        )
    }

    private fun encodeTarget(target: OpeningControlTarget?): String = target?.let {
        "auto=${it.auto ?: "-"};roles=${encodeRoles(it.roles)}"
    }.orEmpty()

    private fun encodeControlState(state: com.kokkoro.clanbattle.control.BattleControlState?): String =
        state?.let {
            "auto=${it.auto};global=${it.globalSet};roles=${encodeRoles(it.roles)}"
        }.orEmpty()

    private fun encodeRoles(roles: Map<com.kokkoro.clanbattle.recognition.CharacterRole, VisualToggleState>?): String =
        com.kokkoro.clanbattle.recognition.CharacterRole.entries.joinToString("") { role ->
            when (roles?.get(role)) {
                VisualToggleState.ON -> "O"
                VisualToggleState.OFF -> "X"
                VisualToggleState.UNKNOWN -> "?"
                null -> "-"
            }
        }

    private fun executeControlAction(action: ControlAction, width: Int, height: Int) {
        when (action) {
            ControlAction.TapAuto -> executor.tapAuto(width, height)
            ControlAction.TapGlobalSet -> executor.tapGlobalSet(width, height)
            is ControlAction.TapRole -> executor.tapRole(action.role, width, height)
            ControlAction.TapMenu -> executor.tapMenu(width, height)
            ControlAction.None -> Unit
        }
    }

    /** 叠加层关闭时不做任何几何计算，保证正常运行路径零额外开销。 */
    private fun publishDebugOverlay(
        image: Image,
        observation: BattleControlObservation?,
        energy: EnergyDetectionResult?
    ) {
        if (!regionOverlayEnabled) return
        runCatching { buildDebugOverlayFrame(image, observation, energy) }
            .getOrNull()
            ?.let(debugOverlayCallback)
    }

    /**
     * 用识别器实际使用的 ROI 拼出一帧调试叠加内容。这里必须复用
     * [ImageRoiExtractor.scaleRegion]，否则画出来的框会和真正裁剪的区域脱节，
     * 反而掩盖坐标映射问题。
     */
    private fun buildDebugOverlayFrame(
        image: Image,
        observation: BattleControlObservation?,
        energy: EnergyDetectionResult?
    ): DebugOverlayFrame {
        fun rect(region: ReferenceRegion, anchor: HorizontalAnchor) = ImageRoiExtractor.scaleRegion(
            image.width, image.height, region.x, region.y, region.width, region.height, anchor
        )
        fun tint(state: VisualToggleState?) = when (state) {
            VisualToggleState.ON -> DebugBoxTint.ON
            VisualToggleState.OFF -> DebugBoxTint.OFF
            else -> DebugBoxTint.UNKNOWN
        }

        val boxes = buildList {
            add(
                DebugRegionBox(
                    "时钟",
                    ImageRoiExtractor.scaleReferenceRegion(image.width, image.height),
                    DebugBoxTint.NEUTRAL
                )
            )
            add(DebugRegionBox("菜单", rect(BattleReferenceRegions.MENU_BUTTON, HorizontalAnchor.TOP_HUD)))
            add(
                DebugRegionBox(
                    "AUTO",
                    rect(BattleReferenceRegions.AUTO_BUTTON, HorizontalAnchor.RIGHT_CONTROL),
                    tint(observation?.auto?.state)
                )
            )
            add(
                DebugRegionBox(
                    "全局SET",
                    rect(BattleReferenceRegions.GLOBAL_SET_BUTTON, HorizontalAnchor.RIGHT_CONTROL),
                    tint(observation?.globalSet?.state)
                )
            )
            if (sessionGate.isWaitingForStart()) {
                add(DebugRegionBox("开始", rect(BattleReferenceRegions.START_BUTTON, HorizontalAnchor.CENTER)))
                add(
                    DebugRegionBox(
                        "模拟战开始",
                        rect(BattleReferenceRegions.SIMULATION_START_BUTTON, HorizontalAnchor.CENTER)
                    )
                )
            }
            if (sessionGate.isWaitingForLoading()) {
                add(DebugRegionBox("加载", rect(BattleReferenceRegions.LOADING, HorizontalAnchor.LOADING)))
            }
            BattleReferenceRegions.ROLE_SET_BADGES.forEach { (role, region) ->
                add(
                    DebugRegionBox(
                        "SET${role.ordinal + 1}",
                        rect(region, HorizontalAnchor.CENTER),
                        tint(observation?.roles?.get(role)?.state)
                    )
                )
            }
        }

        val hudRect = rect(BattleReferenceRegions.ENERGY_HUD, HorizontalAnchor.CENTER)
        val hudWidth = hudRect.width()
        val hudHeight = hudRect.height()
        val tpBars = if (hudWidth > 0 && hudHeight > 0 && energy != null) {
            BattleReferenceRegions.energyRegionsForHud(hudWidth, hudHeight).map { (role, region) ->
                val state = energy.characters[role]
                DebugTpBar(
                    label = "TP${role.ordinal + 1}",
                    rect = Rect(
                        hudRect.left + region.x,
                        hudRect.top + region.y,
                        hudRect.left + region.x + region.width,
                        hudRect.top + region.y + region.height
                    ),
                    ratio = state?.blueRatio ?: 0f,
                    full = state?.isFull == true,
                    triggered = state?.triggered == true
                )
            }
        } else {
            emptyList()
        }

        return DebugOverlayFrame(
            captureWidth = image.width,
            captureHeight = image.height,
            boxes = boxes,
            tpBars = tpBars,
            fullThreshold = AppPreferences.energyFullThreshold(appContext),
            dropThreshold = AppPreferences.energyDropThreshold(appContext)
        )
    }

    private fun extractRegion(
        image: Image,
        region: ReferenceRegion,
        anchor: HorizontalAnchor = HorizontalAnchor.CENTER
    ) = ImageRoiExtractor.extract(
        image,
        ImageRoiExtractor.scaleRegion(
            image.width, image.height, region.x, region.y, region.width, region.height, anchor
        )
    )

    private fun BattleControlObservation?.isTrustworthy(): Boolean = this != null &&
        consistent &&
        auto.state != VisualToggleState.UNKNOWN &&
        globalSet.state != VisualToggleState.UNKNOWN &&
        roles.values.none { it.state == VisualToggleState.UNKNOWN }

    private fun publishWaitingStatus(label: String, score: Double, start: Long, image: Image) {
        val elapsed = SystemClock.elapsedRealtime() - start
        statusCallback(
            FrameStatus(
                "$label  匹配度 ${"%.2f".format(score)}  ${elapsed}ms",
                false,
                elapsed,
                image.width,
                image.height
            )
        )
    }

    private companion object {
        const val BOSS_UB_LOG_TAG = "KokkoroBossUb"
        const val SAFETY_LOG_TAG = "KokkoroSafety"
        const val CALIBRATION_LOG_TAG = "KokkoroCalibration"
        const val REAL_DEVICE_CLOCK_MIN_CONFIDENCE = 0.75
        const val TEMPLATE_THRESHOLD = 0.72
        const val DEBUG_PREFERENCE_POLL_MS = 1_000L
        const val CENTER_SEARCH_RADIUS = 180
        const val RIGHT_SEARCH_RADIUS = 120
        const val TOP_HUD_MIN_SEARCH_RADIUS = 180
        const val TOP_HUD_SEARCH_MARGIN = 96
        const val RIGHT_CONTROL_MIN_SEARCH_RADIUS = 420
        const val RIGHT_CONTROL_SEARCH_MARGIN = 96
        const val LOADING_MIN_SEARCH_RADIUS = 180
        const val LOADING_SEARCH_MARGIN = 96
        const val RIGHT_CONTROL_CALIBRATION_THRESHOLD = 0.58
        const val CALIBRATION_COARSE_STEP = 12
        const val CALIBRATION_MIN_SCORE = 0.55
        const val MENU_TRUST_THRESHOLD = 0.70
        const val OPENING_GRACE_SECONDS = 5
        const val UB_CONTROL_MAX_HOLD_MS = 8_000L
        const val RECOGNITION_WORKER_COUNT = 3

        fun emptyAxis() = AxisDocument(AxisType.SEQUENCE, 100, emptyMap(), emptyList())
    }
}

internal fun GameState?.isCharacterUbActive(): Boolean =
    this == GameState.CHARACTER_UB || this == GameState.UB_ANIMATION

internal fun shouldHoldControlRecognitionForCharacterUb(
    gameState: GameState?,
    nowMs: Long,
    holdUntilMs: Long
): Boolean = gameState.isCharacterUbActive() && nowMs < holdUntilMs