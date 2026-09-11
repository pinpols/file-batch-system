# 控制面热路径优化核验（2026-08-25）

### 2026-09-11 压测口径收口

现有 `run-control-plane-worker-benchmark.sh` 与 `run-p2-capacity-profile.sh` 已统一使用同一套控制面画像入口。
10w 子画像会显式继承 `PG_SAMPLE_INTERVAL_SECONDS`，并在流量期间持续记录：数据库大小、活跃/等待连接、锁等待、
事务提交与回滚、WAL 字节数。报告同时保留流量前后快照和采样文件，便于区分入口、Relay、Worker 回报与终态回写阶段的压力。

本次只完善压测证据采集，不修改 Orchestrator 状态机或 `report-batch` 事务语义；新的 1w/10w 结果须使用该入口重新留档，
不得与旧报告的不同配额、并发和超时口径直接比较。代码静态验证已通过；本节不宣称已完成新的 10w 运行。

### 控制面容量计划落地矩阵

| 阶段 | 当前状态 | 证据 / 约束 |
|---|---|---|
| 统一 10w 压测入口 | ✅ 已落 | `run-p2-capacity-profile.sh` 复用 `run-control-plane-worker-benchmark.sh`，统一请求、终态和清理口径 |
| PG profiling | ✅ 采集能力已落 | 流量前后快照 + 期间 CSV；新结果必须保留样本文件 |
| Orchestrator 批量写入 | ✅ 已落 | `BatchInsertChunks` 已用于分区、任务和步骤实例；report-batch 仍逐项事务，避免未经证据改坏幂等语义 |
| 1w / 10w 对比复测 | 🟡 待按新口径重跑 | 旧报告可作历史基线，不能与新采样结果混表 |
| 多实例压测 | 🟡 待同构环境取证 | 本地 Docker 基础设施可以验证功能；生产容量需多 Orchestrator/Worker 实例和真实连接池预算 |
| 反压闭环 | ✅ 机制已落，需持续取证 | admission、QUEUE_DEFER、Kafka lag、outbox backlog 和终态收敛均有脚本/指标 |
| 数据归档与恢复演练 | ✅ 脚本与 runbook 已落 | 真实 PITR、独立故障域和 RTO/RPO 仍属于 staging/运维执行证据 |
| Console 运维闭环 | ✅ 后端能力已落 | diagnosis、retry/replay/cancel/resume 等受控入口已有；前端联调结果单独留档 |

### 2026-09-11 新口径本地容量复测

使用现有 Docker 基础设施和 benchmark Trigger 配置，未新增容器拓扑。1k 快速回归用于验证报告关联口径，
10w 使用 `STORM_TOTAL_REQUESTS=100000`、`STORM_RPS=200`、`PG_SAMPLE_INTERVAL_SECONDS=5`。

| 档位 | 入口结果 | 实例结果 | PG / outbox 观测 | 结论 |
|---|---|---|---|---|
| 1k 报告口径回归 | 1,000 OK，0 KO，p95 318ms | 1,000/1,000 `SUCCESS` | 锁等待 0；报告与终态一致 | 通过 |
| 1w | 10,000 OK，0 KO，p95 997ms | 10,000/10,000 `SUCCESS` | 锁等待 0；WAL 与数据库体积按流量增长 | 通过正确性和收敛 |
| 1w relay benchmark（400 events/s，100 RPS） | 10,000 OK，0 KO，p95 102ms | 10,000/10,000 请求已发布并最终收敛 | `trigger_outbox_event=PUBLISHED` 10,000；锁等待 0；Kafka lag 归零 | 通过；确认 benchmark relay 配置有效 |
| 10w relay benchmark（400 events/s，100 RPS） | 100,000 OK，0 KO，p95 51ms | 约 56k 实例在 2,400s 窗口内建立；仍有消费尾部 | `trigger_outbox_event=PUBLISHED` 100,000；锁等待 0；快照时约 81 个 RUNNING | relay 通过；控制面端到端容量仍不通过 |
| A/B clean 1w（24 consumers / pool 100，100 RPS） | 9,758 OK，242 次 429，入口 p95 3,750ms | 已接受的 9,758/9,758 `SUCCESS` | 无锁等待；Kafka lag 归零；未产生已接受任务丢失 | 消费扩容可保证已接受任务收敛，但 admission 仍需限流 |
| 10w | 99,964 OK，36 次 429；p95 46ms | 约 22k 在等待窗口内形成实例 | 锁等待 0；约 77k `trigger_outbox_event` 保持 `NEW`，relay 预算 40 events/s | **本机容量不通过** |

10w 的失败不是终态复活或数据库锁争用：已形成实例均继续收敛，瓶颈是 Trigger 异步 launch relay 的排空预算，
导致 HTTP `ACCEPTED` 与实例创建/完成脱钩。该结果不能作为生产容量承诺，但足以确定下一步优化方向：先把
admission 返回语义、outbox backlog/oldest-age 告警和 relay 扩展策略闭环，再复测多实例；不能靠客户端超时重试猜测实例是否已创建。
本轮所有测试 RUN_ID 已清理，Trigger 已恢复健康。

本轮新增的 relay benchmark 短测使用同一 Docker 基础设施，将隔离 benchmark 的 relay 预算提高到 400 events/s，
并将入口速率控制在 100 RPS 以避免 admission 保护与 relay 排空混在同一变量中。该短测通过，但它不是 10w
生产容量承诺；正式 10w 复测仍需单独确认入口速率、接受率、outbox oldest-age 和最终实例收敛四项指标。

正式 10w relay benchmark 已证明入口和 outbox 发布链路可承载 100 RPS，但未证明控制面可在窗口内完成
100k 实例创建：本次使用中的 `batch-orchestrator` 仍由另一个 `be-acceptance` 工作树管理，实际 consumer
并发为 6、平台库连接池为 50，benchmark 文件中新增的 24/100 覆盖尚未加载到该共享容器。因此下一轮应在
同一 compose 项目中受控重建 Trigger 和 Orchestrator 后，再比较 6/50 与 24/100；不得把本次局部发布成功
写成全链路 10w 通过。

中间的 A/B 复测曾因上一轮 Kafka 残留产生 `request_not_found`，该轮已标记为无效并清理；新增的压测前
consumer lag=0 预检用于阻断同类污染。当前清洁 A/B 结果只证明消费扩容后的“已接受请求”闭环，不代表
可以取消 admission 保护或直接承诺 10w 全链路容量。

随后在 lag=0 的干净环境中启动正式 10w A/B：入口发送阶段达到 `100,000/100,000`、`0 KO`，但在
控制面收敛阶段观察到 Orchestrator CPU 约 109%、内存约 839MiB/1GiB，PG 无锁等待；在约 22 分钟
取证点建立约 66.5k 实例，仍有约 13k `RUNNING`。该轮因已能定性控制面 JVM 处理吞吐瓶颈而中止并清理，
不计为通过。下一步应针对 launch 事件处理和状态写入做批处理/削峰 profiling，再复测；不能继续单纯
增加 Kafka consumer 并发。

## 本轮结论

本轮针对实例聚合、任务领取、结果写入和历史表生命周期完成代码核验，并落地一个低风险优化：

- 普通非 DAG 实例的 task report 改为数据库单行状态聚合，避免把实例全部分区状态加载到 JVM；
- DAG 实例继续使用 `(partition_id, partition_status)` 轻量投影，因为节点推进需要分区与节点映射；
- 备用 READY task 查询补充 `FOR UPDATE SKIP LOCKED`，防止未来启用数据库拉取路径时多实例重复拿到同一批任务；
- 新增 `idx_job_partition_instance_status`，支持实例状态聚合定位。

## 保留的设计边界

当前生产主链路是 Kafka 携带明确 taskId，Worker 通过 `READY + version CAS` 认领；不新增全表扫描式任务分发器。

结果 `report-batch` 仍保持逐项独立事务。该取舍保护 DAG、补偿、重试和幂等语义；是否继续做 set-based 终态写入，必须先有压力数据证明。

全量分区扫描保留为 DAG 推进和一致性校验用途，不作为普通实例热路径的默认实现。

## 后续验证门槛

使用相同 Docker 基础环境分别压测 1k、1w、10w task storm，并记录：

1. `job_partition` 聚合 SQL 的 p95/p99 与 `EXPLAIN (ANALYZE, BUFFERS)`；
2. PostgreSQL CPU、IO、WAL 增量、锁等待和连接池占用；
3. task report 延迟、Kafka lag、outbox backlog、终态收敛时间；
4. DAG 与非 DAG 混压时的成功率、重复回报率和状态一致性；
5. archive lag、dead tuple、autovacuum 延迟和未来分区维护失败告警。

只有在 report 写放大仍是瓶颈时，才评估简单非 DAG 作业的 set-based 终态更新；复杂 DAG、补偿和重试继续沿用逐项事务。

## 本地 1k 控制面复验

### 环境与口径

- 时间：2026-08-25；Docker 仅提供 PostgreSQL、Kafka、MinIO、Valkey 基础设施，应用均由本地 JVM 启动。
- 工作负载：`atomic_sql_demo`，20 秒内以 50 launch/s 发起 1,000 个实例；每实例一个 ATOMIC task，SQL 为轻量 `SELECT 1`。
- 验收：`success=1000`、`failed=0`、`non_terminal=0`，并在结束后恢复本地默认租户 quota 和清理带 `RUN_ID` 的测试数据。
- 为避免 quota 策略干扰吞吐测量，运行期间临时把默认租户上限设为 2,000 job / 2,000 partition / 1,000 QPS，策略设为 `QUEUE_DEFER`；运行后已恢复为 `8 / 16 / 80 / REJECT`。这不是生产推荐配额。

首次直接用本地默认 quota 运行时，1,000 个 launch HTTP 请求全部成功，但只有 6 个 task 被执行，其余 994 个按 `REJECT` 策略异步终止。这是 fail-close 配额语义，不是吞吐基线，已清理且不参与下表对比。

### 结果

本地 Kafka dispatch topic 为 3 分区，而 atomic worker 的默认 listener concurrency 原为 2。由于消费处理同步覆盖 task 执行、认领和回报链路，第三个可消费分区未被默认配置使用。将默认值对齐为 3 后，在相同工作负载和临时 quota 下复验：

| 指标 | 默认 2 listener | 对齐 3 listener | 变化 |
|---|---:|---:|---:|
| 实例成功 / 失败 / 非终态 | 1000 / 0 / 0 | 1000 / 0 / 0 | 语义不变 |
| 实例平均终态时间 | 72.591 s | 41.787 s | -42.4% |
| 实例 p95 终态时间 | 112.610 s | 86.938 s | -22.8% |
| task 平均 claim delay | 72.565 s | 41.755 s | -42.4% |
| task p95 claim delay | 112.594 s | 86.920 s | -22.8% |
| task 平均执行时间 | 0.015 s | 0.020 s | 仍非瓶颈 |
| launch HTTP p95 | 2.070 s | 1.739 s | -16.0%，仍未满足 500 ms 本地门槛 |

原始报告：

- `load-tests/target/control-plane-worker-report-hotpath-20260825-1k-capacity.md`
- `load-tests/target/control-plane-worker-report-hotpath-20260825-1k-capacity-c3.md`

### 结论与边界

本轮确认了一个低风险默认值漂移：默认 listener 并发应与本地标准 dispatch topic 的 3 分区对齐。生产仍通过 `BATCH_WORKER_ATOMIC_KAFKA_CONCURRENCY` 按真实 topic 分区数、worker 实例数和平台库连接预算覆盖，不能把本地值机械复制到生产。

该结果不能作为生产容量承诺：运行在开发机、任务只执行 `SELECT 1`，也没有覆盖多实例、慢外部依赖、DAG/补偿或大文件负载。Kafka lag 在本轮 `BATCH_SCRIPT_RUNTIME=host` 下未能由本机 CLI 采集；该运行模式刻意不回退到容器 CLI，因此不能据此声明 lag 已验证。

### 256 分片非 DAG 聚合复验

为直接覆盖 PR #970 的非 DAG 聚合路径，使用 `TA_PROCESS_STAGE4_SHARDED` 在单实例内展开 256 个静态分区。测试前临时写入 1,024 条隔离的 `HFA*` 源记录，使每个分区恰好得到 4 条记录，符合该 PROCESS fixture 的业务校验；测试结束后源表和目标表中的这批记录均已删除。

| 项目 | 结果 |
|---|---|
| 实例 / job | `84622` / `TA_PROCESS_STAGE4_SHARDED` |
| 分区终态 | 256 `SUCCESS`，0 `FAILED` |
| 实例终态 | `SUCCESS` |
| 执行时间 | 11.378 s |
| 聚合查询 | `idx_job_partition_instance_status` Index Only Scan，256 行，0.419 ms，129 shared-buffer hits |

验证使用本地 JVM 应用和 Docker 基础设施。`scripts/sim/27-batch-claim-consume.sh` 现会在判定前严格核对实际分区数，避免 `partitionCount` 被所选作业策略忽略时把单分区结果误报为高 fan-out 成功；`REQUIRE_BATCH_CLAIM=false` 可将它用于纯分区/实例聚合验证。

首次启用该开关时让 worker 与任务同时启动，Kafka poll 实际每批只有一条消息，观测为
`256 claim-batch calls / 256 partitions`，因此当时没有把 batch-claim 收益记为通过。这是测试拓扑未预积压
消息，不是聚合失败。

### 256 分片预积压 batch-claim 复验（2026-08-26）

本次按正确拓扑重做：先仅启动本地 JVM control plane，向 `TA_PROCESS_STAGE4_SHARDED` 写入 1,024 条隔离
`HFE*` 源记录并触发 256 个 partition；确认全部 task 已是 `READY` 后，才启动 PROCESS worker。Docker 仍只提供
PostgreSQL、Kafka、MinIO 和 Valkey。worker 使用以下容量矩阵：

```text
listener.concurrency = 4
max.poll.records = 8
max-concurrent-tasks = 32
execution.pool-size = 32
```

该矩阵满足 batch listener 的启动约束
`max-concurrent-tasks >= listener.concurrency * max.poll.records`，也满足执行池不小于任务许可数的既有约束。

| 项目 | 结果 |
|---|---|
| 实例 / job | `86103` / `TA_PROCESS_STAGE4_SHARDED` |
| 分区终态 | 256 `SUCCESS`，0 `FAILED`，0 非终态 |
| 实例终态 | `SUCCESS` |
| 目标数据 | `biz.process_stage4_target` 写入 1,024 条 `HFE*` 记录 |
| claim 指标 | 34 次 claim-batch，258 个 claim item，有效 K=7.59 |

本地 Kafka topic 保留了前面中断验证的 2 条可重放消息，所以指标为 258 而非严格 256；它们均被正常 claim，
没有影响本实例的 256 个分区终态。关键证据是 `34 < 256` 且实例全终态，已证明预积压时实际走批量 claim，
不再把单条 poll 误当作 batch-claim 收益。该结果仍只是开发机本地证据，不构成生产吞吐或容量承诺。

256 是当前静态分片的安全上限；1k/1w fan-out 必须以 bundle 或受控的动态分片场景单独验证，并采集 report 延迟、WAL、锁等待和 lag。在该证据出现前，不改变逐项 report 事务或引入异步执行旁路。

### Trigger 入口 p95 拆分（2026-08-26）

为区分 trigger 同步写入成本与完整执行链路造成的共享平台库争用，本地只启动
`orchestrator`、`trigger`、`console` 三个 JVM，不启动 worker；Docker 仍只提供 PostgreSQL、Kafka、
MinIO、Valkey。每档 15 秒，以 `atomic_sql_demo` 发起唯一幂等键的 API launch，读取负载为 0；每档结束
均按独立 `RUN_ID` 清理 `job_instance`、outbox 与 trigger 记录。

| Launch rate | 请求数 | p95 | p99 | 失败 |
|---|---:|---:|---:|---:|
| 5 RPS | 75 | 49 ms | 81 ms | 0 |
| 10 RPS | 150 | 42 ms | 142 ms | 0 |
| 25 RPS | 375 | 359 ms | 889 ms | 0 |
| 50 RPS | 750 | 21 ms | 211 ms | 0 |

25 RPS 有短暂尾部尖峰，但 p95 仍在 500 ms 本地写入门槛内；50 RPS 复测未复现，不能把单次尖峰归因为
确定的代码缺陷。与前述“worker 参与、1,000 实例完整终态”时的 1.739 s launch HTTP p95 对照，可确认
入口事务本身不是当前改造目标。高压完整链路的入口尾延迟来自 worker claim/report、实例推进和 outbox 同时
写入平台库后的资源竞争；后续应在 1w/10w task storm 中采集 PG 锁等待、连接池占用、WAL 与 outbox backlog，
再决定是否优化写模型。

为支持纯入口画像，`SchedulingBacklogUnderLoadSimulation` 现在仅在
`scheduling.read.rps > 0` 时登记 scheduler-read p99 断言。此前 `0` 会对不存在的请求详情断言并让
Gatling 误报失败；常规读写混压的读 p99 门槛未改变。

### 当前代码 1k Atomic 完整链路复验（2026-08-26）

在合入非 DAG 聚合优化、Atomic listener 并发对齐和 batch-claim 容量启动校验后，使用既有
`run-control-plane-worker-benchmark.sh` 重跑完整链路。Docker 只提供 PostgreSQL、Kafka、MinIO、
Valkey；orchestrator、trigger、console 和五类 worker 均由本地 JVM 启动。每档均以
`atomic_sql_demo` 发起 1,000 个唯一幂等键请求；压测期间仅临时提高本地默认租户配额，结束后恢复
`8 job / 16 partition / 80 QPS / REJECT`，脚本自动清理本轮实例、任务、outbox 和业务 seed。

| 档位 | 入口请求 | 终态 | 入口 p95 | 实例终态 p95 | task claim p95 | task 执行 p95 | Kafka lag |
|---|---:|---|---:|---:|---:|---:|---|
| 25 RPS，40 秒 | 1,000 | 1,000 `SUCCESS`，0 failed，0 non-terminal | 31 ms | 2.871 s | 2.847 s | 34 ms | 前后均为 0 |
| 50 RPS，20 秒 | 1,000 | 1,000 `SUCCESS`，0 failed，0 non-terminal | 2.062 s | 5.291 s | 5.235 s | 74 ms | 前后均为 0 |

原始报告：

- `load-tests/target/control-plane-worker-report-hotpath-20260826080210-1k-25rps.md`
- `load-tests/target/control-plane-worker-report-hotpath-20260826080001-1k.md`

结论：25 RPS 在当前本机完整链路下满足 500 ms 入口 p95 门槛，且所有任务和实例均收敛；50 RPS
仍保持正确性、终态收敛和 lag 清零，但入口 p95 超过该门槛。因此 50 RPS 只能作为本机的正确性与
积压恢复证据，不应表述为低延迟容量承诺。该结果也不能直接外推到生产：负载为轻量 SQL，未包含
多实例、慢外部依赖、复杂 DAG、补偿或生产数据库规格。后续若要提升 50 RPS 的入口尾延迟，应先采集
PostgreSQL 锁等待、连接池占用、WAL、outbox backlog 和 JVM CPU/GC，再决定是否改动写模型。

### 当前代码 1w/10w Atomic Storm（2026-08-26）

继续使用本地 JVM 应用和既有 Docker PostgreSQL、Kafka、MinIO、Valkey 基础环境，工作负载为
`atomic_sql_demo` 的轻量 `SELECT 1`。为使画像测量执行链路而不是默认 fail-close quota，运行期间临时把
`default-tenant` 配额提升为 `10,000 job / 10,000 partition / 1,000 QPS / QUEUE_DEFER`；每轮结束均恢复
`8 / 16 / 80 / REJECT`，并验证该轮 `job_instance` 残留为 0。此临时配额不是生产推荐值。

| 档位 | Gatling 请求结果 | 服务端终态 | 实例 p95 | task claim p95 | 结论 |
|---|---|---|---:|---:|---|
| 1w，200 RPS | 10,000 / 10,000 HTTP OK | 10,000 `SUCCESS`，0 failed，0 non-terminal | 0.527 s | 0.483 s | 当前本机完整执行链路正确收敛 |
| 10w，200 RPS | 99,986 请求；64,699 OK、35,287 KO（35.29%）；p95 60.002 s | 85,848 `SUCCESS`，0 failed，0 non-terminal | 0.658 s | 0.601 s | 入口达到本机容量边界，不是执行链路终态错误 |

10w 运行中 Gatling 的主要失败为 60 秒请求/连接超时；但其中一部分请求已在服务端继续创建并执行。因此，
`HTTP OK`、`请求是否被服务端接受` 和 `实例是否最终成功` 在超时窗口内不是同一指标，不能把 85,848 个成功
实例误记为 85,848 个可靠的客户端成功。这是本轮最重要的产品结论：入口必须在客户端超时前给出明确的
admission 结果，或让调用方通过幂等键查询确定的 request/instance 状态；不能依赖超时后重试来猜测是否已创建。

PostgreSQL 5 秒采样峰值为 19 个活跃连接、3 个锁等待会话和 3 个未授予锁，未出现锁等待发散。运行期间
平台库体积从约 1.19 GB 升至约 2.22 GB；这是 10w 临时运行记录和后续删除产生的本地写放大证据，不能用
物理文件大小替代 vacuum、归档和长期分区策略的结论。清理后的 85,848 行大删除已完成，运行 id 残留为 0。
生产同构环境仍须补充 WAL、CPU/IO、autovacuum、archive lag 和多 orchestrator 实例的留档。

本次同时修复两个压测工具问题：先清理 Trigger/outbox 持久来源，再清理异步在途实例；P2 profile 按
`<run-id>-10w` 清理并校验 storm 子 run，而不是错误使用父 run id。`run-control-plane-worker-benchmark.sh`
也不再吞掉 Gatling 的退出码，因此 `CAPACITY_STRICT=1` 现在会在报告生成和终态等待完成后正确返回失败。
默认 `CAPACITY_STRICT=0` 仍只记录容量画像，不把预期的低延迟 SLO 超限伪装成通过。

### P2 多租户公平性夹具复核（2026-08-26）

公平性 profile 使用隔离的 `p2fa/p2fb/p2fc` 临时租户，避免历史 `ta/tb/tc` 的运行实例污染共享组计数。
策略权重固定为 `3:1:1`，请求供给默认采用 `1:1:1` 并轮转提交。直接写入临时作业定义和 quota 策略后，脚本会通过已有
Console Ops API 同时失效 job-definition 与 quota cache；结束时删除实例、Trigger 请求、策略、作业定义和临时租户。
同一工作区的 profile 通过本地锁互斥，避免一轮 cleanup 删除另一轮的临时 fixture。

首轮 600 条预验暴露了真实缺陷：`group_shared_max_running_jobs` 仅做非原子 `count -> compare`，首个调度窗口完成
95 条，穿透了配置的 12 条上限。修复后，共享组计数前取得 PostgreSQL 事务级 advisory lock，并将锁保持到
`job_instance` 状态提交；WAITING 出队在其 `REQUIRES_NEW` 写事务内再次校验，不能再依赖事务外候选排序的旧判断。

| 场景 | 结果 |
|---|---|
| 硬上限验证：120 条、组上限 12 | 120 `SUCCESS`，0 non-terminal；0.2 秒采样峰值 `READY + RUNNING = 12`；三租户各 40 条；临时数据清理为 0 |
| 公平画像：600 条、组上限 96 | 600 `SUCCESS`，0 non-terminal；三租户各 200 条；采样峰值 87（未超过 96）；临时数据清理为 0 |

低上限硬约束与高并发画像现在是两个独立参数：`FAIRNESS_GROUP_SHARED_MAX_RUNNING_JOBS=12` 配合小规模请求验证原子
admission；默认 `96` 与 6,000 请求 / 1,200 秒的画像预算匹配。上述结果是本地 JVM + Docker 基础设施的正确性与
基础容量证据，不构成生产公平份额、吞吐或延迟承诺；6,000 条和多实例 worker 的生产级容量结论仍需在同构 staging 留档。

本轮还补了 `job_instance(trigger_request_id)` 与 `job_step_instance(job_partition_id)` 索引迁移。前者避免
删除 Trigger 请求时重复扫描运行实例表；后者覆盖分区删除时的 step 外键检查。它们同时使 Trigger 模式的
压测清理能够按 `trigger_request` 关联实例，而不再只依赖 `params_snapshot` 中的 `runId`。

### 2026-08-27 Process report-batch / PG 资源画像

使用同一套 Docker 基础设施和本地 JVM，运行 `aggregate`、`copy`、`idempotency` 三个 Process 场景，
每个场景 10 个并发用户、5,000 条源数据。原始报告：
`load-tests/target/process-worker-report-pgprofile-20260827081115.md`。

| 指标 | 结果 |
|---|---:|
| 实例终态 | 22 个终态：14 个 `SUCCESS`、8 个 `FAILED`，0 个非终态 |
| COPY `COMMIT` p95 | 72.2 ms |
| SQL `COMMIT` p95 | 53.5 ms |
| PG rollback 增量 | 0 |
| `process_event_copy` dead tuple | 5,000（幂等 upsert/清理前观测值） |
| PostgreSQL CPU（采样前/后） | 12.68% / 1.29% |
| 业务库 WAL records 增量 | 176,381 |

本轮未证明需要把 `report-batch` 改成 set-based 写入：任务终态和幂等结果均正确，当前证据不足以抵消
逐项事务对 DAG、补偿和重试语义的保护价值。`process_event_copy` 的 dead tuple 说明高频幂等写入需要
纳入后续 vacuum/膨胀观察，但不是本轮的状态机缺陷。

### 2026-08-27 分区与生命周期只读核查

- `batch.job_instance`：RANGE(`biz_date`)，37 个子分区，含 1 个 default 分区。
- `batch.outbox_event`：RANGE(`created_at`)，37 个子分区，含 1 个 default 分区。
- `archive.job_instance_archive`、`archive.outbox_event_archive`：当前无遗留行。
- `batch.process_staging`：当前 0 行；核查时无运行中的 vacuum 作业。
- 分区裁剪执行计划：按当天 `biz_date` 查询 `job_instance` 移除 36 个子计划，执行约 0.05 ms；按本月起
  查询 `outbox_event` 移除 25 个子计划，执行约 2.39 ms。当前分区裁剪生效，查询计划没有退化为全量子分区扫描。

这证明当前本地结构和清理后的即时状态正常，不等于长期生产生命周期已验收。仍需 staging 长周期演练，
记录 archive lag、dead tuple、autovacuum 延迟、磁盘增长和未来分区创建失败告警；完成前不把历史表
膨胀风险标记为关闭。

### 2026-09-11 控制面消费热路径微优化

10w 清洁压测在提高 Trigger relay 配额、Orchestrator 消费并发和连接池后，入口接收与消息发布均可完成，
但本地单实例 Orchestrator CPU 已接近满载，实例形成和终态收敛仍无法在当前预算内完成；PG 未出现锁等待发散，
因此下一步重点是 launch 状态写入/削峰，而不是继续盲目增加 Kafka consumer 并发。

本轮在不改变业务状态机、事务边界、ack、重试和租户标签规则的前提下，给
`TriggerLaunchConsumer` 的动态标签 Counter 增加了有界 Caffeine 缓存（最多 2,048 个组合，1 小时未访问过期）。
原实现每条消息都调用 `MeterRegistry` 注册查找，改后只在新标签组合首次出现时注册；缓存驱逐后仍会从
`MeterRegistry` 取回同名 Counter，不会改变指标累计值。测试辅助方法误留的 `@Test` 也已移除，避免 JUnit
discovery warning 掩盖真实测试结果。

验证：`TriggerLaunchConsumerTest` 6/6 通过；orchestrator 模块 `spotless:check` 通过；`git diff --check` 通过。
该改动尚未证明可以单独消除 10w 的控制面 CPU 瓶颈，后续应在同一清洁口径下复测并比较 CPU、GC、launch
处理速率和终态收敛时间，再决定是否进入批量状态写入改造。

同日 10w 复测结果（Counter 缓存版本）：入口共 100,000 次，99,575 次 HTTP 成功、425 次 `429`
admission 拒绝；服务端形成 99,574 个实例，99,574 个全部 `SUCCESS`，0 个 `FAILED`、0 个非终态。
最终实例平均耗时 384.395 秒，p95 627.832 秒；Kafka launch lag 最终为 0。Orchestrator 内存约
790--806MiB，CPU 约 70%--150% 波动。

因此本轮证明了已形成实例的正确性，但没有证明 10w 在当前单实例窗口内具备目标容量；Counter 缓存也未
带来可观测的端到端吞吐改善。严格脚本因为 425 次受控 429 和 1 条尚未关联实例的 `ACCEPTED` 请求返回
非零，不能标记为容量通过。日志已确认该请求的 trigger outbox 成功进入 Kafka，但 Orchestrator consumer
连续遇到 429，重试耗尽后被通用错误处理器按 recovered 消息提交 offset，最终留下 `ACCEPTED`。该 1 条
请求已随压测现场清理；修复顺序应先保证 admission 429 不会被重试耗尽策略静默跳过，再优化 Orchestrator
状态写入削峰/批量化，而不是继续增加 consumer 并发。

### 2026-09-11 热路径与 worker 容量契约收口

本轮按“先消除无效读写，再增加并发，最后用同口径压力验证”的顺序实施，未改变状态机终态、事务边界、
Outbox 一致性、租户隔离或 Kafka ack 语义：

1. Trigger launch consumer 遇到 admission `429` 时执行带退避的 nack，不再被有限重试器提交 offset；受信 Kafka
   入口只绕过面向 HTTP 调用方的防滥用限流，业务 quota、资源队列和 worker 容量判定仍保留。
2. launch 高频配置读取增加 250ms 有界进程内缓存；无租户/队列配额时跳过无意义的 active-count 查询。
3. 普通可派发 plan 在同一 T2 事务内直接插入 `READY` 分区和任务，移除刚插入后再做
   `CREATED -> READY` 的冗余 CAS；DAG、重试、WAITING 释放仍走既有状态机 CAS。
4. 去掉 mark-running 前的实例整行重读，直接使用当前事务已知 version 做 CAS；增加 launch phase 指标，分别
   观测 validation、prepare、plan、resource schedule、materialize 和 instance transition。
5. benchmark profile 使用两个 Orchestrator 实例，各 6 个 launch consumer、平台库池 50；Atomic topic 12
   分区、listener 12、执行许可/线程池 16。压测脚本会在请求总数不能被 RPS 整除、旧 launch lag 未清零、
   容器预算不一致时前置失败，避免生成虚假的“精确 1w/10w”报告。

首次把 Atomic listener 提高到 12 后，约 7 分钟只有 6,479 个实例终态，另有约 3,521 个实例停在
`WAITING`。根因不是 Kafka lag 或 worker 执行慢，而是容器实际许可为 16，`worker_registry.max_concurrent`
仍沿用数据库默认值 10；selector 看到的容量与 worker 实际容量不一致。修复后，五类内置 worker 在注册时
上报本地 `max-concurrent-tasks`，平台仅在 register/re-register 时校准该值；普通 heartbeat 不覆盖平台后续
可能下发的动态限额。旧 SDK 不带该可选字段时仍保留已有值或数据库默认值，wire 向后兼容。

运行态复核确认 `atomic-node-1` 登记为 `current_load=0 / max_concurrent=16`。同一 Docker 基础设施下的结果如下：

| 档位 | 入口 | 实例终态 | 实例平均 / p95 | task claim p95 | task exec p95 | 结论 |
|---|---|---|---:|---:|---:|---|
| 1k，100 RPS | 1,000 OK，0 KO，HTTP p95 1,269ms | 1,000 `SUCCESS`，0 非终态 | 6.919s / 9.900s | 9.643s | 241ms | 容量契约与小规模收敛通过 |
| 1w，100 RPS | 10,000 OK，0 KO，HTTP p95 928ms | 10,000 `SUCCESS`，0 非终态 | 48.876s / 66.477s | 66.396s | 152ms | 严格画像通过；Kafka lag、锁等待和测试残留均为 0 |

与本轮同日、修复容量契约前的干净 1w 结果相比，实例平均耗时从 56.049s 降到 48.876s（约 12.8%），
p95 从 70.686s 降到 66.477s（约 6.0%），task claim p95 从 70.638s 降到 66.396s。代价是 HTTP p95
从 162ms 上升到 928ms，launch phase 加权平均也从 validation/prepare/dispatch 的
6.65/23.66/42.06ms 上升到约 11.98/38.02/72.11ms；Atomic 12 路消费与两个 Orchestrator 同时写平台库，
会增加本机 CPU、连接池和 WAL 竞争。因此该配置提高了最终排空能力，但不是低延迟入口的无条件推荐值。

当前下一步不应继续盲目增加 listener 或数据库连接池。生产同构环境应先分别测 8/10/12 路 Atomic
consumer 的矩阵，固定 100 RPS 和相同 PG 规格，比较 HTTP p95、终态 p95、WAL、CPU/IO 与 backlog
排空时间；以“满足窗口的最低并发”为生产推荐值。若 100 RPS 入口 p95 仍要求低于 500ms，应隔离
worker 业务连接池与控制面平台库资源，或降低单节点 worker 并发并横向扩 worker，而不是削弱 quota、
状态 CAS 或 Outbox 一致性。
