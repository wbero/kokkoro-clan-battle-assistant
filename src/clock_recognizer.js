"use strict";

/**
 * 时钟识别模块 — 固定位置逐字符像素比对
 *
 * 为什么不用 images.findImage()（TemplateMatch）：
 *   - AutoJS 的 findImage 在真机上每次 200-800ms，太慢
 *   - 计时器位置固定、字体固定，不需要"搜索"模板位置
 *   - 直接把 ROI 切成 4 个字符格，逐像素比对，<3ms 完成
 *
 * 计时器格式: M:SS，固定宽度字体，4 个字符位置：
 *   ┌───┬───┬───┬───┐
 *   │ M │ : │ S0│ S1│    M=分钟位  S0=秒十位  S1=秒个位
 *   └───┴───┴───┴───┘
 *
 * 模板准备（需要用户在真机上手动裁剪）：
 *   从游戏截图 timerRegion 中裁剪 0-9 和冒号，
 *   保存为 0.png ~ 9.png, colon.png
 *   放到 /sdcard/Download/可可萝自动会战助手/templates/
 */

/**
 * 计算两张等大图片的像素差异率（二值化后比较）
 *
 * 只比形状分布（暗/亮），不比实际颜色值。
 * 这样即使模板和截图的背景色不同（黄色 vs 白蓝），也能正确匹配。
 */
function pixelDiff(imgA, imgB) {
  var w = Math.min(imgA.getWidth(), imgB.getWidth());
  var h = Math.min(imgA.getHeight(), imgB.getHeight());

  // 各自计算平均亮度作为二值化阈值
  var sumA = 0, sumB = 0, count = 0;
  for (var y = 0; y < h; y += 2) {
    for (var x = 0; x < w; x += 2) {
      var ca = images.pixel(imgA, x, y);
      var cb = images.pixel(imgB, x, y);
      sumA += ((ca >> 16) & 0xff) + ((ca >> 8) & 0xff) + (ca & 0xff);
      sumB += ((cb >> 16) & 0xff) + ((cb >> 8) & 0xff) + (cb & 0xff);
      count++;
    }
  }
  var threshA = sumA / count;
  var threshB = sumB / count;

  // 比较二值化结果
  var diff = 0, total = 0;
  for (var y = 0; y < h; y += 2) {
    for (var x = 0; x < w; x += 2) {
      var ca = images.pixel(imgA, x, y);
      var cb = images.pixel(imgB, x, y);
      var ba = ((ca >> 16) & 0xff) + ((ca >> 8) & 0xff) + (ca & 0xff);
      var bb = ((cb >> 16) & 0xff) + ((cb >> 8) & 0xff) + (cb & 0xff);
      var bitA = ba < threshA ? 0 : 1;
      var bitB = bb < threshB ? 0 : 1;
      if (bitA !== bitB) diff++;
      total++;
    }
  }

  return total === 0 ? 1 : diff / total;
}

/**
 * 在模板集中找到最匹配的字符
 * @returns {{ char: string, confidence: number }}
 */
function bestMatch(charImage, templates) {
  var best = null;
  var bestDiff = Infinity;

  var chars = Object.keys(templates);
  for (var i = 0; i < chars.length; i += 1) {
    var ch = chars[i];
    var tpl = templates[ch];
    if (!tpl) continue;

    // 缩放模板到与字符区域等大
    var scaled = images.resize(tpl, [charImage.getWidth(), charImage.getHeight()]);
    var d = pixelDiff(charImage, scaled);
    scaled.recycle();

    if (d < bestDiff) {
      bestDiff = d;
      best = ch;
    }
  }

  if (!best) return { char: "?", confidence: 0 };

  // 差异率 → 置信度映射：diff 0.05 → 0.95, diff 0.4 → 0.6
  var confidence = Math.max(0, 1 - bestDiff * 2);
  return { char: best, confidence: confidence };
}

/**
 * 从截图 timerRegion 中识别剩余时间
 *
 * @param {Image} screenImage - 完整截图
 * @param {Object} timerRegion - { x, y, width, height }
 * @param {Object} templates - { "0": Image, "1": Image, ..., "9": Image, ":": Image }
 *                             如果为 null/undefined，返回 not-implemented
 * @returns {{ ok: boolean, timeSeconds?: number, rawText?: string, confidence?: number, reason?: string }}
 */
function recognizeClock(screenImage, timerRegion, templates) {
  if (!templates || Object.keys(templates).length < 11) {
    return { ok: false, reason: "no-templates", confidence: 0 };
  }
  if (!timerRegion || timerRegion.width < 20 || timerRegion.height < 10) {
    return { ok: false, reason: "invalid-timer-region", confidence: 0 };
  }

  try {
    // 1. 裁剪计时器区域
    var clip = images.clip(
      screenImage,
      timerRegion.x, timerRegion.y,
      timerRegion.width, timerRegion.height
    );

    var cw = clip.getWidth();
    var ch = clip.getHeight();

    // 2. 按比例切分为 4 个字符格
    // 格式 M:SS，假设字符等宽：M占20%，冒号占15%，S0占30%，S1占30%
    // 实际比例需要真机校准，这里用自适应切分
    var charWidth = Math.floor(cw / 4); // 粗略等分

    // 更准确的分割：第一个字符窄一点（M 只有 0-1），冒号窄，秒位宽
    var splitX = [
      0,                                  // M 位起始
      Math.floor(cw * 0.22),              // 冒号起始
      Math.floor(cw * 0.38),              // S 十位起始
      Math.floor(cw * 0.62)               // S 个位起始
    ];
    var splitW = [
      splitX[1] - splitX[0],              // M 位宽度
      splitX[2] - splitX[1],              // 冒号宽度
      splitX[3] - splitX[2],              // S 十位宽度
      cw - splitX[3]                      // S 个位宽度
    ];

    // 3. 逐位匹配
    var chars = [];
    var confidences = [];
    var charTypes = ["digit", "colon", "digit", "digit"];

    for (var i = 0; i < 4; i += 1) {
      var charClip = images.clip(clip, splitX[i], 0, splitW[i], ch);

      if (charTypes[i] === "colon") {
        // 冒号位：只跟 ":" 模板比较
        var colonTpl = templates[":"];
        if (colonTpl) {
          var scaled = images.resize(colonTpl, [charClip.getWidth(), charClip.getHeight()]);
          var d = pixelDiff(charClip, scaled);
          scaled.recycle();
          chars.push(":");
          confidences.push(Math.max(0, 1 - d * 2));
        } else {
          chars.push(":");
          confidences.push(0.8); // 没模板时盲猜冒号
        }
      } else {
        // 数字位：约束匹配范围
        // M 位(i=0) 只可能是 0 或 1；S0 位(i=2) 只可能是 0-5
        var digitTemplates = {};
        var minD = 0, maxD = 9;
        if (i === 0) { maxD = 1; }        // M 位：只有 0 或 1
        else if (i === 2) { maxD = 5; }    // S0 位（秒十位）：只有 0-5
        for (var d = minD; d <= maxD; d += 1) {
          if (templates[String(d)]) {
            digitTemplates[String(d)] = templates[String(d)];
          }
        }
        var match = bestMatch(charClip, digitTemplates);
        chars.push(match.char);
        confidences.push(match.confidence);
      }

      charClip.recycle();
    }

    clip.recycle();

    // 4. 校验结果
    var rawText = chars.join("");
    var avgConfidence = confidences.reduce(function (a, b) { return a + b; }, 0) / 4;

    // 格式校验：必须是 数字:数字数字
    var formatOk = /^\d:\d\d$/.test(rawText);

    if (!formatOk || avgConfidence < 0.5) {
      return { ok: false, reason: "low-confidence", rawText: rawText, confidence: avgConfidence };
    }

    // 5. 转秒数
    var parts = rawText.split(":");
    var minutes = parseInt(parts[0], 10);
    var seconds = parseInt(parts[1], 10);
    var timeSeconds = minutes * 60 + seconds;

    return {
      ok: true,
      timeSeconds: timeSeconds,
      rawText: rawText,
      confidence: avgConfidence
    };

  } catch (e) {
    return { ok: false, reason: "error:" + e.message, confidence: 0 };
  }
}

/**
 * 加载模板文件
 *
 * 优先级：用户工作目录 > APK 内置 assets > 相对路径
 * 这样用户可以在工作目录放入自己的模板覆盖默认值。
 *
 * @param {string|Array} templateDirs - 模板目录路径（可多个，按优先级排列）
 * @returns {Object|null} { "0": Image, "1": Image, ..., "9": Image, ":": Image }
 */
function loadTemplates(templateDirs) {
  // 统一转为数组
  if (typeof templateDirs === "string") {
    templateDirs = [templateDirs];
  }
  if (!templateDirs || templateDirs.length === 0) {
    templateDirs = [];
  }

  // 追加 APK assets 路径和相对路径作为兜底
  templateDirs.push("./assets/templates");

  try {
    var templates = {};
    var requiredFiles = ["0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "colon"];

    for (var i = 0; i < requiredFiles.length; i += 1) {
      var name = requiredFiles[i];
      var img = null;

      // 逐个路径尝试
      for (var d = 0; d < templateDirs.length; d += 1) {
        var dir = templateDirs[d];
        if (!dir) continue;

        var path = dir + "/" + name + ".png";
        if (!files.exists(path)) {
          path = dir + "/" + name + ".jpg";
        }
        if (files.exists(path)) {
          img = images.read(path);
          if (img) break;
        }
      }

      if (!img) {
        console.log("模板缺失: " + name);
        return null;
      }

      // 键名统一：colon → ":"
      var key = (name === "colon") ? ":" : name;
      templates[key] = img;
    }

    console.log("模板加载成功，来源路径数: " + templateDirs.length);
    return templates;
  } catch (e) {
    console.log("模板加载异常: " + e.message);
    return null;
  }
}

module.exports = {
  recognizeClock: recognizeClock,
  loadTemplates: loadTemplates,
  pixelDiff: pixelDiff,
  bestMatch: bestMatch
};
