# 面板对齐 STS2（对照表）

参考实现：CombatSolver-main/src/UI/（SolverOverlay、SolverRouteRow、SolverActionPill、
SolverRouteActionFlow、SolverLoopGroup、SolverActionBar 等，已逐个读过）。

| STS2 元素 | 它的行为 | STS1 现状 |
|---|---|---|
| TurnLabel（第 N 回合，当前回合高亮） | 每回合一行，当前回合强调 | ✅ 回合标题 + 当前步 ▶ |
| ActionFlow + SolverActionPill | 动作药丸，含 卡→目标、击杀标记、重放×N、tooltip | ✅ 行文本（卡→目标 + ×N 重复压缩）；tooltip 用日志代替 |
| **单击动作 = 执行该步** | pill 可点击 | ✅ **新增**：Hud 记录每行矩形，点击行 → 执行到该步（stepTarget，逐帧推进） |
| SolverLoopGroup | 重复序列折叠成一组 | ⬜ 未做（跨回合同构序列） |
| EnemyDamageLabel / OutcomeLabel / EnergyLabel | 回合右栏：受击、结果、能量 | ✅ 回合标题 HP a→b、能量、格挡；结束回合行 预计 -X HP |
| 回合折叠 | 只展开当前回合，其余折叠 | ✅ **新增**：只展开当前与下一回合，其余一行 … 第 N-M 回合（折叠 X 步） |
| SolverRelicEffectText | 显示遗物触发的影响 | ⬜ 未做（需要归因到遗物） |
| SolverPotionStrategyPanel / SolverGrowthStrategyPanel | 药水/成长策略面板 | ⬜ 未做（P2-3 跨战斗估值的一部分） |
| SolverSettingsPanel | 设置面板 | ⬜ 未做（F6 只是一个策略开关） |
| SolverMemoryUsageBar | 搜索内存条 | n/a（沙盒无托管堆监控需求） |

## 尺寸对齐（B27，2026-10-30）

对照 STS2 `src/UI/SolverUiTokens.cs`，把「上下太窄」的旧值改掉：

| 项 | STS2 | 我们（旧） | 我们（现在） |
|---|---|---|---|
| 动作药丸高 ActionPillHeight | 28 | 22 | **28** |
| 回合行高 RouteRowHeight | 44（28 + 回合标题留白） | 标题与药丸挤在一起 | 标题 26 + 药丸 28 + 行距 10 |
| 间距 Spacing Sm/Md | 8 / 12 | 行距 6、列距 8 | 行距 **10**、列距 **10** |
| 药丸左右内边距 | 8（Sm） | 20（含 3px 色条与文字偏移） | 26 |
| 路线可视区高 | 148（只放 3 行，其余折叠） | 430 | **520**（我们整条可滚，不折叠） |
| 面板宽 | ExpandedMaxWidth 820 | 600 | **720** |
| 按钮高 ButtonHeight | 34 | 34 | 34（本来就一致） |

门禁：S61 新增两条断言（PILL_H==28 && HEAD_H==26、GAP_X/GAP_Y>=10），日志
`S61 strip: rows=2 pills=9 maxRight=222.0 total=250.0`（旧 208/188）。

### B30 再放大 + 滚动可见（2026-10-30，486/0）

用户 m09373：「左边的出牌太挤了，上下间距调大，而且要能上下滑动看之后怎么出牌」。B27 那版虽然比最初宽了，但两行药丸仍贴在一起、回合之间没有分界，滚动也没有任何可见指示。

| 项 | B27 | B30（现在） |
|---|---|---|
| 动作药丸高 | 28 | **30** |
| 回合标题行高 | 26 | **28** |
| 换行行距 GAP_Y | 10 | **16** |
| 回合间距 TURN_GAP | 无 | **10**（只在回合之间，末回合不加） |
| 顶部留白 / 药丸内边距 / 最小药丸宽 | 8 / 26 / 40 | **10 / 28 / 44** |
| 滚轮步长 | 3 行 | **2 行** |
| 滚动条 / 「后面还有 N 步」 | 无 | **有**（滑块高 = 视口平方 / 总高，最小 24px，内容不超出时不画） |

门禁 S61 扩到 12 条，日志：

```
S61 strip: rows=2 pills=9 maxRight=226.0 total=306.0
S61 spacing: pillLine=46.0 turnGap=10.0
PASS the strip uses taller pills and row labels (>= 30 / 28) | pill=30.0 head=28.0
PASS turn blocks are separated by the turn gap | gap=10.0
PASS no trailing gap after the last turn | total=306.0
```

同一场景 B27 时是 `total=250.0`。STS2 的 `RouteRowHeight=44` 相当于「药丸 30 + 回合标题 28 + 药丸行距 16」里的 46，仍然对齐。

## 本轮实现（P1-1 第一刀）

1. **可点击动作行**：Hud.drawNow 记录每行矩形（虚拟坐标），SolverMod 每帧调用 Hud.handleClick()；
   命中后 Gameplay.requestStep(step) 设置 stepTarget，tick 逐帧执行直到到达该步；已执行/越界点击被拒绝。
2. **回合折叠**：只展开当前回合与下一回合，其余压成一行 … 第 N-M 回合（折叠 K 步）。
3. **重复压缩**：同一回合内相邻同标签动作合并为 名称 ×N（对应 STS2 的 重放×N）。
4. **回合结果列**：标题行 HP 起→止、能量、格挡；结束回合行保留 预计 -X HP。

## 本轮未做（已排序）

- 遗物触发行（需要把数值变化归因到遗物；先做 P2-3 的估值层更合适）
- 循环组（跨回合同构序列）
- 药水/成长策略面板、设置面板

### B31 一回合一张卡片（2026-10-30，487/0）

用户 m09521/m09523（附截图）：「每个回合的步骤和第几回合挡住了」——B30 只是把间距放大，标题与数字仍黏在一个 `head` 字符串里、画在药丸同一水平区间，所以被药丸压住。B31 按 STS2 `SolverRouteRow` 的结构重排：

| STS2 结构 | 我们之前 | 现在（B31） |
|---|---|---|
| 一个回合一整块卡片（圆角 panel + 左色条） | 无卡片层 | `Panel.drawCard`：底色 + 描边 + 左侧 3px 色条；当前回合金边 |
| 左侧固定宽度回合标签列 | 标题整行画在药丸上方 | `RouteLayout.LABEL_W=104` 独立左列，药丸永不进入 |
| 中间横向排动作药丸 | 有（B30） | 不变（`PILL_H=30`、`GAP_X=10`、`GAP_Y=16`） |
| 右侧右对齐数字（伤害/HP/费用） | 粘在标题里 | `stats` 右对齐到卡片内右边缘，只占第一行药丸右侧空间 |
| 卡片间距 | 无（只有行距） | `TURN_GAP=10`，末卡片不加 |

数据层同步拆分：`ui/RouteRow` 新增 `label` / `stats`（`head = label + "   " + stats` 保留给旧调用与 S11/S19 门禁）。

门禁 S61 扩到 15 条：

```
S61 cards: minPillX=124.0 labelRight=116.0 baseline=30.4 cardH=280.0 statsW=0.0
S61 real card: labelRight=116.0 firstPillX=124.0 statsLeft=450.0 statsRight=690.0 invasion=0.0 cardH=96.0
PASS no pill is drawn over the turn label column | 124.0 >= 116.0
PASS the turn label sits on the first pill line, not above it | baseline=30.4
PASS no pill on the first line reaches into the numbers | invasion=0.0
```

同一场景 B30 是 `total=306.0`、B31 是 `total=440.0`（卡片内边距 + 卡片间距）。

### B32 裁剪 + 自适应宽度（2026-10-30，495/0）

| 项目 | 旧（B31） | 现在（B32） | 依据 |
| --- | --- | --- | --- |
| 越界卡片 | 只挡整张越界，其余整块画（压住标题） | `RouteLayout.clipBand` 截到视口，标签/数字带外不画，药丸只在完全可见时画 | 滚动容器必须裁剪子项 |
| 面板宽度 | 写死 `720 * scale` | `TextWrap.boxWidth`：按最长行定宽（下限 720、上限屏幕可用宽），仍超宽按**字符**折行 | 头部行（mod 钩子/预算）本来比 720 长 |
| 左右内边距 | 文字 x=28，右边贴边 | 盒子 x=22、内边距 20 | 文字要留白 |
| 底部提示 | 「跟随当前步…」+「滚轮 / 上滚 下滚 = …」两行 + 隐形「回到当前步」点击区 | 全部删除（滚动条、上滚下滚按钮、滚轮、点药丸仍在） | 用户 m09705 第 3 条 |
| 重复状态句 | `Panel.render` 与 `lines()[0]` 各画一遍 | 标题带只画「STS1 求解器」；标题行只在无计划时拼 status | 用户 m09705 第 4 条 |

门禁 **S71**（8 条）实测：`S71 fit: grown=1140.0 clamped=1400.0 minimal=720.0 wrapped=4`；
`clipBand` 断言 120.0/30.0、null、0.0/300.0。套件 **495/0**，安装版 jar sha256 `20CAC3066CF97197E61A23A4EE75E2586F98603A2F19CB59190BC5353C2F3D3E`。

### B33 精简头部 + 结束回合归本回合 + 可拖动缩放（2026-10-30，505/0）

| 项 | STS2 的做法 | 本轮之前 | 现在 | 理由 |
| --- | --- | --- | --- | --- |
| 头部信息量 | 只有标题带 + 路线（掉血/节点这类在结果行里） | 来源行/难度行/预算行/遗物行/「面板按钮：…」五行 | 全部从面板删除（仍写 `solver.log`） | 用户 m10040 第 1 条 |
| 结束回合归属 | 回合行内以 pill 结尾，扣血写在该 pill 上 | `Step.turn` 是动作后回合 → endTurn 被分到下一行 | `shown[i]=isEndTurn()?turn-1:turn` 分行；pill 尾「预计 -N HP」；`turnStats` 出该行 HP 起→止 | 用户 m10040 第 2 条 |
| 路线区尺寸 | 可拖拽调整 | 写死 `720*scale`，无 grip | `ui/RouteSize` 0.6–2.0 存 `settings.txt`，右下角 grip 拖动，松手 commit | 用户 m10040 第 3 条 |
| pill 内文字 | 长标签折行在 pill 内 | pill 只有一行，超宽直接溢出 | `pills` 6 元含 `textLines`，`TextWrap.wrapLine` 折行，pill 高随行数 | 用户 m10040 第 4 条 |
| 首行与右侧数字 | 首行药丸不得压住右侧统计 | 首个 pill 在 `x==0` 无条件画 | 换行条件加 `statsLine`，放不下就换行 | 同上（截图里数字被盖住） |
| 出牌区宽度 | 始终在按钮栏左侧 | 只按屏幕宽算，可能伸到按钮面板下面 | `min(可用宽, PanelLayout.x0 - x - 14*scale)` | 右侧 12 按钮不能被压住 |

门禁 **S72**（10 条）实测：`S72 turns: rows=4 endTurnRows=3 rowsStartingWithEndTurn=0`；
`S72 grip: 2034.6666,1138.6666 24.0x24.0 panelLeft=2136.0`；`S72 panel: lines=2 closedTurns=3`。
**S61** 扩到 15 条：`S61 real card: … invasion=0.0 cardH=96.0`、`S61 strip: rows=2 pills=9 maxRight=224.0 total=440.0`。
套件 **505/0**，安装版 jar 539021 bytes / sha256 `6F397729B44DDE688A546A3557984B72CE47563C4F89FDB0E73F890B220BC522`。

