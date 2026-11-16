# 实机复测清单（2026-11-01 重建）

> 原 TESTING.md 在 2026-11-01 与 PLAN.md 一起从磁盘消失（回收站也没有），这里重建为「当前状态 + 本轮复测清单」。历次门禁明细见 evidence/*.txt 与 build/run-*.log。

## 当前状态

- 安装 jar（2026-11-15，B187 口径）：`D:/Steam/steamapps/common/SlayTheSpire/mods/sts1-engine-solver.jar`，sha256
  `E85332F8C3B26B6B44C8CAF00CF04D9F2E3E1421013692557C38BB8E51E4A08D`（1,287,415 B；**B187＝沙盒隔离收口（机制 M51）**：新增 `mirror/SandboxClip.java` 惰性替身，窗口内 mod 的 `Clip` 静态（连 null 的也）全换替身、`play()` 因 `currentClip != null` 早退、一次 `AudioSystem.getClip()` 都不发生；`AudioQuiet.end()` 每步 `isOpen()` 守卫、快照已关闭写 null 并计 `closedSnapshot`；`World.install()` 的 `AudioQuiet.begin()` 挪到 `StaticWatch.afterInstall()` 之后；S255 重写 **37 条**新契约 + 新增 S257(2) 真机开关冒烟（无设备 SKIP 不算通过）；**本轮未跑门禁，待用户实测**。旧：B186 口径＝`F24DE109C2D77A211923B62D6226BC3AE7CE720754B5337A35510BAD743D1F83`（1,280,074 B；**B186＝S171 预览门禁不再把机器速度当常量**（只改测试代码，产品代码零改动）：(a) 预算 1200→2000ms、(b) →9000ms 且当上限（采到预览即 `Gameplay.stopQuickSolve()`），断言仍 10 条、S171 10/0；旧：B185＝沙盒窗口内不许真开 `javax.sound` 音频线**：新增 `mirror/AudioQuiet.java`，扫全部 mod 静态字段缓存 `Clip` 型字段（实测仅 `yuaimod.YuAiMusic#currentClip`），`World.install()` 在 `MusicMute.begin(true)` 之后 `begin()`、`World.uninstall()` 在 `StaticWatch.beforeUninstall()` **之前** `end()`，卸窗口时对「窗口内新建」的 Clip `stop/flush/close` 后还原快照；候选字段强制 `setAccessible`，扫不开的进 `blind` 并 `warnEvery`；回滚＝删 `World` 两处挂点；S255(16)，反向验证 `passed=4 failed=10`，带 `-Mods YuAiBloodMusic` `16/0`；core 待复跑确认（S171 修复前全量 755/6 全在该门、其余 751 条全绿；S171 单跑 10/0；02:46 用户叫停复跑）（S1–S255）。旧：B184 口径＝`56486847279F6FCF439EAF0A52C0289800495624DABCF794A87D2B7B8D633AF7`（1,258,662 B；**B184＝白名单帧长分档 + Profile 计时器接通**：新增 `drive/FramePlan.java` 只给 6 个动作类计划快帧（`DrawCardAction`/`FastDrawCardAction`/`defect.ScrapeAction`/`unique.RipAndTearAction`/`loadout.actions.EasyXCostAction` 窗 `{0.0f}`、`unique.BurnIncreaseAction` 窗 `{1.5f}`），非白名单类**永远** 0.05 s；白名单类前 3 帧 0.05 s；落点 `duration - stop + 0.001` 不越窗；`Driver.frameDelta()` 顺序 屏帧(1/60) → 计划 → `simDelta`；回滚键 `settings.txt search.frameTiers`；`Solver.Profile` 六个死计数器接通、切片路径也打 `profile:` 行；S251(4)/S252(3)/S253(3)，反向验证 `passed=8 failed=2`；core 739（S1–S253）。旧：B182 口径＝`8B0E1D9054CEF4600E03E6755520415EA43A72CBAD797E4757A73525F9E2531B`（1,247,388 B；**B183 已回退 ⇒ 当时是 B182 的帧长语义**：无 `FramePlan`、`frameDelta()` 直接返回 `simDelta`、Probe 无 S251/S252；回退前的 B183＝**沙盒帧长按动作分档 + 阈值落点**：新增 `drive/FramePlan.java`（白名单窗表六类 + 按 Class 缓存 + `Reflect.find(AbstractGameAction,"duration")`），动作第一帧恒 0.05 s（保住所有 `duration == 初值` 判断与开屏）、之后一次跳到「该动作下一个时间窗之下 0.02 s」、过完所有窗才用 20 s；`Driver.engineFrame()` 包住主循环与 `runEndOfTurnQueue` 两处 `gam.update()`，`frameDelta()` 在屏规则后读 `planned()/delta()`；回滚键 `settings.txt search.frameTiers=off`；审计 7206 类 / 378 动作后确认首帧之后还有窗的只有 `DrawCardAction`/`FastDrawCardAction`/`ScrapeAction`/`RipAndTearAction`/`EasyXCostAction`（`duration < 0`）与 `BurnIncreaseAction`（`< 1.5f`）；S251(4)/S252(3)，S82/S133/S134 的旧行为分支自钉关闭分档并各加分档分支，反向验证（恒 20 s）3 红；core 739/0。旧：B182＝`559F67C208506FA2D6DBBC731B04F4E2C23D8E944A4F684E2AD7D6303A10E478`（1,247,390 B；**沙盒不保留手牌发光队列**：新增 `mirror/GlowReset.java`，每世界一次把沙盒卡容器上非空 `glowList` 换成空表（不拷贝，user m00148 定案），`World.install()` 末尾调用；S250(4)，反向验证 2 红；代价每世界 µs 级、搜索/评分/出牌不变。旧：B181＝**沙盒帧长统一成 0.05 s**：`Driver.simDelta` 默认从 `FAST_DELTA`(20.0f) 改成 `SELECTION_DELTA`(0.05f)，`FAST_DELTA` 只留 `-Dsts1solver.cardDelta=fast` 做 A/B；`Driver.endTurn` 按 `endTurnSmallFramesForTest`（null→小帧）选帧长（S133/S134 的旧行为分支因此重新可复现）；`Probe.runStep` 收尾清掉 5 个测试钩子；4 个药水门禁自己钉 `Limits.forcePotionsForTest(true)`（玩家 settings `search.potions=ban` 会让药水分支整段消失，与 B181 无关）；S249(3)：3 张灼伤（2 弃牌堆 + 1 抽牌堆）经一个 `BurnIncreaseAction` 后原有 3 张全升级、总数 +3（修前 `passed=2 failed=1`，反向验证同样 1 红）；core 725/0）；旧：B180＝`9DDCBBD60C23D59DEADB876F8B28CCA1731DE000B19793B58F55A039ACFF6DE4`（1,240,061 B；B180＝**时间不限时末档不再被节点预算截断** + **面板说清是哪个限制**：`Solver.Session.widenToNextRung()` 在 `lastRung && DeepBudget.unlimited(budgetMs)` 时 cap=0、`nodeBudget=0`、`layerAllowance=Integer.MAX_VALUE`（日志 `cap=+unlimited`；仍受 maxLayer=high 400、provenOptimal、前沿干、停止按钮约束），`PlanText.candidateNote(cutShort,open,reason,deferred,promoted)`+`limitText(reason)` 把原因写成「节点预算用完/时间预算用完/到层数上限/手动停止」并追加「另有 D 条被挤掉的线停着（本档回放 P 条）」（旧 2 参重载文案不变）；S247(3)：不限预算末档 `maxNodesForTest()==0`、有限 12000ms 预算 `20053`；S248(6)：四种措辞与 169/2000/121 文案；另修 S153 截断断言（`conclusive ||`→`!conclusive ||`，非产品改动）；core 722/0）；旧：B179＝`9D189521EAD01511B270209B75D27B03EDA74BC77CE8F39115EE063F7DF8B379`（1,236,016 B；B179＝普通战斗的 portfolio 四档收进同一个 `Solver.Session` 逐档加宽（`Session.planWidening(beams, deadlinesMs, nodeCaps)` + `budgetNow()`，份额 35/25/25/15 改成累计 deadline；S246(6)：4 档走完、第 2 档不回根、每档 deadline 生效、`totalNodes` 与外壳一致，反向注释 `planWidening` 时 3 红），`Result.summary()` 同时打本档与合计；core 713/0）；旧：B178＝`FF10482E55384948FCC2B8620778ABEAB9F7D399ACFC3C19E06A2B96F95F8E71`（1,233,172 B；B178＝B176 的汇总日志从「每个重复节点一行」改成「每趟一行」（本局 96% 日志行＝12,731/13,248、1.1 MB，`Log.write` 同步且逐行 flush），并把 `Driver.settleBlock()` 的原因改成采样时快照进 `Choice.settleWhy`（以前写日志时才读，5 条诊断全空）；无新增门禁，S242/S243 6/0、core 709/0）；旧：B177＝57DC8A8E3C28690C0A49DE3E7B212A738B7968431CB6233D4797E381EB85CDAA（1,233,118 B；B177＝加宽阶梯在同一个 `Solver.Session` 内逐档进行（`Session.planWidening` / `widenToNextRung`：保留层号/前沿/转置表，只换 beam 与节点预算并重置本档 promotion 配额，`QuickSession` 只建一个 Session；S244(5) 钉 Session 机制、S245(3) 钉 QuickSession 接线：`rungsForTest()>1` 且 `rungForTest()>=1`，实测 `S245 passes=4 rungs=4 rung=3`；反向验证＝注释 `QuickSession.planWidening` 后 S245 `passed=6 failed=2`，S244 仍绿）；旧：B176＝`7C6444E7075E7C1E90F5A0EBBFBD2793377A4185B05CD29452939C909D7D2FEB`（1,228,383 B；B176＝同一局面的中途相位不再算两条世界线（`Driver.settled()` + `Solver.positionKey`，S242/S243）；旧：B175＝`2F96945955E0447C5902E09D6854346FC2D028BA72F43E6B7DCB81CF6F1FA29E`（1,224,037 B；B175＝面板「预计无伤」改回原始掉血、成长抵扣只作注记（`Gameplay.hpPreviewText`，S241））；旧：B174
  `F0ADBE1D185ED730D1F6BA2DA5FB1E8D59E2B5D33AEC5BC7610A8B8BB959FE82`（1,222,511 B；B174 含 S240 复刻＝同一开局两种牌序：能不打药赢的牌序 1.5s/8s 两次都不喝（hpLoss=4 potions=0），凑不出胜线的牌序喝了也没赢（win=false potions=1）；旧：B174 首版＝6ABB3A0A1325192068FBE94E631C58E571DE06560E479D402083DB3D50362C11（1,219,570 B））。B174 首版说明：**药水计价查证 + 面板补价**：S236 证明药水价确实进了排名净值（net=9 expect=9）、S237 证明沙盒喝药真的清槽（before=9 after=0）、S235 用用户那场配置跑 10 个种子一次都不喝、S238 对照证明这瓶先古药水在当前对手模型里值 0 HP、S239 让「预算内最好」那行打出「（价值 N HP，合计 M）」；旧：B173＝B173＝**面板的「预计掉血」与跨档合并改用一把净尺子**：新增 `Node/Result.netHpLoss` 与 `Solver.netHpLoss/netOf`（**不含战利品项**，user m21803：掉 6 血换 6 血奖励仍算 6 血），跨档 `Solver.better` 比净值，面板 `hpPreviewText` 打净掉血并在原始值更大时附「原始 N，成长/回血抵扣 D」；**档内 best 与 beam 排序仍按 `Solver.score`**（第一版把排序也换成净值后丢掉棋盘项，选出 0 伤害 0 回合的短线，S 段 10 条红，已回退）；旧：B172＝**普通怪的最宽档不再硬编码 128**：B171 已让 Boss 阶梯按 `Limits.beam()` 加宽（high 96 / unlimited 256），但普通怪 `Solver.portfolioProfiles()` 第四档写死 128，且 `PortfolioSession` 在类初始化时快照整张表 ⇒ `limits=unlimited` 下普通怪比 Boss 窄一半；现在第四档是 `max(128, Limits.beam())`、切片路径每次现读（门禁 S232）。旧：B171＝**Boss/200HP 战的快搜不再「见赢即停」**：`search/QuickSession.java` 的窄深档 beam=10 一旦拿到任意胜利就 finished，更优的宽档胜利被丢弃（史莱姆 Boss 面板 -33 HP vs 玩家实测还剩 21 HP）；现在窄档之后按 28→64→`Limits.beam()` 逐档重搜、用 `Solver.better` 合并、只有 `provenOptimal` 才早停，每档 `maxNodes=max(20000, 窄档节点×2)` 保证无上限预算时能跑完（门禁 S230/S231）。旧：B170＝**静态监听不再把大数组逐元素装箱**：`ModStatics.watchable()` 只按类型名排除 texture/pixmap/sprite/atlas/string，于是雨爱的 `yuaimod.YuAiMusic#audioData`（`private static byte[]`，整个 8 MB wav）进了 `StaticWatch` 候选；旧 `snapshot()` 把它逐元素 `Array.get` 拷进 `Object[8M]`（约 32 MB）、`bodyEquals()` 再逐元素比 8M 次，而采样窗口是 installs 1–4 与每 128 个 ⇒ 一次搜索十来个窗口各付 582 ms + 280 ms（门禁 S229 修前实测），玩家看到的就是「只有装雨爱才卡 500–700 ms」。修法：`StaticWatch` 新增 `MAX_ARRAY_ELEMENTS=4096` 与形状 5「large array」，超阈值用 `Array.newInstance(componentType,n)`+`System.arraycopy` 整体快照、按组件类型分派 `Arrays.equals` 比较、`System.arraycopy` 还原；阈值以下仍是原 shape 3。门禁 S229(4)，修前 `passed=2 failed=2`、修后 `4/0`（`S229 shape=large array snapshotMs=[355,0,0] compareMs=[4,1,1]`，355 ms 是候选表冷启动）；旧：B169＝**静态监听只回滚「沙盒把对象塞进实机静态」，不再回填别的线程的「释放」**：MTS 捕获的 mod stdout（`sendToDevs/mts_process_launch.log`，GBK）证明搜索窗口里 `YuAiMusic.play()` 一次都没跑（全程只有实机 2 次「开始循环播放」），而音频回调线程 `YuAiMusic$1` 在 STOP 事件里 `clip.close()` 后把 `currentClip` 置 null，旧 `StaticWatch.restore()` 见「快照非 null、现在 null」就回填 ⇒ `isPlaying()` 永真、搜索后雨爱假死。修法：`beforeUninstall()` 遇释放留 null、计数 `released`、30s 限流 WARN；`releasedForTest()`。门禁 S228(5)，反向验证 `passed=2 failed=3`、`leftAlone=0`；旧：B168＝`941B00FE9F932A21ECAB211A967B2D8110831898BF5EA3EA883804346698D3AB`（1,185,933 B；B168＝**沙盒窗口内不再派发纯表现事件 `healthBarUpdatedEvent`**：它是 `AbstractCreature` 上的无参纯表现方法（只写 `healthBarAnimTimer`/`targetHealthBarWidth`），mod（YuAi）在它上面挂 postfix 播半血特效；沙盒每个节点都真跑伤害/治疗 ⇒ 每次血变都触发一次实机特效（8 类 14 个调用点），叠加在 40ms 切片上就是 600ms 的帧。MTS 的 prefix 早退**挡不住别人的 postfix**（javassist insertAfter 插在所有 RETURN 之前，实测），所以只能改调用点：新增 `mirror/PresentationQuiet.java`，`quiet()=World.anyInstalled()`，14 个 `@SpirePatch`+`@SpireInstrumentPatch` 用 ExprEditor 换成 `if (!quiet()) { $0.healthBarUpdatedEvent(); }`，`patchedSites` 走系统属性（MTS 用另一个类副本加载补丁类）；门禁 S227(6)，反向验证 `passed=4 failed=2`；旧：B167＝`AF702048E57AE8248DAD63F8B71AAF878F8FFB37DEA9BE1B36CBF5B18B424D23`（1,173,143 B；B167＝**读档后计划真的能复用 + 选牌步答案同一条链消费**：①`Gameplay.cacheFits` 过去只认「首步结束回合/药水」，其余走 `liveCardFor`（只在手牌里找），而首步「答选牌」没有手牌身份 ⇒ 缓存 100% 被拒，日志里同一 digest 被拒 8 次（改为 `selectionAnswerFits`：屏种类对得上 + 计划要选的每张牌都能在 `Driver.selectionCandidates()` 里唯一定位，选 0 张放行，屏没开拒绝）；②缓存键过去用 raw digest，首段含 `ph=/q=/use=/end=` ⇒ 隔一帧换 key（改为 `PlanCache.key` 先 `Digest.stable`，并修掉 `stable` 只看得见整段 `ph=` 的剔除循环 ⇒ 首段内部的 `q=/use=/end=` 也剥离）；③`submit` 的选牌分支过去绕过 `answerNextScreen` ⇒ `pendingChoiceAt` 永不推进、旧答案残留并可能答后面的屏（改为 `answerSelectionStep` 走同一条链，`answerNextScreen` 按段传 moved，保留 B163 换序）；门禁 S224(2)/S225(2)/S226(3)，反向验证 `passed=2 failed=1`、`pending=[Strike_R]`；旧：B166＝`ECE17BD0…`（1,166,416 B；**纯表现对象 `BobEffect` 不再共享**：它落在 `ClonePolicy` 的 `com.megacrit.cardcrawl.vfx.` 共享规则里 ⇒ 沙盒与实机同一个 bob；`BobEffect.update()` 读 `Gdx.graphics.getDeltaTime()`，而窗口代理 delta＝`FAST_DELTA=20.0f`、speed=4 ⇒ `PostCombatLoot` 每评估一个胜利节点泵 `room.update()`（`Solver.java:1575`）就推进 80 rad≈12.7 周期，实机徽章抖动/瞬移。修法＝ClonePolicy 加 `BobEffect` 例外（拷贝），S223(4)；旧：B165＝`62A49DFE…C2377`（1,163,941 B，**开屏随机归一**：`DiscoveryAction.update()` 每帧重生成三张但只有 `duration == ACTION_DUR_FAST` 那帧被采用，而我们的实机泵屏开后还要跑 ≤8 次 `gam.update()`、每次再抽 3 张（≈24 抽），沙盒泵在模态屏期间不跑 manager ⇒ 实机随机流被白推、之后蛇眼/洗牌/唐全错位。修法＝`mirror/DiscoveryRandom.java` 对两个 private 生成方法打 prefix，非 FAST 帧返回空表、FAST 帧生成并计数（无状态，手打/自动/沙盒同一份）；`Action.rngNormalized` + 步骤行 ` *` + 计划底部一行图例；`Driver.noteFrameDraws()` 按 `screen-rng` 键限流告警其它帧抽；门禁 S221(6)/S222(6)；旧：B164b＝**战后奖励页计入胜利评分**：新增 `search/PostCombatLoot.java`（一次性窗口里 `rewardAllowed=false`+`isScreenUp=true`+`endBattleTimer=-1` 泵 `room.update()` 到 COMPLETE、`setupItemReward()`，读真实 `room.rewards`；失败回退引擎公式复刻）与 `search/LootBook.java`（卡 2/4/10 HP、遗物 5/10/15 HP），`Solver` 两处评分 `-forfeitPoints()` → `+lootPoints(loot)`，面板**不显示**这行、只写日志 `loot :`（m17808），金币按枚计价（1 金=80 分，24 金=1920 分，没有 25 金分档），门禁 S217(8)/S218(4)/S219(6)/S220(4)；旧：B163＝`4D1E6D4EB878169121AC6E23843E5C5641F543F4C2AF33D8AA55185089210B7C`（1,130,506 B；B163＝**答选牌可以带换序**（A 方案）：`Action.moved/movedUuids/movedDisplay` + `Action.selectionWithMoves(...)`，`Driver.moveToEndOfHand(...)` 复刻「点一张再点一次」的净效果（该牌落到手牌末尾，`CardGroup.addToTop` 语义），`Legal.selectionActions()` 每屏额外 ≤4 个变体（移「未选中的最高价值牌」），`Gameplay.submit` 传 moved；顺带把「空答案」从 `not-offered` 改成合法（`askedEmpty`）；非 HAND 屏要换序报 `not-offered`；门禁 S216(5)；旧：B162＝`AE237A93E31BD049D537DED870AACAB603E66C26D22B87A6CFE419515D67DB67`（1,123,015 B；B162＝**屏开着也能开算 + 答选牌本身是一步**：`World.readBack()` 不再把选牌屏状态丢掉（B81 的 `discardOnReadBack` 曾让「答完屏的世界」下次 install 又把屏装回来 ⇒ action 队列冻结 ⇒ 之后每张牌静默 no-op），改为「有屏在等答案就回写实际值，否则写回 NONE/false」；`Legal.selectionActions()`/`Solver.candidates`/`Gameplay.submit` 支持把「答这块屏」当成计划里的一步；门禁 S215；旧：B161＝「弃任意张」屏（anyNumber，如赌博筹码 `open(…,99,true,true)`）现在枚举全部子集而不只发价值前缀（`Driver.selectionOptions` 的 upTo 分支 + `combinations`，cap=`Limits.handBranches` 16/36/∞），旧前缀是新集合的子集；旧：B160＝`5B41140D1F75C0B56897F712668B1AE6B89AFBFF363D0FC2C56338C8FFD6C22E`（1,115,020 B；B160＝面板只报「谁回了多少血」：删掉 `· 还有没认出来的遗物，可能回更多`（来自遗物白名单 `certified()`，带任何 mod 遗物必现）与从未显示过的 `战后药水: …` 行（`PotionOutlook.describe()`）；计价与 `bound()` 未动；旧：B159＝`BA8D6273DAFD08D8D0104A253A65C7AEBDFA2FFD10EC5140D13AA53E2DA8D3CE`（1,115,759 B；B159＝结束回合风险闸门改用计划里的答案逐块答完选牌屏再读血（`answerEndTurnScreens`，屏没答案就报 unknown 停下、不猜），门禁 S213(3)，修前实测 `damage=0 played=0`；旧：B158＝删掉第二处墙钟（选牌答案 6 秒过期 `pendingChoiceUntilMs`）、不再猜牌（计划没有答案的选牌屏/卡牌奖励屏停下交回玩家，`selection-no-answer`/`reward-no-answer`）、判步只看 `engineSettled()` 与 digest（不再看 700ms `autoplayVerifyMs`）；门禁 S211(4)/S212(3)。旧：B157＝`B1C66D64F31DC9D1EEC6034AA1F01F0D1345D560396A28375A7539BB7AE1E408`（1,107,252 B；B157＝删掉固定 20 秒墙钟（不再有时限兜底），结束回合必须走到沙盒测出的 `turnAfter` 才算完，实机回合越过计划或某步结果与计划不符时**自动出牌立即停下、计划保留**；旧：B155＝`01006A355843BEA7DDEE8CDA5CA384C68D321633E011DA7816DDB087005A1433`（1,102,888 B；B155＝机制清单 `docs/SANDBOX-MECHANISMS.md` + S208；B154＝并排取证日志；B153＝答完选牌屏必须把队列跑完再存终态：`Driver.drainAfterAnswer()`（B102 完成泵）+ `lastAnswerUnfinished()` + `Solver` 丢半程分支（S207 修前必红 `tail 0->600/1200`）。旧：B152＝`2A1D3819B3BE8E47DD232A26FA7A26C002295DBA3B51976B024901361109AC5F`（1,095,405 B，门禁开关 pin：`GrowthBook.countingPinned`，`budgets()` 不再覆盖被钉住的 `counting`（S187 曾因此红：`live=4000 expected=5200`）。B151＝结束回合队列泵对齐引擎「屏开着不推进 actionManager」：`Driver.managerFrozen()`、`runEndOfTurnQueue` 遇屏返回、`endTurn` 屏开着拒绝并留 latch、`resolveSelection` 改「填→关屏→drain」、`previousScreen=NONE` 改 `null`；门禁 S206(5)、S182 改两段（屏开着被拒 + 答完屏走通）。旧：B150＝按 user m13154 **整段撤销 B148**：`PlanCache` 的随机戳键/5 参重载、`Gameplay` 三处 `RngStamp.of()` 取键、门禁 S205 全部删除；保留 B147/B149 的取证设施。撤销理由：读档进房把这五个流固定播种成 counter 0（确定值），旧计划若也在计数 0 时算出则键相同 ⇒ 仍命中，方案识别不了「新读的一次档」。旧：B149＝`F96AE9D5…`（出牌流水取证）：普通步也打 `step plays: live … || sandbox … || rng same/DIFFERS`，屏开了但计划没答案时打 `selection open with no planned answer … | step=N/M | last plays: …`（`PlayTrace.recent(8)`）；面板提示删掉「再点一次算下一轮」。**未修**：唐自动出牌开屏那条分叉本身，等下一份带这两行的日志。B148（已撤销）＝`7FA7707B…`；B146＝`C6189FE2…`；B143＝`F023BDD7…`；B142＝`87FAA067…`；B141＝`C8B7C7BD…`）
- 当前基线（2026-11-15，B187 口径）：核心套件 **待复跑确认（B187 本轮未跑门禁，用户 m00732 自行实测）**；门禁 S1–**S257**；B187 断言＝S255 重写 **37 条**（替身语义、B169 推翻、死快照写 null、liveStop 恢复，含带真实雨爱时的 `readableForTest`/`blindForTest`）+ 新增 S257 **2 条**（真实 `javax.sound` 设备 open/loop/stop/close 冒烟，无设备 SKIP 不计通过，规则 I1：假对象不能证明真资源安全）；旧 S255 的 16 条被这 37 条替换；反向验证本轮未跑。旧 2026-11-15 基线（B186 口径）：核心套件 **待复跑确认**（S171 修复前全量 `passed=755 failed=6`、6 条全在 S171，单跑修复后 `10/0`，其余 751 条全绿 ⇒ 预期 `passed=761 failed=0`；02:46 用户叫停复跑）（门禁 S1–**S255**；B185 新增 16 条断言＝S255 16（含带真实雨爱时的 `readableForTest`/`blindForTest` 两条），反向验证 `passed=4 failed=10`；S255 不带雨爱 `passed=14 failed=0`、带 `-Mods YuAiBloodMusic` `passed=16 failed=0`）；旧：B184 口径＝`passed=739 failed=0`（S1–S253；B184 新增 10 条断言＝S251 4 + S252 3 + S253 3，旧 B183 的 S251 4 + S252 3 + S133 +1 + S134 +2 共 9 条断言随 2026-11-13 回退删除；旧「272 条」那套加总与 `passed=` 本就对不上，一律以 `passed=` 为准；B184＝白名单帧长分档（S251(4)：`S251 burns=3->6 steps=7 fresh=5 fast=2`（同场景 B181/B183 要 49–61 步）；S252(3)：`S252 burns=3->6 steps=49 fresh=0 fast=0`；S253(3)：`S253 burns=3->3 steps=49 screenFrames=48 fast=45`（屏帧优先，快帧未被采用）；反向验证 `passed=8 failed=2`）；旧：B183＝沙盒帧长按动作分档（S251：`S251 burns=3->6 upgradedOriginals=3 steps=5 fresh=1 fast=1`（B181 需 ~61 步）；S252：`S252 burns=2->5 steps=49 fresh=0 fast=0`；S133 分档分支 `inHand=true orphans=0`、S134 分档分支 `scried=true screenFrames=1`；反向验证 `passed=4 failed=3`、`fresh=0 fast=0`）；旧：B182＝沙盒不保留手牌发光队列（S250(4)：`S250 glow before=1.2 after=1.2 liveSize=1 sandboxSize=0 scanned=1 dropped=1 sameCard=true liveSame=true liveAfter=true`；反向验证 `passed=2 failed=2`、`after=0.24000001 sandboxSize=1`）；旧：B181＝沙盒帧长统一 0.05 s（S249(3)：`S249 burns=3->6 upgradedOriginals=3 timedOut=false`；修前 `passed=2 failed=1`；代价：`S246 … nodes=3866 … ms=7363`，每节点 0.58–0.78 → 1.90 ms）；旧：B180＝`passed=722 failed=0`（S1–S248，270 条；末档去节点上限（S247(3)）与面板限制原因/停车候选（S248(6)），另修 S153 截断断言。旧：B179＝`passed=713 failed=0`（S1–S246，268 条，见下）；旧记录的「S1–S245，268 条」多算 1，实际当时 267 条（`only [S242, S243] asked for, 265 gate(s) skipped` ⇒ 2+265=267），已订正；B179＝普通战斗走单 Session 逐档加宽（S246(6)：`S246 finished=true rungs=4 rung=3 beam=128 nodes=4123 pass=245 total=4123 ms=1938`；反向验证 `passed=3 failed=3`）；旧：B178＝日志刷屏修复，无新增门禁；B177＝加宽阶梯在同一个 Session 内逐档进行（S244(5)：阶梯真的加宽、两档按序在同一个会话里跑完、首次加宽的层号 ≥1、加宽时窄档已展开过、更宽的档把窄档停车的线提回；S245(3)：`QuickSession` 真的接线、阶梯真的推进；反向验证＝注释 `QuickSession.planWidening` 后 S245 红 2 条、S244 仍绿；实测 `widenedAt=27 expansions=1082 atWiden=888 promoted=66 parked=230`））；B176＝中途相位去重（S242(4)：结算局面同键、未结算保留原串、null 有键、规范键不与原串相撞；S243(2)：真实切片 `unsettled==0`；反向验证 `passed=2 failed=2`）；B175＝面板「无伤」只认原始掉血（S241(6)：净 0 原始 3 不许写「无伤」、真空刀仍写、九张升级=45 HP 抵扣，反向验证 `passed=3 failed=3`）；B174＝药水计价查证与面板补价（S234(5)/S235(2)/S236(2)/S237(3)/S238(2)/S239(3)/S240(1)）；B173＝B173＝统一净掉血尺子（S233(9)，含 `sixHpWithLoot=6` 与 `S233 loot: worth=18000 netWithLoot=6` 两条奖励独立性断言，反向验证 `passed=8 failed=1`）；B172＝普通怪最宽档改 `max(128, Limits.beam())`，`PortfolioSession` 不再快照 profile 表，S232(4)，反向验证 `passed=3 failed=1`；B171＝Boss/200HP 战的快搜加宽阶梯（窄档见赢不再收工，逐档 28→64→`Limits.beam()` 重搜、`Solver.better` 合并、只有 provenOptimal 早停），S230(3)/S231(2)，修前夹具实测 `passes=1` 必红，修后 `S230 passes=4`、`S231 passes=1`；B170＝静态监听对大数组用整体复制/整体比较（雨爱 8 MB `byte[]` 不再逐元素装箱，计算时 500–700 ms 帧消失），S229(4)，修前 `passed=2 failed=2`（`snapshot=582ms compare=280ms`）；B169＝静态监听的「释放」不再被回填（别的线程清实机静态不再被当成沙盒写，修好搜索后雨爱假死），S228(5)；B168＝沙盒窗口内不派发 `healthBarUpdatedEvent`（实机雨爱半血照常播、搜索不再每节点触发实机特效），S227(6)；B167＝读档后计划可复用（缓存键去中途相位 + 首步答屏按候选判）+ 选牌步答案同一条链消费（`pendingChoiceAt` 推进、旧答案不再漏给后面的屏），S224(2)/S225(2)/S226(3)；B166＝纯表现对象 `BobEffect` 不再共享（计算期间意图徽章不再被 20 s 的模拟帧推进），S223(4)；B165＝开屏随机归一（`DiscoveryRandom` 一次开屏只抽一次）+ 面板 ` *` 与图例行 + `screen-rng` 帧抽告警，S221(6)/S222(6)，**S190 末条旧断言「manager 再跑时继续抽」已订正为「不再抽」**（它钉住的正是本次消灭的浪费），全量首跑 626/1 就是这一条；B164＝战后奖励页结算（PostCombatLoot 引擎真结算 + LootBook 价目 + 日志行），S217(8)/S218(4)/S219(6)/S220(4)，首跑 S217/S220 各红一条（`AbstractDungeon.screen` 是 null 不是 NONE；`r.cards.size()` 是选项数不是张数）；B163＝答选牌带换序（A）+ 空答案合法（S216(5)，修前禁用 `moveToEndOfHand` 实测 `passed=3 failed=2`）；B162＝屏开着也能开算、答屏是一步、屏状态回写归一（S215(7)，实测 `steps=12 first=答选牌（选：打击,打击）`）；B161＝anyNumber 屏枚举全部子集（`Driver.selectionOptions` upTo 分支 k=0..maxKeep 全组合），门禁 S214(5)，修前实测 `options=4 one=1 two=1`；B175＝面板「无伤」只认原始掉血（S241(6)，反向验证 `passed=3 failed=3`）；B174＝药水计价查证与面板补价（S234(5)/S235(2)/S236(2)/S237(3)/S238(2)/S239(3)/S240(1)）；B160 无新增门禁，S14 断言 5→4（extended，不再覆盖已删除的 `certified()`）；B159 新增 S213(3)，B158 新增 S211(4)/S212(3)；B151 新增 S206(5)，B152 无新增门禁，B153 新增 S207(5)，B155 新增 S208(5)，B157 新增 S209(3)/S210(3)）。最近一次全量 `-Full` 是 B103 时 **`passed=936 failed=0`**；
  此后新增 S156(3)/S157(6)/S158(7)/S159(3)/S160(4)/S161(8)/S162(4)/S163(5)/S164(3)/S165(3)/S166(3)/S167(1)/S168(1)/S169(1)/S170(1)/S171(10，B118 起；B121 加两条断言；B186 把预算改成 (a)2000ms、(b)9000ms 上限 + 采到预览即停)/S172(2)/S173(2，不加载 CaiNongMod 时 skipped)/S174(8，B119)/S175(2，B120)/S176(1，B121)/S177(4，B122)/S178(2，B122)/S180(5，B124)/S181(4，B125)/S182(3，B125)/S183(1，B126)/S184(2，B126)/S185(4，B127)/S186(6，B128 改造后仍 6 条)/S187(6，B129；B146 起末条钉住开关：关掉后金币半边为 0)/S188(4，B130)/S189(3，B130；core 不加载 CaiNongMod 时 skipped 记 1)/S190(8，B133)/S191(7，B134)/S192(3，B135)/S193(4，B136)/S194(3，B136)/S195(1，B136)/S196(2，B139)/S197(2，B140)/S198(6，B141)/S199(6，B141)/S200(4，B142)/S201(4，B143；B144 起末条改读 settings.txt 实际值；S64 的 expected 加了 growth，并在门禁里做了开关往返)/S202(4，B146；金币直接计分 +400/−240 + `GrowthBook` 对金币返回 0 + 关掉开关后金币为 0)/S203(3，B147；一次出牌一条轨迹含牌/回合/随机位置、roll 后随机戳会变)/S204(3，B147；一行 Action 回放成一步且 Step 带 rng/plays)/S206(5，B151；结束回合中途被块内动作开屏：泵停下不零消费、答完屏那张牌真打出、随后结束回合完成)；S207(5，B153；答完选牌屏后队列必须跑到尾：屏停在自己开的选牌上 / 有牌可答 / 答案被接受 / `tail 0->1200/1200` / `unfinished=false`)；S208(5，B155；M2 回合末整手弃掉、空抽牌堆从弃牌堆洗回：按按钮 / 回合前进 / hand=5 / 三堆总数守恒 / draw>0)；S209(3，B157；实机回合已越过计划这一步 ⇒ 自动出牌停下、计划保留、pending 不动)/S210(3，B157；某步结果与计划不符 ⇒ 自动出牌停下、计划保留、状态写明「和计划不一样」)/S211(4，B158；计划没有答案的选牌屏交回玩家不填牌：屏开着 / 没被猜着关掉 / selectedCards 为空 / 计划保留)/S212(3，B158；计划答案 6.3 秒后仍被用来答屏 / 答完即被消费)；S213(3，B159；结束回合风险闸门跑完整个结束回合：`risk.ok` / 屏被计划里的牌答了 `played==1` / 屏之后的怪物回合算进来 `damage>0`)；S214(5，B161；赌博筹码式 anyNumber 屏枚举全部子集：屏已开 / hand=3 / `one=3 two=3` / `options=8`)、S215(7，B162；屏开着也能开算：真屏已开 / 搜索不消耗 root 的屏 / 第一步是答屏 / 屏提供答案 / 答屏不是结束回合 / 答完 waiting action 真执行且 `discard==pick.size()` / `计算` 能开算)；B144、B145 未新增门禁（B145 只让 S112 显式钉住 `GrowthBook` 开关，4 条断言不变；B146 起 S112 期望改 `credit=21 goldOnly=0`）、S216(5，B163；答选牌带换序：真手牌屏 / label 含「换序」/ 等答案的动作只看到选中的那张 / 手牌最后一张是被移动的那张 / 「一张都不选 + 移一张」也放行)；S217(8，B164；杀小偷 vs 放跑：引擎结算 gold=35/0、屏幕与 escaped 还原、killed.points()>fled.points())/S218(4，B164；有怪逃跑时回退金币为 0)/S219(6，B164；价目表 2/4/10、5/10/15、日志文本、24 金=1920 分)/S220(4，B164；引擎结算与回退对账)；S221(6，B165；FAST 帧各得 3 张且抽 3、后续帧空表零抽、generations 计数)/S229(4，B170；大雨爱式静态数组整体快照：形状 `large array` / 深位置写入仍被抓到并还原 / 温窗口 snapshot+compare <150 ms，修前 582+280 ms)；S222(6，B165；真实 DiscoveryAction：屏开三张、之后 10 帧零抽且三张不变、`rngNormalized` 三份拷贝都保留)；S227(6，B168；`patchedSites=14 expected=14` / 沙盒 `damage(3)` 后 HP 80→77 而 `healthBarAnimTimer` 位级仍 0.0（派发被吞）/ 直接调 `healthBarUpdatedEvent()` 仍置 1.2 / 卸窗口后 `quiet=false`)；S228(5，B169；`released=null leaked=null putBack=true written=[sts1solver.probe.Probe#S228_LEAKED] leftAlone=1`)；S233(7，B173；`S233 ruler: growth=9 loot=7 heal=6 noBank=0 potion=2`、`S233 merge: … netA=1 netB=5 aWins=true bWins=false`、`S233 rung: … rises=0`）；共 270 条（B150 撤掉 S205 的 5 条、B151 加回 5 条、B153 加 5 条、B155 加 5 条、B157 加 6 条、B158 加 7 条、B159 加 3 条、B161 加 5 条、B162 加 7 条、B163 加 5 条、B164 加 22 条、B165 加 12 条、B166 加 4 条（S223）、B167 加 3 条（S224–S226）、B168 加 6 条（S227）、B169 加 5 条（S228）、B170 加 4 条（S229）、B171 加 5 条（S230/S231）、B172 加 4 条（S232）、B173 加 7 条（S233），最近一次全量 `passed=669 failed=0`（2026-11-10，B173 后；S1–S233）。另外 **S230 的墙钟截止时间已在 B173 这轮从 300 s 放宽到 900 s**：节点数完全没变（narrow 35863、wide 各 71726），只是这台机器当天慢约 2–4 倍（同一 jar 的窄档 35863 节点从 11.5 s 变成 44–48 s，玩家实测面板速率 1100–1400 条/s 掉到 691–771 条/s）⇒ 判据仍是 `finished + passes>=2 + win`，截止时间只是安全网。`-Gates` 的参数是逗号分隔（`-Gates "S224,S225,S226"`），空格分隔会被当成一个 id。/S246(6，B179)/S247(3，B180)/S248(6，B180)/S250(4，B182)/S251(4，B184；白名单分档：`S251 burns=3->6 steps=7 fresh=5 fast=2`)/S252(3，B184；`search.frameTiers=off` 退回统一帧 `steps=49 fresh=0 fast=0`)/S253(3，B184；屏帧优先 `screenFrames=48 fast=45 burns=3->3`)（B183 的旧 S251(4)/S252(3)/S133(+1)/S134(+2) 已随 2026-11-13 回退删除，S251–S253 是 B184 新写的）
- 负载提醒：这台机器上 `MuMuVMMHeadless` + `MAA` 常驻抢 CPU，游戏启动要 100s+、核心套件从 2.5 分钟涨到 10–15 分钟。
  `tools/run.ps1` 请用 `-TimeoutSeconds 2400`，否则 900 秒会在负载下误报 `STALLED/TIMEOUT`（S153 曾因此误红，已改 20s 预算 + 不判 dominance）。
- 完整套件：`tools/run.ps1 -TimeoutSeconds 2400 -Full`；不带参数 = 核心门禁。
- 装 jar 前必须关游戏。

### 显示休眠 = 看起来卡死（2026-11-02）

这台机器的电源计划 **3 分钟无操作就关显示器**。显示器一黑，游戏的 `Present` 就阻塞（线程停在
`org.lwjgl.opengl.WindowsContextImplementation.nSwapBuffers`），表现是：没有新日志、CPU 近 0、
等多久都不出 `AUTOTEST DONE`——和真正的死锁一模一样。锁屏（`LogonUI`/`LockApp`）也是同一症状。

- `tools/run.ps1` 现在启动时就调用 `SetThreadExecutionState(CONTINUOUS|DISPLAY_REQUIRED|SYSTEM_REQUIRED)`，
  脚本活多久显示器就亮多久，跑测试不再被关屏打断。
- 手工在游戏里自动出牌时同样会中招：鼠标停 3 分钟屏幕变黑，自动出牌就停在那里，动一下鼠标即恢复
  （不是崩溃、也不用重开）。长时间挂机前先把显示器超时设为「从不」。
- 判断依据：`jcmd <pid> Thread.print` 里渲染线程停在 `nSwapBuffers`，且 `Get-Process` 的 CPU 增量近 0。

## 复测：B78 / P2-50（计划按身份打牌，重放不通过不算可赢）

1. 打出「头槌+」后选「发掘+」，实机不应再打邻牌：日志没有 `refusing instead of playing a neighbour`。
2. 计划里的牌已不在手上时，自动出牌停下并提示「计划里的「X」不在手上了；计划已保留」，计划仍在面板上。
3. 面板若出现「这条线重放不通过（第 N 步：X），只给前 M 步；不算可赢」，先按「计算」再自动。
4. 正常一局：不出现该提示，自动出牌能一路打到战斗结束。

## 复测：B79 / P2-51（选牌 uuid 进计划、失败先结算后重试、失败分类）

1. 头槌+/坚毅 这类选牌：自动出牌自己选中那一张并继续，不出现「重放不通过」。
2. 真的打不出来时，面板说「这条线重放不通过（第 N 步：X）」，solver.log 同一行带 not-in-hand / not-offered / not-settled。
3. 点「计算」重算一次，应给出可一路打到战斗结束的线。
4. 存量行为不变：缺牌只暂停、计划仍在面板上（B77）。
## 复测：B80 / P2-52（自我修复的战后回血会被算进去）

1. 打出「自我修复」后，面板 A10 行出现「打赢后固定回血 +N（…，自我修复 +7）」。
4. 有富余费用且血不满时，计划里应该出现「自我修复」（门禁 S130 search：plan=自我修复 | 打击 | 打击）。
2. 有 7 点血可以白拿时，求解器更愿意在计划里打这张牌（掉血相同或更少才选）。
3. 满血再打「自我修复」不加分（信用被缺失 HP 封顶）。

## 历史清单（已归档）

- 各轮复测清单分别在 evidence 下对应的 `*.txt`（B47、B49、B59、B61…B79、P2-26…P2-51）。

## 复测：B81 / P2-53（日志不再刷屏；界面状态不进世界）

1. 打三哨卫这种长搜索：`solver.log` 里 `input mute:` 行只剩一两行，且带 `screen=` / `isScreenUp=`。
2. 同一个搜索，面板「N 条/s」应回到千条级，不再一秒几十条；帧不再 p50=83ms。
3. `solver.log` 不再几秒涨到 16MB（不会频繁轮转、也就不会把上一段日志挤掉）。
4. 计算期间有界面开着（设置/卡组）时，鼠标依旧不被改写（B70 行为不变）。

### 卡顿诊断：GC 日志（2026-11-01）

- 工具：`tools\run-gcdiag.bat`（10GB 堆 + GC 日志，默认 ParallelGC）、`tools\run-gcdiag.bat g1`（再加 G1，MaxGCPauseMillis=150）。
- 日志位置：`C:\Users\YOKI\sts1-engine-solver\gc\gc-<pid>.log`，控制台输出 `gc\console.log`。
- 实测（同一台机、同一批 18 个 mod，跑到主菜单）：
  - 默认 ParallelGC：年轻代停顿 0.05–0.32 秒约 1–1.5 次/秒，另有两次 Full GC 1.25 秒 / 2.08 秒；堆 5.7→7.0GB，存活对象 2.8–4.1GB。
  - G1：停止世界停顿 70 次，最大 0.154 秒、平均 0.065 秒；无 Full GC；堆扩到 10.2GB，存活对象 3.7–5.0GB。
- 结论：卡顿来自 mod 套装把存活对象顶到 4–5GB（原版约 0.7–1GB），不是求解器。取证记录见 `evidence/GC-DIAG-20261101.txt` 与 `evidence/GC-G1-LOG-20261101.log`。

### GC A/B：哪个 mod 最吃内存（2026-11-01）

工具 `tools\gc-ab.ps1 -Mods <csv> -Xmx <size> -Tag <name>`，用 `--mods` 精确控制加载哪些 mod（我们的 jar 不在其中），等主菜单出现后统计 GC。

| 组合 | 堆 | 到主菜单 | GC 次数 | 最长停顿 | 疏散失败 |
| --- | --- | --- | --- | --- | --- |
| 只 BaseMod | 1 GB | 否 | 173 | 0.269 秒 | 55 |
| 只 BaseMod | 2 GB | 是 | 105 | 0.395 秒 | 13 |
| BaseMod + 卡面娘化(106 MB) | 2 GB | 否 | 205 | 0.825 秒 | 65 |

结论：光 BaseMod 就要 1–2 GB；只加 106 MB 的卡面娘化包，同样 2 GB 下 GC 次数翻倍、疏散失败 13→65、最长停顿 0.395→0.825 秒，并且到不了主菜单。全套 18 个 mod 则要 4–5 GB 存活内存（10 GB 堆 + G1 时最长停顿 0.154 秒）。原始记录：`evidence/GC-AB-20261101.txt`、`gc\ab-*-gc.log`。


### 存档卡死与 GC（2026-11-02，只查未改代码）

- 存档本身 16ms（SlayTheSpire.log 15:18:05.333 Enqueue -> 15:18:05.349 Successfully saved，DEFECT.autosave 20,608 B）；卡住那刻 FileSaver 线程停在 take()。
- 秒级停顿在「进房间 / 回主菜单」，栈是 MainMenuScreen -> StatsScreen -> CharManager.recreateCharacter（Gson 读 STSData*.json）+ 贴图加载，叠加 GC（dump 里 heap 5317/7106MB，gc 82/29401ms）。
- mod 未加载的 21:20 会话同样停 10.76s（同一行 Loading character stats）。
- 复测办法：双击 tools\play-g1.bat（-Xmx10g -XX:+UseG1GC，不改游戏文件）再点一次保存并退出，看是否还停；对照仍是 Steam 直接启动。

### 复测：B83 / P2-54（计划妥当保留牌）

1. 带「计划妥当」打一场：回合结束时会出现选牌界面，自动出牌替你选，选中的牌下一回合仍在手上。
2. 下一回合计划里若写着「中和+」，那张牌应该真的在手，不再出现「计划里的「中和+」不在手上了」。
3. 没有「计划妥当」的战斗不应变慢（只有 Retain 类 power 存在时才用小帧）。
4. `solver.log` 里不应出现 `pending action did not settle within the deadline` 这种 20 秒等待（若出现，说明又是开屏动作被快帧打死）。

### 复测：B84 / P2-55（选牌屏开着的每一拍按游戏帧长）

1. 带「先见之明」（Foresight）打一场：回合开始出预见选牌，自动出牌能应答，被弃的牌真的离开抽牌堆（不再出现屏开着却没人应答）。
2. 带「计划妥当」＋手牌保留：回合结束选牌后被保留的牌下一回合还在手上（与 B83 同一条，但现在不依赖名单规则）。
3. 「尼尔丽的法典」回合结束给牌时，界面能被应答而不是卡在那里（REWARD 分支本来就冻结，属同一类）。
4. 正常出牌节奏不应变慢（屏只在选牌时按 1/60 推进，其余仍用快帧）。
5. `solver.log` 里若出现 `selection open with no planned answer` 或动作一直 `awaitingSelection`，请把该段日志发回。

## 复测：B85 / P2-56（回合归属、毒杀收尾、腾跃）

1. 打一个「毒/持续伤害在回合结束杀掉最后一只怪」的局，让自动出牌打到最后：面板该显示「计划执行完毕」，不该再报「计划属于上一回合」，也不该出现「第 0 回合」。
2. 同上局面里手动按一次结束回合：毒把怪跳死后，剩下的步骤不该被误判过期而丢掉（旧版会丢）。
3. 观者打腾跃（Vault）：面板里腾跃归**当前**这一回合，它之后出的牌归下一回合；自动出牌不该因为腾跃而每步都重算。
4. 只按 F8 不自动出牌：仍然不该弹出战斗结束的奖励/选牌界面（B20 保持）。
5. 若仍看到「第 0 回合」或计划被丢，请把 `%USERPROFILE%\sts1-engine-solver\solver.log` 里附近 100 行发我（重点看 `plan stale:` 与 `plan :` 两行）。


## 复测：B86 / P2-57 / P2-58（头追两屏选牌、上限三档）

1. 打出「头追」：面板出现两屏答案（例如「选牌：防御 → 打击（2/2）」），自动出牌把两张牌**真的互换**（弃牌堆里那张进抽牌堆，抽牌堆那张进弃牌堆）。旧版是「不卡住但也没换」。
2. 手动点两次也能换（这条旧版就成立，用来确认不是 mod 自己的问题）。
3. 头槌（只选一次）、发现、scry（预见）等单屏选牌行为不变：牌进手/弃牌正常，不出现「重放不通过」。
4. 打开 `%USERPROFILE%\sts1-engine-solver\settings.txt`，写一行 `limits=low` 或 `limits=unlimited`（也可单键：`limits.combos=unlimited`），重进战斗：算子变快/变细，面板与日志 `limits=` 行跟着变。
5. 选了 `unlimited` 后如果内存吃紧：堆守卫仍会自动收束（日志 `heap guard`），游戏不该卡死；此时把 `limits` 改回 `high` 即可。
6. 若出现「有选牌屏没人答」的情况，日志会有 `selection open with no planned answer: kind=… candidates=…`，把那一行及附近 50 行发我。

## 复测：B87 / P2-60（搞里头、手牌选牌保真）

1. 打出「搞里头」：面板提示两张要选的牌（例如「搞里头（选：打击,打击）」），自动出牌选完后——**这两张牌必须从手牌里消失**（旧版会留在手上）。手牌张数当场减 2。
2. 再打出一次「搞里头」：上次存下的两张牌会被真的打出去（走 limbo → 打牌），不是空过。
3. 全程结束后对照：下一回合的抽牌张数与面板预测一致（旧版会差一张）。
4. `solver.log` 里应有一行 `mirror: mod static-final card container(s) mirrored by content: CaiNongMod.helpers.ModHelper#cards_Inside`（说明搞里头那张静态卡牌表已被沙盒隔离）。没有这行 = 没识别到，请发我日志。
5. 手动打「搞里头」并手选两张牌：沙盒推演不应再把这两张牌算成还在手牌（若仍分叉，把该局 log 发我）。
6. 计划妥当（保留牌）、头槌、发现、scry 等其它手牌选牌行为不变（这些是 S133/S134/S141 覆盖的回归）。


## 复测：B88 / P2-61（效果终态 + 出牌流程审计）

本轮不需要专门手测——补算的是内部状态，用户可见行为应该**没有变化**。可以顺带确认三件事：

1. 连续用「搞里头」：第二段打出的牌伤害/格挡按卡面算（不应出现「打出去的牌伤害是 0 或翻倍」）。
2. 打出一张打不出来的牌（目标已死/能量被抽干后仍在队列里的牌，例如多段结算里最后一张攻击打到已死目标）：不回血、不加格挡、不残留费用异常。
3. 提前结束回合一类的效果（如「腾跃」）之后，上一回合手牌里的牌不应带着上回合的临时改动（费用/伤害）出现在新回合。
   若出现异常，把该局 `solver.log` 里 `effect settle:` 与 `effect dropped without terminal state:` 附近 50 行发我。


## 复测：B89 / P2-63（打出后强制结束回合）

1. 观者打「结末」（Conclude）：面板应把这张牌记在**当前回合**，结束回合那一步自动补在第 1 回合之后；不应该再出现「计划已过期（已经进入新回合）；按 F8 重算」。
2. 让自动出牌跑完整条线：线尾那一步（通常是「结束回合」或下一回合的第一张牌）要真的打出来，日志里没有 `plan stale` 丢弃计划。
3. 时间迷宫（Time Maze，每回合第 12 张牌强制结束回合）触发时同理：不会出现「本回合还差一步」的假象。
4. 手动点「结束回合」的行为不变（这条是 `latchedAtEntry` 保护的：玩家自己的结束回合不该被 drain 再推一次）。
5. 若仍有异常，把该局 `solver.log` 里 `plan stale` / `autopilot stopped` / `S145` 附近 50 行发我。
