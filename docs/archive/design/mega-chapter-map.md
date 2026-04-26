# mega 设计文档章节 → 当前位置导航表

> 配套阅读：[`system-design-2026-03-21.md`](./system-design-2026-03-21.md)（**已变存根**：仅保留 20 章 + 95 子节标题供锚点兼容；原 8005 行正文需 `git show 5b72df9b:docs/archive/design/system-design-2026-03-21.md` 找回）。
> 本表把 20 章每章的内容指向**当前权威位置**，方便从 mega 章号反查。

## 三类状态

- ✅ **单文件替代**：mega 章已被 1 个独立文件完整接管
- 🟢 **新拆 (2026-04-26)**：本次拆分单独建文件
- 🟡 **散落覆盖**：mega 章内容拆到多个子文件 / ADR / runbook 中维护，没有单文件聚合

## 20 章导航

| mega 章 | 主题 | 状态 | 当前位置 |
|---|---|---|---|
| **ch.1** | 系统概述 | ✅ | [`../../architecture/architecture-truth.md`](../../architecture/architecture-truth.md) §"系统目标" + [`../../architecture/system-flow-overview.md`](../../architecture/system-flow-overview.md) §0 |
| **ch.2** | 系统总体架构设计 | ✅ | [`../../architecture/system-flow-overview.md`](../../architecture/system-flow-overview.md)（10+ Mermaid 图） |
| **ch.3** | 技术栈与设计原则 | 🟢 | [`../../design/tech-stack-and-principles.md`](../../design/tech-stack-and-principles.md) |
| **ch.4** | 核心模块与职责 | ✅ | [`../../architecture/architecture-truth.md`](../../architecture/architecture-truth.md) §模块职责 + CLAUDE.md §模块边界 |
| **ch.5** | 调度与编排总体设计 | 🟡 | 5.1 调度总览 → [`../../architecture/system-flow-overview.md`](../../architecture/system-flow-overview.md) §1<br>5.2 任务依赖 → [`../../architecture/workflow-dependency-guide.md`](../../architecture/workflow-dependency-guide.md)<br>5.3-5.4 编排引擎 → [`../../architecture/system-flow-overview.md`](../../architecture/system-flow-overview.md) §2 + [`../../architecture/quartz-replacement-design.md`](../../architecture/quartz-replacement-design.md) + [`../../runbook/wheel-scheduler-rollout.md`](../../runbook/wheel-scheduler-rollout.md) |
| **ch.6** | DAG 编排与可视化设计 | ✅ | [`../../architecture/workflow-dependency-guide.md`](../../architecture/workflow-dependency-guide.md) |
| **ch.7** | 执行与分片设计 | 🟡 | 7.1 Worker 执行 → [`../../architecture/worker-plugins.md`](../../architecture/worker-plugins.md)<br>7.2 分片设计 → [`../../architecture/core-model.md`](../../architecture/core-model.md) §分片<br>分区数解析链 → [`../../architecture/adr/ADR-005-partition-count-resolver-chain.md`](../../architecture/adr/ADR-005-partition-count-resolver-chain.md)<br>实现：`batch-orchestrator/.../application/plan/PartitionCountResolver*.java` |
| **ch.8** | 资源调度与运行控制设计 | 🟡 | 8.1 Worker 分组 / 优先级 → [`../../architecture/worker-plugins.md`](../../architecture/worker-plugins.md) §1<br>8.2 资源调度决策图 → **mega ch.8 是唯一可视化来源**，决策实现：`TokenBucketRateLimiter` / `TenantActionRateLimiter` / `application/quota/*`<br>开关运维 → [`../../runbook/feature-switches.md`](../../runbook/feature-switches.md) |
| **ch.9** | 文件处理链路设计 | ✅ | [`../../design/file-pipeline-design.md`](../../design/file-pipeline-design.md) |
| **ch.10** | 文件资产与治理设计 | 🟡 | 文件链路 → [`../../design/file-pipeline-design.md`](../../design/file-pipeline-design.md)<br>删除 / 归档 → [`../../design/delete-strategy.md`](../../design/delete-strategy.md)<br>对象桶 lifecycle → [`../../runbook/minio-lifecycle-policy.md`](../../runbook/minio-lifecycle-policy.md)<br>实现：`batch-orchestrator/.../infrastructure/file/FileGovernance*` |
| **ch.11** | 运行质量与 SLA 设计 | 🟢 | [`../../design/sla-and-quality.md`](../../design/sla-and-quality.md) |
| **ch.12** | 补偿、状态机与任务实例设计 | ✅ | [`../../architecture/core-model.md`](../../architecture/core-model.md) + [`../../architecture/adr/ADR-006-compensation-requires-new.md`](../../architecture/adr/ADR-006-compensation-requires-new.md) |
| **ch.13** | 事务、消息与参数化设计 | 🟡 | 13.1 事务（短事务+chunk）→ [`../../architecture/adr/ADR-006-compensation-requires-new.md`](../../architecture/adr/ADR-006-compensation-requires-new.md) + [`../../architecture/adr/ADR-003-launch-t1-t2-split.md`](../../architecture/adr/ADR-003-launch-t1-t2-split.md)<br>13.2 MQ 协议 → [`../../architecture/kafka-topic-plan.md`](../../architecture/kafka-topic-plan.md) + [`../../architecture/adr/ADR-002-transactional-outbox.md`](../../architecture/adr/ADR-002-transactional-outbox.md)<br>13.3 任务模板参数化 → 实现：`batch-orchestrator/.../service/LaunchParamResolver.java`（**无独立设计文件**）|
| **ch.14** | 数据模型与 PG 表结构设计 | ✅ | [`../../design/data-model-ddl.md`](../../design/data-model-ddl.md) |
| **ch.15** | 多租户与安全设计 | 🟢 | [`../../design/multi-tenant-and-security.md`](../../design/multi-tenant-and-security.md) |
| **ch.16** | 可观测性与运行手册设计 | ✅ | [`../../design/logging-architecture.md`](../../design/logging-architecture.md) + [`../../runbook/observability-stack.md`](../../runbook/observability-stack.md) |
| **ch.17** | 项目结构、模块划分与 POM 设计 | ✅ | [`../../design/project-structure-pom.md`](../../design/project-structure-pom.md) |
| **ch.18** | 生产可用性与部署设计 | ✅ | [`../../runbook/docker-deployment.md`](../../runbook/docker-deployment.md) + [`../../runbook/ha-elastic-scaling.md`](../../runbook/ha-elastic-scaling.md) + [`../../runbook/autoscaling-strategy.md`](../../runbook/autoscaling-strategy.md) + [`../../runbook/pg-table-partitioning.md`](../../runbook/pg-table-partitioning.md) + [`../../runbook/read-replica.md`](../../runbook/read-replica.md) + [`../../runbook/orchestrator-statefulset-migration.md`](../../runbook/orchestrator-statefulset-migration.md) + [`../../runbook/rolling-upgrade-workers.md`](../../runbook/rolling-upgrade-workers.md) + [`../../runbook/incident-response.md`](../../runbook/incident-response.md) |
| **ch.19** | 实施落地计划 | ✅ | [`../../analysis/hardening-backlog.md`](../../analysis/hardening-backlog.md)（v4，按 P0/P1/P2 排期，意图等同 mega 的 Phase 划分） |
| **ch.20** | 批量调度能力评估 | ✅ | [`../../design/capability-assessment.md`](../../design/capability-assessment.md) |

## 汇总

```
✅ 单文件替代       12 章
🟢 本次新拆          3 章 (3, 11, 15)
🟡 散落覆盖          5 章 (5, 7, 8, 10, 13)  ← 内容不丢，但需跨文件查
                    ────
                    20 章
```

## 🟡 章节剩余的"唯一来源"内容

5 个 🟡 章节中，**只有以下两点**确实只在 mega 文档里有原始设计描述、当前主干没有等价文字：

1. **ch.8 §8.2 资源调度决策图**（Mermaid 流程：Batch Window → 配额 → 资源池 → 优先级 → 投递）
   - 当前实现散在 `TokenBucketRateLimiter` / `application/quota/*` / `feature-switches.md`，没有单独可视化
   - 想补的话：将该 Mermaid 移到 `architecture/system-flow-overview.md` §1.9 或 `runbook/feature-switches.md` 顶部

2. **ch.13 §13.3 任务模板参数化设计原则**
   - 实现是 `batch-orchestrator/.../service/LaunchParamResolver.java`，但**为什么这样设计**没在文档里
   - 想补的话：写个 `design/parameter-templating.md` 或 `architecture/adr/ADR-009-launch-param-resolver.md`

其他 🟡 章节的"目标声明 / 设计原则"内容已经分散到子文件 + ADR + runbook 中重新表达，属于自然演化。
