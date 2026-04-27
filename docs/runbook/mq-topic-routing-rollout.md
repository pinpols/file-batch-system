# MQ Topic 分流（`batch.mq.routing.mode`）切换灰度发布

> 配套：[`docs/runbook/feature-switches.md`](./feature-switches.md) §3.3、`BatchTopicResolver` / `AbstractTaskConsumer.topicPattern()`。
>
> 适用于 `batch.mq.routing.mode` 在 `SINGLE` / `TENANT` / `PRIORITY` 之间切换的运维操作。

---

## 0. 关键设计前提（先理解再操作）

| 组件 | 行为 |
|---|---|
| **Producer**（orchestrator `KafkaOutboxPublisher`） | 由 `BatchTopicResolver` 根据当前 `mode` 决定写到哪个 topic：`SINGLE` → `base`，`TENANT` → `base.<tenantId>`，`PRIORITY` → `base.<priorityBand>` |
| **Consumer**（worker `AbstractTaskConsumer`） | 默认 `topicPattern()` 用宽松正则 `^base(\\.[^.]+)?$` 同时匹配所有三种 producer 输出，**无需重启即可拾取新 topic** |
| **Kafka client metadata 刷新** | `metadata.max.age.ms`（默认 300_000=5min）周期重发现 topic；模式切换后 worker 最多 5 分钟内自动接到新 topic 流量 |
| **Broker auto-create** | 本地 `KAFKA_AUTO_CREATE_TOPICS_ENABLE=true`，producer 第一次写新 topic 自动建；生产**强烈建议关闭**，需提前脚本预创建 |

> 因 worker `topicPattern()` 已自适应,**切换 mode 时 worker 不需要先升级也不需要重启**——这是相对传统消息系统切分流方案最大的简化。但 broker auto-create 在生产关闭时,**必须先预创建 topic**,否则 producer 会写失败堆积 outbox。

---

## 1. 选择目标 mode

| mode | 何时选 | 代价 |
|---|---|---|
| `SINGLE` | 单租户 / 总量小（< 10 万/天）/ 想关分流 | topic 数最少；任何租户高峰会争抢 base topic 分区 |
| `TENANT`（默认） | 多租户，存在大租户挤占小租户的风险 | topic 数 = 租户数 × 4（import/export/process/dispatch）；万级租户会让 broker 元数据膨胀 |
| `PRIORITY` | 业务有 HIGH/NORMAL/LOW 三档优先级且高优不能被低优阻塞 | topic 数 = 4 × 3 = 12（每个 worker 类型 3 个 priorityBand）；consumer group 需按 band 拆分配合才有效果 |

> 注意:**`TENANT` 和 `PRIORITY` 互斥**(`MqRoutingProperties.Mode` 是单值枚举),不能同时按 tenant 和 priority 分。需要双维度的话,要在代码里扩展（不在本 runbook 范围）。

---

## 2. SINGLE → TENANT 切换步骤

### 2.1 切换前预检（必做）

```bash
# 2.1.1 确认 worker 端订阅模式是 PATTERN（默认；FIXED 模式不会拾取新 topic）
grep -n "subscribe.*mode\|SUBSCRIBE_MODE" batch-worker-*/src/main/resources/application*.yml
# 期望：未显式设置 → 走 PATTERN 默认；或显式 PATTERN

# 2.1.2 列出当前已知租户（决定要预创建哪些 topic）
docker exec batch-postgres psql -U batch_user -d batch_platform \
  -c "SELECT DISTINCT tenant_id FROM batch.tenant_config WHERE status='ACTIVE' ORDER BY 1;"
```

### 2.2 预创建 topic（生产环境必做）

```bash
# 假设租户列表为 default-tenant tenant-a tenant-b
TENANTS=(default-tenant tenant-a tenant-b)
PARTITIONS=${KAFKA_PARTITIONS_DISPATCH:-40}
REPLICATION=${KAFKA_REPLICATION_FACTOR:-3}

for TYPE in import export process dispatch; do
  for TENANT in "${TENANTS[@]}"; do
    docker exec batch-kafka kafka-topics --bootstrap-server localhost:9092 \
      --create --if-not-exists \
      --topic "batch.task.dispatch.${TYPE}.${TENANT}" \
      --partitions "${PARTITIONS}" \
      --replication-factor "${REPLICATION}"
  done
done

# 验证
docker exec batch-kafka kafka-topics --bootstrap-server localhost:9092 --list \
  | grep -E "batch.task.dispatch.(import|export|process|dispatch).(default-tenant|tenant-a|tenant-b)"
```

> ⚠️ 新增租户时**必须**回到这一步给新租户预创建 topic,否则 producer 写失败、outbox 堆积。建议把这套脚本放到 `tenant_config` INSERT 的 ops 流程里,做成一键。

### 2.3 切换 producer mode

```bash
# 在部署平台 / .env.local 设置
BATCH_MQ_ROUTING_MODE=TENANT

# 重启 orchestrator（producer）
docker compose --profile apps up -d --force-recreate orchestrator
```

**worker 不需要重启**——`topicPattern()` 自动匹配新 topic。Kafka client 在 `metadata.max.age.ms`（默认 5 min）内重发现并拾取。

### 2.4 灰度验证（第 0-15 分钟）

```bash
# 2.4.1 producer 启动日志确认 mode 生效
docker compose logs --tail=200 orchestrator | grep -i "mq.routing\|topic resolver\|mode="

# 2.4.2 观察实际写入的 topic（5 min 内应看到 .{tenantId} 后缀）
docker exec batch-kafka kafka-topics --bootstrap-server localhost:9092 --list \
  | grep "batch.task.dispatch.import" | sort

# 2.4.3 老 base topic 的消息消费完后入流应停止；用 lag 命令确认
docker exec batch-kafka kafka-consumer-groups --bootstrap-server localhost:9092 \
  --describe --group batch-worker-import \
  | grep "batch.task.dispatch.import"

# 期望：新 topic LAG 持续刷新（worker 在消费），老 base topic LAG → 0 后保持不动
```

### 2.5 完成判据（第 15-30 分钟）

- 所有 `batch.task.dispatch.<type>.<tenant>` topic 都有消息流入
- 老 base topic 的 LAG = 0 且不再增长
- worker 没有 `unknown topic` / `topic_authorization_failed` ERROR
- Grafana 三块板（P99 / outbox 积压 / DL 量）无回归

---

## 3. SINGLE → PRIORITY 切换步骤

流程同 §2,只是预创建的 topic 后缀变成 `.high` / `.normal` / `.low`。

### 3.1 预创建

```bash
PARTITIONS=${KAFKA_PARTITIONS_DISPATCH:-40}
REPLICATION=${KAFKA_REPLICATION_FACTOR:-3}

for TYPE in import export process dispatch; do
  for BAND in high normal low; do
    docker exec batch-kafka kafka-topics --bootstrap-server localhost:9092 \
      --create --if-not-exists \
      --topic "batch.task.dispatch.${TYPE}.${BAND}" \
      --partitions "${PARTITIONS}" \
      --replication-factor "${REPLICATION}"
  done
done
```

### 3.2 切 mode

```bash
BATCH_MQ_ROUTING_MODE=PRIORITY
docker compose --profile apps up -d --force-recreate orchestrator
```

### 3.3 让 PRIORITY 真正发挥作用（关键）

仅切 producer mode 还不够 —— 默认所有 worker 共用一个 consumer group,高优消息和低优在 partition 级混合消费,**没有真正的优先隔离**。要发挥 PRIORITY 效果,还需要:

- 给高优 topic 分配独立 consumer group（如 `batch-worker-import-high`）+ 独立 worker pool
- 低优 worker pool 容量小一点,资源紧张时主动饿死低优而不影响高优
- 这部分需要代码 / 配置改造,**不在本 runbook 单纯切 mode 的范围**

只切 mode 而不分 consumer group 的话,效果约等于"把消息按 priority 散开到不同 partition"——对高优保障作用有限。

---

## 4. 回滚（任意 mode → SINGLE）

回滚 producer 是安全操作,因为 worker 端 PATTERN 同时匹配 base 和 base.*,新写入的消息会到 base topic、worker 直接接到。

```bash
BATCH_MQ_ROUTING_MODE=SINGLE
docker compose --profile apps up -d --force-recreate orchestrator
```

**老的 .{tenantId} / .{band} topic 中残留消息**:
- 不会自动消费（producer 不再写,但已写入的还在）
- 必须等 worker 消费完(LAG → 0)
- 用 `kafka-consumer-groups --describe` 确认 LAG = 0 后,topic 可以保留(下次切回 TENANT 还能用)或删除

```bash
# 确认老分流 topic LAG = 0
docker exec batch-kafka kafka-consumer-groups --bootstrap-server localhost:9092 \
  --describe --group batch-worker-import \
  | grep "batch.task.dispatch.import" | awk '$NF != 0 && $NF != "-"'
# 输出为空才能删

# 可选删除（生产请慎重，删完不能恢复）
docker exec batch-kafka kafka-topics --bootstrap-server localhost:9092 \
  --delete --topic "batch.task.dispatch.import.tenant-a"
```

---

## 5. 风险点速查

| 风险 | 触发条件 | 缓解 |
|---|---|---|
| **新租户 topic 没预创建 → outbox 堆积** | TENANT 模式 + broker auto-create 关闭 + 新增 tenant 没补 topic | 把 topic 预创建脚本接入 `tenant_config` 创建 ops 流程 |
| **broker 元数据膨胀** | TENANT 模式 + 万级租户 | 提前评估 broker 容量；考虑租户分组合并（如 `tenant_band` 自定义维度） |
| **worker 用 FIXED 订阅模式** | `WorkerKafkaSubscribeProperties.subscribeMode=FIXED` 又切到 TENANT/PRIORITY | worker 收不到分流 topic 的消息;预检 §2.1 必做 |
| **PRIORITY 模式但没分 consumer group** | 只切 mode 不改 worker 部署 | 实际优先级隔离弱;参见 §3.3 |
| **回滚后老 topic 残留消息漏消费** | 切回 SINGLE 后 producer 不再写、worker 也不再调度新消费 | LAG = 0 确认;否则手动指挥 worker 接管旧 topic 消费 |

---

## 6. 监控指标

切换期间应重点观察:

| 指标 | 来源 | 期望 |
|---|---|---|
| Kafka producer send rate（按 topic 拆） | Kafka JMX / Grafana Kafka panel | 切换后 5 min 内,新 topic 出现流量,老 base topic 流量归零 |
| Consumer lag（按 group + topic） | `kafka-consumer-groups --describe` | 老 topic lag → 0;新 topic lag 稳定（worker 跟得上） |
| Outbox publish_status=PUBLISHING 滞留 | Grafana 观测板 §1 outbox panel | 不应堆积;堆积说明 producer 写新 topic 失败(多半是 topic 没预创建) |
| Worker `messages_consumed_total{topic=...}` | micrometer | 切换后新 topic 计数器开始上升 |

---

## 7. 相关代码

- `batch-orchestrator/.../infrastructure/mq/BatchTopicResolver.java` — producer 端 topic 解析
- `batch-orchestrator/.../infrastructure/mq/KafkaOutboxPublisher.java` — 调 resolver 决定 send 目标
- `batch-orchestrator/.../config/MqRoutingProperties.java` — mode 枚举 + 配置 binding
- `batch-worker-core/.../support/AbstractTaskConsumer.java#topicPattern` — worker 端正则订阅
- `batch-worker-core/.../config/WorkerKafkaSubscribeProperties.java` — worker 端订阅模式（PATTERN/FIXED/TENANT_SCOPED）

## 8. 相关文档

- `docs/runbook/feature-switches.md` §3.3 — 开关速查
- `docs/architecture/rework-classification.md` Phase 2 第 3 项
- `docs/architecture/scalability-assessment.md` §6 路线图
