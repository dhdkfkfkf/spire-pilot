# 日志是怎么做的、能不能用来查 mod 的错、会不会拖慢计算

> 2026-10-30 实测记录（用户 m11505 两问）。数字都在本机跑出来的，不只是推断。

## 1. 现在的做法

| 项目 | 现状 |
|---|---|
| 文件 | `%USERPROFILE%\spire-pilot\solver.log` = **当前会话**；上一会话在 `logs\solver-<时间>.log`（B39 起）。worker 进程按 `-Dsts1solver.instance=w1` 加后缀 → `solver-w1.log` |
| 写入 | `util\Log`：`PrintWriter(new OutputStreamWriter(new FileOutputStream(path, true), UTF_8), true)`，每行 `println` 后**立即 `flush`**，方法上 `synchronized` |
| 格式 | `HH:mm:ss.SSS LEVEL message`；级别 `INFO/WARN/ERROR/PASS/FAIL` |
| 不进控制台 | 故意不 `System.out`：ModTheSpire 的控制台是 Swing `JTextArea`，每次 flush 在调用线程整块重排（P1-2d 实测把一场战斗一半时间花在那）。solver.log 是唯一通道 |
| 体量 | B39 之前是 68,722,617 字节 / 834,286 行（一个文件累积几十次运行）；B39 之后每会话一个文件，单文件 16 MB 上限，`logs\` 合计 ≤ 200 MB / ≤ 30 个 |
| 编码 | B39 起**统一 UTF-8**，每个文件第一行是 `# sts1-solver log v2 utf-8 …`；2026-10-30 之前归档下来的旧文件仍是混编码（旧段用 `[Text.Encoding]::GetEncoding(936)` 读） |
| 每场的量 | 一次 F8 求解 **5–10 行**（started / summary / plan / wall clock / profile / A3），`-Mode longrun` 36 秒跑下来共 42 行 |

## 2. 会不会拖慢计算：不会（有实测）

同一段代码路径（`PrintWriter(FileWriter,true)` + 每行 flush）在本机跑 2000 行 × 3 轮：

```
round 0: 7.5 us/line    round 1: 4.6 us/line    round 2: 4.8 us/line
```

换算成实际影响：

- 一次求解写 10 行 ≈ **0.05 ms**；一次求解本体是 1–4 s（快的一次 100 ms）⇒ 占比 **0.005%–0.05%**；
- 全速长跑（`-Mode longrun`）36 秒 42 行 ≈ 0.2 ms，可以忽略；
- **搜索里没有逐节点日志**（这是设计选择）：20k 节点若每节点一行就是 0.1 s，200k 节点 1 s，那才开始有感；现在只在层末/结束时说话。

唯一有成本的不是写文件，而是**读**：68 MB / 83 万行的文件用 PowerShell `Get-Content` 扫一遍要几十秒，
所以查日志要按时间前缀或关键词先筛（见第 4 节）。

## 3. 加 mod 之后，日志能不能定位错误：分四类，前三类能，第四类不能

### 能：这个 mod 到底进来了什么

- 启动：`content surface: accepted (no whitelist); hook profiles=[…]`
- 进战斗：`content: 来源: 含 mod 内容（按真实引擎模拟）: 类名…` 或 `来源: 原版 + N 个 mod 战斗钩子（模拟时照常运行）: demoMod.ninjaMod#postDrawSubscribers…`（开「兼容实验模式」后括号里变成「模拟时不运行」）
- 静态镜像：`mod statics: 395 field(s) in 779 class(es) from 20 jar(s), 1 not loadable, 2063 final (52ms)`
- 模拟期间被隔离的第三方事件（B197，仅「兼容实验模式」）：`sandbox: N BaseMod hook dispatch(es) not sent to other mods (兼容实验模式; …)`——本次搜索里沙盒没有广播出去的战斗事件数。卡死排查先看它：N > 0 说明当时有 mod 在监听这些事件，而它们的代码没有在沙盒里运行。

### 能：mod 代码抛异常（带完整栈，栈里就有 mod 的类名）

- 搜索里某一支炸了：`expand failed (Nx): <异常>`，随后 `expand failed, stack follows` + 完整 `printStackTrace`
  （**只打第一次**，后面只计数，避免刷屏）；
- 引擎 update 炸了：`engine update threw: phase=… turnHasEnded=… queues=a/b/c player=ok hand=5` + 栈；
- 计划重放炸了：`plan replay failed at step N: <异常>`；
- 选择屏/奖励屏炸了：`live selection failed` / `answerCardReward failed` + 栈。

栈里出现 `demoMod.*`、`CaiNongMod.*` 这样的帧，就是该 mod 的代码在抛。

### 能：mod 把状态改得和预测不一样（最有用的那一类）

```
PREDICTION DRIFT at step 4: 打击 → 持盾地精
  expected t=2 ph=EXECUTING_ACTIONS q=1/0/0 | P hp=55/60 b=8 e=0 pw=Vulnerable:2 rl=… or=Dark:6/… H=… M0=GremlinWizard:25/25 …
  actual   t=2 ph=WAITING_ON_USER   q=0/0/0 | P hp=55/60 b=0 e=0 pw=- rl=… or=-        H=… M0=GremlinWizard:25/25 …
```

`Digest.ofCurrent()` 是**可读指纹**（不是哈希）：回合/相位/三个队列/HP/格挡/能量/姿态/能力/遗物/充能球/
四堆牌/每只怪的 HP·格挡·意图·能力·濒死标记。所以两个状态哪里不一样，肉眼能对出来。
这正好是排查 mod 的入口：mod 如果偷偷加了格挡、改了意图、多生了一个球，都会在这里露出来。

### 不能（当前的三个盲区）

1. **没有归因**：漂移只能告诉你「状态不一样」，不会说「是 ninjaMod 的 onCardUse 写的」。得自己顺着钩子名单猜；
2. **没有逐动作效果 diff**：只打「这一步之前/之后的样子」，不打「这张牌造成了 3 点伤害、+2 格挡」这种分项；
3. **没有钩子计数**：一次搜索里 BaseMod 的 9 个钩子各被调了多少次、哪些返回了非空，日志里没有。

## 4. 查日志的速查表（按关键词筛就够）

```powershell
$p = "$env:USERPROFILE\spire-pilot\solver.log"
# 最近 200 行
Get-Content $p -Tail 200
# 只看结论与漂移
Select-String -Path $p -Pattern "content:|mod statics:|solve:|plan :|PREDICTION DRIFT|refused|stale" | Select-Object -Last 40
# 只看异常（含 mod 栈）
Select-String -Path $p -Pattern "ERROR|expand failed|engine update threw|replay failed" | Select-Object -Last 20
```

| 想知道 | 搜这个 |
|---|---|
| 这一场判了什么 | `solve:` / `plan :` / `route:` |
| 预算/档位 | `quick search: started` / `deep search: started`（含 `boss=x2`） |
| 有没有 mod 内容/钩子 | `content:` / `content surface` / `mod statics:` |
| 点计算整帧卡死（B197） | `sandbox: N BaseMod hook` / `content:` 里的钩子名单 / `hang-*.txt` 的栈（默认模式下没有 sandbox 行，先看 `content:` 和栈） |
| 和预测不一致 | `PREDICTION DRIFT` 后两行 `expected` / `actual` |
| 为什么没出计划 | `solve refused:` / `plan stale:` / `expand failed` |
| 自动出牌为什么停 | `autopilot stopped:` / `plan exhausted` / `pending action did not settle` |

## 5. 如果要补这三个盲区（可选，半天）

1. `-Dsts1solver.verbose=1`（或 settings.txt 开关）：每个动作前后打一行**分项 diff**（HP/格挡/能量/手牌增减/怪物 HP 变化），
   只在手动开的时候生效；
2. **归因**：异常与漂移时，把栈帧按包名分类（`com.megacrit.` / `com.badlogic.` / `java.` = 原版，其余 = mod），
   打印 `mod frame: demoMod.ninjaMod.X.y (ninja)`；漂移时额外打印「这次执行里被调过的非原版类」。
3. **钩子计数**：一次搜索结束时打 `hooks: postDraw=12 onCardUse=3 …`（已在 `ModHooks` 有名单，加计数即可）。
4. 顺带修两个运维问题：32 MB 轮转（保留 2 份）+ 新文件统一 UTF-8 并写一行 `encoding: utf-8` 头，
   以后再也不用猜编码。

这四项都不改判定，只加可观测性；加起来约半天（含门禁一条：verbose 关闭时行数与现在一致）。

## 6. 对照：STS2 的 CombatSolver 怎么做日志（2026-10-30 读源码）

入口 `src\Runtime\CombatSolverLog.cs`（17 行）：`Info/Debug/Warn/Error` → `CombatDiagnosticJournal` + `PerformanceRecording.Log`。

| 方面 | CombatSolver 的做法 |
|---|---|
| 位置 | 每个进程建一个目录 `<根>/<pid>-<guid>/`；根目录由 `CombatSolverLog(directory)` 传入 |
| 文件结构 | `process.jsonl`（生命周期 + 白名单性能事件，上限 **4 MB**）+ 每场战斗一个 `combat-<id>.jsonl` |
| 行格式 | 一行一条 JSON：`{time(Unix 毫秒), level, message}`，UTF-8（`JavaScriptEncoder.UnsafeRelaxedJsonEscaping`，中文不转义） |
| 写入 | `AppendOnlyEventLog`：后台线程 + `Channel`，append 立刻返回，**不阻塞搜索线程**；待写内存上限 8 MB，超了记 `event_pending_memory_limit` 并丢弃 |
| 单文件上限 | 默认 32 MB（`process.jsonl` 用 4 MB）；超了**停止写这个文件**并记 `event_file_size_limit`——**不轮转** |
| 怎么更新 | `BeginCombat` 把上一场「退休」：同 seed → 压一条 summary 进内存历史（最多 **100** 条）；不同 seed → 历史清空 |
| 会不会清理 | **会**：①`retainPreviousCombatDetails=false` 时上一场的 `combat-*.jsonl` 在后台 `File.Delete`（删失败只在 `process.jsonl` 记 `log_cleanup_failed`）②进程一换就是新目录（旧目录靠外部脚本清）③单文件到上限自停 |
| 归档/上报 | `CaptureAsync()`（FIFO 栅栏，最多 2 个排队）把当前进度截图成 `EventLogSnapshot`，交给 `CombatBugReportExporter` 打成 bug 报告压缩包 |
| 级别 | 字符串 `info/debug/warning/error`；**没有运行时级别阈值**，Debug 也照写（靠文件上限兜底） |

一句话：它的日志是**逐场结构化 + 逐场自清理 + 硬上限自停**，代价是「上一场结束就没了」，需要留证据时得在结束前抓快照。

我们 vs 它：

| | 我们 | CombatSolver |
|---|---|---|
| 形式 | 单文件纯文本（人读友好） | 逐场 JSONL（机器读友好） |
| 体量控制 | 无（现 68.7 MB / 83 万行） | 逐场删除 + 单文件 4/32 MB 上限 |
| 会话隔离 | 靠时间戳前缀和 runId 行 | 目录名带 pid+guid，天然隔离 |
| 阻塞搜索 | 是（每行同步 flush，实测 4.6–7.5 µs） | 否（后台线程） |
## 7. 日志怎么收拾（✅ B39 已实现，2026-10-30，门禁 S78，套件 554/0）

现状的三个问题（B39 要解决的）：①`solver.log` 无上限，只会更大；②混编码（旧 GBK 新 UTF-8）；③越到后面越难读——今天的会话和两周前的会话在同一文件里。

### 7.1 规则：三条同时生效

| 规则 | 数值 | 说明 |
|---|---|---|
| 单文件上限 | **16 MB** | 活文件写满就轮转：内容进 `logs\`，活文件从空开始，并写一行 `log rotated: …` |
| 保留年龄 | **7 天** | 超过 7 天的归档删除 |
| 保留数量 | **30 个** | 超过就删最旧 |
| 保留总量 | **200 MB** | 超过就删最旧 |
| 清理频率 | 最多每 **5 分钟** 一次 | 不在热路径上 `stat` 目录 |

**`solver.log` 永远是当前会话**，所以 `docs/TESTING.md` 里的 grep 命令不用改；旧内容在 `logs\solver-<yyyyMMdd-HHmmss>.log`。每个文件第一行是头：`# sts1-solver log v2 utf-8 started=… pid=… instance=main cap=16MB retention=7d/30files/200MB`。

### 7.2 实现落点

- `src\main\java\sts1solver\util\LogFiles.java`（新）：`logsDir`、`shouldRotate`、`archive`（move，失败则 copy+truncate）、`retain`（年龄 → 数量 → 总量，按 mtime 从旧删起）、`header`。纯文件操作，无游戏状态。
- `src\main\java\sts1solver\util\Log.java`（v2）：UTF-8；`open()` 先归档上一会话再写头；`write()` 里每行计数、满 16 MB 轮转、每 5 分钟最多清理一次。
- `tools\run.ps1`：原来按“启动前行数”跳过旧行，轮转后行数变小会永远等不到 `AUTOTEST DONE`；改成 `Get-FreshLog`（活文件比启动前短 = 发生过轮转 = 偏移归零）。

### 7.3 第一次实跑抓到的真 bug（值得记）

第一次跑完，`logs\` 是空的、活文件第一行还是旧行、头出现在第 834287 行 —— 归档失败后仍以追加方式写进旧文件。单 JVM 里 move 同一个 68 MB 文件是成功的，差别在于 **`tools\run.ps1` 正在轮询这个文件**：Windows 上 rename 被句柄拒绝（错误文本“另一个程序正在使用此文件”，且到达 Java 时已错误解码成乱码），`Files.move` 抛异常被吞掉。

修法：①move 失败回退 **copy + truncate**（只要读/写权限，不要 delete 共享），并把原因写进 `lastError`，活文件里留一行 `log archive: …`；②`LogFiles.reason` 把系统错误文本过滤成 ASCII（限 120 字符），空则写 `the file is locked by another process`，不再把乱码写进 UTF-8 日志。

### 7.4 验证

- 门禁 **S78** 十条：轮转阈值（15 MB=false / 16 MB=true / cap=0 关闭）、上一会话变归档且活文件名重新可用、空文件不留空归档、超龄删（2 removed / 3 left）、数量上限（6 removed / 4 left）、总量上限（3 removed / 2,000 B left）、生产常量 16 MB/7d/30/200 MB、归档成功时 `lastArchive` 有名字且 `lastError=null`、以及“门禁只在临时目录里跑，不碰玩家日志”。
- 真实运行（23:21）：活文件 141,211 B 从头发开始，`logs\solver-20261030-231501.log` 68,863,453 B，归档合计 69,004,503 B（未超 200 MB）。回退路径也在 23:19 那次真实触发过（`rename refused …, copied and truncated instead`）。

### 7.5 怎么用

```
# 当前会话
Get-Content "$env:USERPROFILE\spire-pilot\solver.log"
# 上一会话 / 更早（文件名=归档时刻）
Get-ChildItem "$env:USERPROFILE\spire-pilot\logs" | Sort-Object LastWriteTime -Descending | Select-Object -First 5
```

想清空：删 `logs\` 里的文件即可。合计上限约 200 MB + 16 MB，正常使用到不了。

## 8. B45：跑套件时屏幕上会打什么（时间点与心跳）

`tools\run.ps1` 现在把「卡在哪一步」直接写出来，不用再靠等超时：

```
launching : -jar ...ModTheSpire.jar --skip-launcher --skip-intro
first log : +12s (the mod's classes are loaded)
requested : +17s (the suite started; before this the game is still booting)
waiting   : +75s, 349 line(s) this run, last=10:51:52.083 PASS the headline still calls it ...
startup   : first log line +12s, suite started +17s, finished +247s
```

| 行 | 含义 |
|---|---|
| `first log : +Ns` | 游戏进程已经把我们的类加载起来并写出第一行日志；更大的数字=启动慢（19 mod 约 12 s） |
| `requested : +Ns` | 门禁/覆盖开始跑（之前都是 MTS/游戏启动） |
| `waiting   : +Ns, N line(s)` | 每 15 s 一次心跳：耗时、本次新增行数、最后一行。**这个数在涨=没卡住** |
| `EXITED    : the game ended (exit code N)` | 进程自己退了却没报 done（=真崩溃，看 `sendToDevs\logs\SlayTheSpire.log`），脚本 exit 5 |
| `STALLED at startup / while booting / inside the suite` | 超时了，并说明死在哪一段 |

日志本身仍是每行几微秒（LOGGING §2），心跳不会拖慢套件。

### 8.1 B46：探针实例的静音标记（每会话第一行左右）

```
silent instance: audio muted for this process (player had music=0.5 sound=0.5 master=1.0000002; why=probe request present at startup)
```

出现在 `solver.log` 最前面（第二条日志线附近）。含义：这个进程是工具起的测试实例，音量已整进程归零；`player had …` 是读取到的玩家原值（`master` 直接来自 `preferences\STSSound`，`music`/`sound` 缺键时为默认 0.5）。

`why=` 的三种取值：`probe request present at startup`（启动时请求已在盘上）、`probe request (mode=…)`（请求后到，例如 worker pool 派单）、`S87`（门禁自己调的）。

正常启动游戏玩不会出现这一行；出现这一行就说明这个进程是我起的、不会发声。

