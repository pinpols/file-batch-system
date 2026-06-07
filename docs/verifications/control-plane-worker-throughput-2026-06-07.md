# Control Plane Worker Throughput Baseline - 2026-06-07

> 范围:process / dispatch / atomic / trigger
> RUN_ID:`ctlw-20260607202126`
> 结论级别:小基线已跑通,不是容量上限报告

## 结论

1. process、dispatch、atomic、trigger 四类控制面 worker 已补独立 benchmark 入口,默认不再夹带 import/export。
2. 本轮走正常系统链路:trigger API -> orchestrator -> Kafka -> worker -> DB。没有走前台模拟。
3. 小基线全部成功,错误率 0%。process/dispatch/atomic 各 2 个直接 pipeline 任务成功;trigger 30 次 launch 成功,并同步压内部 scheduler snapshot/history 读接口。
4. trigger 读写已经并行执行。process/dispatch/atomic 本轮是按模块顺序跑,模块内部并发为 `USERS=2`;跨模块同时加压还没有做。
5. Kafka lag 未采到,原因是本地 `batch-kafka` 容器里没有 `kafka-consumer-groups` 命令;脚本已降级为 best-effort,不再导致 benchmark 失败。

## 本轮做了什么

| 项 | 状态 | 说明 |
|---|---|---|
| 新增控制面 benchmark 脚本 | 已完成 | `load-tests/scripts/run-control-plane-worker-benchmark.sh` |
| process/dispatch/atomic 只 launch 后 DB 等终态 | 已完成 | 避免 console 登录/轮询影响 worker 基线 |
| trigger launch 与 scheduler 读压并行 | 已完成 | `SchedulingBacklogUnderLoadSimulation` + `sample-scheduler-backlog.sh` |
| console 读接口可选化 | 已完成 | 默认 `SCHEDULING_CONSOLE_READS=false` |
| atomic/trigger 参数透传 | 已完成 | Gatling launch 使用 `launch.paramsJsonFile` |
| 报告模块拆分 | 已完成 | 按 `metadata.benchmarkModule` 拆 direct atomic 与 trigger 拉起的 atomic job |
| `.env.local` 内部密钥读取 | 已完成 | 默认从 `BATCH_INTERNAL_SECRET` 取值 |

## 执行命令

```bash
SKIP_AUTO_CLEANUP=1 \
USERS=2 \
MODULES_CSV=process,dispatch,atomic,trigger \
TRIGGER_LAUNCH_RPS=1.0 \
TRIGGER_READ_RPS=1.0 \
TRIGGER_DURATION_SECONDS=30 \
WAIT_TERMINAL_TIMEOUT_SECONDS=240 \
bash load-tests/scripts/run-control-plane-worker-benchmark.sh
```

## 结果摘要

| 模块 | Job | 实例数 | 成功 | 失败 | 非终态 | 平均端到端秒 | P95 端到端秒 |
|---|---|---:|---:|---:|---:|---:|---:|
| process | `lt_process_sql_job` | 2 | 2 | 0 | 0 | 0.636 | 0.655 |
| dispatch | `lt_dispatch_local_job` | 2 | 2 | 0 | 0 | 1.854 | 2.818 |
| atomic | `atomic_sql_demo` | 2 | 2 | 0 | 0 | 2.160 | 3.065 |
| trigger | `atomic_sql_demo` | 30 | 30 | 0 | 0 | 0.954 | 3.130 |

## Task 延迟

| 模块 | Task type | 任务数 | 成功 | 失败 | 平均 claim 秒 | P95 claim 秒 | 平均执行秒 | P95 执行秒 |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| process | PROCESS | 2 | 2 | 0 | 0.431 | 0.551 | 0.193 | 0.290 |
| dispatch | DISPATCH | 2 | 2 | 0 | 1.776 | 2.761 | 0.067 | 0.091 |
| atomic | ATOMIC | 2 | 2 | 0 | 2.129 | 3.031 | 0.020 | 0.022 |
| trigger | ATOMIC | 30 | 30 | 0 | 0.928 | 3.092 | 0.017 | 0.031 |

## Trigger API 指标

| 接口 | 请求数 | 成功 | 失败 | 平均 ms | P95 ms | P99 ms |
|---|---:|---:|---:|---:|---:|---:|
| `POST /api/triggers/launch` | 30 | 30 | 0 | 33 | 77 | 239 |
| `GET /internal/scheduler/snapshot` | 30 | 30 | 0 | 30 | 43 | 222 |
| `GET /internal/scheduler/snapshot/history` | 30 | 30 | 0 | 10 | 30 | 55 |

## Worker 状态

| Worker group | Worker | 状态 | 当前负载 | 最大并发 |
|---|---|---|---:|---:|
| PROCESS | `process-node-1` | ONLINE | 0 | 10 |
| DISPATCH | `dispatch-node-1` | ONLINE | 0 | 10 |
| ATOMIC | `atomic-node-1` | ONLINE | 0 | 10 |

`dispatch-node-drain` 是历史 OFFLINE 节点,不参与本轮结论。

## 未做项

| 项 | 是否能做 | 本轮不做原因 | 下一步 |
|---|---|---|---|
| 1w/10w task storm | 能做 | 小基线先验证链路和指标口径 | 提高 `USERS` / `TRIGGER_LAUNCH_RPS`,分 1k -> 1w -> 10w 阶梯跑 |
| process 1000w staging/聚合 | 能做 | 需要准备更大业务输入和 PG 资源窗口 | 单独跑 process 大数据脚本,采 `pg_stat_statements`/WAL/IO |
| dispatch remote SFTP/NAS/EMAIL/OSS | 能做 | 依赖外部服务或本地 mock endpoint | 放到可选矩阵,不进安全默认压测 |
| atomic stored-proc/http/shell | 部分能做 | SQL executor 是默认安全路径;shell 默认关闭 | stored-proc/http 补本地 fixture 后跑;shell 只 opt-in |
| Kafka lag | 能做 | 当前 Kafka 镜像缺 `kafka-consumer-groups` | 用宿主机 Kafka CLI 或换带 CLI 的镜像采样 |
| 失败重试/背压/lease renew 压力 | 能做 | 需要故障注入,不能和成功基线混跑 | 单独建 failure profile |
| 跨模块真并行 | 能做 | 本轮模块顺序跑,避免 Maven/Gatling 输出互相覆盖 | 新增单 simulation 或独立进程隔离 output 后跑 |

## 产物

| 类型 | 路径 |
|---|---|
| 自动报告 | `load-tests/target/control-plane-worker-report-ctlw-20260607202126.md` |
| 日志目录 | `load-tests/target/control-plane-worker-logs/ctlw-20260607202126` |
| Trigger backlog CSV | `load-tests/target/control-plane-worker-logs/ctlw-20260607202126/trigger-scheduler-backlog.csv` |
| Gatling process | `load-tests/target/gatling-results/launchpipelinecompletionsimulation-20260607122137847/index.html` |
| Gatling dispatch | `load-tests/target/gatling-results/launchpipelinecompletionsimulation-20260607122152107/index.html` |
| Gatling atomic | `load-tests/target/gatling-results/launchpipelinecompletionsimulation-20260607122206340/index.html` |
| Gatling trigger | `load-tests/target/gatling-results/schedulingbacklogunderloadsimulation-20260607122220823/index.html` |

## 当前判断

这轮没有暴露 process/dispatch/atomic/trigger 的功能性失败;P95 claim 延迟主要来自调度/worker 领取周期,不是 task 执行本身。后续优化应先做高并发阶梯压测和 Kafka/DB 采样,再决定是否调 worker 并发、outbox poll batch、claim/report 批量化或 process staging 写入策略。
