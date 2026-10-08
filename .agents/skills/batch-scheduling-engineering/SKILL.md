---
name: batch-scheduling-engineering
description: 设计、开发、评估或审查 file-batch-system 的批量业务、分布式执行和调度主链时使用；固化业务日、状态推进、幂等、恢复与容量语义，并路由至专项技能。
---

# 批量分布式调度工程

## 适用范围

用于新增或修改批次/作业调度、Trigger、业务日历、任务编排、Worker 执行、文件到达与交付、重试/补跑/补偿、分布式协调、容量与恢复，以及这些能力的架构评估。普通单文件或独立 PR 审查不因此升级为全系统审计。

本技能是领域入口，不是新架构规范。先确认本轮需求和当前代码；仓库硬约束、有效 ADR、当前实现、测试和运行证据优先于本技能中的概括。来源索引见 [references/domain-sources.md](references/domain-sources.md)。

## 领域不变量

- 保持控制面职责清晰：Orchestrator 是作业运行状态主机，数据库是状态事实来源；Kafka 负责异步传递，不单独承担业务状态。
- 主执行链按当前契约核对 `DB -> Outbox -> Kafka -> CLAIM -> EXECUTE -> REPORT`。状态与 Outbox 的事务边界、租户身份、幂等键、CAS、终态保护和重放语义必须沿写路径验证。
- 把“计划触发时间”“事件发生时间”“租户业务日期”“批处理业务日”区分开。时区、日历、cutoff、DST、跨午夜、补跑日期和 catch-up 语义必须显式；不以 JVM 默认时区推断业务日。
- 分布式协调要考虑多实例并发、重复投递、超时重试、lease 过期、旧 leader 恢复、进程崩溃和依赖故障。只证明最终状态，不足以证明业务副作用恰好一次；检查外部副作用幂等和恢复路径。
- 资源控制要覆盖 claim 前准入、队列/线程/连接池/文件大小边界、租户公平性、Kafka 与 Outbox 积压、DB 锁等待和失败重试放大。不能把“增加 Worker”当成未测容量的结论。
- Worker、SDK、控制面和 Console 的职责及协议边界按当前实现确认；fixture/conformance、集成、真实 transport、SIM、严格真实数据和 staging 是不同证据层级。

## 设计与实现工作流

1. 确认用户场景、参与者、租户、调度触发条件、预期业务日、状态变化、外部副作用和失败后的恢复目标。
2. 阅读相关架构索引、ADR、状态模型、API/SDK 契约和 Runbook；追踪现有入口到持久化、消息、Worker 与最终业务结果，避免引入第二事实源或旁路写状态。
3. 写清正常路径与边界路径：并发/重复触发、窗口关闭、暂停恢复、部分失败、超时重试、重启、leader/lease 变化、下游不可用和人工重放。若语义尚未定义，先向用户指出决策点，不以实现细节替代产品语义。
4. 采用最小兼容改动，复用现有端口、状态模型、事务和配置；新增抽象必须解决明确的所有权或替换问题。涉及 schema、API、SDK、配置或运维行为时，纳入相应迁移、兼容、文档与回滚检查。
5. 先写能暴露关键不变量的定向测试，再按风险扩大验证。重点覆盖数据库并发与唯一约束、消息重复/乱序、跨时区日期边界、恢复后业务副作用和最终数据结果。
6. 记录构建 revision、环境、数据集、命令、验收条件和结果；分开报告静态/单测、集成、真实 transport、SIM/strict、负载与 staging 证据。不可访问的外部环境明确标为未验证。

## 评估与审查

按本次改动选择下列维度，逐项记录“有当前证据 / 不适用及原因 / 未验证”，不要机械地把每项都展开成全仓审计：

| 维度 | 核心问题 |
|---|---|
| 批次与业务日期 | 哪个租户日历/时区决定日期？跨午夜、DST、补跑和重试是否固定到原业务日？ |
| 调度语义 | fire、misfire、readiness defer、catch-up、pause/resume 是否会丢触发、重复建实例或复活终态？ |
| 状态与一致性 | 状态写、Outbox、去重键是否原子？所有写入者是否有 CAS/终态谓词？失败重试后是否有残留或重复副作用？ |
| 分布式并发 | 多 Trigger/Orchestrator/Worker 实例、重复消息、旧 lease/leader 和超时竞态是否有明确胜者及恢复路径？ |
| 执行和契约 | 五类 Worker 的执行前 claim、续租、心跳、取消、报告、优雅停机和 SDK 版本兼容是否覆盖？ |
| 故障与恢复 | Worker fleet crash、PG failover、Kafka/Redis/存储中断、DLQ/replay 后，任务和外部业务数据能否收敛？ |
| 容量与隔离 | admission 是否在 claim 前生效？热点锁、连接池、队列、backpressure、租户公平和重试风暴是否量化？ |
| 证据与运营 | 指标/trace/告警能否关联租户、业务日、instance、task、worker 与 offset？Runbook 是否能安全恢复而不直接改运行态表？ |

每项发现给出代码/配置位置、触发条件、状态或数据路径、业务影响、复现方法和修复/验收标准；分开标识确认缺陷、回归风险、纵深建议与未验证假设。无当前证据时，不将历史报告或“测试全绿”写成生产保证。

## 专项技能路由

- Trigger、日历、misfire、readiness 和 pause/resume：`scheduler-correctness`
- Worker、claim/report、lease、Outbox、Kafka 和恢复：`worker-pipeline-review`
- DB schema/迁移与查询性能：`database-migration-safety`、`sql-query-performance`
- 真实数据验收、SIM 和性能容量：`acceptance-validation`、`performance-validation`
- 跨信任边界和整系统攻击/故障审查：`adversarial-system-review`
- SDK 协议、配置/模块边界、前后端契约及交付：使用对应专项技能。

本领域工作结束时，同步受影响的测试、ADR/API/SDK 契约、Runbook、待办与索引；按用户授权决定是否提交或发布。
