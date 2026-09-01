# Process / Atomic 场景与性能验证报告

## 1. 范围与环境

验证日期：2026-09-01。

本轮继续复用现有 Docker 基础设施和本地 JVM 后端：

- PostgreSQL 17：`batch-postgres-primary` / `batch-postgres-replica`
- Kafka：`batch-kafka`
- Valkey、MockServer：已运行
- Process：`18086/actuator/health` 为 `UP`
- Atomic：`18087/actuator/health` 为 `UP`

未重启整套环境。测试脚本使用真实 Trigger API、Orchestrator、Kafka、worker 和 PostgreSQL 链路，运行数据按 run id 自动清理。

## 2. Process 场景验证

| 场景 | 预期 | 实际 | 结果 |
|---|---|---|---:|
| JSONB staging 成功 | `SUCCESS` | `SUCCESS` | PASS |
| DIRECT 快路径 | `SUCCESS` | `SUCCESS` | PASS |
| VALIDATE 失败 | `FAILED/PROCESS_VALIDATION_FAILED` | `FAILED/PROCESS_VALIDATION_FAILED` | PASS |
| 空结果 | `SUCCESS` | `SUCCESS` | PASS |
| 稳定 batch key 幂等重跑 | 结果不重复、金额正确 | 2 行、400.00、3 events、staging 0 | PASS |
| 4 分片处理 | 4 个分片成功、16 行目标数据 | 4/4、16 行、296.00、16 events | PASS |
| RUNNING 取消 | 进入可解释失败终态 | `FAILED/WORKER_EXECUTION_CANCELLED` | PASS |

执行入口：

- `scripts/sim/10-process-stage4.sh`
- `scripts/sim/13-process-stage4b.sh`
- `scripts/sim/19-process-stage4c.sh`

### Process 失败清理语义

VALIDATE 失败后观察到 1 行 staging 残留。该行为与当前设计一致：失败结果保留 staging 供取证，后续同 batch key 的 PREPARE 或 `ProcessStagingOrphanCleaner` 按保留策略清理；并非成功数据已发布后残留。幂等重跑验证已证明重跑前可清理并最终回到 staging 0。

## 3. Process 性能基线

执行入口：`load-tests/scripts/run-process-worker-benchmark.sh`。

运行标识：`perf-process-20260901a`。

- source rows：10,000
- account cardinality：1,000
- 场景：aggregate、copy、idempotency
- 每个场景用户数：1

| Job | 实例 | 成功 | 失败 | 非终态 | 平均端到端耗时 | p95 |
|---|---:|---:|---:|---:|---:|---:|
| `lt_process_sql_job` | 1 | 1 | 0 | 0 | 2.961 s | 2.961 s |
| `lt_process_copy_job` | 3 | 3 | 0 | 0 | 4.271 s | 5.567 s |

关键阶段观测：

- COPY 模式 COMMIT 平均 63.7 ms，p95 73.4 ms。
- COPY 模式 COMPUTE 平均 1.7 ms，p95 2.0 ms。
- SQL aggregate COMPUTE 35 ms，COMMIT 16 ms。
- 测试期间 PostgreSQL primary 约 100% CPU，Kafka 约 1.4% CPU；说明该规模的主要压力在数据库，不在 Kafka。
- `batch.process_staging` 在完成后为 0 行、0 bytes，未观察到持续残留。

原始报告：`load-tests/target/process-worker-report-perf-process-20260901a.md`。

说明：报告中的业务计数是自动清理后的最终快照，因此为 0；业务行数以运行过程中的 prepared fixture 和场景脚本断言为准，不能把清理后的快照当成吞吐为 0。

## 4. Atomic 场景验证

| 场景 | 预期 | 实际 | 结果 |
|---|---|---|---:|
| Shell 成功 | `SUCCESS` | `SUCCESS` | PASS |
| SQL 成功 | `SUCCESS` | `SUCCESS` | PASS |
| Stored procedure 成功 | `SUCCESS` | `SUCCESS` | PASS |
| HTTP 非 loopback 成功 | `SUCCESS` | HTTP 601、`SUCCESS` | PASS |
| SQL statement timeout | `FAILED/TIMEOUT` | `FAILED/TIMEOUT` | PASS |
| Shell cancel | 取消请求 200，任务失败终态 | `FAILED/WORKER_EXECUTION_CANCELLED` | PASS |

执行入口：

- `scripts/sim/16-atomic-stage5b.sh`
- `scripts/sim/21-atomic-stage5c.sh`

Shell cancel 的验收是控制面和任务状态正确收敛。`Future.cancel(true)` 依赖外部任务协作处理中断，不能保证任意不响应中断的 shell/插件进程立即退出；这属于现有语义边界，不作为本轮隐藏的成功条件。

## 5. Atomic 性能基线

执行入口：`load-tests/scripts/run-control-plane-worker-benchmark.sh`。

运行标识：`perf-atomic-20260901a`。

- 模式：parallel
- Atomic job：`atomic_sql_demo`
- 发送速率：2 req/s
- 持续时间：30 秒
- 总请求：60

| 指标 | 结果 |
|---|---:|
| HTTP launch | 60/60 OK |
| 失败率 | 0% |
| launch 平均响应 | 18 ms |
| launch p95 | 30 ms |
| 端到端实例 | 60/60 SUCCESS |
| 实例平均耗时 | 0.814 s |
| 实例 p95 | 1.340 s |
| 任务执行平均耗时 | 20 ms |
| 任务执行 p95 | 33 ms |
| claim delay p95 | 1.315 s |
| 非终态 | 0 |

原始报告：`load-tests/target/control-plane-worker-report-perf-atomic-20260901a.md`。

本轮 Atomic 结果表明，在当前本地配置和 2 req/s 负载下，入口、领取、执行、上报链路稳定；不能据此推导 shell、HTTP 外部服务或 stored procedure 的生产容量上限。

## 6. 运行态核对

测试结束后即时核对：

- Process、Atomic 相关任务无非终态。
- `job_instance` 非终态：0。
- `job_task` 非终态：0。
- `outbox_event` 待发布：0。
- `trigger_outbox_event` 待发布：0。
- 相关 Kafka consumer group lag：0。
- Process staging：无持续残留。

## 7. 结论与剩余边界

本轮 Process 和 Atomic 的主要业务分支、失败分类、幂等、分片、取消、控制面吞吐和终态收敛均已验证，没有发现新的生产代码 bug。

仍不能从本轮结果宣称完成的项目：

1. Process 1,000 万行以上、复杂 SQL 和高并发 PG 资源上限。
2. Atomic shell 不可中断进程的隔离、强制回收和 worker 自动摘除策略。
3. Atomic 真实外部 HTTP 供应商的延迟、限流、断连和长响应容量。
4. Process 与 Atomic 同时高压时的混合容量曲线。
5. 真实 staging 环境的 PG 主备切换、Kafka 故障和 RTO/RPO 证据。

这些属于生产容量/故障域验收，不影响本轮本地场景验证结论。
