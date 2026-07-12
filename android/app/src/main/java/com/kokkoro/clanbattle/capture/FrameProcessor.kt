package com.kokkoro.clanbattle.capture

import android.content.Context
import android.media.Image
import android.os.SystemClock
import com.kokkoro.clanbattle.automation.ActionExecutor
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
import com.kokkoro.clanbattle.scheduler.Scheduler
import com.kokkoro.clanbattle.switchaxis.SwitchControlCoordinator
import com.kokkoro.clanbattle.switchaxis.SwitchFrameInput
import java.util.Locale

data class FrameStatus(
    val text: String,
    val success: Boolean,
    val processingMs: Long,
    val frameWidth: Int,
    val frameHeight: Int,
    val controlSafety: ControlSafetyState? = null
)

private data class ControlDetection(
    val observation: BattleControlObservation,
    val crops: ControlCrops
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
    private var openingControlTarget: OpeningControlTarget? = null
    private var scheduler = Scheduler(emptyList())
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

    init {
        installAxis(loadSelectedAxis())
        controlStateMachine.setDesired(openingControlTarget)
        openingControlsConfirmed = openingControlTarget == null
        lastPauseFrameNodeId = null
        lastSwitchDebugKey = null
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
        scheduler.reset()
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
            if (debugEnabled) recordEarlyFailure(currentFrameId, "portrait-frame")
            statusCallback(FrameStatus("等待游戏横屏 ${image.width}×${image.height}", false, 0, image.width, image.height))
            return
        }

        if (sessionGate.isWaitingForStart()) {
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
        if (sessionReady) {
            controlStep = updateControls(controls, menuScore, start, image)
            if (axis.type == AxisType.SWITCH) {
                val controlsTrustworthy = controls.isTrustworthy() &&
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
                    var coordinated = actionCoordinator.update(controlStep, start)
                    executeControlAction(coordinated.newControlAction, image.width, image.height)
                    executor.execute(coordinated.immediateEvents, image.width, image.height, axis.clickIntervalMs)
                    controlStep = coordinated.controlStep

                    if (!coordinated.busy) {
                        gameState = gameStateDetector.update(filtered.timeSeconds, null)
                        val schedule = scheduler.update(gameState, filtered.timeSeconds)
                        scheduleReason = schedule.reason
                        actionCoordinator.enqueue(schedule.events)
                        coordinated = actionCoordinator.update(controlStep, start)
                        executeControlAction(coordinated.newControlAction, image.width, image.height)
                        executor.execute(coordinated.immediateEvents, image.width, image.height, axis.clickIntervalMs)
                        controlStep = coordinated.controlStep
                        if (coordinated.busy) scheduleReason = "verified-control-action"
                    } else {
                        scheduleReason = "verified-control-action"
                    }
                } else {
                    scheduleReason = "control-state-gate"
                }
            }
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
                controlStep.safety.takeIf { sessionReady }
            )
        )
    }

    fun requestSafetyPause(reason: String = "manual-safety-menu") {
        controlStateMachine.forceSafety(reason)
    }

    fun confirmPauseFrame(nodeId: String) {
        switchCoordinator?.confirmPauseFrame(nodeId)
    }

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
        openingControlTarget = if (document.type == AxisType.SEQUENCE) {
            OpeningControlTarget.from(document)
        } else {
            null
        }
        scheduler = Scheduler(if (document.type == AxisType.SEQUENCE) sequenceEvents(document) else emptyList())
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
        if (actions.isEmpty()) null else event.copy(actions = actions)
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
        val desired = coordinated.controlStep.desired?.let { target ->
            val roles = com.kokkoro.clanbattle.recognition.CharacterRole.entries.joinToString("") { role ->
                when (target.roles?.get(role)) {
                    VisualToggleState.ON -> "O"
                    VisualToggleState.OFF -> "X"
                    VisualToggleState.UNKNOWN -> "?"
                    null -> "-"
                }
            }
            "auto=${target.auto ?: "-"};roles=$roles"
        }.orEmpty()
        val key = listOf(
            coordinated.activeNodeId,
            coordinated.pauseFrame?.role,
            coordinated.busy,
            desired,
            coordinated.controlStep.safety,
            controlsTrustworthy,
            triggeredRoles.sortedBy { it.ordinal }.joinToString("|")
        ).joinToString("|")
        if (key == lastSwitchDebugKey) return
        lastSwitchDebugKey = key
        recorder().recordSwitch(
            frameId = currentFrameId,
            wallMs = wallMs,
            axisName = axis.header["轴名称"].orEmpty(),
            nodeId = coordinated.activeNodeId,
            clockSeconds = clockSeconds,
            triggeredRoles = triggeredRoles,
            controlsTrustworthy = controlsTrustworthy,
            busy = coordinated.busy,
            desired = desired,
            safetyState = coordinated.controlStep.safety,
            pauseFrameRole = coordinated.pauseFrame?.role
        )
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

        fun emptyAxis() = AxisDocument(AxisType.SEQUENCE, 100, emptyMap(), emptyList())
    }
}
