"use strict";

var SRC = files.path(engines.myEngine().cwd());
var models = require(SRC + "/models.js");

/**
 * 游戏状态融合检测器
 *
 * 融合三个信号源：时钟识别 + 能量条检测 + UB 图标检测
 *
 * 借鉴 MaaPcrclanbattle 的双层验证思路：
 *   ColorMatch(能量满) + TemplateMatch(图标亮) → 确认 UB 可用
 *   inverse TemplateMatch(图标灭) → 确认 UB 放完
 *
 * 我们全部用像素颜色替代 TemplateMatch，适配手机性能。
 *
 * 判定矩阵（更新版）：
 *   ┌──────────────┬────────────┬────────────┬──────────────────────┐
 *   │ 时钟数字变了 │ 能量在涨   │ UB图标     │ → RUNNING             │
 *   │ 时钟没变     │ 能量在涨   │ —          │ → RUNNING (即将跳秒)  │
 *   │ 时钟没变     │ 能量冻结   │ 图标灭     │ → CHARACTER_UB_ENDING │
 *   │ 时钟没变     │ 能量冻结   │ 图标没变化 │ → UB_ANIMATION        │
 *   │ 时钟没变     │ 能量恢复涨 │ —          │ → UB_JUST_ENDED       │
 *   │ 某角色满→空   │ —          │ —          │ → CHARACTER_UB        │
 *   │ —            │ —          │ 图标刚灭   │ → UB_ICON_DROPPED     │
 *   └──────────────┴────────────┴────────────┴──────────────────────┘
 */

function createGameStateDetector(clockRecognizer, energyDetector) {

  var lastClockSeconds = null;
  var lastEnergyFrozen = null;
  // 持续冻结的帧数计数器（用于区分 Boss UB vs 角色 UB 链）
  var frozenFrameCount = 0;
  var BOSS_UB_FROZEN_THRESHOLD = 60; // 50ms × 60 = 3秒现实时间

  /**
   * 每帧调用，输出融合后的游戏状态
   */
  function detect(screenCapture, timerRegion, templates) {
    // 1. 时钟识别
    var clockResult = clockRecognizer.recognizeClock(screenCapture, timerRegion, templates);
    var clockSeconds = (clockResult && clockResult.ok) ? clockResult.timeSeconds : null;

    // 2. 能量条 + UB 图标检测
    var energyResult = energyDetector.detect(screenCapture);
    var energyFrozen = energyResult.energyFrozen;

    // 3. 时钟是否跳动
    var clockChanged = false;
    if (clockSeconds !== null && lastClockSeconds !== null) {
      clockChanged = clockSeconds !== lastClockSeconds;
    }

    // 4. 能量是否从冻结恢复
    var energyResumed = false;
    if (lastEnergyFrozen === true && energyFrozen === false) {
      energyResumed = true;
      frozenFrameCount = 0;
    }

    // 5. 冻结帧计数
    if (energyFrozen) {
      frozenFrameCount++;
    } else {
      frozenFrameCount = 0;
    }

    // 6. 综合判定游戏状态
    var state = models.GameState.RUNNING;
    var isBossUbCandidate = frozenFrameCount > BOSS_UB_FROZEN_THRESHOLD;

    if (energyResult.anyUbTriggered) {
      // 某角色能量从满→空 → 角色 UB 刚释放
      state = models.GameState.CHARACTER_UB;

    } else if (energyResult.anyUbFinished) {
      // UB 图标刚灭掉 → 某个角色的 UB 动画刚结束（包括 Auto UB）
      // 这是比"能量恢复涨"更精确的 UB 结束信号
      state = models.GameState.UB_JUST_ENDED;

    } else if (energyResumed) {
      // 能量从冻结恢复增长 → UB 动画刚结束
      state = models.GameState.UB_JUST_ENDED;

    } else if (energyFrozen && lastEnergyFrozen === true && isBossUbCandidate) {
      // 持续冻结超过阈值 → 很可能是 Boss UB
      // 标记为 UB_ANIMATION，但不改变调度行为（都是等待）
      state = models.GameState.UB_ANIMATION;

    } else if (energyFrozen && lastEnergyFrozen === true) {
      // 短期冻结 → 角色 UB 动画或 Boss UB 早期，继续等待
      state = models.GameState.UB_ANIMATION;

    } else if (clockChanged) {
      state = models.GameState.RUNNING;
    }
    // 默认 RUNNING

    // 7. 更新记忆
    if (clockSeconds !== null) {
      lastClockSeconds = clockSeconds;
    }
    lastEnergyFrozen = energyFrozen;

    return {
      state: state,
      clockSeconds: clockSeconds,
      clockChanged: clockChanged,
      energyDelta: energyResult.energyDelta,
      energyFrozen: energyFrozen,
      energyResumed: energyResumed,
      frozenFrameCount: frozenFrameCount,
      bossUbCandidate: isBossUbCandidate,
      anyUbTriggered: energyResult.anyUbTriggered,
      anyUbFinished: energyResult.anyUbFinished,
      triggeredCharacters: energyResult.triggeredCharacters,
      finishedCharacters: energyResult.finishedCharacters,
      characterStates: energyResult.characters
    };
  }

  function reset() {
    lastClockSeconds = null;
    lastEnergyFrozen = null;
    frozenFrameCount = 0;
    energyDetector.reset();
  }

  return {
    detect: detect,
    reset: reset
  };
}

module.exports = {
  createGameStateDetector: createGameStateDetector
};
