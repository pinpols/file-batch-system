# ADR-012 · 失败分类（Failure Taxonomy）

- **Status**: Accepted（**第 1 阶段必做 / P0**，不越界，~3-5 人天）
- **Date**: 2026-05-06
- **Supersedes**: —
- **Related**: ADR-011（幂等边界对齐）/ ADR-013（分布式 tracing）/ §14.3.2 后端缺口审计 / [ADR 012/021-027 优先级 + 范围边界](../../analysis/adr-012-021-027-priority-scope-2026-05-06.md)

## 范围边界（Scope Discipline）

> **批量系统核心能力，不越界。**failure_class 不是为了"好看"，是为了支撑 retry / escalation alert routing / 自动 RERUN / DLQ 重放 / SLA 分析五条响应链。

| ✅ 做 | ❌ 不做 |
|---|---|
| 7 enum 封闭分类 + DictEnum 一等公民 | 让 worker 自定义新 FailureClass（枚举封闭） |
| 按 class 派发 retry / alert / RERUN | 把 FailureClass 拆成多个终态（保留 FAILED 单一终态 + class 列） |
| Worker 显式上报 + classifier chain 回退 | 让 ops 在 console 手填 errorClassHint 改分类（污染审计） |
| `retry_policy_by_class` JSONB 让 job 级覆盖 | 按 class 分流到不同 worker pool（与 ADR-027 正交，v1 不做） |

## 背景

当前 `job_instance` 的失败终态只有一个 `FAILED`，错误信息散落在 `error_key / error_args / error_message` 三个字符串列。失败响应按统一策略处理：retry policy 由 `job_definition.retry_policy ∈ {NONE, FIXED, EXPONENTIAL}` 全局决定，不区分根因。

真实场景四类失败响应完全不同：

| 失败类 | 真因 | 正确响应 |
|---|---|---|
| **INFRASTRUCTURE** | DB 异常退出 / Kafka 抖动 / Worker 网络 | SRE 自动重试，业务方零感知 |
| **DATA_QUALITY** | 行级校验失败 / 上游数据缺字段 | 业务方介入修数，不要重试 |
| **BUSINESS_RULE** | 余额不足 / 审批拒绝 / 配额超限 | 跳到下一笔，不算 job 级失败 |
| **CONFIG** | cron 错 / SQL 写错 / 资源不存在 | ops 修配置后人工 RERUN |
| **UPSTREAM_DELAY** | 前置 job 还没 EFFECTIVE / 文件没到 | 等 + 接 ADR-018 跨日依赖唤醒 |
| **TIMEOUT** | job 跑超过 SLA / partition lease 过期 | 看上下文 — 网络可能是 INFRA，业务可能是 DATA |

不分类的代价：

- 一次 DB 闪断把所有跑着的 job_instance 都标成 FAILED，运维半夜起来 RERUN 几百个，但其实自动重试 1 次就好；
- 数据质量问题被 EXPONENTIAL retry 重试 8 次，每次都失败，浪费 40 分钟还污染监控；
- 配置错的 job 被自动 RERUN 直到 retry 上限，业务方还以为系统在试。

## 决策

引入 `FailureClass` 枚举一等公民 + 按 class 派发响应链。

### 核心模型

```sql
ALTER TABLE batch.job_instance
    ADD COLUMN IF NOT EXISTS failure_class VARCHAR(32);  -- 终态时填，非 FAILED 为 NULL

ALTER TABLE batch.job_task
    ADD COLUMN IF NOT EXISTS failure_class VARCHAR(32);  -- task 级根因，可与 instance 不同
```

```java
public enum FailureClass implements DictEnum {
  INFRASTRUCTURE("INFRASTRUCTURE", "基础设施异常 — 自动重试候选"),
  DATA_QUALITY("DATA_QUALITY", "数据质量异常 — 业务方介入"),
  BUSINESS_RULE("BUSINESS_RULE", "业务规则异常 — 跳过或人工裁决"),
  CONFIG("CONFIG", "配置异常 — ops 修配置后 RERUN"),
  UPSTREAM_DELAY("UPSTREAM_DELAY", "上游延迟 — 等待 / WAITING_DEPENDENCY"),
  TIMEOUT("TIMEOUT", "超时 — 上下文相关，二次分类"),
  UNKNOWN("UNKNOWN", "未分类 — 需要 ops 人工判定");
}
```

### 分类来源（写入路径）

按写入侧由近及远：

1. **Worker 上报时显式标**：`TaskExecutionReportDto.failureClass`，worker 业务侧最知根因；
2. **BizException 携带**：`BizException` 增加 `failureClass` 字段，`error.<scope>.<reason>` key 在 `messages.properties` 维护对应 class（例：`error.data.row_validation_failed=DATA_QUALITY`）；
3. **Orchestrator 回退分类器**：`FailureClassifier`（chain）按异常类型 / SQL state / HTTP code / timeout 模式做 fallback；
4. **不知道就 UNKNOWN**：永远不要为了显得"有分类"瞎猜；UNKNOWN 触发 alert 让 ops 看一眼。

### 响应派发

| FailureClass | 默认 retry | escalation alert | 自动 RERUN | 反向回退 |
|---|---|---|---|---|
| INFRASTRUCTURE | EXPONENTIAL up to 5 | INFO → WARN @ retry=3 | ✓ 含 jitter | ✓ DB CAS retry_count |
| DATA_QUALITY | NONE | ERROR 立即 | ✗ | 写 `data_quality_check`（ADR-021） |
| BUSINESS_RULE | NONE | INFO 仅审计 | ✗ | partition skip + summary |
| CONFIG | NONE | ERROR 立即 | ✗ | 等 ops PR 修 |
| UPSTREAM_DELAY | WAIT（不算 retry） | INFO | reconciler 唤醒 | 接 ADR-018 |
| TIMEOUT | 二次分类后派发 | WARN | 看上下文 | — |
| UNKNOWN | FIXED 1 次 | WARN 立即 + ops review | ✗ | 等 ops 重新分类 |

### 配置层

`job_definition` 加 `retry_policy_by_class` JSONB 列让 job 级覆盖：

```json
{
  "INFRASTRUCTURE": {"strategy": "EXPONENTIAL", "maxAttempts": 5, "jitterMs": 200},
  "DATA_QUALITY":   {"strategy": "NONE"},
  "TIMEOUT":        {"strategy": "FIXED", "maxAttempts": 2}
}
```

NULL = 用 §响应派发 默认表。

### Console / 监控暴露

- `job_instance` 列表多 `failureClass` 列；
- 监控看板 `batch.job.failure.{class}` 系列 gauge；
- alert routing：INFRASTRUCTURE → SRE oncall channel；DATA_QUALITY → 业务 channel；CONFIG → ops channel。

## 影响面

| 维度 | 影响 |
|---|---|
| 持久层 | `job_instance / job_task` 各加 1 列（nullable），archive 同步；`job_definition` 加 1 JSONB 列 |
| 模块 | 新 `FailureClassifier` chain bean；orchestrator 终态推进路径接入；worker SDK 暴露 `failureClass` 字段；retry policy 解析层加按 class 分支 |
| 兼容性 | 老数据 `failure_class IS NULL` → 视为 UNKNOWN；老 worker 不上报 = orchestrator 用 classifier 回退；零行为变化前提下逐步替换 |
| 监控 | metric tag `failure_class` 维度；alert routing 重构（加个 dispatcher） |

## 实施分阶段

| Stage | 范围 | 状态 |
|---|---|---|
| 1 | schema + enum + 默认 NULL 不影响现有 | ✓ V111 + `FailureClass` enum |
| 2 | `FailureClassifier` chain + 回退分类器（exception 类 / SQL state） | ✓ `service/failure/FailureClassifier` + `FailureClassifierTest` 7/7 |
| 3 | Worker SDK 接 `TaskExecutionReportDto.failureClass`，BizException 携带 | ✓ `BizException.of(code, FailureClass, ...)` + Worker DTO + `TaskOutcomeCommand` 透传 + `finishTask` / `updateProgress` 写库 |
| 4 | retry policy 按 class 派发；`retry_policy_by_class` JSONB 解析 | ☐ **deferred follow-up** —— Stage 1 已落 schema 列，应用层未接；触发条件：失败重试想按 class 微调时再做（默认全局 retry_policy 仍工作） |
| 5 | metric / alert routing / Console 列展示 | ✓ `batch.job.failure{tenant,jobCode,class}` counter；`ConsoleMetaQueryService` 注册 `failureClass` + OpenAPI MetaEnums 同步 |

总 ~3.5-5 人天，本轮落 4/5（Stage 1/2/3/5），Stage 4 留给真正需要时再开工。

## 替代方案

| 方案 | 拒绝 |
|---|---|
| 用 `error_key` 字符串 grep 推断 class | 字符串没契约，重构 message 文案就崩；分类是行为决策，不该靠搜 |
| 在每个 worker 自己实现 retry 策略 | 散在 N 个 worker 一致性失控；orchestrator 失去统一治理 |
| 引入完整 OpenTelemetry semantic conventions error catalog | 过度工程，6 个 enum 已够覆盖；可后续扩展 |

## 不变量

1. `failure_class IS NOT NULL` 当且仅当 `instance_status` 为 `FAILED / PARTIAL_FAILED`；
2. `UNKNOWN` 永远是合法终态值，不是 bug — 它表示"分类器和 worker 都没把握"，是 ops 介入信号；
3. `retry_policy_by_class` 不能让某 class 的 retry 行为强于全局上限（防止配错刷死）；
4. classifier 永远只读，不写状态；状态由 orchestrator 终态推进路径写。

## 验收

- 单测：`FailureClassifierTest`（10 类典型 exception → 分类映射）
- IT：DB 闪断模拟 INFRASTRUCTURE 自动重试；数据校验失败模拟 DATA_QUALITY 不重试
- E2E：监控看板 4 类 failure 分布正确
- 守护：`FailureClassEnumRegistrationTest` 强制 enum 在 `ConsoleMetaQueryService.REGISTRATIONS` 登记

## 开放问题（已收敛）

| # | 问题 | 决策 |
|---|---|---|
| 1 | TIMEOUT 二次分类怎么做 | 不在 v1 实现自动二次分类。timeout 默认 UNKNOWN + 触发 ops review；后续可加 `TimeoutContextHint` 在 worker 侧上报"timeout 期间最后一次操作类型"用于二次分类 |
| 2 | 是否暴露 `errorClassHint` 给 console 用户手填 | **不做**。class 由代码 / 异常 / classifier 决定，用户填会污染审计；ops 真要重新分类走"reclassify approval"独立 API（v2） |
| 3 | 跨 ADR 一致性 | ADR-021 的 DQ check 失败必须返 DATA_QUALITY；ADR-018 跨日依赖等待映射到 UPSTREAM_DELAY 而非 FAILED |
| 4 | metric 基数 | failure_class 7 枚举 × tenant × jobCode 基数可控；不加 errorKey 维度（基数爆炸） |

### 不会做

- ❌ 不为每种异常类型建独立终态（`INFRA_FAILED / DATA_FAILED ...`）；保留 FAILED 单一终态 + class 列分维度，避免状态机膨胀
- ❌ 不让 worker 自定义新 FailureClass；枚举封闭，扩展走新 ADR
- ❌ v1 不做"按 class 分流到不同 worker pool"；与 ADR-027 资源亲和正交
