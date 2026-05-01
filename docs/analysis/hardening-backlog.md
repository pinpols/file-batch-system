# 🛡 硬化与遗留问题 Backlog · v6

> 产出日期:2026-04-30
> 上一版:[`./hardening-backlog.md@v5`](./hardening-backlog.md)(2026-04-26 — 本文件直接覆盖,v5 不再单独保留;历史诊断详见 git log)
> 校准方法:v5 后 4 天密集开发 17+ 个主线 commit,逐条核 ADR-009 / ADR-010 全栈 + deep-issue 6 项现状 + v2 评估 P1/P2 项
> 修订历史:v5 顶部"P0/P1/P3 全部完成"在 ADR-009/010 全栈实装 + console god service 拆完后已是事实,v6 把 v5 误标"未完成"的项移到"已完成",新增 v2 评估锁定的 4 项硬化条目

---

## v6 滚版变更要点(2026-04-30)

✅ **新增"已完成"4 项**(v5 误标"未完成"或本会话刚清账):
- **deep-issue §5.1** trigger Spring Security:`cd389a0b`(2026-04-22 v4 闭环)`TriggerSecurityConfiguration.java:42-46`
- **deep-issue §5.2** X-Console-Token 物理删除:`ff20c36f` 主代码 + yaml + OpenAPI + 测试 9 文件 +20/-168
- **deep-issue §5.7** trigger → orchestrator 异步化:ADR-010 全栈 7 stage(`9587b8bf` / `087f6b7a` / `1ca3a957` / `22b330ea` / `788b637d` / `68bc49e8`),含 22 测试全绿(单测 9 + relay 7 + Layer 1 trigger E2E 4 + Layer 2 跨模块 E2E 2)
- **deep-issue §5.12** Console Job 过胖:`DefaultConsoleJobApplicationService` 现 90 LOC + 6 兄弟类 1278 LOC

🟢 **ADR 路线图全栈完成**:
- **ADR-009 Workflow DSL**:Stage 1(V72 列)/ 1.2(worker outputs 上报)/ 2(`WorkflowParamResolver` 160 LOC + 10 单测)/ 3(`DefaultWorkflowNodeDispatchService.mergeNodeParams` 集成)全栈,Stage 4(7 workflow 配 DSL)是业务方按需触发 deferred
- **ADR-010 trigger 异步解耦**:Stage 1-5 代码 100%,Stage 6 灰度 operational,Stage 7 物理删除等 1 minor
- 配套 V80 `trigger_outbox_event` schema + `docs/runbook/trigger-async-launch-rollout.md` 280 行 SOP + `heal-zombie-pipelines.sh` ops 脚本

🆕 **v2 评估锁定的 4 项硬化条目**(2026-04-30):
- **V6-OPS-1** ✅(部分):`.env.prod` `KAFKA_TOPICS` 缺 `batch.trigger.launch.v1` + `batch.task.dispatch.process` — 本会话本地修;治本要 CI 加 `.env.prod` 与 `.env.example` 同步检查
- **V6-OPS-2** ✅:Prometheus 3 条 ADR-010 告警(`TriggerOutboxBacklogGrowing` / `TriggerLaunchFailureSpike` / `TriggerOutboxGiveUp`)从 runbook §建议落 `prometheus-batch-rules.yml` — `0c623eb0`
- **V6-Q-1** ✅:9 处 FQN 违规(`BizExceptionUtils:69` / `ConsoleAuthenticationFilter:93+116` / `ConfigPackageExcelValidator:855` / `PartitionLifecycleService:17` / `PlatformFileRuntimeRepository:209+251+268+290`)5 文件批量改 — `8dc6eac1`,全仓 grep 残留 0
- **V6-NOISE-1** ✅:运行日志噪声治理 — ChannelConfigMerge `LEGACY_REDUNDANT_KEYS` + FileGovernance `processingDelayMaxAgeSeconds` 默认 7 天 zombie 上限 + `heal-zombie-pipelines.sh` 闭环 — `aa249bf8` / `0d650fab`

🟢 **v6 P2 部分清账**(2026-04-30 14:55 `b74e0a0c` "feat(p2): 4 个 P2 项一把过"):
- ✅ **V6-P2-WEBHOOK-DURABILITY**(deep-issue §5.11)— **已完成**。V81 migration `delivery_status` CHECK 加 `GIVE_UP` + `(status, next_retry_at)` 部分索引;`WebhookDeliveryRelay.java` 278 行(@ConditionalOnProperty 默认开 + ShedLock 互斥 + 5min/10min/20min/30min cap 退避 + absolute-max-attempts=8 后 GIVE_UP);抽 `WebhookEventPayload` + `WebhookDeliveryResult` 顶级类;新 `batch_webhook_delivery_give_up_total` counter + `WebhookDeliveryGiveUp` Prometheus 告警;7 个 `WebhookDeliveryRelayTest` 单测全绿
- ✅ **V6-P2-ORCHESTRATOR-GODCLASS** — **已完成**(2026-04-30 20:46 `7d6faad6` 完结收尾)。`DefaultTaskOutcomeService` 926 → 795 LOC (-14%):抽 `TaskOutcomePayloadSupport` (104 LOC) + `TaskOutcomeSummaryBuilder` (76 LOC) + 内联 helper(`b74e0a0c`)。`DefaultWorkflowNodeDispatchService` 840 → **371 LOC** (-56%)(`7d6faad6`):抽 `WorkflowNodePayloadBuilder` (311 LOC, Cluster F: payload 拼装 + 上游 partition output 合并 + ADR-009 DSL 解析) + `ChildJobLaunchSupport` (276 LOC, Cluster B+C: JOB 节点子作业拉起全套);保留 25 LOC 重复 (`recordNodeRunReady`/`nextRunSeq`) 比抽公共类成本低;主 service 留 `dispatchNode`/`dispatchTaskNode`/`dispatchGatewayNode` 核心调度路径;test 构造器 5 → 4 参 同步;12 个 `*WorkflowNode*Test` + `*Dispatch*Test` 全绿,506 IT 仅 1 已知 race flake (`WorkerClaimProgressCompleteIT`,isolation 重跑通过)。
- 🟢 **V6-P2-EXCEL-GODCLASS** — **6/7 完成**(2026-04-30 `002b8864` + `bd0f0532` + `b9eefb47`)。P2-3 战场 7 个 god class 主 service 平均 **-67% LOC**,新增 13 个收口类:
  - `DefaultConsoleWorkflowExcelApplicationService` 1512 → **497** (-67%) [`002b8864`]:抽 8 类(metadata/writer/parser/validator/keys/text-utils/parsed-session/validation-result)
  - `DefaultConsoleTenantConfigInitApplicationService` 823 → **120** (-85%) [`b9eefb47`]:抽 `TenantConfigInitApplyHandlers` 集中 10 类 spec apply + insert/update/upsert
  - `DefaultConsoleJobDefinitionExcelApplicationService` 887 → **663** (-25%) [`bd0f0532`]:抽 writer
  - `DefaultConsoleBusinessCalendarExcelApplicationService` 1009 → **763** (-24%) [`bd0f0532`]:抽 writer (含 SheetSpec 模板)
  - `DefaultConsolePipelineDefinitionExcelApplicationService` 1061 → **822** (-22%) [`bd0f0532`]:抽 writer
  - `DefaultConsoleTenantConfigPackageExcelApplicationService` 846 → **728** (-14%) [`bd0f0532`]:抽 row projections
  - `ConfigPackageExcelValidator` 874 LOC **保留**(已是 single-purpose validator,内部 8 个 validateXxxRows 共享 cross-reference 数据,split 8 文件反而 fragment + overhead)

✅ **v6 全部 P2 已闭环**(2026-04-30):
- **V6-P2-CONSOLE-IDEMPOTENCY**(deep-issue §5.5 / §5.6 / §5.10)— **已完成**。代码 3 层各自归位:
  - **Layer 1** `ConsoleIdempotencyInterceptor` 全文重写 — key 绑定 (tenant+method+uri+idempotencyKey),两阶段占坑(`PENDING` 30s → 2xx 升 `DONE` 24h / 非 2xx DELETE 释放),Redis fail-closed (503)
  - **Layer 2** `DefaultTriggerService.approvePendingCatchUp` (`:134-142`) 用 `idempotencyKey` 查 `trigger_request` 已 `LAUNCHED` 短路返回;类 Javadoc (`:60-71`) 明示"trigger 层只做尽力去重,最终去重由 orchestrator 兜底"
  - **Layer 3** `db/migration/V37` 删 `uk_trigger_request_tenant_dedup` + `uk_job_instance_tenant_dedup` 作为最终事实源
  - 设计定稿 `docs/architecture/adr/ADR-011-idempotency-boundary-alignment.md`

🆕 **V6-P2-POSITIONAL-ARGS** · 位置参数构造臃肿治理 v3(2026-05-01 方案定稿,业界标准对齐版):
- **背景**:CLAUDE.md "方法参数 ≥7 必须封装" 第一阶段落地后,参数臃肿从方法签名搬到 `new XxxParam(a,b,...,n)` inline 调用,留下 main 反例
- **范围(业界对齐后收窄)**:① 方法签名 argc=7 共 **7** 处 + ② inline argc>6 共 **54** 处 = **61 处**
  - **删桶 ③(argc=4-6 共 137 处)**:Effective Java / Google Style / Oracle Conventions 均未禁止 `f(new Foo(a..f))`,业界无依据
  - **豁免声明式注册类**:`ConsoleMenuRegistry`(41 处 MenuItem + 8 处 MenuGroup) / Excel `*SchemaRegistry`(8 处 SheetDef) 等,inline new 在声明式数据结构里是业界鼓励写法
- **统一动作**:② 加 `@Builder` + 提取引用 + 默认值不显式 set;class 加 `@Builder` 用 `@NoArgsConstructor`+`@AllArgsConstructor` 三连或 `@Tolerate` 兜底空参,**不降级**
- **提交策略**:**1 个大 PR** 内 4 commit 拆分(① / ② / 守护测试 / 文档),~1100 行 diff
- **规约同步**:CLAUDE.md §方法参数约束 追加"调用方约束"子节 + docs/changelog.md 2026-05-01 条目 + 守护 `PositionalArgsConventionTest` 白名单方式拦回潮
- **不做**:Spring Data JDBC entity 强制 `@Builder` / 重排 record 字段 / argc≤6 治理 / 声明式注册类 / test 重灾区 fixture builder(独立立项)
- **详细方案**:[`positional-args-cleanup-plan.md`](./positional-args-cleanup-plan.md) v3
- **前置 PR-A**:Query record 17 类 / 39 处调用点 + `QueryRecordConstructionConventionTest` 已完成(主线 commit 待 push)

🟡 **deferred(基础设施完备,触发条件出现再做)**:
- **V6-D-1** ADR-009 Stage 4 业务配 DSL — 现有 seed 节点间 `mergeUpstreamPartitionOutputs` 自动透传 fileId 已够用,业务方设计跨节点参数串联时按 §10 文档配
- **V6-D-2** ADR-010 Stage 6 灰度 operational — staging → canary → prod 按 `trigger-async-launch-rollout.md` SOP 执行(需真部署环境)
- **V6-D-3** ADR-010 Stage 7 物理删除旧 HTTP 路径 — 灰度全量切稳定 1 minor 后
- **V6-D-4** I4 `buildContext` 模板抽取 — 等 4 个 `*JobContext` 出现共同基类时再做
- **V6-D-5** Worker 4 模块单测密度补齐 — 各 Default*StageExecutor + *StepExecutionAdapter 加 5-10 个单测

---

## 总览(v6)

| 优先级 | 已完成 | 部分完成 | 待办 | 不做(标) | 合计 |
|---|:---:|:---:|:---:|:---:|:---:|
| **deep-issue §5 6 项** | **5**(§5.1 Sec / §5.2 token / §5.5 幂等 / §5.7 异步 / §5.11 webhook / §5.12 god) | **1**(§5.2 X-Token compat 物删) | 0 | 0 | 6 |
| **ADR 路线图** | **3**(ADR-009 / ADR-010 代码 100% / ADR-011 幂等边界定稿) | 0 | **2**(ADR-009 Stage 4 业务配置 / ADR-010 Stage 6+7 灰度+物删) | 0 | 5 |
| **v2 评估硬化** | **6**(OPS-1 / OPS-2 / Q-1 / NOISE-1 / **WEBHOOK-DUR** / **IDEMP**) | **2**(ORCH-GOD / EXCEL-GOD 各部分) | 0 | 0 | 8 |
| v5 历史 P0-P3 | 19 | 2 | 0 | 2 | 23 |
| **合计** | **33** | **5** | **2** | **2** | **42** |

> **总览解读**:v5 时声称 P0/P1 全完成但有 5 项未实测对齐;v6 把 ADR-009/010 全栈、deep-issue §1+§2+§5+§7+§11+§12 实地核验为已完成,新增 v2 评估的 4 项硬化条目。**2026-04-30 14:55 `b74e0a0c` 清掉 V6-P2-WEBHOOK-DURABILITY 全栈** + 16:42 `b9eefb47` 完结 P2-3 EXCEL-GODCLASS 战场全 7 类(主 service 平均 -67% LOC,新增 13 个收口类) + 同日定稿 **ADR-011 幂等三层边界**(deep-issue §5.5/§5.6/§5.10 三处一并闭环,3 层代码已实施,本 ADR 是事后定稿)。完成率 **33/42 = 79%**(部分完成 +2 还未计入);余 2 项 follow-up 都是各 god class 拆分剩余,独立 sprint 排期。

## v5 历史总览(归档)

| 优先级 | 已完成 | 部分完成 | 待办 | 不做(标) | 合计 |
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
