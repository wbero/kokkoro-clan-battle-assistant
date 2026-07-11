# Kokkoro Android Native Roadmap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有 AutoJS6 会战助手迁移为可构建、可配置、失败即停且能在模拟器与真机稳定运行的原生 Android 应用。

**Architecture:** 保留“轴解析 → 调度 → 动作执行”和“截图 → 识别 → 时序过滤 → 游戏状态 → 调度”的模块边界。Android 使用 MediaProjection 获取画面，AccessibilityService 执行点击，前台 Service 管理会话，Overlay 提供游戏内控制；配置、识别与状态判断保持纯 Kotlin，优先通过 JVM 测试验证。

**Tech Stack:** Kotlin 1.9.24、Android SDK 35、minSdk 26、Java 17、MediaProjection、AccessibilityService、SYSTEM_ALERT_WINDOW、JUnit 4、Gradle 8.7。

---

## 1. 当前基线

### 已实现

- 原生 Android 工程骨架、Activity、MediaProjection 前台服务、无障碍服务和悬浮窗。
- `AxisParser → Scheduler → ActionExecutor` 主链路。
- `ClockRecognizer → RecognitionFilter` 时钟识别链路及 top2 候选恢复。
- `BattleSessionGate`：准备新战斗后依次等待开始模板、加载模板和 `1:30` 锚点。
- AxisParser、ClockRecognizer、RecognitionFilter、Scheduler、BattleSessionGate、FixedTemplateMatcher JVM 测试。

### 尚未形成产品闭环

- Android 运行时没有能量条和 UB 状态检测；`FrameProcessor` 始终向 Scheduler 传 `GameState.RUNNING`。
- 坐标和 ROI 使用 1920×1080 参考硬编码，没有迁移原计划的配置与校准能力。
- 轴解析错误会退化为空轴，用户可能看到识别正常但永不执行动作。
- `SET_ROLES` 尚未按目标开关状态执行，角色别名没有完整映射。
- 重新选择轴后，正在运行的 `FrameProcessor` 不会可靠热加载。
- Android 源码、测试和必要资源尚未形成可审查的 Git 基线；大量生成帧未被忽略。
- README、HANDOFF 和架构文档仍以 AutoJS 或混合实现为主。

### 范围

首个原生版本继续只支持整数秒轴，不加入通用 OCR、云服务、PC 伴侣、多游戏支持或亚秒级时间轴。AutoJS 版本在原生版完成验收前保留为参考和回退方案。

---

## 2. 目标文件结构

```text
android/app/src/main/java/com/kokkoro/clanbattle/
  axis/
    AxisModels.kt
    AxisParser.kt
    AxisValidator.kt                 # 新增：启动前严格校验
  automation/
    ActionExecutor.kt
    AutomationState.kt               # 新增：AUTO/角色目标状态
    KokkoroAccessibilityService.kt
  capture/
    BattleSessionGate.kt
    FrameProcessor.kt
    ScreenCaptureService.kt
    ImageRoiExtractor.kt
  config/
    AppPreferences.kt
    CalibrationConfig.kt             # 新增：坐标、ROI、阈值模型
    CalibrationConfigParser.kt       # 新增：兼容原 14 行配置
    CalibrationRepository.kt         # 新增：持久化和默认配置
  recognition/
    ClockRecognizer.kt
    RecognitionFilter.kt
    EnergyDetector.kt                # 新增：能量和 UB 图标信号
    GameStateDetector.kt             # 新增：三信号融合
  session/
    AutomationSession.kt             # 新增：会话状态和启动门禁
  overlay/
    OverlayController.kt
  MainActivity.kt

android/app/src/test/java/com/kokkoro/clanbattle/
  axis/AxisValidatorTest.kt
  automation/ActionExecutorTest.kt
  config/CalibrationConfigParserTest.kt
  recognition/EnergyDetectorTest.kt
  recognition/GameStateDetectorTest.kt
  session/AutomationSessionTest.kt
  capture/FrameProcessorTest.kt
```

职责约束：`FrameProcessor` 只编排一帧；`AutomationSession` 决定是否允许执行；`ActionExecutor` 不解析轴；识别模块不读取 Activity 或 Service 状态；所有无法信任的状态都必须阻止点击。

---

## Sprint 0：建立可重复构建的 Android 基线

**Goal:** 保护当前成果，并证明干净环境能运行 JVM 测试、生成和安装 debug APK。

**Demo/Validation:** `testDebugUnitTest` 与 `assembleDebug` 通过；APK 可安装启动；Git 状态不再显示视频帧和 Gradle 生成物。

### Task 0.1：收敛 Git 生成物

**Files:**
- Modify: `.gitignore`
- Verify: `test/video_frames_*`, `test/video_roi_*`, `android/.gradle/`, `android/app/build/`

- [ ] 在 `.gitignore` 增加 `android/.gradle/`、`android/**/build/`、`.idea/`、`*.iml`、视频抽帧目录、ROI 输出目录和临时设备截图规则。
- [ ] 保留 `test/fixtures/`、`test/native/` 中明确用于回归测试的最小样本，不使用 `test/**/*.png` 这种会吞掉夹具的宽泛规则。
- [ ] 运行 `git status --short`，预期只显示源码、计划、必要模板和精选测试夹具。
- [ ] 提交：`chore: establish android source baseline`。

### Task 0.2：固定构建入口

**Files:**
- Create: `android/gradlew`, `android/gradlew.bat`, `android/gradle/wrapper/gradle-wrapper.properties`, `android/gradle/wrapper/gradle-wrapper.jar`
- Create: `android/README.md`
- Modify: `android/app/build.gradle.kts`

- [ ] 使用本机 Gradle 8.7 在 `android/` 生成 Wrapper，版本必须与 Android Gradle Plugin 兼容。
- [ ] 修正 assets sourceSets，使根目录 `assets/templates/` 与 `android/app/src/main/assets/battle/` 同时打包；当前 `srcDirs("../../assets")` 可能覆盖默认 assets 目录。
- [ ] 增加构建期或 JVM 测试，明确验证 `templates/0.png`、`templates/9.png`、`battle/start_battle.bmp` 和 `battle/loading.bmp` 都能从 APK assets 打开。
- [ ] 在 `android/README.md` 记录 JDK 17、SDK 35、debug 构建和测试命令。
- [ ] 运行 `./gradlew.bat testDebugUnitTest`，预期所有现有 JVM 测试通过。
- [ ] 运行 `./gradlew.bat assembleDebug`，预期生成 `android/app/build/outputs/apk/debug/app-debug.apk`。
- [ ] 使用 ADB 安装并启动 APK，确认 Activity 可打开且不立即崩溃。
- [ ] 提交：`build: add reproducible android wrapper and build guide`。

---

## Sprint 1：安全启动、严格轴校验与热加载

**Goal:** 无效轴、缺权限或服务状态异常时绝不点击；有效轴能显示摘要并可靠进入运行会话。

**Demo/Validation:** 导入错误轴会显示准确行号；导入有效轴后服务无需重启即可使用新内容；dry-run 默认为开启。

### Task 1.1：增加严格轴校验器

**Files:**
- Create: `android/app/src/main/java/com/kokkoro/clanbattle/axis/AxisValidator.kt`
- Create: `android/app/src/test/java/com/kokkoro/clanbattle/axis/AxisValidatorTest.kt`
- Modify: `android/app/src/main/java/com/kokkoro/clanbattle/axis/AxisModels.kt`

- [ ] 先写失败测试，覆盖缺少 `[轴]`、未知轴类型、非法 `点击间隔`、空动作、未知角色、非法 AUTO 值、SET 数量不是 5、SET 出现非“开/关”、时间越界和重复事件 ID。
- [ ] 定义 `AxisValidationIssue(line: Int?, code: String, message: String)` 与 `AxisValidationResult`，保证 UI 可以显示行号和可读原因。
- [ ] 实现 `AxisValidator.validate(document)`，不得静默修正用户输入。
- [ ] 运行 `./gradlew.bat testDebugUnitTest --tests '*AxisValidatorTest'`，预期通过。
- [ ] 提交：`feat: validate axis documents before automation`。

### Task 1.2：让解析失败可见且失败即停

**Files:**
- Modify: `android/app/src/main/java/com/kokkoro/clanbattle/MainActivity.kt`
- Modify: `android/app/src/main/java/com/kokkoro/clanbattle/config/AppPreferences.kt`
- Modify: `android/app/src/main/java/com/kokkoro/clanbattle/capture/ScreenCaptureService.kt`
- Modify: `android/app/src/main/java/com/kokkoro/clanbattle/capture/FrameProcessor.kt`

- [ ] 导入轴时先解析和校验，只有成功后才写入 SharedPreferences。
- [ ] 保存轴内容、文件名和递增 `axisRevision`；服务检测 revision 变化后重建 Scheduler 和执行状态。
- [ ] 删除“异常时使用空轴继续运行”的路径，改为发布 `AXIS_INVALID` 状态并禁止执行。
- [ ] UI 展示轴类型、事件数量、首末时间和第一条错误信息。
- [ ] 增加测试证明运行中更换轴会 reset Scheduler，旧轴事件不会继续执行。
- [ ] 提交：`feat: fail closed and reload validated axes`。

### Task 1.3：集中启动门禁

**Files:**
- Create: `android/app/src/main/java/com/kokkoro/clanbattle/session/AutomationSession.kt`
- Create: `android/app/src/test/java/com/kokkoro/clanbattle/session/AutomationSessionTest.kt`
- Modify: `MainActivity.kt`, `ScreenCaptureService.kt`, `FrameProcessor.kt`

- [ ] 定义 `IDLE / WAITING_PERMISSIONS / WAITING_BATTLE / RUNNING / PAUSED / ERROR` 会话状态。
- [ ] 测试未选轴、无障碍未连接、悬浮窗未授权、截图未授权、配置无效和 dry-run/实点模式切换。
- [ ] 只有 `RUNNING` 且所有门禁成立时，`FrameProcessor` 才能调用 `ActionExecutor.execute()`。
- [ ] 所有错误状态清空待执行事件，并在 Activity、Overlay 和通知中显示同一错误原因。
- [ ] 提交：`feat: gate automation behind explicit session state`。

---

## Sprint 2：坐标配置与校准闭环

**Goal:** 移除关键硬编码，让同一 APK 能在至少两种横屏尺寸安全使用。

**Demo/Validation:** 可导入旧 14 行配置、预览 ROI、调整并持久化；重启后配置保持；所有坐标都经过边界校验。

### Task 2.1：迁移配置模型与旧格式解析

**Files:**
- Create: `config/CalibrationConfig.kt`
- Create: `config/CalibrationConfigParser.kt`
- Create: `config/CalibrationRepository.kt`
- Create: `config/CalibrationConfigParserTest.kt`

- [ ] 定义参考分辨率、5 个角色点、AUTO 点、时钟 ROI、能量 ROI、UB 图标 ROI、开始/加载模板 ROI 和阈值。
- [ ] 写失败测试覆盖少于 14 行、非整数、负坐标、零尺寸 ROI 和超出参考画布。
- [ ] 实现旧 14 行文本到 `CalibrationConfig` 的兼容解析；新增 Android 字段使用版本化默认值。
- [ ] 使用 JSON 或明确字段的 SharedPreferences 保存 `configVersion`，不得只保存不可演进的 14 行字符串。
- [ ] 提交：`feat: add versioned android calibration configuration`。

### Task 2.2：统一坐标缩放与边界保护

**Files:**
- Modify: `capture/ImageRoiExtractor.kt`
- Modify: `automation/ActionExecutor.kt`
- Modify: `capture/FrameProcessor.kt`
- Test: `config/CalibrationConfigParserTest.kt`, `automation/ActionExecutorTest.kt`

- [ ] 把当前散落的参考坐标改为从 `CalibrationConfig` 获取。
- [ ] 抽出纯函数 `scalePoint()` 和 `scaleRect()`，测试 1920×1080、2560×1440 和 letterbox 输入。
- [ ] 坐标或 ROI 越界时返回错误，不允许裁剪为未知区域或执行越界点击。
- [ ] 保留 reference-space 坐标，避免多次缩放产生累计误差。
- [ ] 提交：`refactor: drive capture and taps from calibration config`。

### Task 2.3：实现校准和 dry-run 预览

**Files:**
- Create: `android/app/src/main/java/com/kokkoro/clanbattle/calibration/CalibrationActivity.kt`
- Create: `android/app/src/main/res/layout/activity_calibration.xml`
- Modify: `AndroidManifest.xml`, `MainActivity.kt`, `OverlayController.kt`

- [ ] 提供时钟 ROI、5 个角色点和 AUTO 点的分项选择、方向微调、步长切换、保存和恢复默认。
- [ ] 截图预览必须显示当前 ROI/点位，且不触发无障碍点击。
- [ ] 在 dry-run 中记录“计划点击的参考坐标 → 实际坐标”，并显示在 Overlay 最近事件区域。
- [ ] 在 1920×1080 MuMu 和第二种横屏尺寸完成手工验收并保存截图证据。
- [ ] 提交：`feat: add native calibration and coordinate preview`。

---

## Sprint 3：原生时钟打轴 MVP

**Goal:** 从新战斗边界开始，按游戏整数秒可靠执行角色、AUTO 和提示事件。

**Demo/Validation:** 一条短轴从 `1:30` 开始运行；跳秒能补执行；同秒不会重复；暂停和停止后绝不点击。

### Task 3.1：修正动作语义

**Files:**
- Create: `automation/AutomationState.kt`
- Create: `automation/ActionExecutorTest.kt`
- Modify: `axis/AxisModels.kt`, `axis/AxisParser.kt`, `automation/ActionExecutor.kt`

- [ ] 用纯函数把角色名和别名解析为固定角色槽位，未知别名在启动前报错。
- [ ] 追踪 AUTO 已知状态；只有目标状态与已知状态不同时才点击，未知时明确降级并记录警告。
- [ ] `SET_ROLES` 按五个目标开关值和当前状态生成最少点击集合，不得无条件点击五人。
- [ ] 测试 CLICK_ROLE、CLICK_AUTO、TOGGLE_AUTO、NOTIFY、BOSS、SET_ROLES、dry-run 和无障碍失联。
- [ ] 提交：`fix: execute axis actions with tracked target state`。

### Task 3.2：完成游戏内控制面板

**Files:**
- Modify: `overlay/OverlayController.kt`
- Modify: `MainActivity.kt`, `ScreenCaptureService.kt`

- [ ] Overlay 显示轴名、识别时间、会话状态、最近事件、最近警告和单帧耗时。
- [ ] 提供开始/暂停/停止/准备新战斗按钮；停止必须清空 Scheduler、BattleSessionGate 和待执行动作。
- [ ] 打开文件和完整校准保留在 Activity，Overlay 只提供快速控制，避免游戏上方窗口过大。
- [ ] 增加防抖，重复点击开始或准备不会创建多个服务或多个执行会话。
- [ ] 提交：`feat: complete in-game automation controls`。

### Task 3.3：端到端 MVP 验收

**Files:**
- Create: `docs/android-mvp-acceptance.md`
- Add: 精选的验收截图和日志，不提交完整视频抽帧目录

- [ ] 使用只含提示的轴验证识别、调度和 UI，不执行真实点击。
- [ ] 使用 dry-run 验证角色/AUTO 坐标、文件顺序、同秒顺序和跳秒补执行。
- [ ] 在测试关卡启用实点模式，验证每个事件仅执行一次。
- [ ] 遮挡时钟 ROI，确认达到连续失败阈值后会话进入 PAUSED/ERROR 并停止点击；把 `maxFailedReads=999` 改为可配置的安全值。
- [ ] 提交：`test: document native clock-axis mvp acceptance`。

---

## Sprint 4：迁移能量与 UB 状态融合

**Goal:** Android 调度真正感知角色 UB、Boss UB 和解冻，不再把每帧都当作 RUNNING。

**Demo/Validation:** 录像夹具覆盖普通倒计时、角色 UB、Boss UB、连续 UB、同秒停留和解冻跳秒，均不重复或漏触发事件。

### Task 4.1：实现能量与图标检测

**Files:**
- Create: `recognition/EnergyDetector.kt`
- Create: `recognition/EnergyModels.kt`
- Create: `recognition/EnergyDetectorTest.kt`
- Modify: `config/CalibrationConfig.kt`

- [ ] 从 JS `src/energy_detector.js` 移植蓝色占比、图标亮度、帧间变化和 iconDropped 逻辑，保持纯 PixelImage 输入。
- [ ] 阈值和 5 组 ROI 来自 CalibrationConfig，测试中使用固定图片夹具和合成像素图。
- [ ] 输出每个角色的 fillRatio、iconBright、ubConfirmed、iconDropped，以及全局 energyChanging/frozenFrameCount。
- [ ] 在 50ms 处理间隔下重新定义冻结阈值，不直接沿用 JS 200ms 主循环的 60 帧值。
- [ ] 提交：`feat: detect energy and ub icon state on android`。

### Task 4.2：实现三信号 GameStateDetector

**Files:**
- Create: `recognition/GameStateDetector.kt`
- Create: `recognition/GameStateDetectorTest.kt`
- Modify: `scheduler/Scheduler.kt`

- [ ] 移植并测试 RUNNING、UB_ANIMATION、CHARACTER_UB、UB_JUST_ENDED 状态转换。
- [ ] 明确“时钟未变但能量增长”为 RUNNING，“时钟与能量冻结”为 UB_ANIMATION，“图标掉亮/能量恢复”为 UB_JUST_ENDED。
- [ ] 测试连续角色 UB 接 Boss UB、低置信时钟但能量恢复、长时间静止和误检测恢复。
- [ ] Scheduler 只消费状态与可信时间，不直接依赖像素指标。
- [ ] 提交：`feat: fuse clock and energy signals into game state`。

### Task 4.3：接入 FrameProcessor 与录像回归

**Files:**
- Modify: `capture/FrameProcessor.kt`
- Create: `capture/FrameProcessorTest.kt`
- Create: `docs/android-ub-regression.md`

- [ ] 每帧依次执行时钟识别、能量识别、状态融合、时间过滤、Scheduler 和 ActionExecutor。
- [ ] 测试 UB_ANIMATION 不执行、UB_JUST_ENDED 补执行冻结区间、同秒不重复和错误帧失败即停。
- [ ] 从现有视频输出中精选小型 ROI 序列作为测试夹具，不把整套 1GB 抽帧纳入 Git。
- [ ] 对每类录像输出 accepted clock、GameState、due event 的 CSV/日志，形成可审查基线。
- [ ] 提交：`feat: run ub-aware scheduling in frame processor`。

---

## Sprint 5：生命周期、性能与真机稳定性

**Goal:** 权限变化、旋转、后台切换和长时间运行时都保持可控，任何异常都停止点击并解释原因。

**Demo/Validation:** MuMu 与至少一台真机各运行 20–30 分钟，无 OOM/ANR；投影或无障碍失效后零额外点击。

### Task 5.1：补齐服务生命周期错误路径

**Files:**
- Modify: `ScreenCaptureService.kt`, `KokkoroAccessibilityService.kt`, `AutomationSession.kt`, `MainActivity.kt`
- Create: `android/app/src/androidTest/.../CaptureLifecycleTest.kt`

- [ ] 覆盖截图拒绝、MediaProjection 被系统停止、通知权限拒绝、悬浮窗权限撤销、无障碍失联、游戏退后台和屏幕旋转。
- [ ] 所有回调统一进入 `PAUSED` 或 `ERROR`，关闭 Image、清空动作队列并更新通知/Overlay。
- [ ] 不自动恢复实点模式；用户必须再次确认开始或准备新战斗。
- [ ] 提交：`fix: fail closed across android lifecycle interruptions`。

### Task 5.2：建立性能预算

**Files:**
- Modify: `FrameProcessor.kt`, `ScreenCaptureService.kt`
- Create: `docs/android-performance.md`

- [ ] 记录 capture、ROI 提取、时钟识别、能量识别、状态判断和总帧耗时。
- [ ] 保持 latest-image 策略；处理未结束时丢弃旧帧，不排队积压 Image。
- [ ] 目标：目标设备平均单帧处理低于 50ms 或主动降采样到可持续频率，内存曲线不持续增长。
- [ ] 运行 20–30 分钟长测，记录峰值内存、平均/95 分位耗时、丢帧率和异常次数。
- [ ] 提交：`perf: bound capture processing and document budgets`。

### Task 5.3：完整真机验收矩阵

**Files:**
- Create: `docs/android-device-matrix.md`

- [ ] 至少覆盖 MuMu 1920×1080 和一台 Android 8+ 真机。
- [ ] 每台设备验证安装、通知、截图、悬浮窗、无障碍、导入轴、校准、dry-run、实点、新战斗重置、UB 和异常恢复。
- [ ] 每项记录 APK 版本、设备、系统版本、分辨率、结果和证据路径。
- [ ] 未通过项必须关联具体复现步骤，不允许只写“偶现”。
- [ ] 提交：`test: add android device acceptance matrix`。

---

## Sprint 6：发布准备与文档切换

**Goal:** 可重复生成 release APK，并让新用户只看文档就能完成安装、授权、校准和首次 dry-run。

**Demo/Validation:** 从干净 checkout 构建 release；升级安装保留配置；卸载或回滚步骤明确。

### Task 6.1：版本、签名和 release 构建

**Files:**
- Modify: `android/app/build.gradle.kts`, `android/app/proguard-rules.pro`
- Create: `docs/android-release.md`

- [ ] 建立不提交密钥的签名配置，敏感值只从环境变量或本地 properties 读取。
- [ ] 运行 release 单测、assembleRelease 和安装验证；若启用 R8，验证模板加载和反射相关代码。
- [ ] 定义 versionCode/versionName、升级兼容与配置迁移规则。
- [ ] 输出 APK SHA-256 和构建环境信息。
- [ ] 提交：`build: prepare signed android release workflow`。

### Task 6.2：同步用户和开发文档

**Files:**
- Modify: `README.md`, `HANDOFF.md`, `docs/architecture.md`, `docs/calibration.md`, `docs/axis-format.md`
- Create: `docs/android-troubleshooting.md`

- [ ] README 以原生 Android 为推荐路径，AutoJS 标为参考/回退实现。
- [ ] 架构图反映 AutomationSession、BattleSessionGate、EnergyDetector 和 GameStateDetector。
- [ ] 写清四类权限的用途、失败表现和恢复方式。
- [ ] 提供“首次安装 → 导入轴 → 校准 → dry-run → 实点”的最短成功路径。
- [ ] 提交：`docs: make native android the primary product path`。

### Task 6.3：发布门禁

- [ ] JVM 测试、instrumentation 测试和 debug/release 构建全部通过。
- [ ] P0 设备矩阵没有阻断项。
- [ ] 无效轴、识别失败、权限丢失和服务异常均已证明不会点击。
- [ ] 真实战斗中角色、AUTO、提示、Boss/角色 UB 和新战斗边界均有验收证据。
- [ ] Git 工作区不包含生成帧、密钥、设备隐私截图或构建产物。
- [ ] 创建版本 tag 和发布说明；在原生版本完成至少一轮稳定实战前不删除 AutoJS 回退实现。

---

## 3. 测试策略

### JVM 单元测试

- 解析与校验：合法/非法轴、旧配置兼容、坐标缩放。
- 识别：数字模板、top2 候选、置信度、能量与图标状态。
- 状态：新战斗门禁、时序过滤、UB 状态转换、会话门禁。
- 调度与执行：跳秒补轴、事件幂等、冻结/解冻、AUTO/SET 状态、dry-run。

运行：

```powershell
cd android
.\gradlew.bat testDebugUnitTest
```

### Android 集成测试

- Activity 导入文件和错误展示。
- Service 启停、MediaProjection 回收、旋转重建。
- Overlay 控制与状态同步。
- AccessibilityService 失联时的 fail-closed 行为。

### 录像与真机回归

- 录像用于确定性复现识别和 UB 状态。
- 真机用于验证权限、截图方向、窗口层级、点击坐标、性能与生命周期。
- 测试资产只提交最小 ROI 序列和结果清单；原始长视频留在外部测试资料目录。

---

## 4. 优先级与依赖

- **P0：** Sprint 0–3。完成后得到安全可用的整数秒时钟打轴 MVP。
- **P1：** Sprint 4–5。完成后达到原设计中的 UB 感知和稳定运行目标。
- **P2：** Sprint 6。完成后具备可分发和可维护性。

关键依赖：严格轴校验必须先于真实点击；配置化必须先于多设备验收；能量/UB 状态必须先于宣称 Scheduler 的冻结能力已交付；release 必须等待真机矩阵通过。

允许并行：Sprint 1 的 AxisValidator 与 Sprint 2 的配置解析可并行；Sprint 4 的 EnergyDetector 与 GameStateDetector 测试设计可并行。`FrameProcessor`、`ScreenCaptureService` 和 `OverlayController` 属于共享热点文件，实施时避免多任务同时编辑。

---

## 5. 风险与缓解

- **识别在不同设备漂移：** 使用版本化配置、reference-space 缩放、ROI 预览和两设备验收，不假设所有 16:9 屏幕像素一致。
- **MediaProjection/Overlay 生命周期复杂：** 所有错误统一进入 AutomationSession，默认暂停而不是自动恢复点击。
- **AUTO/角色实际状态不可观测：** 首版记录“已知/未知”状态；未知时提示用户并使用明确的降级策略，不伪装成可靠 toggle。
- **测试资产膨胀：** Git 只保存精选夹具、CSV 和验收截图，原始视频和批量帧不入库。
- **Android 与 AutoJS 行为分叉：** 以共享录像、轴样例和状态转移用例作为行为合同，文档明确原生版为主实现。
- **50ms 处理频率导致发热或 OOM：** latest-image、主动节流、分阶段耗时指标和长跑内存门禁必须在发布前通过。

---

## 6. 回滚方案

- 每个 Task 独立提交，禁止把配置、识别、UI 和发布改动压成单个大提交。
- 新功能通过 dry-run 和会话门禁接入；出现回归时可关闭实点而保留识别诊断。
- 配置结构必须带版本，升级失败时保留旧值并回退默认配置，不覆盖用户文件。
- 原生 Android 稳定版本发布前保留 AutoJS 源码、模板和运行说明。
- Release 回滚使用上一版本 APK 和对应 tag；若 Android 配置发生不可逆迁移，发布前必须提供导出/恢复路径。

---

## 7. 完成定义

项目只有同时满足以下条件，才可从“迁移中原型”标记为“原生 Android 首版完成”：

1. 干净 checkout 可重复构建 debug/release APK。
2. 所有 JVM 和必要的 Android 集成测试通过。
3. 无效轴、权限缺失、识别失败和服务异常均不会产生点击。
4. 至少两种设备环境完成端到端验收。
5. 时钟、能量、UB、调度和执行链路在录像与真机上均有证据。
6. README、架构、校准、排障和发布文档与实际实现一致。
7. Git 中没有生成帧、构建缓存、签名密钥或未审查的大型二进制文件。
