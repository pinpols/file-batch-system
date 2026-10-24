# 本地日志布局

本地脚本统一使用 `scripts/lib/logging.sh` 创建日志目录。目标是把“常驻应用日志”和“一次性运行日志”分开,避免 `logs/` 根目录继续堆 `run2.log`、`jacoco-run-2` 这类不可读文件。

## 目录约定

```text
logs/
  current/
    app/                 # start-all / restart 当前进程日志
    docker/              # docker 应用日志落盘目录
  runs/
    tests/<run-id>/      # run-tests.sh 每次执行
    be-acceptance/<run-id>/
    sim-harness/<run-id>/
    sim-4day/<run-id>/   # sim-4day 4 天批量/批量日/观测快照
  verification/<domain>/ # 按验证领域分组的运行日志
  archive/
    app-<component>/     # 按 console / orchestrator / worker 等组件分组的历史应用日志
    legacy/              # 首次迁移旧 logs/app、logs/test 等目录

.local/run/pids/
  start-all.pids           # 本地进程状态，不属于日志
```

`logs/` 的真实目录最多三层。协调过程快照、SQL 输入、源码备份、编译产物和依赖缓存不属于运行日志，统一放在被忽略的 `tmp/log-artifacts/` 或 `tmp/cache/`；PID 状态保存在 `.local/run/pids/`。运行报告和复现资产由本地迁移清单记录，不混入应用日志目录。

## 兼容路径

以下旧路径会自动变成软链,旧脚本/人工习惯短期仍可用:

- `logs/app` -> `logs/current/app`
- `logs/docker` -> `logs/current/docker`
- `logs/test` -> 最新一次 `logs/runs/tests/<run-id>`
- `logs/be-acceptance` -> 最新一次 `logs/runs/be-acceptance/<run-id>`
- `logs/sim-4day` -> 最新一次 `logs/runs/sim-4day/<run-id>`
- `logs/start-all.pids` -> `.local/run/pids/start-all.pids`

## 命名规则

- 常驻应用:`<component>.log`,例如 `orchestrator.log`、`worker-import.log`。
- 验收步骤:`<step>-<action>.log`,例如 `01-build-maven.log`、`03-test-integration.log`。
- 测试结果:`<step>-test-<scope>-failed.log`,例如 `02-test-integration-failed.log`。
- 4day 仿真:`00-run-4days.log`、`02-day0-2026-06-06.log`、`02-day0-2026-06-06-watch.log`。
- run id:`<label>-YYYYMMDD-HHMMSS-<git-sha>`。

归档目录：

- 常驻日志归档文件：`logs/archive/app-<component>/<component>.YYYYMMDD-HHMMSS-<git-sha>.log`
- Docker 日志归档文件：`logs/archive/docker/<service>-YYYYMMDD-HHMMSS-<git-sha>.log`
- 一次性运行目录：`logs/archive/legacy/` 用于第一次碰到旧布局（如 `logs/app`）时自动迁移保留。

旧目录首次遇到时会移动到 `logs/archive/legacy/` 后再创建软链,不会直接删除。

每次获取当前日志目录时会执行一次轻量整理：误放在 `logs/current/` 或 `logs/current/app/` 的 `.gz`、`*.screen.log`、`*.manual*.log`、`*.debug*.log` 和压测日志会移动到 `logs/archive/legacy/`。`logs/current/app/` 只保留当前常驻应用的规范 `.log`，确保运行时 WARN 审计不会重复扫描历史或人工日志。Spark E2E 依赖缓存和本地进程 PID 状态分别保存在 `tmp/cache/` 与 `.local/run/pids/`，不放入日志目录。

`cleanup-disk.sh --include-app-logs` 只清理超过保留期的 `logs/archive/app-<component>`，不会删除
`logs/current/app` 中仍可能被进程持有的当前日志。相同组件在同一秒内连续启动时，归档名会追加
`.1`、`.2` 等序号，避免覆盖。
