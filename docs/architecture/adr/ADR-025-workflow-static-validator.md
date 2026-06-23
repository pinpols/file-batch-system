# ADR-025 · Workflow 静态校验

- **Status**: Accepted（**第 1 阶段必做 / P0**，~5 人天，便宜高收益，建议第一个落地）
- **Date**: 2026-05-06
- **Supersedes**: —
- **Related**: ADR-009（workflow DSL）/ ADR-018（跨日依赖）/ §14.3.2 / [ADR 012/021-027 优先级 + 范围边界](../../analysis/adr-012-021-027-priority-scope-2026-05-06.md)

## 范围边界（Scope Discipline）

> **批量系统核心能力，不越界。**配置越灵活，发布前校验越重要 —— 凌晨 2 点跑挂 vs 配置时拒绝，体验差距大；ROI 极高。

| ✅ 做 | ❌ 不做 |
|---|---|
| V1-V15 静态校验项（拓扑 / 不可达 / DSL nodeCode / 跨日依赖 offset & 90 天 / OPTIONAL 传染性退化 / GATEWAY 一致性 / param_schema 类型 / output contract） | 完整 type system（Dagster 风格） —— Java SPI 注解已够 |
| enable 切换时同步校验 + 错误清单一次性返回（不只一个错） | 隐式 type-coercion（int ↔ long） —— 不匹配就是错 |
| daily reconciler 重 evaluate 并发 alert | launch 时重跑 validator（性能不可接受，只在 enable） |
| 批量导入 all-or-nothing 校验 | ERROR 通过 `force=true` 强制 enable 后门（不留逃生路径） |
| reconciler 不修复，只告警 — 修复必经人工 | disable 路径校验（disable 是清理操作，不该 block） |

## 背景

当前 `WorkflowDagService` 在 enable 时检查环 + 拓扑，但还不够：

- **DSL 静态分析缺**：`$.nodes.X.output.fileId` 引用了不存在的 `X`，跑到第 5 个节点才 fail；
- **跨日依赖时间窗矛盾**：`bizDateOffset = +1` 引用未来日期 / range > 90 天 → 应该启用期拒绝；
- **GATEWAY join_mode 不一致**：节点声明 `ALL_OF` 但 incoming edges 数量为 1 → 显然写错；
- **类型不匹配**：node A output 是 `string`，下游节点 param 期望 `integer`；
- **OPTIONAL 配错传染性退化**：OPTIONAL 节点跳过依赖后输出仍被下一日依赖（已写入 ADR-018 §不会做，但需要 validator 实际检查）；
- **dead nodes**：从 START 不可达的节点（多见于复制粘贴遗漏边）。

业界 Airflow 启动期 import 整 DAG 树触发静态检查；Dagster 用 type system 强校验。

## 决策

`WorkflowGraphValidator` 在 `workflow_definition.enabled = true` 切换时同步运行；不通过 → 拒绝启用并返回详细错误清单（不只一个错），让用户一次改完。

### 校验项清单

| ID | 校验 | 严重度 |
|---|---|---|
| V1 | 拓扑成环 / 自环 | ERROR |
| V2 | 不可达节点（从 START 走不到） | ERROR |
| V3 | 不可终止节点（走不到 END） | ERROR |
| V4 | DSL 引用 nodeCode 不存在 | ERROR |
| V5 | DSL 引用 output key 在上游 outputs 契约表里没声明 | WARN（暂为 WARN，下游 worker 自己 fail 回退） |
| V6 | 跨日依赖 offset 解析失败（业务日历不识别） | ERROR |
| V7 | 跨日依赖 range 跨度 > 90 天（接 ADR-018 上限） | ERROR |
| V8 | 跨日依赖 OPTIONAL 节点的 output 被下一日 REQUIRED 引用（传染性退化） | ERROR |
| V9 | GATEWAY join_mode = ALL_OF 但 incoming edges < 2 | ERROR |
| V10 | GATEWAY join_mode = N_OF 但 N > M 或 M ≠ incoming count | ERROR |
| V11 | edge type 与节点 type 矛盾（如 START 节点有 incoming） | ERROR |
| V12 | param_schema 类型与上游 outputs 契约类型不匹配 | WARN（contract 不全的 worker 仍能跑） |
| V13 | 同 (workflow_code, node_code) 重复 | ERROR |
| V14 | edge 引用的 node_code 不存在 | ERROR |
| V15 | workflow timezone / calendar 与所引用 calendar 一致性 | WARN |

ERROR ⇒ 拒绝启用；WARN ⇒ 启用但展示警告。

### 触发时机

```
console: PUT /workflow-definitions/{id}/enable
    │
    ▼
WorkflowGraphValidator.validate(workflowDefinition)
    │
    ├── ERRORS empty?  yes → enabled=true
    │                 no  → BizException(error.workflow.validation_failed) + 错误列表
    │
WorkflowDefinitionImport（批量配置导入）
    │
    └── 同样跑 validator，所有 workflow 校验后再批量提交（all-or-nothing）
```

定期对账：

`WorkflowValidatorReconciler`（daily）扫描 `enabled=true` 的 workflow 重新跑 validator —— 可能因为引用的 calendar / job 被 disable / 删除导致原本 valid 的 workflow 现在 invalid，写 alert event 让 ops 知道。

### Output Contract（V5/V12 依赖）

可选：worker step plugin 声明 outputs schema：

```java
@WorkerOutputs({
  @Output(key = "fileId", type = LONG),
  @Output(key = "recordCount", type = INTEGER)
})
public class ImportLoadStep implements StepPlugin { ... }
```

启动期收集到 `step_registry`（已存在），validator 读这张表做 V5/V12 检查。
没声明的 step → V5/V12 跳过（向后兼容）。

## 影响面

| 维度 | 影响 |
|---|---|
| 持久层 | 零（V5/V12 复用已有 step_registry） |
| 模块 | `WorkflowGraphValidator` 主类 + 错误清单 DTO；console-api enable 路径调用；`WorkflowValidatorReconciler` 定期对账 |
| 兼容 | 老 workflow 不主动重启（reconciler 标 WARN 不阻塞）；新 enable / 编辑保存时强制校验 |

## 实施分阶段

| Stage | 范围 | 估算 |
|---|---|---|
| 1 | V1-V4 + V13-V14（拓扑 + DSL 基础） | 1 天 |
| 2 | V6-V8（跨日依赖检查，接 ADR-018） | 1 天 |
| 3 | V9-V11（GATEWAY 一致性） | 0.5 天 |
| 4 | V5/V12（output contract，需 worker SPI 配合） | 1.5 天 |
| 5 | reconciler + alert event | 1 天 |
| 6 | Console error UI（错误清单展示，非后端） | UI 团队 |

总 ~5 人天。

## 替代方案

| 方案 | 拒绝 |
|---|---|
| 让 worker 跑到再 fail | 用户体验差（凌晨 2 点跑挂 vs 配置时拒绝） |
| 只做 V1-V4，其余靠 worker 报错 | 低收益高便宜的检查不做没理由；DSL 错最常见 |
| 引入完整 type system（如 Dagster 风格） | 过度工程；Java SPI 注解已够用 |

## 不变量

1. validator 只读，永不改业务状态；
2. ERROR 必须阻断 enable，永不"warn 后允许"；
3. 同一 workflow 短时间内多次 enable/disable 不会因校验耗时累计成长事务；
4. reconciler 不修复，只告警 — 修复必经人工。

## 验收

- 单测：每个 V1-V15 一个典型 case
- IT：批量导入 100 个 workflow，1 个错全部拒绝（all-or-nothing）
- E2E：跨日依赖配错 → enable 时拒绝 + 错误清单可见

## 开放问题（已收敛）

| # | 问题 | 决策 |
|---|---|---|
| 1 | 是否做 type-coercion 智能匹配（int ↔ long） | **不做**。type 不匹配就是错；coerce 隐式转换会埋问题 |
| 2 | reconciler 频率 | daily（半夜跑）；高频意义不大，节假日突发也轮不到 reconciler 救 |
| 3 | output contract 是否强制 | **不强制**。新写的 step 加，老的不强求；validator 跳过未声明 step 的 V5/V12 |
| 4 | console UI 一次显示多少错 | 全部展示（无截断）；100+ 错时让用户头疼但不假装"只有 1 个错" |

### 不会做

- ❌ 不在 disable 路径校验（disable 是清理操作，不该 block）
- ❌ 不在每次 launch 时重跑 validator（性能不可接受）；只在 enable / reconciler
- ❌ 不允许 ERROR 通过 "force=true" 强行 enable
- ❌ 不引入外部 schema validation（OpenAPI / JSON Schema），用 Java SPI 注解就够
