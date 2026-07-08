/**
 * scheduler + recognition_filter 单元测试
 *
 * 运行：node test/test_scheduler.js
 */

var models = require("../src/models.js");
var schedulerModule = require("../src/scheduler.js");
var filterModule = require("../src/recognition_filter.js");

var passed = 0;
var failed = 0;

function assert(condition, msg) {
  if (condition) { passed++; }
  else { failed++; console.log("  FAIL: " + msg); }
}

// ---- 辅助：构建模拟轴事件 ----
function makeEvent(id, timeSeconds, actions) {
  return { id: id, sourceLine: 1, timeSeconds: timeSeconds, actions: actions || [] };
}

function makeGameState(state, clockSeconds, opts) {
  opts = opts || {};
  return {
    state: state,
    clockSeconds: clockSeconds,
    clockChanged: opts.clockChanged || false,
    energyDelta: opts.energyDelta || 0.01,
    energyFrozen: opts.energyFrozen || false,
    energyResumed: opts.energyResumed || false,
    frozenFrameCount: opts.frozenFrameCount || 0,
    anyUbTriggered: opts.anyUbTriggered || false,
    anyUbFinished: opts.anyUbFinished || false,
    triggeredCharacters: opts.triggeredCharacters || [],
    finishedCharacters: opts.finishedCharacters || [],
    characterStates: opts.characterStates || {}
  };
}

function run() {
  console.log("=== scheduler 测试 ===");

  // ---- 场景1：正常运行，按秒触发 ----
  var events1 = [
    makeEvent("e1", 76, [{ type: "clickRole" }]),  // 1:16
    makeEvent("e2", 72, [{ type: "clickAuto" }]),  // 1:12
    makeEvent("e3", 65, [{ type: "clickRole" }])   // 1:05
  ];
  var s1 = schedulerModule.createScheduler(events1);

  // 首次：只执行精确匹配
  var r1 = s1.update(makeGameState(models.GameState.RUNNING, 76));
  assert(r1.events.length === 1, "首次执行 1:16 事件");
  assert(r1.events[0].id === "e1", "触发 e1");
  assert(r1.reason === "scheduled", "reason=scheduled");

  // 同一秒不重复
  var r1b = s1.update(makeGameState(models.GameState.RUNNING, 76));
  assert(r1b.events.length === 0, "同秒不重复");
  assert(r1b.reason === "same-second", "reason=same-second");

  // 跳到 1:12 → 补执行中间的 e2
  var r2 = s1.update(makeGameState(models.GameState.RUNNING, 72));
  assert(r2.events.length === 1, "补执行 e2");
  assert(r2.events[0].id === "e2", "触发 e2");

  // 跳到 1:05 → 触发 e3
  var r3 = s1.update(makeGameState(models.GameState.RUNNING, 65));
  assert(r3.events.length === 1, "触发 e3");
  assert(r3.events[0].id === "e3", "触发 e3");

  // ---- 场景2：UB 动画冻结 → 解冻后补事件 ----
  var events2 = [
    makeEvent("ea", 60, [{ type: "clickRole" }]),  // 1:00
    makeEvent("eb", 58, [{ type: "clickRole" }]),  // 0:58
    makeEvent("ec", 55, [{ type: "notify" }])      // 0:55
  ];
  var s2 = schedulerModule.createScheduler(events2);

  // 正常到 1:00
  var r4 = s2.update(makeGameState(models.GameState.RUNNING, 60));
  assert(r4.events.length === 1, "触发 1:00");
  assert(r4.events[0].id === "ea", "触发 ea");

  // 1:00 时刻来了个角色 UB → 冻结
  var r5 = s2.update(makeGameState(models.GameState.CHARACTER_UB, 60, {
    anyUbTriggered: true, triggeredCharacters: ["角色3"]
  }));
  assert(r5.events.length === 0, "UB 动画中不调度");
  assert(r5.reason === "character-ub", "reason=character-ub");

  // UB 还在播，持续冻结
  var r6 = s2.update(makeGameState(models.GameState.UB_ANIMATION, 60, {
    energyFrozen: true, frozenFrameCount: 30
  }));
  assert(r6.events.length === 0, "持续冻结不调度");

  // UB 结束！时间跳到 0:54（跳过了 0:58 和 0:55）
  var r7 = s2.update(makeGameState(models.GameState.UB_JUST_ENDED, 54, {
    energyResumed: true
  }));
  assert(r7.events.length === 2, "解冻后补执行错过的事件");
  var ids = r7.events.map(function (e) { return e.id; });
  assert(ids.indexOf("eb") >= 0, "补 ea");
  assert(ids.indexOf("ec") >= 0, "补 eb");

  // ---- 场景3：Boss UB 长冻结，时间跳多秒 ----
  var events3 = [
    makeEvent("eA", 50, [{ type: "clickRole" }]),
    makeEvent("eB", 48, [{ type: "clickRole" }]),
    makeEvent("eC", 45, [{ type: "notify" }])
  ];
  var s3 = schedulerModule.createScheduler(events3);

  s3.update(makeGameState(models.GameState.RUNNING, 52));
  // Boss UB → 冻结，时间停在 50
  s3.update(makeGameState(models.GameState.UB_ANIMATION, 50, { energyFrozen: true }));
  // Boss UB 结束，时间到了 44（跳过了 50, 48, 45 三个事件）
  var r8 = s3.update(makeGameState(models.GameState.UB_JUST_ENDED, 44, { energyResumed: true }));
  assert(r8.events.length === 3, "Boss UB 后补执行全部 3 个错过事件");

  // ---- 场景4：时间增加（异常）→ 拒绝 ----
  var s4 = schedulerModule.createScheduler([makeEvent("ex", 70, [])]);
  s4.update(makeGameState(models.GameState.RUNNING, 70));
  var r9 = s4.update(makeGameState(models.GameState.RUNNING, 72)); // 时间增加了
  assert(r9.events.length === 0, "时间增加应拒绝");
  assert(r9.reason === "time-increased", "reason=time-increased");

  // ---- 场景5：clockSeconds 为 null → 等待 ----
  var s5 = schedulerModule.createScheduler([makeEvent("en", 60, [])]);
  var r10 = s5.update(makeGameState(models.GameState.RUNNING, null));
  assert(r10.events.length === 0, "无时钟读数时等待");
  assert(r10.reason === "no-clock-reading", "reason=no-clock-reading");

  console.log("调度器结果: " + passed + "/" + (passed + failed) + " 通过");
  var schedulerPassed = passed;
  var schedulerFailed = failed;

  // ==========================================
  console.log("");
  console.log("=== recognition_filter 测试 ===");

  passed = 0;
  failed = 0;

  var f = filterModule.createRecognitionFilter({
    minConfidence: 0.8,
    maxFailedReads: 5
  });

  // 首次有效值 → 接受
  var fr1 = f.update({ ok: true, timeSeconds: 76, confidence: 0.95 });
  assert(fr1.accepted === true, "首次接受");
  assert(fr1.timeSeconds === 76, "初始时间=76");

  // 同秒 → 拒绝但不增加失败计数
  var fr2 = f.update({ ok: true, timeSeconds: 76, confidence: 0.93 });
  assert(fr2.accepted === false, "同秒不接受");
  assert(fr2.reason === "same-time", "reason=same-time");

  // 正常下降 1 秒 → 接受
  var fr3 = f.update({ ok: true, timeSeconds: 75, confidence: 0.91 });
  assert(fr3.accepted === true, "正常下降 1 秒");
  assert(fr3.timeSeconds === 75, "时间=75");

  // 下降 3 秒（采样间隙）→ 接受
  var fr4 = f.update({ ok: true, timeSeconds: 72, confidence: 0.90 });
  assert(fr4.accepted === true, "下降 3 秒接受");

  // 下降 5 秒（大跳）→ 需要二次确认
  var fr5 = f.update({ ok: true, timeSeconds: 67, confidence: 0.88 });
  assert(fr5.accepted === false, "大跳需确认");
  assert(fr5.reason === "large-drop-needs-confirmation", "reason=large-drop");

  // 二次确认通过
  var fr6 = f.update({ ok: true, timeSeconds: 67, confidence: 0.89 });
  assert(fr6.accepted === true, "大跳二次确认通过");
  assert(fr6.timeSeconds === 67, "时间=67");

  // 时间增加 → 拒绝
  var fr7 = f.update({ ok: true, timeSeconds: 70, confidence: 0.92 });
  assert(fr7.accepted === false, "时间增加被拒绝");
  assert(fr7.reason === "time-increased", "reason=time-increased");

  // 低置信度 → 拒绝
  var fr8 = f.update({ ok: true, timeSeconds: 66, confidence: 0.5 });
  assert(fr8.accepted === false, "低置信度拒绝");

  // 识别失败 → 拒绝
  var fr9 = f.update({ ok: false, reason: "no-match" });
  assert(fr9.accepted === false, "识别失败拒绝");

  console.log("过滤器结果: " + passed + "/" + (passed + failed) + " 通过");

  console.log("");
  console.log("=== 总计 ===");
  var totalPassed = schedulerPassed + passed;
  var totalFailed = schedulerFailed + failed;
  console.log("通过: " + totalPassed + " / " + (totalPassed + totalFailed));
  if (totalFailed > 0) console.log("*** " + totalFailed + " 个测试失败 ***");
}

if (typeof module !== "undefined" && require.main === module) {
  run();
}

module.exports = { run: run };
