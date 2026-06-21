# 改造工作量分类 — 哪些动代码、哪些只动配置/数据/运维

> 配套文档：[`scalability-assessment.md`](./scalability-assessment.md) — 这里只回答"分类落地"问题。
> 评估日期：2026-04-25

`scalability-assessment.md` 列了一长串改进项，但**改动性质差异巨大**——有些是 cron 跑 SQL 一周完成，有些是数据访问层重写半年起步。这份文档把它们按"改什么"重新分类，帮你判断**哪些可以立刻开始、哪些需要立项**。

---

## 1. 七类改造方式

| # | 类型 | 改什么 | 不改什么 | 典型工具 |
|---|---|---|---|---|
| **A** | **纯运维 / 部署** | K8s manifests / 监控仪表盘 / supervisor | 任何 jar 内代码 | k8s liveness/readiness、Grafana dashboard、PagerDuty |
| **B** | **数据治理 SQL** | 定时 cron 跑归档 / 删除 SQL | Java 业务代码、DDL | crontab + psql / Airflow + DBT 类调度 |
| **C** | **DDL 迁移** | DB schema（建分区、archive schema、索引）| Java 业务代码 | Flyway migration |
| **D** | **配置 / yaml** | `application.yml`、Kafka topic 配置、起新中间件 | Java 业务代码 | Spring config、Kafka admin、Helm values |
| **E** | **配置驱动的代码改造** | 加少量代码 + 大量配置 | 现有架构层 | Spring `@Bean` 拓扑、`@Conditional`、Spring Profile |
| **F** | **代码改造（中）** | 加新组件 / 改现有 service | 数据模型契约 | Spring Boot starter、Redis client、micrometer |
| **G** | **重大代码改造** | 数据访问层 / 跨模块协调 | 业务表语义 | ShardingSphere / Vitess / 自实现路由 |

> H 类（前端代码）和 I 类（新建工程模块）是 G 的特例，单列出来意义不大，直接归 G。

---

## 2. `scalability-assessment.md` 所有项的归类

### 2.1 黄色（中等量级天花板，9 项）

| 项 | 类型 | 解释 |
|---|---|---|
| `outbox_event` 无清理 | **B** 数据治理 | 加一个 cron job：`DELETE FROM batch.outbox_event WHERE publish_status='PUBLISHED' AND created_at < now() - interval '7 days'` |
| `job_partition` 单表无分区 | **C** DDL | Flyway migration 改成 `PARTITION BY RANGE (biz_date)`，按月或按周建子表 |
| 历史数据治理是手工脚本 | **A + B** 运维自动化 | 把现有 `cleanup-historical-failures.sql` 装到 cron / Airflow，加监控告警 |
| `DefaultWorkerSelector` 实时查 DB | **F** 代码（中） | 加 Redis cache，5s TTL，failover 时降级回 DB |
| `OrchestratorConfigCacheService` 无主动失效 | **F** 代码（中） | 监听 Redis pub/sub channel，配置变更广播，多实例同步 |
| Worker 内重试不跨 worker | **F** 代码（中） | partition 第 N 次失败时改派给其他 worker（修改 RetryGovernanceService）|
| 单 PostgreSQL 实例（read replica）| **D + E** 配置 + 少量代码 | 起 PG 从库；改 `console-api` / queries 路由层走从库（写入仍主库）|
| `biz_business` 直连查询 | **D + E** 同上 | 给 `business` datasource 也加 read replica 路由 |
| JVM 启动慢 + 弹性扩缩 | **A + D** 运维 + 配置 | k8s pre-warm pool / HPA 提前扩容；CDS / GraalVM native image 探索 |

### 2.2 红色（真海量硬伤，6 项）

| 项 | 类型 | 解释 |
|---|---|---|
| 单库瓶颈（分库分表）| **G** 重大代码改造 | 数据访问层路由：ShardingSphere / Vitess / 自实现；按 `tenant_id` hash 或 `biz_date` range；MyBatis Mapper / SQL 两侧都要适配 |
| Kafka topic 设计细化 | **D + F** 配置 + 中量代码 | 起多 topic + producer/consumer 路由按 tenant/priority 分流；router service 一层 |
| 观测性空白 | **A + 少量代码** | 主要是搭 Grafana / Prometheus / OpenTelemetry，micrometer 已经埋了大部分点；缺的话补关键指标 |
| `workflow_run` 扇出表膨胀 | **B + F** 数据治理 + 中量代码 | 加 archive 调度作业 + workflow_run / workflow_node_run 归档逻辑 |
| 资源 quota 软限流不够强 | **F** 代码（中） | 替换 `quota_runtime_state` 的 PG 乐观锁实现为 Redis token bucket（接口不变，实现层重写）|
| 跨 DC / AZ 部署 | **A + 少量代码** | k8s 多集群 / 多 region 部署；少量代码加 health probe + region awareness |

---

## 3. 按 Phase 看"动代码 vs 不动代码"比例

### Phase 1 — 中等量级前置（5 项）— **2026-04-25 全部完成 ✅**

| 项 | 类型 | 改代码？ | 状态 | 交付物 |
|---|---|---|---|---|
| outbox 自动归档 | B | ❌ 不改 | ✅ 完成 | `scripts/db/cleanup-outbox-events.sql`（PUBLISHED >7d / GIVE_UP >30d 归档） |
| file_record / job_instance 归档 | B + C | ❌ 不改业务代码（仅 DDL）| ✅ 完成 | `scripts/db/cleanup-success-instances.sql`（30d 保留窗口 + 8 步级联清理） |
| 配置缓存 Redis pub/sub 失效 | F | ✅ 改 | ✅ 完成 | `ConsoleConfigCacheController`：6 个 ops 端点 `/api/console/ops/cache/evict-*`，DB 直改后手动失效 Redis |
| 完整观测三块板 | A | ❌ 不改（micrometer 已就位）| ✅ 完成 | `docker/observability/grafana-dashboard-batch-coverage.json`（6 panel：outbox pending/stale/DL pending/HTTP P99/publish rate/总览） |
| worker auto-restart | A | ❌ 不改 | ✅ 完成 | `scripts/local/watchdog.sh`（macOS 闲置回收 worker 后自动拉起；docker-compose 模式 `restart: unless-stopped` 兜底） |

**Phase 1：5 项里只有 1 项要改代码** ✅✅✅✅✅(改) — 全部交付，详见上表

### Phase 2 — 百万 → 千万（5 项）— **2026-04-25 全部完成 ✅**（opt-in scaffolding）

| 项 | 类型 | 改代码？ | 状态 | 交付物 / 开关 |
|---|---|---|---|---|
| Read replica | D + E | ✅ 改（DataSource routing） | ✅ 完成（含 docker 部署 + 本地验证） | `batch-console-api/.../config/ReadReplicaDataSourceConfiguration.java` 路由 + 9 个核心 query service 加 `@Transactional(readOnly=true)`；docker-compose `--profile replica` 起 `postgres-replica` 容器（streaming replication）；运维指引见 `docs/runbook/read-replica.md` |
| WorkerSelector 加 Redis cache | F | ✅ 改 | ✅ 完成 | `WorkerRegistryCache`（5s TTL，fail-open）+ `WorkerSelectorCacheProperties`；`DefaultWorkerSelector.findCandidates` 走缓存；开关 `batch.scheduler.worker-cache.enabled` |
| Kafka topic 按租户/优先级分 | D + F | ✅ 改（producer/consumer 分流） | ✅ 完成 | `BatchTopicResolver` 按 `MqRoutingProperties.mode=SINGLE/TENANT/PRIORITY` 追加 topic 后缀；`KafkaOutboxPublisher` 走 resolver；开关 `batch.mq.routing.mode` |
| ~~Quartz JobStore 单独库~~ | ~~D~~ | — | ❌ **2026-04-25 撤销**（半成品清理） | 移除原因：基础设施未交付（无独立 PG 容器 / QRTZ_* 建表 SQL / 数据迁移），且即使补完只能解 Quartz 共库 5% 问题（WAL 隔离），不能解 95% 协调瓶颈（QRTZ_LOCKS 行锁 / polling / 全副本拓扑）。演进路径直接换时间轮，详见 [`quartz-replacement-evaluation.md`](./quartz-replacement-evaluation.md) |
| quota Redis token bucket | F | ✅ 改（替换实现） | ✅ **2026-04-25 完成** |

**Phase 2：原 5 项 → 现 4 项**（Quartz JobStore 单独库已撤销）— 其余 4 项 ✅✅✅✅ 全部交付

**Phase 2 落地策略 — 实际默认（2026-04-25 校准）**：scaffolding 验证完成后多数开关 application.yml fallback 已设为开启 + fail-open 兜底，并非全 opt-in。每个开关的实际默认 / 风险等级 / 启用条件详见 [`docs/runbook/feature-switches.md`](../runbook/feature-switches.md)。

| 开关 | application.yml 默认 | docker-compose 默认 | 备注 |
|---|---|---|---|
| `batch.console.read-replica.enabled` | true | **true**（与 yml 一致） | fail-open 已就位；测试 `application-test.yml` 覆盖 false |
| `batch.scheduler.worker-cache.enabled` | true | true | Redis fail-open 已就位 |
| `batch.mq.routing.mode` | TENANT | TENANT | 切换需 worker 端 topicPattern 配套 |
| ~~`batch.trigger.quartz-datasource.enabled`~~ | — | — | **已移除**（2026-04-25 半成品清理）；演进直接换时间轮见 [`quartz-replacement-evaluation.md`](./quartz-replacement-evaluation.md) |
| `batch.quota.runtime-store` | redis | redis | Redis 故障 fail-open 放行 |

> 历史叙述（"opt-in scaffolding，所有开关默认关闭"）已不准确，以本表 + `feature-switches.md` 为准。

**P2-1 quota Redis 落地说明（2026-04-25）**：
- 接口 `QuotaRuntimeStateService` 抽出，配 `batch.quota.runtime-store=redis|database` 切换实现。默认 `redis`。
- Redis 路径：`RedisQuotaRuntimeStateService` 用单条 Lua 脚本原子完成"窗口判定 + peakBorrowed CAS 抬升 + TTL 续命"，避免多 orchestrator 实例对 PG 同一行的写竞争。Lua 返回 `{allowed, peak, winStart, winEnd}`。
- DB 路径：`DatabaseQuotaRuntimeStateService` 保留 PG `@Version` 乐观锁的原实现，作为 Redis 故障 / 短期回退路径。`QuotaRuntimeResetScheduler` 仅 DB 模式启用（Redis 由 TTL 自动回收过期窗口）。
- 故障策略：Redis 抛 `DataAccessException` 时 fail-open（放行 + WARN 日志）。限流故障不应放大成业务故障；下一轮自然恢复。
- 时区敏感：CALENDAR_DAY 边界由 Java 侧 `BatchTimezoneProvider` 计算后透传给 Lua（`calendarStartMillis / calendarEndMillis`），不依赖 Lua server 时区。
- PG 审计落盘：`QuotaRuntimeStateSnapshotScheduler` 默认每 5 分钟（`batch.quota.snapshot.interval-millis`）按 `tenant_quota_policy` / `resource_queue` 枚举 owner，把 Redis 当前 peak 状态 upsert 到 PG `quota_runtime_state`。仅 Redis 模式启用。
- 测试覆盖：12 个 unit test（mock OrchestratorRedisSupport，验证 fail-open / 守卫逻辑）+ 6 个 integration test（真实 Redis testcontainer，验证 Lua 脚本窗口/peak 语义）。

### Phase 3 — 千万 → 亿（5 项）

| 项 | 类型 | 改代码？ |
|---|---|---|
| 分库分表 | G | ✅✅ 重大改 |
| 多 Kafka 集群 | D + F | ✅ 改（producer/consumer 多 cluster 路由）|
| ~~workflow archive 自动化~~ | ~~B + F~~ | ✅ **2026-04-25 完成**：`WorkflowArchiveService` + `WorkflowArchiveScheduler`（默认每天 04:15，30d 保留窗口，5000 条 batchSize 防长事务）；兜底 SQL 脚本 `scripts/db/cleanup-workflow-runs.sql` |
| 跨 AZ active-active | A + 少量 | ❌ 大部分（health probe 加点） |
| 观测中台（tracing + log + metric 长存）| A + F | ❌ 大部分（少量 OpenTelemetry instrumentation）|

**Phase 3：5 项里 2 项重大代码改造，3 项偏运维**
**预估工作量**：3-6 个月（分库分表是真工程）

---

## 4. 加分项（5 项）— 不阻塞但锦上添花

| 项 | 类型 | 改什么 |
|---|---|---|
| 状态机仪表盘 | **A** | Grafana dashboard，不改代码 |
| Pipeline UI 编排 | **G**（前端） | 新建 console 页面 + 后端接口（已有 console-api） |
| tenant quota 报表 | **G**（前端 + 少量后端）| Console UI + queries 接口 |
| DAG 可视化 | **G**（前端为主） | console 用 D3 / X6 / ReactFlow 画 workflow_node + workflow_edge |
| Worker plugin 热加载 | **G**（重大）| 类加载器 + plugin 上传 / 校验 / 注册流程 |

---

## 5. 三种"立项姿势"建议

### 5.1 防御性改进（最小投入，先稳住）

> 适合："系统现在跑得动，担心 1 年后不行"

只做 Phase 1 + Phase 2 的两项不改代码项：

- ✅ outbox 自动归档（B）
- ✅ file_record / job_instance 归档（B + C）
- ✅ 完整观测三块板（A）
- ✅ worker auto-restart（A）

**4 项全部不动业务代码**。1 人 1-2 周。能把"无意识技术债"挡住。
（原"Quartz JobStore 单独库"已于 2026-04-25 撤销半成品；真要演进直接换时间轮，详见 [`quartz-replacement-evaluation.md`](./quartz-replacement-evaluation.md)）

### 5.2 中等扩容（一个 sprint）

> 适合："业务增长可见，准备扩到百万/天"

Phase 1 全部 + Phase 2 选 3 项：

- 上面 5 项
- 配置缓存 Redis pub/sub 失效（F）
- WorkerSelector Redis cache（F）
- Read replica（D + E）

**8 项里 3 项改代码**，但都是加层不重写。1-2 人 1 个月。

### 5.3 真海量改造（立项工程）

> 适合："已经看到 1 亿/天的业务方向，需要改造支撑"

完整 Phase 1 + Phase 2 + Phase 3。

**最关键节点**：分库分表（Phase 3）是真改造。建议先做 PoC 验证 ShardingSphere / Vitess 方案，再决定全量铺开。

3-6 个月工程，2-3 人核心 + 测试压测协同。

---

## 6. 怎么判断该走哪条路

| 你的情况 | 推荐 |
|---|---|
| 没看到具体业务量级目标 | 5.1 防御性改进（不浪费） |
| 业务量级 < 100 万/天，担心稳定性 | 5.1 防御性改进 + 加 worker auto-restart |
| 业务方明确说"半年内要支持 500 万/天" | 5.2 中等扩容 |
| 业务方说"1 年内要支持 5000 万/天" | 5.2 + Phase 3 部分（分 topic + workflow archive）|
| 业务方说"亿级/天，明年上线" | 5.3 真海量改造，**立刻立项** |
| 没人提需求，只是想"做点优化" | **不要乱做**——技术债治理优先级低于真正的功能开发；先用 micrometer 看实际指标再决定 |

---

## 7. 一句话总结

> **Phase 1 大部分不改业务代码；Phase 2 是"加层不重写"；Phase 3 才是真改造。**

如果你只是想稳住现状 + 留点扩容空间，**只做 Phase 1 + Redis cache 这两项就够**，工作量 2-3 周一人，零业务代码侵入。

更激进的改造**等业务量级真的逼近瓶颈再做**，否则就是为了改造而改造。
