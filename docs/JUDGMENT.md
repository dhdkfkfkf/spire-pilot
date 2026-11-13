# 判定规则对齐（参照 CombatSolver-main / STS2）

> 用户要求：**凡是"判定"类需求，先看 `C:\Users\YOKI\Desktop\CombatSolver-main` 怎么做的**。
> 本文逐条记录参考实现里的判定规则、我们的对应实现、以及未对齐项。新判定一律先在这里登记再实现。

## 1. 参考实现的判定规则（读源码摘录）

| # | 规则 | 参考位置 | 内容 |
|---|---|---|---|
| R1 | **完整胜利** | `SolverInterimResultOrdering.IsCompleteVictory` | `actionCount>0 && allEnemiesDead && !playerDead && projectedHp>0` |
| R2 | **主质量排序** | `SolverInterimResultOrdering.ComparePrimaryQuality` | 完整胜利 → 战略掉血（扣成长信用）→ 战斗结束回合；成长信用在回合数之前 |
| R3 | **候选晋级** | `CanPromoteDisplayedResult` | 已胜 > 存活 > 少用复活 > 少丢资源 > 掉血更少（扣成长信用），再比质量 |
| R4 | **战术掉血** | `ActEndingBossPolicy.StrategicHpDeficit` | `掉血 + 掉上限 − 回血 − 遗物回血 − 复活回血 − 战略信用` |
| R5 | **药水花费阈值** | `PotionUsePolicy.SmartRequiredHpSaved` / `AdditionalRequiredUseStrategicHpCost` | 药水必须省下 ≥ 其战略血值才值得用；"必须用"档位另加要求 |
| R6 | **无药基线** | `FinalPlanOrdering`（potionFreeBaseline） | 若存在**无药即胜**的线，优先它；用药线必须用省下的血证明自己 |
| R7 | **药水替换信用** | `PotionRewardOutlook` + `ApplyReplacementCredit` | 只在**药水栏满**且**确认掉落**时，把奖励价值折成信用抵消费用；Sozu 归零 |
| R8 | **快速通道对账** | `Engine/Common/FastLaneVerification` | 每条廉价通道都要有 opt-in 对账：同时跑昂贵路径，**首次分歧就抛异常** |

## 2. 我们的对应实现

| 规则 | 我们的实现 | 状态 |
|---|---|---|
| R1 完整胜利 | `search/Solver.score` 的 `win` + `Driver.combatOver()`（引擎空闲且全灭、玩家未死）+ **G2 的 `actionCount>0` 显式守卫** | ✅ 与参考同构（362/0 门禁 S53） |
| R2 主质量排序 | `Solver.score` = 胜(10M) − **战略掉血标量**×2000 − 怪物血×20 − 存活×400 **− 格挡×2（仅未分胜负的线）** − 层数；成长/回血信用折算进标量。兑换率由门禁 S53 钉住，且与 `StrategicDeficit.compare`（胜→掉血→回合）在 4×3×4×3 网格上同序 | ✅ 与参考同构（G1/G5 已对齐） |
| R3 候选晋级 | `Solver` 内部以 score 单值比较；`Gameplay` 只展示最优线 | 🟡 单值近似，没有显式的多级晋级 |
| R4 战术掉血 | `Solver.postCombatHealBonus()`（回血上界）+ `PostCombatHealing` 面板行 | ✅ |
| R5 药水阈值 | `score` 里每瓶药水 = 10 HP（20000 分），用一次就扣 | ✅ |
| R6 无药基线 | 未显式实现；但"每瓶药水 −10 HP"天然让无药线占优 | 🟡 行为近似，规则未显式化 |
| R7 替换信用 | `search/PotionOutlook`（副本重放 `dropReward()`）+ 8000 分信用，仅满栏且确认掉落 | ✅ 238/0 门禁 S27 |
| R8 快速通道对账 | `search/Reconcile`：`-Dsts1solver.verify.fastLanes=1` 开启，**分歧抛异常** | ✅ 241/0 门禁 S32 |

## 3. 未对齐 / 待做

| 编号 | 差距 | 影响 | 计划 |
|---|---|---|---|
| G1 | ~~合成单一战略掉血标量~~ | — | ✅ **已做**：`search/StrategicDeficit`（`hpLost + maxHpLost − recoveredHp − growthCredit + potionCost`，单位 HP），`Solver.score` 改为先算这一个标量；门禁 S33 8 项 |
| G2 | ~~`IsCompleteVictory` 要求 `actionCount>0`~~ | — | ✅ **已做（2026-10-30 二次修正）**：参考 `SolverInterimResultOrdering.cs:5-13` 的四个条件里，`actionCount>0` 在我们引擎里是**不变式自检**而非「胜利的定义」（我们的 `win` 只由真实引擎判定战斗结束产生）。**第一版把它直接当成 `win` 传进 `Result`，等于让一个诊断标志改写判定语义**——已拆开：`Result.win` 恢复为引擎判定，`Result.completeVictory = win && plan 非空` 单独成字段，summary 打 `complete=`；门禁 S53 最后一条 + S56 三条在真搜索上钉住两者一致 |
| G3 | ~~死亡豁免（`ProjectedDeathSaveUseCount`，Lizard Tail / Fairy in a Bottle）~~ | — | ✅ **已做**：`search/DeathSaves`（1 次复活=1,000,000 分=500 HP，低于胜利的 10,000,000；观测 Lizard Tail `counter == -2` 与药水栏少一瓶 FairyPotion），`Solver.better()` 在 escaped 之后先比 `deathSaves`；门禁 S55 八条（含沙盒里真打死玩家两次：药水救 saves=1/hp=27、遗物救 saves=2/hp=45） |
| G4 | 偷取资源（`OutstandingStolenResource`） | STS1 没有这套机制 | N/A |
| G5 | ~~多级晋级（`RouteQuality` 元组）~~ | 我们单值 score | ✅ **已对齐（2026-10-30 二次修正）**：路线排序改为照抄参考 `RouteQualityPolicy.cs:98-115` 的键序（won → survives → 死亡豁免 → 战略掉血 → 成长 → 结束回合），实现在 `Solver.compareRoutes`；**格挡整条不参与路线排序**——参考里它只出现在 beam 保留（`BeamRetentionPolicy.Ranking.cs:34-35`），我们的 `score()` 里它也只作存活/未完成线的保留提示。旧 `better()` 还曾偏好「计划更长」，与「回合更少」相反，已删除。门禁 S56 七条 + S53 九条 |

## 4. 结论（本轮）

- 判定骨架基本一致：胜利/掉血/上限/药水/成长/回血/回合的权重方向都对；
- **本轮新增 R8**：快速通道对账开关（`Reconcile`），分歧直接抛异常；
- G1/G2/G3/G5 四处差距已全部落地（见下表与 4b/4d），后续判定改动仍以本表为准。

## 4b. G2 / G5 落地记录（2026-10-30，套件 362/0，门禁 S53）

- **G2**：`Solver` 的两处 `Result` 构造都改成了 `completeVictory = win && plan 非空`，对应参考
  的 `IsCompleteVictory` 里 `actionCount > 0`。root 节点本来就 `win=false`，所以是防回归护栏。
- **G5**：单值 `score` 继续保留，但把兑换率变成可验证的契约（1 HP=2000、1 药=10 HP、
  1 怪物血=20、1 活怪=400、1 格挡=2、1 层=1），并修掉唯一与 `StrategicDeficit.compare`
  矛盾的地方：**胜利线的格挡不再计分**（格挡不过战斗；旧公式里 9 回合赢+40 格挡会压过
  7 回合赢）。公式通过 `Solver.scoreOf(...)` 暴露给门禁。
- **门禁 S53**（九条）：五条兑换率、胜利压非胜利、胜利线格挡不能买回合、
  `scoreOf` 与 `StrategicDeficit.compare` 在 4×3×4×3 网格上同序、真搜索的胜利必须至少一手。
- S53 第一版就抓到了我改符号时写反的回合项（`10000007 vs 10000009`），这正是加它的理由。

## 4c. 仍存在的差距

- ~~**G3** 复活次数的显式排序项~~ → 已落地（2026-10-30，378/0，门禁 S55，见 4d）。
- **G5 的其余级次**（`DeathSaveUseCount` 之外，`GrowthHpCredit` / `GrowthRewardCount` 的
  独立级次）目前是折算进 `StrategicDeficit` 的，不是独立元组项；只要兑换率稳定，
  在 STS1 的决策空间里两者同序（已由 S53 的网格检查覆盖）。
## 4d. G3 落地记录（2026-10-30，套件 378/0，门禁 S55）

- 行为本来就对：STS1 的复活写在 `AbstractPlayer.damage()` 里（血降到 0 → 先试 FairyPotion
  `destroyPotion` / 再试 Lizard Tail `onTrigger()`），血量不会停在 0，没有单独的「濒死」状态。
- 缺的是**代价**：一次复活是一整场战斗只有一份的资源。新增 `search/DeathSaves`：
  `POINTS = 1000000`（= 500 HP，远低于 10,000,000 胜利奖励 → 赢永远压过少复活；
  少复活又压过少掉血，与 R3 的顺序一致），观测两条：Lizard Tail `counter == -2`（`onTrigger` 里
  `setCounter(-2)`，而触发条件要求 `== -1`）、药水栏里 FairyPotion 少一瓶（`canUse()==false`，
  人不能主动喝，只能由死亡分支 `destroyPotion` 移除）。
- `Solver.better()` 在 escaped/存活之后、资源与回合之前先比 `deathSaves`；`Result.summary()` 打 `saves=N`。
- 门禁 S55（八条）：权重三条、`scoreOf(90 掉血, 无复活)=9,819,988 > scoreOf(0 掉血, 1 复活)=8,999,997`、
  沙盒里真打死玩家两次（第一次药水救 saves=1/hp=27，第二次遗物救 saves=2/hp=45，`isDead` 均 false）。
- 残留：Mark of the Bloom 在游戏里关掉复活时两者都读不到，记 0（与游戏一致）；面板文案未改。

## 4e. G2 / G5 二次修正：照参考的键序，别让标志冒充判定（2026-10-30，387/0，门禁 S56）

用户指出的两条，都成了这节的标题。

**（1）格挡不是路线质量维度。** 参考 `RouteQualityPolicy.cs:98-115` 的 `ComparePrimary` 依次比
won → `DeathSaveUseCount` → `StrategicHpDeficit` → `GrowthHpCredit` → `GrowthRewardCount` →
`CombatEndedTurn`；**格挡一条都不在里面**。参考里格挡只用于 beam 保留
（`CombatBeamSolver.BeamRetentionPolicy.Ranking.cs:34-35` `UsefulDefensiveBlockReserve`），
也就是「哪些还活着的节点值得留」，从不参与已完成路线的排序。
所以「9 回合赢但剩 40 格挡」压过「7 回合干净赢」不是权重问题，是维度问题——我们把不该进来的维度
拿进来又打补丁。现在 `Solver.compareRoutes` 就是上面那条键序的直译（deficit 用 HP：
`hpLoss + 10 × 药水`；层数只在「都没赢也没逃」时兜底），`better()` 只是它的包装；
`score()` 里的格挡项保留但只作存活/未完成线的保留提示（等价参考的 block reserve）。
旧 `better()` 还偏好「计划更长」，与「回合更少」正好相反，一并删掉。

**（2）`IsCompleteVictory` 确实存在，但我们用错了地方。** 参考
`SolverInterimResultOrdering.cs:5-13`：

```csharp
public static bool IsCompleteVictory(
    int actionCount, bool allEnemiesDead, bool playerDead, int projectedPlayerHp)
    => actionCount > 0 && allEnemiesDead && !playerDead && projectedPlayerHp > 0;
```

它是**发布闸门**：参考的求解器会在搜索中持有「已判赢、动作还没落地」的中间结果，
所以对外宣称胜利前要求真有动作（外加敌人全死、玩家活着、投影血量 > 0）。
我们的第一版只抄了 `actionCount > 0` 一个字面，还把它**直接当成 `win`** 传给 `Result`——
真实的含义是：一旦出现「赢了但没有一步可重放」的结果，我们会把它记成失败，搜索继续烧预算去找
已经拿到的胜利。这是把诊断标志写成了判定语义。

而且那条断言的前提在我们这里不存在：`win` 只由真实引擎判定战斗结束产生，手里必有步骤列表。
所以它在我们这儿是「不变式自检」，不是「胜利的定义」。现在 `win` 恢复为引擎判定，
`completeVictory = win && plan 非空` 单独成字段（summary 打 `complete=`），排序仍只用 `win`。

门禁 S56（八条）钉住上面两条：胜利 > 逃跑 > 死亡、deficit 先于回合、同 deficit 少回合优先、
死亡豁免先于掉血、1 瓶药 = 10 HP、剩余格挡不能买回合、真搜索里 `win` 与 `completeVictory` 一致、
summary 暴露 `complete=`。证据：`evidence\G2-G5-ORDER-ALIGN-20261030.txt`（6072 bytes，sha256
`1C4A42A867DDAF37C59232B44B4D4D665EE2171B6238DFA48A6F9A1874AD6C83`）。

## 5. G1 落地记录（本轮）

`search/StrategicDeficit`：

```
deficit = hpLost + maxHpLost − recoveredHp − growthHpCredit + potionCostHp   // 单位 HP
POINTS_PER_HP   = 2000
POTION_POINTS   = 20000   // 一瓶药水 = 10 HP
compare(winA, deficitA, turnsA, winB, deficitB, turnsB)
  胜者优先 → 掉血少者优先 → 回合少者优先
```

`Solver.score` 现在只做 `胜?10M:0 − deficit×2000 − 怪物血×20 − 存活×400 + 格挡×2 − 层数`，
不再把"掉血/药水/回血/成长"四项分别加权（以前四项权重不同，两个方案天然不可比）。

门禁 **S33**（8 项）覆盖：基础掉血、掉上限计入、回血抵扣、成长信用抵扣、药水代价、胜利优先、同胜比掉血、同掉血比回合。

## R9 搜索停止规则（对齐 STS2，2026-10-30，P2-8）

STS2 的做法（取证：`C:\Users\YOKI\Desktop\CombatSolver-main`）：
`docs\archive\strategy\beam-width-portfolio.md:26-37` 的门控四条 + `src\Search\CombatSearchCoordinator.cs:730-740`
的 `IsProvenZeroDamageRoute`。它**不承诺数学最优**，停止条件是下面三种之一：

1. **已证明零战损**：`IsCompleteVictory && ExplicitPotionCount==0 && FutureSoldHp==0 &&`
   `ProjectedBattleHpLost-BattleHpLostSoFar==0 && PlayerMaxHp>=InitialPlayerMaxHp && PlayerHp>=PlayerMaxHp`
   （满血、零掉血、零药）→ 不可能更好，停；
2. **前沿跑尽**（`BoundaryReason == None`）→ 停；
3. **预算/节点/内存用尽** → 停，报预算内最好。

我们的 R9（`search/Solver.java`，P2-8）：

- 第一条胜线**不是**停止条件：胜线不进 frontier，但搜索继续；
- 首胜后不设固定层数：**用满预算继续搜**（P2-10 / A0，见 R11；P2-8 的 `REFINE_LAYERS = 8` 已于同日删除）；
- 找到「零 HP 损失 + 零药水」的胜线就置 `provenOptimal` 并立即停（等价于 STS2 的第 1 条）；
- `frontierExhausted`（前沿跑尽）与 `reason=no-live-branches` 分开记：前者**不等于穷尽**，
  beam 截断可能丢线，所以 UI 不许把「前沿跑尽」说成「所有路线都会死」；
- `solvePortfolio` 的四档（`{{10,96},{28,64},{64,32},{128,96}}`，份额 35/25/25/15）就是 STS2 的
  「基线 + 精炼成员」：只接受 `better()`（`StrategicDeficit.compare` 的胜→掉血→回合），
  一旦 `provenOptimal` 立刻收工。F8 与 Shift+F8 的差别只剩预算，不再有「浅搜给差答案」这条路径。

门禁 **S59**（5 项）：单只 `AcidSlime_S` + 10 张打击 → `win=true`、`hpLoss=0`（返回的是零伤线，
不是遇见的第一条胜线）、`provenOptimal=true`、portfolio 不劣于单趟窄搜。

已知差距：STS2 在「前沿跑尽」时还会换宽度继续精炼到预算用尽；我们目前四档跑完就收工。
这只会让结果更差一点，不会更贵。

## R11 A0：用满预算（2026-10-30，P2-10，用户 m06945/m06700）

用户的话：「默认用满预算，如果是很长很复杂的战斗我等上十分钟也可以，只要在有效计算」。
这条把 R9 的「首胜后再搜 8 层」升级成「首胜后一直搜」。

**停止条件只有三个**（`search/Solver.java`，阻塞 `solveInternal` 与分帧 `Session` 两处必须一致）：

1. `provenOptimal`：拿到「零 HP 损失 + 零药水」的胜线 —— 不可能更好，立即停；
2. 前沿跑尽（`reason=no-live-branches`，`frontierLeft == 0`）；
3. 预算或层数上限用尽（`reason=time` / `layers`）。

禁止的第四个条件（P2-10 已删除）：

- 「已经有一条不错的胜线，且预算用得差不多了」就收工（旧 `solvePortfolioInternal` 的
  `best.hpLoss<=0 && elapsed>=budget/2`）；
- 「首胜后又搜了 N 层」就收工（旧 `REFINE_LAYERS=8`）。

**预算口径**：`settings.txt` 的 `deepSeconds` / `deepNodes` 是唯一入口。默认 300s / 400k 节点（一次性迁移写
`fullBudget=1`，之后玩家手动改就不再覆盖）；计算（F8 / 面板「计算」）也用它，不再有自己的 3/5/8/12s 阶梯；
Boss 房 ×2（`ui/DeepBudget`，秒数封顶 600、节点封顶 1M）。面板上**没有**「加深」按钮（B43 删除）；
唯一还用两次预算的入口是 Shift+F8 深度搜索（`Gameplay.deepened`，同样封顶，预算已 unlimited 时原样返回）。

**可观测性**（为什么敢让它跑十分钟）：`Result.layersAfterFirstWin`（首胜后又搜了几层）、
`Result.frontierLeft`（还剩多少候选）、面板「已查阅 N 条世界线（M 条/s）」「已用 Xs / Ys」「⚠ Ns 没有新世界线」。
没有这些数字，「跑十分钟」和「卡死」无法区分，A0 就不该开。

**门禁**：S63（A0：更大预算不会更差、首胜后 `layersAfterFirstWin>0`、`frontierLeft` 与 `frontierExhausted` 一致）。
套件里所有真搜索受 `Gameplay.setBudgetCapForTest(1500)` 限制，A0 不改变门禁时长。

**边界**：A0 只提高「找到更好线」的概率，不改变判定（R10 不变）；`frontierLeft>0` 时 `PlanText` 会写「还有 N 条候选未展开（可再算）」，
不许把它写成结论。**被挤掉（parked）的线没回放时也会给原因**（B103：`Result.deferredWhy` → `PlanText.parkedNote`），
"可以再算"仅在确实没有具体拒绝原因时才写。

**实测（2026-10-30，`-Mode longrun`，证据 `evidence\A0-LONGRUN-20261030.txt`）**：A20 收藏家 + 30 张真牌组，180s 预算时全程 411–484 节点/秒、stalled 恒 0（真的在算）；但四档 portfolio 的前沿在 52.4s 就全部跑尽，预算没用完就提前返回——所以**预算是上限，不是固定等待**；同一场战斗 180s 与 20s 的 Result 逐字段相同，说明多给预算只提高「找到更好线」的概率，不保证更好。内存峰值 1.68–2.02GB（最宽档 beam=128）——同日由 P2-11b 加了堆守卫兜底（见下段）。

**口径（P2-11b 已修，S67）**：搜索规模有两个层次，两处都必须写清楚，不许只报一个数——
「本档」= 产出这条线的那一趟（`Result.nodes` / `expansions`，判定与 R10 措辞门限只看它）；
「整次」= 一次任务累计（`Result.totalNodes` / `totalExpansions` / `passes`，面板进度行与结论行补写）。
面板写「已查阅 N 条世界线（本档 M）」；结论行写「本档只搜了 X 条分叉，整次搜索共 Y 条」。
是否升级成「所有路线都会死」仍然只看本档扩展数（R10 推论 3），不因为其他档跑得多就升级。

**堆守卫（P2-11b）**：`Solver.heapLimitMb` 默认 1400MB（`-Dsts1solver.heapLimitMb=` 可改，**0=关闭**）；堆超限且配置宽度 ≥`GUARD_MIN_BEAM=64` 时，`effectiveBeam` 返回 `GUARDED_BEAM=48`，接在 `solveInternal` 与 `Session` 两处层末截断。
它只限制「继续保留多少条世界」，不丢已保存节点，也**不改变 `win` / `hpLoss` 判定**（R10 不变）；门禁 S67 在开/关两种模式下都跑。
## R10 搜索架构不变式：判定只能有一份实现（2026-10-30 起）

这条不变式是 B 被否决的直接结果，用来防止以后"顺手写个更快的模拟"。

**不变式**：一个候选是否赢、掉多少血、用几瓶药、活几只怪，只能由**真引擎**在沙盒副本上跑出来。
任何近似（静态打分、短程 rollout、支配关系、数值代理）都只能用在**排序/剪枝/预算分配**上，
不得进入 `win` / `hpLoss` / `potionsUsed` / `deaths` 这些字段，也不得改变 `provenOptimal` / `frontierExhausted` 的含义。

**推论 1（可解释性）**：面板上的每条数字都能追到一次真实 `update()`；如果它来自近似，那它就是"候选排序值"，
不能写进结论行。

**推论 2（近似必须可关闭）**：任何近似都要有 `-Dsts1solver.*=0` 开关，门禁在开/关两种模式下各跑一遍，
且"关"能赢的局面"开"也必须赢。

**推论 3（措辞跟着不变式走）**：`frontierExhausted`（前沿跑尽）永远不等于"已穷尽所有路线"，
只有 `reason=no-live-branches` 且搜索规模足够（`PROVEN_EXPANSIONS`）才允许说"所有路线都会死"；
开启近似剪枝时，这句话必须降级为"按当前搜索没找到能活的线"（S58 分级）。

**为什么这条比"性能"重要**：我们相对 STS2 的唯一结构性优势就是保真度免费；
一旦有第二个"近似判定"入口，优势立刻消失，而 bug 会以"某一局算错"的形式零散出现。
详见 `docs\SEARCH-OPTIONS.md` §2。

**实测记录（A1，2026-10-30）**：R10 允许的唯一近似用法「排序」已经试过一次——短程 rollout 重排（`-Dsts1solver.search.rollout=true`，默认关）。
开/关对照显示试算吃掉 20–25% 墙钟、同预算节点少 19–28%、SlimeBoss 掉血 32→65，因此**否决、保持默认关闭**；门禁 S68 钉住「默认关 / 关时不试算 / 开时真的试算 / 关能赢的开也必须赢」。数据在 `evidence\A1-ROLLOUT-20261030.txt`。

## R12 A3：不排除规则（2026-10-30，用户 m06700 第②③条）

**规则**：搜索**不许**因为“这一层只留 top-n”就把候选永久丢掉。beam 截断必须走“停车”（parking）：
把候选的动作路径、分数、层号、digest 存进延后队列，等主搜索用满/跑尽后再用剩余预算重放物化并继续搜。

**允许真丢的只有两种**：①内存上限（`DEFERRED_LIMIT=2000`，满了替换最差项）；②被支配（A2 支配剪枝，尚未实现）。
任何其他理由（分数低、怕慢、感觉没希望）都不构成丢弃的依据。

**与 A0 的配合**：A0 负责“不提前收工”，A3 负责“不丢候选”。两者同时生效时，主搜索会把预算吃满，
晋升只能吃收尾余量，所以日志里 `kept` 大、`promoted` 小是**正常形态**，不是失效。
门禁判断 A3 是否生效要看 `deferred>0` 与“开/关对照”的 `promoted>0`，不要拿真实战场的 `promoted` 数量当指标。

**可观测性**：`Result.deferred` / `Result.promoted` 必须进 `summary()` 与日志（`A3 deferred: kept=… promoted=… budgetLeftMs=…`），
面板在“未证明”时可以说“还有 N 条候选未展开”。

**与 R10 的关系**：A3 只是把**真引擎**的搜索结果补回来，不引入任何近似判定；`provenOptimal` 仍只由“零伤 + 零药”触发，
`frontierExhausted` 仍**不等于**穷尽（物化的子树本身也受 beam 与层数限制）。措辞规则不变。

**门禁**：S65（开/关对照、晋升不更差、分帧版同样 parking）。

## R13 镜像不变式：静态字段指向“可变单例”时，只恢复引用等于没隔离（2026-10-30，B26）

**规则**：`mirror/Statics` 里 `Kind.VALUE` 的语义只是“保存/恢复静态字段的引用”，它**不拥有**对象本身。
因此凡是「静态字段 → 可变单例对象」且模拟会写、又能影响玩家可见画面的（镜头/摇屏/弹窗/光标/音乐音量之外的界面对象），
都必须在 `ROOTS` 里登记为 `Kind.CLONE`，并按需加 `discardOnReadBack`（表现层不回写）。

**已按此修的**：`cardRewardScreen`、`dynamicBanner`（B20，选牌屏被模拟打开）、`screenShake`（B26，计算时整窗抖动）。
**已知但不需要修的**：`CardCrawlGame.sound`（Driver 模拟期间 `Settings.SOUND_VOLUME=0`，且不改变画面）。

**为什么容易漏**：shell 清扫会自动把 `AbstractDungeon` / `CardCrawlGame` 的每个非 final 静态字段收成 VALUE，
所以“没登记”不会报错，只会静默共享。判据不是“这个字段有没有被写”，而是“被写的对象有没有可见副作用”。

**音频不是 VALUE（B45）**：音乐对象确实不必进 ROOTS，但模拟会**改它内部状态**：Boss 的 `changeState("Activate")`
调 `unsilenceBGM()+playPrecachedTempBgm()`（`Hexaghost.java:253-256`），后者播在 TEMP 轨（`MusicMaster.java:103-110`），
而 `silenceBGMInstantly()` 只静主轨（`MusicMaster.java:139-143`）；`TempMusic` 构造即 `music.play()`（`TempMusic.java:57-59`），
从不回收 → 一次套件可留 507 条在播轨道。规则因此补一条：**模拟期间对混音器的写入必须用窗口包围**——
`mirror/MusicMute.begin(restoreOnEnd)` / `end()` 删除并 `kill()` 窗口内新建的轨道，再还原静音标志（游戏内搜索）
或整轨静音（探针实例）。这和 B37 的 `InputMute` 是同一类：静态引用没变，变的只是它指向的那个共享对象。

**mod 静态同等级（B38）**：mod 自己的 `public static` 标量不再是“镜像之外的字段”，由 `mirror/ModStatics`
扫描所有已加载 mod jar 后并进 `Statics.specs()`（`Kind.VALUE`），与游戏静态同一套 save/restore 语义。
扫描只做一次（本机 20 jar = 395 field(s) / 779 class(es) / 52ms）。仍未覆盖的是“静态 → 单例对象，模拟写其内部字段”这一类，见下条 R14。


**整进程静音（B46）**：窗口只覆盖「模拟期间」。工具起的实例还要更彻底——它整个生命周期都不该发声，因为玩家可能正在耳机上玩另一局。
`util/SilentInstance.arm()` 记住 `Settings.MUSIC_VOLUME/SOUND_VOLUME/MASTER_VOLUME` 原值后三者置 0（这三个静态量被
`MainMusic:150`、`TempMusic:154/190`、`SoundMaster:439` 相乘），`tick()` 每帧把 0 维持住、每 60 帧再 `MusicMute.silenceLive()` 清两轨；
`SolverMod` 在 `receivePostInitialize`（请求文件已在时）、`receivePostUpdate`（每帧）、请求开始处三处接线。**玩家的 `preferences\STSSound` 一次都不写**
（只有 Options 滑块会写，见 `Slider.java:72/117/127`）。注意玩家的「静音」不一定在文件里：本机 `STSSound` 只有 `Ambience On`/`Mute in Bg`/`Master Volume`，
`Music Volume`/`Sound Volume` 缺键时引擎按 0.5 播 —— 所以不能指望“玩家静音了”来让测试实例安静。

**门禁**：S69（沙盒持有副本、沙盒 shake 不改实机 duration、uninstall 后引用身份恢复、不回写）、S77（mod 静态被扫描 + 沙盒写不进实机）、S87（实例 arm 后三音量归零、之后谁改都拉回 0、`STSSound` 不被写、启动后 armed 的轨道 1 秒内被静）。
审计脚本：`-Dsts1solver.audit` 的 D1「no static field outside the mirror changed」仍会兜住“字段本身被换”的一类，
但**兜不住对象内部字段被写**——这正是 B26 躲过 D1 的原因。

## R14 没有白名单：任何 mod 内容都按真实引擎模拟（2026-10-30，B38，用户 m11229）

**规则**：求解器不维护“已认证 mod”名单。任何卡/遗物/药水/怪物/角色/充能球/power，只要它走的是引擎接口，
就一律在沙盒里真跑；结论不再因为“含 mod 内容”或“检测到第三方战斗钩子”而降级。

**为什么**：我们不是重写规则，而是复制真实引擎。mod 的内容就是引擎调用的对象，
“未认证”在这种架构下等于把“来源信息”当成了“可信度”，而真实可信度来自门禁与对账（`search/Reconcile`、
`worker/Worker.verify`），不来自名单。参考项目 STS2 用钩子数阻断认证，是因为它自己重写规则、必须知道漏了什么；
这不适用于我们的路线（R10）。

**仍然要做的**：
1. 来源如实报告（三种说法：原版 / 原版+N 个 mod 战斗钩子 / 含 mod 内容），钩子与内容名字都要列出来——出问题时这是唯一的线索；
2. mod 的静态标量必须被镜像（`mirror/ModStatics`），否则“沙盒写一次就留在实机”；
3. 认不出的自绘选择屏仍然**拒答**（拒答比猜安全），并在面板写明原因；
4. `ModProfile` 的 `trust` 列保留解析但**不作判据**，profile 文件降级为类名前缀的显示名。

**门禁**：S77（mod 静态被镜像、新充能球机制在沙盒里真跑、实机球位/上限未被碰、卸载后计数复原）、
M2/M3（钩子仍被报出来；profile 是可选元数据而不是权限表）。

## R15 什么可以判定「计划错了」：预测对账只比可复现的状态（2026-10-30，B19 / B41，用户 m12039）

**规则**：一条计划执行完一步之后，只把「下一次真的会从这里继续」的状态当判据。可以比：回合、玩家 HP/上限/格挡/能量/姿态、手牌/抽牌/弃牌/消耗堆、遗物计数、活着的怪物的血量/格挡/意图/伤害/power。不许比：

1. `GameActionManager` 的相位与队列计数（`ph=` / `q=` / `use=` / `end=`）——同一状态在相邻两帧可以是 EXECUTING_ACTIONS 也可以是 WAITING_ON_USER（B19，门禁 S42）；
2. **已经死掉的怪物**（`d=11` 或 HP 0）的 power、意图、攻击段数、显示伤害——真机在死亡动画结束（1.5 s）时 `AbstractMonster.updateDeathAnimation()` 里 `this.powers.clear()`（build/gm2/.../monsters/AbstractMonster.java:853-860），沙盒快照在刚打完的那一刻还带着它们；B41 就是菜农的 `NongBaiPower`（`atDamageFinalGive ×0.75`）让同一只死掉的酸液史莱姆显示 3 而非 4，被当成「局面变了」，白花一次重算。

**仍然必须比**：活着的怪物的 power 与伤害差异（门禁 S80 第 3、4 条），以及任何 HP 差异（S42 的 `a real HP difference is still a drift`）。死掉的怪不会行动，所以忽略它不改变任何判定结论，只去掉假警报。

**实现**：`mirror/Digest.stable()` 先按段切，怪物段交给 `Digest.monsterStable()`；`d=11` 或 `hp=0` 时丢掉 `in=` / `mv=` / `dm=` / `pw=`。`Digest.ofCurrent()`（原始指纹，写日志用）不变，漂移日志仍能看到差异原文。

**门禁**：S42（相位与濒死 power 不算漂移；真 HP 差异与活怪 power 差异仍算）、S80（用户那两行原始指纹逐字相等化、活怪仍算、真实引擎里杀死一只带 power 的怪前后 `stable()` 相等而原始指纹不等）。


## R16 没有胜线时给最深的一条可重放线（B43）

**规则**：只要本次搜索没有任何胜线（`reason` 不等于 `win`），就采用本次搜索到的最深一条可重放线作为计划，**不再用血量损失做门槛**。

**为什么改**：旧规则是 `deepestHpLoss <= best.hpLoss + max(6, startHp/5)` 才用最深线（`Solver.solveInternal`、`Solver.Session.finish`）。六火（Hexaghost）实测：34 步掉 61 血的线被丢掉，只剩 9 步零伤短桩 —— 面板于是显示「打到第 7 回合没打死就没有下一步」，玩家会读成「求解器认为没得打」。但这条短桩不是搜索能给出的最好信息，它只是「没掉血」这一个指标下的局部最优。

**边界**：

1. 有胜线时规则完全不变（胜线优先，再按 R2/R5 的掉血与药水口径排序）；
2. 只影响「怎么呈现最长线」，不影响胜负判定与 `score`；
3. 采用的最深线必须能重放（`Solver.replay` 通过），重放失败的线仍然丢弃；
4. `reason=layers`（到深度上限）时标题必须带「（到深度上限，未模拟到战斗结束；可再算）」，不许让玩家把它读成战斗已定。

**门禁**：S83（全防御牌组必败 → `deepestRoute=true` 且 `plan.size()==deepestActions`、headline 含「最长生存线」与「到深度上限」）。

**相关成本口径（R17 简记）**：为了让每张牌（含 mod 挑牌卡）的选牌屏都能被消费，`Driver` 对每张牌都用 0.05 帧步长（`-Dsts1solver.cardDelta=fast` 可退回旧行为）。实测 1.08 ms/节点 vs 0.83 ms/节点；正确性优先，这是可接受的 1.3× 代价。
## R18 没有胜线时，线必须走到战斗结束（B44，用户 m13104）

**规则**：报给玩家的计划，最后一步必须是战斗的结束点——**赢**或**玩家死亡**。不允许给一条「打到第 7 回合、Boss 还剩 74/264、后面没有下一步」的半场线。

**怎么做到**（`Solver.solveInternal` 与 `Solver.Session.finish` 两处同构）：

1. 层上限从 `96/64/32/96` 统一改成 `Solver.MAX_SEARCH_LAYERS = 400`。层上限只是**安全网**，不是停止规则（R11 的停止只有三个：证明最优、前沿跑尽、预算/层数上限）；真正限制预算是 `settings.txt`，重复状态由 digest 去重挡住，不会因为放宽层数而绕圈。
2. 若最深线停在层上限或预算处，先**续算**：用 `Solver.stateAfter(root, path)` 把这条线一次性重建（一次 fork、一次 install、走完再 `trimPresentation/readBack`），在该状态上再跑一次 `solveInternal`；续算找到胜线 → 直接拼成胜利线。
3. 仍不赢 → `Solver.finishLine(root, line, 8)` 补「结束回合」直到 `Driver.combatOver()`，让线落到玩家死亡。
4. A3 的晋升为续算**预留 1/4 预算**（`l / EXTEND_RESERVE_DIV`），否则一次晋升吃光预算，续算无钱可用（实测 `budgetLeftMs=0`）。
5. `Result.lineFinished` 记录这条线是否真的走到结束；`reason=layers` 时标题仍带「（到深度上限，未模拟到战斗结束；可再算）」，`reason=no-live-branches` 且已走到死亡时标题是「所有路线都会死 · 给最长生存线 N 步」。

**为什么**：玩家读到的「没有下一步」等于「求解器认为打完了/没得打」。真实战斗中一回合的出牌顺序在回合末才结算，半场线无法执行，也不符合 STS2 的做法（它的路线一律到战斗结束或边界原因）。

**边界**：

1. 判定与 `score` 不变（R10 仍是一份实现）；本规则只决定「呈现哪条线、线有多长」。
2. 续算失败（重建不了那条线）就退回原线并记 `orig route: ... replayed=` 诊断，不抛异常。
3. `finishLine` 只追加「结束回合」，不改变既定出牌；补的回合数上限 8（足够让任何战斗结束）。
4. 分帧路径（Shift+F8 / 昂贵战斗的 QuickSession）与阻塞路径必须同构，否则面板在 Boss 战又会给出半场线。

**门禁**：S84（低血量 + 全防御牌组必败 → `plan` 非空、`lineFinished=true`、标题含「最长生存线」）；S65 的比较口径同步改为「两条都是败线时比长度」（B44 之前它比掉血，会因为续算故意多掉血而误报）。
## R19 预算默认不限（P2-12，2026-10-31）

**规则**：

1. `settings.txt` 的 `deepSeconds=0` 与 `deepNodes=0` 都表示**不限**，且这是默认值（用户 m13385："默认成不设限……只要每秒多少世界线这个数在动说明没有卡住，可以一直等"）。
2. 面板必须仍能改成具体数字：秒数档 `{不限, 60, 120, 300, 600}`，节点档 `{不限, 10万, 20万, 40万, 100万}`。玩家自己改过的值不许被迁移覆盖。
3. 「不限」只解除时间/节点上限，不解除任何**正确性**停止条件：证明最优（零伤零药）、前沿跑完、玩家点「停止」三者仍然生效（R11 不变）。
4. Boss 房 ×2 只作用于具体数字；0 已经是不限，乘 2 仍是不限。
5. 面板在没有分母时不许画假百分比：显示「已用 Ns（不限…）」+「已查阅 N 条世界线（M 条/s）」，并把 3 秒无新世界线标成可能卡住。

**实现口径**：

- 时间用哨兵 `DeepBudget.UNLIMITED = 1e9 ms`（约 11.5 天），因为搜索内部有 `budget*share/100` 与 `budget-elapsed`，`Long.MAX_VALUE` 会溢出。
- 节点 0 原本就是「不设上限」（`Solver` 里 `layerAllowance = nodeBudget > 0 ? … : Integer.MAX_VALUE`），P2-12 只是把它变成默认。
- 老存档一次性迁移写 `unlimitedBudget=1`；`fullBudget=1` 那条旧标记保留但不再抬高数值。

**门禁**：S85（`budgetMs(0)` 在普通与 Boss 房都是 UNLIMITED；`budgetMs(300)=300000`；`nodes(0)=0`；面板文案「不限」；不限预算下 `reason != time`；证明最优时 `win=true reason=win proven=true` 且 `ms < 60s`）；S29（档位改为 60→120、120000→200000，无文件默认断言改为 0/0）；S47（补 `budgetMs(0,true)` 仍是不限）。


## R20 回合号只做末位 tie-break，不用来反推动作归属（B85/P2-56，2026-11-02）

**参照**：AutoWatcher（STS2）`tools/run-watcher-matrix.ps1:437` 的注释——「真正的原因是求解器优化的是掉血，不是回合数」；它的回归锁把「战斗在第几回合结束」当**实测锁定值**（`:430`「回归锁，不是算术判别」），不是靠算术推导。

**规则**：

1. 计划里每一步的回合号 = **这个动作被采取时**引擎的 `GameActionManager.turn`（`Solver.replay` 在 `Legal.apply` **之前**读）。不许用「动作执行后的回合 ± isEndTurn」反推。
2. 结束回合归**本回合**（`Driver.endTurn` 之后 turn 才 +1）；卡牌驱动的回合推进（腾跃 Vault）也归**本回合**，它之后的步骤归下一回合——两者都不需要特判，因为记的是动作开始时的回合。
3. 搜索排序里回合数是**末位** tie-break（`Solver` 顺序：胜 → 逃 → 复活 → HP 亏空 → 回合数 → 层数）。让回合数 +1 的额外回合本身不改变路线选择，与 AutoWatcher 口径一致。
4. 战斗在结束回合内结束时（毒/持续伤害杀掉最后一只怪），`turn` 不再推进；此时结束回合这一步必须被记为**已完成**（`fightFinished()` 判据），不许等到 20 秒 `pendingDeadline` 兜底，也不许把计划判过期。
5. 判「战斗已结束」用 `fightFinished()`（`Driver.combatOver() || room == null || room.isBattleOver || room.phase != RoomPhase.COMBAT`），因为房间先离开 COMBAT、`combatOver()` 慢一帧，那一帧的清理会先抹掉面板状态。

**门禁**：S135（毒杀结束回合：末步 `turn==1`、`planIsStale()==false`、面板无「第 0 回合」）、S136（整条线跑到底：`verifiedSteps()` 增长、111ms 内完成）、S137（腾跃 `t0==1`、`t1==2`、缓存可命中）、回归 S52/S74。

