# 架构说明

## 数据流

```
截图
  ├── clock_recognizer ──┐
  └── energy_detector ───┤
                          ├─> game_state_detector ──> scheduler ──> executor
配置文件 / 作业轴          │
  ├── config_parser ──────┤
  └── axis_parser ────────┘
      -> 标准轴对象 ──────> scheduler
```

## 模块边界

| 模块 | 输入 | 输出 | 不知道 |
|------|------|------|--------|
| clock_recognizer | 截图 + timerRegion + 数字模板 | `{ok, timeSeconds, rawText, confidence}` | 轴事件、坐标点击 |
| energy_detector | 截图 + 能量条 ROI + UB图标 ROI | 每角色 fillRatio, iconBright, ubConfirmed, iconDropped | 轴事件、时间 |
| game_state_detector | clockResult + energyResult | GameState 枚举 | 轴事件、坐标 |
| recognition_filter | clockResult | `{accepted, timeSeconds, shouldPause}` | 轴事件 |
| scheduler | 轴事件数组 + GameState + clockSeconds | 当前应执行的事件列表 | 截图、像素、坐标 |
| executor | 动作对象 + runtimeContext | AutoJS 点击/提示 | 时间、轴格式 |

---

## Boss UB 与角色 UB 处理设计

### 核心问题

Boss UB 动画和角色 UB 动画都会**暂停游戏内计时器**。旧方案（录制毫秒时间轴）在此刻彻底失效——现实时间在走但游戏时间是冻结的，回放必然跑偏。

新方案通过**三个信号融合**解决：

### 信号源

| 信号 | 来源 | 含义 |
|------|------|------|
| 时钟数字是否跳动 | clock_recognizer | 游戏时间在走？ |
| 能量条蓝色占比是否变化 | energy_detector (帧间对比) | 游戏逻辑在跑？ |
| UB 图标是否灭掉 | energy_detector (亮度骤降) | 某个角色刚放完 UB？ |

### 关键洞察

**时间数字不变 ≠ UB 动画中。**

Boss UB 动画结束后，游戏时间可能仍然显示同一秒（因为还没跳到下一秒）。此时时钟不动但能量条已经恢复增长——只有同时看能量条才能判断"UB 真的结束了"。

### 状态判定矩阵

```
┌─────────────────┬──────────────┬─────────────────┬────────────────────┐
│ 时钟数字        │ 能量条        │ UB 图标         │ GameState           │
├─────────────────┼──────────────┼─────────────────┼────────────────────┤
│ 变了            │ 任意         │ 任意            │ RUNNING            │
│ 没变            │ 在涨         │ 任意            │ RUNNING (即将跳秒) │
│ 没变            │ 冻结         │ 没变化          │ UB_ANIMATION       │
│ 没变            │ 冻结超过3秒  │ 没变化          │ UB_ANIMATION       │
│                 │              │                 │ (Boss UB 候选)     │
│ 没变            │ 恢复增长     │ 任意            │ UB_JUST_ENDED      │
│ 任意            │ 角色满→空    │ 任意            │ CHARACTER_UB       │
│ 任意            │ 任意         │ 图标刚灭        │ UB_JUST_ENDED      │
└─────────────────┴──────────────┴─────────────────┴────────────────────┘
```

### 实现位置

- `game_state_detector.js`: 三信号融合，输出 GameState
- `scheduler.js`: 感知 GameState 调度
  - UB_ANIMATION → 冻结调度，记录 frozenClock
  - CHARACTER_UB → 冻结调度
  - UB_JUST_ENDED → 解冻，补执行冻结期间错过的事件
- `energy_detector.js`: 帧间对比能量变化 + UB 图标亮度追踪

### frozenFrameCount 机制

能量条持续冻结的帧数。50ms 一帧，冻结超过 60 帧（3 秒现实时间）标记为 `bossUbCandidate`。

区分场景：
- 角色 UB 链：单次 UB 动画约 1-2 秒，冻结 < 60 帧
- Boss UB：动画通常 3-5 秒，冻结 > 60 帧
- 角色 UB 无缝衔接 Boss UB：冻结持续时间长但 UB 图标有变化

### 与 MaaPcrclanbattle 的对比

| 场景 | MaaPcrclanbattle (PC) | 可可萝助手 (真机) |
|------|----------------------|-------------------|
| Boss UB 等待 | 手动延迟 `d` 命令 | 能量条冻结检测自动判断 |
| 目押 | `k` 命令人工确认 | 能量解冻自动恢复 |
| 角色 UB 完成 | inverse TemplateMatch mfd.png | 能量骤降 + 图标亮度骤降 |
| UB 就绪 | ColorMatch + TemplateMatch aub.png | 蓝色占比 + 图标亮度双层确认 |

MaaPcrclanbattle 的 Boss UB 处理依赖用户手动估计延迟时间。我们的方案通过能量条冻结检测实现自动化。

---

## UB 图标双层确认

借鉴 MaaPcrclanbattle 思路，但全部用像素颜色替代 TemplateMatch（真机性能要求）。

```
能量条蓝色占比 >= 85%  →  ubAvailable = true
UB 图标区域亮像素 >= 70% →  iconBright = true
两者同时满足            →  ubConfirmed = true (双层确认)
UB 图标亮度骤降 60%      →  iconDropped = true (UB 已释放)
```

所有检测在 40×6（能量条）和 25×20（UB图标）的小 ROI 内完成，2px 抽样，单次检测 < 5ms。

---

## 调度器逻辑

### 正常运行
- 首次接受时间：只执行精确匹配当前秒的事件
- 后续：执行 (上次时间, 本次时间] 区间内所有未执行事件
- 同一秒内不重复触发

### UB 动画期间
- 冻结调度，记录 frozenClock（冻结时的时间）
- 不执行任何事件

### 解冻后
- frozenClock > currentClock：时间跳了 → 补执行区间内全部事件
- frozenClock == currentClock：时间没跳但能量恢复了 → 检查当前秒未执行事件
- frozenClock < currentClock：异常，不处理

---

## 执行器动作类型

| 动作 | 说明 |
|------|------|
| CLICK_ROLE | 点击角色头像 |
| CLICK_AUTO | 点击 AUTO 按钮 |
| TOGGLE_AUTO | 切换 AUTO（追踪开关状态，避免盲点） |
| NOTIFY | 弹出提示消息 |
| BOSS | Boss UB 通知 |
| WAIT_BOSS_UB | 等待 Boss UB（调度器冻结机制处理） |
| RELEASE_UB | 自动释放角色 UB |
| setRoles | 开关轴批量切换角色 SET 状态 |
