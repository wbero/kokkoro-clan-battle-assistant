# 时钟识别离线基线

本工具用于把 Android `clock-debug` 导出转成可重复分析的数据集和人工复核清单。它不修改 Android 运行时识别逻辑。

## 重要限制

`filterTime` 只作为 **weak label（弱标签）**，不是 ground truth。过滤器本身会发生 `19→18`、`06→05` 等误判，因此文中的 agreement 只能表示 `rawTop1` 与过滤器输出的一致率，不能称为真实准确率。

只有填入人工 `labels.csv` 后，`manual_only` 混淆矩阵才可作为可靠回归指标。

## 使用方法

```powershell
python tools\clock_debug_baseline.py `
  android\clock-debug-pull-2026-07-12\clock-debug `
  --output android\clock-debug-baseline-output
```

工具只依赖 Python 标准库。输出目录和原始诊断目录均已由 `.gitignore` 排除。

输出文件：

- `summary.json`：session、帧、丢弃、Gate、Filter、识别原因及 margin 汇总。
- `raw_top1_confusion.csv`：行是真值/弱标签，列是 `rawTop1`，同时输出 weak+manual 与 manual-only 两套矩阵。
- `score_means.csv`：按标签数字统计生产决策分数的平均值。新版诊断读取 `decision0..decision9`（结构 IoU），并继续兼容旧版 `s0..s9`（NCC）导出。
- `margin_stats.csv`：整体和各标签数字的 margin 分布。
- `transition_intervals.csv`：自动发现的 `n+1 → n-1` 跳秒区间。
- `review_manifest.csv`：过渡区间及重点数字 `0/3/5/6/8/9` 的低 margin、错配样本。
- `labels_template.csv`：供人工填写的标签模板。

人工标签格式：

```csv
session,frameId,slot,truth,note
session-20260712-002615-859,380,SECOND_ONES,6,人工查看裁剪图确认
```

填写后重新运行：

```powershell
python tools\clock_debug_baseline.py <clock-debug目录> `
  --labels <labels.csv> `
  --output android\clock-debug-baseline-output
```

人工标签按 `(session, frameId, slot)` 精确覆盖 weak label。建议先从 `review_manifest.csv` 中的 `n+1_to_n-1_transition` 和 `focus_digit_mismatch` 开始标注，并把调参集和最终验证集分开保存。

标签输入采用严格校验：

- 显式传入的 `--labels` 文件不存在时，命令以退出码 2 失败。
- 同一 `(session, frameId, slot)` 出现互相冲突的 truth 时失败。
- 默认情况下，找不到对应数字记录的孤儿标签会失败，避免标签静默失效。
- 只有明确需要审计孤儿标签时才使用 `--allow-unmatched`；此时 `summary.json` 会在 `warnings` 中提示，并分别报告 `manual_labels_loaded/applied/unmatched`。

## 2026-07-12 两次运行基线

输入包含 4 个 session，其中 2 个有完整战斗数据，另有 1 个短竖屏 session 和 1 个空 session：

| 指标 | 数值 |
|---|---:|
| 帧数 | 8,896 |
| 数字记录 | 21,990 |
| 识别成功帧 | 6,003 |
| Filter 新接受时间帧 | 143 |
| 具有 `filterTime` weak label 的数字 | 20,856 |
| 未标注数字 | 1,134 |
| 诊断队列丢弃 | 0 |
| `n+1 → n-1` 区间 | 21 |
| 人工复核候选 | 6,751 |

`rawTop1` 对 weak label 的整体 agreement 为 **90.69%（18,914 / 20,856）**。这不是准确率；当前人工标签数为 0。

已量化的系统性混淆：

| weak label | 样本 | rawTop1 正确 | 主要误判 |
|---:|---:|---:|---|
| 0 | 6,966 | 6,706 | `0→9`: 260 |
| 3 | 1,373 | 742 | `3→8`: 631 |
| 5 | 2,544 | 1,872 | `5→0`: 672 |
| 8 | 785 | 785 | 当前 weak set 中无误判 |
| 9 | 379 | 0 | `9→0`: 379 |
| 6 | 0 | 0 | Filter 从未输出 6，暴露 weak label 的选择偏差 |

重点数字的 raw margin 中位数：`0=0.0508`、`3=0.0777`、`5=0.1279`、`8=0.1459`、`9=0.0769`。完整 0–9 分数均值位于生成的 `score_means.csv`。

最关键的结果是：weak label 中完全没有数字 6，说明不能用 Filter 输出直接评估 `6→0/5`。必须人工标注过渡区间中的 6 样本，之后才可比较二值化、IoU 和组合权重。

## 后续回归规则

1. 不用 weak agreement 宣称算法准确率提升。
2. 先人工标注重点混淆对，保留独立验证集。
3. 新旧算法使用同一份 manual labels 比较。
4. 分别报告单帧评分与时序过滤结果，避免 DP 掩盖单帧缺陷。
