/**
 * 真机诊断脚本
 *
 * 在 AutoJS6 中运行此脚本，检查各项功能是否就绪。
 * 运行后会输出诊断报告。
 */

"use strict";

var WORK_DIR = "/sdcard/Download/可可萝自动会战助手/";
var CONFIG_FILE = WORK_DIR + "可可萝自动会战助手.txt";
var TEMPLATE_DIR = WORK_DIR + "templates";

// ---- 模拟 console.log（AutoJS 用 toast/log 输出） ----
function log(msg) {
  console.log(msg);
  toast(msg);
  sleep(800);
}

// ---- 检查项 ----
function check(name, condition, detail) {
  if (condition) {
    log("[OK] " + name);
    return true;
  } else {
    log("[FAIL] " + name + (detail ? " — " + detail : ""));
    return false;
  }
}

// ---- 主诊断 ----
log("=== 可可萝助手 真机诊断 ===");
sleep(1500);

var allOk = true;

// 1. 工作目录
allOk &= check("工作目录存在", files.exists(WORK_DIR),
  "请手动创建 " + WORK_DIR);

// 2. 配置文件
allOk &= check("配置文件存在", files.exists(CONFIG_FILE),
  "请运行校准或手动创建 " + CONFIG_FILE);

if (files.exists(CONFIG_FILE)) {
  try {
    var cfgText = files.read(CONFIG_FILE);
    var lines = cfgText.split("\n");
    check("配置文件 14 行", lines.length >= 14,
      "当前 " + lines.length + " 行，需要 14 行");

    // 检查计时器区域是否为 0
    var timerX = parseInt(lines[10]) || 0;
    var timerY = parseInt(lines[11]) || 0;
    var timerW = parseInt(lines[12]) || 0;
    var timerH = parseInt(lines[13]) || 0;
    allOk &= check("计时器区域已配置",
      timerW > 0 && timerH > 0,
      "计时器区域为 0，请运行校准");
  } catch (e) {
    log("[FAIL] 配置文件读取失败: " + e.message);
    allOk = false;
  }
}

// 3. 时钟模板
allOk &= check("模板目录存在", files.exists(TEMPLATE_DIR),
  "请创建 " + TEMPLATE_DIR + " 并放入 0-9.png + colon.png");

if (files.exists(TEMPLATE_DIR)) {
  var required = ["0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "colon"];
  var missing = [];
  for (var i = 0; i < required.length; i++) {
    var name = required[i];
    if (!files.exists(TEMPLATE_DIR + "/" + name + ".png") &&
        !files.exists(TEMPLATE_DIR + "/" + name + ".jpg")) {
      missing.push(name);
    }
  }
  allOk &= check("时钟模板完整", missing.length === 0,
    "缺失: " + missing.join(", "));
}

// 4. 源文件
var srcDir = "/sdcard/脚本/可可萝自动会战助手/src/";
if (files.exists(srcDir)) {
  var modules = ["models", "config_parser", "axis_parser", "clock_recognizer",
                 "recognition_filter", "energy_detector", "game_state_detector",
                 "scheduler", "executor", "ui", "calibrator", "main"];
  var missingSrc = [];
  for (var j = 0; j < modules.length; j++) {
    if (!files.exists(srcDir + modules[j] + ".js")) {
      missingSrc.push(modules[j]);
    }
  }
  allOk &= check("源文件完整", missingSrc.length === 0,
    "缺失: " + missingSrc.join(", "));
} else {
  log("[WARN] 源文件目录未找到，请确保已部署到手机");
}

// 5. 截图权限
try {
  var testCapture = images.captureScreen();
  if (testCapture) {
    log("[OK] 截图权限已授予 (" + testCapture.getWidth() + "x" + testCapture.getHeight() + ")");
    testCapture.recycle();
  } else {
    log("[FAIL] 截图失败，请检查无障碍和悬浮窗权限");
    allOk = false;
  }
} catch (e) {
  log("[FAIL] 截图异常: " + e.message);
  allOk = false;
}

// 6. AutoJS 版本
try {
  log("[INFO] AutoJS 版本: " + (typeof autojs !== "undefined" ? autojs.version : "unknown"));
  log("[INFO] 设备分辨率: " + device.width + "x" + device.height);
} catch (e) {
  // ignore
}

// ---- 总结 ----
sleep(1000);
log("");
if (allOk) {
  log("=== 全部检查通过，可以启动助手 ===");
} else {
  log("=== 存在未就绪项，请先完成以上 [FAIL] 项 ===");
}
