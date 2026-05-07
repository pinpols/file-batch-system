# 基础依赖部署方案（Postgres / Kafka / MinIO / Redis）

应用层（orchestrator / trigger / console-api / worker × 3）完全无状态，通过 `batch-defaults.yml` 的
几个环境变量接入 4 个基础服务。Helm chart（`helm/batch-platform/templates/`）只部署应用模块，
基础服务按环境自行选型。本文覆盖本地开发和生产的所有主流部署方案。

## 本地开发

### 默认：全 Docker（`docker-compose.yml`）

| 服务 | 镜像 | 宿主机端口 | 容器端口 |
|---|---|---|---|
| Postgres | `postgres:${POSTGRES_IMAGE_TAG}` | 15432 | 5432 |
| Kafka (KRaft) | `apache/kafka:${KAFKA_IMAGE_TAG}` | 19092 | 9092 |
| MinIO | `minio/minio:${MINIO_IMAGE_TAG}` | 19000 / 19001 | 9000 / 9001 |
| Redis | `redis:7.4` | 16379 | 6379 |

另有 2 个 init 容器：`batch-kafka-init` 建 topic、`batch-minio-init` 建 bucket。一键启停：

```bash
make dev-start   # 包装 scripts/local/start-all.sh：up -d 基础依赖 + 启 7 个 app JVM（含 batch-worker-process）
make dev-stop    # 仅停 app JVM；基础依赖保持 up
```

**优点**：团队环境一致、版本锁定、一键启停清理、跨平台。
**代价**：Docker Desktop 驻留 2-4GB 内存，宿主机 ↔ 容器 IO 有 10-30% 惩罚。

### 可选：Postgres/Redis 裸机 + Kafka/MinIO Docker（混合模式）

适合场景：Docker Desktop 吃内存严重、集成测试 Postgres IO 密集、性能敏感开发。

| 服务 | 建议 | 理由 |
|---|---|---|
| Postgres | 裸机（`brew install postgresql@16`） | 访问最频繁，fsync 裸跑快 20-30% |
| Redis | 裸机（`brew install redis`） | 极轻，纯内存 |
| Kafka | 保留 Docker | KRaft 配置复杂，镜像封装好 |
| MinIO | 保留 Docker | Bucket 初始化脚本已自动化 |

预期收益：启动快 ~10-15s，Docker 内存占用降 30%，Postgres IO 提升 20-30%。
代价：新人多一步 setup，升级 Postgres 版本要双路径同步。

本仓库当前不自带混合模式脚本；如需启用，建议自行在 `.env.local.bare` 覆盖 DB/Redis 连接串
并直接跳过 docker-compose 里对应的 service。

### 不推荐：全裸机

Kafka KRaft 裸装维护成本（quorum controller + log dir + JVM 参数），收益不抵投入。

---

## 生产部署

应用层已完全解耦，基础服务选型只需对应改 `.env.prod` / Helm values 里几个变量：

- `BATCH_PLATFORM_DB_URL` / `BATCH_PLATFORM_DB_USERNAME` / `BATCH_PLATFORM_DB_PASSWORD`
- `BATCH_KAFKA_BOOTSTRAP_SERVERS`
- `BATCH_MINIO_ENDPOINT` / `BATCH_MINIO_ACCESS_KEY` / `BATCH_MINIO_SECRET_KEY`
- `BATCH_REDIS_HOST` / `BATCH_REDIS_PORT`

### 方案 1：全托管云服务（云原生首选）

| 服务 | AWS | GCP | Azure | 阿里云 |
|---|---|---|---|---|
| Postgres | RDS / Aurora | Cloud SQL | Azure Database | RDS for PostgreSQL |
| Kafka | MSK | Pub/Sub 或 Confluent Cloud | Event Hubs（Kafka API） | MessageQueue for Kafka |
| 对象存储 | S3 | GCS | Blob Storage | OSS |
| Redis | ElastiCache | Memorystore | Azure Cache for Redis | 云数据库 Redis |

**优点**：零运维、SLA 99.99%、按量付费、与应用完全解耦。
**缺点**：云厂商绑定；大规模时贵（MSK 特别贵）；出口流量费；**监管/金融/政企通常不允许**。

### 方案 2：K8s Operator（云原生自建）

| 服务 | Operator |
|---|---|
| Postgres | **Crunchy / CloudNativePG / Zalando** |
| Kafka | **Strimzi**（事实标准） |
| MinIO | **MinIO Operator** |
| Redis | **Redis Operator**（Opstree / Spotahome） |

跟应用同一套 K8s 集群，Helm 管理。

**优点**：与应用同栈（监控/日志/secret/网络策略统一）、HA 能力完整、云中立、可混合云。
**缺点**：运维门槛（K8s + 各服务专业知识）、持久化存储必须靠 CSI driver、Kafka 再平衡需要懂原理。

### 方案 3：独立 VM / 物理机（传统运维）

Postgres / Kafka / Redis 跑在单独 VM 集群，**不在 K8s 里**；应用 K8s 通过网络访问。

**优点**：成熟（DBA/SRE 传统流程）、性能可预测、适合超大规模。
**缺点**：运维双轨、弹性差。

### 方案 4（反模式）：docker-compose 在单台 VM

不推荐生产使用，仅适合 PoC / 内测：单点、无 HA、扩容难、备份/监控/升级全靠手工。
唯一优点是跟本地开发同构。

---

## 推荐路线

| 场景 | 方案 |
|---|---|
| 初创 / 小规模（< 100 万任务/天） | **方案 1 云托管**。运维 = 0，启动最快 |
| 中大规模 / 需要云中立 | **方案 2 K8s Operator**。跟 app 同栈，全 Helm 托管 |
| 金融 / 政企 / 信创 | **方案 3**：Postgres 用 DBA VM 集群（通常国产化）；Kafka 独立机房；应用只在 K8s |
| PoC / 内测 | 方案 4（过渡用） |

## 上云 vs 传统部署 决策框架

### 3 个决定性问题

每条都是硬约束，答案直接决定选型。

**Q1：行业性质 + 监管要求？**
- **金融 / 银行 / 保险 / 证券 / 政企 / 信创** → 99% 走传统部署（方案 3）。
  监管要求数据不出 IDC、国产化、等保三级以上，公有云基本不被允许
- **互联网 / SaaS / 制造业 / 一般企业** → 云托管（方案 1）最划算
- **有跨境业务** → 各地合规要求不同，需混合

**Q2：是否具备 DBA / SRE / K8s 运维能力？**
- 没有 → 云托管（方案 1），不要想自建
- 有基础 K8s 团队、DBA 弱 → K8s Operator（方案 2）
- 有成熟 DBA + 传统运维流程 → VM / 物理机（方案 3）

**Q3：现有基础设施？**
- 已经在 AWS / 阿里云 → 直接云托管
- 已经有 IDC + K8s → K8s Operator
- 已经有 IDC + 传统 DBA → VM 部署
- 白地新建 → 看 Q1 + Q2

### 本项目架构的部署倾向信号

代码里以下特征，说明架构设计时就考虑了**企业内部 / 金融场景**：

| 特征 | 来源 | 指向 |
|---|---|---|
| 多租户隔离 + KMS 密钥管理 + 审计日志 + HTTP MDC traceId | `ConsoleTenantGuard`、`BatchSecurityProperties` | 合规驱动开发 |
| 批次日（batch_day）+ catch-up 审批 + SLA 监控 + Outbox | V32 迁移脚本、`ConsoleApprovalController` | 银行 / 金融批处理业务域 |
| Quartz JDBC 集群模式 + ShedLock + 分布式一致性 | `application.yml` Quartz 配置、`V30__create_shedlock.sql` | 企业级可靠性要求 |
| 完整的 `security-scan.md` / `observability-stack.md` / `observability-stack.md` | `docs/runbook/` | 严肃 SRE 思维 |

**综合判断**：本项目大概率定位于**中大型企业内部批处理平台**，**传统部署（方案 3）** 是默认
落地姿势——基础服务（Postgres / Kafka / Redis）走独立 VM 集群或 DBA 自管，应用层（7 个 jar）
走 K8s 滚动升级。

### 4 种典型部署组合（按实际场景）

| # | 场景 | 基础服务部署 | 应用部署 | 说明 |
|---|---|---|---|---|
| 1 | **互联网 SaaS，初创** | RDS + MSK + S3 + ElastiCache | K8s（Helm）| 零运维启动；成本随规模递增 |
| 2 | **互联网 SaaS，成熟** | K8s Operator（Strimzi + CloudNativePG + MinIO Op）| K8s（Helm）| 云中立、自托管，避开 MSK 昂贵 |
| 3 | **金融 / 银行 / 政企** | **VM 集群 +（常）国产数据库**（达梦 / openGauss / Oracle）| K8s（Helm）| 数据不出 IDC；DBA 传统运维；等保合规 |
| 4 | **制造业 / 传统企业** | 单台 VM 上 docker-compose 或裸装 | VM + systemd 或 K8s（若有基础） | 规模小，运维能力有限；PoC 常态 |

### 如何判断自己属于哪一种

1. **客户或业务是否在强监管行业**（金融 / 医疗 / 政府）？
   - 是 → 组合 3
   - 否 → 看下一步
2. **基础设施已经在哪？**
   - 公有云 → 组合 1 或 2（看规模）
   - 自建 IDC / 私有云 → 组合 2 或 3
   - 还在决策阶段 → 按成本 / 团队能力选
3. **日任务量、数据量级？**
   - `< 100 万/天，< 100GB` → 组合 1（云托管最省事）
   - `100 万 ~ 1000 万/天` → 组合 2 或 3
   - `> 1000 万/天 / TB 级数据` → 组合 3（云托管在这个量级非常贵）

### 决策时要回答

落地前请先回答这三个问题：
1. **行业是什么？**（银行？保险？互联网？制造？SaaS？）
2. **DBA / K8s 团队现状？**（有 / 没有 / 半成熟）
3. **现有基础设施？**（公有云 / IDC / 混合 / 白地新建）

三个答案敲定后，参照上面"4 种典型部署组合"表就能得出具体方案，避免在选型阶段反复折腾。

---

## 生产硬约束（与应用架构对齐）

**不论选哪种方案，下列配置必须满足**，否则应用架构的可靠性保证（Outbox / At-Least-Once /
分布式锁 / 任务状态主机）都会被破坏：

| 基础服务 | 必需配置 | 违反后果 |
|---|---|---|
| **Postgres** | `fsync=on`、`synchronous_commit=on`、开启 WAL 归档 | Outbox 事件和任务状态丢失 → 任务静默消失 |
| **Postgres** | 连接池上游建议 PgBouncer `transaction` 模式 | Hikari 连接爆炸 |
| **Kafka** | `replication-factor≥3`、`min.insync.replicas=2`、`unclean.leader.election.enable=false` | `acks=all` 配合才生效；否则消息丢失 |
| **Kafka** | Topic 分区数足够（`batch.task.dispatch.*` 每 topic ≥ 6 分区起步） | worker 并发吃不满 |
| **Redis** | 持久化开启（AOF everysec 或 RDB 15min），主从 + 哨兵 / Redis Sentinel | ShedLock 锁状态丢失 → 触发重复调度 |
| **MinIO / S3** | 开启版本管理 + 对象锁（Object Lock） | 批处理文件被误覆盖后无法回溯 |

## 接入配置速查

统一由 `batch-common/src/main/resources/batch-defaults.yml` 消费环境变量：

```yaml
spring:
  datasource:
    url: ${BATCH_PLATFORM_DB_URL}
    username: ${BATCH_PLATFORM_DB_USERNAME}
    password: ${BATCH_PLATFORM_DB_PASSWORD}
  kafka:
    bootstrap-servers: ${BATCH_KAFKA_BOOTSTRAP_SERVERS}
  data:
    redis:
      host: ${BATCH_REDIS_HOST}
      port: ${BATCH_REDIS_PORT}
batch:
  storage:
    minio:
      endpoint: ${BATCH_MINIO_ENDPOINT}
      access-key: ${BATCH_MINIO_ACCESS_KEY}
      secret-key: ${BATCH_MINIO_SECRET_KEY}
      bucket: ${BATCH_MINIO_BUCKET}
```

部署时填充这些变量即可，应用层不区分服务是 RDS / MSK / Operator / VM / 自建。

### 示例：AWS 全托管模板

```bash
BATCH_PLATFORM_DB_URL=jdbc:postgresql://batch-prod.abc123.us-east-1.rds.amazonaws.com:5432/batch_platform
BATCH_KAFKA_BOOTSTRAP_SERVERS=b-1.batchmsk.abc.kafka.us-east-1.amazonaws.com:9098,b-2...:9098
BATCH_MINIO_ENDPOINT=https://s3.us-east-1.amazonaws.com        # 或自托管 MinIO 的实际地址
BATCH_REDIS_HOST=batch-prod.abc.cache.amazonaws.com
BATCH_REDIS_PORT=6379
```

### 示例：K8s Operator（Strimzi + CloudNativePG）模板

```bash
BATCH_PLATFORM_DB_URL=jdbc:postgresql://batch-pg-cluster-rw.batch-system.svc:5432/batch_platform
BATCH_KAFKA_BOOTSTRAP_SERVERS=batch-kafka-bootstrap.kafka-system.svc:9092
BATCH_MINIO_ENDPOINT=http://minio.storage-system.svc:9000
BATCH_REDIS_HOST=batch-redis.cache-system.svc
BATCH_REDIS_PORT=6379
```

### 示例：本地开发（docker-compose 默认）

```bash
BATCH_PLATFORM_DB_URL=jdbc:postgresql://localhost:15432/batch_platform
BATCH_KAFKA_BOOTSTRAP_SERVERS=localhost:19092
BATCH_MINIO_ENDPOINT=http://localhost:19000
BATCH_REDIS_HOST=localhost
BATCH_REDIS_PORT=16379
```

---

## 应用云原生能力与水平扩容评估

选型时常见疑问："我这套应用能不能上 K8s 直接跑？能水平扩吗？" 本节逐项审计给出明确答案。

### 结论先说

**完全支持云原生，所有模块都设计为无状态水平扩容**。有状态的东西全部外置到 Postgres / Kafka /
Redis / MinIO，应用进程本身零本地状态。审计证据见下表。

### 云原生 10 项能力清单（实测）

| # | 能力 | 证据位置 | 状态 |
|---|---|---|---|
| 1 | K8s liveness / readiness 探针 | `batch-defaults.yml` + 所有 Helm templates | ✅ |
| 2 | JWT 无状态会话（Console 层） | `ConsoleSecurityConfiguration` `SessionCreationPolicy.STATELESS` | ✅ |
| 3 | 分布式锁 ShedLock（协调单例任务） | `ShedLockProviderFactory` + `V30__create_shedlock.sql` | ✅ |
| 4 | Quartz JDBC 集群模式（多实例抢占） | `application.yml` `isClustered: true` | ✅ |
| 5 | Worker 注册表（DB 驱动服务发现） | `worker_registry` 表 + `AbstractWorkerLoop` 心跳 | ✅ |
| 6 | Outbox 分片（多副本并行消费） | `BATCH_OUTBOX_SHARD_TOTAL` / `SHARD_INDEX` 配对 | ✅ |
| 7 | Kafka consumer group 自动再平衡 | `@EnableKafka` + 每 worker 独立 group ID | ✅ |
| 8 | 优雅停机 + drain in-flight 任务 | `server.shutdown=graceful` + `awaitDrain(Duration)` | ✅ |
| 9 | SSE Pub/Sub 广播（不需 sticky session） | `ConsoleRealtimeRedisPublisher` Redis 分发 | ✅ |
| 10 | Helm 支持 replicaCount + HPA | `helm/batch-platform/values.yaml` + worker HPA 模板 | ✅ |

### 各模块水平扩容机制

| 模块 | 扩容方式 | 关键机制 |
|---|---|---|
| **orchestrator** | 分片复制（`shard-total` 控制并行度） | 每 Pod 按 `SHARD_INDEX` 只处理自己那片 outbox；ShedLock 协调"单例"定时任务 |
| **trigger** | 完全对等复制 | Quartz 集群模式抢占触发；多实例同时跑不会重复执行 job |
| **console-api** | 完全无状态，任意副本数 | JWT 验签无共享状态；SSE 推送通过 Redis Pub/Sub 广播到所有实例 |
| **worker-import / export / process / dispatch** | **HPA 已配置**，按 Kafka lag 或 CPU 自动伸缩 | Kafka partition 自动再平衡；新 Pod 加入 consumer group 自动分到任务 |

### 多副本防重复机制（已全部落地）

| 机制 | 生效模块 | 实现 |
|---|---|---|
| **Outbox 分片** | orchestrator | Helm template 为 StatefulSet，Pod 名带 ordinal（`batch-orchestrator-0..N-1`），`entrypoint.sh` 提取 ordinal 注入 `BATCH_OUTBOX_SHARD_INDEX`；`BATCH_OUTBOX_SHARD_TOTAL` 由 `orchestrator.replicaCount` 模板化注入 |
| **ShedLock** | orchestrator（12 个 `@Scheduled`）、console-api | 每个业务 `@Scheduled` 方法挂 `@SchedulerLock`，Redis 存锁；确保同一时刻只有一个 Pod 执行 |
| **Quartz 集群** | trigger | `isClustered: true`，JDBC JobStore 协调多实例抢占；多 trigger Pod 不会重复触发同一个 job |
| **Kafka consumer group** | worker-* | 每个 worker 模块一个独立 group ID，K8s 扩容时自动 rebalance partition |
| **PodDisruptionBudget** | 所有 replicaCount≥2 的模块 | Helm `pdb.yaml` 模板，默认 `maxUnavailable: 1`，K8s 节点维护时至少保留 N-1 个 Pod 在线 |

### 代码强制约束：新加 `@Scheduled` 必须挂防重

orchestrator / console-api 新增 `@Scheduled(fixedDelay=...)` 方法时，**必须**二选一：

- **方式 A：挂 ShedLock**（绝大多数场景）
  ```java
  @Scheduled(fixedDelayString = "${batch.xxx.poll-interval-millis:30000}")
  @SchedulerLock(name = "xxx-scheduler", lockAtMostFor = "5m", lockAtLeastFor = "10s")
  public void pollXxx() { ... }
  ```

- **方式 B：用 shard-index 手动分片**（仅 Outbox 这类高频、不能互斥执行的场景）
  ```java
  @Value("${batch.outbox.shard-index}")  int shardIndex;
  @Value("${batch.outbox.shard-total}")  int shardTotal;
  // SQL WHERE id % :shardTotal = :shardIndex
  ```

**漏挂后果**：N 副本同时跑 = N 倍无效工作，审计日志/第三方回调出现重复。

### 若只打算部署 1 个副本

本项目不强制多副本，单副本部署也完全跑得起来：
- `BATCH_OUTBOX_SHARD_TOTAL=1`（默认值）
- orchestrator / trigger / console-api：`replicaCount: 1`
- worker-*：`replicaCount: 1`（HPA 下限设 1）

所有分布式协调机制在单副本下退化为无操作（ShedLock 永远是自己拿锁），零开销。

## 相关文档

- [本地开发指南](local-development.md) — 本地联调流程
- [本地环境变量](local-development.md) — `.env.local` 字段说明
- [Docker 部署基线](docker-deployment.md) — `docker-compose.*.yml` 编排
- [滚动升级 worker](rolling-upgrade-workers.md) — 生产 worker 灰度策略
- [orchestrator 迁移到 StatefulSet](orchestrator-statefulset-migration.md) — **仅首次** 升级到新 chart 时的一次性操作步骤
- [弹性伸缩策略](autoscaling-strategy.md) — 6 模块的扩缩机制差异 + 合理性评估 + 何时重新设计
