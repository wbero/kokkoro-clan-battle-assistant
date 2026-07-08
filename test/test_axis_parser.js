/**
 * axis_parser 单元测试
 *
 * 运行：node test/test_axis_parser.js
 */

var parser = require("../src/axis_parser.js");
var models = require("../src/models.js");

var passed = 0;
var failed = 0;

function assert(condition, msg) {
  if (condition) { passed++; }
  else { failed++; console.log("  FAIL: " + msg); }
}

function assertThrows(fn, msg) {
  try { fn(); failed++; console.log("  FAIL (expected throw): " + msg); }
  catch (e) { passed++; }
}

function run() {
  console.log("=== axis_parser 测试 ===");

  // ---- 解析顺序轴 ----
  var seqInput = [
    "轴类型=顺序",
    "点击间隔=100",
    "角色5=sms",
    "角色4=水驴",
    "角色3=原晶",
    "角色2=水花",
    "角色1=水田",
    "自动=AUTO",
    "BOSS=BOSS",
    "",
    "[轴]",
    "1:16 | 点击=角色3",
    "1:12 | 点击=AUTO",
    "1:05 | 点击=角色5,角色1",
    "0:58 | 提示=狐狸满血后开",
    "0:45 | 点击=BOSS"
  ].join("\n");

  var axis = parser.parseAxisText(seqInput);
  assert(axis.type === models.AxisType.SEQUENCE, "轴类型=顺序");
  assert(axis.clickIntervalMs === 100, "点击间隔=100");
  assert(axis.events.length === 5, "5 个轴事件");

  // 事件 0: 1:16 点击角色3
  assert(axis.events[0].timeSeconds === 76, "1:16 → 76秒");  // 1×60+16
  assert(axis.events[0].actions[0].type === "clickRole", "动作=clickRole");
  assert(axis.events[0].actions[0].role === "角色3", "角色=角色3");

  // 事件 1: 1:12 点击AUTO
  assert(axis.events[1].timeSeconds === 72, "1:12 → 72秒");
  assert(axis.events[1].actions[0].type === "clickAuto", "动作=clickAuto");

  // 事件 2: 1:05 点击角色5,角色1 (两个点击)
  assert(axis.events[2].timeSeconds === 65, "1:05 → 65秒");
  assert(axis.events[2].actions.length === 2, "两个动作");
  assert(axis.events[2].actions[0].role === "角色5", "第一个点角色5");
  assert(axis.events[2].actions[1].role === "角色1", "第二个点角色1");

  // 事件 3: 提示
  assert(axis.events[3].actions[0].type === "notify", "提示=notify");
  assert(axis.events[3].actions[0].message === "狐狸满血后开", "提示内容");

  // 事件 4: 点击BOSS
  assert(axis.events[4].actions[0].type === "boss", "BOSS事件");

  // ---- 解析开关轴 ----
  var swInput = [
    "轴类型=开关",
    "点击间隔=100",
    "角色顺序=角色5,角色4,角色3,角色2,角色1",
    "自动=AUTO",
    "",
    "[轴]",
    "1:16 | SET=关,关,开,关,关 | AUTO=关 | 提示=开角色3",
    "1:12 | SET=关,关,关,关,关 | AUTO=开",
    "0:58 | SET=开,关,开,关,关 | AUTO=关 | 提示=狐狸目押"
  ].join("\n");

  var swAxis = parser.parseAxisText(swInput);
  assert(swAxis.type === models.AxisType.SWITCH, "轴类型=开关");
  assert(swAxis.events.length === 3, "3 个事件");

  // 事件 0: SET + AUTO关 + 提示
  assert(swAxis.events[0].timeSeconds === 76, "开关 1:16 → 76秒");
  var setAction = swAxis.events[0].actions.find(function (a) { return a.type === "setRoles"; });
  assert(setAction !== undefined, "有 setRoles 动作");
  assert(setAction.values[0] === "关", "角色5=关");
  assert(setAction.values[2] === "开", "角色3=开");
  var toggleAuto = swAxis.events[0].actions.find(function (a) { return a.type === "toggleAuto"; });
  assert(toggleAuto !== undefined, "有 toggleAuto");
  assert(toggleAuto.value === "off", "AUTO=关");

  // ---- parseTimeToSeconds ----
  assert(parser.parseTimeToSeconds("0:00") === 0, "0:00=0");
  assert(parser.parseTimeToSeconds("0:01") === 1, "0:01=1");
  assert(parser.parseTimeToSeconds("1:30") === 90, "1:30=90");
  assert(parser.parseTimeToSeconds("0:59") === 59, "0:59=59");
  assertThrows(function () { parser.parseTimeToSeconds("abc"); }, "非法格式");
  assertThrows(function () { parser.parseTimeToSeconds("1:60"); }, "秒数超范围");
  assertThrows(function () { parser.parseTimeToSeconds(""); }, "空字符串");

  // ---- 注释行 ----
  var commentInput = [
    "轴类型=顺序",
    "# 这是注释",
    "点击间隔=50",
    "[轴]",
    "# 这也是注释",
    "1:00 | 点击=角色1"
  ].join("\n");
  var commentAxis = parser.parseAxisText(commentInput);
  assert(commentAxis.clickIntervalMs === 50, "注释行不影响解析");
  assert(commentAxis.events.length === 1, "注释不计为事件");

  console.log("结果: " + passed + "/" + (passed + failed) + " 通过");
  if (failed > 0) console.log("*** " + failed + " 个测试失败 ***");
}

if (typeof module !== "undefined" && require.main === module) {
  run();
}

module.exports = { run: run };
