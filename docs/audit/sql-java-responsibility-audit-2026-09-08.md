# SQL 与 Java 职责边界全量审计

日期：2026-09-08  
审计基线：`codex/control-plane-throughput-optimization` / `19e1d6ffa`  
审计方式：静态全量清点 + 状态枚举对账 + 事务/并发路径复核 + SQL/配置边界守护复核。本轮未执行运行态、单元、集成或压测。

## 验收结论

系统的核心持久化边界总体正确。CAS、`ON CONFLICT`、`FOR UPDATE SKIP LOCKED`、发件箱领取、租约、增量计数、集合聚合与 JSONB 原子合并应继续留在 SQL；动态 import/export/process/atomic SQL 应继续由 Java builder/validator 生成。不能把这些逻辑拆成“Java 先查、再计算、再写”，否则会引入丢更新、双领取和状态复活。

当前不是“大量 SQL 应搬到 Java”，而是以下三类问题：

1. 状态分类规则以字符串集合复制到多个 Mapper，新增状态后发生真实口径漂移。
2. 少数 Java 冷路径以“先读后整行覆盖”推进状态，绕开了数据库 CAS。
3. 固定 SQL 和测试/压测 SQL 仍有分层遗留，现有 CI 白名单只能防新增，不能证明历史内容已治理完成。

上线前建议修复 4 项 P1；其余工程治理可分批完成。

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

`SuccessInstanceArchiveMapper.selectArchivableInstanceIds` 只包含 `SUCCESS/PARTIAL_FAILED/FAILED/CANCELLED/TERMINATED`，遗漏已经写入 `job_instance` 的 `SUCCESS_DRY_RUN/FAILED_DRY_RUN`。这两种状态有 `finished_at`，但永远不会进入归档候选，长期演练会持续膨胀热表。

位置：`batch-orchestrator/src/main/resources/mapper/SuccessInstanceArchiveMapper.xml:10`

整改：归档候选使用完整终态集合，并补真实 PostgreSQL 集成测试，分别证明两个 dry-run 终态可复制到冷表并从热表删除。归档判断本身继续留在 SQL。

### P1-2 批量日统计状态口径已漂移，分项与总数不守恒

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

失败路径当前执行：原子递增失败次数 -> 查询当前次数 -> Java 计算退避 -> 无条件写 `next_probe_at`。注释声称“无竞争”，但并发失败下旧线程可能按较小次数计算后，在新线程之后覆盖更长退避，造成渠道过早 half-open。

位置：`batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/DispatchChannelHealthService.java:207`

整改：让递增语句返回本次 `consecutive_failures`，Java 仅计算时间；回写增加 `WHERE consecutive_failures = :expectedFailures` CAS。CAS 失败表示已有更新的失败结果，不能覆盖。若 Citus 支持等价纯参数 CTE，可合并成单次往返；不要改成普通 Java 锁，Java 锁无法覆盖多实例。

### P1-4 Worker 冷路径整行覆盖可使状态回退

`WorkerRegistryMapper.updateById` 只按全局 `id` 更新，并整行覆盖 status、心跳和 drain 字段。`warmup/startDrain/updateStatus` 都采用“查询快照 -> Java 改 record -> updateById”；并发 decommission、heartbeat 或 drain 时，旧快照可能复活/回退新状态。该语句还被租户守护白名单豁免，防御纵深不足。

位置：

- `batch-orchestrator/src/main/resources/mapper/WorkerRegistryMapper.xml:242`
- `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultWorkerDrainGovernanceService.java:48`
- `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DefaultWorkerRegistryService.java:306`

整改：废弃生产路径的通用整行状态覆盖，改成按 `(tenant_id, worker_code/id, expected_status)` 的语义化 CAS 方法，例如 `startDrainIfOnline`、`warmupIfOffline`；热心跳继续使用当前数据库 CASE 防复活。测试夹具可保留单独 save helper，但不能与生产状态推进共用。

## 建议治理

### P2-1 建立状态分类唯一事实源

`JobInstanceStatus` 已有 `lifecycle()`，orchestrator 又有 `LifecycleStatusCatalog`，但 Console、归档、诊断、通知和压测 SQL仍各自复制字符串集合。`CapacityProfileMapper` 已包含 dry-run，其他 Mapper 未同步，证明人工同步不可持续。

建议新增状态分类契约：由 Java 枚举生成 `terminal/success/failure/nonTerminal` 参数集合，或为报表查询提供受控公共 SQL fragment；再加测试扫描关键 Mapper 的分类覆盖。不要把聚合结果拉到 Java 后逐行统计。

### P2-2 Mapper 中业务默认值过多，存在三份默认值漂移

`JobDefinitionMapper`、`BatchWindowMapper`、`BusinessCalendarMapper`、`WorkflowNodeMapper`、`TenantQuotaPolicyMapper`、`WorkerRegistryMapper`、`BatchDayInstanceMapper` 等在 XML `<choose>` 中保存默认策略；同一默认值还存在于 Java 和 DDL。

建议由 Java command factory 完成产品默认值，数据库 DEFAULT 作为最终防线，Mapper 只绑定完整对象。必须保留的 DB 时间和版本初值可以继续由 SQL/DDL生成。优先治理具有业务语义的时区、DST、窗口动作、重试策略、优先级和并发上限，不必机械删除所有 `coalesce`。

### P2-3 固定 SQL 仍嵌在非持久化组件

`ConsolePipelineProgressDirtyPublisher` 同时承担定时器、游标、限流、SQL、ResultSet 映射和事件发布。应把固定查询抽到 Mapper/repository，publisher 只保留调度与发布。`AdminTestDataCleanupRepository` 虽符合“repository 可承载 SQL”的现行规范，但 300 余行级联字符串难以随 FK/表演进，适合迁到 Mapper XML 或 SQL resource，Java 保留事务编排和删除结果汇总。

### P2-4 Misfire 过期策略隐藏在 SQL

`TriggerMisfirePendingMapper.insertPending` 固定 `now() + interval '7 days'`。这是保留/审批产品策略，不是数据库不变量，应由配置生成 `expiresAt` 后绑定；DB 只保存结果。否则不同环境无法调整，文档和配置扫描也看不到该开关。

位置：`batch-trigger/src/main/resources/mapper/TriggerMisfirePendingMapper.xml:22`

### P2-5 SQL 边界 CI 是增量门禁，不是存量清零证明

`check-sql-config-boundaries.sh` 当前通过，但整文件白名单覆盖 50 余项。实际仍有压测报告 SQL、sim reset/quiesce SQL、SDK E2E seed/断言 SQL等内联内容。建议把白名单改成“语句级基线 + 只减不增”，分批迁到 `load-tests/sql`、`scripts/*/sql` 和 `docs/test-data`；避免整文件白名单让后续新增 SQL 继续被放行。

整批量日 Dry-run 的推荐模型、阶段划分与验收矩阵见
[`batch-day-dry-run-enhancement-plan-2026-09-08.md`](../plans/batch-day-dry-run-enhancement-plan-2026-09-08.md)。

### P3 可维护性整理

- `BatchDayInstanceMapper.updateWithCas` 可补 `tenant_id`，移除语句级豁免；虽然全局 id + version 已降低可利用性，但实体本身已有 tenantId。
- 文件治理 stale sweep 的 `interval '5 minutes'` 在同一事务内不会漏掉本轮刚更新行，因此不是当前 bug；可改为具名 lookback 参数以消除隐藏策略。
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
6. 将 shell 整文件白名单改成只减不增基线，逐批清理剩余 27 个候选文件。

## 验收标准

- 所有 `JobInstanceStatus` 都明确归入成功、失败、非终态或“有意排除”之一，且 `total = classified + explicitlyExcluded`。
- 两个 dry-run 终态可归档，终态活跃子项诊断可识别 dry-run。
- 并发渠道失败不能缩短已写入的 `next_probe_at`。
- 并发 decommission/drain/heartbeat/warmup 不能发生状态回退。
- 新增状态时，CI 会因关键 SQL 分类未更新而失败。
- SQL 边界检查的历史白名单数量只能下降，不能以整文件方式掩盖新增内联 SQL。
