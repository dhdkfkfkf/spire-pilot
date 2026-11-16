# PLAN-P7 — 药水分档搜索：让「先喝药再上格挡」进得了前沿（2026-11-16 开）

> 触发（user m00001，原话）：「sts1-engine-solver自己看日志为什么打地精大块头敏捷药水没有第一时间喝？先喝敏捷药水再打出幽灵铠甲能少扣2血」
>
> 追加要求（user m00149）：同瓶数下先喝药 HP 更多，分数理应更高。
> 参考实现（user m00167）：`C:\Users\YOKI\Desktop\CombatSolver-main`（STS2 的 C# 求解器）按瓶数分档——先搜不喝，再逐步搜喝 1 瓶直到全部；要求先出计划不动手。
> 硬要求（user m00201）：① 每档的 beam 适当加宽以适应喝药水；② 写进计划（本文件）。

## 0. 本轮判据（先说清楚不做什么）

- **不改判定口径**（JUDGMENT R10 不动）：`Solver.score` 的权重、`limits` 档位、「净扣血优先」的对外结论都不动。
- 只改两件事：中间节点怎么给「还没喝的药水」记账；搜索预算与候选前沿怎么按「喝了几瓶」分档保留。
- 验收由你自己实测（user m00300：「不要门禁，我自己可以测试」）：按 §5 的自测清单看面板与 `solver.log`，本轮不新增 Probe 门禁。
- 要能被实测认出来：端到端那一局（地精大块头 + 敏捷药水 + 幽灵铠甲）面板必须把药水排在幽灵铠甲之前，预计损失比现状 17 少 2。

## 1. 现场与证据（solver.log，2026-11-16 10:43，行号为物理行）

| 行 | 内容摘录 | 说明 |
| --- | --- | --- |
| 1784 | `solve: QUICK win splits=0 deepest=20 hpLoss=17 potions=1 potionHp=9 … nodes=28228 … 154672ms` | 本局求解结果：喝了 1 瓶，净扣血 17 |
| 1786 | `plan :` 14 步：… 10 幽灵铠甲 / **11 敏捷药水** / 12 防御 … | 药水排在第 11 步，在幽灵铠甲之后 |
| 1788 | trace 步 10 `t=3 b=10`；步 11 `t=3 b=10 mhp=60`；步 12 防御 `with b=17` / `without b=15` | +2 敏只作用在第 12 步的防御上 |
| 1779 | `potion counterfactual (B174): … with{loss=17} without{loss=19} … replayOk=false` | 先甲后药的线少挡 2 点、净扣血 19 |
| 1778 | `WARN plan replay failed at step 12: … 重锤+ [Bludgeon] -- not-settled: a screen is open (DEATH)` | 打印第 12 步是「防御」，回放报「重锤+」⇒ 顺序疑点，见 §9 |

结论：这条线里药水的 +2 敏只落在第 12 步的防御上。若先喝药再打第 10 步幽灵铠甲，这 2 点格挡会提前落在第 10 步——用户所述「少扣 2 血」就是这一条。也就是说，**问题不是「该不该喝」，而是「喝得太晚」**。

## 2. 根因

### 2.1 记账不对称（主因）

- `Legal.actions()` 的候选顺序是「全部卡牌 → 药水 → 结束回合」（`Legal.java:84-125`，药水分支条件 `Limits.potionsOffered() && PotionBook.usable(p)`，每瓶对每个活怪各出一支，label `药水名::怪名`）；入口 `Solver.candidates(World)`（`Solver.java:2936`）。所以药水在同层里天然排在任何卡牌之后。
- 叶子计分（`Solver.java:4474-4487`）：`int n2 = Math.max(0, this.startPotionHp - choice.potionHp);` 记的是**已花**药水，再用 `Solver.score(n2s, n2, …)` 按 `StrategicDeficit` 的价格扣分（`POINTS_PER_HP = 2000`、`POTION_POINTS = 20000` ⇒ 一瓶 10 HP 参考价；实测一瓶 `potionHp=9` ⇒ 扣 18000 分）。
- 于是**喝下那一步整层分数瞬降 18000，而「继续持有」一分不加**。同一父节点下，先喝的分支在同层比较里无条件落后。
- rank 继承 + 停车场淘汰：`defer()` 上限 `DEFERRED_LIMIT = 2000`（`Solver.java:48`），`sortDeferred` 按 rank→score→digest 排序（`Solver.java:3418`），每轮提升上限 `MAX_PROMOTIONS_CAP = 60`（`Solver.java:60`，`promoteOne` 在 `Solver.java:4530/4551`）⇒ 先喝的分支既进不了 beam，也进不了停车场。
- 净效应：搜索**系统性偏好把喝药往后推**，一直推到某个「没有别的可打」的回合；此时能吃到加成的只剩后面的防御牌。

### 2.2 档位无隔离（次因，也是参考实现的做法）

- 现行 Session 只有一维 beam 阶梯（`portfolioProfiles()` = {10, 28, 64, max(128, Limits.beam())}，`Solver.java:680-692`），喝 0 瓶 / 喝 1 瓶 / 喝 2 瓶的线在同一条前沿里抢名额。
- 喝得多的线在中间步骤天然分低，被喝得少的线挤掉；而「喝 N 瓶」这一档整体从未被独立评估——所以「同瓶数下更优的喝法」也没有机会被比较出来。

## 3. 方案

### 3.1 A 对称记账（T2，已按数学更正）

> **2026-11-16 更正**：本节最初写的是「叶子里 `+ choice.potionHp * POINTS_PER_HP`」，这条公式是错的，实现时已推翻。
> 设起手药水价值 `S`、叶子上仍持有 `H`，则 `n2 = S - H` 是**已花**。原式把叶子药水项记成 `-(S-H) + H = 2H - S`：喝掉一瓶（`H` 减 v）会让分数**再掉 2v**（一瓶 9 HP 的药水是 36000 分），比原来的 18000 分断崖更糟，与「喝药动作不再制造断崖」的目标正好相反。「已花 + 仍持有 = S（常数）」要多记的是**已花**，不是仍持有。

实际实现的口径：**档内叶子不再给药水计价**（`n2Score = 0`，见 `Solver.java` 的 `expandAndRecord`），药水成本只在**档间合并**时由 `netHpLoss` / `compareRoutes` 收一次。

- 效果：喝药那一步不再瞬降 18000 分；先喝药提前拿到的 2 点格挡立刻体现为更高分。同瓶数、同档内的叶子排序只由 HP / 格挡 / 能量决定。
- 终局口径不变：合并用的仍是「净扣血 = 扣血 − 成长/战后回复 + 药水价」；跨档比较同样按这个数。
- `provenOptimal` 仍要求真实 `n2 == 0`（一瓶没喝）；`netHpLoss` 仍含 `n2`，所以「零伤最优」与面板数字都不受影响。
- 位置：`Solver.java` `expandAndRecord`（`n2Score`）、`Solver.Session#deferPotionCost`。

### 3.2 B 分档搜索（T3 / T4 / T5）

- **档位**：0 瓶 → 1 瓶 → … → K 瓶（K = 根局面可用药水数，Potion Belt 最多 5）。档内用新上限 `maxPotionUses` 剪掉「超过本档」的分支（在 `Solver.candidates` 之后、`defer` 之前过滤，等价参考实现 `ExpansionPlan` 的到上限剪枝）。
- **结构**：新增 `PotionTieredSession.java` 负责档循环（`solvePortfolioInternal`、`PortfolioSession`、`QuickSession` 三条入口都接到它）；每档 `new Session` + `planWidening(本档 beams, 本档 deadlines, 本档节点 caps)`，档间不共享状态 ⇒ 内存峰值只等于最宽那一档。档位在 `Session.microStep` 的 `candidates` 之后、`defer` 之前过滤（`Solver.withoutPotions`）。
- **预算**：单次求解预算按份额切分——0 瓶档 40%，其余 60% 按 2^(K-i) 归一；档内仍按 `PORTFOLIO_SHARES = {35,25,25,15}`（`Solver.java:698`）切四级 beam；累计 deadline 相对各档 Session 起点计算（`widenToNextRung`，`Solver.java:4680`）。
- **提前收尾**：0 瓶档若 `provenOptimal && win`，直接停（不喝酒已最优）；某档 win 且净扣血为 0 时同样停后续档。B120 的三条收尾路径：`Solver.solve`（阻塞式昂贵战斗，本轮**不接档位**，见 §8）、`PortfolioSession`（委托 `PotionTieredSession`）、`Session`（每档内部仍走 `provenOptimal`）。档循环自身在 `PotionTieredSession.tierDone` 统一判断，因此切片路径（`QuickSession`）与阻塞路径共用同一处。
- **合并**：`Solver.better` 逐档合并；净扣血并列时**少喝优先**（保留灵活度）。日志打 `potion tier: done tier=n …`、`potion tier: baseline tier=0 loss=…` 与收尾的 `potion tiers: tiers=… ran=… bestTier=… why=…`。

### 3.3 每档 beam 加宽（user m00201 第 1 条，T4）

- `tierBeam(tier) = min(Limits.beam(), base + ceil(base * step * tier / 100))`（`step` 默认 25，即每档 +25%）；`tier=0` 与 `search.potionBeam=off` 时原样返回 base；base 取该档 profile 的窄档值（战斗默认从 10 起算），最终仍过 `effectiveBeam()`（`Solver.java:450`，堆占用超阈值时压到 `GUARDED_BEAM = 48`，`Solver.java:421`，带 `GUARD_RELEASE_PCT=65` 滞回，见 `Solver.java:403-421` 的 128 全克隆世界≈1.4GB 注释）。
- 依据：喝药动作把一条线拆成「喝 / 不喝」两支，档内候选数近似随瓶数线性增长；每档 +25% 是折中值，实际生效 beam 会打进 solver.log（`beam=`），实测可再用 `search.potionBeamStep` 调。
- **同步放大停车场与回放名额**：档位 > 0 时 `deferredCap = max(Limits.deferred(), 2000, 32 × beam)`、每轮 `promoteQuota = max(60, beam/2)`（`Session#setCaps`）。本局 `deferred=2000 promoted=229` 已经顶到上限——只加宽 beam 而不加宽这两处，宽出来的名额会立刻被停车场上限吃掉。0 瓶档保持默认值，避免无药局面的行为漂移。
- 档顺序执行 ⇒ 峰值内存只取最宽档，不会出现「所有档同时驻留」。

### 3.4 B174 探针改口径（T6）

现探针用「去掉计划里那瓶药再回放」当基线，回放自身会失败（1779 行 `replayOk=false`），且基线不是 potion-free 最优线。改为直接读同一次求解里 0 瓶档的结果当基线，日志打 `potion tier: baseline tier=0 loss=…`，不再做二次回放。

## 4. 任务表（每项 ≤0.5 天）

| 编号 | 任务 | 文件 | 验收（可执行判据） | 估时 | 状态 |
| --- | --- | --- | --- | --- | --- |
| T1 | 钉死 `planText()` 顺序 == `actions` 顺序（离线核对，不写门禁） | `Solver.java:3191` | 把 §9 疑点定性并写回 B188；若顺序不等，先修打印顺序再谈其余 | 0.25d | ✅ 已定性为 (b)，见 §9/§11 |
| T2 | 对称记账（实现改为档内不计药水价，见 §3.1 更正） | `Solver.java` `expandAndRecord`、`Session#deferPotionCost` | 同层先喝/不喝两条线的分差只剩 HP/格挡（自测 2） | 0.5d | ✅ |
| T3 | 档位枚举 + `maxPotionUses` 剪枝 + settings 键 | `PotionTieredSession.java`、`Solver.Session`、`Limits.java` | 日志出现 `potion tier:` 的 tier=0..K；tier=1 不含第 2 瓶（自测 3） | 0.5d | ✅ |
| T4 | `tierBeam` 加宽 + 停车场/回放名额 | `PotionTieredSession.java`（tierBeam/startTier）、`Solver.Session#setCaps` | 日志 `beam=` 随档位变大且 ≤ `Limits.beam()`（自测 4） | 0.5d | ✅ |
| T5 | 预算切分 + 逐档合并 + 并列少喝优先 | `PotionTieredSession.java`、`Solver.solvePortfolioInternal`、`PortfolioSession`、`QuickSession` | 各档累计用时单调、末档 ≤ 总预算（自测 5） | 0.5d | ✅ |
| T6 | B174 探针改 potion-free 基线 | `PotionTieredSession`（baseline 日志）、`Solver.Session#finish`（`tiered` 时跳过回放） | 日志出现 `baseline tier=0`，不再受回放失败拖累 | 0.25d | ✅ |
| T7 | 端到端自测（用户实机） | — | 自测 6 通过（药水在幽灵铠甲之前、预计损失 15） | 0.5d | ⏳ 待用户实测（user m00300） |
| T8 | 文档回填 | `docs` | 本文件状态列 + PLAN.md 板块行 + PLAN-BUGS B188 + WORKLOG 同步 | 0.25d | ✅ |

合计 3.25 天。

## 5. 自测清单（不写门禁；user m00300「不要门禁，我自己可以测试」）

实现完成后由你按下面 6 条现场验，判据都落在**面板**与 `solver.log`，不需要跑 `tools/run.ps1`：

1. **planText 顺序**（对应 T1）：一条含药水的计划里，`plan :` 打印的步号与同一次求解的 `trace:` 每步动作应为同一顺序；若第 N 步打印与回放/实机不符，把那一对行发我，先定性 §9 的疑点。
2. **对称记账**（T2）：喝药那一步前后，面板「预计损失」不应再出现「喝一瓶整体预期陡差一截」（原实现约 9 HP 的瞬降）；同一局面先喝 / 后喝两条线，档内比较只应差在 HP 与格挡上。终局面板数字仍是「净扣血（含药水价）」，所以喝药是否划算要跨档看 `potion tiers:` 的 `baselineNet` 与 `bestNet`，不要只盯中间层。
3. **档位剪枝**（T3）：只带 1 瓶药时，`potion tier:` 行里 tier=1 的线不应出现第 2 瓶；带 2 瓶时应分别出现 tier=0/1/2 三档。
4. **档内 beam**（T4）：`potion tier:` 行里的 `beam=` 应随档位变大（每档约 +25%），且不超过 `limits` 档给的 beam；堆紧张时被压到 48 属预期。
5. **预算份额**（T5）：各档 `potion tier:` 行的累计用时单调递增、末档不超总预算；0 瓶档应最先出结果（面板先有数）。
6. **端到端**（T7，用户主诉场景）：打地精大块头、手上有敏捷药水与幽灵铠甲，面板 / 自动出牌应把「敏捷药水」排在「幽灵铠甲」之前，且该线预计损失比现状 17 少 2（即 15）。

> 原计划里的 S258–S263 六条门禁**取消**（user m00300）；上述 6 条即原门禁的验收内容，改口述可观察项。

## 6. settings 键（`Limits.java` 单键覆盖机制）

| 键 | 默认 | 含义 |
| --- | --- | --- |
| `search.potionTiers` | `on` | 档位搜索总开关；`off` 回到当前单前沿行为（回滚开关） |
| `search.potionBeam` | `on` | 档内 beam 加宽开关 |
| `search.potionBeamStep` | `25` | 每档加宽百分比 |
| `search.potionOrder` | `beam` | P7b 执行顺序：`beam`＝cell 顺序（rung 外层，每档 beam 先跑 0→K 瓶）；`tier`＝旧顺序（一档跑完四档 beam）。只切顺序，不是总回滚 |

## 7. 代价 / 风险 / 影响

① **代价**：每档一次 Session 构造（几十 ms 级），档数 ≤ 6；对称记账每节点一次整数乘加，可忽略；宽档节点数按 beam 线性增长，但预算按份额切分 ⇒ 总时长不增，且首档 40% 先出结果、可先显示。内存峰值 ≈ 最宽一档的 beam（受 `effectiveBeam` 堆守卫约束）。

② **风险**：对称记账改变所有局面的选线口径（净扣血口径不变，但「要不要喝」的边界会移动）；药水按 `PotionBook` 战略价计价，不等于真实价值，该留 Boss 的药可能被提前喝；三条收尾路径都要接档位，漏一处就重演 B120（预计无伤却一直计算中）；宽档 + 大停车场使单次内存略升。回滚＝`search.potionTiers=off`。

③ **影响**：面板 plan 与预计损失（药水会更早出现）；自动出牌顺序；`solver.log` 新增 `potion tier:` 行与 `beam=` 实际值；`settings.txt` 新增三键。与其它 mod 无交互（不改镜像、不动 static 还原）。实际实现见 §11。

## 8. 已知边界

- 药水价值取 `PotionBook` 的战略价（≈9–10 HP），不是「这瓶药在本场的真实边际价值」；本计划不引入按局面估值的模型。
- 不建模「药水留到下一场」的跨战斗价值，只看本场净扣血（与现口径一致）。
- 开屏类药水（如 Discovery）的随机流仍按实机 seed，档内不做多次抽样。
- 本计划**不修** §9 的顺序疑点本身；T1 已定性为回放期的 DEATH 屏所致（非打印错序），故无代码改动。
- 昂贵战斗走 `Solver.solve(liveRoot, 10, 96, budgetMs)`（`Gameplay.java:2332`，条件：boss 房或任一怪 maxHealth ≥ 200），该路径本轮**不接档位**，仍是单前沿 + 阻塞式收尾；只有 `solvePortfolio` / `PortfolioSession` / `QuickSession`（切片式，含面板与地精大块头这类 90HP 局）走分档。

## 9. 疑点（T1 必答）

1778 行回放失败报的 action 是「重锤+ [Bludgeon]」，而 1786 行 plan 第 12 步是「防御」、第 14 步才是「重锤+」。两种可能：

- (a) `planText()` 的打印顺序与真实 action 顺序不一致 —— 若成立，B174 的比较对象也错位，必须先修；
- (b) 回放过程中药水/格挡改变了手牌与能量，第 12 步的真实动作确实变成了重锤+（DEATH 屏导致 `not-settled`）。

T1 先把顺序钉死（离线核对，不写门禁）之后再下结论；在此之前不把「先甲后药」当成已验证事实。

**T1 结论（2026-11-16，离线核对）**：`Solver.planText()`（`Solver.java:3240-3249`）只按 `this.plan` 的下标顺序拼 `Step.action.label`，没有重排；`Result.plan` 是 `finish()` 里 `Solver.replay(root, list)`（`Solver.java:2069`）返回的 `List<Step>`，`replay` 按传入的 action 列表**逐个**执行（`for i < list.size()`，`Legal.applyOrRefuse(action)` 后立刻 `new Step(action, …)`），所以 `Step.action` 就是被回放的那个 Action，打印顺序 == 执行顺序。因此 (a) 不成立——打印顺序就是 action 顺序。1778 行的错位属 (b)：回放走到第 12 步时前序药水/格挡已改变手牌与能量，实机动作变成「重锤+」，且该步开着 DEATH 屏导致 `not-settled`。B174 的 `replayOk=false` 由此解释，不是比较对象错位；本计划不修这条回放探针本身（已由 T6 用 0 瓶档基线替代）。

## 10. 文档落点

- 本文件：`sts1-engine-solver/docs/PLAN-P7.md`
- 板块行：`docs/PLAN.md` 板块表末尾（P2-60 之后）+「在跟的条目」新增 B188
- bug 细节：`docs/PLAN-BUGS.md` 追加 B188（B187 之后）
- 工作记录：`docs/PLAN-WORKLOG.md` 追加 `## 2026-11-16` 小节

## 11. 实现记录（2026-11-16，已构建安装，待用户实测）

### 11.1 落点与行号（行号为当前源码）

| 文件 | 内容 |
| --- | --- |
| `sts1-engine-solver/src/main/java/sts1solver/search/PotionTieredSession.java`（新，`public final class` :43） | 档循环壳：`startTier()` :137、`advance(long)` :271、`tierDone(Result)` :247、`finishAll(String)` :228；`tierBeam` :110、`better` :218；`limits` :54；`tierBeam`、`shares`、`baseline`、`bestTier`、`nodesDone/expansionsDone` |
| `sts1-engine-solver/src/main/java/sts1solver/search/Solver.java` | `potionCountUsable(World)` :2901、`potionUses(List<Action>)` :2922、`withoutPotions(List<Action>)` :2932；`Session` 新字段 `maxPotionUses` :4042、`deferPotionCost` :4043，方法 :4054/:4058/:4063/:4069；候选剪枝 :4498-4500；叶子 `int n2Score = this.deferPotionCost ? 0 : n2;` :4581；`promoteOne` 的 `promoteFloor`；`finish()` 里 `if (!tiered)` 才跑 B174 回放；`PORTFOLIO_SHARES` 去 `private`、`rungDeadlines` 改 `static`；`solvePortfolioInternal` 改走 `PotionTieredSession`；文件末尾的 `PortfolioSession` 改为纯委托壳（`advance` 首次建 `PotionTieredSession`，其余访问器全部转调） |
| `sts1-engine-solver/src/main/java/sts1solver/search/QuickSession.java` | `narrowDeep` 分支改用 `PotionTieredSession`（阶梯 = 10 + `widenBeams()` 去重升序）；`passes()` 委托 |
| `sts1-engine-solver/src/main/java/sts1solver/search/Limits.java` | `search.potionTiers` / `search.potionBeam` / `search.potionBeamStep` 三键 + `yes()` 解析器 |

### 11.2 与计划原文的偏差（都已在上文更正）

1. **§3.1 公式**：原式 `+choice.potionHp*POINTS_PER_HP` 数学上会让喝一瓶再掉 2v（36000 分），实现改为「档内叶子不计药水价、档间合并收一次」。
2. **§3.2 结构**：不是直接在 `solvePortfolioInternal` 外套循环，而是抽出 `PotionTieredSession`，让阻塞/切片/面板三条入口共用同一档循环（避免三处各写一份收尾逻辑，B120 的教训）。
3. **§3.3 公式**：`tierBeam` 用整数 `base + ceil(base*step*tier/100)`，且停车场/回放名额放大只在 `tier > 0` 生效。
4. **§3.2 提前收尾**：0 瓶档的 `provenOptimal && win` 由每档 `Session` 自己先判（`microStep` 里已有），档循环的 `tierDone` 再做「win 且净扣血 ≤ 0」的跨档收尾；`Solver.solve` 阻塞路径不接档位（见 §8 边界）。
5. **T6**：不新增 Gameplay 日志，B174 回放改为在 `Session.finish()` 里 `!tiered` 时才跑，档位模式下由 `PotionTieredSession` 打 `potion tier: baseline tier=0 loss=…`。
6. **单档不改旧行为**：只有 `limits.length > 1` 时才 `markTiered()` / `deferPotionCost(true)`；`search.potionTiers=off` 与「身上没药」两种情况仍是单档 + 叶子扣药水价 + B174 回放，回滚开关是真的回到旧口径。

### 11.3 构建与安装（可测版本）

- `tools\build.ps1`：JDK 21（`F:\Stata\utilities\java\windows-x64\zulu-jdk21.0.11`），`--release 8`，141 个源文件，Jar 555 条目，BUILD OK。
- `tools\install.ps1`：`mod\sts1-engine-solver.jar` 1,293,146 B → `D:\Steam\steamapps\common\SlayTheSpire\mods\sts1-engine-solver.jar`，sha256 `9C8E9CA5A8E39686BC240D34C91E0658BB7DC6B1B91D84FD73F2BB4DA200AEC4`，INSTALL OK（含「单档回滚时保留 B174 回放」的 `markTiered()` 条件化修正）。
- 未跑门禁/游戏自测（user m00300）。

### 11.4 回滚

- `settings.txt` 加 `search.potionTiers=off` ⇒ `PotionTieredSession` 单档（`limits={MAX_VALUE}`、份额 100%）、叶子按旧口径扣药水价、B174 回放保留，行为回到本轮之前。
- 只回滚 beam 加宽：`search.potionBeam=off`（档位仍分，但每档 beam 相同）；`search.potionBeamStep=0` 等价。
## 12 后续改造计划（2026-11-16，user m00331；**P7b 已实施，2026-11-16，见 §13**）

### 12.1 用户要求

1. **面板加提示**（模仿 STS2 参考实现的进度提示）：让玩家看到「现在在算用几瓶药水的世界线」。
2. **迭代顺序改成 rung 外层、药水档内层**：先算第 1 档 beam 的 0→n 瓶，再算第 2 档 beam 的 0→n 瓶；不再「0 瓶把四档 beam 跑完，再 1 瓶跑四档」。
3. 先给计划、不动代码（本节即当时的计划）。**后续：同日已按本计划实施完 P7b-1..P7b-5，见 §13。**

### 12.2 现状（改造前）

- `PotionTieredSession` 是 tier 外层：构造 :71-89 定 `limits`/`shares`，`startTier()` :137-184 每档建一个 Session 并把整条 ladder 交给 `planWidening`（:156-159），`advance()` :271-294 逐档推进。日志 `potion tier: start/done`。
- 面板：`Gameplay.lines()` :1854 拼 `worldlineText(quick)` + `quick.phase()`；`PotionTieredSession.phase()` :442-454 只说 beam/layers；状态行 `Gameplay.java` :729/:732 只用 `worldlineText`。
- `Gameplay.worldlineText()` :977 同时被 `probe\\Probe.java:3482` 与门禁断言引用 ⇒ 它的话术不改。

### 12.3 顺序改造（A：cell 循环）

- 执行单元从「一个药水档」改成 **cell = (rung r, 药水档 t)**，r∈0..R-1（ladder 长度），t∈0..K。
- 严格顺序：`r=0: t=0,1,…,K` → `r=1: t=0..K` → … → `r=R-1`。即「第一档 beam 把 0→n 瓶算完，再第二档 beam」。
- 每个 cell 一个**独立** `Solver.Session`：`beam = tierBeam(ladder[r], t)`（:110 不变），`planWidening(null)`（单档，不再内部加宽），`limitPotionUses(limits[t])`，`deferPotionCost(true)`，`markTiered()`；t>0 时仍 `setCaps(max(2000,32*beam), max(60,beam/2))`。
- 预算：`cellBudget = B × rungShare[r]/100 × potionShare[t]`。`rungShare = PORTFOLIO_SHARES {35,25,25,15}`；`potionShare` 沿用 :92-107（tier0=0.40，其余 0.60×2^(K-i)/Σ）。cell 开始时再夹 `min(cellBudget, 总剩余)`，剩余 <300ms 则 `finishAll("time")`。
- 节点：`maxNodes>0` 时 `cellNodes = max(200, maxNodes × rungShare[r]/100 × potionShare[t])`；`Session` 的 firstNodes 直接取 cellNodes（单档，无 widening 名额分配）。
- 早停不变：任何 cell 的 result `win && (provenOptimal || netOf(r) <= 0)` ⇒ `finishAll("proven")`，剩余 cell 不跑。
- 基线：`baseline` = 已经跑完的 t==0 cell 里净扣血最优者（不再假定 0 瓶档跑满整条 ladder）；日志 `potion tier: baseline tier=0 loss=… cells=…` 在 t==0 cell 完成时刷新。
- 单档例外：`K==0` 或 `search.potionTiers=off` 时仍走现状路径（一个 Session 内 `planWidening` 跑完 ladder，保留 B177 的 in-place 复用），不产生 cell 日志、不加提示。
- 回滚键：`search.potionOrder`，`beam`=新顺序（**默认**）、`tier`=旧顺序（一个 Session 一档、内含 ladder）。`search.potionTiers=off` 仍是总回滚。旧顺序保留实现，便于 A/B。

### 12.4 提示改造（B：面板/状态行）

- `PotionTieredSession` 新增：`tierCount()`、`curTier()`、`curLimit()`、`cellRung()`、`cellRungs()`、`hint()`（单档返回 `""`）。
  `hint()` 文本：`药水 ≤1 瓶（2/3）· beam 28（2/4 档）`；若已有 0 瓶结果追加 `· 0 瓶基线 净-15`。
- `phase()` :442 改为 `hint() + " · " + 现有 layers 文案`（单档时保持原文案）。
- `QuickSession` 加 `potionHint()`（`portfolio/session` 为空或未分档返回 `""`）。
- `Gameplay`：状态行 :729/:732 追加 ` + potionHint(quick)`（空串不加分隔符）；`lines()` :1854 的进度行同样追加。**不动 `worldlineText`**，避免 probe/门禁整行断言失效。
- 面板新增一行（可选，预算允许时）：`药水世界线：≤1 瓶（2/3）· 已出 0 瓶 净-15`，与状态行同源，保证单行宽度可控。
- 每 cell 开始打日志：`potion cell: start rung=1/4 tier=2/3 limit=1 beam=28 budgetMs=… nodes=…`；结束时 `potion cell: done rung=… tier=… win=… net=… potions=… nodes=… ms=…`。收尾汇总：`potion tiers: cells=… ran=… bestCell=rung/tier why=… baselineNet=… bestNet=…`。

### 12.5 任务表（估时合计 1.35d）

| 任务 | 内容 | 估时 |
| --- | --- | --- |
| P7b-1 | `PotionTieredSession` 引入 cell 循环；保留 `search.potionOrder=tier` 旧分支 | 0.5d |
| P7b-2 | 预算/节点按 rung×potion 双权重切分；剩余/截止；早停、baseline 口径 | 0.25d |
| P7b-3 | 提示 API + `phase()` + 状态行/进度行接线（不动 `worldlineText`） | 0.25d |
| P7b-4 | cell 日志与汇总日志 | 0.1d |
| P7b-5 | 构建安装、自测清单交接、文档回填（本节状态 + PLAN.md + WORKLOG） | 0.25d |

**状态（2026-11-16）**：P7b-1 ～ P7b-4 ✅ 已实现；P7b-5 的构建安装与文档回填 ✅，**端到端验收待用户实测**（§12.6 六条）。`search.potionOrder` 默认 `beam`（新顺序），`tier` 回旧顺序；`search.potionTiers=off` 仍是总回滚。

### 12.6 验收（用户实测，不写门禁；user m00300）

1. 日志顺序：`potion cell: start rung=0 tier=0..K` 连续出现后，才出现 `rung=1 tier=0`。
2. 面板/状态行 1s 内出现 `药水 ≤N 瓶（i/K+1）`，换 cell 时数字跟着变。
3. 同预算对照：最终净扣血不比改造前差；并列时喝得少。
4. `K==0` 或 `search.potionTiers=off`：无 cell 日志、无药水提示，行为与改造前一致。
5. `search.potionOrder=tier`：日志顺序回到旧的「一档跑完四档 beam」。
6. 三处入口（计算 / 自动出牌 / 深度搜索）都显示提示；`Solver.solve` 阻塞路径仍无提示（未接档位，边界不变）。

### 12.7 代价 / 风险 / 影响

- **代价**：cell 数 =(K+1)×R（K=2、R=4 时 12 个 Session，现状 3 个）。每个 cell 从 root 重建并重新展开前几层，B177 的 in-place 复用只在单档路径保留 ⇒ 同预算下节点/耗时约 +5~15%，每档挖得浅一点；总时长仍由总预算封顶，不会超。
- **风险**：① 0 瓶宽档（原最强基线）被推到 rung 2/3 才跑，预算不足时最终线可能比现在差（对策：rung 份额不变、每档先跑 t=0）；② 早停在窄 beam 上更难满足（净扣血 ≤0 少见），cell 数多，用时更贴近预算上限；③ 面板文字是玩家可见契约，probe/门禁若断言整行要同步——只加独立段、不改 `worldlineText` 以降低风险；④ 每 cell 一次 `World.install/uninstall`，构造停顿点变多（单次几十 ms）。
- **影响**：`solver.log` 新增 `potion cell:` 行与 `potion tiers: cells=…` 汇总（旧 `potion tier:` 行保留给单档/旧顺序）；`settings.txt` 新增 `search.potionOrder`；面板/状态行新增药水档提示；自动出牌顺序与预计损失仍由合并结果决定，口径不变（净扣血优先、并列少喝）；不改镜像、不动 static 还原、与其它 mod 无交互。

### 12.8 文档落点

- ~~本节（PLAN-P7 §12）；`docs/PLAN.md` P7 行追加「后续改造（顺序+提示）计划见 PLAN-P7 §12，未实施」；`docs/PLAN-WORKLOG.md` 2026-11-16 段追加计划条目；实施后再回填 PLAN-BUGS B188「后续」小节。~~（2026-11-16 已按此落点执行完，见下一条。）
- **实施后回填（2026-11-16）**：本节标题状态已改；实现记录见 §13；`PLAN.md` P7 行与安装行、`PLAN-WORKLOG.md` 2026-11-16 新增实施段、`PLAN-BUGS.md` B188「P7b 后续改造」小节均已同步。

## 13 P7b 实现记录（2026-11-16，已构建安装，待用户实测）

### 13.1 落点（行号为当前源码）

- `src/main/java/sts1solver/search/PotionTieredSession.java`（~700 行，原 465 行）：
  - 新增字段 :76-108 —— `rungShares[]`、`cellMode`（`limits.length>1 && Limits.potionOrderBeam()`）、`rung`、`bestRung`、`cellBudgetMs`、`cellNodesCap`。
  - `rungShares(int rungs)` :134-148：把 `Solver.PORTFOLIO_SHARES={35,25,25,15}` 按实际 ladder 长度归一化（4 档时数值与旧 35/25/25/15 完全一致；QuickSession 去重后 3 档时变为 35/85、25/85、25/85，不会白留预算）。
  - `totalCells()` / `cellIndex()` / `nextCell()` :170-195：rung-major 推进（t 内层，r 外层）。
  - `startCell()` :253-300：每个 cell 一个**独立** `Solver.Session`，`planWidening(null)`（单 beam、不在内部加宽）、`limitPotionUses(limits[t])`、`deferPotionCost(true)`、`markTiered()`；`t>0` 时 `setCaps(max(2000,32*beam), max(60,beam/2))`；预算 `b = max(300, min(B*rungShare[r]*potionShare[t], 剩余))`，剩余 `<=300ms` 直接 `finishAll("time")`；节点 `cellNodes = maxNodes>0 ? max(200, maxNodes*rungShare*potionShare) : 0`。
  - `cellDone()` :406-436：t==0 的 cell 里取最优作 `baseline`（`potion tier: baseline tier=0 loss=… cells=i/N`）；`merge`；早停 `win && (provenOptimal || netOf<=0) ⇒ finishAll("proven")`；cell 耗尽 `finishAll("cells")`。
  - `finishAll()` :356-404：cell 模式汇总 `potion tiers: cells=… ran=… bestCell=r/t why=… baselineNet=… bestNet=… bestPotions=…`；旧 `tiers=` 行只在 tier 顺序保留。
  - `advance()` :446-486：`cellMode` 走 `startCell`/`cellDone`，否则旧 `startTier`/`tierDone`。
  - `rung()`/`rungs()` :600-614：cell 模式返回 cell 的 rung 与 `ladder.length`（面板「第 i/R 档」即此值）。
  - `phase()` :625-643：cell 模式返回 `hint() + " · beam=… layers=…"`；tier 模式文案不变。
  - `hint()` :645-662：`药水 ≤1 瓶（2/3）· beam 28（2/4 档）· 0 瓶基线 净损 15`（无 baseline 时省略最后一段；非 cell 模式返回空串）。
  - 测试钩子 :664-695：`cellMode()` / `cellsForTest()` / `curLimit()` / `cellBudgetMs()` / `bestRungForTest()`；旧 `tiersForTest` / `bestTierForTest` 保留。
- `src/main/java/sts1solver/search/Limits.java:269-280`：新增 `potionOrderBeam()`，读 `search.potionOrder`，默认 `beam`；值为 `tier` / `tiers` / `档位` / 以 `tier` 开头时返回 false。
- `src/main/java/sts1solver/search/Solver.java`：`PortfolioSession.phase()` :5094 委托不变；新增 `hint()` :5102；`PortfolioSession.advance()` 的启动日志 :5117-5121 追加 `cellMode=… cells=…`。
- `src/main/java/sts1solver/search/QuickSession.java`：`phase()` :281-284 在 `cellMode()` 时直接返回 `session.phase()`（不再套「加宽搜第 N 档」，因为cell 只有一个 beam）；新增 `potionHint()` :296-301（portfolio 或 session 为空返回空串）。
- `src/main/java/sts1solver/Gameplay.java:726-737`：状态行两条分支都追加 `" · " + quick.potionHint()`（空串不追加），`worldlineText()` 未改。
- `Gameplay.lines()` :1854 未改：`quick.phase()` 在 cell 模式已自带 hint，避免重复；深度搜索面板走 `deep.phase()` ⇒ 同样带上 hint。

### 13.2 与计划原文的偏差

1. **提示措辞**：计划写 `0 瓶基线 净-15`，实现为 `0 瓶基线 净损 15`（避免“净-”被读成负号）。
2. **面板单行**：计划 §12.4 的“可选的独立一行”未新增，hint 直接并入 `phase()`；状态行另行追加。理由：面板宽度紧张，且 `phase()` 已同时被普通计算、自动出牌、深度搜索三处读取。
3. **日志序号 1-based**：`potion cell: start rung=1/4 tier=1/3`；计划示例用 1-based，便于玩家对照“第几档”。
4. **K==0 时的 cell 日志**：`cellMode` 要求 `limits.length>1`，所以“身上没有可用药水”时走的是**单档旧路径**（`limits={MAX_VALUE}`），只有一行 `potion tier:`、无 `potion cell:`、无提示 —— 与 §12.6 第 4 条一致。`search.potionTiers=off` 同理。
5. **rungShare 归一化**：计划按 `PORTFOLIO_SHARES` 原值/100 直接乘；实现改为按 ladder 实际长度归一，避免 QuickSession 的 3 档 ladder 只花掉 85% 预算。4 档时两者完全等价。
6. **`cellNodes` 分配**：计划写 `Session` 的 firstNodes 直接取 cellNodes；实现同上，且 `maxNodes<=0`（不限）时 cellNodes=0（保持不限）。

### 13.3 构建与安装

- `tools/build.ps1`：JDK 21.0.11、`--release 8`、555 entries，`BUILD OK`。
- `mod\sts1-engine-solver.jar`：1,295,185 B，sha256 `D6D9D262DD586CBB27C0018ABC1E4EE5A7029210913D345F6599FC5B62A24A6F`；已复制到 `D:\Steam\steamapps\common\SlayTheSpire\mods\sts1-engine-solver.jar`（同尺寸同 hash）。
- **未跑 `tools/run.ps1`、未启动游戏**（user m00300 / m00732：用户自行实测）。

### 13.4 回滚

- 只回滚顺序：`settings.txt` 加 `search.potionOrder=tier`（旧“一档跑完四档 beam”）。
- 总回滚：`search.potionTiers=off`（单 Session、旧计价、旧 B174 探针、无提示）。
- 代码回滚：本段新增的方法都是增量的；旧 `startTier`/`tierDone`/`logTier` 原样保留，把 `cellMode` 初值改为 false 即整体回到旧顺序。

### 13.5 代价 / 风险 / 影响（实现后的实测口径）

- **代价**：cell 数 =(K+1)×R，K=2、R=4 时 12 个 Session（改造前 3 个）；每 cell 都从 root 重建并重展开前几层，同预算下节点/耗时预计 +5~15%，每档挖得略浅；总时长仍由总预算封顶（`startCell` 的剩余 ≤300ms 即收尾）。内存峰值仍 = 最宽的那一个 cell（cell 顺序执行，不叠加）。
- **风险**：① 0 瓶的宽档（rung 3/4）被推到最后，预算不足时最终线可能比改造前差 —— 但 rung 份额未变、每档先跑 t=0；② 早停只剩“净扣血 ≤0 或 provenOptimal”，窄 beam 上更难触发，实际用时会比改造前更贴近预算上限；③ 面板/状态行文字变了，若有 probe/门禁整行断言会红 —— 未改 `worldlineText` 以把面收窄；④ 每 cell 一次 `World.install/uninstall`，构造停顿点从 3 次变成最多 12 次（每次几十 ms）；⑤ `search.potionOrder=tier` 分支没有门禁覆盖（本轮不写门禁），只能靠用户实测抽样。

## 14 P7c 实现记录（2026-11-16，药水档上限改读游戏数据；已构建安装，待用户实测）

### 14.1 用户要求与证据

- 用户（m00172）：“1.药水并不是上限只有3，改成读取游戏内的数据”。
- 改造前唯一的写死上限：`PotionTieredSession.java:53` `private static final int MAX_TIERS = 6;`，构造器 `:100` `int k = Math.min(Solver.potionCountUsable(world), MAX_TIERS - 1);`。
- 游戏侧数据（`javap -classpath desktop-1.0.jar com.megacrit.cardcrawl.characters.AbstractPlayer` 确认）：`public int potionSlots;`、`public ArrayList<AbstractPotion> potions;`。`potionSlots` 会被 Potion Belt 与 mod 改写，是“本局真实容量”，而 `potions` 只是当前持有。
- 实机日志（`C:\Users\YOKI\sts1-engine-solver\solver.log`，11:42:41，19 个 workshop mod）：`portfolio ladder (B179): beams=[10, 28, 64, 128] potionTiers=3 cellMode=true cells=12 nodes=0`、`potion cell: start rung=1/4 tier=1/3 limit=0`、`potion tiers: cells=12 ran=1 bestCell=1/1 why=proven`。当时的 3 档 = 可用药水 2 瓶 ⇒ K=2，说明“3”不是硬编码，而是被“当前可用瓶数”决定的。

### 14.2 改动（落点，行号为当前源码）

1. **删常数**：`sts1-engine-solver/src/main/java/sts1solver/search/PotionTieredSession.java` 删除 `MAX_TIERS`；新增字段 `usablePotions`/`beltSlots`（:52-58 区）。
2. **新读法**：`Solver.java` 新增 `static int potionSlots(World)`（`potionCountUsable` 之后）：`world.install()` 后读 `AbstractDungeon.player.potionSlots`，`<=0` 时回退 `potions.size()`，异常返回 0。
3. **新算法**：`PotionTieredSession.tierCount(int usablePotions, int potionSlots, long budgetMs, int rungs)`：
   - `usablePotions <= 0` ⇒ 返回 0（身上没有可喝的药水 ⇒ 仍走单档旧路径，无 cell、无日志、无提示，行为与 P7b 前一致）；
   - 否则 `cap = max(usablePotions, potionSlots)`（`potionSlots<=0` 时取 `usablePotions`）；
   - 预算地板：`floorCells = max(1, budgetMs/300)`、`byBudget = max(1, floorCells/max(1,rungs)) - 1`；
   - 返回 `min(cap, byBudget)`。⇒ 档位上限＝**本局药水槽容量**（Potion Belt/mod 可 >3；Entropic Brew/Alchemize/mod 药水可在战斗中补满），预算不足时按每格 300ms 自动收窄。
4. **hint**：`hint()` 在括号里追加槽位，形如 `药水 ≤1 瓶（2/3，槽 3）· beam 28（2/4 档）· 0 瓶基线 净损 15`；`beltSlots<=0` 时不显示“槽 N”。
5. **日志**：构造时新增 INFO `potion tiers: usable=… slots=… K=… tiers=…`；`PortfolioSession.advance` 的 `portfolio ladder (B179)` 行追加 `potionSlots=… usablePotions=…`（新访问器 `slotsForTest()`/`usablePotionsForTest()`，不额外 install/uninstall）。
6. **旧注释订正**：`potionCountUsable` 的 javadoc 原称“limit 高于可喝瓶数只会重复上一档搜索”，该结论在“战斗会生成药水”时不成立，已改写为：上限来自槽容量，`potionCountUsable` 只决定“是否分档”。

### 14.3 与计划/前文的偏差

1. 档位**不再有 6 的上限**；唯一的收窄是预算地板（300ms/格），因此极端 mod 环境（如 20 槽）会按预算得到较少档位，这是算术约束，不是口味。
2. `K` 取 `max(usable, slots)` 而不是单纯 `slots`：读不到槽位时仍能按可喝瓶数分档，避免退回单档。
3. 槽位 > 持有瓶数时，高出的档并非重复搜索，而是“这一局如果打出/生成药水，能多喝一瓶”的世界线；只有战斗完全不产药时才退化为重复（份额按 2^(K-i) 几何衰减，浪费有界）。

### 14.4 构建与安装

- `tools/build.ps1`：JDK 21.0.11、`--release 8`、555 entries，BUILD OK。
- `mod\sts1-engine-solver.jar`：1,295,693 B，sha256 `4A155B622BF12691603A3D6ED4A5A7DBCE92A07FAA989070F6D8E5435077B644`；已复制到 `D:\Steam\steamapps\common\SlayTheSpire\mods\sts1-engine-solver.jar`（同尺寸同 hash）。
- **未跑 `tools/run.ps1`、未启动游戏**（user m00300 / m00732）。

### 14.5 回滚

- 行为回滚：`search.potionTiers=off`（单 Session、旧计价、无 cell、无提示）。
- 顺序回滚：`search.potionOrder=beam`（P7d 起默认 `tier`，cell 布局需显式开启；`tier` 即“一档跑完四档 beam”）。
- 代码回滚：`tierCount` 是纯函数，把构造器里的调用换回 `Math.min(Solver.potionCountUsable(world), 5)` 即回到 P7b 口径（`potionSlots` 保留不用）。

### 14.6 代价 / 风险 / 影响

- **代价**：多一次 `World.install/uninstall`（读 `potionSlots`，几十 ms 级，只在作业构造时一次）；cell 数从 `(min(usable,5)+1)×R` 变为 `(K+1)×R`，K 由槽位决定（默认槽位 3、持有 2 ⇒ 4 档 × 4 rung = 16 个 cell，比改造前多 4 个）；同预算下每 cell 更浅，节点/耗时上限不变（总预算封顶）。内存峰值仍＝最宽的那一个 cell。
- **风险**：① 槽位多而药水少时，高档位可能跑出与低档相同的结论，浪费其份额（几何衰减，最坏约 10~15% 预算）；② `potionSlots` 是运行时字段，若某 mod 在该字段上给出异常值（0/负数/超大），走 `<=0` 回退与预算地板兜底，但超大值会明显摊薄每档深度；③ 提示与日志多出“槽 N”/“slots=”“usable=”，若有整行断言的门禁/probe 会红（本轮未跑门禁，未改 `worldlineText()`）；④ `search.potionTiers=off` 时 `usablePotions/beltSlots` 记为 0，提示与日志不显示槽位，属预期。
- **影响（面板 / 自动出牌 / 搜索 / 兼容性）**：面板状态行与进度行在 cell 模式显示“（i/K，槽 N）”，K 随本局药水槽变化；自动出牌顺序与预计损失仍取合并结果（净扣血优先、并列少喝），口径不变；`solver.log` 新增 `potion tiers: usable=… slots=… K=… tiers=…` 与 `portfolio ladder` 行的 `potionSlots=/usablePotions=`；无新 settings 键；不改镜像、不动 static 还原、与其它 mod 无交互（只读 `AbstractPlayer` 两个公有字段）。

- **影响（面板输出 / 自动出牌 / 搜索 / 兼容性）**：面板状态行与进度行在 cell 模式多出“药水 ≤N 瓶（i/K）· beam B（r/R 档）”和基线净损；自动出牌顺序与预计损失仍取合并结果（净扣血优先、并列少喝），口径未改；`solver.log` 新增 `potion cell: start/done` 与 `potion tiers: cells=` 汇总，旧 `potion tier:` 行只在单档/旧顺序出现；`settings.txt` 新增 `search.potionOrder`；不改镜像、不动 static 还原、与其它 mod 无交互。



## 15 P7d 实现记录（2026-11-16，user m00561：顺序改回档外层 + 重复计算审查；已构建安装，待用户实测）

### 15.1 用户要求（原话）

1. 「还是改回先计算 0 药水的 1 到 4 档再计算到 n 药水的 0 到 4 档。现在这样太慢了。」
2. 「检查是否有重复计算，比如药水 1 第一档和药水 2 第一档会不会重复计算。」

### 15.2 改动落点（行号为当前源码）

1. **默认顺序改回档外层**：`Limits.potionOrderBeam()`（`search/Limits.java:273`）默认值 `"beam"` → `"tier"`；只有 `beam|cell|cells|beam*` 才进 cell 布局，其余（含空值）走 `startTier()`（`PotionTieredSession.java:232`）——即“每档一个 Session，档内 10→28→64→128 原地加宽”。
2. **提示不再依赖 cell 布局**：`PotionTieredSession.hint()`（`:683`）的判断由 `!cellMode ⇒ ""` 改为 `limits.length<=1 ⇒ ""`；tier 布局下 beam 后跟「（加宽 r/R 档）」并给基线净损；cell 布局仍跟「（r/R 档）」。
3. **phase 前置提示**：`PotionTieredSession.phase()`（`:656`）与 `QuickSession.phase()`（`search/QuickSession.java:271`，窄深搜分支）都把 hint 拼在“窄搜/加宽搜”之前；cell 分支行为不变。
4. cell 路径（`startCell()` `:288`、`cellDone()` `:465`、`nextCell()` `:222`）原样保留，供 A/B 与回滚使用，但不再是默认。

### 15.3 重复计算审查（结论表）

| 关系 | 是否重复计算 | 依据 |
| --- | --- | --- |
| 同一档内相邻 beam（tier 布局） | 否 | 同一 `Solver.Session` 上 `planWidening`/`widenToNextRung` 逐档加宽并复用前沿，不重建树 |
| 同一档内相邻 beam（cell 布局） | **是，真实重复** | `startCell()` 每个 cell 都 `new Solver.Session(root, beam…)` 且 `planWidening(null)` ⇒ 同档 4 个 beam 各从根重展开一次，再乘 (K+1) 档；这是“太慢”的主因，现已非默认 |
| 药水 1 瓶档 vs 2 瓶档（同序 beam） | 否（但前缀重复） | 两档至少三个参数不同：`limitPotionUses(k)`（0/1/2…）、起始 beam `tierBeam(base,t)=base+ceil(base×25%×t)`、`t>0` 的 `setCaps(max(2000,32×beam), max(60,beam/2))`；不是同一搜索，结果不可互替 |
| 档间“不含药水”的根前缀 | 是（结构性） | 每档 `new Solver.Session(this.root, …)` 从根重搜，该前缀被展开 K+1 次 |
| 同一档内结果合并 | 否 | 每档只 `merge()` 一次 `Result`；`baseline` 只取 tier 0 |

**消除档间重复的唯一干净办法**：在同一个 Session 上逐档放宽 `maxPotionUses`（先 0 瓶跑完 ladder，再原地放开到 1 瓶并复用前沿），等于改搜索核心的停车场/结算语义（`deferPotionCost` 与 `maxPotionUses` 剪枝在 `Solver.java:4526` 处直接判死节点），本轮**不做**，按用户 m04606 记为已知边界；若实测仍嫌慢，下一轮单独立项。

### 15.4 构建与安装

- `tools/build.ps1`：JDK 21.0.11、`--release 8`、555 entries，`BUILD OK`。
- `mod\sts1-engine-solver.jar`：1,295,807 B，sha256 `05D00831DD59261954FB63E659204680F8C32DED5095B740BA2587FF4067D973`；已复制到 `D:\Steam\steamapps\common\SlayTheSpire\mods\sts1-engine-solver.jar`。
- **未跑 `tools/run.ps1`、未启动游戏**（user m00300 / m00732）。

### 15.5 回滚

- 回 cell 顺序（P7b 行为）：`settings.txt` 加 `search.potionOrder=beam`。
- 回单档（P7 前）：`search.potionTiers=off`。
- 代码回滚：`Limits.potionOrderBeam()` 默认值改回 `"beam"`。

### 15.6 代价 / 风险 / 影响

- **代价**：作业 Session 数从 `(K+1)×4`（默认 16）降回 `K+1`（默认 4），同一预算下每档更深、树内不重复展开；总预算与节点上限公式未改；内存峰值仍为最宽那一档的树。
- **风险**：① P7b 的实测观察点（rung 外层日志、`potion cell:` 行）默认不再出现，需要 `search.potionOrder=beam` 才能复现；② tier 布局的早停按“档”触发（proven / 净损 ≤0），没有逐 cell 早停，宽 beam 可能一开始就用掉该档全部预算后才发现零伤；③ 档间仍有结构性重复（见 15.3），极端预算下会显得“同一段前缀跑了两遍”；④ 面板文案在 tier 布局多出「加宽 r/R 档」，若门禁对状态行做整行断言会红（本轮未跑门禁）。
- **影响（面板 / 自动出牌 / 搜索 / 兼容性）**：面板与状态行继续显示药水档提示（现在覆盖 tier 布局），示例 `药水 ≤1 瓶（2/3，槽 3）· beam 12（加宽 1/4 档）· 0 瓶基线 净损 15`；预计损失与自动出牌顺序口径不变（净扣血优先、并列少喝）；`solver.log` 默认回到 `potion tier: start/done`，`potion tiers:` 汇总走 `tiers=` 分支，`potion cell:` 仅 opt-in 时出现；settings 键不变；与其它 mod 无交互。
