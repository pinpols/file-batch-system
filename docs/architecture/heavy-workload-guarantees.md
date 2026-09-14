# 重任务容量与执行保障

- **状态**：已实现
- **最后核查**：2026-09-14
- **适用范围**：Import、Export、Process、Dispatch、Atomic 以及 DAG 中的同类任务
- **关联决策**：ADR-016、ADR-027、ADR-038、ADR-042、ADR-046

本文是重任务场景的权威设计入口。它回答的是“控制面如何避免重任务洪峰拖垮平台、任务如何落到合适的
Worker、长任务如何安全执行、下游故障如何阻断放量、容量承诺如何计算”，不替代各 Worker 的业务场景
文档和压测报告。

## 1. 五项保障

| 保障 | 当前实现 | 核心不变量 |
|---|---|---|
| 活跃容量受控 | 全局、租户、资源队列、分片、Worker 实例五层并发闸门；租户/队列派发 QPS 由 Redis 共享令牌桶限制；超限进入 `WAITING` 或按租户策略拒绝 | 生产环境不能以无限全局活跃作业配置启动；多 Orchestrator 并发准入不能突破全局上限 |
| 异构资源隔离 | 作业声明 `resourceProfile`，Worker 注册能力标签；Helm 可创建独立 CPU、内存或 IO 资源池 | 调度只挑 Worker 池，不接管 Kubernetes 节点调度；不同资源池不消费彼此的直达 topic |
| 长任务可靠执行 | invocation fence、批量续租、超时、协作式取消、lease reclaim、checkpoint、幂等重派和优雅停机 | 旧 Worker 不能续租或上报新 invocation；网络故障不能被误判成业务拒绝；checkpoint 只在满足幂等约束时启用 |
| 下游容量参与准入 | Worker 在线状态、`currentLoad/maxConcurrent`、能力标签、资源画像和 Dispatch 渠道健康快照共同参与调度 | 没有匹配 Worker 或渠道非健康时不产生新的任务派发消息，分片保留在 `WAITING` 等待恢复 |
| 容量按数据量和窗口承诺 | 容量画像同时返回实例数、任务数、文件字节、处理记录数、累计耗时和墙钟耗时；records/s 与 MB/s 以墙钟耗时计算 | 并行任务耗时求和只表示资源成本，不能作为批量窗口吞吐分母 |

## 2. 统一准入链

`ResourceScheduler` 是 launch、DAG 节点和 WAITING 重派共用的准入入口，检查顺序固定为：

```text
批量窗口
  -> 全局 / 租户 / 资源队列作业并发
  -> 租户 / 资源队列分片并发
  -> Worker 类型、能力、resourceProfile、实例负载
  -> Dispatch 下游渠道健康
  -> 租户 / 资源队列每秒派发预算
  -> ACCEPT / DEFER / REJECT
```

顺序有意把廉价且影响范围大的检查放在前面。只有找到可用 Worker 后才消耗派发令牌，避免“没有执行者”
的任务提前占用速率预算；只有所有检查通过才写任务派发 Outbox。

### 2.1 全局活跃作业硬上限

`batch.resource-scheduler.global-max-running-jobs` 默认 `1000`。生产 profile 下该值必须大于零，否则
`ResourceAdmissionStartupGuard` 让应用启动失败。正式准入在当前数据库事务内取得 PostgreSQL transaction
advisory lock，再读取 `READY/RUNNING` 活跃作业数；状态推进提交前不释放锁，因此多个 Orchestrator 不会
同时抢到最后一个槽位。

DAG 的多个初始节点只占一个父作业槽位。WAITING 父作业重新进入活跃态时再次取得同一事务锁；已经运行
的 DAG 后续节点不重复计算为新作业。

本地 profile 默认显式把该值设为 `0`，便于独立性能实验。生产 Helm values 固定为正数，不能照搬本地值。

### 2.2 租户、队列和 Worker 反压

- `tenant_quota_policy.max_running_jobs_per_tenant` 与 `max_partitions_per_tenant` 限制单租户活跃量。
- `resource_queue.max_running_jobs` 与 `max_running_partitions` 限制单队列活跃量。
- 两表的 `max_qps*` 字段限制实际派发速率；所有 Orchestrator 共享 Redis Bucket4j 状态。
- `max_qps*` 是热更新配置，阈值签名进入 Redis key；改值立即启用新桶，不依赖应用重启或部署级桶版本，
  旧 key 由 Bucket4j 过期策略回收。
- 内部派发令牌桶在 Redis 故障时 fail-closed，分片留在 WAITING；外部 Console API 限流仍按其独立策略处理。
- Worker 注册的 `currentLoad >= maxConcurrent` 时不再被选中；容量释放后 WAITING scheduler 重新评估。

`QUEUE_DEFER` 保护的是活跃执行面：任务不会因为容量暂时不足而误失败，也不会继续放大 Worker、Kafka 和
业务库压力。它不是无限存储承诺，见“范围边界”。

## 3. 资源画像与稳定 Worker 池

### 3.1 路由契约

作业可在参数中声明 `resourceProfile`，兼容读取历史别名 `resource_profile` 和 `resourceTag`。调度器要求
候选 Worker 的 `capabilityTags` 或 `resourceTag` 命中该值；不命中时保持 WAITING，不回退到错误资源池。

Worker 身份拆成两个字段：

| 字段 | 语义 | 示例 |
|---|---|---|
| `workerPoolCode` | Deployment 级稳定路由代码，同池所有副本共享 | `import-memory` |
| `workerCode` | 具体进程或 Pod 的唯一实例 ID | `import-memory-7b8f...` |

调度消息写稳定池代码，使任一池内副本都能竞争消费；claim 成功后，task 与 partition 的执行者改写为实际
实例 ID，后续续租、取消、上报继续受 invocation fence 约束。旧 SDK 不上报 `workerPoolCode` 时自动回退为
`workerCode`，保持一实例一池的历史语义。

### 3.2 Kafka 隔离

专用池使用 `DIRECT_ONLY`，只订阅 `base.node.<poolCode>`。生产者与消费者都通过
`BatchTopics.directDispatchTopic` 生成并清洗 topic 后缀，避免自定义池代码包含空格或斜杠时两端不一致。
每个池有独立 consumer group，不会和共享池重复消费同一任务。

专用 direct topic 必须由部署流程预创建，分区数至少覆盖目标并行 consumer 数。BFS 不依赖生产 Kafka
自动建 topic；`scripts/data/init-kafka-topics.sh` 通过 `KAFKA_DIRECT_WORKER_TOPICS` 追加池 topic，同时保留
五类内置 Worker 的默认 direct topic。

### 3.3 Kubernetes 边界

`workerResourcePools` 只创建独立 Deployment、Service、PDB、单 Pod 并发、资源 request/limit、JVM 参数及
池级 `nodeSelector/tolerations/affinity`。自动扩容仍交给 Kubernetes 或外部 HPA/KEDA overlay；BFS 不实现自己
的节点调度器。Atomic 的安全隔离规则不通过通用资源池绕过，通用模板因此不允许创建 Atomic 资源池。

## 4. 长任务生命周期

```text
claim
  -> 生成 partitionInvocationId
  -> 执行线程注册 active lease
  -> 周期批量 renew，同时读取 cancelRequested
  -> checkpoint 持久化已提交进度
  -> SUCCESS/FAILED/CANCELLED report
```

- **续租**：Worker 批量续租，失败区分数据库 CAS 拒绝和网络故障；只有明确拒绝才标记 lease lost。
- **超时**：任务执行在独立有界线程池中；超时后 `Future.cancel(true)`，不占住 Kafka listener。
- **取消**：renew 返回取消标记后中断执行线程；插件必须遵守 interrupt/取消契约。忽略中断的第三方插件仍需
  通过独立进程或 Pod 隔离，这是 JVM 协作式取消的固有限制。
- **回收**：Worker 崩溃或 lease 到期后由 Orchestrator reclaim，旧 invocation 的迟到上报被拒绝。
- **续跑**：Import、Export 和 Process 已有 checkpoint/阶段续跑；跨库 Import 使用“业务先提交、位点后推进
  + 幂等吸收最多一个 chunk 重做”的补偿式最终一致语义。

详细约束和回滚步骤见
[`platform-worker-checkpoint-howto.md`](../runbook/platform-worker-checkpoint-howto.md)。

## 5. 下游健康准入

Dispatch 作业从参数读取 `channelCode`，兼容 `dispatchChannelCode`、`targetChannelCode`。若
`file_channel_health` 中该渠道为 `DEGRADED/UNHEALTHY`，调度返回
`DOWNSTREAM_CHANNEL_UNAVAILABLE`，不写新的 Kafka 派发消息。Dispatch Worker 的独立探针把状态恢复为
`HEALTHY` 后，WAITING scheduler 才小批量释放。

健康表没有该渠道的记录时允许首次探测性执行；首次失败由现有渠道熔断和健康仓储建立快照。这样既不会让
新渠道因“从未执行”永久卡住，也不会在已知故障期间持续放大流量。

## 6. 容量承诺口径

`GET /api/console/capacity-profile` 的每行包含：

- `instanceCount/taskCount/successCount/failureCount`：控制面规模与正确性；
- `totalFileBytes/processedRecords`：实际数据量；
- `totalDurationMs`：各实例或任务耗时总和，用于估算资源成本；
- `wallClockDurationMs`：同一分组最早开始到最晚结束的时间跨度；
- `recordsPerSecond/mbPerSecond`：以前述墙钟时间为分母的窗口吞吐。

生产容量承诺至少使用 `数据量 + wallClockDurationMs + p95DurationMs + 失败率`，并结合 PostgreSQL WAL、
CPU/IO、Kafka lag 和 Worker 饱和度。`tasks/s` 只适用于同一任务重量的控制面 A/B，不可直接外推重任务 SLA。

## 7. 范围边界

本轮完成五项重任务执行保障，但以下两项仍是独立的容量治理增强，不能混写成已经实现：

1. **WAITING 存储量硬上限与统一 TTL**：当前活跃执行量和释放速率有硬边界，WAITING 使用 PG 持久化并按批
   重评；尚未提供跨租户统一 `max_pending` 和等待超时终态。上线容量必须为待处理数据预留 PG 空间并告警。
2. **任务派发 Kafka 显式 lag gate**：Trigger relay 已有可选的 lag 自适应释放；任务派发侧当前依靠全局活跃
   上限、Worker 负载、QPS、Outbox 重试和 Kafka/KEDA 告警形成反馈，没有直接按 consumer lag 拒绝准入。

这两个增强继续以 ADR-042 和调度边界路线图为准。未完成前不得宣称“任意请求洪峰下 backlog 永不增长”。
同时明确不做本地队列替代 Kafka、不自研 Kubernetes Scheduler、不把容量画像扩成 FinOps 平台。

## 8. 代码与证据入口

| 范围 | 入口 |
|---|---|
| 统一准入 | `DefaultResourceScheduler`、`DefaultConcurrencyLimiter`、`DefaultDispatchAdmissionLimiter` |
| 全局硬上限 | `GlobalJobAdmissionGuard`、`JobInstanceMapper.acquireGlobalJobAdmissionLock` |
| Worker 路由 | `DefaultWorkerSelector`、`WorkerRegistration`、`TaskConsumerRoutingPolicy` |
| 下游健康 | `DispatchChannelAdmissionGuard`、`file_channel_health` |
| 长任务 | `WorkerTaskLeaseRenewer`、`TaskExecutionPool`、`PartitionLeaseReclaimScheduler`、checkpoint stores |
| 部署 | `helm/batch-platform/templates/worker-resource-pool.yaml` |
| 操作手册 | [`heavy-workload-operations.md`](../runbook/heavy-workload-operations.md) |
| 本轮验证 | [`heavy-workload-guarantees-2026-09-14.md`](../verifications/heavy-workload-guarantees-2026-09-14.md) |
