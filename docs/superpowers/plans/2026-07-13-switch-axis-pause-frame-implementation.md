# Switch Axis and Pause-Frame Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add multiple stored axes, a complete switch-axis runtime with time/character-UB/Boss-delay/pause-frame triggers, and a MuMu focus-based overlay for manual frame stepping and confirmed execution.

**Architecture:** Extend parsing with dedicated switch-axis nodes instead of forcing switch semantics through sequence `AxisEvent`s. Keep scheduling in a pure `SwitchAxisRuntime`, keep manual frame stepping in a pure `PauseFrameSession`, and adapt both in `FrameProcessor`/`OverlayController`. Store imported axes in app-private files with persisted metadata and expose selection through both the main activity and overlay.

**Tech Stack:** Kotlin/JVM 17, Android MediaProjection, `TYPE_APPLICATION_OVERLAY`, accessibility global actions/taps, SharedPreferences, app-private files, JUnit 4.

---

## File structure

**Create:**

- `axis/SwitchAxisModels.kt` — opening node, trigger types, switch nodes.
- `axis/AxisLibrary.kt` — pure metadata, selection lock, storage-facing API.
- `axis/AndroidAxisRepository.kt` — private-file persistence and preference metadata.
- `switchaxis/SwitchAxisRuntime.kt` — pure ordered trigger/runtime state machine.
- `pauseframe/PauseFrameSession.kt` — pure manual frame-step state machine.
- `pauseframe/OverlayFocusPort.kt` — focus/back/tap boundary.
- `pauseframe/AndroidOverlayFocusPort.kt` — WindowManager/accessibility implementation.
- focused JVM tests for every pure component.

**Modify:**

- `axis/AxisModels.kt`, `AxisParser.kt`, `AxisValidator.kt`.
- `config/AppPreferences.kt`.
- `MainActivity.kt`.
- `overlay/OverlayController.kt`.
- `capture/ScreenCaptureService.kt`, `FrameProcessor.kt`, diagnostics files.
- `automation/ActionExecutor.kt` when pause-frame requires global Back.

---

### Task 1: Parse switch-axis syntax into dedicated models

**Files:**

- Create: `android/app/src/main/java/com/kokkoro/clanbattle/axis/SwitchAxisModels.kt`
- Modify: `android/app/src/main/java/com/kokkoro/clanbattle/axis/AxisModels.kt`
- Modify: `android/app/src/main/java/com/kokkoro/clanbattle/axis/AxisParser.kt`
- Create: `android/app/src/test/java/com/kokkoro/clanbattle/axis/SwitchAxisParserTest.kt`

- [ ] **Step 1: Write failing parser tests**

```kotlin
@Test fun `parses switch opening and all trigger forms`() {
    val axis = AxisParser.parse("""
        轴类型=开关
        轴名称=E5刀1
        [轴开局] | SET=关,关,关,关,开 | AUTO=开 | 提示=开局
        1:12 | UB后=角色5 | SET=关,关,关,关,关 | AUTO=开
        0:26 | UB后=BOSS | 延迟=1.20 | SET=关,开,关,关,开 | AUTO=开
        0:18 | 卡帧=角色3 | SET=开,关,开,关,开 | AUTO=开
    """.trimIndent())

    assertEquals(AxisType.SWITCH, axis.type)
    assertEquals(VisualToggleState.ON, axis.switchOpening!!.auto)
    assertEquals(CharacterUbTrigger(CharacterRole.ROLE_5), axis.switchNodes[0].trigger)
    assertEquals(BossDelayTrigger(1_200), axis.switchNodes[1].trigger)
    assertEquals(PauseFrameTrigger(CharacterRole.ROLE_3), axis.switchNodes[2].trigger)
}

@Test fun `opening marker infers switch type for legacy files without header`() {
    val axis = AxisParser.parse("""
        [轴开局] | SET=关,关,关,关,开 | AUTO=开
        1:12 | SET=关,关,关,关,关 | AUTO=开
    """.trimIndent())
    assertEquals(AxisType.SWITCH, axis.type)
}
```

- [ ] **Step 2: Run RED**

```powershell
cd android
.\gradlew.bat testDebugUnitTest --tests com.kokkoro.clanbattle.axis.SwitchAxisParserTest
```

Expected: unresolved switch-axis model properties.

- [ ] **Step 3: Add the model API**

```kotlin
sealed interface SwitchNodeTrigger
data object TimedTrigger : SwitchNodeTrigger
data class CharacterUbTrigger(val role: CharacterRole) : SwitchNodeTrigger
data class BossDelayTrigger(val minimumDelayMs: Long) : SwitchNodeTrigger
data class PauseFrameTrigger(val role: CharacterRole) : SwitchNodeTrigger

data class SwitchControlTarget(
    val auto: VisualToggleState,
    val roles: Map<CharacterRole, VisualToggleState>,
    val message: String? = null
)

data class SwitchAxisNode(
    val id: String,
    val sourceLine: Int,
    val timeSeconds: Int,
    val trigger: SwitchNodeTrigger,
    val target: SwitchControlTarget
)
```

Extend `AxisDocument` with nullable `switchOpening` and `switchNodes`, defaulting to empty values for sequence compatibility.

- [ ] **Step 4: Implement parsing**

Recognize `[轴开局] | SET=关,关,关,关,开 | AUTO=开 | 提示=开局` as an inline opening record. After it, parse timed switch lines without requiring `[轴]`. Parse `SET`, `AUTO`, `提示`, `UB后`, `延迟`, and `卡帧`; reject duplicate fields at parse time. Infer `AxisType.SWITCH` when `[轴开局]` exists.

- [ ] **Step 5: Run GREEN and commit**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.kokkoro.clanbattle.axis.SwitchAxisParserTest
git add android/app/src/main/java/com/kokkoro/clanbattle/axis android/app/src/test/java/com/kokkoro/clanbattle/axis/SwitchAxisParserTest.kt
git commit -m "feat: parse switch axis nodes"
```

---

### Task 2: Validate switch axes conservatively

**Files:**

- Modify: `android/app/src/main/java/com/kokkoro/clanbattle/axis/AxisValidator.kt`
- Create: `android/app/src/test/java/com/kokkoro/clanbattle/axis/SwitchAxisValidatorTest.kt`

- [ ] **Step 1: Write failing validation tests**

Cover these exact codes:

```kotlin
"missing-switch-opening"
"duplicate-switch-opening"
"switch-target-required"
"invalid-character-ub-role"
"boss-delay-required"
"invalid-boss-delay"
"conflicting-switch-triggers"
"pause-frame-target-required"
```

Also assert that every switch node contains exactly five ON/OFF role targets and an AUTO target.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.kokkoro.clanbattle.axis.SwitchAxisValidatorTest
```

- [ ] **Step 3: Implement type-specific validation**

Sequence validation remains unchanged. Switch validation requires one opening target, ordered times in `0..90`, positive Boss delays no greater than 30 seconds, and one trigger kind per node.

- [ ] **Step 4: Run GREEN and commit**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.kokkoro.clanbattle.axis.SwitchAxisValidatorTest
git add android/app/src/main/java/com/kokkoro/clanbattle/axis/AxisValidator.kt android/app/src/test/java/com/kokkoro/clanbattle/axis/SwitchAxisValidatorTest.kt
git commit -m "feat: validate switch axes"
```

---

### Task 3: Store and select multiple imported axes

**Files:**

- Create: `android/app/src/main/java/com/kokkoro/clanbattle/axis/AxisLibrary.kt`
- Create: `android/app/src/main/java/com/kokkoro/clanbattle/axis/AndroidAxisRepository.kt`
- Modify: `android/app/src/main/java/com/kokkoro/clanbattle/config/AppPreferences.kt`
- Modify: `android/app/src/main/java/com/kokkoro/clanbattle/MainActivity.kt`
- Create: `android/app/src/test/java/com/kokkoro/clanbattle/axis/AxisLibraryTest.kt`

- [ ] **Step 1: Write failing pure library tests**

```kotlin
@Test fun `valid imports are selectable and selected id persists through snapshot`() {
    val library = AxisLibrary(InMemoryAxisStorage())
    val imported = library.import("switch.txt", VALID_SWITCH_TEXT)
    assertTrue(imported.valid)
    assertTrue(library.select(imported.id))
    assertEquals(imported.id, library.snapshot().selectedId)
}

@Test fun `invalid imports remain listed but cannot be selected`() {
    val library = AxisLibrary(InMemoryAxisStorage())
    val imported = library.import("bad.txt", "轴类型=未知\n[轴]\n1:00 | 提示=坏轴")
    assertFalse(imported.valid)
    assertFalse(library.select(imported.id))
    assertEquals(imported.id, library.list().single().id)
}

@Test fun `active battle locks selection until reset`() {
    val library = AxisLibrary(InMemoryAxisStorage())
    val first = library.import("a.txt", VALID_SWITCH_TEXT)
    val second = library.import("b.txt", VALID_SWITCH_TEXT.replace("E5刀1", "E5刀2"))
    library.select(first.id)
    library.lock()
    assertFalse(library.select(second.id))
    library.unlock()
    assertTrue(library.select(second.id))
}

@Test fun `reimporting identical normalized text replaces metadata without duplication`() {
    val library = AxisLibrary(InMemoryAxisStorage())
    val first = library.import("old-name.txt", VALID_SWITCH_TEXT)
    val second = library.import("new-name.txt", "\r\n$VALID_SWITCH_TEXT\r\n")
    assertEquals(first.id, second.id)
    assertEquals(1, library.list().size)
    assertEquals("new-name.txt", library.list().single().sourceName)
}
```

Define:

```kotlin
data class StoredAxis(
    val id: String,
    val name: String,
    val sourceName: String,
    val type: AxisType,
    val eventCount: Int,
    val valid: Boolean,
    val validationMessage: String?
)
```

- [ ] **Step 2: Run RED and implement `AxisLibrary`**

`AxisLibrary` receives a storage port, exposes `list()`, `import()`, `select()`, `selected()`, `lock()`, and `unlock()`, and refuses selection while locked.

- [ ] **Step 3: Implement Android persistence**

Copy imported UTF-8 text to `filesDir/axes/<stable-id>.txt`. Persist metadata and selected ID in SharedPreferences. Use a SHA-256 prefix of normalized text as stable ID; no external JSON dependency is added.

- [ ] **Step 4: Update main activity**

Replace the single-axis label with a vertical imported-axis list showing name/type/validation. Keep `ACTION_OPEN_DOCUMENT` for import. Add select and delete controls for stored axes.

- [ ] **Step 5: Verify and commit**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.kokkoro.clanbattle.axis.AxisLibraryTest
git add android/app/src/main/java/com/kokkoro/clanbattle/axis android/app/src/main/java/com/kokkoro/clanbattle/config/AppPreferences.kt android/app/src/main/java/com/kokkoro/clanbattle/MainActivity.kt android/app/src/test/java/com/kokkoro/clanbattle/axis/AxisLibraryTest.kt
git commit -m "feat: manage multiple imported axes"
```

---

### Task 4: Implement the pure switch-axis runtime

**Files:**

- Create: `android/app/src/main/java/com/kokkoro/clanbattle/switchaxis/SwitchAxisRuntime.kt`
- Create: `android/app/src/test/java/com/kokkoro/clanbattle/switchaxis/SwitchAxisRuntimeTest.kt`

- [ ] **Step 1: Write failing runtime tests**

```kotlin
@Test fun `boss node never emits before minimum wall delay`() {
    val runtime = runtimeWith(node(time = 26, trigger = BossDelayTrigger(1_200)))
    runtime.update(frame(clock = 26, wallMs = 10_000, trustworthy = true))
    assertEquals(SwitchRuntimeCommand.None, runtime.update(frame(clock = 26, wallMs = 11_199, trustworthy = true)))
    assertTrue(runtime.update(frame(clock = 26, wallMs = 11_200, trustworthy = true)) is SwitchRuntimeCommand.Converge)
}

@Test fun `character ub before arming cannot satisfy node`() {
    val runtime = runtimeWith(node(time = 57, trigger = CharacterUbTrigger(CharacterRole.ROLE_4)))
    runtime.update(frame(clock = 58, triggered = setOf(CharacterRole.ROLE_4)))
    runtime.update(frame(clock = 57))
    assertEquals(SwitchRuntimeCommand.None, runtime.update(frame(clock = 57)))
    assertTrue(runtime.update(frame(clock = 57, triggered = setOf(CharacterRole.ROLE_4))) is SwitchRuntimeCommand.Converge)
}

@Test fun `pause frame blocks later nodes until manual confirmation`() {
    val pause = node(time = 18, trigger = PauseFrameTrigger(CharacterRole.ROLE_3))
    val later = node(time = 17, trigger = TimedTrigger)
    val runtime = runtimeWith(pause, later)
    assertTrue(runtime.update(frame(clock = 18)) is SwitchRuntimeCommand.EnterPauseFrame)
    assertEquals(SwitchRuntimeCommand.None, runtime.update(frame(clock = 17)))
    runtime.confirmPauseFrame(pause.id)
    assertTrue(runtime.update(frame(clock = 17)) is SwitchRuntimeCommand.Converge)
}

@Test fun `opening emits once from ninety through eighty eight`() {
    val runtime = runtimeWithOpening()
    val command = runtime.update(frame(clock = 89))
    assertTrue(command is SwitchRuntimeCommand.Converge)
    runtime.confirmConvergence((command as SwitchRuntimeCommand.Converge).nodeId)
    assertEquals(SwitchRuntimeCommand.None, runtime.update(frame(clock = 89)))
}

@Test fun `same time nodes retain source order`() {
    val first = node(id = "first", time = 42, trigger = TimedTrigger)
    val second = node(id = "second", time = 42, trigger = TimedTrigger)
    val runtime = runtimeWith(first, second)
    assertEquals("first", (runtime.update(frame(clock = 42)) as SwitchRuntimeCommand.Converge).nodeId)
    runtime.confirmConvergence("first")
    assertEquals("second", (runtime.update(frame(clock = 42)) as SwitchRuntimeCommand.Converge).nodeId)
}

@Test fun `wrong role energy drop cannot satisfy character ub node`() {
    val runtime = runtimeWith(node(time = 57, trigger = CharacterUbTrigger(CharacterRole.ROLE_4)))
    runtime.update(frame(clock = 57))
    assertEquals(
        SwitchRuntimeCommand.None,
        runtime.update(frame(clock = 57, triggered = setOf(CharacterRole.ROLE_2)))
    )
}

@Test fun `boss deadline waits for trustworthy controls`() {
    val runtime = runtimeWith(node(time = 26, trigger = BossDelayTrigger(1_200)))
    runtime.update(frame(clock = 26, wallMs = 10_000, trustworthy = true))
    assertEquals(SwitchRuntimeCommand.None, runtime.update(frame(clock = 25, wallMs = 11_200, trustworthy = false)))
    assertTrue(runtime.update(frame(clock = 25, wallMs = 11_300, trustworthy = true)) is SwitchRuntimeCommand.Converge)
}
```

Use this public boundary:

```kotlin
data class SwitchFrameInput(
    val clockSeconds: Int?,
    val triggeredRoles: Set<CharacterRole>,
    val controlsTrustworthy: Boolean,
    val wallMs: Long
)

sealed interface SwitchRuntimeCommand {
    data object None : SwitchRuntimeCommand
    data class Converge(val nodeId: String, val target: SwitchControlTarget) : SwitchRuntimeCommand
    data class EnterPauseFrame(val nodeId: String, val role: CharacterRole) : SwitchRuntimeCommand
}
```

- [ ] **Step 2: Run RED and implement minimal state transitions**

Keep one active node and a queue of crossed nodes. `confirmConvergence(nodeId)` completes a node. `confirmPauseFrame(nodeId)` changes the pause-frame node into `Converge` without waiting for UB.

- [ ] **Step 3: Run GREEN and commit**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.kokkoro.clanbattle.switchaxis.SwitchAxisRuntimeTest
git add android/app/src/main/java/com/kokkoro/clanbattle/switchaxis android/app/src/test/java/com/kokkoro/clanbattle/switchaxis
git commit -m "feat: schedule switch axis triggers"
```

---

### Task 5: Route switch targets through verified control convergence

**Files:**

- Modify: `android/app/src/main/java/com/kokkoro/clanbattle/capture/FrameProcessor.kt`
- Modify: `android/app/src/main/java/com/kokkoro/clanbattle/control/BattleControlStateMachine.kt`
- Create: `android/app/src/test/java/com/kokkoro/clanbattle/switchaxis/SwitchControlCoordinatorTest.kt`

- [ ] **Step 1: Write failing coordinator tests**

Verify a switch target:

- sets desired AUTO/roles;
- emits at most one click per frame;
- confirms the entire target before completing the node;
- blocks later nodes;
- keeps Boss nodes waiting under UNKNOWN controls;
- resets with a new selected axis.

- [ ] **Step 2: Implement an axis-mode coordinator**

Create a small coordinator that selects the existing sequence path for `SEQUENCE` and `SwitchAxisRuntime` for `SWITCH`. Do not add switch branches throughout `FrameProcessor`.

- [ ] **Step 3: Refactor axis loading**

`FrameProcessor` must no longer capture one axis permanently in its constructor. `prepareNewBattle(document)` resets recognizers and installs the selected immutable document for the new session.

- [ ] **Step 4: Verify and commit**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.kokkoro.clanbattle.switchaxis.SwitchControlCoordinatorTest
git add android/app/src/main/java/com/kokkoro/clanbattle/capture/FrameProcessor.kt android/app/src/main/java/com/kokkoro/clanbattle/control/BattleControlStateMachine.kt android/app/src/test/java/com/kokkoro/clanbattle/switchaxis
git commit -m "feat: converge switch axis control targets"
```

---

### Task 6: Replace the overlay with functional controls and axis selection

**Files:**

- Modify: `android/app/src/main/java/com/kokkoro/clanbattle/overlay/OverlayController.kt`
- Modify: `android/app/src/main/java/com/kokkoro/clanbattle/capture/ScreenCaptureService.kt`
- Create: `android/app/src/main/java/com/kokkoro/clanbattle/overlay/OverlayUiState.kt`
- Create: `android/app/src/test/java/com/kokkoro/clanbattle/overlay/OverlayUiStateTest.kt`

- [ ] **Step 1: Write failing UI-state tests**

Test button labels/enabled states for idle, running, pause-frame, and safety states. Verify axis selection is disabled while the battle lock is active.

- [ ] **Step 2: Implement the compact panel**

Remove the large status TextView. Add `选择轴`, `下一帧`, `确定`, `安全菜单`, and `重置`. Build the axis list as a secondary `TYPE_APPLICATION_OVERLAY` panel populated only from valid stored axes.

- [ ] **Step 3: Wire service callbacks**

Expose callbacks for select axis, advance frame, confirm frame, request safety menu, and reset. Selection updates the stored selected ID but cannot replace the active battle document.

- [ ] **Step 4: Verify and commit**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.kokkoro.clanbattle.overlay.OverlayUiStateTest
git add android/app/src/main/java/com/kokkoro/clanbattle/overlay android/app/src/main/java/com/kokkoro/clanbattle/capture/ScreenCaptureService.kt android/app/src/test/java/com/kokkoro/clanbattle/overlay
git commit -m "feat: add functional battle overlay"
```

---

### Task 7: Implement MuMu focus-based pause-frame

**Files:**

- Create: `android/app/src/main/java/com/kokkoro/clanbattle/pauseframe/OverlayFocusPort.kt`
- Create: `android/app/src/main/java/com/kokkoro/clanbattle/pauseframe/PauseFrameSession.kt`
- Create: `android/app/src/main/java/com/kokkoro/clanbattle/pauseframe/AndroidOverlayFocusPort.kt`
- Modify: `android/app/src/main/java/com/kokkoro/clanbattle/overlay/OverlayController.kt`
- Modify: `android/app/src/main/java/com/kokkoro/clanbattle/automation/KokkoroAccessibilityService.kt`
- Create: `android/app/src/test/java/com/kokkoro/clanbattle/pauseframe/PauseFrameSessionTest.kt`

- [ ] **Step 1: Write failing pure session tests**

```kotlin
@Test fun `advance releases focus sends back waits interval and reacquires focus`() {
    val port = FakeOverlayFocusPort()
    val clock = FakeDelayPort()
    val session = PauseFrameSession(port, clock, frameIntervalMs = 40, focusTransitionMs = 1_000)
    session.enter("node-1", CharacterRole.ROLE_3)
    session.advance()
    assertEquals(listOf("focus:on", "focus:off", "delay:1000", "back", "delay:40", "focus:on"), port.events + clock.events)
    assertEquals(PauseFrameState.SOFT_PAUSED, session.snapshot().state)
}

@Test fun `confirm emits exactly one target role and does not wait for ub`() {
    val port = FakeOverlayFocusPort()
    val session = PauseFrameSession(port, FakeDelayPort(), 40, 1_000)
    session.enter("node-1", CharacterRole.ROLE_3)
    val result = session.confirm()
    assertEquals(CharacterRole.ROLE_3, result.confirmedRole)
    assertEquals(listOf(CharacterRole.ROLE_3), port.roleTaps)
    assertTrue(result.readyForConvergence)
}

@Test fun `focus failure enters safety without role click`() {
    val port = FakeOverlayFocusPort(failAcquire = true)
    val session = PauseFrameSession(port, FakeDelayPort(), 40, 1_000)
    val result = session.enter("node-1", CharacterRole.ROLE_3)
    assertEquals(PauseFrameState.FAILED, result.state)
    assertTrue(port.roleTaps.isEmpty())
}

@Test fun `reentrant advance is rejected`() {
    val port = FakeOverlayFocusPort(blockAdvance = true)
    val session = PauseFrameSession(port, FakeDelayPort(), 40, 1_000)
    session.enter("node-1", CharacterRole.ROLE_3)
    session.advance()
    assertFalse(session.advance().accepted)
}

@Test fun `enter reports scheduler blocking`() {
    val session = PauseFrameSession(FakeOverlayFocusPort(), FakeDelayPort(), 40, 1_000)
    val result = session.enter("node-1", CharacterRole.ROLE_3)
    assertTrue(result.blocksScheduler)
    assertEquals(PauseFrameState.SOFT_PAUSED, result.state)
}
```

Inject a scheduler/clock port so tests advance virtual wall time without sleeping.

- [ ] **Step 2: Implement the pure session**

States: `IDLE`, `SOFT_PAUSED`, `ADVANCING`, `CONFIRMING`, `FAILED`. Default interval is `40ms`; focus-transition delay is configurable and initially `1_000ms`, matching the archived MuMu flow.

- [ ] **Step 3: Implement Android focus control**

Use `WindowManager.updateViewLayout()` to remove/add `FLAG_NOT_FOCUSABLE`, set `isFocusableInTouchMode`, and call `requestFocus()`. Accessibility exposes a global Back action and existing scaled role taps.

- [ ] **Step 4: Connect confirmation to switch runtime**

After the target role tap, call `confirmPauseFrame(nodeId)` and immediately start SET/AUTO convergence. Do not wait for energy or UB state.

- [ ] **Step 5: Verify and commit**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.kokkoro.clanbattle.pauseframe.PauseFrameSessionTest
git add android/app/src/main/java/com/kokkoro/clanbattle/pauseframe android/app/src/main/java/com/kokkoro/clanbattle/overlay/OverlayController.kt android/app/src/main/java/com/kokkoro/clanbattle/automation/KokkoroAccessibilityService.kt android/app/src/test/java/com/kokkoro/clanbattle/pauseframe
git commit -m "feat: pause frame through overlay focus"
```

---

### Task 8: Add switch/focus diagnostics

**Files:**

- Modify: `android/app/src/main/java/com/kokkoro/clanbattle/capture/ClockDebugCsv.kt`
- Modify: `android/app/src/main/java/com/kokkoro/clanbattle/capture/ClockDebugRecorder.kt`
- Modify: `android/app/src/test/java/com/kokkoro/clanbattle/capture/ClockDebugCsvTest.kt`

- [ ] **Step 1: Write a failing CSV contract test**

Add `switch.csv` columns for selected axis, node ID/line, trigger, runtime state, eligible/deadline wall times, triggered roles, focus action/result, pause-frame action, target role, desired state, and safety reason.

- [ ] **Step 2: Reuse the bounded recorder queue**

Write one row on node/focus/action transitions and throttle repeated waiting rows to one per second. Do not create a new executor thread.

- [ ] **Step 3: Verify and commit**

```powershell
.\gradlew.bat testDebugUnitTest --tests com.kokkoro.clanbattle.capture.ClockDebugCsvTest
git add android/app/src/main/java/com/kokkoro/clanbattle/capture/ClockDebugCsv.kt android/app/src/main/java/com/kokkoro/clanbattle/capture/ClockDebugRecorder.kt android/app/src/test/java/com/kokkoro/clanbattle/capture/ClockDebugCsvTest.kt
git commit -m "feat: record switch axis and pause-frame diagnostics"
```

---

### Task 9: Regression, deployment, and emulator acceptance

**Files:** No production changes without a failing regression test.

- [ ] **Step 1: Run complete automated verification**

```powershell
cd android
.\gradlew.bat testDebugUnitTest assembleDebug
cd ..
python -m unittest discover -s tools/tests -p "test_*.py"
git diff --check
git status --short
```

- [ ] **Step 2: Install on MuMu when no active battle is running**

```powershell
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s 127.0.0.1:5555 install -r android/app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 3: Accept multiple-axis selection**

Import two valid switch axes and one invalid axis. Verify only valid axes appear in the overlay, selection persists, locks after start, and unlocks on reset.

- [ ] **Step 4: Accept switch triggers**

Run opening, direct timed, named-character UB, and Boss-delay nodes. Record evidence that the Boss node never executes before its deadline.

- [ ] **Step 5: Accept pause-frame**

Enter a pause-frame node, verify clear soft pause, advance repeatedly, confirm one target role, and verify immediate SET/AUTO convergence. Force focus failure and confirm no role click occurs.

- [ ] **Step 6: Audit diagnostics**

Pull `switch.csv`, `controls.csv`, and saved transition crops. Confirm every node transition and focus action is explainable and no safety invariant was violated.
