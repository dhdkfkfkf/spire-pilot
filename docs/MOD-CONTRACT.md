# mod 接入契约（没有白名单：所有 mod 都按真实引擎跑）

## 定位

- **原版**（4 角色 / 370 卡 / 43 药水 / 190 遗物 / 66 怪物）是**跑过全套自动化测试的那一部分**。
- **mod 一律接受**（2026-10-30 起，用户 m11229）：不再有「未认证」这个门槛，也不再需要往白名单里登记。
  理由：我们不是重写规则，而是把**真实引擎**搬到沙盒里跑——只要内容走的是引擎接口
  （卡/遗物/药水/怪物/角色/充能球/power），它就天然被正确模拟，mod 与原版没有区别。

## 判定（现在只剩「来源」，没有「认证」）

运行期检查这一场战斗里出现的所有内容（玩家类、手牌/抽牌堆/弃牌堆/消耗堆/牌库、遗物、药水、玩家能力、
怪物及其能力）的**类包名**，以及 BaseMod 的战斗钩子注册表，只用于**如实报告来源**：

- 全部 `com.megacrit.cardcrawl.` 且 0 个外部战斗钩子 ⇒ `来源: 原版`
- 原版内容 + N 个外部战斗钩子 ⇒ `来源: 原版 + N 个 mod 战斗钩子: <类#钩子表 …>`
- 出现非原版类 ⇒ `来源: 含 mod 内容（按真实引擎模拟）: <名字 …>`（面板结论行也写「（含 mod 内容：按真实引擎模拟）」）

这三种说法都**不再**带「未认证 / 结果不保证」的限定。启动日志打
`content surface: accepted (no whitelist); hook profiles=[…]`。

### 为什么以前有门槛，现在没有

旧版本把「0 个第三方战斗钩子」当成认证条件（与参考项目 STS2 的 `RootCombatModSubscriberCount` 等三条阻断对齐）。
但那三条在 STS2 里成立，是因为 STS2 自己重写规则、必须知道哪些 mod 逻辑被漏掉了；我们是真跑引擎，
钩子照样被真跑，所以「有钩子」不等于「算错」。门槛的真正价值只是**提示哪里可能出问题**，
这个信息保留在来源行里，但不再阻止计算。

## 仍然真实存在的三道边界（不是门槛，是盲区）

1. **mod 自有的全局状态**：`public static` 的标量现在会被自动扫描镜像（见下节 `ModStatics`），
   但 mod 的**单例 manager 对象**（`public static final SomeManager INSTANCE`）只恢复引用，
   不恢复对象内部被改的字段。战斗状态挂在玩家/怪物/牌堆/充能球这些对象图上，随克隆走，已隔离；
   真正被 mod 存在自己的 manager 里的状态属于未覆盖风险。
2. **自绘选择界面**：牌能不能打、结算成什么，走真引擎，自动正确；但选牌/选目标要我们认界面。
   `CardRewardScreen` / `GridCardSelectScreen` 认得（含 Discovery 式三选一、chooseOne），
   其他自绘屏会被**拒答**（拒答比猜安全）。
3. **每帧副作用**：mod 在自己的 `update()` 里直接改实机东西（画 UI、写文件、发网络请求）时，
   沙盒跑的是副本对象，改不到实机；但如果有 mod 直接写实机全局（我们没镜像到的），仍可能漏。
   这一条靠「静态扫描 + 泄漏审计」（`-Dsts1solver.audit`、`StaticSweep`）发现，见 `docs\JUDGMENT.md` R13。

## mod 自己的静态字段：自动扫描，不再手工登记

新增 `mirror\ModStatics.java`（2026-10-30，B38）：反射读 MTS 的 `Loader.MODINFOS`（每个 `ModInfo` 带 `jarURL`），
逐个 jar 扫 `.class`，`Class.forName(name, false, loader)` 不初始化，只收**静态、非 final、标量**
（primitive / String / enum / Color），过滤规则与游戏扫表一致（跳过 VOLUME/MUTED/AUDIO/BGM/SFX 这类纯音频）。
结果并入 `Statics.specs()`（`Kind.VALUE`，install 记实机值 / uninstall 写回），`-Mode genstatics` 的清单也包含它们。

- 本机 20 个 mod jar（含 109MB 与 50MB 的大包）：`mod statics: 395 field(s) in 779 class(es) from 20 jar(s), 1 not loadable, 2063 final (52ms)`，只扫一次。
- 门禁 S77 用一个真的 `AbstractOrb` 子类（原版从未见过的新机制）验证：沙盒里施放 5 个球、计数变 10、
  实机的球位与上限 0/0 没被碰、卸载后计数回到 0。

## profile 文件：从权限表降级为显示名

`<游戏目录>\mods\spire-pilot-content\profiles.txt` 与 `%USERPROFILE%\spire-pilot\content\profiles.txt`
仍然会读，但只用于把 `CAI_NONG.card.Foo` 显示成「CaiNong」。第四列 `trust` 仍被解析（旧文件照常加载），
但**没有任何代码路径拿它当门槛**。删掉 profile 也能正常跑。

## 现状

- 已接入 profile：13 条（只是可读名字）。
- 已实测能跑（两种 mod 角色，打出的就是 mod 卡）：
  ninja `win=true hpLoss=4 steps=19 replay=19/19`、CaiNong `win=true hpLoss=0 steps=16 replay=16/16`
  （`-Mode modchar -AllMods`，20 jar）。与 2026-10-30 早先的 18/18、24/24 不同是预期的——
  mod 静态隔离后沙盒状态更干净，搜索走出的线会变；两条都是「赢」。
- 同一批 `-Mode coverage -AllMods`：cards 541/501（171 张是 mod 卡）、potions 46/44、relics 210/206、
  monsters 67/67、characters 6/6。
- 证据：`evidence\B38-NO-WHITELIST-MOD-STATICS-20261030.txt`（去白名单 + ModStatics + S77）、
  `evidence\P3-1-AUTOSPIRE-COMPARE-20261030.txt`（与竞品 auto-spire 的对比：它对 mod 卡的做法是
  把卡从真实卡池里 `removeIf` 掉，我们的做法是原样跑）。

## 任意 mod 角色能不能自动求解（2026-10-30 更新）

**能。** 原版 4 角色与 mod 角色走同一条入口：抓「这一场真实战斗」的当前状态
（live 捕获 → 指纹校验 → 静态镜像 → 沙盒副本），角色/卡是不是原版不改变入口。

以前的三道门槛现在这样处理：

1. **mod 战斗钩子**（本机 9 个：ninjaMod 的 postDraw/postExhaust/onCardUse/postEnergyRecharge/postPowerApply/postBattle、
   MintySpire、RelicStats）：不再降级结论，只在来源行列出来。沙盒里出牌照样触发它们，效果照样被算进去。
2. **克隆与静态镜像**：游戏静态 + mod 静态都自动镜像（`Statics` + `ModStatics`）；单例 manager 见上面的边界 1。
3. **自绘选择界面**：认得的那几种自动应答，其余拒答并在面板写明原因。

## 想自己验证一个 mod（可选，不再是准入步骤）

1. `powershell -NoProfile -File tools\run.ps1 -Mode coverage -AllMods`（先关游戏）：看该 mod 的卡/药水/遗物/怪物/角色
   是否都在 `ok` 列，有 `threw` / `prompt` 的先记下来；
2. `-Mode modchar -AllMods`：拿 mod 角色开一场合成战斗，看计划能否整条重放到底（`replay=N/N`）；
3. 想更硬的对账：用 `search\Reconcile` 或 `worker\Worker.verify` 同种子比沙盒预测 vs 实机结算
   （胜负一致、HP 差 ≤2、回合差 ≤1），差异会抛异常而不是静默。

### 与搜索架构选项的关系

- **A（短程 rollout 排序）/ A2（同状态合并、支配剪枝）**：仍走真引擎 → 对 mod 零影响；
- **B（数值代理）**：mod 卡在代理里根本不存在 → 结构性冲突，已永久否决。
  详见 `docs\SEARCH-OPTIONS.md` §2。
