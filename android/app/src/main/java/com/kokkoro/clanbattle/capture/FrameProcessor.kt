package com.kokkoro.clanbattle.capture

import android.content.Context
import android.media.Image
import android.os.SystemClock
import com.kokkoro.clanbattle.automation.ActionExecutor
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
import com.kokkoro.clanbattle.control.BattleControlStateMachine
import com.kokkoro.clanbattle.control.ControlAction
import com.kokkoro.clanbattle.control.ControlCrops
import com.kokkoro.clanbattle.control.ControlSafetyState
import com.kokkoro.clanbattle.control.ControlStep
import com.kokkoro.clanbattle.control.CoordinatedActionStep
import com.kokkoro.clanbattle.control.OpeningControlTarget
import com.kokkoro.clanbattle.control.VerifiedActionCoordinator
import com.kokkoro.clanbattle.control.VisualToggleState
import com.kokkoro.clanbattle.recognition.AndroidTemplateLoader
import com.kokkoro.clanbattle.recognition.ClockRecognizer
import com.kokkoro.clanbattle.recognition.EnergyDetector
import com.kokkoro.clanbattle.recognition.EnergyDetectionResult
import com.kokkoro.clanbattle.recognition.RecognitionFilter
import com.kokkoro.clanbattle.recognition.RecognitionResult
import com.kokkoro.clanbattle.scheduler.GameStateDetector
import com.kokkoro.clanbattle.scheduler.GameState
import com.kokkoro.clanbattle.pauseframe.PauseFrameDiagnosticEvent
import com.kokkoro.clanbattle.sequenceaxis.SequenceAxisRuntime
import com.kokkoro.clanbattle.sequenceaxis.SequenceFrameInput
import com.kokkoro.clanbattle.sequenceaxis.SequenceRuntimeCommand
import com.kokkoro.clanbattle.switchaxis.SwitchControlCoordinator
import com.kokkoro.clanbattle.switchaxis.SwitchFrameInput
import java.util.Locale

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
    private val battleLockCallback: () -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val recognizer = ClockRecognizer(AndroidTemplateLoader.load(appContext))
    private val battleTemplates = BattleTemplateLoader.load(appContext)
    private val controlTemplates = AndroidControlTemplateLoader.load(appContext)
    private val controlRecognizer = BattleControlRecognizer(controlTemplates.controls)
    private val controlStateMachine = BattleControlStateMachine()
    private val actionCoordinator = VerifiedActionCoordinator(controlStateMachine)
    private val filter = RecognitionFilter(minConfidence = 0.8, minAlternativeScore = 0.55, maxFailedReads = 999)
    private var energyDetector: EnergyDetector? = null
    private var energyHudSize: Pair<Int, Int>? = null
    private var axis: AxisDocument = emptyAxis()
    private var activeAxisId: String = ""
    private var openingControlTarget: OpeningControlTarget? = null
    private var sequenceRuntime: SequenceAxisRuntime? = null
    private var switchCoordinator: SwitchControlCoordinator? = null
    private val gameStateDetector = GameStateDetector()
    private val executor = ActionExecutor(appContext)
    private val sessionGate = BattleSessionGate()
    private var recorder: ClockDebugRecorder? = null
    private var frameId = 0L
    private var debugEnabled = false
    private var lastDebugPreferenceCheckMs = Long.MIN_VALUE
    private var openingControlsConfirmed = true
    private var lastPauseFrameNodeId: String? = null
    private var lastSwitchDebugKey: String? = null
    private var lastSwitchDiagnosticContext: SwitchDiagnosticContext? = null
    @Volatile private var roleTapSafe = false

    init {
        installAxis(loadSelectedAxis())
        controlStateMachine.setDesired(openingControlTarget)
        openingControlsConfirmed = openingControlTarget == null
        lastPauseFrameNodeId = null
        lastSwitchDebugKey = null
        lastSwitchDiagnosticContext = null
    }

    fun prepareNewBattle(document: AxisDocument = loadSelectedAxis()) {
        filter.reset()
        energyDetector?.reset()
        gameStateDetector.reset()
        controlStateMachine.reset()
        actionCoordinator.reset()
        installAxis(document)
        controlStateMachine.setDesired(openingControlTarget)
        openingControlsConfirmed = openingControlTarget == null
        lastPauseFrameNodeId = null
        sessionGate.prepare()
        val wasDebugEnabled = debugEnabled
        refreshDebugPreference(SystemClock.elapsedRealtime(), force = true)
        if (debugEnabled && wasDebugEnabled) recorder().startSession()
    }

    fun process(image: Image) {
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
            val score = matchRegion(image, BattleReferenceRegions.START_BUTTON, battleTemplates.startBattle)
            if (score >= TEMPLATE_THRESHOLD) {
                sessionGate.onStartMatched()
                battleLockCallback()
            }
            if (debugEnabled) recordEarlyFailure(currentFrameId, "waiting-start-template score=${"%.4f".format(Locale.US, score)}")
            publishWaitingStatus("等待战斗开始按钮", score, start, image)
            return
        }

        if (sessionGate.isWaitingForLoading()) {
            roleTapSafe = false
            val score = matchRegion(image, BattleReferenceRegions.LOADING, battleTemplates.loading)
            if (score >= TEMPLATE_THRESHOLD) sessionGate.onLoadingMatched()
            if (debugEnabled) recordEarlyFailure(currentFrameId, "waiting-loading-template score=${"%.4f".format(Locale.US, score)}")
            publishWaitingStatus("等待加载界面", score, start, image)
            return
        }

        val region = ImageRoiExtractor.scaleReferenceRegion(image.width, image.height)
        val roi = ImageRoiExtractor.extract(image, region)
        val recognition = recognizer.recognize(roi, includeDiagnostics = debugEnabled)
        val energy = detectEnergy(image)
        val controlDetection = detectControls(image)
        val controls = controlDetection?.observation
        val menuScore = matchRegion(image, BattleReferenceRegions.MENU_BUTTON, controlTemplates.menu)
        roleTapSafe = controls.isTrustworthy() && menuScore >= MENU_TRUST_THRESHOLD
        gameStateDetector.observeEnergy(energy)
        if (!sessionGate.shouldEvaluate(recognition.timeSeconds)) {
            if (debugEnabled) recorder().record(currentFrameId, System.currentTimeMillis(), start, sessionGate.debugState(), recognition, null, energy)
            val elapsed = SystemClock.elapsedRealtime() - start
            statusCallback(
                FrameStatus(
                    "等待开场 1:30  ${recognition.rawText ?: "--:--"}  ${elapsed}ms",
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

        var gameState: GameState? = null
        var scheduleReason: String? = null
        var controlStep: ControlStep = controlStateMachine.snapshot()
        var activeNodeId: String? = null
        var sequenceProgress: CoordinatedActionStep? = null
        val executionWarning = actionExecutionBlockReason(
            dryRun = AppPreferences.dryRun(appContext),
            accessibilityConnected = KokkoroAccessibilityService.instance != null
        )
        if (sessionReady && executionWarning == null) {
            controlStep = updateControls(controls, menuScore, start, image)
            if (axis.type == AxisType.SWITCH) {
                val controlsTrustworthy = roleTapSafe &&
                    controlStep.safety == ControlSafetyState.RUNNING
                val coordinated = requireNotNull(switchCoordinator).update(
                    SwitchFrameInput(
                        clockSeconds = filtered.timeSeconds,
                        triggeredRoles = energy?.triggeredRoles.orEmpty(),
                        controlsTrustworthy = controlsTrustworthy,
                        wallMs = start
                    ),
                    controlStep
                )
                activeNodeId = coordinated.activeNodeId
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
                        energy?.triggeredRoles.orEmpty(),
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
                if (controlStep.confirmed && !openingControlsConfirmed) {
                    openingControlsConfirmed = true
                    controlStateMachine.setDesired(null)
                }
                if (openingControlsConfirmed && controlStep.safety == ControlSafetyState.RUNNING) {
                    gameState = gameStateDetector.update(filtered.timeSeconds, null)
                    val triggeredRoles = energy?.triggeredRoles.orEmpty()
                    var coordinated = actionCoordinator.update(
                        controlStep,
                        start,
                        triggeredRoles
                    )
                    sequenceProgress = coordinated
                    executeControlAction(coordinated.newControlAction, image.width, image.height)
                    executor.execute(coordinated.immediateEvents, image.width, image.height, axis.clickIntervalMs)
                    controlStep = coordinated.controlStep

                    if (!coordinated.busy) {
                        val runtime = requireNotNull(sequenceRuntime)
                        val command = runtime.update(
                            SequenceFrameInput(
                                clockSeconds = filtered.timeSeconds,
                                triggeredRoles = triggeredRoles,
                                controlsTrustworthy = roleTapSafe,
                                wallMs = start,
                                schedulingAllowed = gameState != GameState.CHARACTER_UB &&
                                    gameState != GameState.UB_ANIMATION
                            )
                        )
                        activeNodeId = runtime.snapshot().activeEvent?.id
                        when (command) {
                            SequenceRuntimeCommand.None -> {
                                scheduleReason = if (activeNodeId != null) {
                                    "sequence-trigger:$activeNodeId"
                                } else {
                                    "sequence-waiting"
                                }
                            }
                            is SequenceRuntimeCommand.EnterPauseFrame -> {
                                activeNodeId = command.nodeId
                                if (lastPauseFrameNodeId != command.nodeId) {
                                    lastPauseFrameNodeId = command.nodeId
                                    pauseFrameCallback(command.nodeId, command.role)
                                }
                                scheduleReason = "pause-frame:${command.role.name}"
                            }
                            is SequenceRuntimeCommand.Dispatch -> {
                                actionCoordinator.enqueue(listOf(command.event))
                                coordinated = actionCoordinator.update(
                                    controlStep,
                                    start,
                                    triggeredRoles
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
                        scheduleReason = "verified-control-action"
                    }
                } else {
                    scheduleReason = "control-state-gate"
                }
            }
        } else if (sessionReady) {
            controlStep = controls?.let(controlStateMachine::observeOnly) ?: controlStateMachine.snapshot()
            scheduleReason = "execution-blocked"
        }

        val elapsed = SystemClock.elapsedRealtime() - start
        if (debugEnabled && sessionReady) {
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
        val controlText = ControlStatusFormatter.format(controlStep)
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
            "等待开场 1:30  ${recognition.rawText ?: "--:--"}  ${elapsed}ms  $energyText"
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
                controlStep.safety.takeIf { sessionReady },
                actionPreview.current,
                actionPreview.next,
                executionWarning.takeIf { sessionReady }
            )
        )
    }

    fun requestSafetyPause(reason: String = "manual-safety-menu") {
        controlStateMachine.forceSafety(reason)
    }

    fun confirmPauseFrame(nodeId: String) {
        switchCoordinator?.confirmPauseFrame(nodeId)
        sequenceRuntime?.confirmPauseFrame(nodeId)
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
                nodes = document.switchNodes
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

    private fun matchRegion(image: Image, region: ReferenceRegion, template: com.kokkoro.clanbattle.recognition.PixelImage): Double {
        val scaled = ImageRoiExtractor.scaleRegion(
            image.width,
            image.height,
            region.x,
            region.y,
            region.width,
            region.height
        )
        return FixedTemplateMatcher.score(ImageRoiExtractor.extract(image, scaled), template)
    }

    private fun detectEnergy(image: Image): EnergyDetectionResult? = runCatching {
        val region = BattleReferenceRegions.ENERGY_HUD
        val scaled = ImageRoiExtractor.scaleRegion(
            image.width, image.height, region.x, region.y, region.width, region.height
        )
        val hud = ImageRoiExtractor.extract(image, scaled)
        val size = hud.width to hud.height
        if (energyDetector == null || energyHudSize != size) {
            energyDetector = EnergyDetector(BattleReferenceRegions.energyRegionsForHud(hud.width, hud.height))
            energyHudSize = size
        }
        energyDetector!!.detect(hud)
    }.getOrNull()

    private fun detectControls(image: Image): ControlDetection? = runCatching {
        val crops = ControlCrops(
            auto = extractRegion(image, BattleReferenceRegions.AUTO_BUTTON),
            globalSet = extractRegion(image, BattleReferenceRegions.GLOBAL_SET_BUTTON),
            roles = BattleReferenceRegions.ROLE_SET_BADGES.mapValues { (_, region) ->
                extractRegion(image, region)
            }
        )
        ControlDetection(controlRecognizer.recognize(crops), crops)
    }.getOrNull()

    private fun updateControls(
        controls: BattleControlObservation?,
        menuScore: Double,
        nowMs: Long,
        image: Image
    ): ControlStep {
        val before = controlStateMachine.snapshot()
        val step = when (before.safety) {
            ControlSafetyState.SAFETY_PAUSING -> controlStateMachine.updateMenu(menuScore)
            ControlSafetyState.SAFETY_PAUSED -> if (controls == null) before else {
                controlStateMachine.updateRecovery(menuScore, controls, nowMs)
            }
            ControlSafetyState.RUNNING -> if (controls == null) {
                controlStateMachine.forceSafety("control-recognition-failed")
                controlStateMachine.snapshot()
            } else {
                controlStateMachine.update(controls, nowMs)
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

    private fun extractRegion(image: Image, region: ReferenceRegion) = ImageRoiExtractor.extract(
        image,
        ImageRoiExtractor.scaleRegion(
            image.width, image.height, region.x, region.y, region.width, region.height
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
                "$label  ${"%.2f".format(score)}  ${elapsed}ms",
                false,
                elapsed,
                image.width,
                image.height
            )
        )
    }

    private companion object {
        const val TEMPLATE_THRESHOLD = 0.72
        const val DEBUG_PREFERENCE_POLL_MS = 1_000L
        const val MENU_TRUST_THRESHOLD = 0.70

        fun emptyAxis() = AxisDocument(AxisType.SEQUENCE, 100, emptyMap(), emptyList())
    }
}
