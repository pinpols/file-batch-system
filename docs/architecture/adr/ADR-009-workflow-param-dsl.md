# ADR-009 · Workflow 节点间参数串联 DSL

- **Status**: Proposed
- **Date**: 2026-04-26
- **Supersedes**: —
- **Related**: V5-P1-1（hardening-backlog）/ V5-P2-9（依赖本 ADR 落地）

## 背景

Workflow DAG 编排（详见 [`../workflow-dependency-guide.md`](../workflow-dependency-guide.md)）当前**支持节点串联但不支持参数自动流转**：

- ✅ START → SETTLE → DISPATCH 拓扑能正常推进
- ✅ workflow_node.node_params 能下发到该节点子作业（commit 3dbb6d22, 2026-04-24）
- ❌ **上游节点的输出（如 SETTLE 生成的 fileId）不会自动塞进下游节点的 partition payload**

实测痛点：`wf_eod_process` 跑通 START → SETTLE，但 DISPATCH 立即 `fileId missing`，因为没有机制把 SETTLE 的 file_record.id 暴露给 DISPATCH。

## 决策（提案，待落地）

引入 **JSONPath-like 参数引用 DSL**，让 workflow 配置时声明"下游 param ← 上游 output"映射，runtime 自动解析。

### DSL 语法（提案）

`workflow_node.node_params` 的 value 支持引用表达式：

```json
{
  "fileId": "$.nodes.SETTLE.output.fileId",
  "channelCode": "ftp_outbound",
  "_meta": {
    "expectedFileSizeBytes": "$.nodes.SETTLE.output.size"
  }
}
```

引用语法采用受限 JSONPath 子集：
- `$.nodes.<nodeCode>.output.<key>` — 引用同 workflow_run 内某节点 output 的某字段
- `$.workflowRun.<key>` — 引用 workflow_run 级共享字段（如 bizDate / batchNo）
- 不支持通配符 `*` / 过滤 `[?]` / 函数 `length()` 等高级特性
- 不支持表达式（`$ + 1` 之类），只支持纯路径引用

### 上游 output 捕获机制

**方案 A**（推荐）：worker 上报 task SUCCESS 时，在 `report_task_outcome` payload 加 `outputs: Map<String, Object>`。orchestrator 写入 `workflow_node_run.output`（新加 JSONB 列）。

**方案 B**（备选）：worker 仍只上报 SUCCESS，orchestrator 从 `file_record` 等表主动查 output。问题：需要节点类型感知（IMPORT 拿啥、EXPORT 拿啥），耦合高。

倾向 **A**：worker 自己知道生成了啥（fileId、recordCount、bizDate 等），主动上报最干净。

### 下游 param 解析时机

`DefaultSchedulePlanBuilder` 生成 partition payload 时，用 `WorkflowParamResolver` 把 node_params 里的 `$.xxx` 引用替换为实际值：

```java
// 伪代码
Map<String, Object> resolved = workflowParamResolver.resolve(
    workflowNode.getNodeParams(),                 // 含 $.nodes.SETTLE.output.fileId
    workflowRunContext);                           // 已加载所有上游节点的 output
partitionPayload.putAll(resolved);
```

### Fail-open 策略

DSL 解析失败时的处理：

| 场景 | 当前行为（无 DSL） | DSL 启用后 |
|---|---|---|
| 上游节点未跑（output 为 null） | DISPATCH 收到 `fileId=null` → 业务报 `fileId missing` | DSL resolver 返回 null，**保持现行为**（让业务逻辑负责挡） |
| 引用路径写错（如 `$.nodes.WRONG.output.fileId`） | N/A | **fail-fast**：节点拒绝启动，写 `WORKFLOW_PARAM_REF_INVALID` 到 dead_letter |
| 上游 output 没有引用的字段 | N/A | 返回 null，同未跑场景 |

## 影响面

| 层 | 改动 |
|---|---|
| **Schema** | `workflow_node_run` 加 `output JSONB` 列（新 Flyway migration） |
| **Worker** | `WorkerExecutionResult` 加 `outputs: Map<String, Object>`；各 stage 实现填充（IMPORT 填 `recordCount` / EXPORT 填 `fileId,size` / DISPATCH 填 `receiptCode`） |
| **Worker → Orchestrator** | `ReportTaskOutcomeCommand` 加 outputs 字段；HTTP / Kafka 协议同步 |
| **Orchestrator** | `DefaultTaskOutcomeService.persistOutcome` 写 `workflow_node_run.output`；新建 `WorkflowParamResolver`；`DefaultSchedulePlanBuilder` 集成 resolver |
| **测试** | `WorkflowParamResolverTest` 单测 + `WorkflowChainE2eIT` 端到端验证（含 SETTLE → DISPATCH 真正过 fileId） |
| **文档** | `workflow-dependency-guide.md` 加 §节点间参数串联 + DSL 语法 + 示例 |

## 实施分阶段

| Stage | 内容 | 估时 | 阻塞 V5-P2-9 |
|---|---|---|---|
| **Stage 1** | schema 改动 + worker 上报 outputs（向后兼容：旧 worker 无 outputs 字段时 orchestrator 容忍 null） | 1d | 否 |
| **Stage 2** | `WorkflowParamResolver` 实现 + 单测（不挂主链路） | 0.5d | 否 |
| **Stage 3** | 集成到 `DefaultSchedulePlanBuilder` + E2E 测试 + 文档 | 1d | 解锁 P2-9 |
| **Stage 4** | 给现有 wf_eod_process 等 7 个 workflow 配置 DSL（让 SETTLE→DISPATCH fileId 自动流转）| 0.5d | 业务层验证 |

合计 ~3 人天，应作为单独 sprint 推进。

## 替代方案（被拒绝）

### A. 让 worker 直接读上游 file_record

**否决理由**：worker 跨节点知道下游需求 = 强耦合。worker 只该上报"我做了啥"，由编排层决定"流向哪"。

### B. 用 Spring SpEL 全功能表达式

**否决理由**：SpEL 太强（支持任意方法调用 / 系统属性访问），等于把 RCE 路径敞开。受限 JSONPath 子集足够覆盖业务场景。

### C. 不做 DSL，要求用户在 console 手动填 fileId

**否决理由**：违背 workflow"自动编排"的初衷；用户每次跑都要现填，等同于把多 workflow 拆成单 job + 人工胶水。

## 不变量

- 旧 workflow 配置（不含 `$.xxx` 引用）行为完全不变 → 平滑升级
- worker 不感知 DSL 解析，只管上报 outputs → 解析责任全在 orchestrator
- DSL 解析在 partition 派发**之前**完成 → 解析失败立即 fail，不污染主链路

## 验收标准

- [ ] `workflow_node_run.output` migration 落地
- [ ] `WorkflowParamResolverTest` 单测覆盖 4 类引用 + 3 类 fail-fast 场景
- [ ] `wf_eod_process` 端到端：SETTLE 生成 fileId → DISPATCH 自动消费 → SUCCESS
- [ ] V5-P2-9 解锁：能跑通 PIPELINE / MIXED workflow + GATEWAY join_mode
- [ ] hardening-backlog v6 标 V5-P1-1 ✅
