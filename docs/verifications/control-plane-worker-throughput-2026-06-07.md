# Control Plane Worker Throughput Baseline - 2026-06-07

> 范围:process / dispatch / atomic / trigger
> RUN_ID:`ctlw-20260607202126`
> 结论级别:小基线已跑通;T1/T2 终态修复后高压复验全终态,不是容量上限报告

## 结论

1. process、dispatch、atomic、trigger 四类控制面 worker 已补独立 benchmark 入口,默认不再夹带 import/export。
2. 本轮走正常系统链路:trigger API -> orchestrator -> Kafka -> worker -> DB。没有走前台模拟。
3. 小基线全部成功,错误率 0%。process/dispatch/atomic 各 2 个直接 pipeline 任务成功;trigger 30 次 launch 成功,并同步压内部 scheduler snapshot/history 读接口。
4. trigger 读写已经并行执行;跨模块真并行入口已补并已跑。
5. Kafka lag 未采到,原因是本地 `batch-kafka` 容器里没有 `kafka-consumer-groups` 命令;脚本已降级为 best-effort,不再导致 benchmark 失败。
6. 追加并行复验 RUN_ID `ctlw-20260607204120`:跨模块真并行入口已达成,Kafka lag 已采到;但暴露 `CREATED + ACCEPTED + zero partition/task` 滞留缺口。
7. 高压复验 RUN_ID `ctlw-20260607231538`:Gatling 900/900 OK,worker 成功子集 claim/exec 都快,但仍有 103/540 个实例停留 `CREATED + NO_TASK`。结论:这不是 worker 执行瓶颈,而是 launch T1/T2 分发 fail-fast 后实例未终态化的 P0 状态机缺陷。
8. T1/T2 修复后复验 RUN_ID `ctlw-202606080130-t1t2`:Gatling 900/900 OK,540/540 实例全部终态,不再残留 `CREATED + NO_TASK`。本地 quota reject 被可观察地终态化为 `FAILED/NO_TASK`。

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
| Kafka lag 采样 | 已完成 | 改为优先使用 `batch-kafka:/opt/kafka/bin/kafka-consumer-groups.sh` |
| 跨模块真并行入口 | 已完成 | `CONTROL_PLANE_MODE=parallel` 使用 `ControlPlaneMixedPressureSimulation` |
| stale CREATED 恢复器 / T1-T2 终态化 | 已完成并复验 | 普通 job 分发 fail-fast 后转 `FAILED`,trigger_request 转 `REJECTED`;高压复验无 CREATED 残留 |
| cleanup-only 与 outbox 清理 | 已完成 | `CLEANUP_ONLY=1` 可只清理;补删 `event_delivery_log` 避免 outbox FK 阻塞 |
| 本地 restart 只杀监听进程 | 已完成 | `lsof` 加 `-sTCP:LISTEN`,避免重启 orchestrator 时误杀连接中的 worker |

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

## 追加并行复验

RUN_ID:`ctlw-20260607204120`

```bash
CONTROL_PLANE_MODE=parallel \
SKIP_AUTO_CLEANUP=1 \
MODULES_CSV=process,dispatch,atomic,trigger \
PROCESS_LAUNCH_RPS=1.0 \
DISPATCH_LAUNCH_RPS=1.0 \
ATOMIC_LAUNCH_RPS=1.0 \
TRIGGER_LAUNCH_RPS=1.0 \
TRIGGER_READ_RPS=1.0 \
TRIGGER_DURATION_SECONDS=20 \
WAIT_TERMINAL_TIMEOUT_SECONDS=240 \
WAIT_TERMINAL_MIN_INSTANCES=4 \
bash load-tests/scripts/run-control-plane-worker-benchmark.sh
```

Gatling 同一时间窗口内完成 5 个场景:process launch、dispatch launch、atomic launch、trigger launch、scheduler reads。HTTP 请求 120/120 成功,全局 P95 65ms,P99 207ms,错误率 0%。

| 模块 | Job | 实例数 | 成功 | 失败 | 非终态 | 平均端到端秒 | P95 端到端秒 |
|---|---|---:|---:|---:|---:|---:|---:|
| process | `lt_process_sql_job` | 20 | 14 | 0 | 6 | 0.653 | 1.269 |
| dispatch | `lt_dispatch_local_job` | 20 | 14 | 0 | 6 | 0.680 | 1.029 |
| atomic | `atomic_sql_demo` | 20 | 16 | 0 | 4 | 0.604 | 1.102 |
| trigger | `atomic_sql_demo` | 20 | 15 | 0 | 5 | 0.738 | 1.135 |

并行复验结论:

- 真并行已达成:4 类 launch + scheduler reads 在一个 Gatling run 内同时发压。
- Kafka lag 已采到:相关 consumer group before/after lag 均为 0。
- worker 消费不是瓶颈:worker 在线且 current_load 回落 0。
- 暴露缺口:21 个实例停留 `CREATED`,对应 trigger_request 仍为 `ACCEPTED`,且无 partition/task/outbox。该状态符合 launch T1 提交、T2 未完成的恢复缺口。
- 已补代码:新增 `StaleCreatedLaunchRecoveryScheduler`,通过 `batch.trigger.launch.created-recovery.*` 控制。当前运行进程未热加载,需重启/重载 orchestrator 后复验。

## 追加高压复验

RUN_ID:`ctlw-20260607231538`

```bash
SKIP_AUTO_CLEANUP=1 \
CONTROL_PLANE_MODE=parallel \
MODULES_CSV=dispatch,atomic,trigger \
DISPATCH_LAUNCH_RPS=3.0 \
ATOMIC_LAUNCH_RPS=3.0 \
TRIGGER_LAUNCH_RPS=3.0 \
TRIGGER_READ_RPS=3.0 \
TRIGGER_DURATION_SECONDS=60 \
WAIT_TERMINAL_TIMEOUT_SECONDS=900 \
WAIT_TERMINAL_MIN_INSTANCES=540 \
MAX_ERROR_PCT=20.0 \
bash load-tests/scripts/run-control-plane-worker-benchmark.sh
```

HTTP/Gatling 结果:

- `POST /api/triggers/launch` 与 scheduler read 合计 900/900 OK,0 KO。
- Gatling 全局 mean 17ms,P95 28ms,P99 121ms。
- 报告目录:`load-tests/target/gatling-results/controlplanemixedpressuresimulation-20260607151550000/index.html`。

实例最终观测:

| 模块 | Job | 实例数 | 成功 | 非终态 | Task 成功数 | P95 claim | P95 exec |
|---|---|---:|---:|---:|---:|---:|---:|
| dispatch | `lt_dispatch_local_job` | 180 | 147 | 33 | 147 | 1.236s | 0.082s |
| atomic direct | `atomic_sql_demo` | 180 | 140 | 40 | 140 | 1.266s | 0.026s |
| trigger -> atomic | `atomic_sql_demo` | 180 | 150 | 30 | 150 | 1.364s | 0.029s |

Worker 状态:

| Worker group | Worker | 状态 | 当前负载 | 最大并发 |
|---|---|---|---:|---:|
| ATOMIC | `atomic-node-1` | ONLINE | 0 | 10 |
| DISPATCH | `dispatch-node-1` | ONLINE | 0 | 10 |

关键判断:

- Kafka lag 为 0,worker 在线且 current_load 回落 0。
- 成功子集的 claim P95 约 1.2-1.4s,执行 P95 小于 100ms;worker 执行不是瓶颈。
- 非终态实例全部是 `CREATED + NO_TASK`,没有进入 worker。
- orchestrator 恢复日志反复出现 `StaleCreatedLaunchRecoveryScheduler ... error=error.partition.dispatch_business_error`。
- 本地容量策略为 `tenant_quota_policy.exceeded_strategy=REJECT`,且 `dispatch_queue` 只有 3 job / 6 partition。高压下 T2 分发 fail-fast 后,T1 已提交的 `job_instance=CREATED` 没有转成可观察终态;恢复器继续走同一调度路径,会反复撞 fail-fast。

结论:dispatch / atomic / trigger 的小基线和 HTTP 压测通过,但高压端到端不能标记完成。下一步应修 orchestrator launch T1/T2 在 `dispatch_business_error` 下的终态语义:要么实例立即 `FAILED/WAITING` 可观测,要么进入可重试队列,不能长期停在 `CREATED + NO_TASK`。

代码修复:

- `DefaultLaunchService` 在 T2 `error.partition.dispatch_business_error` 后把普通 `job_instance CREATED` CAS 标为 `FAILED`。
- 同时把对应 `trigger_request` 标为 `REJECTED`,避免继续停在 `ACCEPTED`。
- 单测:`mvn -pl batch-orchestrator -am -Dtest=DefaultLaunchServiceTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test` 通过。

## T1/T2 修复后高压复验

RUN_ID:`ctlw-202606080130-t1t2`

```bash
SKIP_AUTO_CLEANUP=1 \
CONTROL_PLANE_MODE=parallel \
MODULES_CSV=dispatch,atomic,trigger \
DISPATCH_LAUNCH_RPS=3.0 \
ATOMIC_LAUNCH_RPS=3.0 \
TRIGGER_LAUNCH_RPS=3.0 \
TRIGGER_READ_RPS=3.0 \
TRIGGER_DURATION_SECONDS=60 \
WAIT_TERMINAL_TIMEOUT_SECONDS=900 \
WAIT_TERMINAL_MIN_INSTANCES=540 \
MAX_ERROR_PCT=20.0 \
bash load-tests/scripts/run-control-plane-worker-benchmark.sh
```

HTTP/Gatling 结果:

- `POST /api/triggers/launch` 与 scheduler read 合计 900/900 OK,0 KO。
- 报告:`load-tests/target/control-plane-worker-report-ctlw-202606080130-t1t2.md`。
- Gatling HTML:`load-tests/target/gatling-results/controlplanemixedpressuresimulation-20260607173104566/index.html`。

实例最终观测:

| 模块 | Job | 实例数 | SUCCESS | FAILED | 非终态 | 结论 |
|---|---|---:|---:|---:|---:|---|
| dispatch | `lt_dispatch_local_job` | 180 | 123 | 57 | 0 | quota reject 已终态化为 FAILED/NO_TASK |
| atomic direct + trigger | `atomic_sql_demo` | 360 | 230 | 130 | 0 | 成功子集正常;拒绝子集不再滞留 CREATED |

结论:T1/T2 状态机 P0 已收口。高压下 FAILED 是本地容量策略 `tenant_quota_policy.exceeded_strategy=REJECT` 的可观察终态,不是 worker 长期停滞或 Kafka 积压。

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
| 1w/10w task storm | 能做 | T1/T2 终态语义已修;需要单独容量窗口 | 分 1k -> 1w -> 10w 阶梯跑,记录 quota 策略和 worker 扩容配置 |
| process 1000w staging/聚合 | 能做 | 需要准备更大业务输入和 PG 资源窗口 | 单独跑 process 大数据脚本,采 `pg_stat_statements`/WAL/IO |
| dispatch remote SFTP/NAS/EMAIL/OSS | 能做 | 依赖外部服务或本地 mock endpoint | 放到可选矩阵,不进安全默认压测 |
| atomic stored-proc/http/shell | 部分能做 | SQL executor 是默认安全路径;shell 默认关闭 | stored-proc/http 补本地 fixture 后跑;shell 只 opt-in |
| Kafka lag | 已做 | 已使用容器内 `/opt/kafka/bin/kafka-consumer-groups.sh` | 后续高压继续记录 before/after |
| 失败重试/背压/lease renew 压力 | 能做 | 需要故障注入,不能和成功基线混跑 | 单独建 failure profile |
| 跨模块真并行 | 已做 | 已新增单 simulation 并跑出 `ctlw-20260607204120`,`ctlw-20260607231538`,`ctlw-202606080130-t1t2` | 已确认全部终态;后续做更大 task storm |

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
| Gatling mixed parallel | `load-tests/target/gatling-results/controlplanemixedpressuresimulation-20260607124132651/index.html` |
| Gatling high pressure | `load-tests/target/gatling-results/controlplanemixedpressuresimulation-20260607151550000/index.html` |

## 当前判断

这轮没有暴露 worker 执行慢的问题;P95 claim 延迟和执行耗时都可接受。真正阻断高压端到端的是 orchestrator launch T1/T2 在资源调度 fail-fast 下留下 `CREATED + NO_TASK`;该 P0 已由 `ctlw-202606080130-t1t2` 复验收口。后续优化顺序转为 1w/10w task storm、故障注入、重试/背压和 worker 参数矩阵。
