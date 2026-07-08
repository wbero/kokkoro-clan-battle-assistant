/**
 * config_parser 单元测试
 *
 * 运行方式（在项目根目录）：
 *   node test/test_config_parser.js
 *
 * 或在 AutoJS 中：
 *   var t = require("./test/test_config_parser.js"); t.run();
 */

var parser = require("../src/config_parser.js");

var passed = 0;
var failed = 0;

function assert(condition, msg) {
  if (condition) {
    passed++;
  } else {
    failed++;
    console.log("  FAIL: " + msg);
  }
}

function assertThrows(fn, msg) {
  try {
    fn();
    failed++;
    console.log("  FAIL (expected throw): " + msg);
  } catch (e) {
    passed++;
  }
}

function run() {
  console.log("=== config_parser 测试 ===");

  // ---- 正常解析 ----
  var validInput = [
    "482",   // 5号位 X
    "716",   // 4号位 X
    "950",   // 3号位 X
    "1184",  // 2号位 X
    "1418",  // 1号位 X
    "845",   // 角色 Y
    "1828",  // AUTO X
    "845",   // AUTO Y
    "0",     // 黑屏 X
    "0",     // 黑屏 Y
    "1075",  // 计时器 X
    "20",    // 计时器 Y
    "48",    // 计时器 W
    "27"     // 计时器 H
  ].join("\n");

  var cfg = parser.parseConfigText(validInput);
  assert(cfg.roleX["角色5"] === 482, "角色5 X = 482");
  assert(cfg.roleX["角色3"] === 950, "角色3 X = 950");
  assert(cfg.roleX["角色1"] === 1418, "角色1 X = 1418");
  assert(cfg.roleY === 845, "角色 Y = 845");
  assert(cfg.auto.x === 1828, "AUTO X = 1828");
  assert(cfg.auto.y === 845, "AUTO Y = 845");
  assert(cfg.timerRegion.x === 1075, "计时器 X = 1075");
  assert(cfg.timerRegion.y === 20, "计时器 Y = 20");
  assert(cfg.timerRegion.width === 48, "计时器 W = 48");
  assert(cfg.timerRegion.height === 27, "计时器 H = 27");
  assert(cfg.blackScreenPoint.x === 0, "黑屏 X = 0");

  // ---- 行数不足 ----
  assertThrows(function () {
    parser.parseConfigText("482\n716\n950");
  }, "行数不足应抛异常");

  // ---- 空行 ----
  assertThrows(function () {
    var lines = [];
    for (var i = 0; i < 14; i++) lines.push("");
    parser.parseConfigText(lines.join("\n"));
  }, "空行应抛异常");

  // ---- 非整数 ----
  assertThrows(function () {
    var lines = [];
    for (var i = 0; i < 14; i++) lines.push("abc");
    parser.parseConfigText(lines.join("\n"));
  }, "非整数应抛异常");

  // ---- CRLF 换行 ----
  var crlf = "100\r\n200\r\n300\r\n400\r\n500\r\n600\r\n700\r\n800\r\n0\r\n0\r\n50\r\n10\r\n40\r\n20";
  var cfg2 = parser.parseConfigText(crlf);
  assert(cfg2.roleX["角色5"] === 100, "CRLF: 角色5 = 100");
  assert(cfg2.timerRegion.width === 40, "CRLF: 计时器宽 = 40");

  console.log("结果: " + passed + "/" + (passed + failed) + " 通过");
  if (failed > 0) {
    console.log("*** " + failed + " 个测试失败 ***");
  }
}

if (typeof module !== "undefined" && require.main === module) {
  run();
}

module.exports = { run: run };
