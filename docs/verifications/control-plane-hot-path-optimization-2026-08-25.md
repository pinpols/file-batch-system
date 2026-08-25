# 控制面热路径优化核验（2026-08-25）

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

本轮没有把 worker batch-claim 收益记为通过：启用该开关但让 worker 与任务同时启动时，Kafka poll 实际每批只有一条消息，观测为 `256 claim-batch calls / 256 partitions`，未产生可证明的往返减少。这是测试拓扑未预积压消息，不是聚合失败。批量 claim 的后续验收必须先积压 task dispatch，再启动 batch listener，且保持该项为独立证据。

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
