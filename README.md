# Kokkoro Clan Battle Assistant

一个基于 Android 无障碍服务与屏幕识别的《公主连结！Re:Dive》（Princess Connect! Re:Dive）公会战辅助工具。通过实时捕获屏幕、识别战斗时钟与能量状态，按照玩家预设的“轴”自动执行点击操作，帮助稳定复现复杂的手动轴。

> ⚠️ **免责声明**
>
> 本项目仅供学习交流使用。请遵守游戏运营商的用户协议与当地法律法规。因使用本工具导致的账号封禁、数据丢失或其他纠纷，由使用者自行承担。

---

## 目录

- [项目结构](#项目结构)
- [功能特性](#功能特性)
- [环境要求](#环境要求)
- [快速开始](#快速开始)
- [使用说明](#使用说明)
- [贡献指南](#贡献指南)
- [许可证](#许可证)
- [致谢](#致谢)

---

## 项目结构

```text
.
├── android/      Android 原生应用源码（Kotlin + Gradle）
├── assets/       识别所需的数字、按钮模板等资源
├── tools/        调试与基准分析脚本
└── README.md
```

- **`android/`** — 完整 Android 项目，包含屏幕捕获、图像识别、战斗调度、无障碍操作等模块。
- **`assets/templates/`** — 时钟数字模板（`0-9.png`）、战斗控制按钮模板（auto、set、menu 等）。这些资源在构建时会被打包进 APK。
- **`tools/`** — 当前包含时钟诊断数据的基准分析脚本，用于对比不同识别算法或参数的效果。

---

## 功能特性

- **实时屏幕识别**：基于结构 IoU 与自适应二值化的数字/按钮识别，适配多分辨率。
- **顺序轴执行**：按时间线执行预先编写的操作序列。
- **开关轴 + 卡帧**：根据屏幕状态切换分支，并在特定帧执行精确操作。
- **能量检测**：识别角色能量条，支持 UB 触发判断。
- **战斗状态机**：追踪开局、战斗中、结算等阶段，自动准备下一场战斗。
- **悬浮窗控制**：提供开始/停止、轴选择等可视化入口。
- **诊断模式**：可选输出逐帧识别中间结果，便于调试与优化。

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
   git clone https://github.com/<your-username>/kokkoro-clan-battle-assistant.git
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
2. 在设置中导入或编辑你的“轴”文件。
3. 进入游戏战斗界面，通过悬浮窗启动辅助。
4. （可选）开启诊断模式以导出识别中间结果，用于调试或算法优化。

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