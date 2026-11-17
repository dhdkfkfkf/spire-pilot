# Spire Pilot

<p align="center"><img src="docs/assets/logo.png" width="220" alt="Spire Pilot"></p>

A combat solver for **Slay the Spire 1** (1.0 / 12-18-2022) that does not model the game.

It **mirrors the real combat engine into an isolated copy**, lets the **game's own card, monster,
power and relic code run on that copy**, and searches the **states that really came out** of it. The
search result is a line, and executing a line is audited against the states the search predicted.

> **Upstream.** This repository is a derivative of
> [STS1 Engine Solver](https://github.com/zck070718/sts1-engine-solver) by 闪珖灵感菇 (MIT). It continues
> from that project's 1.0.3 and rolls its later 1.0.4–1.0.15 work into this 1.0.0 release. The panel
> layout, the sandbox mirror and the search pipeline come from there; the texture guard, the 3-turn
> horizon, the time-budget tiers and the turn-level ranking are this repository's changes. Full
> attribution and license texts: [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

The architecture follows the reference project [CombatSolver (STS2)](https://github.com/Torch1230/CombatSolver):
capture a root state, simulate in the background without touching the live fight, print the route,
execute through the game's native entry points, and re-solve when reality diverges. The mechanism had
to be different because STS1's engine is Java plus global static state, so "background simulation"
here means *cloned worlds swapped into the static slots*, not a re-implementation.

---

## Why not simulate the cards by hand

A previous attempt in this workspace (`sts1-combat-solver`) re-implemented cards, monsters, powers,
relics and RNG in a hand-written model. It worked, and then bled:

* ascension damage tiers were missing per move, so a **12-damage intent matched nothing** and the
  panel showed a different move entirely;
* "ghost" moves existed in the model that the engine never has, so live intents could not be matched;
* `Slimed` was classified "cannot be simulated", so the search expanded **one node** and reported
  that non-solution as the best line;
* shuffle timing and draw-pile order drifted from the engine and produced "play a card you don't have".

Every one of those is a *transcription* bug. The fix chosen here is to stop transcribing: copy the
objects and call the real methods.

## Compatibility

The solver calls the game's own code, so it does not need a per-card table: **in theory it works with
any mod that adds content — cards, relics, potions, characters, monsters, events — without changing how
combat itself resolves.** A mod that rewrites combat rules (damage calculation, action queue, turn
structure, draw/shuffle order) is outside that boundary and may simulate differently from the live
fight. The solver also refuses to expand a branch it cannot replay faithfully and names it at the
bottom of the panel instead of guessing, so an unsupported interaction shows up as "未展开" rather than
as a wrong optimal line.

---

## How it works

1. **Mirror.** `World.captureLive()` deep-copies the combat object graph (player, piles, monsters,
   powers, relics, potions, orbs, the action manager, 13 RNG streams) without running a single
   constructor, and records the 453 process-global fields that hold combat state.
2. **Sandbox.** `World.install()` writes the copy into those static slots, so the engine's own code
   now operates on the copy; `uninstall()` writes the live values back. The live fight is never the
   thing being simulated.
3. **Execute real code.** A step is `AbstractCard.use` / a potion / the End Turn button, driven
   through `GameActionManager` with a synthetic frame delta (and the room's per-frame object pump,
   without which drawing stalls). No card effect is written by this project.
4. **Search.** Beam search over `World` copies: each edge is a real play, each node a real state,
   deduplicated by a readable state digest. Objective: survive, win, then lose the least HP, then
   spend the fewest potions.
5. **Execute with audit.** Each planned step stores the digest the search predicted. Executing
   compares the live digest after every action; the first mismatch invalidates the plan and asks for
   a re-solve. The plan is never allowed to disagree with the game.

### 面板按钮（唯一的正式操作方式）

面板固定在屏幕右上角，全部操作只用鼠标完成：

| 按钮 | 作用 |
| --- | --- |
| **计算** | 开始计算当前战斗；计算进行中再点一次即为停止（标签随状态变化） |
| **执行一步** | 提交计划中的下一步；游戏真正执行完后再比对摘要，不符即作废整条路线 |
| **自动出牌** | 开关。开启后按「节奏」逐步执行整条路线，直到战斗结束 |
| **秒数 / 节点** | 深度搜索的时间与节点预算，点击循环档位 |
| **节奏** | 自动出牌每步之间的间隔（默认 750 ms） |
| **药水** | 允许或禁用求解过程中使用药水 |
| **局外收益** | 是否把局外收益（金币、最大生命、永久伤害、升级、药水）计入评分 |
| **收起 / 隐藏** | 收起面板为一条标题，或隐藏整个覆盖层；隐藏后点屏幕边缘的恢复标签即可回来 |
| **导出报告**（标题行右侧小按钮） | 把日志尾部、设置、版本与当前路线打包成一个 zip，写到 `%USERPROFILE%\spire-pilot\reports\`，并在资源管理器中定位；用户目录会被替换成 `%USERPROFILE%`，不含存档 |

> 1.0.0 起，F5–F12 的按键分支已从代码中删除（m03455）：唯一的操作方式是上面的面板按钮，按键不可能启动搜索、改预算或触发自检。方向键只负责滚动路线区。残留的 `autotest.request` 也不会在玩家机器上跑自检——那需要一个 `%USERPROFILE%\spire-pilot\debug.keys` 标记，只有作者的 `tools/run.ps1` 会创建它。

The overlay follows the STS2 solver's route-row model (read from its `SolverRouteRow`, not guessed):
one row per TURN, one line per action, **every action names its card and its target**, the turn row
carries the turn's outcome (HP / energy / block) and each action carries the target's HP after it.
The current step is marked `▶`, and anything the solver refused to expand is NAMED at the bottom
("未展开（会打开选择提示）: …").

```
Spire Pilot  [win line found]
可赢 · 预计掉血 10 · 3 回合 · 778 节点 · 1.2s
第 1 回合            HP 74  能量 0
▶1. 燃烧
  2. 打击 → 邪教徒    敌 51/51
  3. 结束回合          → 第 1 回合结束
第 2 回合            HP 80  能量 3
未展开（会打开选择提示）: Prepared, Survivor 等 2 张
[计算] [执行一步] [自动出牌] [收起] [隐藏]
```

On screen it also POINTS at what to do, the way the STS2 panel does: a box on the card to play, a red
box on the target monster with a dotted line between them, or a box on the End Turn button. A self-buff
or a potion with no target draws no target box -- naming a wrong monster would be worse than naming
none.

The hint appears as soon as a combat starts, so "the mod is loaded" is visible before any solve.
Rendering is in the game's virtual coordinate space; `Hud.demo()` (no key binding since 1.0.0)
draws a fixed banner, which separates "the panel cannot draw" from "there is no plan".

---

## 更新日志

> 下面的 1.0.4–1.0.15 是上游项目 STS1 Engine Solver 的版本号，本仓库把它们合并为 1.0.0 一次发布；
> 版本号保留原样，是为了让从上游过来的玩家能对上自己记忆里的改动。

搜索这一侧的目标很朴素：**牌多、怪多、怪血厚的时候，也要在你能接受的等待里给出一个能用的答案**。下面按版本拆开写，每个版本只做一件事。

### 1.0.4 — 选牌脚本显示中文

- 计划行里的选牌提示过去会印出内部 id（例如 `（选：PiercingWail → 确定）`），现在优先显示游戏里的中文名，只有该段没有显示名时才退回 id。

### 1.0.5 — 第一次纹理守卫，加三个默认关闭的开关

- 内置纹理守卫的第一版：在球图标、能力图标、闪光效果、治疗面板四处按字段拦截空纹理。它在球类角色上有效，但每处都是"猜 owner"，落地不全。
- 新增三个默认关闭的开关：`search.dominance`（同层里格挡更少的状态直接丢）、`search.symmetricTargets`（合并外观完全相同的怪）、`panel.timing`（状态行显示本帧耗时）。默认关闭的理由写在 `Limits` 里：它们是"会改变答案"或"只给诊断"的东西，必须实测过才允许默认开。

### 1.0.6 — 排序与克隆的成本

- 回合级排序（`search.turnPlan`，默认开）：先用手牌 0/1 背包算出"这回合能不能杀"，再用怪物意图算出"这回合必须不必须防"，据此把斩杀、防御、药水的候选排到前面。只排序，不删分支——估错只花时间，不丢答案。
- 束宽多样性（`search.diversity`，默认开）：一层被截断时，先按动作类型（攻击/技能/能力/药水/结束回合）各保底一个，再按分数填满。纯分数截断可能把整条束都花在攻击上，而活过这一回合的恰好是被丢掉的那条防御线。
- 克隆器身份表按上次规模预分配（`Cloner.observe`），减少每次 fork 的扩容次数。

### 1.0.7 — 纹理守卫改成总闸门

- 删掉 1.0.5 里四个"按字段猜 owner"的补丁，改为唯一补丁：`SpriteBatch.switchTexture` 的前置拦截。纹理为 null 或宽度读不出来时不再执行原方法（原方法下一句就是 `texture.getWidth()`，也就是崩溃点），改为刷新批次并换上一个已加载的占位纹理。
- 同一个调用点只记录一次完整栈（`tex: unusable texture (…) … first call site: 类.方法:行`），把"猜"换成"看栈"。日志里 `tex guard: active` 表示补丁确实挂上了 libgdx 类。

### 1.0.8 — 纹理守卫的收口

- 由 1.0.7 的总闸门承接全部职责，删除遗留的四个旧补丁类，`ModTheSpire.json` 与启动日志的守卫清单同步为一条。

### 1.0.9 — 目标后置

- 每张单体牌原本对每只活怪各生成一个分支，每个分支都要克隆一次沙箱。现在按「同一张牌（含升级/费用）」分组，只保留：能斩杀的目标、意图伤害最高的目标、有效血量最低的目标。
- 组内任一目标带能力（荆棘、缓冲、愤怒、人工制品、mod 自带 power）时整组保留——这类怪换目标结果真会变，不猜。
- 开关 `search.targetFocus`，日志 `target focus: kept X of Y branch(es)`。

### 1.0.10 — 推演到 3 回合就收

- 新增 `search.horizonTurns`（默认 3）：只展开到当前回合 +3 回合，到线的节点不再克隆，用它已有的评分参与竞争；执行一步后再从新局面重搜（滚动规划）。
- 面板不再因为"要把战斗推到底"而长时间无结果；`0` 回到旧行为（推到结束），探针实例固定 `0`，自检门不受影响。
- 日志：`horizon: planning 3 turn(s) ahead only; stopped expanding at layer …`。

### 1.0.11 — 秒数档位与自动预算

- 「秒数」按钮从"0 或 300s"改成一条档位：`自动` → `自动(6s)` → `自动(12s)` → `自动(18s)` → `自动(30s)` → `自动(60s)` → `无限`。
- `自动` 按眼前的场面算：`4 + 2×活怪数 + Σ(血量+护盾)/40` 秒，夹 6–90 秒，BOSS 房翻倍。单只史莱姆约 7 秒，四只合计 300 有效生命约 20 秒。数字档（6/12/18/30/60）按你选的原样执行，BOSS 不翻倍、也不抬到别的下限。
- `无限` 同时取消回合上限（没有等待要限制时，回合上限只会砍掉答案），改由"连续 6 层最优分不动"收尾。

### 1.0.12 — 按时间分阶段降级

- 数字档位是硬上限：到了就停，返回预算内最好。中途按 45% / 75% 两个时间点降深度：45% 还没走完 2 回合就放弃 3 回合，75% 还没走完 1 回合就只保 1 回合。
- 只有真的越过该时间点才降级；提前降级会让搜索"刚开局就收工"，那是 1.0.11 曾经的真实故障。
- 日志：`stage: …s of …s used, N turn(s) finished; promise lowered to M turn(s)`。

### 1.0.13 — 不交付半回合

- 全程记录"真正走完过回合"的最好一条线；收尾时若最优线停在本回合中间，而存在走完回合的线，就交付后者。
- 斩杀的线例外：立刻赢比"整回合"重要。
- 日志：`plan: best line stopped mid-turn; delivering …`。

### 1.0.14 — 「计算」可以重算

- 计划缓存按局面 digest 命中，同一局面再按「计算」过去只会返回旧答案。现在手动按键绕过一次缓存，自动出牌与重规划仍走缓存。
- 计算中按键 = 收尾给结果（不清空已有计划）；已有结果时按键 = 重新计算。
- 日志：`solve: plan cache bypassed (manual press wants a new answer)`。

### 1.0.15 — 自动预算按"一回合能打多少动作"算，并且会长

- `自动` 档加动作项：`手牌数 + 本回合已打出的牌数` 超过 5 后每多 1 个 +0.5 秒，最多 +6 秒。公式：`4 + 2×活怪数 + Σ(血量+护盾)/40 + 动作项`。
- 为什么不只看手牌：有角色"每打一张抽一张"，手牌上限 5 因此永远是 5，可一回合能连着打十几张——只看手牌会把这些牌全漏掉，而这正是最费时间的一类局面。
- 动态延长的另一半在搜索里：沙箱里任何一回合链到比开局更多的动作数时，按**实际见到的峰值**再延长，每多 1 个 +0.4 秒、总计最多 +6 秒。只对 `自动` 生效——6/12/18/30/60 是你选的数字，属于承诺，不会被偷偷改。日志：`budget: a turn chained through N action(s) …`。
- 单回合动作数 ≥ 20 时允许交付半回合：此时凑满一整回合的克隆代价超过收益，1.0.13 的"必须整回合"在这条线以上让位。日志：`plan: half a turn delivered on purpose (N actions in one turn)`。

---

## Automated evidence

The mod has a harness mode: if `%USERPROFILE%\spire-pilot\autotest.request` exists at
startup, it builds real combats **inside the running game**, runs the suite at the main menu, writes
the verdict to `solver.log` and halts the process. That makes "build → launch → verdict" one command
and lets every change be re-validated.

```powershell
powershell -NoProfile -File tools\install.ps1     # build + install into the game's mods dir
powershell -NoProfile -File tools\run.ps1         # launch, run the suite, print this run's log
```

Last full run: **54 checks, 0 failures**. Highlights:

| Check | Result |
| --- | --- |
| Synthetic fight built and started from nothing | turn 1, opening hand 5, energy 3, monster intent rolled |
| Mirror installed identifies with the live fight | digest equal, including monster `moveHistory` and RNG counters |
| Same action on live and on the mirror | **byte-identical resulting digests** |
| 12 mirrored steps across a turn boundary (2 monsters, Strength, Vulnerable, block, draw, shuffle) | **24/24 state comparisons exact** |
| Search on the first fight | win, **5 HP lost**, 778 nodes, 992 ms, 12 layers |
| Every predicted state reproduced on the live engine | **12/12 steps, 0 mismatches** |
| Solver line vs a naive "first playable card" policy | 0 HP lost vs 4 (illustrative, one fight) |
| Search leaves the live fight untouched | digest equal before/after |
| Sandbox actions leave the live fight untouched | digest equal, 3 actions executed |
| Static leak check around a sandbox session | **457 inventory fields, 0 changed**, restore exact |
| Gameplay path (F8/F9/F10 logic) on a real fight | solved, executed, **won at 74/80**, 0 drift |

## Content coverage (measured, not claimed)

There is no card table, no monster table and no relic table in this project. Coverage is therefore a
question about the ENGINE on a mirrored world, and the only honest way to answer it is to run every
piece. `tools/run.ps1 -Mode coverage` does exactly that, inside the running game:

| Content | Count | Result |
| --- | ---: | --- |
| Cards | **541** | **472 played for real**; 40 refused by the game's own rules; 30 opened a card-choice prompt (the one real gap); **0 exceptions** |
| Potions | **46** | **44 drank and resolved**; 1 death-only (Fairy Potion); 0 exceptions |
| Relics | **210** | **206** ran the full hook sweep; 4 throw only because they are out-of-combat card-transform relics (Pandora's Box, War Paint, Whetstone, one mod placeholder) whose `onEquip` needs a run-level screen |
| Monsters | **67** | **63** initialised and took two real monster turns; 4 have no constructor the sweep can reach; 0 exceptions |
| Characters | **6** | **6/6 built, mirrored, SOLVED and played** — Ironclad, Silent, Defect, Watcher, plus the **CaiNong** and **Ninja** mod characters |
| Leak | 397 static fields | after the **entire** sweep (370 cards + 42 potions + 189 relics + 62 monsters): **0 fields changed**, live fight digest unchanged |

Six more cards are only "unplayable" because of the test situation (3 energy, mixed hand) and play
fine with 99 energy and a clean hand: Blood for Blood, Meteor Strike, Clash, Sands of Time,
Force Field, Signature Move.

**These numbers are mod-inclusive**, and that took a fix: ModTheSpire's `--mods` flag FILTERS the load
list, so an earlier sweep reported 370 cards and "characters: 4" while claiming to cover the install.
`tools/run.ps1 -Mode coverage -AllMods` reproduces the player's real launch (19 mods, 21 jars).

**Mod characters work, with one rule learned the hard way**: a content object constructed OUTSIDE the
mirror can capture `AbstractDungeon.player` — the LIVE player — and then hit it. The sweep exposed
this (The Guardian's NoBlock/Weakened ended up on the live player). Potions, relics and monsters are
now all constructed *inside* the mirrored world, and the "live fight untouched" gate passes for an
entire 541-card / 46-potion / 210-relic / 67-monster sweep.

The sweep is also a harness test. Its first run found three run-level statics the sandbox was missing
(`AbstractDungeon.topPanel`, `lastCombatMetricKey`, `CardCrawlGame.dungeon`), which produced
NullPointerExceptions in the exhaustion path, `DaggerSpray` and the hand-select screen. The scenario
now installs a real run shell (`Exordium`), so those cards run instead of failing.

What this does **not** prove: coverage of every card, relic and monster *in every board state*. It does not need to, because
the engine executes them -- that is the point of the design. It does mean a real card this project has
never seen is *more* likely to work here than in a hand-written model, not less.

---

## Limits (current build)

* **Card-choice prompts** (discard / search / Scry style) are detected and the branch is refused
  ("a screen is open" blocks a solve), not modelled. A branch that would open one is dropped by the
  search rather than guessed.
* **Potion targeting** picks the first living monster for targeted potions.
* **Search horizon** is actions, not turns, with a wall-clock budget (3 s in game).
* **No map/pre-combat planning**: this solves the fight that is running, like the reference project's
  in-combat overlay (its pre-combat forecast needed a headless worker process, which STS1's asset
  chain does not allow -- documented in the predecessor's notes and not attempted here).
* Multi-threaded search is not implemented; the sandbox swaps global statics, which must be
  single-threaded by definition.

---

## Layout

```
src/main/java/sts1solver/
  SolverMod.java            mod entry, keys, harness auto-run
  Gameplay.java             solve the live fight, execute with per-step audit
  drive/Driver.java         frame-delta proxy, sound mute, drain, play, end turn, soul pump
  mirror/Cloner.java        constructor-free deep copy, identity-preserving
  mirror/ClonePolicy.java   what is copied, what is shared, what is linked
  mirror/Statics.java       the 453 global fields that hold combat state
  mirror/World.java         install / uninstall / fork / digest
  mirror/Digest.java        readable state fingerprint (dedupe + audit)
  mirror/LeakCheck.java     generated static inventory; proves nothing leaked
  search/Legal.java         candidate actions from the engine's own canUse
  search/Solver.java        beam search over real states, plan replay
  scenario/Synthetic.java   builds a real combat from nothing (tests only)
  probe/Probe.java          the automated suite
  ui/Hud.java               plan overlay
tools/                      build, install, run, javap helpers
research/javap/             decompiled engine signatures used while building this
```

## Build requirements

* Slay the Spire 1.0 at `D:\Steam\steamapps\common\SlayTheSpire` (override with `-GameDir`)
* ModTheSpire 3.30+ and BaseMod in the workshop directory
* A JDK for `javac` (the game itself runs on its bundled Java 8; the jar is built as Java 8 bytecode)