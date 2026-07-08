# 架构说明

程序按职责拆成独立模块：

```text
配置文件 / 作业轴
  -> config_parser / axis_parser
  -> 标准轴对象
  -> scheduler

截图
  -> clock_recognizer ──┐
  -> energy_detector ───┤
                        ├─> game_state_detector ──> scheduler ──> executor
  -> recognition_filter ┘
```

## 新增模块说明

### energy_detector
只做一件事：检测角色能量条区域的蓝色填充比例。
- 输入：截图 + 每个角色能量条的 ROI
- 输出：各角色 fillRatio、ubAvailable、帧间能量变化量、能量是否冻结
- 不了解轴事件、时间、坐标点击

### game_state_detector
将时钟识别 + 能量检测融合为统一的游戏状态。
- 输入：clockResult + energyResult
- 输出：GameState (RUNNING / UB_ANIMATION / UB_JUST_ENDED / CHARACTER_UB)
- **核心价值**：即使时间数字没变，也能通过能量条是否在增长判断 UB 动画是否结束

## 原有模块边界

`clock_recognizer` 只输出候选时间识别结果，不读取轴文件，也不执行点击。

`recognition_filter` 只决定识别结果是否可信，不知道轴事件内容。

`scheduler` 只消费标准事件对象和游戏状态，不知道截图、OCR、模板或坐标点击。
调度器现在会感知 GameState：
- UB_ANIMATION → 不调度，记录冻结时间
- UB_JUST_ENDED → 检查是否有冻结期间错过的事件并补执行
- CHARACTER_UB → 不调度轴事件，等待 UB 动画结束

`executor` 只消费动作对象并调用 AutoJS API，不解析文本，也不决定事件是否到点。
