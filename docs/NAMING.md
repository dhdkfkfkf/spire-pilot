# 源码命名对照（反编译产物的读法）

本工程的 `src/main/java/sts1solver/` 下有相当一部分代码来自 CFR 反编译的真实战斗引擎调用点。
反编译会留下 `bl`、`string2`、`object`、`n3`、`abstractMonster` 这类**无语义的局部名**。
这份文档说明：哪些方法已经整理成人读的名字、名字对应什么含义、以及还剩哪些没整理（P2-5b）。

## 为什么保留反编译代码而不是重写

路线是「镜像真实引擎 → 在副本上跑真实代码 → 搜索真实状态」。凡是能直接调用游戏自己的类（`Driver`、`mirror`、`search` 里的调用点），
我们就不重写规则：重写容易和原版行为漂移，而调用原版代码几乎不会漂。
代价是这些调用点必须按原版 API 的形状写（大量 `AbstractCard`/`AbstractMonster`/`ArrayList`），反编译名顺手就留下来了。

## 命名约定（整理后统一使用）

| 旧名（反编译） | 现在的名字 | 含义 |
|---|---|---|
| `bl` / `bl2` / `bl4` | `combatOver` / `inRoomCombat` / `active` | 战斗是否结束 / 是否在战斗房间 / 面板是否按「进行中」处理 |
| `list` | `rows`（面板）或 `items`（join） | 面板的行 / 待拼接的字符串 |
| `stringBuilder` | `text` / `executed` / `row` | 字符串拼装器（按用途命名） |
| `string` / `string2` / `string3`… | `reason` / `cacheKey` / `refusal`… | 按真实用途命名 |
| `n` / `n2` / `n3`… | `firstRow` / `ascension` / `repeats` / `hpLost`… | 按真实用途命名 |
| `arrayList` | `rows` / `monsters` / `potions` | 按内容命名 |
| `abstractMonster` / `abstractCard` / `abstractPotion` | `monster` / `card` / `potion` | 引擎类型名缩写掉前缀 |
| `throwable` / `object` | `ignored` / `value` | 被吞掉的异常 / 反射取到的值 |

命名原则：**名字描述它在战斗里的角色，不描述它的类型**。`hpBefore`、`folded`、`resumeAutopilot` 比 `n4`、`n6`、`bl` 有用。

## 已经整理过的方法（P2-5，2026-10-30）

这些都是高频阅读/修改的路径，改完行为必须一模一样（门禁 362/0 兜底）：

| 文件 | 方法 | 做了什么 |
|---|---|---|
| `Gameplay.java` | `lines()` | 整段重写：`arrayList`→`rows`、`charSequence` 复用（先 StringBuilder 后 String 的 cast 玩法）拆成 `executed`/`growthRow`、`bl`→`uncertified`、`n2`→`ascension`、`n3`→`first`、循环里 `n6`→`repeats`、`string2`→`delta`。53 个字符串字面量逐字保留（含 `" 回合            HP "` 的 12 个空格）。 |
| `Gameplay.java` | `tick()` | 删掉未使用的 `bl3`（反编译残留），`bl/bl2/bl4` → `combatOver`/`inRoomCombat`/`active`。 |
| `Gameplay.java` | `maybeAutopilot(boolean)` | 形参 `bl` → `fightActive`。 |
| `Gameplay.java` | `panelText()` | `list`/`stringBuilder` → `rows`/`text`。 |
| `Gameplay.java` | `join(List,int)` | `list`/`n`/`stringBuilder` → `items`/`max`/`text`。 |
| `Gameplay.java` | `replanFromCurrentState(String)` | `string`→`reason`、`string2`→`cacheKey`、`bl`→`resumeAutopilot`。 |
| `Gameplay.java` | `stopAutopilot(String)` / `fail(String)` | 形参 → `reason` / `message`。 |
| `Gameplay.java` | `solveLive()` | `string2`（与 `string4` 重复的一次 digest）与未用的 `n`/`object` 删掉；`string`→`digestAfterSearch`、`result`→`found`、`string3`→`blocked`、`l`→`startedAt`、`string4`→`digestBeforeSearch`、`string5`→`cacheKey`、`result2`→`cached`、`snap`→`renderSnap`、`viewState`→`viewSnap`、`l2`→`budgetMs`、`n2`→`aliveMonsters`、`arrayList`→`monsters`、`abstractMonster`→`monster`、`wide`→`widePass`、catch 变量 → `ignored`/`error`。 |
| `Gameplay.java` | `decideEndTurnRisk` / `predictEndTurnRisk` / `intentDamage` | `endTurnRisk`→`risk`、`n`/`n2`/`n3`→`playerMaxHp`/`margin`/`hpBefore`/`hpAfter`/`incoming`/`total`/`damage`/`hits`、`bl`→`manual`/`installed`、`object`→`raw`、`abstractMonster`→`monster`。 |
| `Gameplay.java` | `executeStepInternal` | **形参 `bl` 全方法未使用**（旧签名 `(true)`=手动、`(false)`=自动，但两种调用行为相同），直接删掉；两处调用点同步改。改完套件仍 387/0，说明它确实是死参数。 |
| `Gameplay.java` | `submit` / `pollPending` | `abstractCard`/`abstractMonster`/`abstractPotion`→`card`/`target`/`potion`、`arrayList`→`monsters`/`potions`、`l`→`now`、`bl`→`endsTurn`、`string`→`digestNow`。 |
| `Gameplay.java` | `EndTurnRisk` 构造器 | 形参 → `hpBefore`/`hpAfter`/`incoming`/`ok`/`error`（字段名本来就叫这些）。 |

## 还没整理的（P2-5b 收口后的剩余，✅ 有意保留）

`Gameplay.java` 里已经**没有** `bl`/`n2`/`string2`/`abstractMonster`/`arrayList` 这类名字（`grep -w` 为 0）。
剩下的两处是**有意保留**的：

| 位置 | 规模 | 为什么保留 |
|---|---|---|
| `probe/Probe.java` | 439 处反编译局部名 | 这是**门禁代码**，故意保持「一个 PASS 一行断言」的扁平写法；改名的唯一收益是可读性，而它是内部工具。 |
| `search/`、`mirror/` 里少量 `bl`/`n2` | 零散 | 同上：它们在热路径的局部作用域里，改名要重跑 387 条门禁，收益低于风险。 |

## 改这些代码时的铁律

1. **只做定点 `edit`，绝不整读整写大文件**：`Gameplay.java`（1332 行）、`Solver.java`、`Probe.java` 都被整写截断过，恢复靠 CFR 反编译再修类型。
2. **字符串字面量逐字不动**：面板文本被门禁（S31/S37/S47 等）与真机习惯依赖；改字必须同步改门禁。
3. **重命名一次只动一个方法**，然后 `build → install → run.ps1`（套件跑的是**已安装** jar，不是 `mod/` 里的）。
4. 想确认某个改动是否纯改名：`git diff --word-diff` 看非标识符字符有没有变化。
