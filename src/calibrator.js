"use strict";

var WORK_DIR = "/sdcard/Download/可可萝自动会战助手/";
var CONFIG_FILE = WORK_DIR + "可可萝自动会战助手.txt";

var TARGETS = [
  { label: "5号位角色头像中心" },
  { label: "4号位角色头像中心" },
  { label: "3号位角色头像中心" },
  { label: "2号位角色头像中心" },
  { label: "1号位角色头像中心" },
  { label: "AUTO 按钮中心" },
  { label: "黑屏监测点(可跳过)" },
  { label: "计时器左上角" },
  { label: "计时器右下角" }
];

function createCalibrator() {
  var idx = 0;
  var saved = [];
  var step = 1;

  var win = floaty.rawWindow(
    <frame>
      <vertical bg="#cc000000" padding="5">
        <text id="label" text="" textSize="13sp" textColor="#ffffff" gravity="center"/>
        <text id="pos" text="" textSize="11sp" textColor="#ffcc00" gravity="center"/>
        <horizontal gravity="center">
          <button id="up" text="↑" w="44dp" h="28dp"/>
        </horizontal>
        <horizontal gravity="center">
          <button id="left" text="←" w="44dp" h="28dp"/>
          <button id="stepBtn" text="步长1" w="44dp" h="28dp"/>
          <button id="right" text="→" w="44dp" h="28dp"/>
        </horizontal>
        <horizontal gravity="center">
          <button id="down" text="↓" w="44dp" h="28dp"/>
        </horizontal>
        <horizontal gravity="center">
          <button id="ok" text="确认" w="64dp" h="30dp"/>
          <button id="skip" text="跳过" w="52dp" h="30dp"/>
        </horizontal>
        <button id="saveBtn" text="保存配置文件" w="140dp" h="28dp"/>
      </vertical>
    </frame>
  );

  win.setPosition(Math.floor(device.width / 2) - 70, Math.floor(device.height / 2) - 90);
  win.setSize(-2, -2);

  function updateUI() {
    var t = TARGETS[idx];
    win.label.setText((idx+1) + "/" + TARGETS.length + " " + t.label);
    win.pos.setText("X=" + win.getX() + " Y=" + win.getY() + " 步长=" + step);
    win.skip.setVisibility(idx === 6 ? 0 : 8);
  }

  function confirm() {
    saved[idx] = { x: win.getX(), y: win.getY() };
    advance();
  }

  function skip() { advance(); }

  function advance() {
    if (idx < TARGETS.length - 1) {
      idx++;
      updateUI();
    } else {
      toast("全部标完，点击 [保存配置文件]");
    }
  }

  function move(dx, dy) {
    win.setPosition(win.getX() + dx * step, win.getY() + dy * step);
    updateUI();
  }

  function saveConfig() {
    try {
      var lines = [];
      for (var i = 0; i < 5; i++) lines.push(saved[i] ? String(saved[i].x) : "0");
      var sumY = 0, countY = 0;
      for (var i = 0; i < 5; i++) { if (saved[i]) { sumY += saved[i].y; countY++; } }
      lines.push(countY > 0 ? String(Math.round(sumY / countY)) : "0");
      lines.push(saved[5] ? String(saved[5].x) : "0");
      lines.push(saved[5] ? String(saved[5].y) : "0");
      lines.push(saved[6] ? String(saved[6].x) : "0");
      lines.push(saved[6] ? String(saved[6].y) : "0");
      var tl = saved[7] || { x: 0, y: 0 };
      var br = saved[8] || { x: 0, y: 0 };
      lines.push(String(tl.x));
      lines.push(String(tl.y));
      lines.push(String(Math.max(0, br.x - tl.x)));
      lines.push(String(Math.max(0, br.y - tl.y)));

      files.ensureDir(WORK_DIR);
      files.write(CONFIG_FILE, lines.join("\n"));
      toast("已保存: " + CONFIG_FILE);
    } catch (e) {
      toast("保存失败: " + e.message);
    }
  }

  win.ok.click(confirm);
  win.skip.click(skip);
  win.saveBtn.click(saveConfig);
  win.up.click(function () { move(0, -1); });
  win.down.click(function () { move(0, 1); });
  win.left.click(function () { move(-1, 0); });
  win.right.click(function () { move(1, 0); });
  win.stepBtn.click(function () {
    step = step === 1 ? 10 : 1;
    updateUI();
  });

  updateUI();

  return {
    close: function () { win.close(); }
  };
}

module.exports = { createCalibrator: createCalibrator };
