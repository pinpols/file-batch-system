# 🛡 硬化与遗留问题 Backlog · v5

> 产出日期：2026-04-26
> 上一版：[`../archive/analysis/hardening-backlog-v4.md`](../archive/analysis/hardening-backlog-v4.md)（2026-04-21）
> 校准方法：v4 18 条逐条对照本日代码 / 数据库 / 日志现状重新评估
> 修订历史：v4 顶部声称"P0/P1/P3 全部完成"，实测有 5 项未完成或部分完成；本版按真实状态重新分类

---

## 总览

| 优先级 | 已完成 | 部分完成 | 待办 | 不做（标） | 合计 |
|---|:---:|:---:|:---:|:---:|:---:|
| P0 立即止血 | 3 | 0 | 0 | 0 | 3 |
| P1 结构性 | 4 | 1 | 0 | 0 | 5 |
| P2 增量场景 | 6 | 1 | 0 | 2 | 9 |
| P3 小瑕疵 | 4 | 0 | 0 | 0 | 4 |
| **新发现（v5 新增）** | 2 | 0 | 0 | 0 | 2 |
| **合计** | **19** | **2** | **0** | **2** | **23** |

> **2026-04-30 第七轮 — 评估口径校正(对齐 `project-assessment-2026-04-29.md`)**:
> - **deep-issue §5.1**(Trigger Spring Security):🟢 已修。`cd389a0b`(2026-04-22 v4 闭环)加 `TriggerSecurityConfiguration:42-46` 真起 SecurityFilterChain。本 backlog 把这条移出"未完成"。
> - **deep-issue §5.2**(X-Console-Token 共享密钥):🟡 部分修。`legacyHeaderAuthEnabled` 在 `application.yml:67` env 默认 `false`,deprecated + opt-in compat;真删动作排在 S5-d。
> - **deep-issue §5.12**(Console Job 过胖):🟢 已修。`DefaultConsoleJobApplicationService` 现 90 LOC 纯 delegate,拆出 6 兄弟类(Trigger/Recovery/Approval/Query/Ops 共 1278 LOC)。ADR-008 god-class-decomposition 事实落地。
> - **V5-P1-1 ADR-009 进度更新**:Stage 1.2 也已落 — worker→orchestrator outputs 上报管线已通(`TaskExecutionReport.outputs` + `DefaultTaskExecutionWrapper:108-117` 透传 + `WorkflowNodeRunMapper.xml:84-85` 写 jsonb;`ImportStepExecutionAdapter:112` 已填 NODE_OUTPUTS,Export/Dispatch/Process 按需后补)。剩余 Stage 2(WorkflowParamResolver)/ Stage 3(集成 SchedulePlanBuilder)/ Stage 4(7 workflow 配 DSL)。
> - **deep-issue §5.7**(trigger → orchestrator 同步 HTTP):仍未修,排期推到 ADR-010(trigger 异步化主体)。
> - 修订动机:本评估文档 `project-assessment-2026-04-29.md` 原标 §1+§2+§6 为"未完成"是评估口径滞后于代码事实,本轮把三者状态同步到此处。

> **2026-04-27 第六轮 — P2-3 / P2-4 补完 + P1-1 Stage 1 落地 + P2-1/P2-9 标"不做"**：
> - **V5-P2-4** compensation happy-path（4/6 类）：`DefaultCompensationServiceTest` 加 PARTITION / STEP / DLQ / FILE 4 个 happy path（13 tests 通过）；JOB/BATCH 留 P2-4-ext
> - **V5-P2-3** quota 压测 smoke：跑 JobLaunchSimulation 105 reqs / 25s，p95=112ms，0 失败；报告归档 `testing/load-test-report.md`；真打满 quota 留 P2-3-ext（需先配低 quota policy）
> - **V5-P1-1** Stage 1：`db/migration/V72__add_workflow_node_run_output.sql` 落地（output JSONB 列），完成 ADR-009 4 stage 第一步；Stage 2-4（worker outputs 上报 + WorkflowParamResolver + 集成到 SchedulePlanBuilder）留单独 sprint
> - **V5-P2-1**（6 渠道单 adapter IT）+ **V5-P2-9**（GATEWAY/FILE_STEP）：业务层级未真需要 / 依赖 P1-1 完整落地，本批**不做**

> **2026-04-26 第三轮校准**：V5-P1-3 / V5-NEW-1 / V5-NEW-2 经代码审视也已实际完成或不构成 bug：
> - **V5-P1-3** EXPORT id 列校验：`SqlTemplateExportSpec:62-69` 已有早校验 + 友好错误（默认 cursorColumn=id + 用户 SQL 不含时抛 IllegalArgumentException 含完整修复指引）
> - **V5-NEW-1** workflow steps 协议错位：worker 代码不读 task_payload.steps（grep 全是局部变量），orchestrator 也不塞；4-24 commit 3dbb6d22 修了 resolveJobCode + node_params 后未复现
> - **V5-NEW-2** exp_settlement_csv_v1 模板源头：纳入 default-tenant 7 个 "system" 模板集合，业务无引用，不影响主链路；属"历史遗留 + 不影响"，不再追溯
>
> 剩余仅 V5-P1-1（Workflow DSL 串联，建议单独立项 ADR-009）+ 9 条 P2 验证型场景（按业务需求触发）。

> **2026-04-26 第四轮校准（P2 9 条逐条核实）**：v4 标"全部未碰"过度悲观，重新分类为 ✅ 3 / 🟡 4 / ❌ 2。详见 §三。

---

## 一、✅ 已完成（v5 清账）

| 编号 | 主题 | 完成日期 | 主要 commit |
|---|---|---|---|
| V4-P0-1 | DB 改动入 seed 脚本 | 2026-04-21 | seed 脚本 + multi-tenant-seed UPDATE 块 |
| V4-P0-2 | worker capability_tags 心跳上报 | 2026-04-21 | `WorkerConfiguration.capabilityTags()` + heartbeat dto |
| V4-P0-3 | workflow 空壳种子 | 2026-04-22 | 0 个 workflow_definition 是空壳（已核实） |
| V4-P1-2 | ParseSupport 硬编码 CustomerImportPayload | 2026-04-22 | 删 `convertValue(CustomerImportPayload.class)` |
| V4-P1-4 | EXPORT `:bizDate` 占位符 | 2026-04-22 | `SqlTemplateExportSecurityProperties.allowedExtraParams = ["bizDate"]` 默认 |
| V4-P1-5 | DISPATCH non-retryable 标识 | 已完成 | `DefaultRetryGovernanceService:66` 的 `NON_RETRYABLE_ERROR_CODES` set 已含 7 条（DISPATCH_PREPARE_FILE_MISSING / FILE_NOT_FOUND / CHANNEL_NOT_FOUND / INVALID / PARSE_FAILED + EXPORT_GENERATE_NO_PAYLOAD + STEP_NOT_FOUND）|
| V4-P3-1 | calendar WARN 刷屏 | 2026-04-22 | 当前 trigger.log 0 calendar WARN（已核实） |
| V4-P3-2 | biz.transaction 索引 | 2026-04-22 | 现有 3 索引（pkey + account + tenant_date + unique txn_no） |
| V4-P3-3 | 失败实例堆积 | 2026-04-26 | SQL 状态集扩展含 FAILED/CANCELLED/TERMINATED；一次性脚本清 1222 FAILED + 24 CANCELLED；下次 30 天 retention 后自动归档 |
| V4-P3-4 | dead_letter NEW 堆积 | 2026-04-26 | cleanup-historical-failures.sql 顺手清 1242 NEW → 8；FK 顺序修正（先删 event_delivery_log 再删 outbox_event）|
| V5-P1-3 | EXPORT 强制 id 列友好错误 | 已完成 | `SqlTemplateExportSpec:62-69` 早校验 + 友好错误（默认 cursorColumn=id + 缺失时抛 IllegalArgumentException 含完整修复指引）|
| V5-NEW-1 | workflow steps 协议错位 | 不构成 bug | worker 代码不读 task_payload.steps；4-24 commit 3dbb6d22 修了 resolveJobCode + node_params 后未复现 |
| V5-NEW-2 | exp_settlement_csv_v1 模板源头 | 关闭（不追溯）| default-tenant 7 个 "system" 模板之一，业务无引用，不影响主链路；归类"历史遗留 + 不影响" |
| V5-P2-2 | 业务日历门禁 E2E | 2026-04-26 | `BatchWindowGateTest` 4 IT（in-window / WAIT / FAIL / 无 windowCode 4 分支）|
| V5-P2-8 | FIXED_WIDTH / XML parser IT | 2026-04-26 | `ParseStepFixedWidthAndXmlTest` 4 IT（FIXED_WIDTH 3 字段 + header/footer 跳过 + XML records envelope + XXE 防护）|
| V5-P2-3 | quota / fair-share 压测 smoke | 2026-04-27 | `JobLaunchSimulation` 跑通 105 reqs / 25s，p95=112ms / 0 失败；报告归档 `testing/load-test-report.md`；真打满 quota 留 P2-3-ext |
| V5-P2-4 | compensation 4/6 类 happy-path | 2026-04-27 | `DefaultCompensationServiceTest` +4 IT (PARTITION / STEP / DLQ / FILE)，13 tests 通过；JOB/BATCH 留 P2-4-ext |

---

## 二、🟡 部分完成（v5 待补完）

### V5-P1-1 · Workflow 节点间参数串联（DSL 部分仍缺）

**v4 进展（commit 3dbb6d22, 2026-04-24）**：
- `dispatchJobNode.buildChildLaunchRequest` 调 `mergeNodeParams` ✅
- `WORKFLOW_INTERNAL_PAYLOAD_KEYS` 防泄露 ✅

**仍缺**：上游节点 output → 下游节点 param 的 DSL 映射（如 SETTLE 生成的 fileId 自动塞进 DISPATCH partition）

**ADR 已立项**：[ADR-009 Workflow 节点间参数串联 DSL](../architecture/adr/ADR-009-workflow-param-dsl.md) （2026-04-26 Proposed）—— JSONPath-like DSL + worker 上报 outputs + WorkflowParamResolver 解析；分 4 stage 落地，~3 人天。

**当前进度（2026-04-27）**：
- ✅ **Stage 1**：`db/migration/V72__add_workflow_node_run_output.sql` schema 迁移落地（output JSONB 列）；本地 PG 已 ALTER + flyway_schema_history 同步
- ⏳ **Stage 2**：worker 上报 outputs Map（待）
- ⏳ **Stage 3**：WorkflowParamResolver 实现 + 单测（待）
- ⏳ **Stage 4**：集成到 DefaultSchedulePlanBuilder + E2E（待）

**成本**：L —— Stage 2-4 跨 worker 协议 / orchestrator 核心调度，按 ADR-009 单独 sprint 推进。

---

## 三、❌ 待办（v5 新优先级）

> **2026-04-26 第三轮校准后**：高优 / 新发现 共 4 条经代码审视已全部完成或不构成 bug，悉数移至上方"已完成"章节。
>
> **2026-04-26 第四轮校准（P2 9 条逐条核实）**：原 v4 标"全部未碰"过度悲观——3 条已完整 IT 覆盖、4 条部分覆盖（主体逻辑测了，专项验证缺）、2 条真未覆盖。重新分类如下。

### 🟢 P2 增量场景覆盖

#### ✅ 已完整 IT 覆盖（5 条 — 移到本节但保留 P2 编号）

| 编号 | 场景 | 实际覆盖 |
|---|---|---|
| V5-P2-2 | 业务日历门禁验证 | `BatchWindowGateTest` 4 IT（in-window allow / out-of-window WAIT / out-of-window FAIL / 无 windowCode 跳过）|
| V5-P2-5 | 文件 archive / redispatch 控制端点 | `FileGovernanceIntegrationTest`（archive + reconcile + arrival 全套）|
| V5-P2-6 | drain enable/disable | `OrchestratorDrainControllerTest` 测 GET status + POST enable + POST disable |
| V5-P2-7 | worker drain 生命周期（DRAINING → DECOMMISSIONED）| 5 IT 覆盖 |
| V5-P2-8 | FIXED_WIDTH / XML 文件格式 | `ParseStepFixedWidthAndXmlTest` 4 IT（FIXED_WIDTH 3 字段 + header/footer + XML records envelope + XXE 防护）|

#### 🟡 部分覆盖 → ✅ 本批补完 2 / 3 条

| 编号 | 场景 | 状态 |
|---|---|---|
| V5-P2-3 | quota / fair-share 配额压测 | ✅ smoke 完成（详见 §一已完成表）；真打满压测留 P2-3-ext |
| V5-P2-4 | compensation 独立验证 | ✅ 4/6 happy path 完成（PARTITION / STEP / DLQ / FILE）；JOB/BATCH 留 P2-4-ext |
| ~~V5-P2-1~~ | ~~6 类非 SFTP dispatch 渠道单 adapter IT~~ | ❌ **本批不做**（业务接入对应渠道时再做） |

#### ❌ 真未覆盖

| 编号 | 场景 | 状态 |
|---|---|---|
| ~~V5-P2-9~~ | ~~Workflow PIPELINE / MIXED + GATEWAY / FILE_STEP 节点~~ | ❌ **本批不做**（依赖 V5-P1-1 完整落地后再做）|

---

## 推荐 v5 执行顺序

| 批次 | 内容 | 估时 | 收益 | 状态 |
|---|---|---|---|---|
| 批次 1（数据 + 调度器）| V5-P3-3 数据清 + 调度器扩展终态 | 半天 | 主表瘦身 | ✅ 已完成 |
| 批次 2（业务体验）| V5-P1-3 EXPORT id 校验 + V5-P1-5 non-retryable | 半天 | 用户体验 | ✅ 已完成（实际之前就做了） |
| 批次 3（结构性）| V5-NEW-1 / V5-NEW-2 | 1 天 | 防 workflow 异常 | ✅ 已完成（实际不构成 bug） |
| 批次 4（P2 验证型）| V5-P2-2 日历门禁 E2E + V5-P2-3 quota 压测 + V5-P2-4 compensation 专项 + V5-P2-1 单 adapter IT | 2-3 天 | P2 部分覆盖项补完 | 🟡 4 项 |
| 批次 5（架构改造）| **V5-P1-1 DSL 串联**（单独立项 ADR-009）| 2-3 天 | 解锁 P2-9 完整 workflow | 🟡 待立项 |
| 批次 6（业务驱动）| V5-P2-8 FIXED_WIDTH/XML（业务接入新格式时）+ V5-P2-9（依赖批次 5）| 按需 | 业务接入 | ❌ 等触发 |

---

## 维护规则

- **每发版**：把"已完成"项移到附录或归档（避免 backlog 越长越长）
- **每月**：用本版校验流程（grep + DB 查 + 日志查）重新核对每条状态，避免 v4 那种"顶部已完成 / 明细未更新"的不一致
- **新发现**：先加进 V5-NEW-N，下次重排时归类到 P0/P1/P2/P3
