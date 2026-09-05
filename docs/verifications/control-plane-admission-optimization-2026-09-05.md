# 控制面准入与排空优化复核 - 2026-09-05

## 背景与结论

10 万请求压力过程中，`default-tenant` 的 `fair_share_group=core`、共享上限 6 和
`exceeded_strategy=REJECT` 主导了失败结果；这验证的是业务配额，而不是控制面容量。此前
WAITING 队列每 10 秒扫描 100 条，在容量释放后还会产生最长一个轮询周期的空窗。

本轮只实施不改变状态机和跨实例幂等语义的优化。公平组最终准入仍由 PostgreSQL
事务级 advisory lock 守护，未替换为本地缓存或非原子计数。

## 六项处置

| 项 | 处置 | 状态 |
|---|---|---|
| 1. 容量与业务配额分离 | `run-p2-capacity-profile.sh` 默认准备并清理 `p2capacity` 临时租户，策略无 tenant/group 并发上限；原始 `default-tenant` 配额不再参与容量结论 | 已实施 |
| 2. 公平组准入临界区 | WAITING 候选排序只读共享组活跃数；真正 `WAITING -> READY/RUNNING` 的 `REQUIRES_NEW` 事务继续持 advisory lock 并复核 | 已实施 |
| 3. 容量释放后排空 | task 非重试终态在提交后发布事件，`WaitingPartitionDispatchKick` 用 250ms 合并调度一次；10 秒周期扫描仍是跨实例和遗漏事件兜底 | 已实施 |
| 4. Trigger 自适应准入 | 增加可选 AIMD 本地闸门，默认关闭：慢于阈值时减半，正常完成时每请求恢复一个许可 | 已实施，待灰度开启 |
| 5. 执行面扩容 | Helm 生产 overlay 已为五类 worker 配置 HPA；Kafka lag KEDA 模板和示例已存在。生产启用 KEDA 前仍需确认 operator、topic 分区数和每 worker 资源画像 | 已核查，不改副本策略 |
| 6. Quartz 长事务 | Trigger 固定切换为 `JobStoreTX`，其元数据使用同一平台库的独立 5 连接小池；不再依赖 Spring `LocalDataSourceJobStore` 的事务边界 | 已实施并在真实 PG 会话、Quartz JDBC 集群 IT 验证 |

## 配置与观测

- `BATCH_RESOURCE_SCHEDULER_WAITING_DISPATCH_KICK_ENABLED=true`
- `BATCH_RESOURCE_SCHEDULER_WAITING_DISPATCH_KICK_DELAY_MILLIS=250`
- `BATCH_TRIGGER_API_LAUNCH_ADAPTIVE_ENABLED=false`
- `BATCH_TRIGGER_API_LAUNCH_MIN_CONCURRENCY=16`
- `BATCH_TRIGGER_API_LAUNCH_SLOW_REQUEST_THRESHOLD_MILLIS=1000`
- `BATCH_TRIGGER_PLATFORM_DB_MAX_POOL_SIZE=10`
- `BATCH_TRIGGER_PLATFORM_DB_CONNECTION_TIMEOUT_MS=5000`
- `BATCH_TRIGGER_QUARTZ_DB_MAX_POOL_SIZE=5`

新增指标：

- `batch.scheduler.waiting_dispatch.kick.requested`
- `batch.scheduler.waiting_dispatch.kick.coalesced`
- `batch.scheduler.waiting_dispatch.kick.executed`
- `batch.trigger.api_launch.admission.active`
- `batch.trigger.api_launch.admission.limit`
- `batch.trigger.api_launch.admission.rejected`

Quartz 排查仍应关联 `batch.trigger.quartz.execution.duration`、
`batch.trigger.quartz.misfire.total` 与 PostgreSQL `pg_stat_activity`。持续超过 60 秒的
`idle in transaction` 必须保留 application name、query、wait event 和 xact age；但当前修复后，
Trigger 的 Quartz 会话应仅呈现普通 `idle`，不应再有持久的 `QRTZ_CRON_TRIGGERS` 事务或
`QRTZ_LOCKS FOR UPDATE` 等待。

### V198 并发索引迁移约束

`V198__trigger_request_tenant_dedup_index.sql` 使用 PostgreSQL `CREATE INDEX CONCURRENTLY`，脚本级
`executeInTransaction=false` 不足以避免 Flyway 默认的事务级 advisory lock 持有旧快照。Trigger 与
Orchestrator 的 `spring.flyway.postgresql.transactional-lock=false` 已固定为 session lock；否则新环境首次
启动会在 V198 等待自身事务，表现为启动卡住。该配置仍保留同一数据库上的迁移互斥。

## 验证

- 定向单测：公平组锁、等待队列合并唤醒、任务 outcome、Trigger 自适应闸门均通过。
- `bash -n load-tests/scripts/run-p2-capacity-profile.sh` 通过。
## 实测补充：异步 Relay 批处理

本节结果来自 Docker 本地真实链路：Trigger API → `trigger_outbox_event` → Kafka →
orchestrator → atomic worker。压测租户为隔离的 `p2capacity`，不受 `default-tenant` 公平组
配额影响。

### 10k / 200 RPS：端到端正确性通过，低延迟目标未通过

- 运行：`relay-budget-indexed-10k-20260905091200-10w`，严格终态核验。
- API：10,000/10,000 成功，p95 1.969s，零失败。该轮使用仅用于容量诊断的
  `BATCH_TRIGGER_API_LAUNCH_MAX_CONCURRENCY=512`，不代表默认生产 admission 64 的 SLO。
- Outbox：10,000/10,000 `PUBLISHED`，无 stale `PUBLISHING`。
- 执行：10,000/10,000 atomic task 和 job instance `SUCCESS`；实例平均 40.486s、p95 54.908s。
- 对比优化前的串行 Relay：Kafka ACK 不再逐条阻塞，成功状态回写由逐条 UPDATE 收敛为批量 CAS UPDATE。

### 1k / 200 RPS：Trigger 平台池 A/B

两轮均使用 `apiLaunchMaxConcurrency=512` 以隔离入口 429，relay 保持 `40 events/s`，并严格核验
1,000/1,000 `trigger_request`、`job_instance` 和 atomic task 进入终态后清理测试数据。

| Trigger 平台池 | HTTP 成功 | p95 | 平均 | 结论 |
|---|---:|---:|---:|---|
| 10（默认） | 1,000/1,000 | 2.052s | 893ms | 连接池排队会放大突发延迟 |
| 32（仅本地诊断） | 1,000/1,000 | 1.689s | 674ms | p95 改善约 18%，但仍不达 500ms |

结论：显式暴露 Trigger 平台池是必要的，默认仍保持 10，不能根据单机 512 并发诊断直接改为 32。
剩余主瓶颈是高并发入口写 `trigger_request + trigger_outbox_event` 的事务竞争及下游保护，而非单纯的
Kafka relay。生产调参应先以副本数、PostgreSQL 连接预算和 admission 64 的实测为准。

### 默认生产参数基线：40 RPS 持续写入通过，200 RPS 突发被准入保护

两轮均恢复 Trigger 默认参数：`apiLaunchMaxConcurrency=64`、平台业务池 10、Quartz JobStoreTX 池 5、
Relay 每进程 `40 events/s`。

| 入口画像 | HTTP 结果 | 入口延迟 | 端到端结果 | 结论 |
|---|---:|---:|---|---|
| 1,000 / 40 RPS | 1,000/1,000 成功 | p50 14ms、p95 136ms、p99 454ms | 1,000/1,000 `SUCCESS`，自动清理 | 当前本机可复现的持续释放基线 |
| 1,000 / 200 RPS | 982 成功、18 个 429 | 成功请求 p95 4.397s，全部请求 p95 4.403s | 982/982 已接收请求均 `SUCCESS`，经终态核验后手工清理 | admission 保护生效；不是可承诺的持续入口速率 |

200 RPS 轮的 429 是入口并发闸门主动拒绝，未产生 `trigger_request`，不属于链路半成功。现有容量脚本把
“发送总数必须全部落库”作为自动清理前提，因此该轮保留现场；已在确认 982 个已接收请求、outbox、实例和
atomic task 全部终态后按依赖顺序清理。

`maxPublishEventsPerSecond=40` 是 **单 Trigger JVM** 的内存预算，而非集群预算。当前 Helm 默认
`trigger.replicaCount=1`，所以本结论成立；扩 Trigger 副本前必须改为共享预算，或按副本数下调每 Pod
限额并重新压测，不能直接横向扩容。

### 100k / 200 RPS：未通过，不可作为当前单机容量承诺

此轮为了测上限，临时将 `BATCH_TRIGGER_API_LAUNCH_MAX_CONCURRENCY` 从默认 `64` 提高到
`512`；该覆盖不能带入生产基线。

- 运行：`relay-async-100k-v1-20260905080553`，在 7 分 29 秒后主动终止以保护本机环境。
- 已完成请求：56,551 成功；随后出现 60 秒 HTTP 超时与连接提前关闭，故该轮失败。
- 截止快照：7,270 个实例已创建，5,187 成功，2,083 非终态；task p95 claim delay 82.159s。
- 观测：PostgreSQL 接近多核饱和；Trigger Quartz MisfireHandler 出现
  `idle-in-transaction timeout`，连接被服务端终止。该异常随后放大为 Quartz 连接恢复噪声，
  不是 Kafka 发布失败。

结论：异步 Relay 已移除原先的串行 Kafka ACK 瓶颈，但也更快地把工作释放到
orchestrator/worker。当前单机连续执行容量低于此压测释放速率；正确做法是由下游容量反向
限制 Relay 释放，而不是继续提高 API admission 上限。

## 后续治理（未实施）

1. Trigger Relay 已引入保守的每秒发布预算，保留源端 API 的持久化接收能力；下一阶段改为以
   Kafka consumer lag、非终态 task 数或 orchestrator 容量信号驱动的自适应预算。
2. 已修正压测中断清理：未通过终态核验的轮次保留现场并释放执行锁；不得先删除对应
   `trigger_request`。后续由人工在消费排空后进行依赖顺序清理。
