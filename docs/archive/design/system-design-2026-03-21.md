# 批量调度系统设计说明书（完整版）— 章节索引存根

> ⚠️ **此文件不再含设计正文**。原 8005 行 mega 文档于 **2026-04-26** 拆分到 `docs/{design,architecture,runbook}/` 主干，此处保留**章节标题 + 子节锚点**仅为兼容历史链接（如 `§9.11` 等深链）。
>
> - **找具体内容**：参见 [`mega-chapter-map.md`](./mega-chapter-map.md) 章号 → 现位置导航表
> - **找原始正文**：`git show 5b72df9b:docs/archive/design/system-design-2026-03-21.md`（Stage E 改名后的最后完整版本）或更早的 `git log --follow` 历史
> - **拆分动机**：8000 行单文件难导航 / 协作冲突大 / 实质冻在 2026-04-10 没人维护；详见 [`../README.md`](../README.md) 归档原则

## 目录

20 章存根。每节 1-2 行 redirect。

---

## 1. 系统概述

→ [`../../architecture/architecture-truth.md`](../../architecture/architecture-truth.md) §"系统目标" + [`../../architecture/system-flow-overview.md`](../../architecture/system-flow-overview.md) §0

### 1.1 建设背景与目标

→ 同上

## 2. 系统总体架构设计

→ [`../../architecture/system-flow-overview.md`](../../architecture/system-flow-overview.md)（含 BFF / 观测栈 / Workflow DAG / DLQ 子图）

### 2.1 系统边界与控制面/数据面分层原则
### 2.2 架构总览
### 2.3 图形使用说明

→ 三个子节合并到 system-flow-overview.md §1-§2

## 3. 技术栈与设计原则

→ [`../../design/tech-stack-and-principles.md`](../../design/tech-stack-and-principles.md)（2026-04-26 新拆，按当前 pom.xml 校正）

### 3.1 技术栈
### 3.2 开源软件协议与合规要求
### 3.3 当前使用的开源组件与许可证清单
### 3.4 外部模型服务接入的附加合规要求
### 3.5 本系统自研代码许可证建议
### 3.6 持久层选型原则（Spring Data JDBC + MyBatis）

→ §3.1-§3.6 参见 tech-stack-and-principles.md §1-§5；§3.6 决策详见 [`../../architecture/adr/ADR-001-dual-orm.md`](../../architecture/adr/ADR-001-dual-orm.md)；许可证清单实时版 [`../../compliance/THIRD-PARTY-LICENSES.md`](../../compliance/THIRD-PARTY-LICENSES.md)

## 4. 核心模块与职责

→ [`../../architecture/architecture-truth.md`](../../architecture/architecture-truth.md) §模块职责 + CLAUDE.md §模块边界

### 4.1 模块总览
### 4.2 Orchestrator 内部设计

→ Orchestrator 内部设计：[`../../architecture/runtime-module-communication.md`](../../architecture/runtime-module-communication.md) + [`../../architecture/core-model.md`](../../architecture/core-model.md)

## 5. 调度与编排总体设计

→ 散落覆盖（详见 [`mega-chapter-map.md`](./mega-chapter-map.md) ch.5）

### 5.1 调度总览

→ [`../../architecture/system-flow-overview.md`](../../architecture/system-flow-overview.md) §1

### 5.2 任务依赖调度设计

→ [`../../architecture/workflow-dependency-guide.md`](../../architecture/workflow-dependency-guide.md)

### 5.3 编排引擎
### 5.4 核心编排能力

→ [`../../architecture/system-flow-overview.md`](../../architecture/system-flow-overview.md) §2 + [`../../architecture/core-model.md`](../../architecture/core-model.md)

### 5.5 业务日历、节假日与补跑日历

→ [`../../design/batch-day-design.md`](../../design/batch-day-design.md)

### 5.6 漏跑补跑与 Catch-up 策略

→ CLAUDE.md §领域字典 `catch_up_policy` + 实现：`batch-orchestrator/.../service/CatchUpService*.java`

## 6. DAG 编排与可视化设计

→ [`../../architecture/workflow-dependency-guide.md`](../../architecture/workflow-dependency-guide.md)

### 6.1 DAG 编排详细设计
### 6.2 DAG 可视化设计

→ workflow-dependency-guide.md §详细 + Console 侧 [`../../design/console-sidebar-menu-tree.md`](../../design/console-sidebar-menu-tree.md)

## 7. 执行与分片设计

→ 散落覆盖（详见 [`mega-chapter-map.md`](./mega-chapter-map.md) ch.7）

### 7.1 Worker 执行设计

→ [`../../architecture/worker-plugins.md`](../../architecture/worker-plugins.md)

### 7.2 分片设计
### 7.3 动态分片算法

→ [`../../architecture/core-model.md`](../../architecture/core-model.md) §分片 + [`../../architecture/adr/ADR-005-partition-count-resolver-chain.md`](../../architecture/adr/ADR-005-partition-count-resolver-chain.md) + 实现 `PartitionCountResolver*.java`

### 7.4 执行约束与编码规范

→ [`../../coding-conventions.md`](../../coding-conventions.md)

## 8. 资源调度与运行控制设计

→ 散落覆盖（详见 [`mega-chapter-map.md`](./mega-chapter-map.md) ch.8；§8.2 决策图仍是唯一可视化来源——见 git 历史）

### 8.1 资源隔离与优先级设计

→ [`../../architecture/worker-plugins.md`](../../architecture/worker-plugins.md) §1

### 8.2 资源调度设计

→ 实现：`TokenBucketRateLimiter` / `application/quota/*`；运维 [`../../runbook/feature-switches.md`](../../runbook/feature-switches.md)；可视化决策图：**git 历史唯一来源**

### 8.3 负载均衡设计

→ [`../../runbook/autoscaling-strategy.md`](../../runbook/autoscaling-strategy.md) + [`../../runbook/ha-elastic-scaling.md`](../../runbook/ha-elastic-scaling.md)

### 8.4 Batch Window 管理

→ [`../../design/batch-day-design.md`](../../design/batch-day-design.md)

## 9. 文件处理链路设计

→ [`../../design/file-pipeline-design.md`](../../design/file-pipeline-design.md)

### 9.1 设计目标与统一原则
### 9.2 文件处理统一模型
### 9.3 文件导入链路设计
### 9.4 文件导出链路设计
### 9.5 文件分发链路设计

→ §9.1-§9.5 参见 file-pipeline-design.md §核心链路

### 9.6 可配置扩展设计

→ [`../../architecture/worker-plugins.md`](../../architecture/worker-plugins.md)

### 9.7 链路执行引擎设计
### 9.8 Worker 映射与执行责任

→ [`../../architecture/worker-plugins.md`](../../architecture/worker-plugins.md) + [`../../runbook/worker-stage-coverage.md`](../../runbook/worker-stage-coverage.md)

### 9.9 幂等、重试与补偿要求

→ [`../../architecture/adr/ADR-006-compensation-requires-new.md`](../../architecture/adr/ADR-006-compensation-requires-new.md) + [`../../runbook/compensation-cleanup.md`](../../runbook/compensation-cleanup.md)

### 9.10 运行与治理要求

→ [`../../runbook/feature-switches.md`](../../runbook/feature-switches.md) + `FileGovernanceProperties`

### 9.11 Skip 策略与坏记录处理

→ [`../../design/file-pipeline-design.md`](../../design/file-pipeline-design.md) §错误处理 + 实现 `PreprocessStep` / `ValidateStep`；MinIO 落点 [`../../runbook/minio-lifecycle-policy.md`](../../runbook/minio-lifecycle-policy.md)

### 9.12 边查边写与禁止全量加载的硬约束

→ [`../../coding-conventions.md`](../../coding-conventions.md) + [`../../design/file-pipeline-design.md`](../../design/file-pipeline-design.md) §流式处理

## 10. 文件资产与治理设计

→ 散落覆盖（详见 [`mega-chapter-map.md`](./mega-chapter-map.md) ch.10）

### 10.1 统一文件资产管理模型

→ [`../../design/data-model-ddl.md`](../../design/data-model-ddl.md) `file_record` 表

### 10.2 文件状态机与半文件防护

→ [`../../design/file-pipeline-design.md`](../../design/file-pipeline-design.md)

### 10.3 文件治理闭环设计

→ [`../../design/delete-strategy.md`](../../design/delete-strategy.md) + [`../../runbook/minio-lifecycle-policy.md`](../../runbook/minio-lifecycle-policy.md) + `FileGovernanceProperties`

## 11. 运行质量与 SLA 设计

→ [`../../design/sla-and-quality.md`](../../design/sla-and-quality.md)（2026-04-26 新拆）

### 11.1 SLA 管理设计
### 11.2 数据质量控制设计
### 11.3 数据校验规则设计

→ §11.1-§11.3 参见 sla-and-quality.md §1-§4

## 12. 补偿、状态机与任务实例设计

→ [`../../architecture/core-model.md`](../../architecture/core-model.md) + [`../../architecture/adr/ADR-006-compensation-requires-new.md`](../../architecture/adr/ADR-006-compensation-requires-new.md)

### 12.1 数据补偿设计
### 12.2 状态机设计
### 12.3 任务实例中心
### 12.4 高级补偿与补跑设计
### 12.5 状态跃迁约束与并发保护

→ §12.1-§12.5 参见 core-model.md + ADR-006 + [`../../runbook/compensation-cleanup.md`](../../runbook/compensation-cleanup.md)

## 13. 事务、消息与参数化设计

→ 散落覆盖（详见 [`mega-chapter-map.md`](./mega-chapter-map.md) ch.13；§13.3 参数化原则仅在 git 历史 + LaunchParamResolver 实现）

### 13.1 事务设计

→ [`../../architecture/adr/ADR-006-compensation-requires-new.md`](../../architecture/adr/ADR-006-compensation-requires-new.md) + [`../../architecture/adr/ADR-003-launch-t1-t2-split.md`](../../architecture/adr/ADR-003-launch-t1-t2-split.md)

### 13.2 MQ 消息协议

→ [`../../architecture/kafka-topic-plan.md`](../../architecture/kafka-topic-plan.md) + [`../../architecture/adr/ADR-002-transactional-outbox.md`](../../architecture/adr/ADR-002-transactional-outbox.md)

### 13.3 任务模板与参数化设计

→ 实现：`batch-orchestrator/.../service/LaunchParamResolver.java`（**无独立设计文件**，原则仅 git 历史）

### 13.4 触发幂等与实例去重键

→ [`../../api/console-api-protocol.md`](../../api/console-api-protocol.md) §幂等 + 实现 `trigger_request_fire_dedup`

### 13.5 DB 与 MQ 一致性边界

→ [`../../architecture/adr/ADR-002-transactional-outbox.md`](../../architecture/adr/ADR-002-transactional-outbox.md)

### 13.6 核心流程实现约束

→ [`../../coding-conventions.md`](../../coding-conventions.md)

## 14. 数据模型与 PostgreSQL 表结构设计

→ [`../../design/data-model-ddl.md`](../../design/data-model-ddl.md)（持续维护，含 V1-V71 全 migration 对应表 DDL）

### 14.1 设计目标与落地原则
### 14.2 逻辑分层与核心表清单
### 14.3 DDL 使用说明
### 14.4 PostgreSQL 可执行 DDL 终版
### 14.5 关键落地说明
### 14.6 Flyway 写入数据库建议
### 14.7 术语统一与表结构交叉引用校验结论
### 14.8 本章结论

→ §14.1-§14.8 全部参见 data-model-ddl.md；权威 schema 看 `db/migration/V*.sql` 实际 Flyway 文件

## 15. 多租户与安全设计

→ [`../../design/multi-tenant-and-security.md`](../../design/multi-tenant-and-security.md)（2026-04-26 新拆）

### 15.1 多租户设计
### 15.2 系统安全设计
### 15.3 配置发布、灰度与回滚治理
### 15.4 密钥与凭证轮换机制

→ §15.1-§15.4 参见 multi-tenant-and-security.md §1-§12

## 16. 可观测性与运行手册设计

→ [`../../design/logging-architecture.md`](../../design/logging-architecture.md) + [`../../runbook/observability-stack.md`](../../runbook/observability-stack.md)

### 16.1 文件链路运行手册补充

→ [`../../runbook/worker-stage-coverage.md`](../../runbook/worker-stage-coverage.md)

### 16.2 平台监控设计

→ [`../../runbook/observability-stack.md`](../../runbook/observability-stack.md) §Prometheus

### 16.3 平台告警设计

→ [`../../runbook/observability-stack.md`](../../runbook/observability-stack.md) §告警 + [`../../runbook/incident-response.md`](../../runbook/incident-response.md)

### 16.4 运行手册与运维操作

→ [`../../runbook/`](../../runbook/README.md) 全套 22 文档

### 16.5 调度压测与容量基线指标

→ [`../../runbook/quartz-capacity-baseline.md`](../../runbook/quartz-capacity-baseline.md) + [`../../testing/load-test-report.md`](../../testing/load-test-report.md)

### 16.6 下游不可用时的熔断、限流与渠道健康

→ `DispatchChannelHealthProperties` + [`../../runbook/feature-switches.md`](../../runbook/feature-switches.md)

## 17. 项目结构、模块划分与 POM 设计

→ [`../../design/project-structure-pom.md`](../../design/project-structure-pom.md)

### 17.1 模块与工程结构总览
### 17.2 各模块职责与依赖边界
### 17.3 推荐目录结构

→ §17.1-§17.3 参见 project-structure-pom.md + CLAUDE.md §模块边界

## 18. 生产可用性与部署设计

→ [`../../runbook/`](../../runbook/README.md) §部署 + §容量 + §灰度

### 18.1 生产部署与高可用基线

→ [`../../runbook/docker-deployment.md`](../../runbook/docker-deployment.md) + [`../../runbook/ha-elastic-scaling.md`](../../runbook/ha-elastic-scaling.md)

### 18.2 Worker 缩容、下线与排空机制

→ [`../../runbook/rolling-upgrade-workers.md`](../../runbook/rolling-upgrade-workers.md)

### 18.3 归档、清理与冷热分层执行策略

→ [`../../design/delete-strategy.md`](../../design/delete-strategy.md) + [`../../runbook/pg-table-partitioning.md`](../../runbook/pg-table-partitioning.md)

### 18.4 压测口径与容量阈值

→ [`../../testing/load-test-report.md`](../../testing/load-test-report.md) + [`../../architecture/scalability-assessment.md`](../../architecture/scalability-assessment.md)

### 18.5 滚动升级、版本兼容与变更窗口

→ [`../../runbook/rolling-upgrade-workers.md`](../../runbook/rolling-upgrade-workers.md) + [`../../runbook/wheel-scheduler-rollout.md`](../../runbook/wheel-scheduler-rollout.md)

### 18.6 容灾目标与 RTO/RPO 设计口径

→ [`../../runbook/ha-elastic-scaling.md`](../../runbook/ha-elastic-scaling.md) + [`../../runbook/incident-response.md`](../../runbook/incident-response.md)

## 19. 实施落地计划

→ [`../../analysis/hardening-backlog.md`](../../analysis/hardening-backlog.md)（v4，按 P0/P1/P2 排期）

### 19.1 实施落地计划

→ 同上

## 20. 批量调度能力评估

→ [`../../design/capability-assessment.md`](../../design/capability-assessment.md) + [`../../architecture/scalability-assessment.md`](../../architecture/scalability-assessment.md)

### 20.1 当前评估口径说明
### 20.2 评估方法与分级口径
### 20.3 总体评估结论
### 20.4 多维度能力评估矩阵
### 20.5 关键维度详细评估
### 20.6 设计闭合度与实施范围评估
### 20.7 非功能指标与容量基线评估
### 20.8 实施难度、复杂度与成本评估
### 20.9 上线准备度评估
### 20.10 优化后的综合结论
### 20.11 实施交付件完成状态（更新于 2026-03-25）
### 20.12 综合评分与建设建议
### 20.13 最终结论

→ §20.1-§20.13 参见 capability-assessment.md（基线版）+ scalability-assessment.md（2026-04-25 千万级）

---

## 找原始正文

本存根**不含**原 8005 行设计正文。两个回溯路径：

```bash
# 1. 看拆分前最后完整版（Stage E 改名后）
git show 5b72df9b:docs/archive/design/system-design-2026-03-21.md | less

# 2. 跨版本 follow（中文文件名时期）
git log --follow --all docs/archive/design/system-design-2026-03-21.md
git show <commit_hash>:docs/design/批量调度系统设计说明.md
```

## 为什么改成存根

- mega 文档实质冻在 2026-04-10 没人维护
- 8000+ 行 IDE 卡 / git diff 难看 / 多人冲突
- 章节内容已散到 14 个 design/ 章节文件 + 17 个 architecture/ 文件 + 22 个 runbook/ 文件
- 留 anchor 锚点是为了**兼容历史链接**（如 `§9.11`），不是保留原文
- 此举不删文件，**只清空内容**——历史完全在 git 里

详细策略：[`mega-chapter-map.md`](./mega-chapter-map.md) + [`../README.md`](../README.md)
