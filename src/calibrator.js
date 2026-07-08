"use strict";

/**
 * 坐标校准模块
 *
 * 玩家手动移动标记点到目标位置，逐个标定：
 *   角色头像 (5个) → AUTO 按钮 → 计时器区域 → 能量条
 *
 * 标记点是一个可移动的红色十字准星。
 * 能量条额外支持宽度调节（水平横杠）。
 */

var WORK_DIR = "/sdcard/Download/可可萝自动会战助手/";
var CONFIG_FILE = WORK_DIR + "可可萝自动会战助手.txt";

/**
 * 校准目标定义
 * 每个目标有 label（显示名）、key（存储键）、type（point/region/bar）
 */
var CALIBRATION_TARGETS = [
  { label: "5号位角色头像", key: "role5", type: "point" },
  { label: "4号位角色头像", key: "role4", type: "point" },
  { label: "3号位角色头像", key: "role3", type: "point" },
  { label: "2号位角色头像", key: "role2", type: "point" },
  { label: "1号位角色头像", key: "role1", type: "point" },
  { label: "角色头像 Y 坐标（建议对齐3号位）", key: "roleY", type: "point" },
  { label: "AUTO 按钮", key: "auto", type: "point" },
  { label: "黑屏监测点（可选，点跳过）", key: "blackScreen", type: "point", optional: true },
  { label: "计时器区域左上角", key: "timerTL", type: "point" },
  { label: "计时器区域右下角（宽度=右下X-左上X, 高度=右下Y-左上Y）", key: "timerBR", type: "point" },
  { label: "能量条区域（调整横杠覆盖蓝色条）", key: "energyBar", type: "bar" }
];

/**
 * 创建校准器
 */
function createCalibrator() {
  var currentIdx = 0;
  var results = {};
  var barWidth = 40;    // 能量条宽度（可调）
  var barHeight = 6;    // 能量条高度
  var markerX = Math.floor(device.width / 2);
  var markerY = Math.floor(device.height / 2);

  // ---- 校准悬浮窗 ----
  var calWindow = floaty.rawWindow(
    <frame gravity="center">
      <vertical bg="#cc000000" padding="8">
        <text id="title" text="校准" textSize="16sp" textColor="#ffffff" gravity="center"/>
        <text id="target" text="" textSize="14sp" textColor="#ffcc00" gravity="center"/>
        <text id="coords" text="" textSize="12sp" textColor="#aaaaaa" gravity="center"/>
        <horizontal gravity="center">
          <button id="up" text="↑" w="50dp" h="40dp"/>
        </horizontal>
        <horizontal gravity="center">
          <button id="left" text="←" w="50dp" h="40dp"/>
          <button id="down" text="↓" w="50dp" h="40dp"/>
          <button id="right" text="→" w="50dp" h="40dp"/>
        </horizontal>
        <horizontal gravity="center">
          <button id="stepLabel" text="步长:1" textSize="10sp" w="50dp" h="30dp"/>
          <button id="stepToggle" text="切换" w="50dp" h="30dp"/>
        </horizontal>
        <horizontal id="barRow" gravity="center">
          <button id="barDec" text="−宽" w="45dp" h="32dp"/>
          <text id="barInfo" text="宽:40" textSize="11sp" textColor="#88ccff" gravity="center"/>
          <button id="barInc" text="+宽" w="45dp" h="32dp"/>
        </horizontal>
        <horizontal gravity="center">
          <button id="confirm" text="确认" w="70dp" h="40dp"/>
          <button id="skip" text="跳过" w="70dp" h="40dp"/>
        </horizontal>
        <button id="save" text="保存配置文件" w="140dp" h="36dp"/>
      </vertical>
    </frame>
  );

  calWindow.setPosition(0, Math.floor(device.height * 0.1));
  calWindow.setSize(-2, -2);

  var stepSize = 1; // 1 或 10

  // ---- 绘制标记 ----
  var markerWindow = floaty.rawWindow(
    <frame>
      <canvas id="cv" w="auto" h="auto"/>
    </frame>
  );
  markerWindow.setPosition(0, 0);
  markerWindow.setSize(device.width, device.height);
  markerWindow.setTouchable(false);

  function drawMarker() {
    var canvas = markerWindow.cv;
    if (!canvas) return;
    var w = device.width;
    var h = device.height;
    // 清空画布
    canvas.drawColor(0x00000000, android.graphics.PorterDuff.Mode.CLEAR);

    var paint = new android.graphics.Paint();
    paint.setStyle(android.graphics.Paint.Style.STROKE);
    paint.setStrokeWidth(2);

    var target = CALIBRATION_TARGETS[currentIdx];
    var isBar = target && target.type === "bar";

    if (isBar) {
      // 画水平横杠（能量条）
      paint.setColor(0x88ff4444); // 半透明红
      paint.setStyle(android.graphics.Paint.Style.FILL);
      var barX = markerX - Math.floor(barWidth / 2);
      canvas.drawRect(barX, markerY - Math.floor(barHeight / 2),
                      barX + barWidth, markerY + Math.floor(barHeight / 2), paint);
    }

    // 画十字准星
    paint.setColor(isBar ? 0xffff0000 : 0xffff4444); // 红色
    paint.setStrokeWidth(isBar ? 3 : 2);
    paint.setStyle(android.graphics.Paint.Style.STROKE);
    var crossSize = 15;
    canvas.drawLine(markerX - crossSize, markerY, markerX + crossSize, markerY, paint);
    canvas.drawLine(markerX, markerY - crossSize, markerX, markerY + crossSize, paint);
    // 圆圈
    canvas.drawCircle(markerX, markerY, 6, paint);
  }

  // ---- UI 更新 ----
  function updateUI() {
    var target = CALIBRATION_TARGETS[currentIdx];
    var idxDisplay = (currentIdx + 1) + "/" + CALIBRATION_TARGETS.length;
    calWindow.target.setText(idxDisplay + " " + target.label);
    calWindow.coords.setText("X=" + markerX + " Y=" + markerY +
      (target.type === "bar" ? " 宽=" + barWidth : ""));
    calWindow.stepLabel.setText("步长:" + stepSize);

    // 只在 bar 类型时显示宽度调节
    calWindow.barRow.setVisibility(target.type === "bar" ? 0 : 8); // VISIBLE/GONE
    calWindow.skip.setVisibility(target.optional ? 0 : 8);

    drawMarker();
  }

  function moveMarker(dx, dy) {
    markerX = Math.max(0, Math.min(device.width - 1, markerX + dx));
    markerY = Math.max(0, Math.min(device.height - 1, markerY + dy));
    updateUI();
  }

  function adjustBarWidth(delta) {
    barWidth = Math.max(10, Math.min(200, barWidth + delta));
    updateUI();
  }

  function confirmCurrent() {
    var target = CALIBRATION_TARGETS[currentIdx];
    if (target.type === "bar") {
      results[target.key] = { x: markerX, y: markerY, width: barWidth, height: barHeight };
    } else {
      results[target.key] = { x: markerX, y: markerY };
    }
    advance();
  }

  function skipCurrent() {
    advance();
  }

  function advance() {
    if (currentIdx < CALIBRATION_TARGETS.length - 1) {
      currentIdx++;
      updateUI();
    } else {
      toast("校准完成，请点击 [保存配置文件]");
    }
  }

  function saveConfig() {
    try {
      // 收集结果
      var r = results;
      // 角色坐标
      var role5X = (r.role5 && r.role5.x) || 0;
      var role4X = (r.role4 && r.role4.x) || 0;
      var role3X = (r.role3 && r.role3.x) || 0;
      var role2X = (r.role2 && r.role2.x) || 0;
      var role1X = (r.role1 && r.role1.x) || 0;
      var roleY = (r.roleY && r.roleY.y) || 0;
      var autoX = (r.auto && r.auto.x) || 0;
      var autoY = (r.auto && r.auto.y) || 0;
      var blackX = (r.blackScreen && r.blackScreen.x) || 0;
      var blackY = (r.blackScreen && r.blackScreen.y) || 0;

      // 计时器区域
      var timerTL = r.timerTL || { x: 0, y: 0 };
      var timerBR = r.timerBR || { x: 0, y: 0 };
      var timerX = timerTL.x;
      var timerY = timerTL.y;
      var timerW = Math.max(0, timerBR.x - timerTL.x);
      var timerH = Math.max(0, timerBR.y - timerTL.y);

      // 写入配置文件（14 行整数）
      var lines = [
        String(role5X),
        String(role4X),
        String(role3X),
        String(role2X),
        String(role1X),
        String(roleY),
        String(autoX),
        String(autoY),
        String(blackX),
        String(blackY),
        String(timerX),
        String(timerY),
        String(timerW),
        String(timerH)
      ];

      files.ensureDir(WORK_DIR);
      files.write(CONFIG_FILE, lines.join("\n"));

      toast("配置已保存到: " + CONFIG_FILE);

      // 额外保存能量条校准
      if (r.energyBar) {
        var ebPath = WORK_DIR + "energy_calibration.json";
        files.write(ebPath, JSON.stringify(r.energyBar));
        toast("能量条校准已保存: " + ebPath);
      }

    } catch (e) {
      toast("保存失败: " + e.message);
    }
  }

  // ---- 按钮事件 ----
  calWindow.up.click(function () { moveMarker(0, -stepSize); });
  calWindow.down.click(function () { moveMarker(0, stepSize); });
  calWindow.left.click(function () { moveMarker(-stepSize, 0); });
  calWindow.right.click(function () { moveMarker(stepSize, 0); });
  calWindow.stepToggle.click(function () {
    stepSize = stepSize === 1 ? 10 : 1;
    updateUI();
  });
  calWindow.barDec.click(function () { adjustBarWidth(-5); });
  calWindow.barInc.click(function () { adjustBarWidth(5); });
  calWindow.confirm.click(function () { confirmCurrent(); });
  calWindow.skip.click(function () { skipCurrent(); });
  calWindow.save.click(function () { saveConfig(); });

  // 初始化显示
  updateUI();

  return {
    close: function () {
      calWindow.close();
      markerWindow.close();
    }
  };
}

module.exports = {
  createCalibrator: createCalibrator
};
