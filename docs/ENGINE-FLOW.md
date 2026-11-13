# 出牌后流程与沙盒覆盖审计（P2-61 / B88）

> 用户 m25727：「要确定这种新的效果沙盒可以正确模拟。整理游戏出牌后的流程看沙盒模拟还有哪里不到位。」
> 本文是把「打出一张牌之后引擎到底做了什么」按源码逐段拆开，再逐条对到沙盒镜像手段上：哪些状态落在游戏自己手里（我们照抄）、哪些落在表现层（我们丢掉）、哪些落在我们没覆盖的地方。行号全部来自 `build/decomp6`（CFR 反编译的游戏 jar）。
> 结论先说：**出牌路径本身全部走真引擎，没有一处是我们重写的**；丢掉的东西里只有一类带状态（`ExhaustCardEffect`），本次已补算（B88），并新增「未知效果类」告警，以后新效果不会再悄悄漏掉。

## 1. 打出一张牌，引擎按这个顺序做事

| # | 位置 | 做了什么 | 沙盒怎么处理 |
| --- | --- | --- | --- |
| 1 | `characters/AbstractPlayer.java:1340-1366 useCard` | 攻击动画 → `c.calculateCardDamage(target)` → X 费/自动打出改写 `energyOnUse`/`freeToPlayOnce` → `c.use(this, target)`（卡自己的效果往动作队列里塞动作）→ `actionManager.addToBottom(new UseCardAction(c, target))` → `hand.triggerOnOtherCardPlayed(c)` → `hand.removeCard(c)`、`cardInUse = c` → 非免费时扣能量 → 手牌全不可用时点亮结束回合按钮 | 真引擎原样执行（副本上）；所有状态都在副本对象里 |
| 2 | `actions/GameActionManager.java:174-356 getNextAction` | 从 `cardQueue` 取牌 → 判 `canUse` → 跑 `onPlayCard` 钩子（玩家/怪物 power、遗物、姿态、blight、手牌/弃牌堆/抽牌堆里的每张卡，:210-238）→ 记账 `cardsPlayedThisTurn`、成就判定（:235-252）→ 目标已死/牌在 limbo 时**判废**（:255-269）→ 否则 `player.useCard`（:271） | `actionManager` 与 `player` 都是 CLONE 根（`Statics.java:76-122`）；记账/成就写进副本 |
| 3 | `actions/utility/UseCardAction.java:29-64` 构造 | `onUseCard` 钩子（玩家 power、遗物、手牌/两堆的卡、每只怪物的 power）；`exhaustCard` 由 `exhaustOnUseOnce/exhaust` 决定 | 同上；钩子里的动作也是真动作，进同一个队列 |
| 4 | `UseCardAction.java:71-133 update` | `onAfterUseCard` 钩子 → 清 `freeToPlayOnce/isInAutoplay` → purge 牌 → POWER 牌 empower → 清 `cardInUse` → 奇异勺子（`cardRandomRng`）→ 按 `rebound/shuffleBack/returnToHand` 决定去哪个堆（:110-127）→ 消耗触发 `checkForPactAchievement`（:126）→ `HandCheckAction`（:130） | 全在副本；RNG 是 CLONE 根；`checkForPactAchievement` 写成就 prefs，已被 B22/B25 重定向 |
| 5 | `cards/CardGroup.java:819-842 moveToDiscardPile/moveToExhaustPile` | 移堆，并给消耗堆一张卡挂一个 `ExhaustCardEffect`（:842） | 移堆是真引擎；效果对象被 `World.trimPresentation()` 丢掉 → 其**终态**由 `mirror/EffectSettle` 补算（见 §3） |
| 6 | `rooms/AbstractRoom.java:420-429 update` | `if (!AbstractDungeon.isScreenUp) actionManager.update()` —— **界面开着时动作队列冻结** | 沙盒照此冻结；`Driver.frameDelta()` 在屏开时给 1/60（B84） |
| 7 | `vfx/cardManip/ExhaustCardEffect.java:26-45 update` | 动画结束（duration<0）时 `c.resetAttributes()`；0.3s 起若牌不在手牌则 `c.fadingOut = true` | 效果被丢 → `EffectSettle.settle` 在丢之前补做同样的终态 |

## 2. 每一步的状态落在哪、我们怎么镜像

| 状态 | 载体 | 沙盒手段 |
| --- | --- | --- |
| 玩家、能量上限、手牌/抽牌/弃牌/消耗堆、limbo、power、遗物、姿态、充能球、blight、本回合出牌数 | `AbstractDungeon.player` 对象图 | CLONE 根（`mirror/Statics.java:76-122`） |
| 动作队列、相位、回合数、卡队列、怪物队列、本回合/本场出牌记录 | `AbstractDungeon.actionManager` | CLONE 根 |
| 怪物、房间、奖励、战斗结束标志 | `AbstractDungeon.currMapNode` | CLONE 根 |
| 当前能量 `EnergyPanel.totalCount` | 静态 int | 生成标量表（`GeneratedStaticTable`，见 §4） |
| 战斗里会变的其他标量（`TipTracker.blockCounter`、`Settings.seed`、`AbstractDungeon.floorNum`…） | 静态标量 | 生成标量表 404 项 |
| `AbstractDungeon` / `CardCrawlGame` 的**全部非 final 静态**（含对象引用） | 静态引用 | shell sweep：装时存、卸时还（值不改对象内部） |
| 12 个 RNG 流（怪物/卡/药水/事件/商店/奖励…） | `Random` 对象 | CLONE 根 |
| 表现层队列（`effectList/effectsQueue/topLevelEffects/topLevelEffectsQueue`） | 静态 List | CONTAINERS：按内容复制 → 每次 settle 清空（清之前补算终态） |
| 抽牌动画队列 `DrawMaster.drawList`、`TipTracker.tips` | 静态容器 | CONTAINERS |
| 战斗内界面（选牌/网格/奖励/横幅/overlay） | `AbstractDungeon.*Screen`、`overlayMenu` | CLONE 根；`cardRewardScreen/dynamicBanner/screenShake` 标 `discardOnReadBack`（永不写回实机） |
| 解锁/成就/角色存档 prefs | `UnlockTracker` 等 7 个 `Prefs` | `mirror/PlayerPrefs` + `SandboxPrefs`：写进 `preferences\sts1solver-sandbox` |
| 音乐/音效/网络/文件系统/外部平台 | `CardCrawlGame.*`、`Gdx.*` | `mirror/ExternalMute`（窗口内惰性替身，卸下前先 end） |
| mod 的静态标量 | mod 类静态 | `mirror/ModStatics`（本次默认安装 1 项 / 全 mod 355 项） |
| mod 的 `static final` 卡牌容器（如 `ModHelper.cards_Inside`） | 静态 List | `Kind.MUTABLE_CONTAINER`：装时快照实机内容 → 原地换沙盒副本 → 卸时还原 |

## 3. 剩下的不到位（按严重度）

### 3.1 已修：效果终态只在 `ExhaustCardEffect` 一处漏了（B88 / P2-61）

审计游戏 jar 里 **293 个 `AbstractGameEffect` 子类**，逐个看 `update()` 是否改战斗状态，只有 4 个命中：

| 类 | update() 里改了什么 | 结论 |
| --- | --- | --- |
| `vfx/cardManip/ExhaustCardEffect` | `c.resetAttributes()`（block/damage/magicNumber/costForTurn），并把 `c.fadingOut` 置真 | **战斗内，必须补算** |
| `vfx/campfire/CampfireSmithEffect` | 读网格选牌、升级牌 | 营火，不在战斗沙盒内 |
| `vfx/campfire/CampfireTokeEffect` | 读网格选牌、删牌 | 同上 |
| `vfx/campfire/CampfireSleepEffect` | `player.heal(healAmount)` | 同上 |

B76 只补了「牌进消耗堆」这一处（`mirror/ExhaustAttrs` 按消耗堆张数变化触发）。审计发现另外两处**完全没覆盖**：

- `GameActionManager.java:255-269`、`:275-285`：牌在 `limbo` 里被判废（目标已死/打不出来）时，引擎给这张牌挂 `ExhaustCardEffect`，牌被移出 limbo、不进任何堆；
- `GameActionManager.java:368-381 callEndTurnEarlySequence`：提前结束时清 limbo，给每张 limbo 牌挂 `ExhaustCardEffect`。

修法：`mirror/EffectSettle`。`World.trimPresentation()` 在清空四个效果容器**之前**遍历它们：

- 遇到 `ExhaustCardEffect`：读私有字段 `c`，照 `update()` 的终态补做（不在手牌则 `fadingOut = true`；`resetAttributes()` 无条件执行）；
- 遇到其他效果类：按类名记一次日志（每进程最多 40 个类），这样**将来游戏更新或 mod 引入新的带状态效果会出现在日志里**，而不是悄悄丢掉。

门禁 **S144**（`Probe.java`）：沙盒里造两张带「本回合改动」的牌（一张已离手、一张仍在手），挂 `ExhaustCardEffect`，再挂一个纯表现效果，然后 `trimPresentation()`；断言 cost/damage/block 三项被复位、离手那张 `fadingOut=true`、在手那张不淡出、纯表现类被记录、实机那张牌没被改。实测 `ok settled=true costReset=true dmgReset=true blockReset=true fading=true inHandReset=true inHandFading=false noted=true liveUntouched=true`。

`ExhaustAttrs` 保留：它覆盖的是「效果对象已经因为别的原因被丢掉、但消耗堆张数变了」的路径，两者重复执行是幂等的。

### 3.2 设计边界：mod 的非 final 静态**引用**字段是共享的（不修，已量化）

`ModStatics` 只镜像标量（primitive/String/enum/Color）。mod 类里的非 final 静态对象（集合、`Random`、单例）在沙盒窗口内与实机是**同一个对象**。实测（`tools/audit/static_refs.py`）：

- 游戏 jar：1014 个非 final 静态引用字段，绝大多数是 Texture/Atlas/UI（表现层，写它们不影响判定）；
- mod jar（用户装的全部 17 个）：398 个，其中「战斗相关类型」90 个，全部在 loadout / RelicStats / MapMarker / Feminization 这类**界面/统计** mod；`CaiNongMod`、`ninja`、`mintySpire` 都没有战斗相关非 final 静态。
- 结论：当前安装下**不是**实况风险；将来若有 mod 用静态集合存战斗状态，登记进 `Statics.MOD_CONTAINERS` 即可（P2-62 记录在案）。

### 3.3 设计边界：沙盒里跑到的 mod 钩子会写 mod 自己的统计

`onPlayCard`/`triggerOnCardPlayed` 等钩子会调用 mod 代码。写存档的路径已重定向（B22/B25/B47），但写 mod 自己内存统计的（如 RelicStats 的计数器）不会还原——只影响统计显示，不参与战斗判定。

### 3.4 已知局限（前序板块登记）

- 同一个动作连问**同一个堆**两次选牌，会被当成一问（`Driver.selectionQuestion()` 用堆对象身份判定；现有引擎/mod 卡的两次选择都换堆，见 P2-57）。
- 沙盒不模拟营火/事件界面（`Campfire*` 效果），非战斗相位本来就不在求解范围内。

## 4. 怎么复跑这个审计

```powershell
# 效果类：找出 update() 里改战斗状态的 AbstractGameEffect 子类（需要 build/decomp6 这份 CFR 反编译）
F:\python\python.exe tools\audit\vfx_state.py build\decomp6

# 静态引用字段：游戏 jar 与每个 mod jar 各扫一遍（对照 mirror/Statics 的四类镜像手段）
F:\python\python.exe tools\audit\static_refs.py "D:\Steam\steamapps\common\SlayTheSpire\desktop-1.0.jar"
```

产物（本次）：`evidence/audit-effects-20261102.txt`、`evidence/audit-statics-20261102.txt`。

### 边界：`Collections.shuffle(List)`（不带 Random）—— 不镜像

`java.util.Collections` 在第一次用到时才 `new` 一个私有静态 `Random`，它**不挂在任何游戏对象上**，
镜像表（`Statics.ROOTS`）与克隆都碰不到它。desktop-1.0.jar 里共 4 个调用点，战斗内只有一个：
`DiscardAtEndOfTurnAction.update`（决定「结束回合弃牌的顺序」）。
影响范围：弃牌顺序；**不影响**任何抽牌结果、伤害、格挡或遗物触发（它们走 `AbstractDungeon.*Rng`
与 `com.badlogic.gdx.math.MathUtils.random`，两者都已在镜像里，见 `Statics.java` RNG 段）。
结论：登记为已知边界，不引入反射换 Random（会与 Java 8 的包可见性打架，收益只是洗牌顺序）。
