# 项目状态摘要

更新时间：2026-07-15

## 项目与版本库

- 项目：可可萝自动会战助手，原生 Android/Kotlin 应用。
- 仓库：`C:\Users\wbero\Desktop\linai\kokkoro-clan-battle-assistant`
- 当前分支：`master`
- 当前提交：`a70a4b3 fix: show battle prompts in top overlay`
- 工作区存在未提交改动，不能清理、回退或覆盖这些文件。
- 已提交基线包含轴格式说明、应用内轴编辑、可视化开关轴编辑、战斗控制状态、暂停帧悬浮窗、动态 SET 识别优化及顶部战斗提示。

## 当前结论

以下两项仍是当前最高优先级，均未完成：

1. 跨分辨率识别与点击适配。
2. 启动录屏后自动完成新战斗初始化，不再要求手动点击“重置”。

现有代码已经包含这两项的初步实现和单元测试，但尚未完成端到端实机验证，不能视为可发布状态。

## 跨分辨率适配：进行中

目标是适配 16:9、20:9 等不同屏幕比例以及异形屏安全区域，同时让识别 ROI 和自动点击使用同一套坐标映射。

当前实现：

- 新增 `GameCoordinateMapper.kt`，以 1920×1080 为参考坐标。
- 缩放优先跟随高度，避免把超宽屏横向拉伸。
- 横向区域分为两类：
  - `CENTER`：五名角色、角色能量、战斗开始按钮等中心布局。
  - `RIGHT`：时钟、菜单、AUTO、全局 SET 等右侧布局。
- `ActionExecutor` 和 `ImageRoiExtractor` 已开始共用坐标映射。
- `FrameProcessor` 会尝试：
  - 用“战斗开始”模板小范围校准中心锚点。
  - 用菜单模板小范围校准右侧锚点。
  - 错过短暂加载画面时，以可信菜单和有效时钟作为进入战斗的兜底条件。
- 已新增 `GameCoordinateMapperTest`，并扩展 `ControlRegionTest` 的 2780×1264 测试。

仍未完成或未证明可靠的部分：

- 2780×1264 实机上尚未完整验证从队伍编组到战斗结束的连续流程。
- 右侧锚点校准、时钟识别、AUTO、全局 SET、角色 SET、能量检测尚未全部稳定通过。
- 识别坐标正确后，角色、AUTO、SET、菜单等实际点击位置仍需分别验证。
- 刘海、挖孔、黑边及厂商安全区域造成的偏移尚未形成可靠兜底。
- 必须回归 1920×1080，确认新映射没有破坏原有 16:9 识别和点击。
- 当前校准阈值、搜索半径和步长仍是实验值：中心 ±180px、右侧 ±120px、步长 12px、最低分数 0.55。
- `GameCoordinateCalibration` 是进程级可变状态，需要确认每场战斗重置时机和并发访问不会污染下一场。

## 录屏启动自动初始化：进行中

当前代码在成功创建新的 `MediaProjection` 后调用 `prepareNewBattle()`。该方法会重置：

- 坐标校准状态。
- 战斗会话门控。
- 识别过滤器、能量检测器和游戏状态检测器。
- 控制状态机和动作协调器。
- 当前选中轴及开场控制目标。

这只是初步修复，尚未完成：

- 必须实机确认首次授权并启动录屏后，无需再手动点击“重置”即可识别战斗开始按钮。
- 当前每次收到 `ACTION_START` 都会停止旧投屏、创建新投屏并调用 `prepareNewBattle()`；尚未防止重复 START 意外重置正在进行的战斗。
- 需要区分“新的截图授权”“服务重复启动”“屏幕旋转/尺寸变化”和“服务重建”，只在真正开始新捕获会话时初始化。
- 需要验证初始化顺序，确保所选轴、开场 SET/AUTO 目标、悬浮窗状态及调试会话均正确。
- 需要验证录屏授权取消、授权失效、应用切前后台和服务重启后的行为。

## 其他未提交实现

- 版本号改为 `1.0.0`，`versionCode` 改为 `10000`。
- Release 签名改为通过 `KOKKORO_KEYSTORE_PROPERTIES` 或 `-PkokkoroKeystoreProperties` 读取外部属性文件，密钥和密码不得写入仓库或输出日志。
- 已加入轻量启动背景、应用渐变背景、面板背景，以及设置页“关于”信息：版本和作者 `wbero`。
- Manifest 已声明 `com.bilibili.priconne` 包可见性，供“打开公主连结”按钮使用。
- `ScreenCaptureService` 已改为旋转时复用 `VirtualDisplay`，通过 `resize()` 和替换 Surface 更新尺寸，并捕获投屏授权失效的 `SecurityException`。

## 当前工作区文件

已修改：

- `android/app/build.gradle.kts`
- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/main/java/com/kokkoro/clanbattle/MainActivity.kt`
- `android/app/src/main/java/com/kokkoro/clanbattle/automation/ActionExecutor.kt`
- `android/app/src/main/java/com/kokkoro/clanbattle/capture/FrameProcessor.kt`
- `android/app/src/main/java/com/kokkoro/clanbattle/capture/ImageRoiExtractor.kt`
- `android/app/src/main/java/com/kokkoro/clanbattle/capture/ScreenCaptureService.kt`
- `android/app/src/main/res/values/styles.xml`
- `android/app/src/test/java/com/kokkoro/clanbattle/capture/ControlRegionTest.kt`

新增且未跟踪：

- `android/app/src/main/java/com/kokkoro/clanbattle/automation/GameCoordinateMapper.kt`
- `android/app/src/main/res/drawable/app_background.xml`
- `android/app/src/main/res/drawable/launch_background.xml`
- `android/app/src/main/res/drawable/panel_background.xml`
- `android/app/src/test/java/com/kokkoro/clanbattle/automation/GameCoordinateMapperTest.kt`
- `assets/templates/角色set图标会变大变小/`

`assets/templates/角色set图标会变大变小/` 是用户提供的识别素材，必须保留，不要删除，也不要默认纳入功能代码提交。

## 验证状态

- `android\gradlew.bat testDebugUnitTest`：通过。
- 当前测试结果：42 个测试套件、204 项测试、0 失败、0 错误、0 跳过。
- 单元测试通过不代表上述两项实机功能已经完成。
- 实机诊断素材位于 `android/device-*.png`，主要来自 2780×1264 的 realme 设备。
- 当前存在签名包 `dist/Kokkoro-Clan-Battle-Assistant-v1.0.0-signed.apk`，但由于跨分辨率和自动初始化仍未完成，该文件只能视为临时测试包，不能作为最终发布包。
- Android Gradle Plugin 8.5.2 对 `compileSdk = 35` 会输出兼容性警告，目前不阻塞构建。

## 下一步执行顺序

1. 先修正截图服务启动的幂等性，只在新授权/新捕获会话建立时调用 `prepareNewBattle()`，避免重复 START 重置战斗。
2. 实机验证启动录屏后无需手动重置，并覆盖取消授权、重复启动、前后台切换和旋转。
3. 在 2780×1264 实机完成完整跨分辨率回归：战斗开始、加载兜底、菜单、时钟、五名角色 SET、能量、AUTO、全局 SET 和点击坐标。
4. 使用 1920×1080 环境做回归，确保原识别与点击没有退化。
5. 根据实测结果调整锚点分类、校准阈值和安全区补偿，并补充对应单元测试。
6. 全部通过后重新构建并验证签名 APK，再整理并提交源代码；不要提交密钥、密码、临时截图或用户识别素材。

## 常用命令

```powershell
cd C:\Users\wbero\Desktop\linai\kokkoro-clan-battle-assistant\android
.\gradlew.bat testDebugUnitTest
```

签名构建时使用外部属性文件，不输出其中内容：

```powershell
$env:KOKKORO_KEYSTORE_PROPERTIES='C:\Users\wbero\Desktop\autopcr-android\keystore.properties'
.\gradlew.bat assembleRelease
```

## 2026-07-15 实机日志与分辨率测试补充

- 目标实机：RMX3800，ADB 序列号 `6deeada0`，物理分辨率 `1264×2780`。
- 已使用正式签名 Release APK 覆盖安装，未清除实机数据。
- Release 设置页已开放“记录识别诊断（时钟＋能量）”开关；诊断文件位于应用外部文件目录的 `clock-debug/session-*`。
- 实机有效日志会话 `session-20260715-211732-962` 显示：战斗开始模板命中 `0.8943`，`1:30` 被正确接受；`1:29` 置信度约 `0.7794`，原先因 `0.8` 门槛被拒绝。
- 已将 `FrameProcessor` 生产时钟识别和过滤门槛统一调整为 `0.75`，并增加 `RecognitionFilterTest` 覆盖 `1:30 → 1:29` 及禁止时间回升。全量 JVM 测试通过，Release 构建并安装成功。
- 当前复制的 MuMu 模拟器通过 ADB `127.0.0.1:5557` 连接；原 `127.0.0.1:5555` 已不是该实例。
- 当前模拟器底层手机配置为物理 `1080×1920`、DPI `480`，逻辑宽度上限约 `2160`。已验证手机预设 `1440×2560/640`、`1080×1920/480`、`900×1600/320`、`720×1280/320` 可设置，助手均可启动；测试后已恢复原始 `wm size`/`wm density`。
- 同一模拟器对 `2560/2800/3200/3440/3840` 横向请求会限制为约 `2160`，不能代表真实平板或超宽屏。需要在 MuMu 设置中切换设备类型并重启，或使用对应 AVD/真机后再做平板、超宽屏回归。
- MuMu 官方网页搜索暂未找到明确公开的命令行设备类型切换文档；不要把 `adb shell wm size` 当成手机/平板/超宽屏底层设备切换，它只修改当前实例的逻辑显示尺寸。
- 用户已将 MuMu 实例切换为平板并重启；同一 ADB 端口 `127.0.0.1:5557` 重新上线。平板模式真实物理尺寸为 `1440×2560`、DPI `360`，横屏当前显示区域为 `2560×1440`，确认这是底层设备类型切换而非单纯 `wm size` 覆盖。
- 已在该平板模式安装正式签名 APK 并启动助手；UI 根窗口正确适配横屏 `2560×1440`，无崩溃。尚未安装/运行游戏，因此平板战斗画面、时钟 ROI、控制点击还需用户启动游戏后继续验证。

## 2026-07-16：1920×1080 控制识别误暂停

- MuMu 平板实例已通过 `wm size` 切换为逻辑 `1920×1080`，DPI 保持 `326`；该 DPI 位于目标测试范围 `240～400`。
- 诊断会话 `session-20260715-234455-319` 中，时钟从 `1:30` 到 `1:23` 连续正确；`1:23` 置信度为 `1.0`，被过滤器正确接受为 83 秒。
- 真正异常发生在下一帧 `72398`：第五角色 SET 裁剪区域的青蓝像素覆盖率达到约 `0.64291`，超过 `FixedTemplateMatcher.animatedBadgeScore()` 的 `0.63` 开启阈值，因此蓝色战斗背景被误判为角色 SET 开启。下一帧覆盖率约 `0.62866`，误判随即消失。
- `BattleControlObservationFilter` 会把首次状态变化作为待确认帧并返回上一个稳定观察，同时标记 `trustworthy = false`；但 `FrameProcessor` 当前把这类单帧待确认与完全识别失败等同处理，立即调用 `forceSafety("control-recognition-failed")`。
- 安全状态随后自动点击菜单；菜单覆盖时钟区域后才出现 `1:22 / 1:24 / 1:11` 等低置信度读数。因此用户看到的“1:23 报错”不是时钟 OCR 错误，而是角色 SET 背景误判叠加过于激进的安全暂停策略。
- 待实施修复顺序：
  1. 控制观察短暂不可信时禁止派发新点击并保留稳定状态，不再单帧立即打开菜单；仅在连续不可信达到限定帧数后进入安全暂停。
  2. 区分“待确认状态变化”“原始观察未知/不一致”“裁剪/识别缺失”，避免把正常两帧确认窗口当作故障。
  3. 收紧角色 SET 开启判断，不能只依赖全区域青蓝覆盖率；加入预期位置、图标结构或模板证据，防止蓝色背景直接越过开启阈值。
  4. 补充单元测试，覆盖蓝色背景单帧误报、连续识别失败触发安全暂停、短暂不可信恢复后继续运行，以及真实 SET 动画仍能识别。

### 已实施

- `FilteredControlObservation` 已增加明确状态：可信、待确认、原始观察不可信、不合理跳变、裁剪/识别缺失。
- 新增 `ControlObservationSafetyGate`：不可信帧期间禁止派发新控制动作，连续 8 帧仍未恢复才进入安全暂停；任一可信帧会立即清零计数。
- `FrameProcessor` 不再把单帧 `PENDING_CONFIRMATION` 当成 `control-recognition-failed`；诊断中的 `stepReason` 会显示 `control-hold-<status>-<count>`，便于区分短暂抖动和持续故障。
- `FixedTemplateMatcher.animatedBadgeScore()` 已要求青蓝覆盖之外还必须具备 SET 模板结构证据。诊断中的第五角色误报帧结构分数仅约 `0.09`，修复后会被判为关闭，不会再因 `64.29%` 蓝色背景覆盖直接判定开启。
- 已增加过滤状态、安全门控和纯青蓝背景误报测试；完整 `testDebugUnitTest` 结果为 42 个测试套件、204 项测试、0 失败、0 错误、0 跳过。
- 正式签名 Release 已成功构建，并通过 ADB `127.0.0.1:5557` 使用 `install -r` 覆盖安装；版本仍为 `1.0.0` / `versionCode 10000`，应用数据未清除。
- 当前模拟器保持逻辑 `1920×1080`、DPI `326`，助手已启动。仍需用户重新授权录屏并复测经过 `1:23` 时是否保持运行，以及持续遮挡控制区域时是否在约 8 帧后正确安全暂停。

## 2026-07-16：跨分辨率与极宽屏压力测试

- 修复后的 `1920×1080 / DPI 326` 已由用户确认正常，原 `1:23` 单帧角色 SET 背景误判不再触发安全暂停。
- `2400×1080 / DPI 326` 第二次完整会话正常：战斗开始模板命中约 `0.9217`，时钟从 `1:30` 连续识别到 `1:06`，控制状态全程 `RUNNING`。首次测试只停在 `WAIT_START`，当次画面中未命中战斗开始按钮，不能据此判定该分辨率失败。
- `2600×1200 / DPI 326` 用户确认正常。
- `2800×1264 / DPI 326` 用户确认正常；诊断会话中战斗开始得分约 `0.8171`，时钟从 `1:30` 识别到 `0:55`，控制状态 1067 帧均为 `RUNNING`。期间 5 次单帧 `control-hold-raw_untrustworthy-1` 均自动恢复，没有安全暂停，说明新容错逻辑按预期工作。
- 随后已将 `2800×1264` 的 DPI 调整为 `450`，以覆盖目标范围 `430～460`。

### 极宽屏现状

- 用户要求测试非常规极宽屏；请求 `3200×900` 后 MuMu 将宽度限制为实际 `2880×900`，DPI 保持 `450`。当前模拟器仍处于 `2880×900 / DPI 450`，尚未切换到超高窄屏。
- 较早的极宽屏会话中，战斗开始模板命中约 `0.8496`，控制状态 449 帧均为 `RUNNING`，但时钟有 166 帧 `low-confidence`，只间断接受了 `1:30 → 1:03` 之间的 16 个时间点。
- 最新完整会话 `session-20260716-014117-656` 中，战斗开始模板最高约 `0.7508`，随后进入 `WAIT_CLOCK`，但没有任何时钟读数被过滤器接受：共有约 106 帧 `low-confidence`、42 帧 `clock-split-failed`；`1:04` 原始读数的置信度约 `0.59～0.66`，会话始终未进入 `RUNNING`。
- 用户肉眼观察到游戏内计时器本身的比例没有变化，位置也仍然合理，因此此前“极宽屏把数字非等比拉宽”只能视为已被否定的初步假设，不能作为结论。
- 当前更可信的方向是时钟 ROI、分位裁剪或右侧锚点存在细微偏差。相同 `1:04` 的数字诊断分数显示：
  - 正常 `2800×1264` 会话中，分钟约 `0.786`、秒十位约 `0.669～0.724`、秒个位约 `0.779`。
  - 极宽 `2880×900` 会话中，分钟约 `0.750`、秒十位约 `0.537～0.565`、秒个位约 `0.709～0.732`。
  - 主要退化集中在秒十位，符合局部裁剪边界或数字分割偏差，而不是整个计时器统一变形。
- 已把极宽会话帧 `695` 与正常会话帧 `1065` 的分钟、秒十位、秒个位裁剪拉取到本机临时目录 `C:\Users\wbero\AppData\Local\Temp\kokkoro-clock-compare\`，但尚未完成视觉对比，下一步应先检查这些裁剪是否存在边缘截断或位置偏移。

### TP/能量条现状

- 极宽屏下 TP 检测本身仍在运行。`session-20260716-014117-656/energy.csv` 有完整记录，末段五名角色比例约为 `39% / 42% / 14% / 77% / 88%`，说明能量 ROI 并非完全失效。
- 用户看到原始计时器文字持续变化、却看不到 TP，是当前状态显示门控造成的：`FrameProcessor` 在 `sessionGate.shouldEvaluate()` 为 false 时会显示原始 OCR 时间并提前返回，虽然已经执行并记录能量检测，但该早退分支没有调用 `EnergyStatusFormatter`，因此悬浮窗不显示 TP。
- 同时，未通过有效 `1:30` 门控时会话不会进入 `RUNNING`，TP 数据不会参与轴调度或角色触发判断。原始计时器在变化不等于时钟已被过滤器接受。
- 后续若修改显示逻辑，可在 `WAIT_CLOCK` 状态也展示 TP，同时明确标记“原始时间/等待有效 1:30”，避免把原始 OCR 更新误解为自动化已经进入运行状态。

### 近正方形压力测试与处理决定

- `1600×1600 / DPI 450` 会被现有 `image.width <= image.height` 横屏保护直接拒绝，悬浮窗持续显示“等待游戏横屏”。
- 为绕过该保护，已测试 `1601×1600 / DPI 450`。战斗开始按钮能够识别，典型最高得分约 `0.9045`；但加载界面始终未识别，连续停在 `WAIT_LOADING`，加载模板最高仅约 `0.3152`，低于 `0.72` 门槛。
- 根因是当前映射只有 `CENTER`、`RIGHT` 两种横向锚点，纵向统一按等比内容区居中。`1601×1600` 会把约 `1601×900` 的参考内容上下居中，产生约 350px 的上下留白；而游戏加载图标实际锚定整个屏幕右下角，因此加载 ROI 在纵向偏高约 350px。
- 当前目标分辨率均为约 `16:9～20:9` 横屏，按高度缩放时没有上述纵向留白。接近正方形主要代表异常窗口或少数折叠屏场景，不属于本次发布目标。
- 决定：将近正方形画面记录为已知限制，暂不增加 `TOP/BOTTOM` 纵向锚点，也不为该场景扩大实现和回归范围；继续优先验证清单中的正常横屏分辨率。

## 2026-07-16：v1.0.1 正式打包

- Android 版本已更新为 `versionName 1.0.1`、`versionCode 10001`。
- 完整 `testDebugUnitTest` 通过：42 个测试套件、204 项测试、0 失败、0 错误、0 跳过。
- 正式签名 `assembleRelease` 构建成功，APK 包名为 `com.kokkoro.clanbattle`，APK 内版本核验为 `1.0.1 (10001)`。
- APK Signature Scheme v2 验证通过；签名证书 SHA-256 与 v1.0.0 相同，可覆盖升级并保留应用数据。
- 发布产物：`dist/Kokkoro-Clan-Battle-Assistant-v1.0.1-signed.apk`，大小 `3653624` 字节。
- APK SHA-256：`23D52F623920B89DA14393BDBAB70B06DA740B054617CADC219B159F7A087475`。
- 本次只完成打包与本地验证，未主动覆盖安装到当前模拟器。
