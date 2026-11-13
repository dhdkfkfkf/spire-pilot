# sts1-engine-solver 会话约定

本文件补充 `..\AGENTS.md` 的省 token 约定，只写本项目特有的事实。

## 本项目的大文件（禁止整读）

| 路径 | 体量 | 正确用法 |
| --- | --- | --- |
| `evidence\AUTOTEST-*.log` | 单份可达 16MB | 先用 `grep` 工具拿命中行号（必要时先统计条数再定位），再读命中行 ±20 行；或 `Select-Object -Last 200` |
| `research\javap\*.txt` | 单个 250–530KB | 按类名/方法名 grep 拿行号 → 只读该段；不要通读转储 |
| `src\main\java\sts1solver\probe\Probe.java` | 约 580KB | 只按方法定位后 `edit` 定点改，禁止整文件重写/整读 |
| `mod\sts1-engine-solver.jar`、`dist\` 产物 | 二进制 | 不读内容；用 `jar tf`/`-List` 看条目 |

## 工作方式

- 构建/安装/gate 用 `tools\run.ps1`，属长任务：`run_in_background` + 完成后再取尾部日志。
- 编译失败只摘 `ERROR`/`error:`/`BUILD FAILURE` 行和首个堆栈，不要回贴整段 maven 输出。
- 搜索源码优先 `src\main\java` 范围，别把 `research\`、`evidence\`、`build\` 一起递归。
- 需要跨 54 个 `.log` 比对时先用 `grep` 工具的文件清单/条数确认目标文件，再逐文件取结论。
