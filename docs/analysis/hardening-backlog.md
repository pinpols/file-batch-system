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
| P2 增量场景 | 0 | 0 | 9 | 9 |
| P3 小瑕疵 | 4 | 0 | 0 | 4 |
| **新发现（v5 新增）** | 2 | 0 | 0 | 2 |
| **合计** | **13** | **1** | **9** | **23** |

> **2026-04-26 第三轮校准**：V5-P1-3 / V5-NEW-1 / V5-NEW-2 经代码审视也已实际完成或不构成 bug：
> - **V5-P1-3** EXPORT id 列校验：`SqlTemplateExportSpec:62-69` 已有早校验 + 友好错误（默认 cursorColumn=id + 用户 SQL 不含时抛 IllegalArgumentException 含完整修复指引）
> - **V5-NEW-1** workflow steps 协议错位：worker 代码不读 task_payload.steps（grep 全是局部变量），orchestrator 也不塞；4-24 commit 3dbb6d22 修了 resolveJobCode + node_params 后未复现
> - **V5-NEW-2** exp_settlement_csv_v1 模板源头：纳入 default-tenant 7 个 "system" 模板集合，业务无引用，不影响主链路；属"历史遗留 + 不影响"，不再追溯
>
> 剩余仅 V5-P1-1（Workflow DSL 串联，建议单独立项 ADR-009）+ 9 条 P2 验证型场景（按业务需求触发）。

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
> 当前 ❌ 待办 = 仅 P2 9 条（验证型，按业务需求触发）。

### 🟢 P2 增量场景覆盖（验证型，9 条原样保留）

按业务需求优先级取，**不依赖任何前置项**（除 P2-9 等 P1-1）。

| 编号 | 场景 | 成本 | 何时做 |
|---|---|---|---|
| V5-P2-1 | 6 类非 SFTP dispatch 渠道（OSS / LOCAL / API / API_PUSH / EMAIL / NAS） | M | 业务接入对应渠道时 |
| V5-P2-2 | 业务日历门禁验证 | S | calendar 真用上时 |
| V5-P2-3 | quota / fair-share 配额压测 | M | 接近 quota 上限时 |
| V5-P2-4 | compensation 独立验证 | S | 单独 sprint |
| V5-P2-5 | 文件 archive / redispatch 控制端点 | S | 运维要清旧数据时 |
| V5-P2-6 | drain enable/disable | XS | 测试时顺手 |
| V5-P2-7 | worker drain 生命周期 (DRAINING → DECOMMISSIONED) | S | 同上 |
| V5-P2-8 | FIXED_WIDTH / XML 文件格式 | M | 业务接入对应格式时 |
| V5-P2-9 | Workflow PIPELINE / MIXED + GATEWAY / FILE_STEP 节点 | L | **依赖 V5-P1-1 DSL 完成**后 |

---

## 推荐 v5 执行顺序

| 批次 | 内容 | 估时 | 收益 |
|---|---|---|---|
| **批次 1**（XS-S 快赢）| V5-P3-3 一次性 SQL 清 1224 失败实例 + V5-P3-4 写 DeadLetterArchiveScheduler | 半天 | 主表瘦身，UI 不被历史噪声干扰 |
| **批次 2**（业务体验）| V5-P1-3 EXPORT id 校验友好错误 + V5-P1-5 DISPATCH non-retryable 标注 | 半天 | 用户配置体验改善 |
| **批次 3**（结构性）| V5-NEW-1 workflow steps 协议错位 + V5-NEW-2 模板源头追踪 | 1 天 | 防 workflow 异常 |
| **批次 4**（架构改造）| **V5-P1-1 DSL 串联**（单独立项）| 2-3 天 | 解锁 V5-P2-9 完整 workflow |
| **批次 5+**（按需）| V5-P2-1 ~ V5-P2-9 验证场景 | 每条 0.5-2 天 | 业务接入 / 上量前 |

---

## 维护规则

- **每发版**：把"已完成"项移到附录或归档（避免 backlog 越长越长）
- **每月**：用本版校验流程（grep + DB 查 + 日志查）重新核对每条状态，避免 v4 那种"顶部已完成 / 明细未更新"的不一致
- **新发现**：先加进 V5-NEW-N，下次重排时归类到 P0/P1/P2/P3
