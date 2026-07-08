/**
 * PC 端时钟识别测试工具
 *
 * 用于在 PC 上验证手动裁剪的数字模板是否正确。
 * 算法与 clock_recognizer.js 完全一致，只是用 pngjs+bmp-js 替代 AutoJS images API。
 *
 * 使用方式：
 *   1. 将游戏截图保存为 test/screenshot.png (或 .bmp)
 *   2. 将裁剪的数字模板放到 test/templates/ 下（0~9 + colon，支持 png/bmp）
 *   3. 运行：node test/pc_clock_test.js
 *   4. 如果计时器位置跟默认不同，编辑下方 CONFIG
 */

var fs = require("fs");
var path = require("path");
var PNG = require("pngjs").PNG;
var bmp = require("bmp-js");

// ---- 参数配置 ----
var CONFIG = {
  // MuMu 1920x1080 — 沙漏定位: 沙漏 x=1518 y=27, 计时器在沙漏右边约 116px
  timerX: 1634,
  timerY: 38,
  timerW: 48,
  timerH: 27
};

// ---- 图片读取（支持 PNG + BMP） ----
function readImage(filePath) {
  var data = fs.readFileSync(filePath);
  var ext = path.extname(filePath).toLowerCase();

  if (ext === ".bmp") {
    var bmpData = bmp.decode(data);
    var png = new PNG({ width: bmpData.width, height: bmpData.height });
    for (var i = 0; i < bmpData.data.length; i += 4) {
      png.data[i]     = bmpData.data[i + 2];
      png.data[i + 1] = bmpData.data[i + 1];
      png.data[i + 2] = bmpData.data[i];
      png.data[i + 3] = bmpData.data[i + 3];
    }
    return png;
  }
  return PNG.sync.read(data);
}

/**
 * 尝试读取模板文件（自动尝试 .png 和 .bmp 扩展名）
 */
function readTemplateFile(dir, name) {
  var pngPath = path.join(dir, name + ".png");
  if (fs.existsSync(pngPath)) return { img: readImage(pngPath), path: pngPath };
  var bmpPath = path.join(dir, name + ".bmp");
  if (fs.existsSync(bmpPath)) return { img: readImage(bmpPath), path: bmpPath };
  return null;
}

// ---- 像素操作 ----

function clip(png, x, y, w, h) {
  var dst = new PNG({ width: w, height: h });
  for (var row = 0; row < h; row++) {
    for (var col = 0; col < w; col++) {
      var srcIdx = ((y + row) * png.width + (x + col)) * 4;
      var dstIdx = (row * w + col) * 4;
      dst.data[dstIdx]     = png.data[srcIdx];
      dst.data[dstIdx + 1] = png.data[srcIdx + 1];
      dst.data[dstIdx + 2] = png.data[srcIdx + 2];
      dst.data[dstIdx + 3] = png.data[srcIdx + 3];
    }
  }
  return dst;
}

function getPixel(png, x, y) {
  if (x < 0 || x >= png.width || y < 0 || y >= png.height) return 0;
  var idx = (y * png.width + x) * 4;
  return (png.data[idx + 3] << 24) | (png.data[idx] << 16) | (png.data[idx + 1] << 8) | png.data[idx + 2];
}

function resize(png, newW, newH, keepRatio) {
  // keepRatio: 保持宽高比，居中放置，空白区域填白色
  if (!keepRatio) {
    var dst = new PNG({ width: newW, height: newH });
    for (var y = 0; y < newH; y++) {
      for (var x = 0; x < newW; x++) {
        var srcX = Math.floor(x * png.width / newW);
        var srcY = Math.floor(y * png.height / newH);
        var srcIdx = (srcY * png.width + srcX) * 4;
        var dstIdx = (y * newW + x) * 4;
        dst.data[dstIdx]     = png.data[srcIdx];
        dst.data[dstIdx + 1] = png.data[srcIdx + 1];
        dst.data[dstIdx + 2] = png.data[srcIdx + 2];
        dst.data[dstIdx + 3] = png.data[srcIdx + 3];
      }
    }
    return dst;
  }
  // 保持宽高比，缩放到适合 newH 的高度，居中
  var scale = newH / png.height;
  var scaledW = Math.round(png.width * scale);
  var scaledH = newH;
  var offsetX = Math.floor((newW - scaledW) / 2);

  var dst = new PNG({ width: newW, height: newH });
  // 填白色背景
  for (var y = 0; y < newH; y++)
    for (var x = 0; x < newW; x++) {
      var di = (y * newW + x) * 4;
      dst.data[di] = 255; dst.data[di+1] = 255; dst.data[di+2] = 255; dst.data[di+3] = 255;
    }
  // 复制缩放后的模板
  for (var y = 0; y < scaledH; y++) {
    for (var x = 0; x < scaledW; x++) {
      var srcX = Math.floor(x / scale);
      var srcY = Math.floor(y / scale);
      var srcIdx = (srcY * png.width + srcX) * 4;
      var dstIdx = ((y) * newW + (x + offsetX)) * 4;
      dst.data[dstIdx]     = png.data[srcIdx];
      dst.data[dstIdx + 1] = png.data[srcIdx + 1];
      dst.data[dstIdx + 2] = png.data[srcIdx + 2];
      dst.data[dstIdx + 3] = 255;
    }
  }
  return dst;
}

// ---- 识别算法 ----

function pixelDiff(imgA, imgB) {
  // 先二值化：计算每张图的平均亮度，以此为阈值
  // 这样只比暗/亮分布（形状），不比实际颜色值
  var w = imgA.width, h = imgA.height;
  var total = 0, diff = 0;

  // 分别计算两图平均亮度作为阈值
  var sumA = 0, sumB = 0, count = 0;
  for (var y = 0; y < h; y++) {
    for (var x = 0; x < w; x++) {
      var ca = getPixel(imgA, x, y);
      var cb = getPixel(imgB, x, y);
      sumA += ((ca >> 16) & 0xff) + ((ca >> 8) & 0xff) + (ca & 0xff);
      sumB += ((cb >> 16) & 0xff) + ((cb >> 8) & 0xff) + (cb & 0xff);
      count++;
    }
  }
  var threshA = sumA / count;
  var threshB = sumB / count;

  // 比较二值化结果
  for (var y = 0; y < h; y++) {
    for (var x = 0; x < w; x++) {
      var ca = getPixel(imgA, x, y);
      var cb = getPixel(imgB, x, y);
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

function bestMatch(charImage, templates) {
  var best = null, bestDiff = Infinity;
  var chars = Object.keys(templates);
  for (var i = 0; i < chars.length; i++) {
    var ch = chars[i];
    var scaled = resize(templates[ch], charImage.width, charImage.height, true);
    var d = pixelDiff(charImage, scaled);
    if (d < bestDiff) { bestDiff = d; best = ch; }
  }
  if (!best) return { char: "?", confidence: 0 };
  return { char: best, confidence: Math.max(0, 1 - bestDiff * 2), diff: bestDiff };
}

// ---- 标注函数 ----

function drawRect(png, x, y, w, h, r, g, b, thickness) {
  thickness = thickness || 2;
  for (var t = 0; t < thickness; t++) {
    var x1 = x + t, y1 = y + t, x2 = x + w - 1 - t, y2 = y + h - 1 - t;
    for (var px = x1; px <= x2; px++) {
      setPixelColor(png, px, y1, r, g, b);
      setPixelColor(png, px, y2, r, g, b);
    }
    for (var py = y1; py <= y2; py++) {
      setPixelColor(png, x1, py, r, g, b);
      setPixelColor(png, x2, py, r, g, b);
    }
  }
}

function drawVLine(png, x, y1, y2, r, g, b, thickness) {
  thickness = thickness || 1;
  for (var t = 0; t < thickness; t++) {
    for (var py = y1; py <= y2; py++) {
      setPixelColor(png, x + t, py, r, g, b);
    }
  }
}

function setPixelColor(png, x, y, r, g, b) {
  if (x < 0 || x >= png.width || y < 0 || y >= png.height) return;
  var idx = (y * png.width + x) * 4;
  png.data[idx] = r;
  png.data[idx + 1] = g;
  png.data[idx + 2] = b;
  png.data[idx + 3] = 255;
}

function annotateAndSave(screen, tx, ty, tw, th, splitX) {
  // 复制截图（避免修改原图）
  var annotated = new PNG({ width: screen.width, height: screen.height });
  screen.data.copy(annotated.data);

  // 红框：计时器整体区域
  drawRect(annotated, tx, ty, tw, th, 255, 0, 0, 2);

  // 绿线：字符分割线
  for (var i = 1; i < splitX.length; i++) {
    drawVLine(annotated, tx + splitX[i], ty, ty + th, 0, 255, 0, 1);
  }

  // 标签
  var labels = ["M", ":", "S0", "S1"];
  for (var j = 0; j < labels.length; j++) {
    var labelX = tx + splitX[j] + 2;
    var labelY = ty + th + 14;
    // 简单画标签（用像素点画不太现实，跳过文字，改在控制台输出位置信息）
  }

  // 保存
  var outPath = path.join(__dirname, "annotated.png");
  var buf = PNG.sync.write(annotated);
  fs.writeFileSync(outPath, buf);
  console.log("红框标注已保存: " + outPath);
  console.log("  红框=计时器区域(" + tw + "x" + th + ")");
  console.log("  绿线=字符分割  M|:|S0|S1");
}

// ---- 主识别 ----

function recognizeClock(screenshotPath, templatesDir) {
  // 1. 截图
  if (!fs.existsSync(screenshotPath)) {
    console.log("截图不存在: " + screenshotPath);
    return null;
  }
  var screen = readImage(screenshotPath);
  console.log("截图尺寸: " + screen.width + "x" + screen.height);

  // 2. 模板
  if (!fs.existsSync(templatesDir)) {
    console.log("模板目录不存在: " + templatesDir);
    return null;
  }
  var templates = {};
  var required = ["0","1","2","3","4","5","6","7","8","9","colon"];
  var missing = [];
  for (var i = 0; i < required.length; i++) {
    var name = required[i];
    var tf = readTemplateFile(templatesDir, name);
    if (!tf) { missing.push(name); continue; }
    var key = (name === "colon") ? ":" : name;
    templates[key] = tf.img;
  }
  if (missing.length > 0) {
    console.log("缺失模板 (" + missing.length + "): " + missing.join(", "));
  } else {
    console.log("模板: " + Object.keys(templates).length + " 个");
  }

  // 3. 计时器区域
  var tx = CONFIG.timerX, ty = CONFIG.timerY, tw = CONFIG.timerW, th = CONFIG.timerH;
  if (tx + tw > screen.width || ty + th > screen.height) {
    console.log("计时器区域超出截图! 当前: x=" + tx + " y=" + ty + " w=" + tw + " h=" + th);
    console.log("截图: " + screen.width + "x" + screen.height);
    return null;
  }

  // ---- 画出红框标注（不管模板是否完整都生成） ----
  var splitX = [0, Math.floor(tw * 0.22), Math.floor(tw * 0.38), Math.floor(tw * 0.62)];
  annotateAndSave(screen, tx, ty, tw, th, splitX);

  // 模板不完整则跳过识别
  if (missing.length > 0) {
    console.log("模板不完整，仅生成标注图。请补充后重试。");
    return null;
  }

  var timerClip = clip(screen, tx, ty, tw, th);

  // 4. 切分（使用上面已计算的 splitX）
  var splitW = [splitX[1] - splitX[0], splitX[2] - splitX[1], splitX[3] - splitX[2], tw - splitX[3]];
  var charTypes = ["digit", "colon", "digit", "digit"];

  var chars = [], confidences = [], details = [];

  for (var j = 0; j < 4; j++) {
    var charClip = clip(timerClip, splitX[j], 0, splitW[j], th);

    if (charTypes[j] === "colon") {
      var ct = templates[":"];
      var scaled = resize(ct, charClip.width, charClip.height, true);
      var d = pixelDiff(charClip, scaled);
      chars.push(":");
      confidences.push(Math.max(0, 1 - d * 2));
      details.push({ type: "colon", diff: d, size: charClip.width + "x" + charClip.height });
    } else {
      // 约束匹配范围：M位只可能是0-1，S0位只可能是0-5
      var digitTpls = {};
      var minD = 0, maxD = 9;
      if (j === 0) { maxD = 1; }        // M位：只有 0 或 1
      else if (j === 2) { maxD = 5; }    // S0位（秒十位）：只有 0-5
      for (var d = minD; d <= maxD; d++) {
        if (templates[String(d)]) digitTpls[String(d)] = templates[String(d)];
      }
      var match = bestMatch(charClip, digitTpls);

      var allDiffs = {};
      for (var k = 0; k <= 9; k++) {
        if (templates[String(k)]) {
          var ks = resize(templates[String(k)], charClip.width, charClip.height, true);
          allDiffs[String(k)] = pixelDiff(charClip, ks).toFixed(4);
        }
      }
      chars.push(match.char);
      confidences.push(match.confidence);
      details.push({
        type: "digit", best: match.char, diff: match.diff,
        allDiffs: allDiffs, size: charClip.width + "x" + charClip.height
      });
    }
  }

  var rawText = chars.join("");
  var avgConf = confidences.reduce(function (a, b) { return a + b; }, 0) / 4;
  var formatOk = /^\d:\d\d$/.test(rawText);

  // 5. 输出
  console.log("");
  console.log("========== 识别结果 ==========");
  console.log("文本: " + rawText + "  格式: " + (formatOk ? "OK" : "FAIL") +
              "  置信度: " + (avgConf * 100).toFixed(1) + "%");
  console.log("");

  for (var ii = 0; ii < 4; ii++) {
    var dd = details[ii];
    console.log("位置" + (ii+1) + " [" + dd.type + "]: " + chars[ii] +
                "  置信度=" + (confidences[ii] * 100).toFixed(1) + "%" +
                "  diff=" + (dd.diff || 0).toFixed(4) + "  " + dd.size);
    if (dd.allDiffs) {
      var sorted = Object.entries(dd.allDiffs).sort(function (a, b) { return a[1] - b[1]; });
      console.log("  候选: " + sorted.slice(0, 3).map(function (e) { return e[0] + "(" + e[1] + ")"; }).join("  "));
    }
  }

  if (formatOk && avgConf > 0.7) {
    var parts = rawText.split(":");
    console.log("");
    console.log("OK 识别成功: " + (parseInt(parts[0]) * 60 + parseInt(parts[1])) + " 秒");
  } else {
    console.log("");
    console.log("FAIL 识别不理想");
    if (avgConf <= 0.7) console.log("  - 检查模板是否紧贴字符边缘");
    if (!formatOk) console.log("  - 检查计时器位置 CONFIG.timerX/Y");
  }

  return { rawText: rawText, confidence: avgConf, formatOk: formatOk };
}

// ---- 模板质量检查 ----
function checkTemplates(templatesDir) {
  console.log("========== 模板质量 ==========");
  var names = ["0","1","2","3","4","5","6","7","8","9","colon"];
  var heights = [];
  var issues = [];

  for (var i = 0; i < names.length; i++) {
    var tf = readTemplateFile(templatesDir, names[i]);
    if (!tf) { issues.push(names[i] + " 不存在"); continue; }
    var img = tf.img;
    heights.push(img.height);
    console.log(names[i] + ": " + img.width + "x" + img.height + " (" + path.basename(tf.path) + ")");
    if (names[i] !== "colon" && img.width > img.height * 1.2) {
      issues.push(names[i] + " 偏宽 (" + img.width + "px)，可能有白边");
    }
  }

  // 冒号高度检查
  var digitH = heights.filter(function (_, i) { return names[i] !== "colon"; });
  var medianH = digitH.sort(function (a, b) { return a - b; })[Math.floor(digitH.length / 2)];
  var colonIdx = names.indexOf("colon");
  if (colonIdx >= 0 && heights[colonIdx] !== undefined) {
    var ch = heights[colonIdx];
    if (Math.abs(ch - medianH) > 3) {
      issues.push("冒号高度(" + ch + "px) != 数字高度(" + medianH + "px) — 必须一致!");
    }
  }

  var minH = Math.min.apply(null, heights);
  var maxH = Math.max.apply(null, heights);
  if (maxH - minH > 3) {
    issues.push("高度不一致: " + minH + "~" + maxH + "px");
  }

  if (issues.length > 0) {
    console.log(""); console.log("ISSUES:");
    issues.forEach(function (s) { console.log("  - " + s); });
  } else {
    console.log("OK");
  }
}

// ======== MAIN ========

// 自动查找截图和模板
var testDir = path.join(__dirname);
var templateDir = path.join(testDir, "templates");

// 查找截图：优先 screenshot.png，否则找 templates 目录下的 png（非模板文件）
var screenshotPath = path.join(testDir, "screenshot.png");
if (!fs.existsSync(screenshotPath)) {
  // 尝试在 templates 目录下找截图（优先最新的 MuMu PNG）
  if (fs.existsSync(templateDir)) {
    var files = fs.readdirSync(templateDir);
    var candidates = [];
    for (var i = 0; i < files.length; i++) {
      var f = files[i];
      // 只看 MuMu 开头的 PNG 截图，排除 BMP 模板文件
      if (f.endsWith(".png") && f.startsWith("MuMu")) {
        candidates.push(f);
      }
    }
    candidates.sort().reverse();
    if (candidates.length > 0) {
      screenshotPath = path.join(templateDir, candidates[0]);
    }
  }
}

console.log("PC 时钟识别测试");
console.log("截图: " + screenshotPath);
console.log("模板: " + templateDir);
console.log("计时器: x=" + CONFIG.timerX + " y=" + CONFIG.timerY + " w=" + CONFIG.timerW + " h=" + CONFIG.timerH);
console.log("");

checkTemplates(templateDir);
console.log("");
recognizeClock(screenshotPath, templateDir);
