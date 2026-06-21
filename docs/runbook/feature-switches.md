# Phase 2 能力开关运维手册

> 汇总 2026-04-25 完成的 5 项 Phase 2 scaffolding 涉及的 6 个开关：配置 key、实际默认、启用条件、风险、验证、回滚。
>
> 配套阅读：[`docs/architecture/rework-classification.md`](../architecture/rework-classification.md) Phase 2 章节、[`docs/coding-conventions.md` §13.3](../coding-conventions.md) 配置归属决策。

---

> **配置优先级**：显式环境变量 > docker-compose `:-` 兜底 > application.yml `${VAR:fallback}` > Java 字段默认（`@Data` 字段初始化值，兜底中的兜底）。
>
> **本文档以代码 + yml 为权威**。`rework-classification.md` Phase 2 表格用作交叉对照（已于 2026-04-25 与本文档对齐）。

---

## 1. 开关索引

| 配置 key | 模块 | 实际默认 (jar 直跑) | docker-compose 默认 | 风险等级 | env 变量 |
|---|---|---|---|---|---|
| `batch.console.read-replica.enabled` | console-api | **true**（yml fallback / compose / .env.example 一致） | **true** | 🟢 低 | `BATCH_CONSOLE_READ_REPLICA_ENABLED`；测试在 `application-test.yml` 覆盖为 `false` |
| `batch.scheduler.worker-cache.enabled` | orchestrator | **true** | **true**（继承 yml） | 🟢 低 | `BATCH_SCHEDULER_WORKER_CACHE_ENABLED` |
| `batch.mq.routing.mode` | orchestrator | **TENANT** | **TENANT**（继承 yml） | 🟡 中 | `BATCH_MQ_ROUTING_MODE` |
| ~~`batch.trigger.quartz-datasource.enabled`~~ | ~~trigger~~ | **已移除**（2026-04-25 清理 Phase 2 半成品） | — | — | — |
| `batch.quota.runtime-store` | orchestrator | **redis** | **redis** | 🟢 低 | `BATCH_QUOTA_RUNTIME_STORE` |
| `batch.quota.snapshot.enabled` | orchestrator | **true** | **true** | 🟢 低 | `BATCH_QUOTA_SNAPSHOT_ENABLED` |
| `batch.worker.report-outbox.enabled` | import/export/process/dispatch worker | **false** | **false** | 🟡 中 | `BATCH_WORKER_REPORT_OUTBOX_*`：默认 **`storage=PLATFORM_PG`**（平台表 `batch.worker_report_outbox`，Flyway V96）；**`SQLITE`** 时需 PVC 指向 `sqlite-path` |
| `batch.worker.lease.renew-batch-max-items` | worker（ADR-016） | **256** | **256**（继承 yml） | 🟢 低 | `BATCH_WORKER_LEASE_RENEW_BATCH_MAX_ITEMS`：单 `renew-batch` HTTP 最多携带任务数，超出自动拆单 |
| `batch.datasource.business.routing.enabled` | import/export/process worker | **false**（单片无损，全租户落 shard-0=现库） | **false** | 🟡 中 | `BATCH_DATASOURCE_BUSINESS_ROUTING_ENABLED`；开后按 `placement-source`（CONFIG/TABLE）+ `shards[*]` 路由,详见 [`biz-tenant-routing.md`](./biz-tenant-routing.md) §8 |
| `batch.datasource.business.routing.placement-source` | 同上 | **CONFIG**（hash+silo） | **CONFIG** | 🟡 中 | `BATCH_DATASOURCE_BUSINESS_ROUTING_PLACEMENT_SOURCE`=CONFIG/TABLE；TABLE 读 `batch.business_tenant_placement`（在线维护,见 console `/api/console/ops/tenant-placements`） |
| ~~`batch.trigger.async-launch.enabled`~~ | ~~trigger + orchestrator~~ | **已移除**（2026-05-02 异步路径固化，同步 HTTP 桥删除） | — | — | — |
| `batch.resource-scheduler.default-exceeded-strategy` | orchestrator | **QUEUE_DEFER**（有界队列+背压，洪峰不误拒） | **QUEUE_DEFER** | 🟡 中 | `BATCH_RESOURCE_SCHEDULER_DEFAULT_EXCEEDED_STRATEGY`=QUEUE_DEFER/REJECT；ADR-042 Phase2.3 **改了平台默认行为**（旧默认硬拒 REJECT）→ 设 `REJECT` 可回退。仅作用于未显式配 `exceeded_strategy` 的租户，显式配的以租户策略为准 |
| `batch.worker.import.scanner.done-file-format` | worker-import | **MARKER** | **MARKER** | 🟢 低 | `BATCH_WORKER_IMPORT_SCANNER_DONE_FILE_FORMAT`=MARKER/JSON；JSON 即 sidecar manifest 强校验（#570），配合 `batch-manifest-enabled` |
| `batch.worker.import.scanner.done-file-suffix` | worker-import | **`.done`** | **`.done`** | 🟢 低 | `BATCH_WORKER_IMPORT_SCANNER_DONE_FILE_SUFFIX`；done 文件后缀可配（#569） |
| `batch.worker.import.scanner.batch-manifest-enabled` | worker-import | **false** | **false** | 🟡 中 | `BATCH_WORKER_IMPORT_SCANNER_BATCH_MANIFEST_ENABLED`；开后扫描期强校验批次清单（文件完整性，#570）|
| `batch.file-governance.arrival.require-verified` | orchestrator | **false** | **false** | 🟡 中 | `BATCH_FILE_GOVERNANCE_ARRIVAL_REQUIRE_VERIFIED`；开后到达组要求文件已校验通过才放行（#570）|

> 风险等级判定：🔴 高 = 启用前需起独立基础设施，否则启动失败；🟡 中 = 启用后行为变化明显，需要监控验证；🟢 低 = fail-open 兜底，故障自动降级。
>
> **per-template / per-channel 开关不在此表**：ADR-041 的 trailer 笔数校验、控制金额对账、出站 trailer、投递后回读(#578/580/582/584)是模板/渠道级配置(`trailer_template` / control-total rule / `readback_verify_enabled`),归 [`../design/file-pipeline-design.md`](../design/file-pipeline-design.md);本表只收全局 yml/env 开关。

### 1.1 Fail-open 速查（代码核实，2026-04-26）

| 开关 | Fail-open 强度 | 故障场景 | 行为 | 副作用 |
|---|---|---|---|---|
| `read-replica.enabled` | 🟡 中 | 从库 SQLException | 失败 3 次 → quarantine 30s → 静默走主库；期满自动探活 | 主库压力上升；read-after-write 一致性同步生效 |
| `worker-cache.enabled` | 🟢 强 | Redis 异常 / 反序列化失败 | `catch Exception` → 直通 DB loader + WARN | 派发延迟略增（DB query），业务不受影响 |
| `mq.routing.mode` | — | 无故障场景 | — | 切换需走灰度 SOP（[`mq-topic-routing-rollout.md`](./mq-topic-routing-rollout.md)） |
| `quota.runtime-store=redis` | 🟡 中 | Redis `DataAccessException` | `catch` → `ResourceCheck.allow()`（**放行**）+ WARN | **限流功能等同关闭**：长期 Redis 故障会让大租户吃掉小租户配额 |
| `quota.snapshot.enabled` | 🟢 强（局部） | 单租户 snapshot 失败 | per-tenant `catch` → 跳过该租户继续下一个 | 该租户审计数据漏一个周期，下次自然恢复 |
| `report-outbox.enabled` | — | Outbox 写入失败（磁盘/PG） | `enqueue` 失败 → `report` 继续抛异常（与未开 outbox 相同） | **`PLATFORM_PG`**：依赖 orchestrator 已迁移 V96；**`SQLITE`**：须持久卷 |

> "强 fail-open"=故障时业务完全不受影响；"中 fail-open"=故障时业务继续但行为/语义变化，需运维监控。

---

## 2. 默认开关状态 + 开启建议（按部署形态）

> **原则**：5 个 P2 开关的 yml fallback 全部为生产推荐值（read-replica=true / worker-cache=true / mq.routing=TENANT / quota.runtime-store=redis / quota.snapshot=true），所有开关都有 fail-open 兜底（详见 §1.1）。**多数场景"全部默认"即可**，下表只列需要显式覆盖的场景。

| 部署形态 | 业务量级 | 推荐覆盖（在 .env 显式设） | 理由 |
|---|---|---|---|
| **本地 IDE 直跑** | 极小 | 无（全默认） | 未起 replica 时 read-replica fail-open 仅前几次 WARN 后静默；嫌噪音可设 `BATCH_CONSOLE_READ_REPLICA_ENABLED=false` |
| **本地 docker-compose** | < 1 万/天 | 无（全默认） | compose 默认起 PG / Redis / Kafka；可 `--profile replica` 起从库让 read-replica 真路由 |
| **单机生产** | < 100 万/天 | 无（全默认） | 单机 PG 不起 replica → `BATCH_CONSOLE_READ_REPLICA_ENABLED=false` 减 WARN 噪音 |
| **中等生产** | 100 万 ~ 1000 万/天 | 无（全默认） | 5 项默认值即为本量级目标配置 |
| **海量** | > 1000 万/天 | `BATCH_MQ_ROUTING_MODE=PRIORITY` | TENANT topic 数随租户线性膨胀；切 PRIORITY 收敛到 HIGH/NORMAL/LOW 三 topic（详见 §3.3 切换灰度） |
| **测试 / E2E** | — | `application-test.yml` 已覆盖 `read-replica=false` + `worker-cache=false` + `file-governance.*=false` + 后台调度全关 | 测试不起 replica；关后台 scheduler 防 timing flake |

**何时关某个开关**：

| 想关 | 设置 | 何时这么干 |
|---|---|---|
| read-replica | `BATCH_CONSOLE_READ_REPLICA_ENABLED=false` | 没起 PG 从库 / 想避免 WARN 日志 |
| worker-cache | `BATCH_SCHEDULER_WORKER_CACHE_ENABLED=false` | Redis 抖动期间想完全直通 DB / 调试派发延迟问题 |
| quota Redis | `BATCH_QUOTA_RUNTIME_STORE=database` | Redis 长期故障 / 想看 PG 行锁瓶颈复现 |
| quota snapshot | `BATCH_QUOTA_SNAPSHOT_ENABLED=false` | 不需要审计 quota 历史时减 PG 写压力 |
| mq routing | `BATCH_MQ_ROUTING_MODE=SINGLE` | 单租户场景 / 回退老 worker 兼容期 |

---

## 3. 逐项详述

### 3.1 `batch.console.read-replica.enabled`

**作用**：console-api 的 `@Transactional(readOnly = true)` 查询路由到从库 Hikari 连接池；写事务和无事务调用走主库。

**默认**：
- application.yml fallback：`true`
- docker/compose/app.yml：`true`（`${BATCH_CONSOLE_READ_REPLICA_ENABLED:-true}`）
- `.env.example`：`true`
- 测试 `application-test.yml`：`false`（测试容器不起 replica）

**配套依赖**：
- 必须启动 PG 从库（`docker compose --profile replica up -d postgres-replica`）
- 必须配 `BATCH_CONSOLE_PRIMARY_URL` / `BATCH_CONSOLE_REPLICA_URL` 等 6 项 DB 凭证

**风险**：
- 从库异常退出 → 🟡 **中 fail-open**：`ReadReplicaRoutingDataSource` 在从库 SQLException 时降级走主库；连续失败 ≥ `failureThreshold`（默认 3）后进入 `quarantineSeconds`（默认 30s）隔离期，期内静默走主库；期满下次请求自动探测，成功即解除。**副作用：主库压力上升**，长期 replica 故障要扩容主库
- 主从延迟 → "提交后立即读"场景会读到旧数据；用 `@RouteToPrimary` 注解强制走主库（`RouteToPrimaryAspect` 已就位）
- 多从库扩展 → 当前 routing map 硬编码 PRIMARY/REPLICA，多从库需改 `determineCurrentLookupKey` 加轮询

**可调参数**：
- `batch.console.read-replica.failure-threshold`（默认 3）：进入 quarantine 的连续失败阈值
- `batch.console.read-replica.quarantine-seconds`（默认 30）：quarantine 持续时间
- `batch.console.read-replica.{primary,replica}.{connection-timeout-millis,validation-timeout-millis,idle-timeout-millis,max-lifetime-millis,leak-detection-threshold-millis}`：完整 Hikari 调参

**指标**（micrometer，可在 Grafana 看板观察）：
- `batch.console.replica.failover.count`：每次降级 +1
- `batch.console.replica.connection.failure`：每次从库 SQLException +1（按 SQLState 打 tag）

**验证**：见 `docs/runbook/read-replica.md` §四（停从库 → 调 GET /api/console/queries 不再 500，自动 fail-open 到主库；指标 `batch.console.replica.failover.count` 同步上升）。

**回滚**：`BATCH_CONSOLE_READ_REPLICA_ENABLED=false` → 重启 console-api → 走 Spring Boot 默认主 DataSource。

---

### 3.2 `batch.scheduler.worker-cache.enabled`

**作用**：`DefaultWorkerSelector.findCandidates` 按 `(tenantId, workerGroup)` 缓存 ONLINE worker 列表，TTL 5s；高频派发不再每次查 DB `worker_registry`。

**默认**：`true`（yml fallback、docker、env 三层一致）。

**配套依赖**：Redis 已就位（项目本来就依赖）。

**风险**：🟢 低
- Redis 故障 → 🟢 **强 fail-open**：`WorkerRegistryCache` `catch Exception`（涵盖 Redis 异常 + JSON 反序列化失败）→ 直通 DB loader + WARN，业务完全不受影响
- TTL 内 worker offline → 最多 5s 内可能选到已下线 worker，`DefaultWorkerSelector` 后续 dispatch 会被 worker 拒绝，下次 tick 重试

**验证**：
```bash
# Redis 命中观察
docker exec batch-redis redis-cli --scan --pattern "batch:worker-registry:*" | head
# 应看到 batch:worker-registry:{tenant}:{group} 形式的 key
```

**回滚**：`BATCH_SCHEDULER_WORKER_CACHE_ENABLED=false` → 重启 orchestrator → 直通 DB（行为同历史）。

**TTL 调优**：`batch.scheduler.worker-cache.ttl-millis`（默认 5000）；高峰期 worker 上下线频繁可调到 2000-3000；稳定期可放大到 10000 减压。

---

### 3.3 `batch.mq.routing.mode`

**作用**：派发 Kafka topic 后缀策略。

| 模式 | 行为 | 适用 |
|---|---|---|
| `SINGLE` | 所有租户共用 base topic（如 `batch.task.dispatch.import`） | 单租户 / 历史行为 |
| `TENANT`（**默认**） | base topic 后追加 `.<tenantId>` | 多租户隔离，避免大租户挤占 |
| `PRIORITY` | base topic 后追加 `.<priorityBand>`（HIGH/NORMAL/LOW） | 高优独立 consumer group |

**默认**：`TENANT`。

**配套依赖**（**关键，启用 TENANT/PRIORITY 前必读**）：
- Kafka topic **必须预创建**或开启 broker auto-create（生产强烈不建议依赖 auto-create）
- worker 端 `topicPattern` 必须订阅 `batch.task.dispatch.import.*` 这种通配符模式才能收到分流后的 topic
- 切换 mode 不能在线滚动 → 老消费者订阅 base topic，新生产者写到 `base.{tenant}`，老 worker 收不到 → 任务积压
- 正确切换姿势：先全量升级 worker（同时订阅 base 和 base.*）→ 再切 producer mode → 等老 base topic 消费完 → 老 worker 下线

**风险**：🟡 中（**无 fail-open**——这是配置开关，不是故障降级开关）
- 切换不当 → 任务静默积压（producer 在新 topic、consumer 在老 topic）；走灰度 SOP [`mq-topic-routing-rollout.md`](./mq-topic-routing-rollout.md)
- topic 数膨胀 → 多租户场景一千个租户 = 一千个 topic，broker 分区元数据膨胀，需提前评估 broker 容量
- BatchTopicResolver 仅在 `workerType` 字段无效时返回 null（业务数据异常，**不是基础设施故障**），由调用方走 fallback 路径

**验证**：
```bash
# 观察实际写入的 topic
docker exec batch-kafka kafka-topics --bootstrap-server localhost:9092 --list | grep batch.task.dispatch
# TENANT 模式应看到 batch.task.dispatch.import.tenant-a, batch.task.dispatch.import.tenant-b ...
```

**回滚**：`BATCH_MQ_ROUTING_MODE=SINGLE` → 重启 producer/consumer 全部实例 → 回到单 topic。回滚也要先升 consumer 再切 producer。

---

### 3.4 ~~`batch.trigger.quartz-datasource.enabled`~~（已移除，2026-04-25）

**移除原因**：Phase 2 半成品 — 代码层 wire 了独立 DataSource，但配套基础设施（独立 PG 容器 / QRTZ_* 建表 SQL / 数据迁移）从未交付，且即使补完也只能解 Quartz 共库 5% 的问题（WAL 隔离），不能解 95% 的协调瓶颈（QRTZ_LOCKS 行锁、polling 模型、单一全副本拓扑）。

**Quartz 当前部署形态**：JobStore 表（11 张 `QRTZ_*`）落在 `batch_platform.quartz` schema，与业务表共享主 PG 实例。当前业务量级（< 100 万 fire/天）下完全够用。

**演进路径**：业务量级接近 1000 万 fire/天 拐点时，**直接换时间轮**（Netty `HashedWheelTimer` + 滑动窗口扫库 + ShedLock），跳过"独立库"中间过渡。完整方案见 [`docs/architecture/quartz-replacement-evaluation.md`](../architecture/quartz-replacement-evaluation.md)。

---

### 3.5 `batch.quota.runtime-store`

**作用**：tenant quota 限流的运行时状态后端。

| 值 | 实现 | 适用 |
|---|---|---|
| `redis`（**默认**） | `RedisQuotaRuntimeStateService` Lua 原子脚本，单条 Lua 完成"窗口判定 + peakBorrowed CAS + TTL 续命" | 海量并发，去除 PG 行锁瓶颈 |
| `database` | `DatabaseQuotaRuntimeStateService` PG `@Version` 乐观锁 | 故障降级 / 短期回退 |

**默认**：`redis`。

**配套依赖**：
- `redis` 模式：Redis 必须就位；`QuotaRuntimeStateSnapshotScheduler` 按 `batch.quota.snapshot.interval-millis`（默认 5 分钟）把 Redis 状态 upsert 回 PG `quota_runtime_state` 保留审计能力
- `database` 模式：`QuotaRuntimeResetScheduler` 启用，按时间窗口重置 PG 行；Redis 模式下该 scheduler 不启动（`@ConditionalOnProperty(havingValue=database)`）

**风险**：🟡 中（fail-open 有语义副作用）
- Redis 抛 `DataAccessException` → 🟡 **中 fail-open**：`RedisQuotaRuntimeStateService` 三处 catch（evaluateAndReserve / acquire / release）一律返回 `ResourceCheck.allow()` —— **限流功能等同关闭**。短抖动无影响；**长期 Redis 故障会让大租户吃掉小租户配额**。运维需监控 quota allow WARN 频率，必要时手工切回 `database` 模式
- 切回 `database` → PG `quota_runtime_state` 表行可能因 Redis 模式期间未及时 snapshot 而短暂不一致；下个调度周期自然收敛

**验证**：
```bash
# Redis 模式下应看到 quota Lua 操作的 key
docker exec batch-redis redis-cli --scan --pattern "batch:quota:*" | head
```

**回滚**：`BATCH_QUOTA_RUNTIME_STORE=database` → 重启 orchestrator → 改走 PG 实现。**Redis 中残留 key 会因 TTL 自然过期**，无需清理。

---

### 3.7 ~~`batch.trigger.async-launch.enabled`~~（ADR-010，已移除）

> **2026-05-02 已删除**：trigger → orchestrator 异步链路（outbox + Kafka）已固化为唯一路径，开关和同步 HTTP 桥（`HttpOrchestratorTriggerAdapter`）同步删除。无需配置此参数。
>
> 链路详情见 `docs/architecture/system-flow-overview.md §1.4`。运维观察指标（outbox GIVE_UP 告警等）仍有效，见 `docker/observability/prometheus-batch-rules.yml`。

---

### 3.6 `batch.quota.snapshot.enabled` / `interval-millis`

**作用**：Redis quota 状态 → PG `quota_runtime_state` 周期 snapshot；为审计 / 故障降级到 database 模式时提供数据起点。

**默认**：`enabled=true`，`interval-millis=300000`（5 分钟）。

**仅 `runtime-store=redis` 时生效**。`database` 模式 PG 本身就是权威源，snapshot 自动跳过。

**配套依赖**：无新增。

**风险**：🟢 低
- snapshot 失败 → 🟢 **强 fail-open（局部）**：per-tenant `catch DataAccessException`，单个租户失败仅跳过该租户日志 WARN，其他租户继续；该租户漏一周期，下次自然恢复；**不阻塞限流业务**
- 频率太高 → PG 写压力（每 5 min 全量 upsert 所有 owner）；万级 owner 时建议放大到 600000+

**验证**：
```sql
-- snapshot 命中后行的 updated_at 周期性更新
SELECT owner_type, owner_id, peak_borrowed, updated_at
  FROM batch.quota_runtime_state
  ORDER BY updated_at DESC LIMIT 10;
```

**回滚**：`BATCH_QUOTA_SNAPSHOT_ENABLED=false` → 重启 orchestrator → 不再 snapshot；Redis 仍是限流权威源，但故障切回 database 模式时 PG 数据停在最后一次 snapshot 时刻。

---

### 3.8 `batch.datasource.business.routing.*`（biz 租户分片路由 / Tiered）

应用层把租户路由到不同 biz PG 实例(自研,非 Citus)。**默认 `enabled=false` = 单片无损**(全租户落 shard-0=现库,零行为变更)。开启后:

- `placement-source=CONFIG`(默认):hash 池化 + `silo-overrides`;`=TABLE`:读 `batch.business_tenant_placement`(在线维护,console `/api/console/ops/tenant-placements`,表命中优先 hash 兜底)。
- `shards[*]`(key+url+账密)凭据走 secrets,**不入表**;`shard-max-pool-size` 控每片池;`placement-cache-ttl-ms` 默认 5s(**0=每次查库仅测试用**)。
- 仅 import/export/process 三 worker 持有 biz 数据源;dispatch/atomic/SDK 不涉及。
- **Fail-open**:placement 表读失败时已有缓存保留 stale(silo 路由仍对),冷启动退 hash;未知 placement key **硬失败**(关 lenientFallback,防静默落 shard-0 污染)。

完整设计 / 开片 / 账户 / 凭据注入见 [`biz-tenant-routing.md`](./biz-tenant-routing.md)。验证:`scripts/local/sim-harness.sh verify-data`(两片真实 PG 活体) + 单测 `*PlacementResolver*Test` / `BusinessRoutingDataSource*Test`。

### 3.9 `batch.security.bypass-mode`（认证/CSRF 旁路 — 仅本地/联调/E2E）

总旁路开关(认证/加解密/审批),**prod profile 强制拒绝**。本地 yml 默认 `true`,但 `.env.local` 的 `BATCH_SECURITY_BYPASS_MODE` 会**覆盖** yml。

> ⚠️ **sim/本地遇到问题(2026-06-14)**:`BATCH_SECURITY_BYPASS_MODE=false` 时 CSRF(double-submit cookie)对**所有写请求**生效;sim 的 curl 脚本不带 `X-XSRF-TOKEN` → **全部 403「访问被拒绝」**(租户导入/迁片/上传全挂)。`bypass-mode=true` 时 `BYPASS_MODE_CSRF_IGNORED_MATCHERS={"/**"}` 放行。**跑 sim 必须 `BATCH_SECURITY_BYPASS_MODE=true`**(已纳入 `sim-harness.sh preflight` 检查项)。FE/真实部署走正常 CSRF(axios 回传 XSRF-TOKEN),不受影响。

## 4. 翻开关的统一流程

无论开哪个开关，按以下五步：

1. **预检**：基础设施就位（PG 从库 / Redis / Kafka topic / Quartz 库）；环境变量在部署平台 / `.env.local` 配齐
2. **滚动重启**：按 `console-api → orchestrator → trigger → workers` 顺序，**不要并发重启所有模块**
3. **启动日志验证**：搜启动 INFO 行，例如 `console read-replica enabled: primary=...,replica=...`、`worker registry cache enabled, ttl=5000ms`
4. **行为验证**：本文档每节给的 verify 命令（curl / redis-cli / psql / kafka-topics）
5. **监控观察**：1 小时内观察 Grafana 三块板（P99 latency / outbox 积压 / DL 量）+ 业务核心指标，无回归再认为完成

回滚同样按反序：`workers → trigger → orchestrator → console-api`。

---

## 5. 与文档同步的更新清单

修改任何开关默认值或新增开关时，必须同步：

| 文件 | 改什么 |
|---|---|
| 对应模块 `application.yml` | fallback 值 + 注释 |
| 对应 `@ConfigurationProperties` 类 | Java 字段默认 + javadoc |
| `docker/compose/app.yml` | 如需显式覆盖（如 read-replica）补 `:-xxx` |
| `.env.example` | 列出该开关 + 默认值 + 一行作用说明 |
| 本文档（`feature-switches.md`） | §1 索引表 + §3 详述节 |
| `docs/architecture/rework-classification.md` | Phase 2 表格的"开关"列 |
| `docs/changelog.md` | **仅当**改的是 CLAUDE.md 已有规范条款时记一条 |

---

## 6. 已知待办（基于本次梳理 — 2026-04-25 处理结果）

| # | 待办 | 状态 |
|---|---|---|
| 1 | `docker/compose/app.yml` 给 `quartz-datasource` 加显式 `:-false` 兜底 | ✅ 完成 → 后于 2026-04-25 进一步**整体移除**该开关（Phase 2 半成品清理），新方案见 `docs/architecture/quartz-replacement-evaluation.md` |
| 2 | `rework-classification.md` 第 81 行更新为实际默认表 | ✅ 完成（替换为 5 项开关默认值表 + 引用 `feature-switches.md`） |
| 3 | `read-replica` 应用层 fail-open | ✅ **本次梳理前已落地**（`ReadReplicaRoutingDataSource` C-3.1：失败计数 + quarantine + micrometer 指标 + `@RouteToPrimary` 注解）；本文档 §3.1 已校准 |
| 4 | `mq.routing` 切换灰度发布 runbook | ✅ 完成（新增 `docs/runbook/mq-topic-routing-rollout.md`） |
