"use strict";

function parseIntegerLine(lines, index, name) {
  var raw = lines[index];
  if (raw === undefined || String(raw).trim() === "") {
    throw new Error("配置缺少第" + (index + 1) + "行：" + name);
  }
  var value = parseInt(String(raw).trim(), 10);
  if (isNaN(value)) {
    throw new Error("配置第" + (index + 1) + "行不是整数：" + name);
  }
  return value;
}

function parseConfigText(text) {
  var lines = String(text).replace(/\r\n/g, "\n").split("\n");
  var names = [
    "5号位角色头像 X 坐标",
    "4号位角色头像 X 坐标",
    "3号位角色头像 X 坐标",
    "2号位角色头像 X 坐标",
    "1号位角色头像 X 坐标",
    "所有角色头像 Y 坐标",
    "AUTO 按钮 X 坐标",
    "AUTO 按钮 Y 坐标",
    "黑屏/起点监测 X 坐标",
    "黑屏/起点监测 Y 坐标",
    "时间区域左上 X 坐标",
    "时间区域左上 Y 坐标",
    "时间区域宽度",
    "时间区域高度"
  ];
  var values = [];
  for (var i = 0; i < names.length; i += 1) {
    values.push(parseIntegerLine(lines, i, names[i]));
  }
  return {
    roleX: {
      "角色5": values[0],
      "角色4": values[1],
      "角色3": values[2],
      "角色2": values[3],
      "角色1": values[4]
    },
    roleY: values[5],
    auto: { x: values[6], y: values[7] },
    blackScreenPoint: { x: values[8], y: values[9] },
    timerRegion: { x: values[10], y: values[11], width: values[12], height: values[13] }
  };
}

module.exports = {
  parseConfigText: parseConfigText
};
