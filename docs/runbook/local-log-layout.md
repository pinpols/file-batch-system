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
  archive/
    legacy/              # 首次迁移旧 logs/app、logs/test 等目录
  pids/
    start-all.pids
```

## 兼容路径

以下旧路径会自动变成软链,旧脚本/人工习惯短期仍可用:

- `logs/app` -> `logs/current/app`
- `logs/docker` -> `logs/current/docker`
- `logs/test` -> 最新一次 `logs/runs/tests/<run-id>`
- `logs/be-acceptance` -> 最新一次 `logs/runs/be-acceptance/<run-id>`
- `logs/sim-4day` -> 最新一次 `logs/runs/sim-4day/<run-id>`
- `logs/start-all.pids` -> `logs/pids/start-all.pids`

## 命名规则

- 常驻应用:`<component>.log`,例如 `orchestrator.log`、`worker-import.log`。
- 验收步骤:`<step>-<action>.log`,例如 `01-build-maven.log`、`03-test-integration.log`。
- 测试结果:`<step>-test-<scope>-failed.log`,例如 `02-test-integration-failed.log`。
- 4day 仿真:`00-run-4days.log`、`02-day0-2026-06-06.log`、`02-day0-2026-06-06-watch.log`。
- run id:`<label>-YYYYMMDD-HHMMSS-<git-sha>`。

旧目录首次遇到时会移动到 `logs/archive/legacy/` 后再创建软链,不会直接删除。
