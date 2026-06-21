# 长参数方法治理清单（参数封装建议）

日期：2026-03-31 &nbsp;|&nbsp; 最后更新：2026-04-01（≥7 参数业务方法全部完成，6 参数不作要求）  
范围：`file-batch-system`（以 `batch-console-api` 为主，补充扫描 mapper 目录）

---

## 汇总

> **扫描口径说明**：构造器（含 record、DTO、Response、data holder）已明确排除，不在治理范围内，附录不再列出。
> 以下统计仅涵盖**业务方法**（mapper、application、infrastructure 层）。

- **业务方法总数（参数 > 5）：79**（mapper 4 + application 22 + infrastructure 22 + 其他层 31）
- **必须改**：4 ✅ 全部完成（mapper）
- **建议改**：44 ✅ 全部完成（≥7 参数均已封装；6 参数不作要求）
  - `application` 22 + `infrastructure` 22

### 按层分组统计（业务方法，排除构造器）

- `mapper`: 4
- `application`: 22
- `infrastructure`: 22
- `web`: 0
- `request`: 0

### 分级说明

- **必须改**：公共 Mapper 方法，长参数直接影响调用安全、SQL 可维护性、字段演进成本
- **建议改**：Application / Infrastructure 层的公共方法、核心私有方法（≥7 参数）
- **排除**：所有构造器（Response / DTO / record / data holder / DI 注入）不在治理范围

---

## 治理进展记录

> 以下条目已完成参数对象封装，附录全量清单中对应行已标 ✅。

### 2026-04-01 — mapper 层（4 个，全部必须改）

| 方法 | 原参数数 | 封装类型 |
|------|---------|---------|
| `JobDefinitionMapper.updateJobDefinitionMaintenance` | 15 | `JobDefinitionMaintenanceUpdateParam` |
| `WorkflowNodeMapper.upsertWorkflowNode` | 14 | `WorkflowNodeUpsertParam` |
| `WorkflowDefinitionMapper.upsertWorkflowDefinition` | 9 | `WorkflowDefinitionUpsertParam` |
| `WorkflowEdgeMapper.upsertWorkflowEdge` | 6 | `WorkflowEdgeUpsertParam` |

### 2026-04-01 — infrastructure 层 7+ 参数业务方法（10 个）

| 方法 | 原参数数 | 封装类型 |
|------|---------|---------|
| `DefaultConsoleAiApplicationService.buildAuditCommand` | 11 | `AuditContext` |
| `DefaultConsoleConfigApplicationService.logChange` | 10 | `ChangeLogCommand` |
| `FileGovernanceRepository.createReconciledFileRecord` | 12 | `ReconciledFileRecordCommand` |
| `FileGovernanceRepository.appendAudit` | 8 | `FileAuditCommand` |
| `FileGovernanceScheduler.updateGroupState` | 7–8 | `ArrivalGroupUpdateContext` |
| `DefaultResourceScheduler.resolveFairnessScore` | 8 | `FairnessScoreContext` |
| `DefaultConsoleFileApplicationService.executeFileOperation` | 7 | `FileExecContext` |
| `DefaultConsoleFileApplicationService.submitApproval` | 7 | `ApprovalSubmitContext` |
| `DefaultConsoleJobApplicationService.submitApproval` | 7 | `ApprovalSubmitContext` |
| `DefaultConsoleWorkflowExcelApplicationService.logDefinitionChange` | 7 | `DefinitionChangeContext` |

> `delegateLaunch`（原标 7 参数）实测为 6 参数，审计计数有误，暂不处理。

### 2026-04-01 — application 层（batch-orchestrator）— 审计核查 + 补改（3 个）

> 核查发现：审计清单中 application 层的多数方法在本次查阅前已完成封装，无需重复处理。
> 实际补改了以下 3 个：

| 方法 | 原参数数 | 封装类型 |
|------|---------|---------|
| `QuotaRuntimeStateService.describe` | 6 | `QuotaDescribeRequest` |
| `QuotaRuntimeStateService.loadOrCreate` | 6 | `StateContext`（private record） |
| `DefaultCompensationService.appendCompensationLog` | 6 | `CompensationLogContext`（private record） |

> 以下方法经核查已封装（审计列表未反映最新状态）：
> `evaluateAndReserve` → `QuotaReservationRequest`、`submit` × 2 → `ApprovalSubmitCommand`、
> `dispatch` → `DispatchContext`、`launchCompensation` → `CompensationLaunchRequest`、
> `createTasksAndMaybeOutboxEvents` → `TaskCreationContext`、`buildTask` → `TaskBuildContext`、
> `recordNodeRunFinish` × 4 → `NodeRunFinishCommand`

### 当前状态

- **必须改（mapper）**：✅ 4/4 全部完成
- **建议改（≥7 参数业务方法）**：✅ 全部完成
- **6 参数区间**：不作要求，不再跟踪

---

## 目标

- 降低“参数位置传错 / 漏传 / 增字段引发连锁修改”的风险
- 提升接口可读性与可维护性（特别是 Mapper / Service 公共方法）
- 为后续扩展筛选条件、配置字段提供稳定的入参形态（Query/Command/Param）

---

## 建议规则（可作为团队约定）

- **参数 ≤ 4**：可保留散参（可读性通常尚可）
- **参数 ≥ 5**：**建议封装**（尤其是跨层/公共接口）
- **参数 ≥ 8**：基本**必须封装**

### 不看数量也应封装的场景

- **Mapper / Repository / ApplicationService 的公共方法**（被多处调用、变更影响面大）
- 同一业务对象的一组字段（典型：配置 upsert / 定义维护）
- 未来大概率继续加字段（配置类、定义类）
- 大量 `@Param(...)` 导致“顺序敏感/易错”

### 可暂不处理的例外

- **record/Response 构造器参数多**：属于结构化数据表达（不是“调用入参治理”）
- 私有方法仅调用 1 次且稳定（但也建议不要超过 5）

---

## 扫描方法（说明）

- 扫描 `batch-console-api/src/main/java` 下 **参数数 ≥ 5 的 Java 方法签名**
- 额外扫描 `batch-*/src/main/java/**/mapper/**` 下 **Mapper 接口参数数 ≥ 5 的方法**
- 统计口径：按方法签名 `(...)` 中逗号分割的形参数量粗略统计（用于治理清单足够）

---

## 清单 A：必须优先封装（Mapper 层 / 长参数 / 高风险）

> 这类方法通常应改为 `@Param("p") XxxParam` 或直接传 DTO/Entity，SQL 使用 `#{p.xxx}`，避免几十个 `@Param`。

### A1. 极长参数（优先级最高）

- **43 参数**：`batch-console-api/src/main/java/com/example/batch/console/mapper/FileTemplateConfigMapper.java`
  - 方法：`upsertFileTemplateConfig(...)`
  - 风险：新增/删除字段导致接口与 XML 频繁改动，调用方极易错位传参
  - 建议：封装为 `FileTemplateConfigUpsertParam`（或复用 `FileTemplateConfigEntity/DTO`）并在 XML 改为 `#{p.templateCode}` 等

### A2. 典型“维护/Upsert”长参数

- **15 参数**：`batch-console-api/src/main/java/com/example/batch/console/mapper/JobDefinitionMapper.java`
  - 方法：`updateJobDefinitionMaintenance(...)`
  - 建议：封装为 `JobDefinitionMaintenanceUpdateParam`

- **14 参数**：`batch-console-api/src/main/java/com/example/batch/console/mapper/WorkflowNodeMapper.java`
  - 方法：`upsertWorkflowNode(...)`
  - 建议：封装为 `WorkflowNodeUpsertParam`

- **9 参数**：`batch-console-api/src/main/java/com/example/batch/console/mapper/WorkflowDefinitionMapper.java`
  - 方法：`upsertWorkflowDefinition(...)`
  - 建议：封装为 `WorkflowDefinitionUpsertParam`

- **6 参数**：`batch-console-api/src/main/java/com/example/batch/console/mapper/WorkflowEdgeMapper.java`
  - 方法：`upsertWorkflowEdge(...)`
  - 建议：封装为 `WorkflowEdgeUpsertParam`

### A3. 文件通道配置（中长参数）

- **12 参数**：`batch-console-api/src/main/java/com/example/batch/console/mapper/FileChannelConfigMapper.java`
  - 方法：`upsertFileChannelConfig(...)`
  - 建议：封装为 `FileChannelConfigUpsertParam`

- **11 参数**：`batch-console-api/src/main/java/com/example/batch/console/mapper/FileChannelConfigMapper.java`
  - 方法：`updateFileChannelConfig(...)`
  - 建议：封装为 `FileChannelConfigUpdateParam`

---

## 清单 B：建议封装（ApplicationService 的 list() 入参）

> `list(tenantId, filters..., pageNo, pageSize)` 很容易随着筛选条件增长而越来越长。建议把筛选条件 + 分页统一封装成 QueryRequest。

### B1. 已发现参数 ≥ 5 的 list()

- `batch-console-api/src/main/java/com/example/batch/console/application/ConsoleResourceQueueApplicationService.java`
  - `list(String tenantId, String queueCode, String queueType, Boolean enabled, int pageNo, int pageSize)`（6）
  - 建议：`ResourceQueueQueryRequest`（tenantId/queueCode/queueType/enabled/pageNo/pageSize）

- `batch-console-api/src/main/java/com/example/batch/console/application/ConsolePipelineDefinitionApplicationService.java`
  - `list(String tenantId, String jobCode, String pipelineType, Boolean enabled, int pageNo, int pageSize)`（6）
  - 建议：`PipelineDefinitionQueryRequest`

- `batch-console-api/src/main/java/com/example/batch/console/application/ConsoleQuotaPolicyApplicationService.java`
  - `list(String tenantId, String policyCode, Boolean enabled, int pageNo, int pageSize)`（5）
  - 建议：`QuotaPolicyQueryRequest`

> 扫描中还出现 `ConsoleBatchWindowApplicationService.list(...)`、`ConsoleCalendarApplicationService.list(...)` 等 5 参数方法，同样建议按一致风格封装。

---

---

## 参考实暴露态（建议）

- **MyBatis 参数对象**：
  - `int updateXxx(@Param("p") XxxParam p);`
  - XML：`set col = #{p.field}` / `where tenant_id = #{p.tenantId} and id = #{p.id}`

- **QueryRequest**（用于 list）：
  - 统一 `tenantId` + filters + `pageNo/pageSize`（或复用 `PageRequest`）
  - Controller：`@ModelAttribute XxxQueryRequest request`
  - Service：`PageResponse<T> list(XxxQueryRequest request)`

---

## 附录：业务方法清单（构造器已排除）

> 说明：
> - 仅列出 **mapper / application / infrastructure** 层的业务方法（参数 > 5）
> - 构造器（Response、DTO、record、data holder、DI 注入构造器）一律不在此列

### `mapper`（4）✅ 全部完成

- 15: ~~`batch-console-api/src/main/java/com/example/batch/console/mapper/JobDefinitionMapper.java` -> `updateJobDefinitionMaintenance()`~~ ✅ `JobDefinitionMaintenanceUpdateParam`
- 14: ~~`batch-console-api/src/main/java/com/example/batch/console/mapper/WorkflowNodeMapper.java` -> `upsertWorkflowNode()`~~ ✅ `WorkflowNodeUpsertParam`
- 9: ~~`batch-console-api/src/main/java/com/example/batch/console/mapper/WorkflowDefinitionMapper.java` -> `upsertWorkflowDefinition()`~~ ✅ `WorkflowDefinitionUpsertParam`
- 6: ~~`batch-console-api/src/main/java/com/example/batch/console/mapper/WorkflowEdgeMapper.java` -> `upsertWorkflowEdge()`~~ ✅ `WorkflowEdgeUpsertParam`

### `application`（22）✅ 全部完成

- 11: ~~`batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/scheduler/QuotaRuntimeStateService.java` -> `evaluateAndReserve()`~~ ✅ `QuotaReservationRequest`（审计前已完成）
- 10: ~~`batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/ApprovalWorkflowService.java` -> `submit()`~~ ✅ `ApprovalSubmitCommand`（审计前已完成）
- 10: ~~`batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/DefaultApprovalWorkflowService.java` -> `submit()`~~ ✅ `ApprovalSubmitCommand`（审计前已完成）
- 8: ~~`batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/DefaultCompensationService.java` -> `launchCompensation()`~~ ✅ `CompensationLaunchRequest`（审计前已完成）
- 8: ~~`batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/DefaultPartitionDispatchService.java` -> `createTasksAndMaybeOutboxEvents()`~~ ✅ `TaskCreationContext`（审计前已完成）
- 8: ~~`batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/DefaultPartitionDispatchService.java` -> `dispatch()`~~ ✅ `DispatchContext`（审计前已完成）
- 8: ~~`batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/DefaultTaskExecutionService.java` -> `recordNodeRunFinish()`~~ ✅ `NodeRunFinishCommand`（审计前已完成）
- 8: ~~`batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/DefaultTaskOutcomeService.java` -> `recordNodeRunFinish()`~~ ✅ `NodeRunFinishCommand`（审计前已完成）
- 8: ~~`batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/PartitionDispatchService.java` -> `dispatch()`~~ ✅ `DispatchContext`（审计前已完成）
- 8: ~~`batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/TaskExecutionService.java` -> `recordNodeRunFinish()`~~ ✅ `NodeRunFinishCommand`（审计前已完成）
- 8: ~~`batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/TaskOutcomeService.java` -> `recordNodeRunFinish()`~~ ✅ `NodeRunFinishCommand`（审计前已完成）
- 7: ~~`batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/DefaultCompensationService.java` -> `appendCompensationLog()`~~ ✅ `CompensationLogContext`
- 7: ~~`batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/DefaultPartitionDispatchService.java` -> `buildTask()`~~ ✅ `TaskBuildContext`（审计前已完成）
- 7: ~~`batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/DefaultPartitionDispatchService.java` -> `createTasksAndMaybeOutboxEvents()`~~ ✅ `TaskCreationContext`（审计前已完成）
- 6: `batch-console-api/src/main/java/com/example/batch/console/application/ConsolePipelineDefinitionApplicationService.java` -> `list()`（见 infrastructure 剩余待改）
- 6: `batch-console-api/src/main/java/com/example/batch/console/application/ConsoleResourceQueueApplicationService.java` -> `list()`（见 infrastructure 剩余待改）
- 6: `batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/engine/TaskDispatchOutboxService.java` -> `writeDispatchEvent()`
- 6: `batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/engine/TaskDispatchOutboxService.java` -> `writeDispatchEvent()`
- 6: ~~`batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/scheduler/QuotaRuntimeStateService.java` -> `describe()`~~ ✅ `QuotaDescribeRequest`
- 6: ~~`batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/scheduler/QuotaRuntimeStateService.java` -> `loadOrCreate()`~~ ✅ `StateContext`
- 6: `batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/DefaultWorkflowNodeDispatchService.java` -> `dispatchJobNode()`
- 6: `batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/DefaultWorkflowNodeDispatchService.java` -> `dispatchJobNode()`

### `web`（0）

- 无

### `request`（0）

- 无

### `infrastructure`（22）

- 12: ~~`batch-orchestrator/src/main/java/com/example/batch/orchestrator/infrastructure/file/FileGovernanceRepository.java` -> `createReconciledFileRecord()`~~ ✅ `ReconciledFileRecordCommand`
- 11: ~~`batch-console-api/src/main/java/com/example/batch/console/infrastructure/DefaultConsoleAiApplicationService.java` -> `buildAuditCommand()`~~ ✅ `AuditContext`
- 10: ~~`batch-console-api/src/main/java/com/example/batch/console/infrastructure/DefaultConsoleConfigApplicationService.java` -> `logChange()`~~ ✅ `ChangeLogCommand`
- 8: ~~`batch-orchestrator/src/main/java/com/example/batch/orchestrator/infrastructure/file/FileGovernanceRepository.java` -> `appendAudit()`~~ ✅ `FileAuditCommand`
- 8: ~~`batch-orchestrator/src/main/java/com/example/batch/orchestrator/infrastructure/file/FileGovernanceScheduler.java` -> `updateGroupState()`~~ ✅ `ArrivalGroupUpdateContext`
- 8: ~~`batch-orchestrator/src/main/java/com/example/batch/orchestrator/infrastructure/scheduler/DefaultResourceScheduler.java` -> `resolveFairnessScore()`~~ ✅ `FairnessScoreContext`
- 7: ~~`batch-console-api/src/main/java/com/example/batch/console/infrastructure/DefaultConsoleFileApplicationService.java` -> `executeFileOperation()`~~ ✅ `FileExecContext`
- 7: ~~`batch-console-api/src/main/java/com/example/batch/console/infrastructure/DefaultConsoleFileApplicationService.java` -> `submitApproval()`~~ ✅ `ApprovalSubmitContext`
- 7: ~~`batch-console-api/src/main/java/com/example/batch/console/infrastructure/DefaultConsoleJobApplicationService.java` -> `submitApproval()`~~ ✅ `ApprovalSubmitContext`
- 7: ~~`batch-console-api/src/main/java/com/example/batch/console/infrastructure/DefaultConsoleWorkflowExcelApplicationService.java` -> `logDefinitionChange()`~~ ✅ `DefinitionChangeContext`
- 7: ~~`batch-orchestrator/src/main/java/com/example/batch/orchestrator/infrastructure/file/FileGovernanceScheduler.java` -> `updateGroupState()`~~ ✅ `ArrivalGroupUpdateContext`
- 7: ~~`batch-orchestrator/src/main/java/com/example/batch/orchestrator/infrastructure/file/FileGovernanceScheduler.java` -> `updateGroupState()`~~ ✅ `ArrivalGroupUpdateContext`
- 7: ~~`batch-orchestrator/src/main/java/com/example/batch/orchestrator/infrastructure/file/FileGovernanceScheduler.java` -> `updateGroupState()`~~ ✅ `ArrivalGroupUpdateContext`
- 7: ~~`batch-orchestrator/src/main/java/com/example/batch/orchestrator/infrastructure/file/FileGovernanceScheduler.java` -> `updateGroupState()`~~ ✅ `ArrivalGroupUpdateContext`
- 7: ~~`batch-orchestrator/src/main/java/com/example/batch/orchestrator/infrastructure/file/FileGovernanceScheduler.java` -> `updateGroupState()`~~ ✅ `ArrivalGroupUpdateContext`
- 6: `batch-console-api/src/main/java/com/example/batch/console/infrastructure/DefaultConsoleAiApplicationService.java` -> `buildPrompt()`
- 6: `batch-console-api/src/main/java/com/example/batch/console/infrastructure/DefaultConsoleFileChannelExcelApplicationService.java` -> `logChange()`
- 6: `batch-console-api/src/main/java/com/example/batch/console/infrastructure/DefaultConsoleFileChannelExcelApplicationService.java` -> `requireEnum()`
- 6: `batch-console-api/src/main/java/com/example/batch/console/infrastructure/DefaultConsoleFileTemplateExcelApplicationService.java` -> `logChange()`
- 6: `batch-console-api/src/main/java/com/example/batch/console/infrastructure/DefaultConsoleFileTemplateExcelApplicationService.java` -> `requireEnum()`
- 6: `batch-console-api/src/main/java/com/example/batch/console/infrastructure/DefaultConsolePipelineDefinitionApplicationService.java` -> `list()`
- 6: `batch-console-api/src/main/java/com/example/batch/console/infrastructure/DefaultConsoleResourceQueueApplicationService.java` -> `list()`

