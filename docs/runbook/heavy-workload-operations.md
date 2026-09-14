# 重任务容量与资源池运维

- **适用版本**：1.0.0+
- **最后核查**：2026-09-14
- **设计入口**：[`heavy-workload-guarantees.md`](../architecture/heavy-workload-guarantees.md)

## 1. 上线前定容

先用目标环境、真实数据量跑至少三轮基线，取中位数。不要只按控制面 `tasks/s` 决定重任务并发。

| 输入 | 用途 |
|---|---|
| 单任务 p95 墙钟时间、文件字节、记录数 | 估算批量窗口内单池需要的并发 |
| Worker CPU、堆、直接内存、临时盘、网络 | 决定每 Pod 并发和资源 request/limit |
| PG WAL、连接池等待、CPU/IO | 决定控制面全局活跃上限 |
| Kafka lag 与排空速度 | 验证 Worker 副本和 topic 分区数 |
| 下游并发、QPS、超时和限流约束 | 决定 Dispatch 队列上限和 QPS |

生产必须显式设置：

```bash
BATCH_RESOURCE_SCHEDULER_GLOBAL_MAX_RUNNING_JOBS=1000
```

该值是所有租户活跃作业总和，不是单 Worker 并发。应从较保守值灰度上调；不要为了消除 WAITING 直接设成
压测请求总量。

## 2. 配置租户和队列保护

通过 Console 的租户配额与资源队列配置维护以下字段，不直接改数据库：

| 层级 | 并发 | 速率 | 推荐用途 |
|---|---|---|---|
| 租户 | `maxRunningJobsPerTenant`、`maxPartitionsPerTenant` | `maxQpsPerTenant` | 防单一租户占满平台 |
| 资源队列 | `maxRunningJobs`、`maxRunningPartitions` | `maxQps` | 分离日批、补数、导入、导出或 Dispatch 下游 |
| Worker | `maxConcurrent` | Kafka poll/批量 claim 参数 | 防单实例内存、连接或线程耗尽 |

普通日批建议使用 `QUEUE_DEFER`；需要明确拒绝外部突发请求时，租户策略可设 `REJECT`。修改后观察所有
Orchestrator 的配置缓存失效指标，确认不是单实例生效。

## 3. 配置专用资源池

作业参数示例：

```json
{
  "resourceProfile": "memory-heavy"
}
```

Helm values 示例：

```yaml
workerResourcePools:
  - name: import-memory
    workerType: import
    tenantId: default-tenant
    resourceProfile: memory-heavy
    capabilityTags: [large-file]
    replicaCount: 3
    maxConcurrentTasks: 2
    executionPoolSize: 4
    javaOptsExtra: "-XX:MaxDirectMemorySize=2g"
    resources:
      requests: {cpu: "1000m", memory: "2Gi"}
      limits: {cpu: "4000m", memory: "6Gi"}
```

`executionPoolSize` 必须大于或等于 `maxConcurrentTasks`，否则 Worker 启动守卫会拒绝启动。需要绑定专用节点
时，在单个池中配置 `nodeSelector/tolerations/affinity`；不配置则继承 chart 全局调度约束。资源池不接管
Kubernetes 调度，也不允许用通用模板部署 Atomic Worker。

发布 Deployment 前必须预创建专用池 direct topic，并保证分区数不小于计划并行消费数。生产环境不要
依赖 broker 自动建 topic：

```bash
KAFKA_DIRECT_WORKER_TOPICS="batch.task.dispatch.import.node.import-memory,batch.task.dispatch.process.node.process-cpu" \
KAFKA_PARTITIONS_DISPATCH=8 \
bash scripts/data/init-kafka-topics.sh
```

`KAFKA_DIRECT_WORKER_TOPICS` 只追加专用池 topic，不会替换五类内置 Worker 的默认 direct topic。Kafka
分区只能增加，扩容前应先扩大 topic 分区，再增加 Worker 副本；缩容不需要减少分区。

部署后检查：

1. 专用 direct topic 已存在，partition 数不小于目标 consumer 并行数。
2. 每个 Pod 的 `worker_registry.worker_code` 唯一，`worker_pool_code` 相同。
3. Pod 只订阅 `batch.task.dispatch.<type>.node.<poolCode>`，consumer group 为该池专用值。
4. `resourceProfile` 不匹配的普通 Worker 不会 claim 任务。
5. task claim 后 `assigned_worker_code` 从稳定池代码改为实际 Pod 实例 ID。
6. 副本数至少 2 的池已渲染 PDB，计划内节点驱逐不会同时中断整个池。

不要用通用资源池模板部署 Atomic。Atomic 继续按独立 ServiceAccount、Secret、NetworkPolicy 和安全开关部署。

## 4. 灰度顺序

1. 先部署数据库迁移 V209；旧 Worker 不受影响，池代码回退为原 `worker_code`。
2. 部署新 Orchestrator，保持 `workerResourcePools=[]`，验证共享池主链。
3. 只创建一个低流量资源池，给一条测试作业加 `resourceProfile`。
4. 验证 claim、续租、取消、完成上报和重启 reclaim，再逐步迁移重任务。
5. 最后调整全局、租户、队列与 Worker 并发；每次只改一层并保留 A/B 证据。

## 5. 运行时判据

| 现象 | 先查 | 处置 |
|---|---|---|
| 大量 `GLOBAL_RUNNING_JOB_LIMIT` | 全局活跃作业数、PG WAL/连接等待、Worker 空闲率 | 下游确有余量才上调全局上限；否则等待排空 |
| `TENANT/QUEUE_*_LIMIT` | 租户/队列配置、补数或重跑流量 | 降低补数并发，或把补数迁到独立队列 |
| `NO_AVAILABLE_WORKER` | Worker ONLINE、能力标签、`resourceProfile`、`currentLoad/maxConcurrent` | 修标签或扩对应池；不要回退到错误资源池 |
| `DOWNSTREAM_CHANNEL_UNAVAILABLE` | `file_channel_health`、探针错误、凭据与网络 | 修复下游；健康探针成功后自动释放，不手改状态 |
| 内部派发限流 fail-closed | Redis/Valkey、`batch.ratelimit.failclosed.total` | 恢复 Redis；不要临时删掉 QPS 保护 |
| lease renew rejection | invocation ID、Worker 实例 ID、任务当前状态 | 判定是否旧 Worker 迟到；不要强行续租 |
| checkpoint failure | PG、幂等能力、checkpoint failure 指标 | 暂停扩大流量，必要时关 checkpoint 并重启对应 Worker |

## 6. 回滚

- **资源池回滚**：停止给作业传 `resourceProfile`，待池内任务终态后把对应 `workerResourcePools` 条目缩到 0
  或删除。不要在任务运行中改变稳定 `workerCode`。
- **全局上限回滚**：生产不能设 0；把值调回上一版并滚动重启 Orchestrator。
- **QPS 回滚**：恢复租户/队列旧值。动态 `maxQps` 的阈值签名包含在 Redis key 中，改值后立即启用对应桶；
  不要删除当前桶或修改应用级 `batch.rate-limit.bucket-configuration-version`。
- **数据库**：V209 是 additive migration，不回删列。旧 Worker 和旧 SDK 会自动退化为一实例一池。

## 7. 当前边界

WAITING 的存储数量硬上限与统一等待 TTL、任务派发侧显式 Kafka lag gate 尚未在本轮实现。生产必须为热表
和索引预留容量，并对 WAITING depth/age、Outbox backlog、Kafka lag 设置告警。出现持续增长时先停止补数或
新触发，不通过本地队列、手改状态或绕过 Outbox 处理。
