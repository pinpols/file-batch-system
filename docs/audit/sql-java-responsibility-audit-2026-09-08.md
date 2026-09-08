# SQL 与 Java 职责边界全量审计

日期：2026-09-08  
审计基线：`feature/backend-optimization`
审计方式：静态全量清点 + 状态枚举对账 + 事务/并发路径复核 + SQL/配置边界守护复核；整改阶段补充真实 PostgreSQL 并发与迁移集成测试。

## 验收结论

系统的核心持久化边界总体正确。CAS、`ON CONFLICT`、`FOR UPDATE SKIP LOCKED`、发件箱领取、租约、增量计数、集合聚合与 JSONB 原子合并应继续留在 SQL；动态 import/export/process/atomic SQL 应继续由 Java builder/validator 生成。不能把这些逻辑拆成“Java 先查、再计算、再写”，否则会引入丢更新、双领取和状态复活。

当前不是“大量 SQL 应搬到 Java”，而是以下三类问题：

1. 状态分类规则以字符串集合复制到多个 Mapper，新增状态后发生真实口径漂移。
2. 少数 Java 冷路径以“先读后整行覆盖”推进状态，绕开了数据库 CAS。
3. 固定 SQL 和测试/压测 SQL 仍有分层遗留，现有 CI 白名单只能防新增，不能证明历史内容已治理完成。

上线前建议修复 4 项 P1；其余工程治理可分批完成。

## 整改进度

| 项目 | 状态 | 2026-09-08 实施结果 |
|---|---|---|
| P1-1 dry-run 终态归档 | 已完成 | job/workflow 两类 dry-run 终态均纳入归档；V201 同步冷表状态约束，完整 Flyway 迁移和真实归档通过 |
| P1-2 批量日状态口径 | 已完成 | 正式批量日显式排除 `dry_run=true`，`PAUSED` 计入在途，终态子项诊断覆盖 dry-run；修复 PostgreSQL Map 别名折叠导致 Console 统计静默为 0 |
| P1-3 分发退避并发覆盖 | 已完成 | 失败 UPSERT 通过 `RETURNING` 返回计数，退避回写增加失败次数 CAS；8 线程真实库测试通过 |
| P1-4 Worker 冷路径整行覆盖 | 已完成 | register/status/drain/warmup 均改为按租户、worker 与期望状态推进的语义化 CAS；生产 `updateById` 调用归零 |

## 扫描清单

| 对象 | 数量 | 结论 |
|---|---:|---|
| MyBatis Mapper XML | 139 | 核心稳定 SQL 的主要承载层，方向正确 |
| Java 文件 | 3,221 | 185 个生产文件含 JDBC/SQL 信号，多数属于动态 SQL、校验器或基础设施 |
| SQL 文件 | 369 | Flyway、fixture、压测、运维 SQL 已形成目录边界 |
| Shell 文件 | 165 | 27 个仍含 SQL 信号，主要为 sim、SDK E2E、压测和本地工具 |
| PL/pgSQL 函数/触发器迁移 | 1 | 用于跨表租户一致性，属于数据库不变量，放置合理 |

重点人工复核了最大 Mapper、全部 `instance_status` 分类、Java 内联 SQL高密度文件、动态 `<choose>/<if>`、固定 `interval`、无租户写白名单及对应调用事务。

## 必须修复

### P1-1 归档终态集合遗漏 dry-run，数据会永久滞留热表

状态：**已修复**。

`SuccessInstanceArchiveMapper.selectArchivableInstanceIds` 只包含 `SUCCESS/PARTIAL_FAILED/FAILED/CANCELLED/TERMINATED`，遗漏已经写入 `job_instance` 的 `SUCCESS_DRY_RUN/FAILED_DRY_RUN`。这两种状态有 `finished_at`，但永远不会进入归档候选，长期演练会持续膨胀热表。

位置：`batch-orchestrator/src/main/resources/mapper/SuccessInstanceArchiveMapper.xml:10`

整改：归档候选使用完整终态集合，并补真实 PostgreSQL 集成测试，分别证明两个 dry-run 终态可复制到冷表并从热表删除。归档判断本身继续留在 SQL。

### P1-2 批量日统计状态口径已漂移，分项与总数不守恒

状态：**已修复**。产品口径确定为 dry-run 不进入正式批量日统计、结算、补跑与 SLA；独立整批演练能力按专项设计后续实施。

以下统计仍使用引入 dry-run/暂停前的旧集合：

- `BatchDayMapper` 的三组成功、失败、在途聚合遗漏 `SUCCESS_DRY_RUN/FAILED_DRY_RUN/PAUSED`。
- `JobInstanceMapper.selectBatchDayMetrics` 同样遗漏上述状态，却被 `BatchDaySettleScheduler` 直接用于 `SETTLING -> SETTLED/FAILED` 判定。
- 两处“终态实例仍有活跃子项”诊断遗漏 dry-run 终态，无法发现演练实例的终态一致性破坏。

位置：

- `batch-console-api/src/main/resources/mapper/BatchDayMapper.xml:24`
- `batch-orchestrator/src/main/resources/mapper/JobInstanceMapper.xml:522`
- `batch-orchestrator/src/main/resources/mapper/JobInstanceMapper.xml:544`
- `batch-console-api/src/main/resources/mapper/ConsoleClusterDiagnosticMapper.xml:142`

风险不只是展示错误。当前 `total_count` 包含 dry-run，其他分类却不包含；仅有 `FAILED_DRY_RUN` 时可能被结算逻辑视为“无失败”。同时 ADR-026 声称支持独立 batch-day dry-run，但当前 `(tenant_id, calendar_code, biz_date)` 唯一键没有 dry-run 维度，生产代码也没有创建 `dry_run=true` 的 `batch_day_instance`，文档与实现不一致。

整改前先明确产品语义：

- 若 dry-run 不参与真实批量日，聚合 SQL 必须显式排除 `dry_run=true`，并实现独立演练批量日或修正文档。
- 若参与同一批量日，成功/失败分类必须纳入 dry-run 终态，`PAUSED` 必须计入非终态。

SQL 继续负责集合聚合；状态分类应由公共 catalog 传参或受静态契约测试守护，不能继续复制字符串。

### P1-3 分发渠道退避的三段读写存在并发覆盖

状态：**已修复**。

失败路径当前执行：原子递增失败次数 -> 查询当前次数 -> Java 计算退避 -> 无条件写 `next_probe_at`。注释声称“无竞争”，但并发失败下旧线程可能按较小次数计算后，在新线程之后覆盖更长退避，造成渠道过早 half-open。

位置：`batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/DispatchChannelHealthService.java:207`

整改：让递增语句返回本次 `consecutive_failures`，Java 仅计算时间；回写增加 `WHERE consecutive_failures = :expectedFailures` CAS。CAS 失败表示已有更新的失败结果，不能覆盖。若 Citus 支持等价纯参数 CTE，可合并成单次往返；不要改成普通 Java 锁，Java 锁无法覆盖多实例。

### P1-4 Worker 冷路径整行覆盖可使状态回退

状态：**已修复**。通用 `updateById` 仅保留给集成测试夹具，生产路径均使用带租户与状态前态的语义化更新；并发旧 ONLINE 快照无法覆盖 DRAINING 的真实 PostgreSQL 用例已通过。

`WorkerRegistryMapper.updateById` 只按全局 `id` 更新，并整行覆盖 status、心跳和 drain 字段。`warmup/startDrain/updateStatus` 都采用“查询快照 -> Java 改 record -> updateById”；并发 decommission、heartbeat 或 drain 时，旧快照可能复活/回退新状态。该语句还被租户守护白名单豁免，防御纵深不足。

位置：

- `batch-orchestrator/src/main/resources/mapper/WorkerRegistryMapper.xml:242`
- `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultWorkerDrainGovernanceService.java:48`
- `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DefaultWorkerRegistryService.java:306`

整改：废弃生产路径的通用整行状态覆盖，改成按 `(tenant_id, worker_code/id, expected_status)` 的语义化 CAS 方法，例如 `startDrainIfOnline`、`warmupIfOffline`；热心跳继续使用当前数据库 CASE 防复活。测试夹具可保留单独 save helper，但不能与生产状态推进共用。

## 建议治理

### P2-1 建立状态分类唯一事实源

状态：**已完成核心契约**。`JobInstanceStatus` 现根据 `lifecycle()` 自动派生终态、活跃态、成功终态和非成功终态集合，`LifecycleStatusCatalog` 直接复用，不再维护第二份 Java 字符串集合；分类测试保证每个枚举值恰好归入终态或活跃态。关键批量日门禁 SQL 增加契约测试，新增状态未同步时直接失败。

治理同时发现并修复真实缺陷：SAME_JOB / SAME_JOB_GROUP 前日门禁原来遗漏 `PARTIAL_FAILED`、`SUCCESS_DRY_RUN`、`FAILED_DRY_RUN` 三个终态，会把已结束实例永久计作未完成。两条 SQL 已对齐完整终态，真实 PostgreSQL 测试验证三个终态放行、`PAUSED` 仍阻塞。报表 SQL 继续保留各自明确的 dry-run 隔离口径，不把聚合结果拉到 Java 后逐行统计。

### P2-2 Mapper 中业务默认值过多，存在三份默认值漂移

状态：**已完成生产写路径收敛**。复核当前 Mapper 后，历史提及的定义类 Mapper 已不再包含 `<otherwise>` 产品默认值；剩余集中在 `WorkerRegistryMapper.insert` 和 `BatchDayInstanceMapper.insert`。两处现已改为绑定完整对象：Worker 默认并发 10 由 `WorkerRegistryEntity.DEFAULT_MAX_CONCURRENT` 明确提供，批量日三个创建路径显式提供状态、计数、时区/DST 快照、版本和 `dryRun=false`。DDL DEFAULT 继续作为绕过应用写入时的最后防线。

更新语句中的 `coalesce(入参, 原值)` 属于局部字段更新语义，不是创建默认值，本轮保留；数据库时间和 DDL DEFAULT 也继续保留。这样 Java 是产品策略权威，Mapper 只绑定完整对象，同时数据库仍有独立安全兜底。

### P2-3 固定 SQL 仍嵌在非持久化组件

状态：**已修复**。`ConsolePipelineProgressDirtyPublisher` 的固定查询与结果映射已迁入 MyBatis Mapper，publisher 只保留调度、游标、节流和事件发布；新增真实 PostgreSQL 测试覆盖聚合时间、可空关联实例及租户复合关联。

`AdminTestDataCleanupRepository` 原有 300 余行级联 SQL 字符串已迁入专用 MyBatis Mapper XML。删除目标由 Java 枚举封闭，不使用动态表名或 `${}`；repository 只保留保护租户校验、FK 依赖顺序和删除结果汇总。prefix 与精确租户两种模式均有真实 PostgreSQL 全分支执行测试。

### P2-4 Misfire 过期策略隐藏在 SQL

状态：**已修复**。新增 `batch.trigger.runtime.misfire-pending-retention-days`（默认 7 天），由 Java 计算 `expiresAt`，Mapper 只持久化参数；Spring、Compose 与 Helm 配置入口已同步。

`TriggerMisfirePendingMapper.insertPending` 固定 `now() + interval '7 days'`。这是保留/审批产品策略，不是数据库不变量，应由配置生成 `expiresAt` 后绑定；DB 只保存结果。否则不同环境无法调整，文档和配置扫描也看不到该开关。

位置：`batch-trigger/src/main/resources/mapper/TriggerMisfirePendingMapper.xml:22`

### P2-5 SQL 边界 CI 是增量门禁，不是存量清零证明

状态：**已完成门禁收紧，存量继续分批治理**。`check-sql-config-boundaries.sh` 已取消整文件放行，改为按文件登记可执行 SQL 命中行数预算；纯注释、日志展示和 HTTP curl 不计为 SQL。已把 atomic sim、运行态 reset、quiesce、ADR-046、CI/本地 SDK 真链路、sim harness 及 DR 演练 SQL 迁入对应 `sql` 目录；DR 的源库/恢复库校验复用同一查询文件，数据库标识符通过 psql `format(%I)` 处理。SDK E2E 同时消除了固定定义主键、API key 冲突后返回无效新密钥及无租户条件清理的问题。当前仅剩 1 个历史文件只能减少、不能增加，未登记文件预算为 0；只保存检测表达式且不执行 SQL 的门禁自身明确豁免。剩余 seed 场景校验 SQL 继续分批迁移。

整批量日 Dry-run 的推荐模型、阶段划分与验收矩阵见
[`batch-day-dry-run-enhancement-plan-2026-09-08.md`](../plans/batch-day-dry-run-enhancement-plan-2026-09-08.md)。

### P3 可维护性整理

- `BatchDayInstanceMapper.updateWithCas` 已补 `tenant_id` 条件并移除语句级豁免；CAS 现同时校验 tenantId、全局 id 与 version。
- 文件治理 stale sweep 已取消 `interval '5 minutes'` 回捞窗口：实例更新改为 `UPDATE ... RETURNING id`，同一事务的 step 更新只消费本轮明确返回的 ID。这样既消除隐藏时间策略，也不会误处理近期由其他路径置为 FAILED 的实例；真实 PostgreSQL 对抗用例覆盖批大小边界和并发路径隔离。
- `ShedLockProviderFactory` 的 DDL 只在 local/dev `auto-create=true` 下使用，生产默认 false，现状可接受；生产继续以 Flyway 为权威。
- Console SLA 默认排除 dry-run 与 ADR-026“默认指标隔离”一致，不应因为统一终态集合而盲目纳入。

## 应保留在 SQL 的逻辑

| 类型 | 原因 |
|---|---|
| 状态 CAS、终态防复活、lease claim/renew/reclaim | 原子性必须覆盖多实例 |
| `ON CONFLICT` 幂等键 | 唯一约束才是并发最终裁判 |
| Outbox 领取和批量结果更新 | 避免双投递窗口和逐条往返 |
| `FOR UPDATE SKIP LOCKED` 批量领取 | 数据库行锁是跨实例协调边界 |
| 分区终态与实例增量计数同 SQL | 防重复 report 导致计数重复或丢失 |
| JSONB 原子 merge、数据库时间 | 防读改写覆盖，并统一时间基准 |
| 报表聚合、窗口函数、百分位 | 集合计算应靠数据库，避免拉全量数据 |
| 跨表租户一致性触发器 | 是数据库不变量和最后防线 |

## 应保留在 Java 的逻辑

| 类型 | 原因 |
|---|---|
| 状态迁移意图、权限、产品策略 | 可读、可测试，并由 SQL CAS执行最终写入 |
| import/export/process/atomic 动态 SQL builder | 表名、列名、分片与策略是运行时结构，无法作为普通 bind 参数 |
| SQL AST/identifier/placeholder 校验 | 属于执行引擎安全边界 |
| 指数退避、窗口选择、DST 解析 | 业务算法应可单测；最终写入仍需 CAS |
| 事务编排、外部 I/O、Outbox 事件构造 | 数据库不应承担网络和跨资源流程 |

## 整改顺序

1. 修复归档 dry-run 终态集合，并补 PG 集成测试。
2. 明确 batch-day dry-run 产品语义，修复统计/结算和 Console 展示口径。
3. 修复分发健康退避并发 CAS，增加双线程真实数据库测试。
4. 用语义化 CAS 取代 Worker Registry 生产路径的 `updateById`。
5. 建立状态分类漂移门禁，再治理 Mapper 默认值和 Java 固定 SQL。
6. ~~将 shell 整文件白名单改成只减不增基线~~（已完成）；继续清理最后 1 个历史文件。

## 验收标准

- 所有 `JobInstanceStatus` 都明确归入成功、失败、非终态或“有意排除”之一，且 `total = classified + explicitlyExcluded`。
- 两个 dry-run 终态可归档，终态活跃子项诊断可识别 dry-run。
- 并发渠道失败不能缩短已写入的 `next_probe_at`。
- 并发 decommission/drain/heartbeat/warmup 不能发生状态回退。
- 新增状态时，CI 会因关键 SQL 分类未更新而失败。
- SQL 边界检查的历史白名单数量只能下降，不能以整文件方式掩盖新增内联 SQL。
