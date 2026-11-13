# 1.0.0 发布记录（2026-11-13）

本文件记录 m03344 这一轮的四个决定，供后续 AI 直接照做，不必回翻对话。

## 1. 版本号 0.1.0 → 1.0.0

- 唯一来源：`resources/ModTheSpire.json` 的 `version` 字段（构建时打进 jar 根目录）。
- 构建/安装：`tools/install.ps1` → `mod/sts1-engine-solver.jar`，同时复制到
  `D:\Steam\steamapps\common\SlayTheSpire\mods\sts1-engine-solver.jar`。
- 本轮产物：1,243,786 bytes / 537 项 / sha256 `7C5D55F37017DC9B1BDD7A13408B71C8BCCD7651C055BA5046B2BD4FC65A2ADB`。

## 2. 调试面在玩家机器上不可触发

原本有两处会在玩家机器上生效：

1. `autotest.request` 存在时，主菜单会打一场合成战斗（会冻结画面、改走玩家偏好存档）；
2. F5–F12 热键仍然有分支，其中 **F7 会在渲染线程上跑整套自检**（几分钟卡死）、F5/F6 改搜索预算。

现在的判定：`%USERPROFILE%\sts1-engine-solver\debug.keys` 存在才算「作者机器」。
`SolverMod.receivePostInitialize()` 里读一次存进静态 `debugSurface`，随后：

- 没有标记 → 请求文件不仅不执行，还会被删除并记一行 `autotest: request ignored and deleted`；
  启动时的静音（`SilentInstance.arm`）与看门狗解除武装（`Watchdog.setArmed(false)`）也不生效；
- 没有标记 → `checkHotkeys()` 在方向键滚动之后直接 return，F5–F12 全部不可达；
- 面板按钮（计算/执行一步/自动出牌/节奏/秒数/节点/药水/局外收益/收起/隐藏）是唯一操作面，不受影响。

`tools/run.ps1` 启动时会自动 `New-Item` 这个 `debug.keys`，所以本机门禁流程不变。
验证：`tools/run.ps1 -Gates S2` 通过，日志有 `debug surface: ON (debug.keys present)`。

## 3. 兼容性表述（对外统一口径）

「理论上兼容所有只增加内容（卡牌、遗物、药水、角色、怪物、事件）而不修改战斗结算逻辑的 mod。」

已写入三处：`resources/ModTheSpire.json` 的 `description`、`README.md` 新增的 `## Compatibility`、
以及工坊 `config.json` 的 `description`。理由（引用时不要删）：求解器调用游戏自己的代码、没有卡牌表；
无法忠实复现的分支不展开，面板底部写「未展开」，所以边界外表现为「不展开」而不是「错误最优解」。

## 4. 工坊工作区

- 项目里的空模板 `dist/workshop/sts1-engine-solver/` 已删除（`dist/workshop` 空目录也一并删）。
- 用户自己的工坊工作区：`D:\mods-workshop\sts1-engine-solver\`
  含 `config.json`（已填 title/description/visibility=private/changeNote=1.0.0 首个公开版本，tags 留空）、
  `content\sts1-engine-solver.jar`（1.0.0）、`image.jpg`（1024×1024 封面）、`README.md`。
- 上传：`D:\Steam\steamapps\common\SlayTheSpire\jre\bin\java.exe -jar D:\Steam\steamapps\common\SlayTheSpire\mod-uploader.jar new -w D:\mods-workshop\sts1-engine-solver`
  → `upload -w ...`；需 Steam 客户端已登录运行；tags 填错会整体上传失败，`tool` 标签保留给工具类 mod。
## 5. 删除 F5–F12 按键代码（m03455，1.0.0 最终形态）

用户要求「把所有 F5 到 F12 按键的游戏代码删掉」。`SolverMod.checkHotkeys()` 重命名为
`checkPanelKeys()`，方法体只剩方向键 / PageUp / PageDown / Home 滚动路线区；F5、F6、F7、F8、F9、
F10、F11、F12 八个分支全部删除。`receivePostUpdate` 的调用点与注释同步更新，`Gameplay` 中
`combat detected: F8 solves it` 改为 `the 计算 button solves it`。

jar 校验：`SolverMod.class` 常量池中已无 `F5: deep seconds` / `F6` / `F7: differential` /
`F8: solve` / `F9: execute` / `F10: execute` / `F11: overlay` / `F12` / `Shift+F8` / `checkHotkeys`。
重新构建：1,242,952 bytes / 537 项 / sha256 `575414F23D9FCFB3CE6A2497BBDB6B38E96ED9E20CCD150E76F024E765FF37CB`，已装入游戏。

`debug.keys` 门槛保留，但现在只用于 `autotest.request`（`tools/run.ps1` 自检通道）。
`Probe.selfCheckInteractive()` / `Hud.demo()` 仍保留为方法，只是不再有按键绑定。
