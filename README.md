# 可可萝自动会战助手

可可萝自动会战助手是一个独立开发的 AutoJS6 公主连结会战辅助脚本。

## 核心机制

- **时间识别**：逐字符像素比对，读取游戏倒计时
- **能量条检测**：RGB 蓝色占比 + UB 图标亮度双层确认
- **UB 处理**：能量条冻结检测自动识别 UB 动画，解冻后补执行错过事件
- **事件调度**：按游戏时间触发轴事件，不依赖录制时间轴

## 快速开始

### 1. 打包 APK（推荐）

在 AutoJS6 中打开本项目，使用打包功能：
- 方式 A：AutoJS6 内置「打包应用」
- 方式 B：使用 APK Builder 插件（`[auto.js][apk_builder_plugin_4.1.1_alpha2].apk`）

项目根目录的 `project.json` 已配好打包参数。打包后模板文件会自动打入 APK。

### 2. 裁剪时钟模板

**模板是必须的**——没有模板就无法识别时间。

从游戏截图中手动裁剪 11 个字符模板，保存为 PNG：

```
0.png  1.png  2.png  3.png  4.png
5.png  6.png  7.png  8.png  9.png
colon.png
```

**存放位置**（按优先级）：
1. `/sdcard/Download/可可萝自动会战助手/templates/` — 用户自定义（优先使用）
2. `assets/templates/` — APK 内置默认模板

如果两个位置都有同名校验，工作目录的优先。

### 3. 校准坐标

启动助手 → 点击「校准」→ 用方向键移动红色十字准星到每个目标位置 → 确认 → 保存。

### 4. 放入轴文件

将你的轴文件（如 `d1顺序.txt`）放入 `/sdcard/Download/可可萝自动会战助手/`，启动助手后点「选轴」。

## 工作目录

```text
/sdcard/Download/可可萝自动会战助手/
  可可萝自动会战助手.txt    ← 配置文件（校准自动生成）
  d1顺序.txt               ← 轴文件
  d1开关.txt               ← 轴文件
  templates/               ← 自定义时钟模板（可选）
    0.png  ...  9.png
    colon.png
```

## 测试

PC 端纯逻辑测试：
```bash
node test/run_all.js
```

真机诊断：
在 AutoJS6 中加载 `test/device_test.js` 运行。

## 模块架构

```
main.js (50ms 主循环)
  ├── clock_recognizer    — 时钟识别（像素比对）
  ├── energy_detector     — 能量条 + UB 图标检测
  ├── game_state_detector — 三信号融合判定
  ├── recognition_filter  — 时间过滤
  ├── scheduler           — 事件调度
  ├── executor            — 动作执行
  ├── calibrator          — 坐标校准
  └── ui                  — 悬浮窗
```
