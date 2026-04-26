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
| P1 结构性 | 2 | 2 | 1 | 5 |
| P2 增量场景 | 0 | 0 | 9 | 9 |
| P3 小瑕疵 | 2 | 1 | 1 | 4 |
| **新发现（v5 新增）** | — | — | **2** | 2 |
| **合计** | **7** | **3** | **13** | **23** |

---

## 一、✅ 已完成（v5 清账）

| 编号 | 主题 | 完成日期 | 主要 commit |
|---|---|---|---|
| V4-P0-1 | DB 改动入 seed 脚本 | 2026-04-21 | seed 脚本 + multi-tenant-seed UPDATE 块 |
| V4-P0-2 | worker capability_tags 心跳上报 | 2026-04-21 | `WorkerConfiguration.capabilityTags()` + heartbeat dto |
| V4-P0-3 | workflow 空壳种子 | 2026-04-22 | 0 个 workflow_definition 是空壳（已核实） |
| V4-P1-2 | ParseSupport 硬编码 CustomerImportPayload | 2026-04-22 | 删 `convertValue(CustomerImportPayload.class)` |
| V4-P1-4 | EXPORT `:bizDate` 占位符 | 2026-04-22 | `SqlTemplateExportSecurityProperties.allowedExtraParams = ["bizDate"]` 默认 |
| V4-P3-1 | calendar WARN 刷屏 | 2026-04-22 | 当前 trigger.log 0 calendar WARN（已核实） |
| V4-P3-2 | biz.transaction 索引 | 2026-04-22 | 现有 3 索引（pkey + account + tenant_date + unique txn_no） |

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

**成本**：L（<3d）—— 涉及 orchestrator 调度核心，建议单独立项

---

### V5-P3-3 · 失败实例历史堆积（机制有，数据未清）

**v4 已落地**：`archive.job_instance_archive` cold table（V71 migration）+ `archive_policy` 表（V48）+ `WorkflowArchiveScheduler` / `SuccessInstanceArchiveScheduler`

**实测剩余**：`batch.job_instance` 主表仍有
- FAILED: 1224 条
- CREATED: 116 条
- CANCELLED: 24 条

**根因**：archive_policy 中 `archive_enabled=FALSE` 默认；FAILED / CREATED 状态的 instance 没被清理调度器覆盖（现有 SuccessInstance 只清 SUCCESS）

**修法**：
- 一次性 SQL 归档 → 主表瘦身
- 或新增 `FailedInstanceArchiveScheduler` 周期清理

**成本**：S（一次性 SQL）/ M（新调度器）

---

### V5-P1-5 · DISPATCH non-retryable 标识不明确

**v4 现状**：`PrepareDispatchStep` return `failure("DISPATCH_PREPARE_FILE_MISSING", ...)` ✅ 但 retry governance 是否识别为 non-retryable 未明确

**待验证 + 改进**：
- 看 `RetryGovernanceService` 是否有 `nonRetryableErrorCodes` 配置
- 把 `DISPATCH_PREPARE_FILE_MISSING` / `DISPATCH_PREPARE_CHANNEL_NOT_FOUND` 加入跳过列表

**成本**：XS

---

## 三、❌ 待办（v5 新优先级）

### 🔴 高（影响业务 / 噪音）

#### V5-P1-3 · EXPORT sql_template 强制 id 列（未做）

**现象**：用户 default_query_sql 没 `SELECT id, ...` → `bad SQL grammar` 不友好错误

**修法**：
- 显式检查 query 含 `id` 列，缺失报 `EXPORT_QUERY_MISSING_ID` 友好错误
- 或 template 声明 `orderBy` 列替代硬编码 id
- 或复合主键 fallback（ctid）

**文件**：worker-export 包装层 SQL 代码（待精确定位）

**成本**：S（<4h）

---

#### V5-P3-4 · dead_letter_task 1242 条 NEW 堆积（机制都没有）

**v4 状态**：归档机制**完全未做**（grep 不到 `DeadLetterArchive` 类）

**实测**：`dead_letter_task.replay_status = NEW` 1242 条（比 v4 的 110+ 多 10 倍）

**修法**：
- 新增 `DeadLetterArchiveScheduler`（参考 `OutboxArchiveScheduler`）
- 一次性 SQL 把过期 NEW 归档到 `archive.dead_letter_task_archive`
- 加 cold table

**成本**：M

---

### 🟡 新发现（v4 备忘录提到）

#### V5-NEW-1 · workflow defaultParams `steps` 被 worker 误解析

**现象**（v4 line 24）：workflow SETTLE 节点被 EXPORT worker 认领，worker step registry 报 `STEP_NOT_FOUND: DISPATCH_PREPARE` —— EXPORT worker 把 payload 里 `steps: ["settle","export","dispatch"]`（workflow defaultParams）当步骤执行链

**根因**：Workflow ↔ worker-side step executor 协议错位

**修法**：worker 侧识别 workflow defaultParams 不是 pipeline steps；或 orchestrator 派发时过滤掉 workflow-level params

**成本**：S（协议层 + worker 守护）

---

#### V5-NEW-2 · `default-tenant/exp_settlement_csv_v1` 模板源头未定位

**v4 备忘**：该模板 live DB created_by='system'，应来自租户初始化服务/Console 上传，但源头不明

**待办**：trace 上传路径，要么写文档归类，要么补 seed

**成本**：S

---

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
