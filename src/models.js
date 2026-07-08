"use strict";

var ActionType = {
  CLICK_ROLE: "clickRole",
  CLICK_AUTO: "clickAuto",
  TOGGLE_AUTO: "toggleAuto",
  NOTIFY: "notify",
  BOSS: "boss",
  WAIT_BOSS_UB: "waitBossUb",
  RELEASE_UB: "releaseUb"
};

var AxisType = {
  SEQUENCE: "sequence",
  SWITCH: "switch"
};

/**
 * GameState 枚举 — 融合时钟+能量条的综合判定
 *
 * RUNNING:       游戏正常运行，时间在走或即将跳秒
 * UB_ANIMATION:  时间冻结 + 能量条冻结 → UB 动画播放中
 * UB_JUST_ENDED: 时间仍冻结 + 能量条恢复增长 → UB 动画刚结束
 * CHARACTER_UB:  检测到某角色能量条从满→空（角色 UB 刚释放）
 */
var GameState = {
  RUNNING: "running",
  UB_ANIMATION: "ub-animation",
  UB_JUST_ENDED: "ub-just-ended",
  CHARACTER_UB: "character-ub"
};

module.exports = {
  ActionType: ActionType,
  AxisType: AxisType,
  GameState: GameState
};
