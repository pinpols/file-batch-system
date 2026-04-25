# 架构文档索引

这里收纳系统架构、设计分析、运行约束和 ADR 记录。

## 目录分工

### 核心文档（持续维护）

- `architecture-truth.md`：当前真实架构基线和差距清单
- `core-model.md`：统一实例 / 状态 / 上下文 / 恢复模型的单一权威文档
- `implementation-status.md`：设计文档与代码落地状态
- `design-patterns-evaluation.md`：设计模式使用情况评估
- `engineering-backlog.md`：工程积压任务优先级排序
- `comprehensive-project-analysis.md`：系统安全与能力矩阵分析
- `system-multidimensional-improvement-analysis.md`：系统多维度改进分析
- `scalability-ha-assessment.md`：可扩展性与高可用评估
- `console-api-gap-analysis.md`：控制台 API 差距分析

### 参考文档

- `system-flow-overview.md`：**端到端业务流程总览（图文）— 第一次接触系统先看这份**
- `workflow-dependency-guide.md`：作业依赖与编排指南（DAG / GATEWAY / joinMode / CONDITION）
- `runtime-module-communication.md`：运行时模块通信拓扑
- `runtime-default-parameters.md`：运行默认参数基线
- `kafka-topic-plan.md`：Kafka Topic 设计规范
- `worker-plugins.md`：Worker 平台与插件扩展说明
- `console-sidebar-menu-tree.md`：控制台侧边栏菜单树与可见角色
- `local-env.md`：本地开发环境说明
- `adr/`：架构决策记录（不可变）

### 已完成归档（`completed/`）

- `design-gap-audit.md`：设计与实现差距核查（已被 implementation-status.md 取代）
- `infrastructure-performance-analysis.md`：基础设施性能分析（所有问题已修复）
- `scalability-fix-plan.md`：可扩展性改造计划（6/6 已完成）
- `long-parameter-methods-audit.md`：长参数方法治理清单（全部完成）
- `naming-refactor-candidates.md`：命名重构候选清单（已完成）

## 推荐阅读顺序

1. **第一次接触**先看 [system-flow-overview.md](./system-flow-overview.md)（端到端业务流程总览）
2. 再看 [architecture-truth.md](./architecture-truth.md)
3. 再看 [core-model.md](./core-model.md)
4. 然后看 [implementation-status.md](./implementation-status.md)
5. 如需了解运行约束，继续看 [runtime-default-parameters.md](./runtime-default-parameters.md) 和 [runtime-module-communication.md](./runtime-module-communication.md)
6. 最后按需阅读 [adr/](./adr/)

## 相关入口

- [docs/testing/README.md](../testing/README.md)
- [docs/observability/README.md](../observability/README.md)
- [docs/sql/README.md](../sql/README.md)
