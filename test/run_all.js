/**
 * 全部测试入口
 *
 * 运行：node test/run_all.js
 *
 * 这些模块不依赖 AutoJS API，可以在 PC 上用 Node.js 直接跑。
 */

console.log("可可萝自动会战助手 — 纯逻辑模块测试");
console.log("==================================");
console.log("");

require("./test_config_parser.js").run();
console.log("");
require("./test_axis_parser.js").run();
console.log("");
require("./test_scheduler.js").run();
