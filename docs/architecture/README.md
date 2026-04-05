# 架构文档索引

这里收纳系统架构、设计分析、运行约束和 ADR 记录。

## 目录分工

- `architecture-truth.md`：当前真实架构基线和差距清单
- `core-model.md`：统一实例 / 状态 / 上下文 / 恢复模型的单一权威文档
- `naming-refactor-candidates.md`：最容易混淆的代码命名重构候选清单
- `implementation-status.md`：设计文档与代码落地状态
- `design-gap-audit.md`：设计与实现差距核查
- `design-patterns-evaluation.md`：设计模式使用情况评估
- `runtime-module-communication.md`：运行时模块通信拓扑
- `runtime-default-parameters.md`：运行默认参数基线
- `kafka-topic-plan.md`：Kafka Topic 设计规范
- `worker-plugins.md`：Worker 平台与插件扩展说明
- `scalability-ha-assessment.md`：可扩展性与高可用评估
- `scalability-fix-plan.md`：可扩展性与高可用改造计划
- `system-multidimensional-improvement-analysis.md`：系统多维度改进分析
- `console-sidebar-menu-tree.md`：控制台侧边栏菜单树与可见角色
- `adr/`：架构决策记录

## 推荐阅读顺序

1. 先看 [architecture-truth.md](./architecture-truth.md)
2. 再看 [core-model.md](./core-model.md)
3. 然后看 [naming-refactor-candidates.md](./naming-refactor-candidates.md)
4. 再看 [implementation-status.md](./implementation-status.md)
5. 再看 [design-gap-audit.md](./design-gap-audit.md)
6. 如需了解运行约束，继续看 [runtime-default-parameters.md](./runtime-default-parameters.md) 和 [runtime-module-communication.md](./runtime-module-communication.md)
7. 最后按需阅读 [adr/](./adr/)

## 相关入口

- [docs/testing/README.md](../testing/README.md)
- [docs/observability/README.md](../observability/README.md)
- [docs/sql/README.md](../sql/README.md)
