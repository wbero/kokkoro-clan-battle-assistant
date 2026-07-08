"use strict";

var SRC = files.path(engines.myEngine().cwd() + "/src");
var models = require(SRC + "/models.js");

function parseTimeToSeconds(raw) {
  var match = String(raw).trim().match(/^(\d):([0-5]\d)$/);
  if (!match) {
    throw new Error("时间格式必须为 M:SS：" + raw);
  }
  return parseInt(match[1], 10) * 60 + parseInt(match[2], 10);
}

function parseKeyValue(line, lineNumber) {
  var index = line.indexOf("=");
  if (index < 0) {
    throw new Error("第" + lineNumber + "行缺少等号：" + line);
  }
  return {
    key: line.slice(0, index).trim(),
    value: line.slice(index + 1).trim()
  };
}

function parseActionField(field, lineNumber) {
  var kv = parseKeyValue(field, lineNumber);
  if (kv.key === "点击") {
    return kv.value.split(",").map(function (name) {
      name = name.trim();
      if (name === "AUTO") {
        return { type: models.ActionType.CLICK_AUTO };
      }
      if (name === "BOSS") {
        return { type: models.ActionType.BOSS };
      }
      return { type: models.ActionType.CLICK_ROLE, role: name };
    });
  }
  if (kv.key === "提示") {
    return [{ type: models.ActionType.NOTIFY, message: kv.value }];
  }
  if (kv.key === "AUTO") {
    return [{ type: models.ActionType.TOGGLE_AUTO, value: kv.value === "开" ? "on" : "off" }];
  }
  if (kv.key === "SET") {
    return [{ type: "setRoles", values: kv.value.split(",").map(function (item) { return item.trim(); }) }];
  }
  throw new Error("第" + lineNumber + "行未知字段：" + kv.key);
}

function parseAxisText(text) {
  var lines = String(text).replace(/\r\n/g, "\n").split("\n");
  var header = {};
  var events = [];
  var inAxis = false;
  for (var i = 0; i < lines.length; i += 1) {
    var lineNumber = i + 1;
    var line = lines[i].trim();
    if (line === "" || line.indexOf("#") === 0) {
      continue;
    }
    if (line === "[轴]") {
      inAxis = true;
      continue;
    }
    if (!inAxis) {
      var headerKv = parseKeyValue(line, lineNumber);
      header[headerKv.key] = headerKv.value;
      continue;
    }
    var parts = line.split("|").map(function (part) { return part.trim(); });
    var timeSeconds = parseTimeToSeconds(parts[0]);
    var actions = [];
    for (var p = 1; p < parts.length; p += 1) {
      actions = actions.concat(parseActionField(parts[p], lineNumber));
    }
    events.push({
      id: "line-" + lineNumber,
      sourceLine: lineNumber,
      timeSeconds: timeSeconds,
      actions: actions
    });
  }
  return {
    type: header["轴类型"] === "开关" ? models.AxisType.SWITCH : models.AxisType.SEQUENCE,
    clickIntervalMs: parseInt(header["点击间隔"] || "100", 10),
    header: header,
    events: events
  };
}

module.exports = {
  parseAxisText: parseAxisText,
  parseTimeToSeconds: parseTimeToSeconds
};
