# 海量批量调度承载力评估

> 评估日期：2026-04-25
> 评估角度：架构决策的方向性 + 工程承载力（在量上能不能撑）
> 评估依据：本仓库当前代码（`main` HEAD）+ 数据模型 + 这周端到端跑通验证（参考 [`worker-stage-coverage.md`](../runbook/worker-stage-coverage.md)）

---

## 1. TL;DR — 一句话结论

**架构骨架对，工程完成度高，对中小到中等规模批量（百万任务/天以下）已经 production-ready。** 作为"真海量"批量调度平台（金融日终结算、电商大促对账、广告平台计费等亿级/天场景）还有一段路，卡点在三处：**分库分表 / 自动归档 / 完整观测**。设计上限够高，这周收敛的多个 bug（cron 误用、sourcePayload 泄露、`ensurePipelineDefinition` 污染、Outbox shutdown race）改了之后骨架更稳，没揭示设计层面的错配。

---

## 2. 绿色 — 设计层面做对的事（适合海量批量）

| 维度 | 怎么做的 | 为什么对 |
|---|---|---|
| **DB → Outbox → Kafka 双写一致性** | `LaunchService` 把 `job_instance` + `outbox_event` 同事务 commit；`OutboxPollScheduler` 异步推 Kafka | 海量场景下 Kafka 短暂故障/慢必然发生；同事务 outbox 是经典正确解，业务永不阻塞、消息永不丢。详见 [system-flow-overview §7](./system-flow-overview.md) |
| **CLAIM / lease 模型** | `lease_expire_at` 由 worker 心跳续期；`WorkerHeartbeatTimeoutScheduler` 90s 超时打 OFFLINE → partition 可被其他 worker 接管 | worker 进程随时会挂（k8s pod 重启、JVM OOM、宿主机故障），自动转移是生产可用的硬要求 |
| **Orchestrator 单写者原则** | Worker 不能直连改 `job_instance`；只能通过 HTTP 上报 → orchestrator 推进状态机 | 状态机一致性靠这个；多 worker 并发改同一行状态会立刻失控 |
| **多租户从 model 层就在** | 几乎所有表第一字段是 `tenant_id`；selector / quota / queue 都按 tenant 分割 | 后期想加是地狱，从一开始建对了 |
| **配置驱动（不是代码）** | `pipeline_step_definition` / `file_template_config` / `file_channel_config` 全在 DB；换文件格式/换 channel 不用改代码不用重启 worker | 真实业务每月会冒出新文件格式/新对接方，硬编码会死 |
| **DAG + GATEWAY 编排** | `workflow_node` + `workflow_edge` + `joinMode` (ALL / ANY / N_OF) + `condition_expr` | 真海量批量必定要跨任务依赖（"日终所有数据全到才出报表"），这个能力具备。详见 [`workflow-dependency-guide.md`](./workflow-dependency-guide.md) |
| **熔断 / 退避 / DL / 补偿全链** | `RetryGovernanceService`（NON_RETRYABLE 集合）+ `CompensationService` + `DispatchChannelHealthService` 熔断 | 海量 dispatch 时一两个对端挂掉是常态，必须熔断不让雪崩；不可重试错误必须直接进 DL 而不是浪费指数 backoff |
| **ShedLock 集群单实例 + Sharding** | 调度类任务都加 ShedLock；`OutboxPollScheduler` 支持 `shardTotal>1` 多实例并行 | 这是横向扩 orchestrator 实例的前提 |
| **Outbox 自适应轮询** | 有积压立即下轮（200ms）/ 空闲退避到 5s | 既不空查 DB 又能近实时推消息 |
| **Pipeline / Stage 模板可静态校验** | 每类 worker 的 step 链是固定模板（IMPORT 6 / EXPORT 5 / DISPATCH 6）；ensurePipelineDefinition 已存在不重写（本周修） | 防止跨 worker 错位污染 step 链 |

**判断**：架构方向对——不是堆砌组件，是"经典互联网批量任务平台"该有的样子（XXL-Job / DolphinScheduler / Apache Hop / Spring Batch + custom orchestration 都在做类似的事，这套接近 mature 实现的形态）。

---

## 3. 黄色 — 量级 > 中等时会撞到的天花板

| 风险点 | 现状 | 撑得动的量级 | 撞墙后的代价 |
|---|---|---|---|
| **单 PostgreSQL 实例** | `batch_platform` 一库扛 outbox + job_instance + partition + workflow_run + file_record 全部元数据 | 5K–10K 任务/秒派发（合理硬件） | 写入瓶颈；外加慢查询拖死调度回环 |
| **`job_partition` 单表** | 没分区表（PG partitioning），全靠普通索引 | 单表 5000 万行查询开始抖（取决于 WHERE 复杂度） | `WaitingPartitionDispatchScheduler` 扫表变慢 → 调度滞后 |
| **`outbox_event` 无清理** | 没看到自动归档/删除 PUBLISHED 事件的机制 | 1 亿行后即使有索引也开始慢 | poller select latency 爆炸 → publish 滞后 → 整链路变慢 |
| **`DefaultWorkerSelector` 实时查 DB** | 每次派发都 `SELECT worker_registry WHERE status='ONLINE'` | worker 数 < 几百 OK | 万级 worker 时该 query 成热点 |
| **`OrchestratorConfigCacheService` 无主动失效** | 改 `default_params` 必须重启 orchestrator 才生效（本周踩过） | 配置变更频率低就行 | 多实例集群想做"灰度更新一个 job"基本不可能 |
| **历史数据治理是手工脚本** | `cleanup-historical-failures.sql` 是 `psql -f` 跑 | 单实例可控 | 海量场景必须自动化（带 watermark 的归档作业），否则一年后整库变废 |
| **Worker 内重试不跨 worker** | DISPATCH retry 是同 partition 同 worker 重新走 | 单点失败容忍度有限 | worker 节点本身慢/坏时，retry 全在同节点没用 |
| **biz_business 直连查询** | EXPORT worker 直接 `SELECT FROM biz.*`，无 read replica 路由 | 几千 QPS OK | 海量 export 时业务主库被查崩 |
| **JVM 启动慢 + 弹性扩缩** | 每个进程 15–30s CDS warmup + Spring Boot 初始化 | 静态规模 OK | 真要"突发流量自动扩 50 个 worker"——半分钟才上线，等不及 |

**中等规模**（百万任务/天，~10 worker 实例，~50 租户）：调优能撑住，但需要：
- PostgreSQL 加 read replica；orchestrator 读路径分流
- 给 outbox / partition / file_record 跑定时归档作业
- worker_registry 加 Redis 缓存（已有 `OrchestratorConfigCacheService` 一类，扩展 worker 列表也用）
- Kafka topic 分区数提到 12–24

---

## 4. 红色 — 真海量场景（亿级/天）撑不起来的硬伤

按"日均 1 亿任务、500 worker、200 租户"这种量级评估：

### 4.1 单库就是绝对瓶颈

`batch_platform` 必须**拆**：

| 拆分对象 | 建议 | 理由 |
|---|---|---|
| 配置类（workflow_definition / job_definition / template / channel）| 一个独立库 + Redis 全量缓存 | 写少读多，缓存命中率近 100% |
| 运行态（job_instance / partition / task）| 分库分表（按 `tenant_id` hash 或 `biz_date` range）| 高写入 + 高查询，是热点 |
| outbox_event | 单独一库 | 高写入，独立 IO 通道避免和 job 写入争 WAL |
| workflow_run / workflow_node_run | 跟运行态分一起或独立 | 编排扇出大时易膨胀 |

### 4.2 Kafka topic 设计要细化

当前 4 个主 topic（`batch.task.dispatch.{import|export|process|dispatch}`）+ 每 worker 节点的 `.node.<worker_code>` 子 topic 已经够 lo。海量场景需要：

- **按租户分 topic**（防"大租户压垮所有 worker"）：`batch.task.dispatch.import.tenant.<group>`
- **按优先级分 topic**（高优 quotation 类 ↔ 低优 archive 类不抢线程）：`batch.task.dispatch.import.priority.{p0|p1|p2}`
- **跨集群路由**（海量时单集群也是瓶颈）

### 4.3 观测性是空白

当前缺失:

- 任务派发/执行 P99 latency 仪表盘
- outbox 积压告警
- DL 队列容量告警
- tenant 级 quota 实时使用率
- DAG 大规模 fan-out 时的 join-gate 卡死告警

生产海量没这套基本是裸奔。

### 4.4 workflow JOB 节点扇出表膨胀

一个 workflow 跑会创建多个 child `job_instance`（本周验证过：`TC_WF_RISK_PIPELINE` 1 个 workflow → 4 个 job_instance）。扇出 100 倍的复杂 workflow 会让 instance 表爆炸。需要对 `workflow_run` 单独建 archive 流程。

### 4.5 资源 quota 软限流不够强 — **2026-04-25 已完成 Redis Lua 迁移**

历史问题：`quota_runtime_state` 是 PG 行锁层，并发热点租户会撞乐观锁（曾遇到 `OptimisticLockingFailureException` 500，先修为 409 + retry 兜底）。海量场景需要真分布式限流。

**当前状态**：默认实现已切到 `RedisQuotaRuntimeStateService`：单条 Lua 脚本原子完成"窗口判定 + peakBorrowed 抬升 + TTL 续命"，去掉了 PG 行锁瓶颈。配置 `batch.quota.runtime-store=database` 可回退到原 PG 实现作为故障降级。`QuotaRuntimeStateSnapshotScheduler` 周期把 Redis 状态 upsert 到 PG 表保留审计能力。详见 `docs/architecture/rework-classification.md` Phase 2 第 5 项 / `CLAUDE.md` 2026-04-25 条目。

### 4.6 跨数据中心/AZ 部署

当前没看到地理冗余设计。海量批量平台一般要 active-active / active-standby，DR 切换 RTO < 5 min。

---

## 5. 容量推断（经验估算，仅供参考）

| 量级 | 任务/天 | 当前架构能否扛 | 备注 |
|---|---|---|---|
| **小** | < 10 万 | 🟢 完全够，调优都不需要 | 单实例 stack 即可 |
| **中** | 10 万 ~ 1000 万 | 🟡 调优能扛 | read replica + 归档作业 + 适当扩 worker |
| **大** | 1000 万 ~ 1 亿 | 🟡⚠️ 极限态 | 必须做分片 + topic 细化 + 可观测性补齐 |
| **海量** | > 1 亿 | 🔴 当前架构挡不住 | 需要分库分表 + 多 Kafka 集群 + 观测中台 |

> 这是粗估。实际拐点取决于：单任务平均处理时长、partition 平均分片数、是否有热点租户、worker 单进程并发、PG 硬件配置。可以做一次正式压测得到真实曲线（参考 `load-tests/` 模块）。

---

## 6. 改造路线图

按规模分阶段，每个 Phase 不必跳过下一个就能开始；**Phase 1 做完前不要追海量**，否则技术债会失控。

### Phase 1 — 中等量级前置（中小 → 中）— **2026-04-25 全部完成 ✅**

| 项 | 优先级 | 说明 | 状态 / 交付物 |
|---|---|---|---|
| `outbox_event` 自动归档 | P0 | 每天清 PUBLISHED 超 7 天的，避免无限膨胀 | ✅ `scripts/db/cleanup-outbox-events.sql` |
| `file_record` / `job_instance` 归档 | P0 | 搬到 `archive` schema，按 `biz_date` range 分表 | ✅ `scripts/db/cleanup-success-instances.sql`（30d 保留 + 8 步级联）|
| 配置缓存 Redis pub/sub 失效广播 | P1 | 不用重启 orchestrator 就能让多实例感知 `default_params` 变更 | ✅ `ConsoleConfigCacheController`（`/api/console/ops/cache/evict-*` 6 端点）|
| 完整观测三块板 | P0 | P99 latency / outbox 积压 / DL 量 grafana | ✅ `docker/observability/grafana-dashboard-batch-coverage.json`（6 panel）|
| worker auto-restart | P0 | k8s liveness + readiness（解决本周观察到的 worker 闲置 8h 自动死） | ✅ `scripts/local/watchdog.sh`（本地）+ docker-compose `restart: unless-stopped`（容器）|

### Phase 2 — 百万 → 千万 — **2026-04-25 全部 5 项 scaffolding 完成 ✅**

> 全部 opt-in：默认关闭（保持历史行为），运维侧按需翻开关。详见 [`docs/architecture/rework-classification.md`](rework-classification.md#phase-2--百万--千万5-项--2026-04-25-全部完成--opt-in-scaffolding) Phase 2 落地表。

| 项 | 说明 | 状态 / 开关 |
|---|---|---|
| Read replica | 所有 `console-api` / queries 走从库 | ✅ `batch.console.read-replica.enabled` |
| `DefaultWorkerSelector` 加 Redis cache | `worker_registry` 5s TTL，每秒派发不再每次查 DB | ✅ `batch.scheduler.worker-cache.enabled` |
| Kafka topic 按租户/优先级分 | 防大租户阻塞调度全局 | ✅ `batch.mq.routing.mode=TENANT\|PRIORITY` |
| Quartz JobStore 单独库 | 避免和业务表争 WAL/锁 | ✅ `batch.trigger.quartz-datasource.enabled` |
| ~~资源 quota 改 Redis token bucket~~ | ~~替换 PG 乐观锁，去掉热点行锁~~ | ✅ **2026-04-25 完成**（默认 Redis Lua）|

### Phase 3 — 千万 → 亿

| 项 | 说明 |
|---|---|
| 分库分表（job_instance / partition / task） | 按 `tenant_id` 哈希；查询路由层（ShardingSphere / Vitess / 自实现）|
| 多 Kafka 集群按 tenant group 路由 | 单集群是瓶颈 |
| `workflow_run` 子树归档自动化 | 扇出大的 workflow 一周内必须归档 |
| 跨 AZ / 跨 DC active-active | DR 切换 RTO < 5 min |
| 观测中台 | tracing（OpenTelemetry → Tempo / Jaeger）+ 日志聚合（Loki / ELK）+ metric 长存（Mimir / VictoriaMetrics）|

---

## 7. 加分项（已经在做但可以更进一步）

| 项 | 现状 | 进一步 |
|---|---|---|
| 状态机可观测 | 状态明确，但没仪表盘 | 加 Grafana 状态分布饼图 + 转移延迟 P99 |
| Pipeline 是配置 | 数据驱动 | 加 console UI 让运维直接编排 stage 链（当前要 SQL 改 `pipeline_step_definition`）|
| 多租户隔离 | model 层完全隔离 | 加 quota 报表 / 租户 dashboard / 配额超限自动告警 |
| Workflow DAG | 强大 | 加 DAG 可视化（前端画图）|
| Worker plugin 注册 | 静态注册 | 支持热加载（jar 上传 → 立即可用），加速对接新 channel/format |

---

## 8. 这周修过的 bug 是设计层错配吗？

不是。8 个 commit 修的全是**实现层 bug 或运维层缺口**，不揭示设计层错配：

| Commit | 问题 | 类别 |
|---|---|---|
| `e360f5c9` | console 缺 partition 查询接口 | 接口缺失 |
| `9daaacce` | 6 段长期日志噪声 | 实现层 |
| `64c7910e` | shutdown 期 Redis race | 实现层 |
| `6ba5383f` | 孤儿 job 数据清理 | 数据治理（运维） |
| `28017d49` | MyBatis @Param 绑定 | 实现层 |
| `d52933a1` | Quartz 5字段 cron 误用 + drift detection | 实现层 + 防御 |
| `3dbb6d22` | workflow JOB 节点 node_params 不合并 + sourcePayload 跨节点污染 | 实现层（但暴露的是个真 gap：JOB 节点路径和 TASK 节点路径维护不对称）|
| `46e2c256` | `ensurePipelineDefinition` 跨 worker 错位污染 step | 实现层（设计上 ensure 语义不应该 upsert）|

**设计骨架（DB→Outbox→Kafka→CLAIM→EXECUTE→REPORT）一处没动**。这是健康的信号——bug 都在边角，没动核心契约。

---

## 9. 我的总评

- **架构方向**：✅ 对，对得住"经典 mature 批量调度平台"的设计目标
- **工程完成度**：✅ 高，端到端能跑通真实场景（IMPORT/EXPORT/DISPATCH 全 stage 验证完）
- **可读性 / 可维护性**：✅ 模块边界清楚，配置驱动，文档完整
- **中小批量**：🟢 production-ready
- **中等批量**：🟡 加 Phase 1 改造能扛
- **真海量**：🔴 卡在分库分表 / 自动归档 / 观测中台三处，需要 Phase 2 + Phase 3

如果只是"内部批量调度平台 / 中型 SaaS 客户量"，**直接上**，不需要等改造。
如果是"对外卖给金融/电商的核心账务批量调度产品"，**先做 Phase 1 + 上压测确认拐点**，再分阶段推 Phase 2/3。

---

## 10. 进一步阅读

- [`system-flow-overview.md`](./system-flow-overview.md) — 系统总流程（含 Outbox 详解 §7）
- [`workflow-dependency-guide.md`](./workflow-dependency-guide.md) — 作业依赖与 DAG 编排
- [`../runbook/worker-stage-coverage.md`](../runbook/worker-stage-coverage.md) — 三类 worker 全 stage 真实跑通验证
- [`architecture-truth.md`](./architecture-truth.md) — 当前架构基线
- [`/CLAUDE.md`](../../CLAUDE.md) — 编码规范与变更记录（这周修的 bug 都登记在 §变更记录）
