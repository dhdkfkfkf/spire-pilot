# GRID 选牌界面多一次确认 — 调查留档

> **状态：挂起。** 已有运行期真值表和一组嫌疑 mod，但**没有玩家的现场环境**（mod 名、触发卡/遗物、
> 界面截图、导出报告），所以不写修复、不改代码。本文是证据链与恢复入口，供以后任何一次会话继续。
>
> 触发来源：2026-11-20 创意工坊的一条玩家留言，原文：
> 「mod 有自定义选牌界面 GRID 时，自动战斗中还需要点一次确定，这点能否更新优化一下」。
> 玩家此后没有再提供信息。

## 1. 已经证明的事实

用合成战斗在真实引擎里逐形态构造 GRID 界面，记录求解器看到的类型与能否自动填/关。
门禁 S279 / S280，聚焦 24/0、全 mod 24/0（2026-11-20，jar `fba849ed…b7b5`）：

| 形态 | 打开方式 | `selectionKind()` | `isAwaitingSelection()` | 填牌 | 关屏 |
| --- | --- | --- | --- | --- | --- |
| G1 | 常规 `num=1` | GRID | true | 成功 | 成功 |
| G2 | `anyNumber=true` | GRID | true | 成功 | 成功 |
| G3 | `isJustForConfirming=true`（num=0） | GRID | true | 成功 | 成功 |
| G4 | `num=0 + canCancel=true` | GRID | true（实测仍填 1 张） | 成功 | 成功 |
| G5 | `forUpgrade=true` | GRID | true | 成功 | 成功 |
| G6 | `forTransform=true` | GRID | true | 成功 | 成功 |
| **G7** | **`targetGroup=null`** | **NONE** | **false** | **拒绝** | 单独调用仍能关屏 |
| G8 | 常规网格 + 空选 | GRID | true | **拒绝**（设计如此） | 成功 |

结论（只陈述事实）：**唯一会让求解器「不把界面当决定」的形态，是 `screen==GRID` 且
`targetGroup==null`（G7）**。它的表现与玩家描述一致：求解器不填、不关，界面留在屏幕上等人点。
其余打开方式在沙盒里都能自动填 + 关。

## 2. 为什么不能盲修

- 求解器把选牌界面当作**真实搜索分支**：先枚举界面给出的选项，推演每种选择的后果，再把选中的
  那一项写进路线。玩家按下的「确定」同时是「路线里选了什么」的校验点。
- G7 没有 `targetGroup`，求解器拿不到候选集合，也就无法构造可推演的选项；此时自动点「确定」
  等于替玩家做一个**未被搜索过**的选择，可能直接违背路线。
- 因此当前设计选择停下并点名，而不是猜。要修，先要弄清这条路径是为哪种 mod 界面打开的。

## 3. 已定位的代码路径（1.7.0）

- `Driver.selectionKind()`：GRID 的判定要求 `gridSelectScreen != null && targetGroup != null`；
  `targetGroup == null` 时返回 NONE。
- `Driver` 的填选路径：`kind==NONE` 时以 `refused("not-settled", …)` 记录拒绝；
  `closeGridScreen` 只调 `closeCurrentScreen()`，可单独关闭但不会替玩家选。
- `Solver` 侧：推演时 `pumpUntilSelection(...)` 失败即按不完整收尾（`incomplete-actions`）。
- `Gameplay` 自动执行：等待选界面期间会打日志
  `selection open with no planned answer: kind=…`；
  `Driver` 侧会打 `live selection: kind=… offered=[…]`。
  这两行是现场取证的关键。

## 4. 本机 mod 筛查（只作嫌疑，不是结论）

本机 20 个 jar 中真正引用 GRID 的：StSLib、BaseMod（库）、togawasakikomod、GuFu、MLOnmyoji、
card-forge；ninja 与 GuFu 还会经 StSLib 的 `actions/common` 间接开网格。共定位 42 个 open 调用点。

- **已排除**：`CardForge_CN_ForgeUI` 的 `CardGridRelic.onRightClick()` 有硬守卫
  `screen==MAP && room.phase!=COMBAT`，只在地图使用，与「战斗中多一次确认」不符。
- **修订后的 Top 5（限战斗内，按嫌疑排序）**：
  1. GuFu 遗物开网格（`BottledPlaceholderRelic` / 空鸟 `NiaoPatch_onEquip`，7 参 open，无地图守卫）；
  2. Onmyoji 达摩 / 纸人遗物（同类遗物路径）；
  3. GuFu 感知宝珠（`anyNumber=true`，G2 形态）；
  4. togawa 无何有之颂乐 / 如愿以偿（4 次 open 的多分支）；
  5. Onmyoji 定向召唤 / 复制纸人（标准网格对照组）。

这些只是**本机静态筛查**得到的假设；玩家装了哪些 mod、触发了哪一件，仍未确认。

## 5. 解挂需要什么（任一即可）

1. 遇到「多一次确认」时，点面板的「导出报告」，把 `%USERPROFILE%\spire-pilot\reports\` 里的 zip
   提供出来。zip 不含存档、不含个人信息，用户目录会被替换成 `%USERPROFILE%`。
2. 或者在日志里找到 `selection open with no planned answer: kind=…` 与
   `live selection: kind=…` 两行，连同**当时的界面截图**一起提供。
3. 或者按 Top 5 逐个试：哪件遗物 / 哪张卡触发，就记下名称与界面上的文字。

## 6. 恢复指令

> 本调查已挂起：已有 G1–G8 真值表（G7 是唯一 NONE 形态）与 Top 5 候选；缺游戏内现场。
> 不要在没有现场的情况下写修复。拿到现场后，先确认是哪种 open 路径（`targetGroup` 是否为空、
> 是否有 `canCancel`），再决定是「把确认型 GRID 纳入可推演选项」还是「明确拒绝并提示玩家」。

## 7. 证据

- 真值表运行：S279 / S280 门禁（2026-11-20，聚焦 24/0、全 mod 24/0）。
- 静态筛查：20 个 jar、42 个 open 调用点、Top 5 列表（见第 4 节）。
- 相关修复与验收记录：[VALIDATION.md](VALIDATION.md)、[EXPERIMENTS.md](EXPERIMENTS.md)。
