# 控制面 10 万任务吞吐优化与验证报告（2026-09-12）

## 结论

在同一套本地 Docker 基础环境、2 个 Orchestrator、1 个 Atomic Worker 和真实 PostgreSQL/Kafka
链路上，严格 10 万任务画像最终通过：

| 指标 | 最终结果 |
|---|---:|
| Trigger 请求 | 100000/100000 成功，0 失败 |
| 入口速率 | 200 requests/s |
| HTTP p95 / p99 / max | 67ms / 162ms / 1971ms |
| Job 终态 | 100000 SUCCESS，0 FAILED，0 非终态 |
| 完成窗口 | 741.036s |
| 端到端完成吞吐 | 134.946 tasks/s |
| 端到端平均 / p95 | 211.919s / 289.528s |
| Kafka launch 分区 | 12/12 有流量，最终 lag=0 |
| Atomic dispatch 分区 | 24 个消费者分区最终 lag=0 |
| Kafka 重启 | 0 次 |
| 压测数据残留 | result_version/job_instance/trigger_request 均为 0 |

这证明当前单机环境可以稳定接收 200 requests/s 的 10 万任务洪峰，并以约 135 tasks/s 排空。
入口速率高于完成速率时会形成可控 backlog，因此 200/s 不能表述为持续完成吞吐或生产容量承诺。

在上述 10 万稳定基线之后，又完成了 Worker 双 Orchestrator 端点和结果版本终态写入热路径优化。
同键 1 万任务热态复测达到 `136.517 tasks/s`，10000/10000 成功、零失败、零非终态；该轮用于验证
热点优化效果，不替代上表的 10 万容量验收。

原始报告位于本机：

- `load-tests/target/p2-capacity-profile-throughput-100k-200rps-final-20260912.md`
- `load-tests/target/control-plane-worker-report-throughput-100k-200rps-final-20260912-10w.md`

## 环境与口径

- Docker Desktop：8 CPU、约 7.75 GiB 内存。
- 基础设施：PostgreSQL 17、Kafka 4.1.2、Valkey 8.1、MinIO。
- 应用：Trigger 1 实例、Orchestrator 2 实例、Atomic Worker 1 实例。
- 业务负载：`atomic_sql_demo`，每个请求独立创建 trigger request、job instance、partition 和 task，
  通过真实 Kafka 派发、Worker claim/execute/report 和 PostgreSQL 终态推进。
- 严格判据：请求零错误、请求数与实例数精确相等、全部实例终态、无失败、Kafka 无重启且最终
  lag 为 0、12 个 launch 分区实际有流量、清理后无 run-scoped 残留。

本报告中的 `effective_completed_per_second` 使用第一个 trigger request 创建时间到最后一个实例完成时间，
不是只统计已经进入 Orchestrator 的实例耗时。旧报告没有该口径时，只能用整个运行窗口做趋势对比。

## 已落地优化

### 1. 消除串行分发瓶颈

- Trigger launch topic 扩为 12 分区，2 个 Orchestrator 各 6 个消费者。
- 压测脚本校验 consumer group 实际持有 12 个分区，并按前后 offset 证明 12/12 分区有流量。
- 解决 Kafka 在线扩分区后 producer/consumer 元数据仍停留在旧 3 分区的问题：容量复验要求生产者和
  全部消费者完成重载，脚本拒绝用 3 分区结果冒充 12 分区结果。

最终 Launch 分工为 50257/49743，接近 50/50；各分区消息数为 8224-8501，最大差异约 3.3%。

### 2. 批量 claim 与 Worker 并发

- Atomic Worker 使用 24 个消费者、24 个 dispatch 分区、`max.poll.records=5`。
- 批量 claim 共 20090 次取得 100000 个任务，有效批大小 4.98。
- 相比逐任务 claim，HTTP claim 调用量减少约 79.9%。
- benchmark overlay 将任务许可、注册容量和执行池统一为 480，等待任务扫描改为每次 1000 条、1 秒间隔。

上述值只属于容量画像，不修改普通本地或生产默认容量。生产值必须按数据库连接预算、Worker 类型和
资源隔离重新标定。

### 3. Trigger 入口预算

- benchmark profile 使用 API admission 80、平台库连接池 88、后台保留 8、Relay 400/s。
- 240 并发轮次出现 186 次队列拒绝，64 并发轮次出现 18 次队列拒绝，均被判无效并舍弃。
- 80/88 是本机 200 RPS 下第一个满足零请求错误的预算，不应直接复制为生产值。

### 4. 热路径日志降噪

以下逐任务成功日志从 INFO 降为 DEBUG，失败和拒绝仍保留 WARN/ERROR：

- Worker 单任务/批任务成功结果。
- 本地共享租户的 worker selection/claim fallback 成功结果。

这避免 10 万任务产生约 20 万条低价值 INFO 日志。定向单测覆盖 Worker consumer、批执行协调器、
任务认领和 Worker 选择逻辑：Orchestrator 33 项、Worker 30 项，共 63 项测试通过。

### 5. 压测数据生命周期

原清理脚本只删除 job instance，没有删除无外键回收的 `result_version`。连续压测后本地已累积
800835 条 `p2capacity` 孤儿版本，持续放大唯一索引维护成本并污染轮次对比。

现已按 run-scoped job instance 先删除 `asset_partition` 指针，再删除 `result_version`。使用 100 个
任务做了造数和自动清理回归，随后 10 万任务清理实际删除 100000 条结果版本；最终三个关键表均为 0。

### 6. 容量画像超时隔离

演示作业原有 300 秒硬超时会把容量 backlog 误判为业务执行超时。首轮 10 万在 300 秒后出现 2 个
`JobInstanceTimeoutEnforcer` 失败，已判无效，没有进入最终结论。

容量画像现在为隔离租户显式写入 `CAPACITY_JOB_TIMEOUT_SECONDS`，默认 3600 秒，并要求该值不小于
“流量持续时间 + 终态等待预算”。生产和普通演示作业的超时语义、默认值均未改变。

### 7. PostgreSQL 压力观测修正

原 `waiting_connections` 把 idle session 的 `ClientRead` 也计入等待。现改为仅统计
`state='active' AND wait_event IS NOT NULL` 的活动等待会话，`lock_waiters` 继续独立统计，避免把连接池
常驻空闲连接误判成数据库锁压力。

### 8. Worker 任务控制端点高可用

- Worker 支持配置多个 Orchestrator task-control base URL，按请求轮转；请求失败后的既有重试会自然切换
  到下一端点，单 URL 配置继续兼容。
- 本地双实例 1 万任务复测中，claim items 为 5056/4944，report calls 为 4984/5016，证明任务控制流量
  已实际分布到两台 Orchestrator，而不只是 Trigger launch 消费均衡。
- 该能力用于 VM、裸机和本地直连场景的高可用与分流。Kubernetes 仍优先配置一个 Service URL，避免在
  应用层重复实现服务发现。

### 9. 结果版本终态写入热路径

压测新增 `BIZ_DATE_CARDINALITY`：默认值 1 保留同一 `jobCode + bizDate` 的并发重跑热点；大于 1 时
轮换 bizDate，用于区分单一结果版本链锁竞争和通用控制面负载。使用 100 个 bizDate 的 1 万轮次为
`111.626 tasks/s`，虽然 claim 延迟下降，但冷批量日初始化使 launch 变慢，因此不能与预热后的同键轮次
直接比较，也不能据此宣称吞吐提升。

同键 1000 任务期间对 `pg_stat_activity` 做 9 次采样，累计观察到 141 个活动会话等待
`pg_advisory_xact_lock(hashtext(tenant_id), hashtext(business_key))`，显著高于其他等待。该锁用于保证
`result_version.version_no` 单调、同一业务键只有一个 EFFECTIVE 版本，以及 `asset_partition` 不被乱序
终态覆盖，不能删除或绕过。

本轮在不放松正确性约束的前提下缩短锁内事务：

- `result_version` 写入改为 PostgreSQL `INSERT ... RETURNING`，直接取得持久化实体，消除插入后的重复回查。
- 对不可变的 `(tenantId, assetCode, assetType) -> data_asset.id` 建立 10 万条、1 小时访问过期的有界缓存；
  数据库唯一键仍是最终权威，首次访问仍执行安全 upsert 和查询。
- 保留 advisory lock、EFFECTIVE 状态切换、资产分区单调 CAS 和全部租户条件。

刚重启后的冷态 1 万轮次为 `99.030 tasks/s`，入口前 10 秒有 JIT/连接池预热积压，不纳入优化对比。
同参数热态复跑结果如下：

| 指标 | 优化前双端点 | 优化后热态 | 变化 |
|---|---:|---:|---:|
| 完成吞吐 | 116.523/s | 136.517/s | +17.2% |
| 完成窗口 | 85.820s | 73.251s | -14.6% |
| 端到端 p95 | 42.991s | 33.635s | -21.8% |
| claim delay p95 | 37.510s | 29.804s | -20.5% |

优化后吞吐也比此前 1 万干净基线 `134.367/s` 高约 1.6%，说明本轮没有以正确性换性能。原始报告：

- `load-tests/target/p2-capacity-profile-result-version-hotpath-warm-10k-20260912.md`
- `load-tests/target/control-plane-worker-report-result-version-hotpath-warm-10k-20260912-10w.md`

## 对比结果

| 轮次 | 可信度 | HTTP 结果 | 端到端完成吞吐 | 结论 |
|---|---|---|---:|---|
| 1 万，12 分区、旧等待扫描预算 | 有效 | 10000/10000 | 15.434/s | 等待扫描/并发许可是主瓶颈 |
| 1 万，容量预算完成后 | 有效 | 10000/10000，p95 332ms | 128.714/s | 首次达到稳定高吞吐 |
| 1 万，清理与日志优化后 | 有效 | 10000/10000，p95 559ms | 134.367/s | 较上一有效基线提升约 4.4% |
| 1 万，Worker 双端点，优化前 | 有效 | 10000/10000，零失败 | 116.523/s | claim/report 已双实例均衡，但共享 PG 热点仍在 |
| 1 万，结果版本热路径优化后（热态） | 有效 | 10000/10000，p95 1112ms | 136.517/s | 保留正确性锁，完成窗口较上一行缩短 14.6% |
| 10 万，旧 3 分区缓存状态 | 趋势参考 | 100000/100000 | 约 117/s | 拓扑证据无效，不作为最终验收 |
| 10 万，最终严格轮次 | 有效 | 100000/100000，p95 67ms | 134.946/s | 全终态、零失败、零残留 |

从有真实 12 分区证据的旧 1 万轮次到当前 1 万轮次，完成吞吐约提升 8.7 倍。旧 10 万轮次受 3 分区
元数据缓存影响，只能作为趋势参考；与其运行窗口相比，最终轮次约提升 15%、完成窗口缩短约 13%。

## 当前瓶颈与边界

1. PostgreSQL 仍是当前主要容量约束。持续阶段观测到 PostgreSQL 使用约 2.6-3.0 核；入口停止后，
   backlog 排空速度明显提高，说明入口建模写入与 claim/report/终态写入仍竞争共享数据库资源。
2. 同一结果业务键的终态必须经过 advisory lock 串行化。这是结果版本正确性边界，不是可以通过增加
   Orchestrator 实例消除的无效锁；下一步只能继续缩短锁内 SQL，或让真实业务键自然分散。
3. Worker 多端点已经证明 claim/report 可在双 Orchestrator 间接近 50/50，但总吞吐没有仅因 HTTP 分流而
   提升，说明单 Orchestrator HTTP 入口不是主瓶颈。
4. Kubernetes Service 可在连接层分发请求，但长连接复用可能产生粘连。上线前仍应在真实 Service、多个
   Worker 实例下验证分布，不根据本地应用层轮转结果推断生产网络行为。
5. Orchestrator 已有 report-batch API，但 Worker 当前只批量 claim，没有批量 report。直接切换会影响
   lease、invocation fence、失败回退和终态时序，不能作为无风险参数调优混入本轮。
6. 当前 134.946/s 是本机 Atomic SQL 场景的 10 万完成吞吐，不代表 Import/Export/Process 或真实外部 HTTP、
   SFTP、对象存储场景的容量。

## 后续建议

P0 已完成，无需继续提高 Trigger admission。Worker 多端点已经解决本地直连场景的单实例集中问题；
下一项最有价值的工作是用 PostgreSQL statement/lock profile 继续定位锁内 SQL 占比，并在生产同构
Service、多 Worker 环境复核连接分布和共享数据库上限。

report batching 仅在 profiling 证明 report HTTP/事务是主要剩余热点后立项，并必须覆盖部分失败回退、
旧 invocation fence、重复批次、单项超时和 Orchestrator 切换测试。不要为了继续提高单机数字而弱化
逐任务终态 CAS、租约或幂等边界。
