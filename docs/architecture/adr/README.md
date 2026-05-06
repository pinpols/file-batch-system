# ADR 索引（架构决策记录）

不可变的"为什么这么做"。**新决策只追加，旧 ADR 不改**（出现新结论就写新 ADR 引用旧 ADR）。

## ADR 列表（编号即时间序）


| #   | 文件                                                                                       | 决策摘要                                                                                          |
| --- | ---------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| 001 | [ADR-001-dual-orm.md](./ADR-001-dual-orm.md)                                             | 持久层统一 MyBatis + `JdbcTemplate`；禁止 JPA / Spring Data JDBC                                      |
| 002 | [ADR-002-transactional-outbox.md](./ADR-002-transactional-outbox.md)                     | 使用事务性 Outbox 模式发布 Kafka，避免双写不一致                                                               |
| 003 | [ADR-003-launch-t1-t2-split.md](./ADR-003-launch-t1-t2-split.md)                         | `launch()` 拆 T1/T2 两事务 + CGLIB 自注入解决 `@Transactional` 自调用                                     |
| 004 | [ADR-004-worker-lifecycle-template.md](./ADR-004-worker-lifecycle-template.md)           | Worker 生命周期用模板方法模式，子类只填扩展点                                                                    |
| 005 | [ADR-005-partition-count-resolver-chain.md](./ADR-005-partition-count-resolver-chain.md) | 分区数解析用责任链（job override → tenant default → global default）                                     |
| 006 | [ADR-006-compensation-requires-new.md](./ADR-006-compensation-requires-new.md)           | 补偿 / 重试方法用 `REQUIRES_NEW`，避免外层事务 rollback 把补偿也回滚                                              |
| 007 | [ADR-007-dual-datasource.md](./ADR-007-dual-datasource.md)                               | 单 PG 实例双 schema 隔离 platform / business                                                        |
| 008 | [ADR-008-god-class-decomposition.md](./ADR-008-god-class-decomposition.md)               | God Class 分解为子服务 + Facade 模式（实例：`DefaultLaunchApplicationService`）                            |
| 009 | [ADR-009-workflow-param-dsl.md](./ADR-009-workflow-param-dsl.md)                         | Workflow 节点间参数串联 DSL（JSONPath-like `$.nodes.X.output.fileId`，分 4 stage 落地，~3 人天）              |
| 010 | [ADR-010-trigger-async-decoupling.md](./ADR-010-trigger-async-decoupling.md)             | Trigger → Orchestrator 异步解耦（trigger_outbox + Kafka，复用 ADR-002 模式，~7-8 人天分 7 stage）            |
| 011 | [ADR-011-idempotency-boundary-alignment.md](./ADR-011-idempotency-boundary-alignment.md) | Console / Trigger / Orchestrator 三层幂等责任边界对齐                                                   |
| 012 | [ADR-012-failure-taxonomy.md](./ADR-012-failure-taxonomy.md)                             | 失败分类一等公民：FailureClass 6 enum + 按 class 派发 retry / escalate / RERUN（Accepted，建议 P1，~3-5 人天）          |
| 013 | [ADR-013-distributed-tracing.md](./ADR-013-distributed-tracing.md)                       | Micrometer Observation + OTel 桥接；`ObservedAspect`；种子 `@Observed`；业务 `trace_id` ↔ OTel traceId |
| 014 | [ADR-014-claim-idempotency.md](./ADR-014-claim-idempotency.md)                           | Worker CLAIM 幂等（invocation-id，**V95 已落地**）                                                    |
| 015 | [ADR-015-worker-side-outbox.md](./ADR-015-worker-side-outbox.md)                         | Worker REPORT outbox（PG/SQLite、SKIP LOCKED、熔断协同；Accepted）                                     |
| 016 | [ADR-016-batch-renew-lease-api.md](./ADR-016-batch-renew-lease-api.md)                   | Renew lease 批量 API 收敛 HTTP（Accepted，MVP）                                                      |
| 017 | [ADR-017-result-version-model.md](./ADR-017-result-version-model.md)                     | 结果版本（result_version）主模型：重跑产物多版本归属、EFFECTIVE 单版裁决、GC 策略（Accepted；Stage 1-5 已落 V108，Stage 6 console 待接入） |
| 018 | [ADR-018-cross-batch-day-dag-dependency.md](./ADR-018-cross-batch-day-dag-dependency.md) | 跨批量日 DAG 依赖（pipe 模型）：workflow_node 声明 cross_day_dependencies，复用 ADR-017 解版本路由（Accepted；Stage 2-4 已落 V109 + WAITING_DEPENDENCY + Resolver；Stage 5-7 排期中） |
| 019 | [ADR-019-cross-domain-rate-limit.md](./ADR-019-cross-domain-rate-limit.md)               | 跨业务域限流：business_domain 一等模型 + 域级 quota + 父子借调（Accepted；实施前置触发条件已明确，未触发不开工）                       |
| 020 | [ADR-020-batch-day-replay.md](./ADR-020-batch-day-replay.md)                             | 批量日维度重放：batch_day_replay_session 聚合 + scope/policy 分发 + 接审批（Accepted；Stage 2 schema V110 已落，依赖 ADR-017） |
| 021 | [ADR-021-data-quality-reconciliation.md](./ADR-021-data-quality-reconciliation.md)       | 数据对账闭环：`data_quality_rule` + `data_quality_check` + 4 类规则（行/表/跨表/跨日）+ 接 ADR-017 EFFECTIVE gate（Accepted，金融场景必做）       |
| 022 | [ADR-022-forensic-audit-bundle.md](./ADR-022-forensic-audit-bundle.md)                   | Forensic 一键取证：配置类 *_history 影子表 + ForensicExportService + OSS 7 年保留 + 对象锁（Accepted，受监管必做）                          |
| 023 | [ADR-023-multi-calendar-coordination.md](./ADR-023-multi-calendar-coordination.md)       | 多日历联动 + 半天工作日：calendar_dependency + cutoff_schedule JSONB + calendar_group 共享假日 + disaster_day_override（Accepted，跨境必做）   |
| 024 | [ADR-024-archive-tiering.md](./ADR-024-archive-tiering.md)                               | 冷热数据分层 + 长保留：archive 表 PG 月分区 + DETACH 后写 OSS Parquet + DuckDB 冷查询（Accepted，数据量阈值触发）                                |
| 025 | [ADR-025-workflow-static-validator.md](./ADR-025-workflow-static-validator.md)           | Workflow 静态校验：enable 时跑 15 项 V1-V15 检查（拓扑 / DSL / 跨日依赖 / GATEWAY 一致性）（Accepted，建议 P2，~5 人天）                          |
| 026 | [ADR-026-dry-run-mode.md](./ADR-026-dry-run-mode.md)                                     | 演练 / Dry-run 模式：dry_run 一等字段贯穿全链 + DryRunGuard SDK + DRY_RUN result_version + SUCCESS_DRY_RUN 终态（Accepted，gated）           |
| 027 | [ADR-027-resource-affinity.md](./ADR-027-resource-affinity.md)                           | 资源亲和性 / 地理调度：worker_label + worker_taint + job affinity_json（K8s 风格 required/preferred/anti）（Accepted，多机房/异构必做）       |


## 写新 ADR 的姿势

1. **看上下文**先翻 `[../architecture-truth.md](../architecture-truth.md)` 和相关现有 ADR
2. **新决策**编号 +1，不改老 ADR；如果推翻老 ADR，在新 ADR 里 explicit "Supersedes ADR-NNN"
3. ADR 模板：背景 / 决策 / 理由 / 后果（含负面）/ 替代方案为什么不选

## 相关入口


| 主题     | 文档                                                                         |
| ------ | -------------------------------------------------------------------------- |
| 当前架构基线 | `[../architecture-truth.md](../architecture-truth.md)`                     |
| 系统总流程  | `[../system-flow-overview.md](../system-flow-overview.md)`                 |
| 模块通信拓扑 | `[../runtime-module-communication.md](../runtime-module-communication.md)` |
