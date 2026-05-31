# Per-tenant Worker Pool 接入手册（Phase D）

> 大租户独立 worker pool：SLA / 故障 / 凭据物理隔离。
> 见 [`docs/plans/multi-tenant-isolation-plan-2026-05-31.md`](../plans/multi-tenant-isolation-plan-2026-05-31.md) §Phase D。

## 0. 核心结论：业务代码已 ready，本手册只做部署

按 tenant 过滤任务、Kafka 订阅 mode、producer topic 路由——**代码 2026-05-31 复核全部已实现**，
本 Phase 只补部署侧（Helm 模板 / topic 脚本 / 凭据 / 配额）。涉及代码文件：

| 能力 | 文件 |
|---|---|
| Worker 运行期拒绝跨租户任务 `acceptsConfiguredTenantScope()` | `batch-worker-core/.../AbstractTaskConsumer.java` |
| Kafka 订阅 mode（PATTERN / FIXED / TENANT_SCOPED）+ `tenantAllowlist` | `WorkerKafkaSubscribeProperties` |
| Producer SINGLE / TENANT / PRIORITY → topic 后缀 | `batch-orchestrator/.../mq/BatchTopicResolver.java` |

## 1. Topic 命名规约（权威）

orchestrator `BatchTopicResolver` 在 TENANT 模式下，在 base topic 后**追加 `.{tenantId}`**：

```
base:          batch.task.dispatch.{type}          # type = import|export|process|dispatch|spi
per-tenant:    batch.task.dispatch.{type}.{tenantId}
例：           batch.task.dispatch.import.bigcorp
```

> ⚠ 不是 `batch.task.dispatch.tenant.{tenantId}.{type}`。早期 plan 草稿写过那个形态，与
> `BatchTopicResolver`（`base + "." + tenantId`）和 worker 订阅正则 `^base(\.(tenantId))?$`
> 都对不上，会导致租户任务**根本投不进 / 订阅不到**。以本节为准。

`tenantId` 中 `[^a-zA-Z0-9._-]` 字符会被替换为 `_`（`BatchTopicResolver.safe()` 与
`init-tenant-topics.sh` 行为一致），否则 worker 订阅正则匹配不上。

## 2. 隔离三要素（缺一不可）

只配其中一两个会得到「名义隔离、实际仍混跑」的假象：

1. **Worker 订阅收窄**：`BATCH_WORKER_KAFKA_SUBSCRIBE_MODE=TENANT_SCOPED` +
   `BATCH_WORKER_KAFKA_TENANT_ALLOWLIST={tenantId}`。
   订阅正则变为 `^batch\.task\.dispatch\.{type}(\.({tenantId}|node\.{code}))?$`，
   不接其它租户后缀 topic。
2. **独立 consumer group**：`BATCH_WORKER_{TYPE}_CONSUMER_GROUP_ID=batch-worker-{type}-{tenant}`。
   **这是最容易漏的一步**——若沿用共享池的组名，Kafka 会把 base/各后缀分区在两个池之间
   rebalance，租户任务可能落到共享池、共享任务可能落到租户池，隔离失效。
3. **Producer 切 TENANT 模式**：orchestrator `BATCH_MQ_ROUTING_MODE=TENANT`
   （`batch-orchestrator/.../application.yml:177`，**默认已是 TENANT**）。
   只有这样该租户任务才会被发到 `base.{tenantId}` 后缀 topic。

> 第 4 层兜底：即便订阅/路由配错，`AbstractTaskConsumer.acceptsConfiguredTenantScope()`
> 在运行期仍会拒绝 `tenant-id` 不匹配的消息（`default-tenant` 通配除外）。这是 backstop，
> 不能替代上面三要素——否则任务被拒后只是进 DLQ，不会被正确的池消费。

## 3. 接入步骤

以租户 `bigcorp`、worker 类型 `import`/`export` 为例。

### 3.1 建 per-tenant topic

```sh
KAFKA_BOOTSTRAP_SERVER=kafka:29092 \
TENANTS=bigcorp \
WORKER_TYPES=import,export \
KAFKA_PARTITIONS_DISPATCH=20 \
KAFKA_TOPIC_REPLICATION_FACTOR=3 \
  sh scripts/data/init-tenant-topics.sh
# 产出：batch.task.dispatch.import.bigcorp / batch.task.dispatch.export.bigcorp
```

### 3.2 配 per-tenant 凭据 + 网络隔离（可选但推荐）

`values.yaml`：

```yaml
tenantIsolation:
  - tenantId: bigcorp
    secret:
      enabled: true
      stringData:
        BATCH_SFTP_PASSWORD: "..."        # 覆盖共享 Secret 同名 key
        BATCH_MINIO_SECRET_KEY: "..."
        BATCH_BUSINESS_DB_PASSWORD: "..." # 若该租户用专属业务库账号
    networkPolicy:
      enabled: true
      # 集群外 managed RDS/Kafka 时补 ipBlock：
      # extraEgress:
      #   - to: [{ ipBlock: { cidr: 10.20.0.0/16 } }]
```

> ResourceQuota 是 namespace 级、无 pod selector，**只在「该租户独占一个 namespace」**
> 部署形态下才真正只约束该租户。同 namespace 混部时它约束整个 namespace，因此默认关闭。

### 3.3 渲染 per-tenant worker pool

`values.yaml`：

```yaml
tenantWorkerPools:
  - tenantId: bigcorp
    workerType: import
    replicaCount: 3
    secretName: batch-platform-tenant-bigcorp-secret   # 引用 3.2 渲染的 Secret
  - tenantId: bigcorp
    workerType: export
    replicaCount: 2
    secretName: batch-platform-tenant-bigcorp-secret
```

```sh
helm upgrade --install batch-platform helm/batch-platform -n batch -f values.yaml
# 产出 Deployment/Service：
#   batch-platform-worker-import-bigcorp
#   batch-platform-worker-export-bigcorp
```

`worker-tenant.yaml` 自动注入第 2 节三要素中的 worker 侧两项（SUBSCRIBE_MODE / ALLOWLIST /
TENANT_ID / 独立 CONSUMER_GROUP_ID）。第 3 项（producer TENANT 模式）默认已开。

### 3.4 验证

```sh
# 订阅正则只含本租户
kubectl logs deploy/batch-platform-worker-import-bigcorp -n batch | grep -i "topicPattern\|subscribed"
# 独立 consumer group 已注册
kafka-consumer-groups.sh --bootstrap-server kafka:29092 --describe \
  --group batch-worker-import-bigcorp
# 投一个 bigcorp import 任务 → 只被 *-bigcorp pod 消费；投 default-tenant 任务 → 共享池消费
```

## 4. 故障演练（出口标准）

**目标：示范租户 worker 挂 → 其它租户无感。**

```sh
# 1. 基线：default-tenant + bigcorp 各跑一批 import 任务，均成功。
# 2. 杀 bigcorp 池：
kubectl scale deploy/batch-platform-worker-import-bigcorp -n batch --replicas=0
# 3. 再投 default-tenant import 任务 → 仍被共享池正常消费（无感）。
# 4. bigcorp 任务在 batch.task.dispatch.import.bigcorp 积压（lag 上涨），不影响他租户。
# 5. 恢复：
kubectl scale deploy/batch-platform-worker-import-bigcorp -n batch --replicas=3
#    积压被追平。
```

> 关键断言：步骤 3 共享池任务延迟 / 成功率不受 bigcorp 池宕机影响——这正是独立
> consumer group + 独立 topic 后缀带来的故障隔离。

## 5. 出口检查表

- [ ] 1 个示范大租户独立 worker pool 跑通（§3）
- [ ] Helm 模板 `templates/worker-tenant.yaml`（覆盖 import 等全类型）
- [ ] Kafka topic 自动创建脚本 `scripts/data/init-tenant-topics.sh` + 命名规约（§1）
- [ ] 本手册 `docs/runbook/per-tenant-worker-onboarding.md`
- [ ] 故障演练：示范租户 worker 挂 → 其它租户无感（§4）
- [ ] 自托管租户 Kafka SASL/SCRAM ACL 已 apply,且跨租户 topic 访问被拒(§5.1)

## 5.1 Kafka SASL/SCRAM ACL (ADR-035 Phase 3,自托管 worker 必须)

主项目可信 worker 走共享 PLAINTEXT(集群内信任域),但**租户自托管 worker** 跑在租户自己的 K8s 里,Kafka 必须开 SASL/SCRAM,且 ACL 必须只允许该租户访问自己的 topic/group。否则任一租户拿到 bootstrap 地址就能消费所有 dispatch topic。

**onboarding 步骤**(每个新自托管租户跑一次):

```sh
# 1. 在 Kafka broker 容器里跑 ACL 初始化(需要 admin-client.properties 有管理员 SASL 凭据)
KAFKA_BOOTSTRAP_SERVER=kafka:29092 \
ADMIN_CONFIG=/opt/kafka/config/admin-client.properties \
TENANT_ID=bigcorp \
TENANT_PASSWORD="$(openssl rand -base64 32)" \
WORKER_TYPES=import,export,process \
  sh scripts/data/init-tenant-kafka-acl.sh

# 2. 脚本输出 sasl.jaas.config 一行,拷到 K8s Secret(或租户自己环境变量)
kubectl -n batch create secret generic bigcorp-kafka-sasl \
  --from-literal=BATCH_KAFKA_PROTOCOL=SASL_SSL \
  --from-literal=BATCH_KAFKA_SASL_MECHANISM=SCRAM-SHA-512 \
  --from-literal=BATCH_KAFKA_SASL_JAAS='org.apache.kafka.common.security.scram.ScramLoginModule required username="batch-tenant-bigcorp" password="...";'

# 3. 租户的 worker deployment envFrom 该 secret;SDK 自动透传到 KafkaConsumer
```

**验证隔离**:

```sh
# 用 tenant-A 的 credential 试着消费 tenant-B 的 topic,应该被 ACL 拒绝(TopicAuthorizationException)
kafka-console-consumer.sh --bootstrap-server kafka:29092 \
  --consumer.config /tmp/tenant-a-client.properties \
  --topic batch.task.dispatch.import.tenant-b --from-beginning
# → ERROR org.apache.kafka.common.errors.TopicAuthorizationException
```

**轮换密码**:重新跑步骤 1(同名 user --add-config 会覆盖 SCRAM credential),更新 Secret,触发 worker 重启即生效。ACL 不动。

**密码轮换告警**:轮换后旧 password worker pod 会立刻断 SASL 鉴权失败;建议先滚动新 Secret + 灰度重启,再 alter SCRAM credential。

## 6. 范围边界（不做）

- **不**自研 K8s 调度 / 挑机器（ADR-027：「挑 worker」√ vs「挑机器」✗）。HPA/KEDA/ResourceQuota
  都用 K8s 原生能力，不引入自研调度器。
- **不**为每租户独立 DB / schema（见 plan §6.1 / §6.3 否决）。物理库隔离诉求走 SDK 自托管（Phase B）。
- 此处仅做**部署形态**隔离，业务隔离仍由 RLS（Phase A）+ tenant_id 列保证。
