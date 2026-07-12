# Battle Control State Recognition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recognize AUTO, global instant-UB, and five per-role instant-UB states from the battle image, converge opening axis targets through verified minimal clicks, and pause the game safely when control state becomes untrustworthy.

**Architecture:** A pure Kotlin recognizer converts seven small image crops into tri-state observations. A pure Kotlin session state machine keeps observed, desired, and expected states separate and emits one verified click at a time. `FrameProcessor` owns the battle-session lifecycle, opening gate, safety pause, diagnostics, and overlay feedback; `ActionExecutor` becomes a stateless coordinate executor.

**Tech Stack:** Kotlin/JVM 17, Android MediaProjection, accessibility gestures, existing `PixelImage`/`FixedTemplateMatcher`, JUnit 4, PNGJ test fixtures, Gradle Android plugin 8.5.2.

---

## File Structure

**Create:**

- `android/app/src/main/java/com/kokkoro/clanbattle/control/BattleControlModels.kt` — toggle observations, desired/expected/observed states, control actions, safety state.
- `android/app/src/main/java/com/kokkoro/clanbattle/control/BattleControlRecognizer.kt` — pure image classification.
- `android/app/src/main/java/com/kokkoro/clanbattle/control/BattleControlStateMachine.kt` — opening convergence, click confirmation, retry, safety pause transitions.
- `android/app/src/main/java/com/kokkoro/clanbattle/control/OpeningControlTarget.kt` — extracts 90-second AUTO/SET targets from parsed axis events.
- `android/app/src/main/java/com/kokkoro/clanbattle/control/AndroidControlTemplateLoader.kt` — loads the six control templates.
- `android/app/src/main/java/com/kokkoro/clanbattle/capture/ControlStatusFormatter.kt` — compact overlay feedback.
- `android/app/src/test/java/com/kokkoro/clanbattle/control/BattleControlRecognizerTest.kt`
- `android/app/src/test/java/com/kokkoro/clanbattle/control/BattleControlStateMachineTest.kt`
- `android/app/src/test/java/com/kokkoro/clanbattle/control/OpeningControlTargetTest.kt`
- `android/app/src/test/java/com/kokkoro/clanbattle/capture/ControlRegionTest.kt`
- `android/app/src/test/java/com/kokkoro/clanbattle/capture/ControlStatusFormatterTest.kt`
- `android/app/src/test/resources/control/set_on_off_on_off_on.png`

**Modify:**

- `android/app/src/main/java/com/kokkoro/clanbattle/capture/BattleReferenceRegions.kt`
- `android/app/src/main/java/com/kokkoro/clanbattle/capture/BattleSessionGate.kt`
- `android/app/src/main/java/com/kokkoro/clanbattle/capture/FrameProcessor.kt`
- `android/app/src/main/java/com/kokkoro/clanbattle/capture/ClockDebugCsv.kt`
- `android/app/src/main/java/com/kokkoro/clanbattle/capture/ClockDebugRecorder.kt`
- `android/app/src/main/java/com/kokkoro/clanbattle/automation/ActionExecutor.kt`
- `android/app/src/test/java/com/kokkoro/clanbattle/capture/BattleSessionGateTest.kt`
- `android/app/src/test/java/com/kokkoro/clanbattle/capture/ClockDebugCsvTest.kt`
- `.gitignore`

**Track as Android assets:**

- `assets/templates/auto_on.bmp`
- `assets/templates/auto_off.bmp`
- `assets/templates/set_on.bmp`
- `assets/templates/set_off.bmp`
- `assets/templates/set.bmp`
- `assets/templates/menu.bmp`
- `assets/templates/set_on_off_on_off_on.png`

---

### Task 1: Track Control Fixtures and Lock Reference Geometry

**Files:**

- Modify: `android/app/src/main/java/com/kokkoro/clanbattle/capture/BattleReferenceRegions.kt`
- Create: `android/app/src/test/java/com/kokkoro/clanbattle/capture/ControlRegionTest.kt`
- Create: `android/app/src/test/resources/control/set_on_off_on_off_on.png`
- Track: `assets/templates/*.bmp`, `assets/templates/set_on_off_on_off_on.png`

- [ ] **Step 1: Copy the full-screen fixture into JVM test resources**

Use a mechanical file copy so the bytes remain unchanged:

```powershell
New-Item -ItemType Directory -Force android/app/src/test/resources/control
Copy-Item assets/templates/set_on_off_on_off_on.png android/app/src/test/resources/control/set_on_off_on_off_on.png
```

- [ ] **Step 2: Write the failing geometry test**

```kotlin
class ControlRegionTest {
    @Test fun `control regions use calibrated 1920 by 1080 coordinates`() {
        assertEquals(ReferenceRegion(1740, 20, 170, 65), BattleReferenceRegions.MENU_BUTTON)
        assertEquals(ReferenceRegion(1760, 620, 140, 130), BattleReferenceRegions.GLOBAL_SET_BUTTON)
        assertEquals(ReferenceRegion(1760, 770, 140, 130), BattleReferenceRegions.AUTO_BUTTON)
        assertEquals(
            listOf(550, 790, 1030, 1270, 1510),
            CharacterRole.entries.map { BattleReferenceRegions.ROLE_SET_BADGES.getValue(it).x }
        )
        BattleReferenceRegions.ROLE_SET_BADGES.values.forEach {
            assertEquals(771, it.y)
            assertEquals(54, it.width)
            assertEquals(53, it.height)
        }
    }
}
```

- [ ] **Step 3: Run the test and verify RED**

Run:

```powershell
cd android
.\gradlew.bat testDebugUnitTest --tests com.kokkoro.clanbattle.capture.ControlRegionTest
```

Expected: compilation failure because the new regions do not exist.

- [ ] **Step 4: Add the calibrated reference regions**

```kotlin
val MENU_BUTTON = ReferenceRegion(1740, 20, 170, 65)
val GLOBAL_SET_BUTTON = ReferenceRegion(1760, 620, 140, 130)
val AUTO_BUTTON = ReferenceRegion(1760, 770, 140, 130)
val ROLE_SET_BADGES = CharacterRole.entries.associateWith { role ->
    ReferenceRegion(550 + role.ordinal * 240, 771, 54, 53)
}
```

- [ ] **Step 5: Verify GREEN and track the supplied assets**

Run the focused test again. Expected: PASS.

```powershell
git add assets/templates/auto_on.bmp assets/templates/auto_off.bmp assets/templates/set_on.bmp assets/templates/set_off.bmp assets/templates/set.bmp assets/templates/menu.bmp assets/templates/set_on_off_on_off_on.png android/app/src/test/resources/control/set_on_off_on_off_on.png android/app/src/main/java/com/kokkoro/clanbattle/capture/BattleReferenceRegions.kt android/app/src/test/java/com/kokkoro/clanbattle/capture/ControlRegionTest.kt
git commit -m "test: add battle control state fixtures"
```

---

### Task 2: Implement Pure Battle Control Recognition

**Files:**

- Create: `android/app/src/main/java/com/kokkoro/clanbattle/control/BattleControlModels.kt`
- Create: `android/app/src/main/java/com/kokkoro/clanbattle/control/BattleControlRecognizer.kt`
- Create: `android/app/src/test/java/com/kokkoro/clanbattle/control/BattleControlRecognizerTest.kt`

- [ ] **Step 1: Write failing tests for paired buttons and role badges**

```kotlin
class BattleControlRecognizerTest {
    @Test fun `known mixed fixture reports roles one three five on`() {
        val fixture = loadPngResource("control/set_on_off_on_off_on.png")
        val result = recognizer().recognize(cropsFromReferenceFixture(fixture))
        assertEquals(VisualToggleState.OFF, result.auto.state)
        assertEquals(VisualToggleState.OFF, result.globalSet.state)
        assertEquals(
            listOf(ON, OFF, ON, OFF, ON),
            CharacterRole.entries.map { result.roles.getValue(it).state }
        )
    }

    @Test fun `paired score without enough margin is unknown`() {
        val observation = BattleControlRecognizer.classifyPair(0.76, 0.73, 0.65, 0.08)
        assertEquals(VisualToggleState.UNKNOWN, observation.state)
    }

    @Test fun `role badge score uses an uncertainty band`() {
        assertEquals(ON, BattleControlRecognizer.classifyBadge(0.82, 0.75, 0.55).state)
        assertEquals(OFF, BattleControlRecognizer.classifyBadge(0.42, 0.75, 0.55).state)
        assertEquals(UNKNOWN, BattleControlRecognizer.classifyBadge(0.64, 0.75, 0.55).state)
    }
}
```

- [ ] **Step 2: Run tests and verify RED**

Expected: unresolved recognizer/model classes.

- [ ] **Step 3: Implement the model and classifier API**

```kotlin
enum class VisualToggleState { ON, OFF, UNKNOWN }

data class ToggleObservation(
    val state: VisualToggleState,
    val onScore: Double,
    val offScore: Double? = null,
    val margin: Double = 0.0
)

data class BattleControlObservation(
    val auto: ToggleObservation,
    val globalSet: ToggleObservation,
    val roles: Map<CharacterRole, ToggleObservation>,
    val consistent: Boolean,
    val reason: String? = null
)

data class ControlCrops(
    val auto: PixelImage,
    val globalSet: PixelImage,
    val roles: Map<CharacterRole, PixelImage>
)

data class BattleControlTemplates(
    val autoOn: PixelImage,
    val autoOff: PixelImage,
    val globalSetOn: PixelImage,
    val globalSetOff: PixelImage,
    val roleSetOn: PixelImage
)
```

Implement `classifyPair()` with minimum score `0.65` and margin `0.08`; implement `classifyBadge()` with ON threshold `0.75` and OFF threshold `0.55`. `recognize()` uses `FixedTemplateMatcher.score()` and marks the frame inconsistent when global is ON while any role is explicitly OFF.

- [ ] **Step 4: Run focused tests and calibrate only from fixture evidence**

Print scores in failing assertions. If the supplied fixture does not separate positives and negatives at the initial constants, set the ON threshold to the midpoint between the fixture's minimum positive score and maximum negative score, requiring a gap of at least `0.08`. Set the OFF threshold to `maximumNegative + gap * 0.25`. Do not tune against unlabeled frames.

- [ ] **Step 5: Verify GREEN and commit**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.kokkoro.clanbattle.control.BattleControlRecognizerTest
git add android/app/src/main/java/com/kokkoro/clanbattle/control android/app/src/test/java/com/kokkoro/clanbattle/control/BattleControlRecognizerTest.kt
git commit -m "feat: recognize battle control states"
```

---

### Task 3: Extract Opening Control Targets and Tighten the Gate

**Files:**

- Create: `android/app/src/main/java/com/kokkoro/clanbattle/control/OpeningControlTarget.kt`
- Create: `android/app/src/test/java/com/kokkoro/clanbattle/control/OpeningControlTargetTest.kt`
- Modify: `android/app/src/main/java/com/kokkoro/clanbattle/capture/BattleSessionGate.kt`
- Modify: `android/app/src/test/java/com/kokkoro/clanbattle/capture/BattleSessionGateTest.kt`

- [ ] **Step 1: Write failing opening target tests**

```kotlin
@Test fun `extracts AUTO and SET from the ninety second event`() {
    val axis = AxisParser.parse("""
        轴类型=顺序
        [轴]
        1:30 | AUTO=开 | SET=开,关,开,关,开
        1:20 | 点击=角色3
    """.trimIndent())
    assertEquals(
        OpeningControlTarget(
            auto = VisualToggleState.ON,
            roles = mapOf(ROLE_1 to ON, ROLE_2 to OFF, ROLE_3 to ON, ROLE_4 to OFF, ROLE_5 to ON)
        ),
        OpeningControlTarget.from(axis)
    )
}

@Test fun `gate anchors only from ninety through eighty eight`() {
    assertTrue(gate.shouldEvaluate(90))
    assertTrue(gate.shouldEvaluate(88))
    assertFalse(gate.shouldEvaluate(87))
}
```

- [ ] **Step 2: Verify RED**

Expected: missing `OpeningControlTarget`, and the current gate still accepts 84–87.

- [ ] **Step 3: Implement target extraction and gate range**

`OpeningControlTarget.from(axis)` must inspect only events at `timeSeconds == 90`, accept at most one AUTO target and one SET target, and map SET values in left-to-right role 1–5 order. Change the opening range constant to `88..90`.

- [ ] **Step 4: Verify GREEN and commit**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.kokkoro.clanbattle.control.OpeningControlTargetTest --tests com.kokkoro.clanbattle.capture.BattleSessionGateTest
git add android/app/src/main/java/com/kokkoro/clanbattle/control/OpeningControlTarget.kt android/app/src/test/java/com/kokkoro/clanbattle/control/OpeningControlTargetTest.kt android/app/src/main/java/com/kokkoro/clanbattle/capture/BattleSessionGate.kt android/app/src/test/java/com/kokkoro/clanbattle/capture/BattleSessionGateTest.kt
git commit -m "feat: extract opening control targets"
```

---

### Task 4: Implement the Session State Machine and Minimal Click Planning

**Files:**

- Create: `android/app/src/main/java/com/kokkoro/clanbattle/control/BattleControlStateMachine.kt`
- Create: `android/app/src/test/java/com/kokkoro/clanbattle/control/BattleControlStateMachineTest.kt`

- [ ] **Step 1: Write failing minimal-plan tests**

```kotlin
@Test fun `all on target uses one global click`() {
    val machine = machine(target = allRoles(ON), auto = ON)
    val step = machine.update(observation(auto = ON, global = OFF, roles = allRoles(OFF)), 0)
    assertEquals(ControlAction.TapGlobalSet, step.action)
}

@Test fun `mixed target with global on clicks only first role that must turn off`() {
    val machine = machine(target = roles(ON, OFF, ON, OFF, ON), auto = null)
    val step = machine.update(observation(auto = OFF, global = ON, roles = allRoles(ON)), 0)
    assertEquals(ControlAction.TapRole(ROLE_2), step.action)
}

@Test fun `unknown required state does not click`() {
    val step = machine(target = allRoles(OFF)).update(observation(role1 = UNKNOWN), 0)
    assertEquals(ControlAction.None, step.action)
    assertEquals("waiting-trustworthy-state", step.reason)
}
```

- [ ] **Step 2: Verify RED**

Expected: missing state machine and action types.

- [ ] **Step 3: Implement state separation and one-step planning**

```kotlin
sealed interface ControlAction {
    data object None : ControlAction
    data object TapAuto : ControlAction
    data object TapGlobalSet : ControlAction
    data class TapRole(val role: CharacterRole) : ControlAction
    data object TapMenu : ControlAction
}

enum class ControlSafetyState { RUNNING, SAFETY_PAUSING, SAFETY_PAUSED }

data class ControlStep(
    val action: ControlAction,
    val reason: String,
    val observed: BattleControlState?,
    val desired: BattleControlState?,
    val expected: BattleControlState?,
    val safety: ControlSafetyState
)
```

The machine must keep observed, desired, and expected separate. After emitting a click it waits for two consecutive matching observations. At confirmation it clears expected and replans from the new observed state.

- [ ] **Step 4: Add failing confirmation and retry tests**

```kotlin
@Test fun `click needs two matching frames before next action`() {
    val machine = machine(target = roles(ON, OFF, ON, OFF, ON), auto = ON)
    assertEquals(TapAuto, machine.update(observation(auto = OFF, roles = allRoles(OFF)), 0).action)
    val expectedAutoOn = observation(auto = ON, roles = allRoles(OFF))
    assertEquals(None, machine.update(expectedAutoOn, 100).action)
    assertEquals(TapRole(ROLE_1), machine.update(expectedAutoOn, 150).action)
}

@Test fun `unconfirmed click retries once after five hundred milliseconds`() {
    val machine = machine(target = allRoles(OFF), auto = ON)
    val unchanged = observation(auto = OFF, global = OFF, roles = allRoles(OFF))
    assertEquals(TapAuto, machine.update(unchanged, 0).action)
    val retry = machine.update(unchanged, 501)
    assertEquals(TapAuto, retry.action)
    assertEquals(1, retry.retryCount)
}

@Test fun `second timeout enters safety pausing`() {
    val machine = machine(target = allRoles(OFF), auto = ON)
    val unchanged = observation(auto = OFF, global = OFF, roles = allRoles(OFF))
    machine.update(unchanged, 0)
    machine.update(unchanged, 501)
    val failed = machine.update(unchanged, 1002)
    assertEquals(SAFETY_PAUSING, failed.safety)
    assertEquals(None, failed.action)
}

@Test fun `prepare reset clears observed desired expected and retry`() {
    val machine = machine(target = allRoles(OFF), auto = ON)
    machine.update(observation(auto = OFF, roles = allRoles(OFF)), 0)
    machine.reset()
    assertNull(machine.snapshot().observed)
    assertNull(machine.snapshot().desired)
    assertNull(machine.snapshot().expected)
    assertEquals(0, machine.snapshot().retryCount)
    assertEquals(RUNNING, machine.snapshot().safety)
}
```

- [ ] **Step 5: Implement confirmation, timeout, and one retry**

Use `CONFIRM_FRAMES = 2`, `CONFIRM_TIMEOUT_MS = 500L`, and `MAX_RETRIES = 1`. A contradictory consistent observation fails confirmation; an UNKNOWN observation waits until timeout. The second timeout transitions to `SAFETY_PAUSING`.

- [ ] **Step 6: Verify GREEN and commit**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.kokkoro.clanbattle.control.BattleControlStateMachineTest
git add android/app/src/main/java/com/kokkoro/clanbattle/control/BattleControlStateMachine.kt android/app/src/test/java/com/kokkoro/clanbattle/control/BattleControlStateMachineTest.kt
git commit -m "feat: converge battle control state"
```

---

### Task 5: Make Action Execution Stateless and Correct Role Coordinates

**Files:**

- Modify: `android/app/src/main/java/com/kokkoro/clanbattle/automation/ActionExecutor.kt`
- Create: `android/app/src/test/java/com/kokkoro/clanbattle/automation/ActionCoordinatesTest.kt`

- [ ] **Step 1: Write failing coordinate tests**

```kotlin
@Test fun `roles are left to right one through five`() {
    assertEquals(Point(480, 845), ActionCoordinates.role(ROLE_1))
    assertEquals(Point(720, 845), ActionCoordinates.role(ROLE_2))
    assertEquals(Point(960, 845), ActionCoordinates.role(ROLE_3))
    assertEquals(Point(1200, 845), ActionCoordinates.role(ROLE_4))
    assertEquals(Point(1440, 845), ActionCoordinates.role(ROLE_5))
}

@Test fun `global auto and menu points match reference UI`() {
    assertEquals(Point(1828, 690), ActionCoordinates.globalSet)
    assertEquals(Point(1828, 845), ActionCoordinates.auto)
    assertEquals(Point(1805, 50), ActionCoordinates.menu)
}
```

- [ ] **Step 2: Verify RED**

Expected: missing `ActionCoordinates`; this also exposes the current reversed role mapping.

- [ ] **Step 3: Implement coordinates and stateless single-action methods**

Remove `autoOn`. Add:

```kotlin
fun tapAuto(width: Int, height: Int)
fun tapGlobalSet(width: Int, height: Int)
fun tapRole(role: CharacterRole, width: Int, height: Int)
fun tapMenu(width: Int, height: Int)
```

Each method performs exactly one scaled accessibility tap. `execute()` delegates unconditional axis clicks to these methods, but target-state AUTO/SET actions are routed through the state machine in Task 7.

- [ ] **Step 4: Verify GREEN and commit**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.kokkoro.clanbattle.automation.ActionCoordinatesTest
git add android/app/src/main/java/com/kokkoro/clanbattle/automation/ActionExecutor.kt android/app/src/test/java/com/kokkoro/clanbattle/automation/ActionCoordinatesTest.kt
git commit -m "fix: use observed control state for action coordinates"
```

---

### Task 6: Add Manual-Recovery Safety Pause

**Files:**

- Modify: `android/app/src/main/java/com/kokkoro/clanbattle/control/BattleControlStateMachine.kt`
- Modify: `android/app/src/test/java/com/kokkoro/clanbattle/control/BattleControlStateMachineTest.kt`

- [ ] **Step 1: Write failing safety tests**

```kotlin
@Test fun `safety pausing clicks menu once only when menu is trustworthy`() {
    machine.forceSafety("state-mismatch")
    assertEquals(TapMenu, machine.updateMenu(menuScore = 0.82).action)
    assertEquals(SAFETY_PAUSED, machine.safetyState)
    assertEquals(None, machine.updateMenu(menuScore = 0.90).action)
}

@Test fun `untrusted menu freezes without guessing a click`() {
    machine.forceSafety("state-mismatch")
    val step = machine.updateMenu(menuScore = 0.40)
    assertEquals(None, step.action)
    assertEquals("menu-button-untrusted", step.reason)
}

@Test fun `manual recovery discards expected state and replans from fresh observation`() {
    machine.enterPausedForTest()
    machine.updateRecovery(menuButtonScore = 0.82, observation = trustworthyObservation, nowMs = 1000)
    machine.updateRecovery(menuButtonScore = 0.84, observation = trustworthyObservation, nowMs = 1050)
    assertEquals(RUNNING, machine.safetyState)
    assertNull(machine.expectedState)
}
```

- [ ] **Step 2: Verify RED**

Expected: missing menu safety APIs.

- [ ] **Step 3: Implement menu confirmation and manual recovery**

Use `FixedTemplateMatcher.score(menuCrop, menuTemplate) >= 0.70` before emitting `TapMenu`. After the click, set `SAFETY_PAUSED` and ignore normal control/clock/energy failures. Recovery requires the normal menu button to be visible again with score at least `0.70` and two consecutive trustworthy control observations; then clear expected/retry state and replan from the current desired target.

- [ ] **Step 4: Verify GREEN and commit**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.kokkoro.clanbattle.control.BattleControlStateMachineTest
git add android/app/src/main/java/com/kokkoro/clanbattle/control/BattleControlStateMachine.kt android/app/src/test/java/com/kokkoro/clanbattle/control/BattleControlStateMachineTest.kt
git commit -m "feat: pause game on untrusted control state"
```

---

### Task 7: Integrate Recognition and Opening Convergence into FrameProcessor

**Files:**

- Create: `android/app/src/main/java/com/kokkoro/clanbattle/control/AndroidControlTemplateLoader.kt`
- Modify: `android/app/src/main/java/com/kokkoro/clanbattle/capture/FrameProcessor.kt`
- Create: `android/app/src/main/java/com/kokkoro/clanbattle/capture/ControlStatusFormatter.kt`
- Create: `android/app/src/test/java/com/kokkoro/clanbattle/capture/ControlStatusFormatterTest.kt`

- [ ] **Step 1: Write failing overlay formatter tests**

```kotlin
@Test fun `formats target current and pending control action`() {
    assertEquals(
        "目标 AUTO:开 角色:OXXXO\n当前 AUTO:关 全体UB:关 角色:OOOOO  调整:AUTO",
        ControlStatusFormatter.format(step)
    )
}

@Test fun `formats manual safety pause`() {
    assertEquals(
        "游戏已暂停：控制状态连续不可信\n请手动点击菜单外区域恢复",
        ControlStatusFormatter.format(pausedStep)
    )
}
```

- [ ] **Step 2: Verify RED**

Expected: missing formatter.

- [ ] **Step 3: Add Android template loading**

Load from the exact asset paths `templates/auto_on.bmp`, `templates/auto_off.bmp`, `templates/set_on.bmp`, `templates/set_off.bmp`, `templates/set.bmp`, and `templates/menu.bmp` using the same bitmap-to-`PixelImage` conversion pattern as `BattleTemplateLoader`.

- [ ] **Step 4: Integrate per-frame control crops and state machine**

After loading/start gates and before Scheduler execution:

1. Scale and extract AUTO, global SET, five role badge, and menu ROIs.
2. Recognize control state unless safety state is `SAFETY_PAUSED`.
3. Feed the observation and elapsed time to the state machine.
4. Execute at most one returned control action.
5. While opening target is unconfirmed or safety is not RUNNING, do not call normal Scheduler actions.
6. Once opening target is confirmed, strip the 90-second AUTO/SET target actions from normal Scheduler execution so they do not run twice.
7. Continue clock and energy diagnostics, but ignore their low-confidence results while the menu overlay is active.

- [ ] **Step 5: Implement status formatting and reset lifecycle**

Append control status below the existing clock/TP line. `prepareNewBattle()` resets recognizer session state, state machine, pending confirmation, and safety state.

- [ ] **Step 6: Verify focused and full tests, then commit**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.kokkoro.clanbattle.capture.ControlStatusFormatterTest
.\gradlew.bat testDebugUnitTest
git add android/app/src/main/java/com/kokkoro/clanbattle/control/AndroidControlTemplateLoader.kt android/app/src/main/java/com/kokkoro/clanbattle/capture/FrameProcessor.kt android/app/src/main/java/com/kokkoro/clanbattle/capture/ControlStatusFormatter.kt android/app/src/test/java/com/kokkoro/clanbattle/capture/ControlStatusFormatterTest.kt
git commit -m "feat: verify opening battle controls from screen"
```

---

### Task 8: Add Bounded Control Diagnostics

**Files:**

- Modify: `android/app/src/main/java/com/kokkoro/clanbattle/capture/ClockDebugCsv.kt`
- Modify: `android/app/src/main/java/com/kokkoro/clanbattle/capture/ClockDebugRecorder.kt`
- Modify: `android/app/src/test/java/com/kokkoro/clanbattle/capture/ClockDebugCsvTest.kt`

- [ ] **Step 1: Write the failing CSV contract test**

```kotlin
@Test fun `control diagnostic row matches header`() {
    val values = ClockDebugCsv.controlValues(frameId = 12, wallMs = 34, trace = trace)
    assertEquals(ClockDebugCsv.CONTROL_HEADER.split(',').size, values.size)
    val row = ClockDebugCsv.CONTROL_HEADER.split(',').zip(values.map(Any?::toString)).toMap()
    assertEquals("ON", row.getValue("autoState"))
    assertEquals("TapRole:ROLE_2", row.getValue("action"))
    assertEquals("SAFETY_PAUSED", row.getValue("safetyState"))
}
```

- [ ] **Step 2: Verify RED**

Expected: missing control header/value builder.

- [ ] **Step 3: Implement `controls.csv` and bounded ROI saving**

Add columns for all seven states/scores, consistency reason, desired/expected/observed encodings, action, retry count, safety state, and pause reason. Save small ROI PNGs only when confidence is unknown, before/after a click, or on safety transition. Reuse the existing bounded asynchronous queue; do not add a second thread.

- [ ] **Step 4: Verify GREEN and commit**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.kokkoro.clanbattle.capture.ClockDebugCsvTest
git add android/app/src/main/java/com/kokkoro/clanbattle/capture/ClockDebugCsv.kt android/app/src/main/java/com/kokkoro/clanbattle/capture/ClockDebugRecorder.kt android/app/src/test/java/com/kokkoro/clanbattle/capture/ClockDebugCsvTest.kt
git commit -m "feat: record battle control diagnostics"
```

---

### Task 9: Full Regression, APK Deployment, and Live Acceptance

**Files:** No production changes unless a failing acceptance test first reproduces a defect.

- [ ] **Step 1: Run all automated checks**

```powershell
cd android
.\gradlew.bat testDebugUnitTest assembleDebug
cd ..
python -m unittest discover -s tools/tests -p "test_*.py"
git diff --check
git status --short
```

Expected: JVM and Python tests pass, Debug APK builds, diff check is clean, and only intentionally ignored local diagnostic files remain.

- [ ] **Step 2: Install without launching during an active battle**

```powershell
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s 127.0.0.1:5555 install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Expected: `Success`. Reopen the assistant and grant MediaProjection before starting the acceptance battle.

- [ ] **Step 3: Validate inherited-state recognition**

Before each battle manually set a different inherited state. Verify the overlay reports AUTO, global SET, and roles 1–5 exactly as shown on screen, including the known `O X O X O` fixture pattern.

- [ ] **Step 4: Validate opening convergence**

Use an axis with:

```text
1:30 | AUTO=开 | SET=开,关,开,关,开
```

Start with AUTO off and all roles on. Verify the assistant performs the minimum sequence: AUTO once, role2 once, role4 once; each action waits for visual confirmation. Repeat with all roles off and target all on; verify one global SET click.

- [ ] **Step 5: Validate 90–88 anchoring and no duplicate execution**

At 4× speed, allow first accepted clock to be 1:29 or 1:28. Verify the 1:30 opening target still converges once and the Scheduler does not execute the same AUTO/SET actions again.

- [ ] **Step 6: Validate manual safety pause**

Temporarily obstruct one role badge ROI until state becomes untrustworthy. Verify menu is clicked once, game pauses immediately, no recognition or axis action continues under blur, and overlay requests manual recovery. Manually click outside the menu, remove the obstruction, and verify fresh recognition/replanning resumes without using the abandoned expected state.

- [ ] **Step 7: Pull and audit diagnostics**

Pull the latest `controls.csv`, `frames.csv`, and saved control crops. Confirm every click has a matching expected→observed confirmation, no UNKNOWN state caused a control click, and safety pause has exactly one menu click.

- [ ] **Step 8: Final verification commit if acceptance required no fixes**

No empty commit is required. Record the acceptance evidence in the handoff response and keep the worktree clean.
