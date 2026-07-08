"use strict";

auto.waitFor();

// AutoJS6 require 需要绝对路径
var SRC = files.path(engines.myEngine().cwd());

var models = require(SRC + "/models.js");
var configParser = require(SRC + "/config_parser.js");
var axisParser = require(SRC + "/axis_parser.js");
var clockRecognizer = require(SRC + "/clock_recognizer.js");
var recognitionFilter = require(SRC + "/recognition_filter.js");
var energyDetector = require(SRC + "/energy_detector.js");
var gameStateDetector = require(SRC + "/game_state_detector.js");
var schedulerModule = require(SRC + "/scheduler.js");
var executorModule = require(SRC + "/executor.js");
var uiModule = require(SRC + "/ui.js");
var calibratorModule = require(SRC + "/calibrator.js");

// ---- 常量 ----
var WORK_DIR = "/sdcard/Download/可可萝自动会战助手/";
var CONFIG_FILE = WORK_DIR + "可可萝自动会战助手.txt";
var TEMPLATE_DIR = WORK_DIR + "templates";
var LOOP_INTERVAL_MS = 200; // 5fps，模拟器内存有限

// ---- 悬浮窗 ----
var window = uiModule.createFloatingWindow();
window.setPosition(Math.floor(device.width * 0.1), Math.floor(device.height * 0.05));

// ---- 运行时状态 ----
var running = false;
var paused = false;
var config = null;
var axisData = null;
var scheduler = null;
var executor = null;
var filter = null;
var gsDetector = null;
var energyDet = null;
var loopTimer = null;
var digitTemplates = null;  // 时钟数字模板

// ---- 配置文件加载 ----
function loadConfig() {
  if (!files.exists(CONFIG_FILE)) {
    toast("配置文件不存在: " + CONFIG_FILE);
    return null;
  }
  try {
    var text = files.read(CONFIG_FILE);
    return configParser.parseConfigText(text);
  } catch (e) {
    toast("配置解析失败: " + e.message);
    return null;
  }
}

// ---- 轴文件加载 ----
function loadAxis(filePath) {
  try {
    var text = files.read(filePath);
    var axis = axisParser.parseAxisText(text);
    return axis;
  } catch (e) {
    toast("轴解析失败: " + e.message);
    return null;
  }
}

// ---- 从配置构建能量条和 UB 图标 ROI 区域 ----
// ROI 尺寸参考 MaaPcrclanbattle: 能量条 40×5, UB 图标 25×20

function buildEnergyRegions(cfg) {
  // 能量条：角色头像正下方，紧贴 UB 图标下面
  // MaaPcrclanbattle 用 40×5 像素，我们稍宽松一些
  var barWidth = 40;
  var barHeight = 6;
  // 头像中心 Y → 能量条顶部：头像半高 + 间距 + UB 图标高度
  // 需要真机校准，此处为预估值
  var headHalfHeight = 30;   // 头像半高（60px 直径）
  var ubIconHeight = 22;     // UB 图标高度
  var gap = 6;               // 图标到能量条间距
  var energyY = cfg.roleY + headHalfHeight + ubIconHeight + gap;

  var regions = {};
  var roleOrder = ["角色5", "角色4", "角色3", "角色2", "角色1"];
  for (var i = 0; i < roleOrder.length; i += 1) {
    var name = roleOrder[i];
    regions[name] = {
      x: cfg.roleX[name] - Math.floor(barWidth / 2),
      y: energyY,
      width: barWidth,
      height: barHeight
    };
  }
  return regions;
}

function buildUbIconRegions(cfg) {
  // UB 图标位于头像正下方、能量条正上方
  // MaaPcrclanbattle: roi [ubflag, 687, 25, 20]，能量条在 [ubflag, 695, 40, 5]
  // 图标和能量条之间间隔约 8px
  var iconWidth = 25;
  var iconHeight = 20;
  var headHalfHeight = 30;
  var gap = 2;
  var iconY = cfg.roleY + headHalfHeight + gap;

  var regions = {};
  var roleOrder = ["角色5", "角色4", "角色3", "角色2", "角色1"];
  for (var i = 0; i < roleOrder.length; i += 1) {
    var name = roleOrder[i];
    regions[name] = {
      x: cfg.roleX[name] - Math.floor(iconWidth / 2),
      y: iconY,
      width: iconWidth,
      height: iconHeight
    };
  }
  return regions;
}

// ---- 构建执行器运行时上下文 ----
function buildRuntimeContext(cfg, axis) {
  var roles = {};
  var roleOrder = ["角色5", "角色4", "角色3", "角色2", "角色1"];

  for (var i = 0; i < roleOrder.length; i += 1) {
    var name = roleOrder[i];
    roles[name] = {
      x: cfg.roleX[name],
      y: cfg.roleY,
      alias: (axis.header && axis.header[name]) ? axis.header[name] : name
    };
  }

  return {
    roles: roles,
    auto: cfg.auto,
    clickIntervalMs: axis.clickIntervalMs || 100,
    setY: cfg.roleY + 65  // SET 按钮在头像下方约 65px（需真机校准）
  };
}

// ---- 主循环 ----
function mainLoop() {
  if (!running || paused) return;

  try {
    // 1. 截图
    var screenCapture = images.captureScreen();
    if (!screenCapture) {
      uiSetWarning("截图失败");
      return;
    }

    // 2. 游戏状态融合检测（时钟 + 能量条 + UB 图标）
    var stateResult = gsDetector.detect(screenCapture, config.timerRegion, digitTemplates);

    // 3. 时钟识别结果过一遍 filter（过滤低置信度/异常值）
    var filterInput = {
      ok: stateResult.clockSeconds !== null,
      timeSeconds: stateResult.clockSeconds,
      confidence: stateResult.clockSeconds !== null ? 0.9 : 0
    };
    var filtered = filter.update(filterInput);

    // 4. 如果 filter 接受了时间更新，传给调度器
    if (filtered.accepted) {
      stateResult.clockSeconds = filtered.timeSeconds;
    }

    // 4b. 连续识别失败 → 自动暂停
    if (filtered.shouldPause) {
      uiSetWarning("连续识别失败，已暂停");
      paused = true;
      window.pause.setText("继续");
      screenCapture.recycle();
      return;
    }
    // 注意：即使 filter 没有接受（same-time / low-confidence），
    // game_state_detector 的能量条数据仍然有效，调度器靠它判断解冻

    // 5. 调度器决策
    var scheduleResult = scheduler.update(stateResult);
    var dueEvents = scheduleResult.events;

    // 6. 执行动作
    if (dueEvents.length > 0) {
      dueEvents.forEach(function (event) {
        executor.run(event.actions);
      });
    }

    // 7. 更新悬浮窗
    uiUpdate(stateResult, scheduleResult);

    // 8. 释放截图
    screenCapture.recycle();

  } catch (e) {
    uiSetWarning("运行异常: " + e.message);
  }
}

// ---- UI 更新 ----
function uiSetTime(timeStr) {
  try {
    window.time.setText("时间: " + (timeStr || "--:--"));
  } catch (_) {}
}

function uiSetFile(name) {
  try {
    window.file.setText("轴: " + (name || "未选择"));
  } catch (_) {}
}

function uiSetWarning(msg) {
  try {
    window.warning.setText(msg || "");
  } catch (_) {}
}

function uiUpdate(stateResult, scheduleResult) {
  var secs = stateResult.clockSeconds;
  if (secs !== null) {
    var m = Math.floor(secs / 60);
    var s = secs % 60;
    uiSetTime(m + ":" + (s < 10 ? "0" : "") + s);
  }

  var stateLabels = {
    "running": "",
    "ub-animation": "UB动画中",
    "ub-just-ended": "UB结束",
    "character-ub": "角色UB"
  };
  var label = stateLabels[stateResult.state] || "";
  uiSetWarning(label);
}

// ---- 启动自动化 ----
function startAutomation(axisFilePath) {
  config = loadConfig();
  if (!config) return false;

  axisData = loadAxis(axisFilePath);
  if (!axisData) return false;

  // 加载时钟数字模板
  digitTemplates = clockRecognizer.loadTemplates(TEMPLATE_DIR);
  if (!digitTemplates) {
    toast("警告：时钟模板未找到，请先校准。\n模板路径: " + TEMPLATE_DIR);
    // 不阻止启动，但时钟识别会返回 not-implemented
  }

  // 初始化能量检测器（含 UB 图标双层验证）
  var energyRegions = buildEnergyRegions(config);
  var iconRegions = buildUbIconRegions(config);
  energyDet = energyDetector.createEnergyDetector({
    energyRegions: energyRegions,
    ubIconRegions: iconRegions,
    ubThreshold: 0.85,
    iconBrightThreshold: 0.7,
    sampleStep: 2,
    deltaThreshold: 0.02,
    iconDropThreshold: 0.4
  });

  // 初始化状态融合检测器
  gsDetector = gameStateDetector.createGameStateDetector(clockRecognizer, energyDet);

  // 初始化识别过滤器
  filter = recognitionFilter.createRecognitionFilter({
    minConfidence: 0.8,
    maxFailedReads: 8
  });

  // 初始化调度器
  scheduler = schedulerModule.createScheduler(axisData.events);

  // 初始化执行器
  var ctx = buildRuntimeContext(config, axisData);
  executor = executorModule.createExecutor(ctx);

  uiSetFile(files.getName(axisFilePath));
  uiSetWarning("");

  running = true;
  paused = false;

  // 每 50ms 循环一次（20fps，匹配高刷屏）
  loopTimer = setInterval(mainLoop, LOOP_INTERVAL_MS);

  toast("自动化已启动");
  return true;
}

// ---- 轴文件选择 ----
function selectAxisFile() {
  // 列出工作目录中的 .txt 文件（排除配置文件）
  if (!files.exists(WORK_DIR)) {
    files.ensureDir(WORK_DIR);
    toast("工作目录已创建，请放入轴文件: " + WORK_DIR);
    return null;
  }
  var allFiles = files.listDir(WORK_DIR);
  var axisFiles = [];
  for (var i = 0; i < allFiles.length; i += 1) {
    var name = allFiles[i];
    // 过滤：只要 .txt 文件，排除配置文件
    if (name.endsWith(".txt") && name !== "可可萝自动会战助手.txt") {
      axisFiles.push(name);
    }
  }
  if (axisFiles.length === 0) {
    toast("未找到轴文件，请放入: " + WORK_DIR);
    return null;
  }
  // 弹出选择对话框
  var idx = dialogs.select("选择轴文件", axisFiles);
  if (idx < 0) return null;
  return WORK_DIR + axisFiles[idx];
}

// ---- 停止自动化 ----
function stopAutomation() {
  running = false;
  paused = false;
  if (loopTimer) {
    clearInterval(loopTimer);
    loopTimer = null;
  }
  if (energyDet) energyDet.reset();
  if (gsDetector) gsDetector.reset();
  if (scheduler) scheduler.reset();
  if (executor) executor.reset();
  uiSetWarning("已停止");
  toast("自动化已停止");
}

// ---- 悬浮窗按钮事件 ----
window.start.click(function () {
  if (running) {
    toast("已在运行中");
    return;
  }
  var axisPath = selectAxisFile();
  if (!axisPath) return;
  startAutomation(axisPath);
});

window.pause.click(function () {
  if (!running) return;
  paused = !paused;
  window.pause.setText(paused ? "继续" : "暂停");
  toast(paused ? "已暂停" : "已恢复");
});

window.stop.click(function () {
  if (!running) {
    toast("未在运行");
    return;
  }
  stopAutomation();
  window.pause.setText("暂停");
});

window.openFile.click(function () {
  var axisPath = selectAxisFile();
  if (axisPath) {
    startAutomation(axisPath);
  }
});

window.calibrate.click(function () {
  if (running) {
    toast("请先停止自动化再校准");
    return;
  }
  var cal = calibratorModule.createCalibrator();
  // 校准窗口独立运行，关闭按钮会退出
});

window.exitBtn.click(function () {
  stopAutomation();
  window.close();
  toast("助手已关闭");
});

// ---- 保活 ----
setInterval(function () {}, 1000);
