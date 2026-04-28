# PROCESS Worker 已知问题与下一步计划

> 评估日期：2026-04-28。范围：`batch-worker-process` 的 WAP+bookends 五段链路（`PREPARE → COMPUTE → VALIDATE → COMMIT → FEEDBACK`）、内置 `sqlTransformCompute`、`batch.process_staging`、PROCESS E2E 与 console 配置入口。
>
> 与 [`batch-classification-and-gaps.md`](./batch-classification-and-gaps.md) §4.5 的分工：§4.5 说明已落地能力；本文记录落地后的漏洞、bug、设计缺陷和下一步修复计划。每个修复合入后应在本文对应条目标记状态，并同步修正文档中已不准确的流程描述。

## 0. 结论

PROCESS Worker 的 happy path 已跑通：配置 `pipeline_step_definition.impl_code = sqlTransformCompute` 后，可以读取业务源 SQL、写 staging、校验、发布到目标表并回传 watermark。当前主要风险不在“能不能跑”，而在 **生产双库部署、staging 隔离、失败重跑、SQL 参数边界、console 配置入口**。

当前优先级判断：

| 级别 | 数量 | 主题 |
|---|---:|---|
| P0 | 4 | 生产可用性 / 多租户隔离 / 数据残留 / 水位正确性 |
| P1 | 7 | console 配置、参数污染、重跑语义、资源上限、清理任务、空批次语义 |
| P2 | 7 | 类型矩阵、指标、测试缺口、SPI 文档、长期演进 |

本轮 hardening 状态（2026-04-28）：

- 已修：P0-2 / P0-3 / P0-4 / P1-1 / P1-2 / P1-3 / P1-4 / P1-5 / **P1-6 / P1-7**。
- 已缓解：P0-1 已把 `batch.process_staging` 纳入业务库初始化脚本；平台 Flyway 中的历史 V75 表保留兼容，后续仍建议补真双库 E2E。
- 已文档化:**P2-1 / P2-2 / P2-7**(`system-flow-overview.md` §7.9.8 给出 writeMode 重跑表 / JSONB 类型矩阵 / 自定义 plugin SPI 边界与示例)。
- 未修：P2-3 / P2-4 / P2-5 / P2-6 / 双库 E2E 待补。

## 1. P0 — 必须先修

### P0-1. `process_staging` 的数据库归属与代码实际连接不一致

**现象**

- V75 在 `db/migration/V75__add_process_staging_table.sql` 创建 `batch.process_staging`，这是平台 Flyway migration 路径。
- `SqlTransformComputePlugin` 只注入 `processBusinessDataSource`，`COMPUTE / VALIDATE / COMMIT / FEEDBACK` 全部通过业务库连接执行 `batch.process_staging` SQL。
- `batch-defaults.yml` 默认把业务库指向 `batch_business`，平台库指向 `batch_platform`。
- E2E 的 `E2eProcessWorkerDataSourceConfiguration` 把 `processPlatformDataSource` 和 `processBusinessDataSource` 都指向同一个 Testcontainers datasource，掩盖了真实双库部署问题。
- `runtime-module-communication.md` 目前还写着 PROCESS 通过平台库访问 `process_staging`，而代码不是这样。

**影响**

在真实 `batch_platform` / `batch_business` 分库部署下，`sqlTransformCompute` 首次运行大概率报：

```text
relation "batch.process_staging" does not exist
```

这会让 PROCESS 在本地/E2E 看似正常，生产部署直接失败。

**修复方向**

1. **推荐**：明确 staging 与 source/target 同库，V75 对应 DDL 应迁到业务库初始化路径（例如 `scripts/db/business/` 或独立 business migration），并在 runbook 写清必须在 `batch_business` 执行。
2. 备选：把 `process_staging` 留在平台库，但插件需要同时持有 platform + business datasource，这会让 `INSERT INTO target SELECT FROM staging` 跨库不可行，除非改为应用层搬运或 FDW，不推荐。
3. 补一条“真双库”E2E：platform/business 两个 datasource 指向不同 PostgreSQL database，验证 staging DDL 位置正确。

**验收**

- 已完成：`scripts/db/business/create_biz_tables.sql` 现在会在业务库创建 `batch.process_staging` 及隔离索引，和 `processBusinessDataSource` 的实际访问路径一致。
- 待补：真双库 E2E 仍未落地；`db/migration/V75__add_process_staging_table.sql` 作为历史兼容仍会在平台库创建同名表，不再作为 PROCESS 运行依赖。

### P0-2. staging 读写只按 `batch_key` 过滤，缺少 tenant/target 约束

**现象**

`COMMIT` 和 `FEEDBACK` 当前按 `batch_key` 过滤：

```sql
FROM batch.process_staging
WHERE batch_key = :batchKey
```

```sql
DELETE FROM batch.process_staging WHERE batch_key = :batchKey
```

`VALIDATE` 的示例 SQL 也只约定 `:batchKey`。表上明明有 `tenant_id / target_schema / target_table`，但没有参与隔离。

**影响**

当前 `batchKey = process-<taskId>-<uuid>`，碰撞概率极低；但从安全审计和未来演进看，这仍是多租户软隔离缺陷。一旦 batchKey 复用、人工补数、测试数据污染或未来为了重跑稳定化改造 batchKey，可能发布或删除别的 tenant/target 的 staging 行。

**修复方向**

- 所有框架生成的 staging SQL 强制加：

```sql
AND tenant_id = :tenantId
AND target_schema = :targetSchema
AND target_table = :targetTable
```

- `validations[].checkSql` 至少必须能绑定 `:batchKey` 和 `:tenantId`；更进一步见 P1-3，只允许读本批 staging。
- V75 后续 migration 增加 `(tenant_id, batch_key)` 和 `(tenant_id, target_schema, target_table, batch_key)` 索引。

**验收**

- 已完成：`SqlTransformComputePluginIntegrationTest` 构造相同 `batch_key`、不同 tenant 的 staging 行，验证 COMMIT/FEEDBACK 只处理本 tenant/target。

### P0-3. COMMIT 与 FEEDBACK 分离，成功发布后崩溃会留下孤儿 staging

**现象**

`SqlTransformComputePlugin.commit()` 发布 target，`FeedbackStep` 再调用 `plugin.feedback()` 删除 staging。`AbstractStageExecutor` 没有事务包裹整个 stage loop，两个阶段是独立 SQL 调用。`FeedbackStep` 还会吞掉 runtime exception，以免 target 已发布后把任务标失败。

**影响**

Worker 在 COMMIT 成功后、FEEDBACK 前崩溃，或 FEEDBACK 删除失败，会导致 target 已写入但 staging 永久残留。V75 虽然加了 `staged_at` 索引，但当前没有自动清理任务。

**修复方向**

1. COMMIT 阶段用同一事务完成 publish + cleanup，FEEDBACK 只做指标/审计/水位后置动作。
2. 同时实现 P1-6 的 orphan cleanup scheduler，作为兜底。
3. 文档改写失败语义：VALIDATE 失败保留 staging 是设计，COMMIT 成功后的 staging 残留是异常，需要自动清。

**验收**

- 已完成：`COMMIT` 在业务库事务中执行 publish + cleanup；`FEEDBACK` 保持幂等清理兜底。后续仍建议补 orphan cleanup scheduler 处理历史残留。

### P0-4. watermark 从 source SQL 二次查询，可能推进到未发布数据之后

**现象**

`compute()` 先执行 `INSERT INTO batch.process_staging ... FROM (<sourceSql>) base`，然后 `queryMaxWatermark()` 再跑一次 `sourceSql` 取 `max(watermarkColumn)`。

**影响**

- 大 SQL 读压翻倍。
- 两次 SELECT 之间业务表发生写入时，第二次 max 可能包含未 staging、未发布的新行，导致 `highWaterMarkOut` 虚高。下一次增量会跳过这些真实未处理数据。

**修复方向**

- watermark 从 staging 里取，而不是重跑 source SQL。
- spec 增加 `watermarkColumnType` 或从 target 表列类型推断 cast。默认支持 `bigint / numeric / timestamptz / date / text`。

**验收**

- 已完成：watermark 改为从本批 staging payload 取，不再二次执行 source SQL；集成测试使用带副作用的 source SQL 验证不会被第二次查询推进。

## 2. P1 — 尽快修

### P1-1. Console Excel 配置可能拒绝 `sqlTransformCompute`

**现象**

`ProcessStepBeanRegistrar` 只登记 `ProcessStageStep`：`PROCESS_PREPARE / PROCESS_COMPUTE / PROCESS_VALIDATE / PROCESS_COMMIT / PROCESS_FEEDBACK`。但正式 PROCESS 配置中，`COMPUTE` step 的 `impl_code` 应该是 `ProcessComputePlugin.implCode()`，例如 `sqlTransformCompute`。

`ConfigPackageExcelValidator` 会用 `step_registry[module=PROCESS]` 校验 `impl_code`。如果 PROCESS worker 启动后 registry 非空，Excel 上传 `impl_code=sqlTransformCompute` 很可能被判定为“not registered”。

**修复方向**

- PROCESS registry 同时登记 `ProcessStageStep` 和 `ProcessComputePlugin`。
- 或者新增 `step_registry.kind = STAGE_STEP / COMPUTE_PLUGIN`，console 对 PROCESS 的 COMPUTE stage 使用 plugin 白名单。
- 补 console validator 测试：registry 包含 `sqlTransformCompute` 时上传通过。

**状态**

- 已完成：`ProcessStepBeanRegistrar` 现在同时登记 stage step 与 compute plugin，`sqlTransformCompute` 会进入 `step_registry(module=PROCESS)`。

### P1-2. payload `metadata` 与 `batchKey` 的设计没有真正落到运行参数

**现象**

`ProcessPayload` 声明了 `batchKey` 和 `metadata`，注释说 metadata 会透传给 plugin 作为命名参数源。但 `ProcessStepExecutionAdapter` 只 flatten 顶层 payload；`SqlTransformComputePlugin.buildSqlParams()` 只拷贝 scalar attributes，嵌套 `metadata` 不会展开。`ProcessPayload.batchKey` 也没有赋给 `ProcessJobContext.batchKey`，executor 总是生成新的 batchKey。

**影响**

- 用户按文档把参数放到 `metadata`，SQL 写 `:customerType` 会在 PREPARE 报 unknown parameter。
- 用户希望传入稳定 batchKey 做补偿/重跑隔离，但实际不会生效。

**修复方向**

- 明确支持一种参数模型：
  - 内置参数：`tenantId / jobCode / workerId / traceId / highWaterMarkIn / batchKey / stepCode`
  - 业务参数：`payload.metadata` 展开为安全命名空间，如 `:metadata_customerType`
  - step 固定参数：`sqlTransformCompute.params`
- 如继续支持 payload batchKey，必须校验 tenant/task 范围，避免人为复用造成串数据；否则删除该字段和文档承诺。

**状态**

- 已完成：`SqlTransformComputePlugin.buildSqlParams` 改为白名单 ——
  - 内置：`tenantId / jobCode / workerId / traceId / stepCode / highWaterMarkIn / batchKey / targetSchema / targetTable / bizDate`
  - 业务：仅 `payload.metadata.<key>` 展开为 `:metadata_<key>`，复杂值（嵌套 Map / List）拒绝绑定
  - 用户：`spec.params` 显式提供（不可覆盖内置 / 不可使用 `metadata_*` 前缀，PREPARE 即拒）
- 已完成：`ProcessStepExecutionAdapter.buildContext` 把 `ProcessPayload.batchKey`（如非空）透传到 `ProcessJobContext.batchKey`，业务可传稳定 batchKey 做补偿 / 重跑隔离；与 P0-2 配合（staging WHERE 强制 tenant/target 过滤），即使跨 tenant 复用同 batchKey 也不会污染 commit / cleanup。
- 已完成：删除 `buildSqlParams` 中的 attributes 自动散开 + `STRUCTURED_RUNTIME_KEYS` 否定列表，业务必须显式经 metadata 传参。

### P1-3. validation SQL 跳过 schema allowlist，读权限过宽

**现象**

`validateUserCheckSelect()` 为了让规则读取 `batch.process_staging`，调用 `validate(raw, false)`，完全跳过 schema allowlist。这样用户 checkSql 可以 SELECT 任意业务表或系统表，只要数据库账号有权限。

**影响**

- 数据质量规则可能误读 target/source 表，而不是验证 staging，导致“校验通过”并不代表本批 staging 数据正确。
- 给配置人员提供了额外的数据探查入口。

**修复方向**

- `validateUserCheckSelect` 仍允许 `batch.process_staging`，但表名白名单只能是 `batch.process_staging`。
- 如需要跨表校验，后续用结构化 validation DSL 单独设计，不用任意 SQL 直连。

**状态**

- 已完成：`validateUserCheckSelect()` 只允许读取 `batch.process_staging`，新增单测覆盖拒绝业务表读取。

### P1-4. SQL 参数来源过宽，`spec.params()` 还可以覆盖运行时参数

**现象**

`buildSqlParams()` 会把 context attributes 中所有 scalar 值放入 SQL 参数，然后 `params.putAll(spec.params())`。这意味着配置里的 `params.tenantId`、`params.highWaterMarkIn` 可以覆盖运行时真实租户和水位。

**影响**

- 配置错误或恶意配置可让 SQL 读取别的 tenant 数据。
- `:bizDate`、`:status` 这类通用名可能来自上游 payload、系统 attributes 或 spec.params，来源不清晰。

**修复方向**

- 内置运行时参数不可覆盖。`spec.params()` 如果包含保留名，PREPARE 直接失败。
- 不再自动展开全部 attributes，只允许显式业务参数来源。
- 文档列出保留参数名和业务参数命名规范。

**状态**

- 已完成：`spec.params()` 包含 `tenantId / jobCode / workerId / highWaterMarkIn / traceId / stepCode / batchKey / targetSchema / targetTable / bizDate` 等保留名时，PREPARE 解析阶段直接失败；`metadata_*` 前缀同样保留。
- 已完成：`buildSqlParams()` 不再自动散开 attributes（见 P1-2 状态），业务参数收敛到 `payload.metadata` + `spec.params` 两个显式来源。

### P1-5. 空增量批次被当成失败

**现象**

`validate()` 中 `stagedRows == 0` 返回 `PROCESS_STAGED_EMPTY` 失败。

**影响**

对 INCREMENTAL 任务，“没有新数据”通常应该是成功 no-op，并保留旧 watermark。当前行为会让正常空跑进入 FAILED，触发 retry 或告警。

**修复方向**

- spec 增加 `emptyResultPolicy: SUCCESS | FAIL`，默认对 INCREMENTAL 为 `SUCCESS`，对某些强制产出任务可配置 `FAIL`。
- success no-op 时 `processedCount=0`，不写 `highWaterMarkOut` 或写回 `highWaterMarkIn`，二者选一并文档化。

**状态**

- 已完成：新增 `emptyResultPolicy: SUCCESS | FAIL`，默认 `SUCCESS`；空批次成功时 `processedCount=0` 且不写 `highWaterMarkOut`。

### P1-6. staging 没有写入上限和资源保护

**现象**

`buildStagingInsertSql` 是无上限的 `INSERT INTO staging SELECT ... FROM (<sourceSql>)`。配置 SQL 写错、笛卡尔积或数据激增时，会直接把业务库和 staging 表打爆。

**修复方向**

- spec 增加 `maxStagedRows`，默认例如 100 万。
- 写入时包一层 `LIMIT max + 1` 或先 `SELECT count` 预检；超过上限返回 `PROCESS_STAGED_OVERFLOW`。
- 指标记录每次 staged rows，超过阈值告警。

**状态**

- 已完成:`SqlTransformComputeSpec` 加 `maxStagedRows` 字段(默认 `DEFAULT_MAX_STAGED_ROWS=1_000_000`),compute 写完 staging 后 row count 超阈值返 `PROCESS_STAGED_OVERFLOW` + 同步删本批 staging,target 完全不写。`SqlTransformComputeSpecTest` 加 3 个 parse 用例(默认/自定义/非法值);`SqlTransformComputePluginIntegrationTest` 加 `compute_failsAndCleansStaging_whenStagedRowsExceedMaxStagedRows` 端到端用例。

### P1-7. 缺少 orphan cleanup scheduler 与运行手册

**现象**

V75 有 `staged_at` 索引，文档也承认 VALIDATE 失败会保留 staging，但没有 `ProcessStagingOrphanCleaner`，也没有清理 runbook。

**修复方向**

- 新增 ShedLock 定时任务，每 15 分钟清理超过保留期的 staging。
- 保留期按失败取证需要配置，默认 24h 或 72h。
- 加指标：`process_staging_orphan_cleaned_total`、`process_staging_oldest_age_seconds`。

**状态**

- 已完成:新增 `batch-worker-process/.../cleanup/ProcessStagingOrphanCleaner` + `ProcessStagingCleanupProperties`(`batch.worker.process.staging-cleanup.{enabled,interval,retentionHours,batchSize}`,默认 enabled=true / 15 分钟 / 24 小时 / 5000 行)。`@SchedulerLock` 互斥多实例并发;Counter `process_staging_orphan_cleaned_total` + Gauge `process_staging_oldest_age_seconds` 暴露给 Prometheus。runbook 与运行参数说明见 `docs/architecture/system-flow-overview.md` §7.9.7。

## 3. P2 — 排期修复或文档化

### P2-1. `writeMode=INSERT` 的重跑语义不清晰

`INSERT` 模式遇到 retry/rerun 时很容易撞 PK/UK。建议默认推荐 `UPSERT` 或 `INSERT_IGNORE`；`INSERT` 需要显式 `acceptDuplicateKeyOnRetry: true` 或文档声明仅适用于临时表/无唯一键目标表。

**状态**

- 已文档化:`system-flow-overview.md` §7.9.8 加 writeMode 重跑语义对照表(INSERT/UPSERT/INSERT_IGNORE 三档及推荐场景),不在本期改代码默认值。

### P2-2. JSONB 通用 staging 的目标列类型矩阵未定义

`jsonb_populate_record(NULL::target, payload)` 对 numeric/date/text 可用，但 array、enum、bytea、PostGIS、自定义 composite 类型未验证。需要 PREPARE 阶段读取 `information_schema.columns` 做类型白名单。

**状态**

- 已文档化:`system-flow-overview.md` §7.9.8 列出已验证 / 待验证 / 不支持三档类型矩阵。运行时 PREPARE 类型白名单留到下一期评估。

### P2-3. `ProcessRuntimeKeys.PROCESS_PARSED_SPEC` 在 attributes 中塞强类型对象

当前 attributes 既作为运行时 Map，也会参与 step summary 构建。强类型对象不一定可 JSON 序列化。建议将 plugin 私有状态放进 `ProcessJobContext` 的 typed field，或保证 summary 不会序列化该对象。

### P2-4. 缺 Micrometer 指标

建议补：

- `process_compute_staged_rows`
- `process_commit_published_rows`
- `process_validation_failed_total`
- `process_staging_orphan_cleaned_total`
- `process_stage_duration_seconds`

### P2-5. unknown plugin 的行为需要产品化决策

当前找不到 plugin 时，5 个 stage 走默认 success/no-op，`processedCount=0`。这有利于默认空模板跑通，但生产配置 typo 会静默成功。建议：

- 默认 pipeline 的 `PROCESS_COMPUTE` sentinel 允许 no-op；
- 非 sentinel 的 `impl_code` 找不到时 fail-fast：`PROCESS_COMPUTE_PLUGIN_NOT_FOUND`。

### P2-6. PROCESS 优先级与隔离 topic 尚未设计

PROCESS 只有一个 dispatch topic。大 SQL 加工可能堵住小而急的补数/修复任务。先记录，不急于实现；等业务出现优先级诉求后，可沿用 worker topic priority band。

### P2-7. 自定义 `ProcessComputePlugin` SPI 缺少示例和边界文档

需要写清：

- 只实现 `compute()` 与实现完整 WAP 五方法的差异；
- 是否允许自定义插件不写 staging；
- 自定义插件如何上报 `processedCount / highWaterMarkOut`；
- 失败时哪些 stage 保留中间数据。

**状态**

- 已文档化:`system-flow-overview.md` §7.9.8 给出 `ProcessComputePlugin` 5 lifecycle 默认 no-op 行为说明 + 完整 java 代码示例 + 4 条关键约束(不写 staging 等于绕过 WAP / staging 4 列必填 / highWaterMarkOut 上报方式 / 失败保留语义)+ 指向 `ProcessPipelineE2eIT` 的 `e2eProcessCompute` 测试桩作为完整范例。

## 4. 测试缺口

必须补的测试：

1. 双库 E2E：platform/business 分离，确认 staging DDL 与 datasource 设计一致。
2. tenant 隔离测试：相同 batchKey、不同 tenant/target 的 staging 不会串读/串删。
3. watermark 一致性测试：source 在 staging 后新增更高 watermark，不应推进到新增行。
4. console Excel validator 测试：`sqlTransformCompute` 是合法 PROCESS COMPUTE plugin。
5. validation SQL 安全测试：拒绝读取 `pg_catalog`、source、target，只能读 staging。
6. payload metadata 参数测试：明确支持或明确拒绝嵌套 metadata。
7. 空增量批次测试：按 `emptyResultPolicy` 成功 no-op。

## 5. 下一步计划

### PR-1：生产可用性修正

范围：

- 解决 P0-1 staging 数据库归属。
- 修正 `runtime-module-communication.md`、`system-flow-overview.md` 中与实际 datasource 不一致的描述。
- 补双库 E2E。

验收：真实 platform/business 分离测试通过。

### PR-2：隔离与正确性

范围：

- P0-2 tenant/target 过滤。
- P0-4 watermark 从 staging 计算。
- P1-3 validation SQL 表白名单。
- 对应安全与一致性测试。

验收：跨 tenant 污染、水位虚高、validation 越权读取均有回归测试。

### PR-3：staging 生命周期

范围：

- P0-3 COMMIT 后 cleanup 事务化或等效安全机制。
- P1-7 orphan cleanup scheduler + runbook + metrics。
- VALIDATE 失败保留与 COMMIT 成功清理的语义文档化。

验收：崩溃/失败路径不会无限堆 staging，保留策略可配置。

### PR-4：配置入口与参数模型

范围：

- P1-1 注册 `ProcessComputePlugin` 到 registry 或调整 console 校验。
- P1-2/P1-4 统一参数来源和保留名。
- P2-5 unknown plugin fail-fast 策略。

验收：console 配置包能合法导入 `sqlTransformCompute`，参数覆盖 tenant/watermark 被拒绝。

### PR-5：资源保护与文档补齐

范围：

- P1-5 空批次策略。
- P1-6 `maxStagedRows`。
- P2-1/P2-2/P2-7 文档化。
- PROCESS plugin 指标。

验收：大 SQL 有上限，空增量不误报失败，业务接入文档可独立指导配置。

## 6. 维护规则

- 每个 P0/P1 修复合入后，在本文对应条目后追加：`状态：已修复（commit xxx）`。
- 如果修复改变了 WAP 语义，同步更新：
  - [`batch-classification-and-gaps.md`](./batch-classification-and-gaps.md) §4.5
  - [`../architecture/system-flow-overview.md`](../architecture/system-flow-overview.md) PROCESS 流程
  - 相关 runbook（尤其 staging DDL 与清理策略）
- P0/P1 全部关闭后，本文可转入 `docs/archive/analysis/`，保留最终决策摘要到 §4.5。
