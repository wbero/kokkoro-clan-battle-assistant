# Kokkoro Clan Battle Assistant

[![Version](https://img.shields.io/badge/version-3.0.0-blue.svg)](#)

一个基于 Android 无障碍服务与屏幕识别的《公主连结！Re:Dive》（Princess Connect! Re:Dive）公会战辅助工具。通过实时捕获屏幕、识别战斗时钟与能量状态，按照玩家预设的“轴”自动执行点击操作，帮助稳定复现复杂的手动轴。

> ⚠️ **免责声明**
>
> 本项目仅供学习交流使用。请遵守游戏运营商的用户协议与当地法律法规。因使用本工具导致的账号封禁、数据丢失或其他纠纷，由使用者自行承担。

---

## 目录

- [项目结构](#项目结构)
- [功能特性](#功能特性)
- [角色库与 UB 数据](#角色库与-ub-数据)
- [环境要求](#环境要求)
- [快速开始](#快速开始)
- [使用说明](#使用说明)
- [更新日志](#更新日志)
- [贡献指南](#贡献指南)
- [许可证](#许可证)
- [致谢](#致谢)

---

## 项目结构

```text
.
├── android/      Android 原生应用源码（Kotlin + Gradle）
├── 素材/         角色别名、头像等离线生成素材
├── tools/        数据生成、日志分析与识别调试脚本
└── README.md
```

- **`android/`** — 完整 Android 项目，包含屏幕捕获、图像识别、战斗调度、无障碍操作等模块。
- **`android/app/src/main/assets/`** — APK 内置战斗识别模板、角色库 JSON 与角色头像。
- **`素材/角色数据.txt`** — 角色显示名、日文名、罗马音、昵称和常见外号的本地来源。
- **`tools/generate_character_library.py`** — 从国服主数据库生成离线角色库并补齐 UB / UB+ 名称。
- **`tools/`** — 同时包含时钟、UB 视频等诊断与基准分析脚本。

---

## 功能特性

- **实时屏幕识别**：基于结构 IoU 与自适应二值化的数字/按钮识别，适配多分辨率。
- **顺序轴执行**：严格按轴中动作顺序执行，支持时间点、角色 UB 后、BOSS UB 后、角色卡帧与 `卡帧=AUTO`。
- **顺序轴可视化速录**：直接选择五名角色，自动带入头像、别名和 UB 名；支持 SET、AUTO、UB 后、BOSS 后、卡帧、提示、预览、保存与另存为。
- **开关轴 + 可视化编辑**：按触发条件批量计算 SET/AUTO 差异并执行，支持卡帧和 BOSS UB 延迟。
- **能量检测**：识别角色能量条，支持 UB 触发判断。
- **UB 技能名识别**：可使用轴头记录的五名角色 UB 技能名判断实际释放角色，降低 TP 特效/SET 遮挡造成的误判。
- **离线角色库**：角色头像、昵称/外号、普通 UB 与六星 UB+ 均打包在 APK 内，运行时无需联网查询数据库。
- **战斗状态机**：追踪开局、战斗中、结算等阶段，自动准备下一场战斗。
- **悬浮窗控制**：提供开始/停止、轴选择等可视化入口。
- **独立卡帧悬浮窗**：与主面板解耦的纯悬浮窗卡帧工具，三档独立设置帧率与帧数，支持拖动、缩放、最小化，"恢复"按钮一键归还游戏焦点。不依赖截图识别服务。
- **诊断模式**：可选输出逐帧识别中间结果，便于调试与优化。

---

## 角色库与 UB 数据

顺序轴角色选择器使用 `android/app/src/main/assets/characters/character_library.json`。角色库由开发阶段离线生成，应用运行时不会访问在线数据库。

数据来源分工：

- `素材/角色数据.txt`：角色显示名、昵称、外号、日文名和罗马音。
- [SonderXiaoming/priconne-database](https://github.com/SonderXiaoming/priconne-database)：读取最新 `database-cn-*` 主数据库中的 `unit_data`、`unit_skill_data` 与 `skill_data`，补齐普通 UB / UB+。
- 角色头像优先使用本地素材，缺失时生成脚本可从角色资源站补齐并转为 APK 内置 WebP。

生成器以**国服当前可战斗数据**作为角色选择器的准入条件：只有能解析到有效普通 UB 的国服战斗单位才进入最终角色库。因此日服已实装但国服尚未实装的未来角色、NPC、仅存在立绘但未实装的造型不会混入国服制轴列表。

组合角色卡不按成员拆成多个角色。数据库中这类成员记录通过 `unit_data.original_unit_id` 指向共享战斗单位，生成器会自动合并成员别名并读取共享 UB。目前包括：

- 初音&栞
- 未奏希&美美&镜华（小小甜心）
- 秋乃&咲恋
- 安&古蕾雅
- 静流&璃乃

重新生成角色库：

```powershell
python tools/generate_character_library.py
```

如果 GitHub 访问需要本地代理，可显式传入（不会写死到项目中）：

```powershell
python tools/generate_character_library.py --proxy http://127.0.0.1:7890
```

生成完成后会输出角色数、普通 UB / UB+ 覆盖数、头像数以及被国服数据库或头像门槛排除的记录。

---

## 环境要求

- JDK 17
- Android SDK 35
- Android 设备或模拟器（Android 8.0+，API 26+）
- Gradle Wrapper（已包含在 `android/gradlew`）

本地 SDK 路径写入 `android/local.properties`，该文件不提交到 Git。

---

## 快速开始

1. 克隆仓库：

   ```bash
   git clone https://github.com/wbero/kokkoro-clan-battle-assistant.git
   cd kokkoro-clan-battle-assistant/android
   ```

2. 配置本地 SDK 路径（Windows）：

   ```powershell
   "sdk.dir=C:\\Users\\<your-name>\\AppData\\Local\\Android\\Sdk" | Out-File -Encoding utf8 local.properties
   ```

3. 运行单元测试：

   ```powershell
   .\gradlew.bat testDebugUnitTest
   ```

4. 构建 Debug APK：

   ```powershell
   .\gradlew.bat assembleDebug
   ```

5. 安装到已连接设备：

   ```powershell
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

Release 构建需要配置签名文件，详见 `android/app/build.gradle.kts` 中的签名配置。

---

## 使用说明

1. 安装并启动应用后，授予无障碍服务权限与悬浮窗权限。
2. 在轴列表中导入现有轴，或进入“可视化制作顺序轴 / 开关轴”创建新轴。
3. 制作顺序轴时，从角色库选择五名角色；已收录角色会自动填写普通 UB，拥有六星 UB+ 时可选择对应版本。
4. 通过快速录入加入 SET、AUTO、角色 UB 后、BOSS 后、角色/AUTO 卡帧等节点，预览标准轴文本后保存。
5. 进入游戏战斗界面，通过悬浮窗启动辅助。
6. （可选）开启诊断模式以导出识别中间结果，用于调试或算法优化。

---

## 更新日志

版本更新说明见 [CHANGELOG.md](CHANGELOG.md)。

---

## 贡献指南

欢迎提交 Issue 和 Pull Request！

### 提交 Issue

- 请尽量使用中文或英文描述问题。
- 如果是 Bug，请提供复现步骤、设备型号、Android 版本和日志。
- 如果是新功能建议，请说明使用场景和期望行为。

### 提交 Pull Request

1. Fork 本仓库。
2. 从 `master` 分支创建你的功能分支：`git checkout -b feature/your-feature-name`。
3. 提交你的修改：`git commit -m "feat: add some feature"`。
4. 推送到你的 Fork：`git push origin feature/your-feature-name`。
5. 在 GitHub 上提交 Pull Request，并简要说明修改内容。

### 代码规范

- Kotlin 代码遵循 [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)。
- 提交信息建议使用中文或英文，清晰描述修改目的。
- 新增功能请尽量补充单元测试。

---

## 许可证

本项目采用 [MIT 许可证](LICENSE) 开源。

```text
MIT License

Copyright (c) 2026 kokkoro-clan-battle-assistant contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 致谢

- 感谢《公主连结！Re:Dive》玩家社区分享的各类手动轴思路。
- 本项目图像识别部分参考并实践了计算机视觉中的经典方法。
- 欢迎所有贡献