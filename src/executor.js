"use strict";

var SRC = files.path(engines.myEngine().cwd() + "/src");
var models = require(SRC + "/models.js");

/**
 * 动作执行模块
 *
 * 支持全部 7 种 ActionType + setRoles（开关轴）：
 *   CLICK_ROLE   — 点角色头像释放 UB
 *   CLICK_AUTO   — 点 AUTO 按钮
 *   TOGGLE_AUTO  — 切换 AUTO 状态（追踪开关）
 *   NOTIFY       — 弹出提示消息
 *   BOSS         — Boss UB 提示
 *   WAIT_BOSS_UB — 等待 Boss UB 结束（暂停调度 + 提示）
 *   RELEASE_UB   — 检测到能量满 + 图标亮，自动点角色释放 UB
 *   setRoles     — 开关轴 SET 批量点击
 */

function createExecutor(runtimeContext) {
  // AUTO 状态追踪
  var autoOn = false;

  /**
   * 执行一组动作
   * @param {Array} actions
   */
  function run(actions) {
    for (var i = 0; i < actions.length; i += 1) {
      var action = actions[i];
      var actionType = action.type;

      if (actionType === models.ActionType.NOTIFY) {
        toast(action.message);
        sleep(runtimeContext.clickIntervalMs || 100);
        continue;
      }

      if (actionType === models.ActionType.BOSS) {
        toast("BOSS UB");
        sleep(runtimeContext.clickIntervalMs || 100);
        continue;
      }

      if (actionType === models.ActionType.WAIT_BOSS_UB) {
        // Boss UB 等待：弹出提示，由调度器冻结机制处理时间暂停
        toast("等待 Boss UB 结束...");
        sleep(runtimeContext.clickIntervalMs || 100);
        continue;
      }

      if (actionType === models.ActionType.RELEASE_UB) {
        // 自动释放 UB：点击指定角色的头像
        var ubRole = runtimeContext.roles[action.role];
        if (!ubRole) {
          throw new Error("RELEASE_UB 未知角色：" + action.role);
        }
        click(ubRole.x, ubRole.y);
        sleep(runtimeContext.clickIntervalMs || 100);
        continue;
      }

      if (actionType === models.ActionType.CLICK_AUTO) {
        click(runtimeContext.auto.x, runtimeContext.auto.y);
        autoOn = !autoOn;
        sleep(runtimeContext.clickIntervalMs || 100);
        continue;
      }

      if (actionType === models.ActionType.TOGGLE_AUTO) {
        // 根据目标状态决定是否点击
        // action.value: "on" → 需要 AUTO 开, "off" → 需要 AUTO 关
        var targetOn = action.value === "on";
        if (autoOn !== targetOn) {
          click(runtimeContext.auto.x, runtimeContext.auto.y);
          autoOn = targetOn;
        }
        sleep(runtimeContext.clickIntervalMs || 100);
        continue;
      }

      if (actionType === models.ActionType.CLICK_ROLE) {
        var role = runtimeContext.roles[action.role];
        if (!role) {
          throw new Error("未知角色：" + action.role);
        }
        click(role.x, role.y);
        sleep(runtimeContext.clickIntervalMs || 100);
        continue;
      }

      if (actionType === "setRoles") {
        // 开关轴 SET：根据 values 数组点击每个角色的 SET 按钮
        // values: ["开","关","开","关","关"] → 角色5开, 角色4关, 角色3开...
        // 角色顺序：角色5, 角色4, 角色3, 角色2, 角色1
        var roleOrder = ["角色5", "角色4", "角色3", "角色2", "角色1"];
        for (var j = 0; j < action.values.length && j < roleOrder.length; j += 1) {
          var r = runtimeContext.roles[roleOrder[j]];
          if (!r) continue;
          // 无论开还是关，都点击一次（SET 按钮是切换开关）
          // 如果配置了 setY，使用 SET 按钮坐标；否则用角色头像坐标
          var setX = r.x;
          var setY = runtimeContext.setY || r.y;
          click(setX, setY);
          sleep(runtimeContext.clickIntervalMs || 100);
        }
        continue;
      }

      // 未知动作类型
      throw new Error("未知动作类型：" + actionType);
    }
  }

  /**
   * 获取当前 AUTO 状态
   */
  function isAutoOn() {
    return autoOn;
  }

  /**
   * 重置 AUTO 状态（战斗开始时 AUTO 默认关闭）
   */
  function reset() {
    autoOn = false;
  }

  return {
    run: run,
    isAutoOn: isAutoOn,
    reset: reset
  };
}

module.exports = {
  createExecutor: createExecutor
};
