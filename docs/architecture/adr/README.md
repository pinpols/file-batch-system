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
| 012 | [ADR-012-failure-taxonomy.md](./ADR-012-failure-taxonomy.md)                             | 失败分类一等公民：FailureClass 7 enum + 按 class 派发 retry / escalate / RERUN（Accepted，**第 1 阶段必做 / P0**，不越界，~3-5 人天）       |
| 013 | [ADR-013-distributed-tracing.md](./ADR-013-distributed-tracing.md)                       | Micrometer Observation + OTel 桥接；`ObservedAspect`；种子 `@Observed`；业务 `trace_id` ↔ OTel traceId |
| 014 | [ADR-014-claim-idempotency.md](./ADR-014-claim-idempotency.md)                           | Worker CLAIM 幂等（invocation-id，**V95 已落地**）                                                    |
| 015 | [ADR-015-worker-side-outbox.md](./ADR-015-worker-side-outbox.md)                         | Worker REPORT outbox（PG/SQLite、SKIP LOCKED、熔断协同；Accepted）                                     |
| 016 | [ADR-016-batch-renew-lease-api.md](./ADR-016-batch-renew-lease-api.md)                   | Renew lease 批量 API 收敛 HTTP（Accepted，MVP）                                                      |
| 017 | [ADR-017-result-version-model.md](./ADR-017-result-version-model.md)                     | 结果版本（result_version）主模型：重跑产物多版本归属、EFFECTIVE 单版裁决、GC 策略（Accepted；Stage 1-5 已落 V108，Stage 6 console 待接入） |
| 018 | [ADR-018-cross-batch-day-dag-dependency.md](./ADR-018-cross-batch-day-dag-dependency.md) | 跨批量日 DAG 依赖（pipe 模型）：workflow_node 声明 cross_day_dependencies，复用 ADR-017 解版本路由（Accepted；Stage 2-4 已落 V109 + WAITING_DEPENDENCY + Resolver；Stage 5-7 排期中） |
| 019 | [ADR-019-cross-domain-rate-limit.md](./ADR-019-cross-domain-rate-limit.md)               | 跨业务域限流：business_domain 一等模型 + 域级 quota + 父子借调（Accepted；实施前置触发条件已明确，未触发不开工）                       |
| 020 | [ADR-020-batch-day-replay.md](./ADR-020-batch-day-replay.md)                             | 批量日维度重放：batch_day_replay_session 聚合 + scope/policy 分发 + 接审批（Accepted；Stage 2 schema V110 已落，依赖 ADR-017） |
| 021 | [ADR-021-data-quality-reconciliation.md](./ADR-021-data-quality-reconciliation.md)       | 数据对账闭环：`data_quality_rule` + `data_quality_check` + 4 类规则（行/表/跨表/跨日）+ 接 ADR-017 EFFECTIVE gate（Accepted，**第 2 阶段 / P0-P1 应做但收敛边界**：只做批量交付对账，不做数据治理平台）   |
| 022 | [ADR-022-forensic-audit-bundle.md](./ADR-022-forensic-audit-bundle.md)                   | Forensic 一键取证（Accepted；**v0.1 已落 2026-05-07**：V116 + 同步 bundle + SHA-256 attestation + Console / Orchestrator API，主链路无影响；v0.2 *_history + OSS 对象锁 + 7 年保留 gated） |
| 023 | [ADR-023-multi-calendar-coordination.md](./ADR-023-multi-calendar-coordination.md)       | 多日历联动 + 半天工作日：calendar_dependency + cutoff_schedule JSONB + calendar_group 共享假日 + disaster_day_override（Accepted，**第 1 阶段必做 / P0-P1**，调度核心能力）              |
| 024 | [ADR-024-archive-tiering.md](./ADR-024-archive-tiering.md)                               | 冷热数据分层 + 长保留：archive 表 PG 月分区 + DETACH 后写 OSS Parquet + DuckDB 冷查询（Accepted，**第 3 阶段 / P2 暂缓**，数据量阈值触发，绝不做完整数据湖）                  |
| 025 | [ADR-025-workflow-static-validator.md](./ADR-025-workflow-static-validator.md)           | Workflow 静态校验：enable 时跑 15 项 V1-V15 检查（拓扑 / DSL / 跨日依赖 / GATEWAY 一致性）（Accepted，**第 1 阶段必做 / P0**，便宜高收益，~5 人天，建议第一个落地）              |
| 026 | [ADR-026-dry-run-mode.md](./ADR-026-dry-run-mode.md)                                     | 演练 / Dry-run 模式：dry_run 一等字段贯穿全链 + DryRunGuard SDK + DRY_RUN result_version + SUCCESS_DRY_RUN 终态（Accepted，**第 2 阶段 / P1-P2 轻量版**：L1/L2/L3 配置/计划/Explain，FULL_SIMULATION 不做） |
| 027 | [ADR-027-resource-affinity.md](./ADR-027-resource-affinity.md)                           | 资源亲和性 / 地理调度：worker_label + worker_taint + job affinity_json（K8s 风格 required/preferred/anti）（Accepted，**第 3 阶段 / P2-P3 暂缓**，最高越界风险，绝不重做 K8s scheduler）   |
| 028 | [ADR-028-sensor-wait-node.md](./ADR-028-sensor-wait-node.md)                             | Sensor WAIT 节点：workflow_node_type=WAIT + 4 类 sensor（HTTP/FILE/KAFKA/TIME）+ 终态后下游派发 + V16 静态校验（Accepted） |
| 029 | [ADR-029-shared-config-defaults-module.md](./ADR-029-shared-config-defaults-module.md)   | 共享配置基线 `batch-defaults.yml` 位于 `batch-common/src/main/resources/`,由 `ConfigDriftGuardTest` 守护 classpath 存在性 + OWNED_KEYS(Revised:Accepted,2026-05-16;原独立模块方案被驳回为过度抽象) |
| 030 | [ADR-030-content-verifier-spi.md](./ADR-030-content-verifier-spi.md)                     | 产物内容验收 SPI：`ContentVerifier` + `ContentVerifierRegistry` + Micrometer Timer/Counter；首发实现 ExportFileNonEmptyVerifier；stage hot path 接入由后续 PR 按需做（Accepted）   |
| 034 | [ADR-034-cap-positioning.md](./ADR-034-cap-positioning.md)                               | CAP 定位:核心调度链路 = **CP**(任务 CLAIM / 状态机 / outbox / RBAC / 租户 / 审批必须强一致,牺牲可用),只读 / 观测层 = **AP**(Dashboard / trigger list 等走 `DownstreamFallback` 降级,允许 stale)。例外 + 落地机制 + 何时升级见 ADR(Accepted) |

### 优先级 + 范围边界纪律

> ADR-012 / 021..027 的"做不做 / 什么时候做 / 边界在哪"决策档：[`docs/analysis/adr-012-021-027-priority-scope-2026-05-06.md`](../../analysis/adr-012-021-027-priority-scope-2026-05-06.md)
>
> 系统定位一句话：**"批量运行控制面 + 文件 / 任务交付闭环"，不扩张为"企业数据治理 + 容器资源编排 + 合规审计平台"**。

| 阶段 | ADR | 越界风险 |
|---|---|---|
| **第 1 阶段必做（P0）** | 025 静态校验 / 012 失败分类 / 023 多日历 | 无 |
| **第 2 阶段应做但收敛** | 021 数据对账 / 026 dry-run / 022 Forensic | 中（021/026 高，022 低） |
| **第 3 阶段暂缓** | 024 冷热分层 / 027 资源亲和 | 027 最高 |


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
