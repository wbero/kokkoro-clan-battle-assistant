# New Battle Boundary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an explicit player-controlled new-battle reset that blocks clock scheduling until a valid `1:30` opening anchor is recognized.

**Architecture:** A pure Kotlin `BattleSessionGate` owns the waiting/running state. `MainActivity` sends a prepare action to `ScreenCaptureService`, which resets the recognition filter, scheduler, and gate through `FrameProcessor`. While waiting, non-`1:30` readings are ignored and cannot poison temporal state.

**Tech Stack:** Kotlin, Android Service intents, JUnit 4, Gradle Android plugin.

---

### Task 1: Battle Session Gate

**Files:**
- Create: `android/app/src/main/java/com/kokkoro/clanbattle/capture/BattleSessionGate.kt`
- Test: `android/app/src/test/java/com/kokkoro/clanbattle/capture/BattleSessionGateTest.kt`

- [ ] Write tests proving waiting mode rejects `1:00`, accepts `1:30`, and running mode allows subsequent clock values.
- [ ] Run the focused test and verify it fails because `BattleSessionGate` does not exist.
- [ ] Implement the minimal waiting/running state machine.
- [ ] Run the focused test and verify it passes.

### Task 2: Reset Recognition And Scheduling

**Files:**
- Modify: `android/app/src/main/java/com/kokkoro/clanbattle/capture/FrameProcessor.kt`
- Modify: `android/app/src/main/java/com/kokkoro/clanbattle/capture/ScreenCaptureService.kt`

- [ ] Add `FrameProcessor.prepareNewBattle()` to reset `RecognitionFilter`, `Scheduler`, and `BattleSessionGate`.
- [ ] While waiting, avoid sending non-`1:30` readings into `RecognitionFilter`.
- [ ] Add `ACTION_PREPARE_BATTLE` handling to the capture service and publish a visible waiting status.

### Task 3: Player Control

**Files:**
- Modify: `android/app/src/main/java/com/kokkoro/clanbattle/MainActivity.kt`

- [ ] Add a `准备新战斗` button that sends `ACTION_PREPARE_BATTLE`.
- [ ] Show a concise status telling the player to open the game and start battle.

### Task 4: Verification

**Files:**
- Verify: `android/app/build/outputs/apk/debug/app-debug.apk`

- [ ] Run all JVM unit tests.
- [ ] Assemble the debug APK through proxy `127.0.0.1:7890`.
- [ ] Install on MuMu, authorize capture, press `准备新战斗`, and verify the first accepted clock is `1:30` rather than `1:00`.
