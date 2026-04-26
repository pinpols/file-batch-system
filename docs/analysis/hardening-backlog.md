# 🛡 硬化与遗留问题 Backlog · v5

> 产出日期：2026-04-26
> 上一版：[`../archive/analysis/hardening-backlog-v4.md`](../archive/analysis/hardening-backlog-v4.md)（2026-04-21）
> 校准方法：v4 18 条逐条对照本日代码 / 数据库 / 日志现状重新评估
> 修订历史：v4 顶部声称"P0/P1/P3 全部完成"，实测有 5 项未完成或部分完成；本版按真实状态重新分类

---

## 总览

| 优先级 | 已完成 | 部分完成 | 待办 | 合计 |
|---|:---:|:---:|:---:|:---:|
| P0 立即止血 | 3 | 0 | 0 | 3 |
| P1 结构性 | 4 | 1 | 0 | 5 |
| P2 增量场景 | 3 | 4 | 2 | 9 |
| P3 小瑕疵 | 4 | 0 | 0 | 4 |
| **新发现（v5 新增）** | 2 | 0 | 0 | 2 |
| **合计** | **16** | **5** | **2** | **23** |

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

---

## 二、🟡 部分完成（v5 待补完）

### V5-P1-1 · Workflow 节点间参数串联（DSL 部分仍缺）

**v4 进展（commit 3dbb6d22, 2026-04-24）**：
- `dispatchJobNode.buildChildLaunchRequest` 调 `mergeNodeParams` ✅
- `WORKFLOW_INTERNAL_PAYLOAD_KEYS` 防泄露 ✅

**仍缺**：上游节点 output → 下游节点 param 的 DSL 映射（如 SETTLE 生成的 fileId 自动塞进 DISPATCH partition）

**修法建议**：
- 设计 JSONPath-like DSL（`node_params.dispatch.fileId = "$.nodes.SETTLE.output.fileId"`）
- 或 `workflow_node_run` 记录 output schema，下游派发时读上游

**成本**：L（<3d）—— 涉及 orchestrator 调度核心，建议**单独立项**（不在 v5 闭环范围）

**单独立项原因**：DSL 设计影响 schema、调度器、worker 协议三层；需要先写设计文档（如 ADR-009）讨论 DSL 语法 + 上游 output 捕获机制 + 下游 param 解析时机，再分阶段实施。一次会话内强行做完会留架构债。

---

## 三、❌ 待办（v5 新优先级）

> **2026-04-26 第三轮校准后**：高优 / 新发现 共 4 条经代码审视已全部完成或不构成 bug，悉数移至上方"已完成"章节。
>
> **2026-04-26 第四轮校准（P2 9 条逐条核实）**：原 v4 标"全部未碰"过度悲观——3 条已完整 IT 覆盖、4 条部分覆盖（主体逻辑测了，专项验证缺）、2 条真未覆盖。重新分类如下。

### 🟢 P2 增量场景覆盖

#### ✅ 已完整 IT 覆盖（3 条 — 移到本节但保留 P2 编号）

| 编号 | 场景 | 实际覆盖 |
|---|---|---|
| V5-P2-5 | 文件 archive / redispatch 控制端点 | `FileGovernanceIntegrationTest`（archive + reconcile + arrival 全套）|
| V5-P2-6 | drain enable/disable | `OrchestratorDrainControllerTest` 测 GET status + POST enable + POST disable |
| V5-P2-7 | worker drain 生命周期（DRAINING → DECOMMISSIONED）| 5 IT 覆盖：WorkerControllerTest / DefaultWorkerLifecycleManagerTest / WorkerRegistryIntegrationTest / DefaultWorkerDrainGovernanceServiceTest / WorkerDrainTimeoutSchedulerTest |

#### 🟡 部分覆盖（4 条 — 主逻辑有 IT，专项验证缺）

| 编号 | 场景 | 已有覆盖 | 缺什么 | 成本 |
|---|---|---|---|---|
| V5-P2-1 | 6 类非 SFTP dispatch 渠道（OSS / LOCAL / API / API_PUSH / EMAIL / NAS） | 6 个 ChannelAdapter 类 + `DispatchExternalChannelIntegrationTest` 3 @Test 涵盖主流；NAS×2、EMAIL×2、HTTP×1、API_PUSH×1、OSS×3、LOCAL×6、SFTP×3 测试文件引用 | 单 adapter 专项 IT（如 `SmtpEmailDispatchChannelAdapterTest`）| M（每 adapter 1-2h）|
| V5-P2-2 | 业务日历门禁验证 | 3 IT（OrchestratorConfigCacheServiceIT / StartupSelfCheckIT / BatchDaySqlMigrationsIT）测加载 + 缓存 + migration | "日历不允许时挂起任务"门禁逻辑专项 E2E | S |
| V5-P2-3 | quota / fair-share 配额压测 | load-tests 模块就位（JobLaunchSimulation / CapacityBaselineSimulation / ConsoleQuerySimulation）+ 单测覆盖 quota mapper | **未真跑配额打满压测**记录拐点 | M（Gatling + 数据准备） |
| V5-P2-4 | compensation 独立验证 | `DefaultCompensationServiceTest` 单测；retry/dead-letter IT 隐含覆盖 | 专项 compensation E2E（rerun job / retry partition / replay file 6 类全跑）| S |

#### ❌ 真未覆盖（2 条）

| 编号 | 场景 | 缺口 | 成本 | 何时做 |
|---|---|---|---|---|
| V5-P2-8 | FIXED_WIDTH / XML 文件格式 | `worker-import/test/.../stage/format` 目录无 parser 测试；只跑过 CSV + JSON | M（写 2 套 parser IT + sample data） | 业务接入对应格式时 |
| V5-P2-9 | Workflow PIPELINE / MIXED + GATEWAY / FILE_STEP 节点 | DagServiceTest 不含 GATEWAY；缺节点类型测试 | L | **依赖 V5-P1-1 DSL 完成**后 |

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
