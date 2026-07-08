// Kokkoro Assistant build script. Run: node build.js
var fs = require("fs");
var path = require("path");
var PNG = require("pngjs").PNG;
var ROOT = __dirname;
var SRC_TEMPLATES = path.join(ROOT, "test", "templates");
var DST_TEMPLATES = path.join(ROOT, "assets", "templates");

console.log("=== Kokkoro Build ===\n");

// 1. Convert BMP templates to PNG
console.log("[1/3] Converting templates...");
if (!fs.existsSync(DST_TEMPLATES)) fs.mkdirSync(DST_TEMPLATES, { recursive: true });

var digits = ["0","1","2","3","4","5","6","7","8","9","colon"];
var count = 0;
digits.forEach(function (name) {
  var src = path.join(SRC_TEMPLATES, name + ".bmp");
  if (!fs.existsSync(src)) {
    console.log("  SKIP: " + name + ".bmp not found");
    return;
  }
  // 手动解析 24-bit BMP，直接读 BGR 像素
  var raw = fs.readFileSync(src);
  var bfOffBits = raw.readUInt32LE(10);
  var biWidth = raw.readInt32LE(18);
  var biHeight = raw.readInt32LE(22); // >0 = bottom-up
  var rowSize = Math.floor((biWidth * 3 + 3) / 4) * 4; // 4-byte aligned

  var png = new PNG({ width: biWidth, height: biHeight });
  for (var y = 0; y < biHeight; y++) {
    // BMP is bottom-up: file row (biHeight-1-y) maps to PNG row y
    var srcY = biHeight - 1 - y;
    for (var x = 0; x < biWidth; x++) {
      var srcOff = bfOffBits + srcY * rowSize + x * 3;
      var b = raw[srcOff];
      var g = raw[srcOff + 1];
      var r = raw[srcOff + 2];
      var dstOff = (y * biWidth + x) * 4;
      png.data[dstOff]     = r;
      png.data[dstOff + 1] = g;
      png.data[dstOff + 2] = b;
      png.data[dstOff + 3] = 255;
    }
  }
  var dst = path.join(DST_TEMPLATES, name + ".png");
  fs.writeFileSync(dst, PNG.sync.write(png));
  console.log("  " + name + ".bmp -> assets/templates/" + name + ".png");
  count++;
});
console.log("  Done: " + count + " files\n");

// 2. Check project.json
console.log("[2/3] Checking project.json...");
var pjPath = path.join(ROOT, "project.json");
if (fs.existsSync(pjPath)) {
  var pj = JSON.parse(fs.readFileSync(pjPath));
  console.log("  package: " + pj.packageName);
  console.log("  version: " + pj.versionName);
  console.log("  main: " + pj.main);
  console.log("  OK\n");
} else {
  console.log("  ERR: project.json missing\n");
}

// 3. Run tests
console.log("[3/3] Running logic tests...");
try {
  require("./test/test_config_parser").run();
  console.log("");
  require("./test/test_axis_parser").run();
  console.log("");
  require("./test/test_scheduler").run();
} catch (e) {
  console.log("  Test error: " + e.message);
}

console.log("\n=== Done ===");
console.log("Copy project folder to phone, open in AutoJS6, use [Package] to build APK.");
console.log("Or run src/main.js directly to test.");
