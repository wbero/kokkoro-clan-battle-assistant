"use strict";

var SRC = files.path(engines.myEngine().cwd() + "/src");
var models = require(SRC + "/models.js");

/**
 * 创建调度器
 *
 * 调度规则：
 *  - 首次接受时间时，只执行精确匹配的当前秒事件
 *  - 后续接受时间时，执行 (上次时间, 本次时间] 区间内所有未执行事件
 *  - UB_ANIMATION / CHARACTER_UB 状态下不调度事件
 *  - UB_JUST_ENDED 时检查有没有因为冻结错过的事件，补执行
 *  - 同一秒的事件按文件顺序执行
 *
 * @param {Array} events - 解析后的轴事件数组
 */
function createScheduler(events) {
  var executed = {};
  var previous = null;
  // 记录上次冻结时的时间，用于解冻后判断错过的事件
  var frozenClock = null;

  /**
   * 返回严格匹配当前秒的未执行事件
   */
  function dueAtCurrent(current) {
    return events.filter(function (event) {
      return !executed[event.id] && event.timeSeconds === current;
    });
  }

  /**
   * 返回 (previousTime, currentTime] 区间内所有未执行事件
   * 时间递减：previousTime > currentTime
   */
  function dueBetween(previousTime, currentTime) {
    return events.filter(function (event) {
      return !executed[event.id] &&
        event.timeSeconds <= previousTime &&
        event.timeSeconds >= currentTime;
    });
  }

  /**
   * 主调度入口
   *
   * @param {Object} gameState - 来自 game_state_detector.detect() 的结果
   * @returns {Object} { events: Array, state: string }
   *   events: 本次应执行的事件列表
   *   state: 调度决策说明
   */
  function update(gameState) {
    var gs = gameState.state;
    var currentClock = gameState.clockSeconds;

    // UB 动画播放中 → 不调度，记录当前时间供后续判断
    if (gs === models.GameState.UB_ANIMATION) {
      if (currentClock !== null && frozenClock === null) {
        frozenClock = currentClock;
      }
      return { events: [], reason: "ub-animation-frozen" };
    }

    // 角色 UB 刚释放 → 不调度轴事件（UB 动画还在播）
    if (gs === models.GameState.CHARACTER_UB) {
      if (currentClock !== null && frozenClock === null) {
        frozenClock = currentClock;
      }
      return {
        events: [],
        reason: "character-ub",
        triggeredCharacters: gameState.triggeredCharacters
      };
    }

    // 时间还没识别出来 → 等待
    if (currentClock === null) {
      return { events: [], reason: "no-clock-reading" };
    }

    // UB 刚结束（能量恢复了，时间可能还没跳）
    // 此时检查冻结期间是否错过了事件
    if (gs === models.GameState.UB_JUST_ENDED) {
      var thawResult = _handleThaw(currentClock);
      return thawResult;
    }

    // 正常运行状态
    return _handleRunning(currentClock);
  }

  /**
   * 解冻处理：能量刚恢复增长，检查错过的事件
   */
  function _handleThaw(currentClock) {
    var missedEvents = [];

    if (frozenClock !== null && frozenClock > currentClock) {
      // 冻结期间时间跳了（比如 Boss UB 很长，跳了好几秒）
      missedEvents = dueBetween(frozenClock, currentClock);
    } else if (frozenClock !== null && frozenClock === currentClock) {
      // 时间没跳但能量恢复了 → 检查当前秒有没有未执行的事件
      missedEvents = dueAtCurrent(currentClock);
    }

    frozenClock = null;

    missedEvents.forEach(function (event) {
      executed[event.id] = true;
    });

    // 更新 previous，补齐调度状态
    previous = currentClock;

    return {
      events: missedEvents,
      reason: missedEvents.length > 0 ? "thaw-caught-up" : "thaw-no-missed"
    };
  }

  /**
   * 正常运行状态调度
   */
  function _handleRunning(currentClock) {
    var due;

    if (previous === null) {
      // 首次：只执行精确匹配当前秒的事件
      due = dueAtCurrent(currentClock);
    } else if (previous === currentClock) {
      // 同一秒内 → 不重复触发（但能量可能在涨，属于即将跳秒的正常状态）
      return { events: [], reason: "same-second" };
    } else if (previous > currentClock) {
      // 正常倒计时
      due = dueBetween(previous, currentClock);
    } else {
      // currentClock > previous → 时间增加了（异常），拒绝
      return { events: [], reason: "time-increased" };
    }

    previous = currentClock;
    frozenClock = null;

    due.forEach(function (event) {
      executed[event.id] = true;
    });

    return { events: due, reason: "scheduled" };
  }

  /**
   * 强制重置调度状态（重新开始战斗时调用）
   */
  function reset() {
    executed = {};
    previous = null;
    frozenClock = null;
  }

  return {
    update: update,
    reset: reset
  };
}

module.exports = {
  createScheduler: createScheduler
};
