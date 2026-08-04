# Switch Axis and Pause-Frame Design

## Goal

Add a production Android execution path for switch axes: import and select multiple axes, converge persistent AUTO/SET targets after time or UB triggers, support conservative Boss-UB delays, and pause-frame a single named role through the existing overlay on MuMu.

Sequence-axis one-shot SET behavior is documented separately and is not implemented in this phase.

## Safety invariants

- Boss-UB-after actions must never execute early.
- A control click is confirmed only from the visual field affected by that action.
- No control action executes while the screen is covered by the game pause-menu blur.
- The selected axis is locked after the battle session starts.
- Pause-frame confirmation is always manual; there is no automatic target-frame decision.
- When focus state, menu state, or control state is ambiguous, automation stops or waits instead of guessing.

## Switch-axis format

```text
轴类型=开关
轴名称=E5刀1

[轴开局] | SET=关,关,关,关,开 | AUTO=开 | 提示=开局

1:12 | UB后=角色5 | SET=关,关,关,关,关 | AUTO=开 | 提示=角色5UB后
0:57 | UB后=角色4 | SET=开,关,开,开,开 | AUTO=开
0:26 | UB后=BOSS | 延迟=1.20 | SET=关,开,关,关,开 | AUTO=开 | 提示=Boss UB后
0:18 | 卡帧=角色3 | SET=开,关,开,关,开 | AUTO=开 | 提示=观察角色3动作
```

### Opening node

- A switch axis must contain exactly one `[轴开局]` node.
- The opening node supports `SET`, `AUTO`, and `提示`.
- It is anchored from the first trustworthy clock reading in `88..90`.
- Actual inherited controls are recognized first, then converged with verified minimal clicks.

### Timed node

- A node without `UB后` or `卡帧` becomes eligible when the clock reaches or skips past its time.
- Its complete SET/AUTO target is converged immediately after eligibility.

### Character-UB node

- `UB后=角色1` through `UB后=角色5` names one role.
- The node becomes armed when the clock reaches or skips past its time.
- It executes only after the named role produces a trustworthy full-to-empty energy transition.
- Energy events observed before the node was armed cannot satisfy it.
- Ambiguous or missing energy evidence keeps the node waiting.

### Boss-UB node

- `UB后=BOSS` requires `延迟=<seconds>` with a positive decimal value.
- The delay starts when the node becomes eligible from the game clock.
- The delay is a user-calibrated minimum wall-clock delay and is never shortened by image heuristics.
- Character/Boss continuous UB ambiguity must be included in the configured delay.
- When the delay expires under an untrustworthy or blurred screen, execution continues waiting until the normal controls are trustworthy.
- Energy changes may be recorded diagnostically but cannot cause early execution.

### Pause-frame node

- `卡帧=角色N` names exactly one target role.
- `SET` and `AUTO` remain mandatory node targets.
- The node becomes eligible from the game clock and enters manual pause-frame mode instead of executing immediately.
- `提示` is display text only and never determines the target role.

## Switch execution model

`SwitchAxisRuntime` owns one ordered list of switch nodes and exposes one active node at a time.

```text
WAITING_OPENING
  -> CONVERGING_OPENING
  -> WAITING_TIME
  -> WAITING_CHARACTER_UB | WAITING_BOSS_DELAY | PAUSE_FRAME
  -> CONVERGING_NODE
  -> WAITING_TIME
```

- Nodes stay ordered by source order when times are equal.
- A later node cannot overtake an active node.
- Clock skips arm all crossed nodes, but they remain queued in source order.
- SET/AUTO convergence uses the existing battle-control state machine and executes at most one verified click at a time.
- A node completes only when its entire declared target is visually confirmed.

## Multiple-axis library

The main activity imports and stores multiple text axes.

- Axis text files are copied into app-private `filesDir/axes/` storage.
- Metadata contains a stable ID, display name, axis type, validation result, event count, and source filename.
- The selected axis ID persists in preferences; battle runtime state does not.
- Invalid axes remain visible in the main activity with validation errors but cannot be selected from the overlay.
- Importing the same stable ID replaces that stored axis after validation.

The overlay's `选择轴` button opens a secondary overlay list of valid imported axes. Selection is allowed only while waiting for battle start or after reset. Once a battle session starts, selection is locked until reset.

## Overlay redesign

The current large status `TextView` is removed. The overlay becomes a compact button panel:

- `选择轴`: shows the selected axis name and opens the imported-axis list.
- `下一帧`: enabled only in pause-frame mode.
- `确定`: enabled only in pause-frame mode.
- `安全菜单`: requests the existing verified menu pause.
- `重置`: resets the battle session and unlocks axis selection.

State is expressed by button text and panel color:

- gray: no valid axis or waiting for battle;
- green: switch axis running;
- amber: manual pause-frame active;
- red: safety pausing/paused or focus failure.

Short operational text may appear inside the selected-axis button, but per-frame recognition diagnostics are no longer rendered as a large overlay paragraph. Full detail remains in CSV diagnostics and the main activity status.

## MuMu focus-based pause-frame

The existing overlay uses `FLAG_NOT_FOCUSABLE`, so tapping it is consumed by the overlay without taking focus from the game. That is why it does not currently soft-pause MuMu.

The new `OverlayFocusController` dynamically updates the overlay window flags:

- acquire focus: remove `FLAG_NOT_FOCUSABLE`, make the root focusable, and request focus; MuMu soft-pauses the game without a blur menu;
- release focus: restore `FLAG_NOT_FOCUSABLE`; MuMu returns focus to the game.

### Enter pause-frame

1. Stop scheduler and control convergence.
2. Acquire overlay focus.
3. Require a clear, non-menu frame and a stable clock/control snapshot.
4. Enable `下一帧` and `确定`.

### Advance one frame

The Android implementation follows the archived `PCRPauseFrame.js` MuMu performance sequence:

1. release overlay focus;
2. allow MuMu/game focus transition;
3. send one accessibility Back action to dismiss the game pause menu produced by focus return;
4. wait the configured frame interval, initially `40ms`;
5. reacquire overlay focus;
6. return to manual pause-frame state.

Only one advance operation may run at a time. Repeated taps while an advance is in flight are ignored.

### Confirm target frame

1. Disable pause-frame buttons.
2. Release overlay focus.
3. dismiss the focus-return pause menu;
4. tap the single role declared by `卡帧=角色N`;
5. immediately start converging all five SET values and AUTO declared by that same node;
6. resume switch-axis scheduling only after the complete target state is visually confirmed.

The runtime does not wait for energy loss or UB completion after the user presses `确定`.

### Safety behavior

- Failure to acquire/release focus enters safety state without clicking the role.
- Unexpected menu blur during pause-frame confirmation blocks all role/control clicks.
- `安全菜单` exits focus mode and uses the calibrated menu button to enter the normal blurred game pause menu.
- Pause-frame has no automatic timeout; the user must choose `下一帧`, `确定`, `安全菜单`, or `重置`.

## Diagnostics

Add switch-axis and focus diagnostics to the existing bounded asynchronous recorder:

- selected axis ID/name/type;
- active node source line and trigger type;
- node state and eligible wall time;
- character UB event or Boss minimum-delay deadline;
- focus acquire/release requests and outcomes;
- pause-frame advance/confirm actions;
- target role;
- desired/observed/expected SET/AUTO;
- safety reason.

ROI saving remains bounded and event-driven.

## Testing

### Pure JVM tests

- parse and validate `[轴开局]`, timed nodes, `UB后=角色N`, `UB后=BOSS`, `延迟`, and `卡帧=角色N`;
- reject missing opening nodes, invalid role names, Boss triggers without delay, multiple pause-frame roles, and missing SET/AUTO targets;
- preserve same-time source order and skipped-clock catch-up;
- arm character-UB nodes only after their time and accept only the named role's later energy drop;
- never execute Boss nodes before their wall-clock deadline;
- keep Boss nodes waiting when controls are untrustworthy at the deadline;
- lock axis selection during an active battle;
- verify overlay focus state transitions and reject reentrant frame advances;
- confirm a pause-frame node clicks its one target role and then converges all SET/AUTO targets without waiting for UB.

### Emulator acceptance

- import at least two axes and switch between them from the overlay before battle;
- verify selection locks after battle start and unlocks after reset;
- run opening, timed, character-UB, and Boss-delay nodes;
- verify Boss nodes never execute before configured delay;
- enter MuMu soft pause with no menu blur;
- advance repeatedly and observe bounded frame movement;
- confirm a target frame and verify role click followed immediately by SET/AUTO convergence;
- force focus/menu errors and verify no blind role click occurs.
