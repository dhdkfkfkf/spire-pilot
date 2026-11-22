# spire-pilot 会话约定

本文件即本项目约定，只写本项目特有的事实。

## 本项目的大文件（禁止整读）

| 路径 | 体量 | 正确用法 |
| --- | --- | --- |
| `evidence\AUTOTEST-*.log` | 单份可达 16MB | 先用 `grep` 工具拿命中行号（必要时先统计条数再定位），再读命中行 ±20 行；或 `Select-Object -Last 200` |
| `research\javap\*.txt` | 单个 250–530KB | 按类名/方法名 grep 拿行号 → 只读该段；不要通读转储 |
| `src\main\java\sts1solver\probe\Probe.java` | 约 580KB | 只按方法定位后 `edit` 定点改，禁止整文件重写/整读 |
| `mod\spire-pilot.jar`、`dist\` 产物 | 二进制 | 不读内容；用 `jar tf`/`-List` 看条目 |

> 迁移说明：`evidence\` 与 `research\javap\` 都被 `.gitignore` 排除，未随仓库带过来；
> 表里的读取规则在它们重建后继续适用。

## 工作方式

- 构建/安装/gate 用 `tools\run.ps1`，属长任务：`run_in_background` + 完成后再取尾部日志。
- 编译失败只摘 `ERROR`/`error:`/`BUILD FAILURE` 行和首个堆栈，不要回贴整段 maven 输出。
- 搜索源码优先 `src\main\java` 范围，别把 `research\`、`evidence\`、`build\` 一起递归。
- 需要跨 54 个 `.log` 比对时先用 `grep` 工具的文件清单/条数确认目标文件，再逐文件取结论。

## 发布 / 创意工坊上传约定

- **`changeNote` 只写当前版本的改动，不追加历史条目。** 玩家更新说明里只需要看到「这一版改了什么」，
  旧版本内容留在 CHANGELOG 与工坊描述正文里。
- **`config.json` 的 `description` 上限是 8000 字节（UTF-8），不是字符数。** 上传前必须按字节校验；
  1.7.2 第一次上传就是因 8,455 字节返回 `SteamResult=InvalidParam`。
- `description-bbcode.txt` 必须与 `config.json` 的 `description` 逐字节一致；描述保持纯 BBCode，
  不混 emoji。
- 上传包在 `dist\workshop-ws\`，真人双击 `upload.cmd`；上传前确认游戏与 ModTheSpire 已关闭、
  Steam 已登录。

## Agent skills

### Issue tracker

Issues and specs live in this repo's GitHub Issues (`dhdkfkfkf/spire-pilot`), via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

The five canonical triage roles: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

single-context: root `GLOSSARY.md` + `docs/adr/`. See `docs/agents/domain.md`.
