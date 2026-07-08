# 可可萝自动会战助手 — 交接文档

## 项目概述

公主连结(PCR) AutoJS6 会战自动打轴脚本。核心思路：**读取游戏内倒计时 + 检测角色能量条/UB图标**，在正确的游戏时间触发轴事件，替代传统录制毫秒时间轴的盲打方式。

## 运行环境

- **手机/模拟器**: MuMu 模拟器 1920×1080
- **AutoJS6**: `org.autojs.autojs6`，无障碍已授权，悬浮窗已授权
- **ADB**: `C:\Users\wbero\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- **ADB 设备**: `127.0.0.1:5555` (MuMu)
- **代码位置**: `C:\Users\wbero\Desktop\linai\kokkoro-clan-battle-assistant\`
- **手机路径**: `/sdcard/Download/kokkoro-clan-battle-assistant/src/`

## 部署命令

```bash
# 停止旧脚本
cmd //c "adb -s 127.0.0.1:5555 shell am force-stop org.autojs.autojs6"

# 推送文件
cd "C:/Users/wbero/Desktop/linai/kokkoro-clan-battle-assistant/src"
cmd //c "adb -s 127.0.0.1:5555 push xxx.js /sdcard/Download/kokkoro-clan-battle-assistant/src/xxx.js"

# 清日志 + 运行
cmd //c "adb -s 127.0.0.1:5555 logcat -c && adb -s 127.0.0.1:5555 shell am start -n org.autojs.autojs6/org.autojs.autojs.external.open.RunIntentActivity -d file:///sdcard/Download/kokkoro-clan-battle-assistant/src/main.js"

# 看日志
cmd //c "adb -s 127.0.0.1:5555 logcat -d -t 100" | grep -i "Error\|TypeError\|错误"
```

## 模块架构

```
main.js (200ms 主循环)
  ├── clock_recognizer     — 计时器识别（二值化逐字符像素比对）
  ├── energy_detector      — 能量条蓝色占比 + UB图标亮度检测
  ├── game_state_detector  — 时钟+能量+图标三信号融合，输出 GameState
  ├── recognition_filter   — 时间值过滤（跳秒/异常值/低置信度）
  ├── scheduler            — 事件调度（UB冻结时暂停，解冻后补执行）
  ├── executor             — 8种动作类型 + AUTO状态追踪
  ├── calibrator           — 坐标校准（方向键移动浮窗 + 确认保存）
  └── ui                   — 主悬浮窗（开始/暂停/停止/选轴/校准/关闭）
```

## 当前状态

### 可以跑通（PC端验证过）
- 所有纯逻辑模块：37/37 单元测试通过 (`node test/run_all.js`)
- config_parser, axis_parser, recognition_filter, scheduler 全部正常
- PC 端时钟识别：二值化 pixelDiff + 数字范围约束（M位0-1，S0位0-5）
- 计时器定位：沙漏 x=1518 y=27，计时器 x=1634 y=38 w=48 h=27

### 真机问题
- **calibrator 崩溃**: AutoJS6 的 floaty.rawWindow 对触摸事件/颜色值/canvas API 支持不完整
  当前版本是纯方向键移动（去掉了触摸拖动和canvas绘制），理论应该能跑但还没验证成功
- **模拟器内存**: 192MB 堆限制，主循环已从 50ms 调到 200ms，避免多个 floaty 窗口并存
- **时钟模板**: assets/templates/ 有 11 张 PNG（灰度），是 BMP 逐像素精确转换的

### 还没做
- clock_recognizer 真机模板加载未验证
- 能量条/UB图标 ROI 需要在真机上调偏移量
- 轴文件选择器基本可用（dialogs.select），未充分测试
- 打包 APK：project.json 已配置好，build.js 可转换 BMP→PNG 模板

## AutoJS6 踩过的坑

1. **require() 相对路径不支持**: 必须用 `engines.myEngine().cwd()` 取绝对路径
2. **canvas API 不全**: drawColor/drawLine/drawCircle 在 JsCanvasView 上不存在，只能用 drawRect
3. **颜色值溢出**: `0xffffff00` 超过 Java int 最大值，用字符串 `#xxxxxx` 替代
4. **floaty.rawWindow 按钮 id**: 不能叫 `close`（跟内置方法冲突）
5. **XML 特殊字符**: `<` `>` 在 floaty XML 中必须用 `&lt;` `&gt;` 或 Unicode 替代
6. **浮窗 OOM**: 全屏透明 overlay (`setSize(device.width, device.height)`) 导致内存溢出
7. **多脚本实例**: 每次 RunIntentActivity 都新建实例，旧的不自动关 → 先 force-stop

## 参考资料

- `archive/original-2026-07-08/` — 原始 AutoJS 璃乃自动工具 2.0
- `archive/MaaPcrclanbattle-reference/` — MaaFramework 版 PCR 会战工具（PC端，Python）
- `docs/` — 架构说明、轴格式、校准说明
- `test/pc_clock_test.js` — PC 端时钟识别测试（`node test/pc_clock_test.js`）
- `test/templates/` — 用户裁剪的 BMP 数字模板（原始素材）
