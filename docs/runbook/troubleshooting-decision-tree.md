# 故障决策树 Runbook

> 症状 → 根因 → 动作。按"先看哪里、看到什么、做什么"组织。
> 配合 `docs/runbook/incident-response.md`（分级 + SLA）和 `make ops-inspect` 使用。

## 入口：先跑这两条命令

```bash
make ops-inspect                                        # 全量诊断（DB+Kafka+Worker+Outbox）
curl -s localhost:9090/api/v1/alerts | jq .data.alerts  # Prometheus 当前告警
```

输出对照下方症状定位。

---

## 症状 1：任务不执行 / worker 空转

### 现象
- 控制台看到任务"已派发"但 worker 不动
- 或任务停在 RUNNING 不推进

### 决策树

```
kubectl get pods -n batch-prod
├─ worker pod 全 Running？
│  ├─ 是 → 看 Kafka lag
│  │  └─ curl -s batch-orchestrator:18082/actuator/prometheus | grep kafka_consumer
│  │     ├─ lag > 1000 持续 → 走症状 2（Kafka 消费堆积）
│  │     └─ lag 正常 → 看下一级
│  └─ 否 → kubectl describe pod <name>，先修 Pod 问题
│
├─ 看 /api/console/workers
│  ├─ 有 DRAINING 卡住 → drain 超时未接管
│  │  → bash scripts/ops/heal-drain-timeout.sh
│  │  → 若脚本频繁触发，检查 WorkerDrainTimeoutScheduler 日志
│  │
│  └─ ACTIVE 正常 → 下一级
│
├─ 看 Outbox 积压量（Prometheus：batch_outbox_pending_events）
│  ├─ > 5000 且持续增长 → 走症状 3（Outbox 堆积）
│  ├─ 正常 → 下一级
│  └─ 看 batch_outbox_publishing_stale_events 是否 > 0
│     → 走症状 4（OutboxPoll 轮询本身异常退出）
│
└─ 看 job_instance.status 分布
   ├─ 大量 RUNNING 卡住 → 查 SLA 告警：batch_job_sla_violation_count
   └─ 任务根本没进来 → 查 trigger：curl localhost:18081/actuator/health
```

### 动作
- drain 超时 → `bash scripts/ops/heal-drain-timeout.sh`
- Outbox 堆积 → 见症状 3
- PUBLISHING 长期停滞 → 见症状 4
- SLA 违规 → 看告警原因，多数是 worker 吞吐不足或任务卡在某步；查 `job_execution_log`

---

## 症状 2：Kafka 消费堆积（`BatchKafkaConsumerLagHigh`）

### 根因可能性（从高到低）
1. Worker 实例数不够（扩容 / KEDA 未启用）
2. 消费处理慢（业务代码某步卡住，比如 SFTP / API 下游响应）
3. 某条毒丸消息让 consumer 反复 rebalance

### 动作

```bash
# 1. 看 worker 健康 + 实例数
kubectl get pods -n batch-prod -l app.kubernetes.io/component=worker-import
kubectl top pod -n batch-prod            # CPU / 内存是否打满

# 2. 看具体堆积 topic
kubectl exec -it batch-kafka-0 -- kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --all-groups --describe

# 3. 如果 LAG 集中在某 partition，可能是单条毒丸 —
kubectl logs <worker-pod> --since=5m | grep -i 'failed\|exception' | head -20
# 如果有 poison message，查该 eventKey → 走症状 5（DLQ 排查）

# 4. 立即扩容（临时）
helm upgrade batch helm/batch-platform/ -n batch-prod \
  --values helm/batch-platform/examples/values-autoscale.yaml  # 打开 KEDA
# 或
kubectl scale deployment/batch-worker-import -n batch-prod --replicas=4
```

---

## 症状 3：Outbox 堆积（`BatchOutboxBacklogHigh` / `Critical`）

### 决策树

```
查 batch_outbox_publishing_stale_events
├─ > 0 且持续 → OutboxPollScheduler 没跑
│  → 走症状 4
│
└─ = 0（轮询在跑）→ 说明在发但发不赢
   查 batch_dispatch_circuits_open
   ├─ > 0 → 熔断触发，Kafka 或下游异常
   │  → kubectl logs batch-orchestrator-0 | grep -i 'circuit\|publish'
   │  → 看 Kafka 是否健康：kubectl exec batch-kafka-0 -- kafka-topics.sh --list
   │
   └─ = 0 → 吞吐不够
      → 扩 orchestrator 副本（需先 sharding.mode=dynamic）
      → kubectl scale sts batch-orchestrator --replicas=4
```

### 应急回退

```bash
# 只有明确是"一次性历史积压、新增速率已回正常"才跑
bash scripts/ops/heal-stuck-outbox.sh --dry-run    # 先看会动哪些
bash scripts/ops/heal-stuck-outbox.sh              # 确认后执行
```

---

## 症状 4：PUBLISHING 长期停滞（`BatchOutboxStalePublishingStuck`）

### 根因
OutboxPollScheduler 没有在跑，`resetStalePublishing` 永远不清 0。

### 排查

```bash
# 1. 看 OutboxPoll 启动日志（应有 "OutboxPollScheduler 已启动（自适应模式）"）
kubectl logs batch-orchestrator-0 | grep -i 'OutboxPoll'

# 2. 看 ShedLock 是否被某个 Pod 永久持有
kubectl exec -it batch-postgres-0 -- psql -U batch_user -d batch_platform -c \
  "SELECT name, lock_until, locked_at, locked_by FROM shedlock \
   WHERE name LIKE 'outbox_poll%' ORDER BY lock_until DESC"
# lock_until 远超当前时间 + lockAtMostFor=1min → 锁被 crash 的 pod 留下

# 3. 如果 lazy-init 相关（罕见）→ 看 spring.main.lazy-initialization
kubectl exec batch-orchestrator-0 -- printenv | grep -i lazy
```

### 动作

```bash
# 锁残留 → 手动清：
psql ... -c "DELETE FROM shedlock WHERE name = 'outbox_poll_shard_0' \
             AND lock_until < NOW()"

# 重启 orchestrator：
kubectl -n batch-prod rollout restart statefulset/batch-orchestrator
```

---

## 症状 5：DLQ 堆积（`BatchDeadLetterBacklogHigh`）

### 原则
**不自动重放**——先查根因。批量 heal 会把同一个 bug 触发 N 次。

### 决策树

```
查 dead_letter_task 样本
  SELECT * FROM batch.dead_letter_task
   WHERE replay_status IN ('NEW','FAILED')
   ORDER BY created_at DESC LIMIT 20

按 last_replay_result 字段分组看错误模式：
├─ 都指向同一业务 bug（NullPointerException 之类）
│  → 修代码，打 release，再重放
│
├─ 都指向下游依赖超时（SFTP / API）
│  → 下游恢复后再重放
│
└─ 错误分散，无明显共性
   → 抽样 5-10 条手动 replay，观察成功率
```

### 手动重放

```bash
# 控制台批量入口（per event）
curl -X POST -H "X-Internal-Secret: <secret>" \
  http://batch-console-api:18080/api/console/jobs/compensate \
  -d '{"tenantId":"t1","targetType":"DLQ","targetId":"<dead_letter_id>","reason":"root cause fixed"}'

# 脚本批量（带 dry-run）
bash scripts/ops/heal-dead-letters.sh --tenant t1 --dry-run
bash scripts/ops/heal-dead-letters.sh --tenant t1
```

---

## 症状 6：Pod 启动就失败（CrashLoopBackOff）

### 决策树

```
kubectl logs <pod> --previous | tail -50
├─ "too many clients already" → PG 连接池耗尽
│  → 本地：改 docker-compose.yml postgres 的 max_connections=300
│  → K8s：检查所有模块的 Hikari maximum-pool-size 总和
│
├─ "Connection refused" 到 Kafka/Redis/PG → 依赖未就绪
│  → 确认 batch-postgres / batch-kafka / batch-redis 服务 READY
│
├─ "Missing migration" / Flyway 报错 → 迁移未应用
│  → 检查 db/migration 与生产 DB 版本：
│    SELECT version, description, installed_on FROM flyway_schema_history
│      ORDER BY installed_rank DESC LIMIT 5
│
├─ "startup probe failed" → 启动时间超 startupProbe.timeoutSeconds
│  → 临时调大：values.yaml startupProbe.failureThreshold
│  → 长期：减少 @PostConstruct 重活 / 缩 Flyway baseline / 评估 Spring Boot AOT
│    （注：原方案里的 AppCDS 预热在 JDK 25 + SB 4 上因 dump/runtime native-access 标志
│     错位会触发 NoClassDefFoundError，scripts/local/start-all.sh 已默认 -Xshare:off）
│
└─ BatchStartupSelfCheck 报错 → 按日志列出的缺失项处理
```

---

## 症状 7：分片不均衡（DYNAMIC 模式）

### 现象
kubectl scale 扩容后，新 Pod 没分到活。

### 排查

```bash
# 1. 看 Redis 成员注册
redis-cli -h batch-redis -p 6379 ZRANGE batch:orchestrator:members 0 -1 WITHSCORES
# 预期：活跃 Pod 数 = StatefulSet replicas

# 2. 看某 Pod 当前分到的 shard
kubectl logs batch-orchestrator-2 --since=2m | grep 'shard='
# 正常格式："shard=2/3"

# 3. 看启动模式是否 DYNAMIC
kubectl exec batch-orchestrator-0 -- printenv | grep SHARDING
# 必须 BATCH_OUTBOX_SHARDING_MODE=dynamic
```

### 动作

- Redis ZSET 少成员 → 该 Pod 心跳没发出去 → 看 Pod 日志 Redis 连接状态
- SHARDING_MODE=static → 扩容前要先切 dynamic（`helm upgrade --set orchestrator.sharding.mode=dynamic`）
- 模式对但 shard 不重算 → 等 heartbeat TTL（30s）过期后其他 Pod 下一轮 poll 会自动重算

---

## 症状 8：分布式锁冲突 / 定时任务不执行

### 排查

```sql
-- 活跃锁清单
SELECT name, lock_until, locked_at, locked_by,
       EXTRACT(EPOCH FROM (lock_until - locked_at)) AS hold_seconds
  FROM shedlock
 WHERE lock_until > NOW()
 ORDER BY lock_until - locked_at DESC
 LIMIT 20;

-- 如果 hold_seconds 显著大于该 lock 对应 @SchedulerLock(lockAtMostFor=...) 参数
-- → 说明 crashed pod 留下的残骸
```

### 动作

```sql
-- 确认 pod 不存在后手动清
DELETE FROM shedlock WHERE name = '<lock_name>' AND locked_by LIKE '<dead_pod_host>%';
```

---

## 附录：常用 Prometheus 查询

```promql
# Outbox 积压
batch_outbox_pending_events
# PUBLISHING 长期停滞
batch_outbox_publishing_stale_events
# DLQ 积压
batch_dead_letter_tasks_pending
# 熔断器
batch_dispatch_circuits_open
# SLA 违规
batch_job_sla_violation_count
# Kafka 消费 lag
kafka_consumergroup_lag{consumergroup=~"batch-worker-.*"}
# 各模块 CPU / 内存
rate(process_cpu_seconds_total{job=~"batch-.*"}[1m])
jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"}
```

## 附录：运维脚本速查

| 脚本 | 用途 | 何时用 |
|---|---|---|
| `scripts/ops/inspect-all.sh` | 全量诊断 | 告警响起第一时间跑 |
| `scripts/ops/heal-stuck-outbox.sh` | 重置卡住 >300s 的 Outbox | 确认堆积非业务原因后 |
| `scripts/ops/heal-drain-timeout.sh` | 强制下线排水超时 Worker | WorkerDrainTimeoutScheduler 没跑时 |
| `scripts/ops/heal-dead-letters.sh` | 批量重放 DLQ | **根因明确修复后** |
| `scripts/ops/heal-retry-tasks.sh` | 批量重放 FAILED 任务 | 确认非代码 bug |
| `scripts/ops/heal-retry-partitions.sh` | 重试 FAILED 分区 | 分区集中失败 |
| `scripts/ops/trigger-compensation.sh` | 手工补偿命令 | 定向修复单条任务 |

## 禁止清单

以下操作**永远不要**自动化：
- DLQ 自动重放（会掩盖真实 bug 并放大故障）
- Outbox 堆积时自动重建 consumer（可能双消费）
- 自动 `DELETE FROM shedlock`（可能删掉正在运行中的锁）
- 自动 `force-offline` worker（会丢失 in-flight 任务）

所有恢复动作必须人工 review 根因后再执行。
