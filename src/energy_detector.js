"use strict";

/**
 * 能量条 + UB 图标检测模块
 *
 * 借鉴 MaaPcrclanbattle 的双层验证思路，但全部用像素颜色检测替代 TemplateMatch，
 * 适配 AutoJS6 真机性能（单次检测 < 5ms）。
 *
 * MaaPcrclanbattle 的做法:
 *   ColorMatch (能量条蓝色) → TemplateMatch "aub.png" (UB图标亮起) → 点击
 *   inverse TemplateMatch "mfd.png" → UB 图标消失 → UB 放完
 *
 * 我们的做法（真机性能优先）:
 *   RGB 蓝色占比 (能量条) → 亮度检测 (UB图标亮起) → 双层确认
 *   亮度骤降 (UB图标灭掉) → UB 放完，无需 TemplateMatch
 *
 * ROI 尺寸参考 MaaPcrclanbattle: 能量条 40×5, UB图标 25×20
 */

// ---- 颜色判断函数 ----

/**
 * 简易蓝色像素判断 — B 通道显著高于 R 和 G
 * 用于能量条区域。不做 HSV 转换，只做整数比较。
 * AutoJS images.pixel() 返回 ARGB 格式。
 */
function isBluePixel(color) {
  var r = (color >> 16) & 0xff;
  var g = (color >> 8) & 0xff;
  var b = color & 0xff;
  return b > r + 40 && b > g + 30 && b > 80;
}

/**
 * 判断像素是否"亮" — 用于检测 UB 图标是否亮起
 * UB 图标未就绪时是深灰/半透明的，就绪时会发亮（RGB 均偏高）。
 * 阈值设得较高避免误判。
 */
function isBrightPixel(color) {
  var r = (color >> 16) & 0xff;
  var g = (color >> 8) & 0xff;
  var b = color & 0xff;
  // 三通道都偏高 + 不是纯白色背景
  return r > 160 && g > 160 && b > 160 && r < 250;
}

// ---- 区域扫描函数 ----

/**
 * 在指定 ROI 内抽样统计满足条件的像素占比
 *
 * @param {Image} clip - 截图对象
 * @param {Object} roi - { x, y, width, height }
 * @param {Function} predicate - 像素判断函数
 * @param {number} step - 抽样步长，默认 2
 * @returns {number} 占比 (0.0 ~ 1.0)
 */
function scanRegion(clip, roi, predicate, step) {
  step = step || 2;
  var total = 0;
  var matched = 0;

  var endX = Math.min(roi.x + roi.width, clip.getWidth());
  var endY = Math.min(roi.y + roi.height, clip.getHeight());

  for (var y = roi.y; y < endY; y += step) {
    for (var x = roi.x; x < endX; x += step) {
      total++;
      if (predicate(images.pixel(clip, x, y))) {
        matched++;
      }
    }
  }

  if (total === 0) return 0;
  return matched / total;
}

/**
 * 在指定 ROI 内计算平均亮度（RGB 三通道均值）
 * 用于检测 UB 图标区域的明暗变化
 */
function computeAvgBrightness(clip, roi, step) {
  step = step || 2;
  var total = 0;
  var sum = 0;

  var endX = Math.min(roi.x + roi.width, clip.getWidth());
  var endY = Math.min(roi.y + roi.height, clip.getHeight());

  for (var y = roi.y; y < endY; y += step) {
    for (var x = roi.x; x < endX; x += step) {
      var color = images.pixel(clip, x, y);
      var r = (color >> 16) & 0xff;
      var g = (color >> 8) & 0xff;
      var b = color & 0xff;
      sum += (r + g + b) / 3;
      total++;
    }
  }

  if (total === 0) return 0;
  return sum / total;
}

// ---- 主检测器 ----

/**
 * 创建能量检测器
 *
 * @param {Object} options
 * @param {Object} options.energyRegions - { "角色1": {x,y,w,h}, ... }
 *        能量条 ROI，尺寸参考 MaaPcrclanbattle: ~40×5 像素
 * @param {Object} options.ubIconRegions - { "角色1": {x,y,w,h}, ... }
 *        UB 图标 ROI，位于能量条上方，尺寸 ~25×20 像素
 * @param {number} options.ubThreshold - 能量条蓝色占比阈值，默认 0.85
 * @param {number} options.iconBrightThreshold - UB 图标亮起亮度阈值，默认 180
 * @param {number} options.sampleStep - 抽样步长，默认 2（每 2px 取一个，采样率 25%）
 * @param {number} options.deltaThreshold - 帧间变化阈值，低于此值视为能量冻结
 * @param {number} options.iconDropThreshold - UB 图标亮度骤降阈值，低于此比例视为 UB 已释放
 */
function createEnergyDetector(options) {
  options = options || {};
  var energyRegions = options.energyRegions || {};
  var iconRegions = options.ubIconRegions || {};
  var ubThreshold = options.ubThreshold || 0.85;
  var iconBrightThreshold = options.iconBrightThreshold || 0.7; // bright pixel ratio
  var sampleStep = options.sampleStep || 2;
  var deltaThreshold = options.deltaThreshold || 0.02;
  var iconDropThreshold = options.iconDropThreshold || 0.4; // brightness drop ratio

  // 上一帧快照
  var previousEnergy = null;   // { roleName: fillRatio }
  var previousIconBright = null; // { roleName: avgBrightness }

  /**
   * 对当前截图检测所有角色
   *
   * @param {Image} screenCapture
   * @returns {Object}
   *   {
   *     characters: {
   *       "角色1": {
   *         fillRatio: number,        // 能量条蓝色占比
   *         ubAvailable: boolean,     // 能量满
   *         iconBright: boolean,      // UB 图标亮起（双层确认）
   *         ubConfirmed: boolean,     // 能量满 AND 图标亮 → 可以释放
   *         iconDropped: boolean      // 图标刚灭掉 → UB 放完了
   *       }, ...
   *     },
   *     energyDelta: number,
   *     energyFrozen: boolean,
   *     anyUbTriggered: boolean,
   *     anyUbFinished: boolean,       // 新增：有角色 UB 放完了（图标灭）
   *     triggeredCharacters: string[],
   *     finishedCharacters: string[]  // 新增：刚放完 UB 的角色
   *   }
   */
  function detect(screenCapture) {
    var energySnapshot = {};
    var iconSnapshot = {};
    var totalEnergyDelta = 0;
    var triggeredCharacters = [];
    var finishedCharacters = [];

    var roleNames = Object.keys(energyRegions);

    for (var i = 0; i < roleNames.length; i++) {
      var roleName = roleNames[i];
      if (!Object.prototype.hasOwnProperty.call(energyRegions, roleName)) continue;

      // 1. 能量条蓝色占比检测
      var eRoi = energyRegions[roleName];
      var fillRatio = scanRegion(screenCapture, eRoi, isBluePixel, sampleStep);
      energySnapshot[roleName] = fillRatio;

      // 2. UB 图标亮度检测（如果配置了图标区域）
      var iconBrightRatio = 0;
      var iconAvgBrightness = 0;
      var iconDropped = false;

      if (iconRegions && iconRegions[roleName]) {
        var iRoi = iconRegions[roleName];
        iconBrightRatio = scanRegion(screenCapture, iRoi, isBrightPixel, sampleStep);
        iconAvgBrightness = computeAvgBrightness(screenCapture, iRoi, sampleStep);
        iconSnapshot[roleName] = iconAvgBrightness;

        // 检测图标是否刚灭掉（亮度骤降 = UB 放完）
        if (previousIconBright && previousIconBright[roleName] !== undefined) {
          var prevBright = previousIconBright[roleName];
          if (prevBright > iconBrightThreshold * 255 && iconAvgBrightness < prevBright * iconDropThreshold) {
            iconDropped = true;
            finishedCharacters.push(roleName);
          }
        }
      }

      // 3. 能量条变化对比
      if (previousEnergy && previousEnergy[roleName] !== undefined) {
        var prevFill = previousEnergy[roleName];
        var delta = Math.abs(fillRatio - prevFill);
        totalEnergyDelta += delta;

        // 检测 UB 释放：能量从满骤降到低
        if (prevFill >= ubThreshold && fillRatio < 0.3) {
          triggeredCharacters.push(roleName);
        }
      }
    }

    // 汇总
    var regionCount = Math.max(roleNames.length, 1);
    var avgEnergyDelta = totalEnergyDelta / regionCount;
    var energyFrozen = avgEnergyDelta < deltaThreshold;

    // 输出每个角色的状态
    var characters = {};
    for (var j = 0; j < roleNames.length; j++) {
      var name = roleNames[j];
      if (!Object.prototype.hasOwnProperty.call(energySnapshot, name)) continue;

      var energyFull = energySnapshot[name] >= ubThreshold;
      var iconBright = (iconRegions && iconRegions[name])
        ? (iconSnapshot[name] > iconBrightThreshold * 255)
        : false;

      characters[name] = {
        fillRatio: energySnapshot[name],
        ubAvailable: energyFull,
        iconBright: iconBright,
        ubConfirmed: energyFull && iconBright,  // 双层确认
        iconDropped: finishedCharacters.indexOf(name) >= 0
      };
    }

    // 保存快照
    previousEnergy = energySnapshot;
    previousIconBright = iconSnapshot;

    return {
      characters: characters,
      energyDelta: avgEnergyDelta,
      energyFrozen: energyFrozen,
      anyUbTriggered: triggeredCharacters.length > 0,
      anyUbFinished: finishedCharacters.length > 0,
      triggeredCharacters: triggeredCharacters,
      finishedCharacters: finishedCharacters
    };
  }

  function reset() {
    previousEnergy = null;
    previousIconBright = null;
  }

  return {
    detect: detect,
    reset: reset
  };
}

module.exports = {
  createEnergyDetector: createEnergyDetector,
  scanRegion: scanRegion,
  computeAvgBrightness: computeAvgBrightness,
  isBluePixel: isBluePixel,
  isBrightPixel: isBrightPixel
};
