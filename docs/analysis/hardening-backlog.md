# 硬化与遗留问题 Backlog

> 滚动版本：v6（2026-04-30 校准），历史 v1-v5 见 git log
> 维护规则：见底部

---

## 总览

| 优先级 | 已完成 | 部分完成 | 待办 | 不做 | 合计 |
|---|:---:|:---:|:---:|:---:|:---:|
| deep-issue §5 6 项 | 5 | 1 | 0 | 0 | 6 |
| ADR 路线图 | 3 | 0 | 2 | 0 | 5 |
| v2 评估硬化 | 6 | 2 | 0 | 0 | 8 |
| v5 历史 P0-P3 | 19 | 2 | 0 | 2 | 23 |
| DBA 审查 2026-05-20 | 11 | 4 | 0 | 0 | 15 |
| **合计** | **44** | **9** | **2** | **2** | **57** |

完成率 **33/42 ≈ 79%**；剩余 2 项 follow-up 均为 god class 拆分残余，独立 sprint 排期。

---

## 一、近期闭环（v6 周期）

### 1. deep-issue §5 关闭 4 项

| 编号 | 主题 | 证据 |
|---|---|---|
| §5.1 | Trigger Spring Security | `cd389a0b`（2026-04-22 V4 闭环）；`TriggerSecurityConfiguration.java:42-46` 真起 SecurityFilterChain |
| §5.2 | X-Console-Token 物理删除 | `ff20c36f`：主代码 + yaml + OpenAPI + 测试 9 文件 +20/-168 |
| §5.7 | trigger → orchestrator 异步化 | ADR-010 全栈 7 stage（6 commits），22 测试全绿（单测 9 + relay 7 + Layer 1 trigger E2E 4 + Layer 2 跨模块 E2E 2） |
| §5.12 | Console Job 过胖 | `DefaultConsoleJobApplicationService` 现 90 LOC + 6 兄弟类共 1278 LOC |

### 2. ADR 路线图全栈完成

- **ADR-009 Workflow DSL**：Stage 1（V72 列）/ 1.2（worker outputs 上报）/ 2（`WorkflowParamResolver` 160 LOC + 10 单测）/ 3（`WorkflowNodePayloadBuilder.mergeNodeParams` 集成）/ 4（seed DSL 演示）**全 4 stage 已落地**（2026-06-05 复核，与 ADR 文档头部一致）
- **ADR-010 trigger 异步解耦**：**全 7 stage 已完成**——Stage 1-5 代码 100%，Stage 6 灰度 2026-05-02 全量上线，Stage 7 旧 HTTP 同步路径（`HttpOrchestratorTriggerAdapter` + `async-launch.enabled` 开关）已物理删除
- 配套：V80 `trigger_outbox_event` schema + `docs/runbook/trigger-async-launch-rollout.md` 280 行 SOP + `heal-zombie-pipelines.sh` ops 脚本

### 3. v2 评估锁定 4 项硬化条目

| 编号 | 主题 | 状态 | commit / 落地点 |
|---|---|---|---|
| V6-OPS-1 | `.env.prod` `KAFKA_TOPICS` 缺 `batch.trigger.launch.v1` + `batch.task.dispatch.process` | ✅ 部分 | 本地修复；治本待 CI 加 `.env.prod` 与 `.env.example` 同步检查 |
| V6-OPS-2 | Prometheus 3 条 ADR-010 告警（`TriggerOutboxBacklogGrowing` / `TriggerLaunchFailureSpike` / `TriggerOutboxGiveUp`）落 `prometheus-batch-rules.yml` | ✅ | `0c623eb0` |
| V6-Q-1 | 9 处 FQN 违规（`BizExceptionUtils:69` / `ConsoleAuthenticationFilter:93+116` / `ConfigPackageExcelValidator:855` / `PartitionLifecycleService:17` / `PlatformFileRuntimeRepository:209+251+268+290`）5 文件批量改 | ✅ | `8dc6eac1`；全仓 grep 残留 0 |
| V6-NOISE-1 | 运行日志噪声治理 — ChannelConfigMerge `LEGACY_REDUNDANT_KEYS` + FileGovernance `processingDelayMaxAgeSeconds` 默认 7 天 zombie 上限 + `heal-zombie-pipelines.sh` 闭环 | ✅ | `aa249bf8` / `0d650fab` |

### 4. v6 P2 全部清账

| 编号 | 主题 | 状态 | 主要 commit |
|---|---|---|---|
| V6-P2-WEBHOOK-DURABILITY（deep-issue §5.11）| webhook 投递持久化 — V81 `delivery_status` CHECK 加 `GIVE_UP` + `(status, next_retry_at)` 部分索引；`WebhookDeliveryRelay` 278 LOC（@ConditionalOnProperty 默认开 + ShedLock 互斥 + 5/10/20/30min cap 退避 + absolute-max-attempts=8 后 GIVE_UP）；`WebhookEventPayload` + `WebhookDeliveryResult` 顶级类；`batch_webhook_delivery_give_up_total` counter + `WebhookDeliveryGiveUp` Prometheus 告警；7 单测全绿 | ✅ | `b74e0a0c`（2026-04-30 14:55）|
| V6-P2-ORCHESTRATOR-GODCLASS | `DefaultTaskOutcomeService` 926 → 795 LOC（-14%）：抽 `TaskOutcomePayloadSupport`(104) + `TaskOutcomeSummaryBuilder`(76) + 内联 helper。`DefaultWorkflowNodeDispatchService` 840 → 371 LOC（-56%）：抽 `WorkflowNodePayloadBuilder`(311, Cluster F：payload 拼装 + 上游 partition output 合并 + ADR-009 DSL 解析) + `ChildJobLaunchSupport`(276, Cluster B+C：JOB 节点子作业拉起全套)；保留 25 LOC 重复（`recordNodeRunReady`/`nextRunSeq`）较抽公共类成本低；主 service 留 `dispatchNode`/`dispatchTaskNode`/`dispatchGatewayNode` 核心调度路径；test 构造器 5 → 4 参同步；12 个 `*WorkflowNode*Test` + `*Dispatch*Test` 全绿，506 IT 仅 1 已知 race flake（`WorkerClaimProgressCompleteIT`，isolation 重跑通过） | ✅ | `b74e0a0c` + `7d6faad6`（2026-04-30 20:46）|
| V6-P2-EXCEL-GODCLASS | 7 个 god class 主 service 平均 -67% LOC，新增 13 个收口类（详见下表） | ✅ 6/7 | `002b8864` + `bd0f0532` + `b9eefb47` |
| V6-P2-CONSOLE-IDEMPOTENCY（deep-issue §5.5/§5.6/§5.10）| 三层幂等边界 — Layer 1：`ConsoleIdempotencyInterceptor` 全文重写（key 绑定 tenant+method+uri+idempotencyKey；两阶段占坑 PENDING 30s → 2xx 升 DONE 24h / 非 2xx DELETE 释放；Redis fail-closed 503）。Layer 2：`DefaultTriggerService.approvePendingCatchUp:134-142` 用 `idempotencyKey` 查 `trigger_request` 已 LAUNCHED 短路；类 Javadoc:60-71 明示"trigger 层只做尽力去重，最终去重由 orchestrator 兜底"。Layer 3：`db/migration/V37` 删 `uk_trigger_request_tenant_dedup` + `uk_job_instance_tenant_dedup` 作为最终事实源。设计定稿见 [`ADR-011`](../architecture/adr/ADR-011-idempotency-boundary-alignment.md) | ✅ | — |

**EXCEL-GODCLASS 7 类拆分明细**：

| service | LOC 变化 | commit | 拆出 |
|---|---|---|---|
| `DefaultConsoleWorkflowExcelApplicationService` | 1512 → **497**（-67%）| `002b8864` | 8 类（metadata / writer / parser / validator / keys / text-utils / parsed-session / validation-result）|
| `DefaultConsoleTenantConfigInitApplicationService` | 823 → **120**（-85%）| `b9eefb47` | `TenantConfigInitApplyHandlers`（10 类 spec apply + insert/update/upsert）|
| `DefaultConsoleJobDefinitionExcelApplicationService` | 887 → **663**（-25%）| `bd0f0532` | writer |
| `DefaultConsoleBusinessCalendarExcelApplicationService` | 1009 → **763**（-24%）| `bd0f0532` | writer（含 SheetSpec 模板）|
| `DefaultConsolePipelineDefinitionExcelApplicationService` | 1061 → **822**（-22%）| `bd0f0532` | writer |
| `DefaultConsoleTenantConfigPackageExcelApplicationService` | 846 → **728**（-14%）| `bd0f0532` | row projections |
| `ConfigPackageExcelValidator` | 874 LOC **保留** | — | 已是 single-purpose validator，内部 8 个 validateXxxRows 共享 cross-reference 数据，split 8 文件反而 fragment + overhead |

### 5. V6-P2-POSITIONAL-ARGS（2026-05-01 方案定稿）

- **背景**：CLAUDE.md "方法参数 ≥7 必须封装" 第一阶段落地后，参数臃肿从方法签名搬到 `new XxxParam(a,b,...,n)` inline 调用，留下 main 反例
- **范围**（业界对齐后收窄）：① 方法签名 argc=7 共 7 处 + ② inline argc>6 共 54 处 = **61 处**
  - **删桶 ③**（argc=4-6 共 137 处）：Effective Java / Google Style / Oracle Conventions 均未禁止 `f(new Foo(a..f))`，业界无依据
  - **豁免声明式注册类**：`ConsoleMenuRegistry`（41 处 MenuItem + 8 处 MenuGroup）/ Excel `*SchemaRegistry`（8 处 SheetDef）等，inline new 在声明式数据结构里是业界鼓励写法
- **统一动作**：② 加 `@Builder` + 提取引用 + 默认值不显式 set；class 加 `@Builder` 用 `@NoArgsConstructor` + `@AllArgsConstructor` 三连或 `@Tolerate` 兜底空参，**不降级**
- **提交策略**：1 个大 PR 内 4 commit 拆分（① / ② / 守护测试 / 文档），~1100 行 diff
- **规约同步**：CLAUDE.md §方法参数约束 追加"调用方约束"子节 + `docs/changelog.md` 2026-05-01 条目 + 守护 `PositionalArgsConventionTest` 白名单方式拦回潮
- **不做**：Spring Data JDBC entity 强制 `@Builder` / 重排 record 字段 / argc≤6 治理 / 声明式注册类 / test 重灾区 fixture builder（独立立项）
- **详细方案**：[`positional-args-cleanup-plan.md`](./positional-args-cleanup-plan.md)
- **前置 PR-A**：Query record 17 类 / 39 处调用点 + `QueryRecordConstructionConventionTest` 已完成（主线 commit 待 push）

---

## 二、Deferred（基础设施完备，触发条件出现再做）

| 编号 | 主题 | 触发条件 |
|---|---|---|
| V6-D-1 | ~~ADR-009 Stage 4 业务配 DSL~~ | ✅ 已完成：seed `multi-tenant-seed.sql` 配了 `$.nodes.PROCESS.output.processedCount` / `$.workflowRun.bizDate` DSL 演示；节点间 `mergeUpstreamPartitionOutputs` 自动透传 fileId。业务方需更多跨节点串联时按 §10 文档继续配（机制已就绪） |
| V6-D-2 | ~~ADR-010 Stage 6 灰度 operational~~ | ✅ 已完成:ADR-010 文档头部记 **2026-05-02 全量上线**（异步 launch 路径已固化）|
| V6-D-3 | ~~ADR-010 Stage 7 物理删除旧 HTTP 路径~~ | ✅ 已完成:`HttpOrchestratorTriggerAdapter` 与 `batch.trigger.async-launch.enabled` 开关已物理删除（2026-06-05 复核确认仓库已无残留），trigger 只走 outbox relay |
| V6-D-4 | ~~I4 `buildContext` 模板抽取~~ | ✅ 2026-05-21 完成：未抽 `AbstractJobContext`（4 个 context 仅 5 字段共享、`ExecutionContext` 接口已覆盖 getter；剩 2~3 字段抽基类收益 < 继承+`@EqualsAndHashCode(callSuper=true)` 成本），改抽 `populateCommonFields` 模板 + `putIfPresent` helper 到 `AbstractPipelineStepExecutionAdapter`，去掉 4 处 `buildContext` 5 行 setter 重复与 4 处 `putIfPresent` 8 行同字节复制 |
| V6-D-5 | Worker 4 模块单测密度补齐 | 各 `Default*StageExecutor` + `*StepExecutionAdapter` 加 5-10 单测 |

---

## 三、🟡 部分完成

### V5-P1-1 · Workflow 节点间参数串联（DSL 部分仍缺）

**v4 进展（commit 3dbb6d22, 2026-04-24）**：
- `dispatchJobNode.buildChildLaunchRequest` 调 `mergeNodeParams` ✅
- `WORKFLOW_INTERNAL_PAYLOAD_KEYS` 防泄露 ✅

**已实现**（2026-06-05 复核）：上游节点 output → 下游节点 param 的 DSL 映射已落地——worker 上报 `outputs` → orchestrator 写 `workflow_node_run.output` → `WorkflowParamResolver` 解析下游 `node_params` 里的 `$.xxx` 引用。SETTLE→DISPATCH 透传由 `mergeUpstreamPartitionOutputs` 自动处理（fileId），DSL 用于显式跨节点参数串联。

**ADR 已立项**：[ADR-009 Workflow 节点间参数串联 DSL](../architecture/adr/ADR-009-workflow-param-dsl.md)（2026-04-26 Proposed）—— JSONPath-like DSL + worker 上报 outputs + WorkflowParamResolver 解析；分 4 stage 落地，~3 人天。

**当前进度**（2026-06-05 复核：全 4 stage 已落地，本清单此前漏同步 → 校正）：
- ✅ Stage 1：`db/migration/V72__add_workflow_node_run_output.sql` schema 落地（output JSONB 列）+ worker 上报 outputs（`TaskExecutionReportDto.outputs: Map<String,Object>`）
- ✅ Stage 2：`WorkflowParamResolver` + `WorkflowParamResolverTest`（10 单测全绿，复核日重跑确认）
- ✅ Stage 3：集成在 `WorkflowNodePayloadBuilder.mergeNodeParams`（实现选 payload 装配点，比设计稿假想的 `DefaultSchedulePlanBuilder` 更准确）+ `loadWorkflowRunContext` 加载上游节点 output
- ✅ Stage 4：测试 seed `batch-e2e-tests/.../multi-tenant-seed.sql` 已配 `$.nodes.PROCESS.output.processedCount` / `$.workflowRun.bizDate` DSL 演示

**成本**：L —— 已完成（与 ADR-009 文档头部「Stage 1-4 全 ✅」一致）。

---

## 四、✅ 已完成清账（v5 历史滚到 v6）

| 编号 | 主题 | 完成日期 | 主要 commit / 证据 |
|---|---|---|---|
| V4-P0-1 | DB 改动入 seed 脚本 | 2026-04-21 | seed 脚本 + multi-tenant-seed UPDATE 块 |
| V4-P0-2 | worker capability_tags 心跳上报 | 2026-04-21 | `WorkerConfiguration.capabilityTags()` + heartbeat dto |
| V4-P0-3 | workflow 空壳种子 | 2026-04-22 | 0 个 workflow_definition 是空壳（已核实） |
| V4-P1-2 | ParseSupport 硬编码 CustomerImportPayload | 2026-04-22 | 删 `convertValue(CustomerImportPayload.class)` |
| V4-P1-4 | EXPORT `:bizDate` 占位符 | 2026-04-22 | `SqlTemplateExportSecurityProperties.allowedExtraParams = ["bizDate"]` 默认 |
| V4-P1-5 | DISPATCH non-retryable 标识 | 已完成 | `DefaultRetryGovernanceService:66` `NON_RETRYABLE_ERROR_CODES` 含 7 条 |
| V4-P3-1 | calendar WARN 刷屏 | 2026-04-22 | trigger.log 0 calendar WARN（已核实） |
| V4-P3-2 | biz.transaction 索引 | 2026-04-22 | 现有 3 索引（pkey + account + tenant_date + unique txn_no） |
| V4-P3-3 | 失败实例堆积 | 2026-04-26 | SQL 状态集扩展含 FAILED/CANCELLED/TERMINATED；一次性脚本清 1222 FAILED + 24 CANCELLED；30 天 retention 后自动归档 |
| V4-P3-4 | dead_letter NEW 堆积 | 2026-04-26 | cleanup-historical-failures.sql 清 1242 → 8；FK 顺序修正（先删 event_delivery_log 再删 outbox_event） |
| V5-P1-3 | EXPORT 强制 id 列友好错误 | 已完成 | `SqlTemplateExportSpec:62-69` 早校验 + 友好错误（默认 cursorColumn=id + 缺失时抛 IllegalArgumentException 含完整修复指引） |
| V5-NEW-1 | workflow steps 协议错位 | 不构成 bug | worker 代码不读 task_payload.steps（grep 全是局部变量）；4-24 commit 3dbb6d22 修了 resolveJobCode + node_params 后未复现 |
| V5-NEW-2 | exp_settlement_csv_v1 模板源头 | 关闭（不追溯）| default-tenant 7 个 system 模板之一，业务无引用，不影响主链路；归类"历史遗留 + 不影响" |
| V5-P2-2 | 业务日历门禁 E2E | 2026-04-26 | `BatchWindowGateTest` 4 IT（in-window allow / out-of-window WAIT / out-of-window FAIL / 无 windowCode 跳过） |
| V5-P2-8 | FIXED_WIDTH / XML parser IT | 2026-04-26 | `ParseStepFixedWidthAndXmlTest` 4 IT（FIXED_WIDTH 3 字段 + header/footer 跳过 + XML records envelope + XXE 防护） |
| V5-P2-3 | quota / fair-share 压测 smoke | 2026-04-27 | `JobLaunchSimulation` 跑通 105 reqs / 25s，p95=112ms / 0 失败；报告归档 `testing/load-test-report.md`；真打满 quota 留 P2-3-ext |
| V5-P2-4 | compensation 4/6 类 happy-path | 2026-04-27 | `DefaultCompensationServiceTest` +4 IT（PARTITION / STEP / DLQ / FILE），13 tests 通过；JOB/BATCH 留 P2-4-ext |

---

## 五、P2 增量场景覆盖现状

### ✅ 已完整 IT 覆盖

| 编号 | 场景 | 实际覆盖 |
|---|---|---|
| V5-P2-2 | 业务日历门禁验证 | `BatchWindowGateTest` 4 IT（in-window allow / out-of-window WAIT / out-of-window FAIL / 无 windowCode 跳过） |
| V5-P2-5 | 文件 archive / redispatch 控制端点 | `FileGovernanceIntegrationTest`（archive + reconcile + arrival 全套） |
| V5-P2-6 | drain enable/disable | `OrchestratorDrainControllerTest` 测 GET status + POST enable + POST disable |
| V5-P2-7 | worker drain 生命周期（DRAINING → DECOMMISSIONED）| 5 IT |
| V5-P2-8 | FIXED_WIDTH / XML 文件格式 | `ParseStepFixedWidthAndXmlTest` 4 IT |

### 🟡 部分覆盖

| 编号 | 场景 | 状态 |
|---|---|---|
| V5-P2-3 | quota / fair-share 配额压测 | smoke 完成（详见 §四）；真打满压测留 P2-3-ext |
| V5-P2-4 | compensation 独立验证 | 4/6 happy path 完成（PARTITION / STEP / DLQ / FILE）；JOB/BATCH 留 P2-4-ext |

### ❌ 不做（业务驱动）

| 编号 | 场景 | 原因 |
|---|---|---|
| V5-P2-1 | 6 类非 SFTP dispatch 渠道单 adapter IT | 业务接入对应渠道时再做 |
| V5-P2-9 | Workflow PIPELINE / MIXED + GATEWAY / FILE_STEP 节点 | 依赖 V5-P1-1 完整落地 |

---

## 六、DBA Schema 审查（2026-05-20 立项，PR-A/B/C 已落 2026-05-21）

来源：[`dba-schema-review-2026-05-20.md`](./dba-schema-review-2026-05-20.md)。基于 `docker/postgres/init/*` + `db/migration/V*__*.sql`（V1–V133+）+ `scripts/db/*` 的静态审查。

**落地拆分**：PR-A 纯 DDL/脚本（V134-V141 + 3 cleanup）／PR-B 应用层守护（drift check / Guard / ANALYZE）／PR-C UNIQUE + 索引加新 + cutover/索引 runbook。

### P0 — 增长与生命周期红线

| 编号 | 主题 | 状态 | 落地证据 |
|---|---|---|---|
| V6-DBA-P0-1 | `outbox_event` 月分区执行 | 🟡 runbook 就绪待 ops | `scripts/db/partition-migration/01-outbox-event-partitioned.sql` + [`runbook/partition-cutover-2026-05.md`](../runbook/partition-cutover-2026-05.md) 4 phase SOP |
| V6-DBA-P0-2 | `job_instance` biz_date 分区执行 | 🟡 runbook 就绪待 ops | 同上 P0-1 SOP，与 P0-1 合并维护窗口 |
| V6-DBA-P0-3 | `trigger_outbox_event` 生命周期 | ✅ DDL/script 完成 | V139（archive 镜像 + archive_policy 种子）+ `scripts/db/cleanup-trigger-outbox-events.sql`。relay 游标改造经审计**不需要**（已 SKIP LOCKED + CAS markPublishing + ShedLock + stale reset） |
| V6-DBA-P0-4 | `dead_letter_task` 生命周期 | ✅ | V140 + `scripts/db/cleanup-dead-letter-task.sql` |
| V6-DBA-P0-5 | `job_execution_log` 生命周期 | ✅ | V141（archive_policy 种子，archive 表已在 V71）+ `scripts/db/cleanup-job-execution-log.sql` |

### P1 — 索引整合 / 约束守护

| 编号 | 主题 | 状态 | 落地证据 |
|---|---|---|---|
| V6-DBA-P1-1 | `job_instance` 索引补强 | 🟡 加新完成，DROP 待取证 | V143 加 partial active + `(tenant_id, biz_date DESC, instance_status)`；DROP 流程见 [`runbook/index-consolidation-2026-05.md`](../runbook/index-consolidation-2026-05.md)（需生产 `pg_stat_user_indexes.idx_scan` 数据再 V144 DROP） |
| V6-DBA-P1-2 | `workflow_run` 整合 + 兜底 UNIQUE | ✅ UNIQUE 完成 / 🟡 索引整合待取证 | V142 加 `UNIQUE(tenant_id, id)`；DAO 审计：用户路径 7/7 带 tenant_id，2 处 by-design 旁路（`selectByIdAnyTenant` / cluster reconciler），**无穿透 bug** |
| V6-DBA-P1-3 | `NotValidConstraintGuard` | ✅ 已存在 | `batch-orchestrator/.../startup/NotValidConstraintGuard.java`（R7 DB 审计 P1-5 时落地） |
| V6-DBA-P1-4 | `ArchiveSchemaDriftCheck` 扩展 | 🟡 表清单已扩，列级比对未做 | `ARCHIVED_TABLES` 追加 `trigger_outbox_event` / `dead_letter_task`；`console_operation_audit` 仍无 archive 表（V132 by design）；列级（类型 / nullability）比对推后到独立条目 |

### P2 — Quick wins

| 编号 | 主题 | 状态 | 落地证据 |
|---|---|---|---|
| V6-DBA-P2-1 | `event_delivery_log.outbox_event_id` FK 索引 | ✅ | V134 |
| V6-DBA-P2-2 | `file_record.storage_path` → TEXT | ✅ | V135 |
| V6-DBA-P2-3 | `job_instance` CHECK 兜底 | ✅ | V136 NOT VALID + V137 VALIDATE 同 PR |
| V6-DBA-P2-4 | `job_partition.idempotency_key` 收口 | ✅ | 审计仅 2 处 INSERT，均设确定性非空 key；`DefaultPartitionLifecycleService` + `ChildJobLaunchSupport` 补 `Guard.requireText` 防回归 |
| V6-DBA-P2-5 | `file_record.metadata_json` 计划稳定性 | ✅ | `scripts/db/analyze-hot-tables.sql` 13 表 ANALYZE + last_analyze 校验；partial GIN 评估推后到取证后 |
| V6-DBA-P2-6 | `file_dispatch_record.file_id` CASCADE | ✅ | V138 |

### 完成率

| 优先级 | 完成 | 部分 / 待 ops | 合计 |
|---|:---:|:---:|:---:|
| P0 | 3 | 2 | 5 |
| P1 | 2 | 2 | 4 |
| P2 | 6 | 0 | 6 |
| **合计** | **11** | **4** | **15** |

剩 4 项：P0-1/P0-2 等 ops 维护窗口 + P1-1/P1-2 索引整合等生产 `idx_scan` 取证 + 一项轻量后续（drift check 列级比对）。无代码级 blocker。

---

## 维护规则

- **每发版**：把"已完成"项移到归档（避免 backlog 越来越长）
- **每月**：用 grep + DB 查 + 日志查重核每条状态，避免"顶部已完成 / 明细未更新"不一致
- **新发现**：先加进 V6-NEW-N，下次重排时归类到 P0/P1/P2/P3
