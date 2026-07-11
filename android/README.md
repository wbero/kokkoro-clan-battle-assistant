# Android 原生版开发

## 环境

- JDK 17
- Android SDK 35
- Gradle Wrapper（项目内 `gradlew.bat`）

本机 SDK 路径写入 `local.properties`，该文件不提交到 Git。

## 常用命令

在 `android/` 目录执行：

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

Debug APK 输出到：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安装到当前 ADB 设备：

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## APK 资源

构建同时打包两个资源目录：

- `../assets/templates/`：时钟数字模板。
- `app/src/main/assets/battle/`：战斗开始与加载界面模板。

构建后应在 APK 中同时存在 `assets/templates/0.png` 和
`assets/battle/start_battle.bmp` 等资源。
# 时钟诊断记录

Debug 构建中可在主界面勾选“记录时钟诊断”（默认关闭）。开启后，应用会把逐帧识别结果、逐位打分和限速后的诊断 PNG 写入应用外部文件目录的 `clock-debug` 子目录。每次点击“准备新战斗”会开始一个新的时间戳 session。

连接设备后，可在 PowerShell 中导出全部诊断文件：

```powershell
adb pull /sdcard/Android/data/com.kokkoro.clanbattle/files/clock-debug/ .\clock-debug
```
