# ADR 索引（架构决策记录）

记录决策作出的原因和约束。**决策结论只追加，不回写改写**；实现状态可以在 ADR 的状态记录或本索引中更新。索引中的实施状态按当前仓库代码核对，不能代替 Full Gate、集成环境或生产验收证据。历史排期和人日估算不代表当前待办。

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
| 012 | [ADR-012-failure-taxonomy.md](./ADR-012-failure-taxonomy.md)                             | 失败分类：FailureClass、失败分类器及实例/任务持久化已存在；按 class 派发 retry policy 仍按 ADR 正文标记为 deferred，不再列为当前 P0 待办 |
| 013 | [ADR-013-distributed-tracing.md](./ADR-013-distributed-tracing.md)                       | Micrometer Observation + OTel 桥接；`ObservedAspect`；种子 `@Observed`；业务 `trace_id` ↔ OTel traceId |
| 014 | [ADR-014-claim-idempotency.md](./ADR-014-claim-idempotency.md)                           | Worker CLAIM 幂等（invocation-id，**V95 已落地**）                                                    |
| 015 | [ADR-015-worker-side-outbox.md](./ADR-015-worker-side-outbox.md)                         | Worker REPORT outbox（PG/SQLite、SKIP LOCKED、熔断协同；Accepted）                                     |
| 016 | [ADR-016-batch-renew-lease-api.md](./ADR-016-batch-renew-lease-api.md)                   | Renew lease 批量 API 收敛 HTTP（Accepted，MVP）                                                      |
| 017 | [ADR-017-result-version-model.md](./ADR-017-result-version-model.md)                     | 结果版本（result_version）主模型：重跑产物多版本归属、EFFECTIVE 单版裁决、GC 策略（Accepted；Stage 1-5 已落 V108，Stage 6 console 待接入） |
| 018 | [ADR-018-cross-batch-day-dag-dependency.md](./ADR-018-cross-batch-day-dag-dependency.md) | 跨批量日 DAG 依赖；按 ADR 当前记录 Stage 1-5、7 已落，Stage 6 系统级 E2E 证据待补；历史阶段编号不代表新的实施待办 |
| 019 | [ADR-019-cross-domain-rate-limit.md](./ADR-019-cross-domain-rate-limit.md)               | 跨业务域限流：business_domain 一等模型 + 域级 quota + 父子借调（Accepted；实施前置触发条件已明确，未触发不开工）                       |
| 020 | [ADR-020-batch-day-replay.md](./ADR-020-batch-day-replay.md)                             | 批量日维度重放：batch_day_replay_session 聚合 + scope/policy 分发 + 接审批（Accepted；Stage 2 schema V110 已落，依赖 ADR-017） |
| 021 | [ADR-021-data-quality-reconciliation.md](./ADR-021-data-quality-reconciliation.md)       | 数据对账闭环：`data_quality_rule` + `data_quality_check` + 4 类规则（行/表/跨表/跨日）+ 接 ADR-017 EFFECTIVE gate（Accepted，**第 2 阶段 / P0-P1 应做但收敛边界**：只做批量交付对账，不做数据治理平台）   |
| 022 | [ADR-022-forensic-audit-bundle.md](./ADR-022-forensic-audit-bundle.md)                   | Forensic 一键取证（Accepted；**v0.1 已落 2026-05-07**：V116 + 同步 bundle + SHA-256 attestation + Console / Orchestrator API，主链路无影响；v0.2 *_history + OSS 对象锁 + 7 年保留 gated） |
| 023 | [ADR-023-multi-calendar-coordination.md](./ADR-023-multi-calendar-coordination.md)       | 多日历联动设计；实现与运行验收状态以当前代码、专项验证报告及本 ADR 的实施记录为准，历史“必做/P0-P1”排期不代表仍未实施 |
| 024 | [ADR-024-archive-tiering.md](./ADR-024-archive-tiering.md)                               | 冷热数据分层 + 长保留：archive 表 PG 月分区 + DETACH 后写 OSS Parquet + DuckDB 冷查询（Accepted，**第 3 阶段 / P2 暂缓**，数据量阈值触发，绝不做完整数据湖）                  |
| 025 | [ADR-025-workflow-static-validator.md](./ADR-025-workflow-static-validator.md)           | Workflow 静态校验；`WorkflowGraphValidator` 与 reconciler 已存在，具体规则覆盖和运行验收以当前实现/测试为准；历史 P0 排期不再作为当前待办 |
| 026 | [ADR-026-dry-run-mode.md](./ADR-026-dry-run-mode.md)                                     | 演练 / Dry-run 模式：dry_run 一等字段贯穿全链 + DryRunGuard SDK + DRY_RUN result_version + SUCCESS_DRY_RUN 终态（Accepted，**第 2 阶段 / P1-P2 轻量版**：L1/L2/L3 配置/计划/Explain，FULL_SIMULATION 不做） |
| 027 | [ADR-027-resource-affinity.md](./ADR-027-resource-affinity.md)                           | 资源亲和性 / 地理调度：worker_label + worker_taint + job affinity_json（K8s 风格 required/preferred/anti）（Accepted，**第 3 阶段 / P2-P3 暂缓**，最高越界风险，绝不重做 K8s scheduler）   |
| 028 | [ADR-028-sensor-wait-node.md](./ADR-028-sensor-wait-node.md)                             | Sensor WAIT 节点：提案状态（Proposed）；组件与 fixture 存在不代表已批准为生产能力 |
| 029 | [ADR-029-shared-config-defaults-module.md](./ADR-029-shared-config-defaults-module.md)   | 共享配置基线 `batch-defaults.yml` 位于 `batch-common/src/main/resources/`,由 `ConfigDriftGuardTest` 守护 classpath 存在性 + OWNED_KEYS(Revised:Accepted,2026-05-16;原独立模块方案被驳回为过度抽象) |
| 029 | [ADR-029-dedicated-spi-worker.md](./ADR-029-dedicated-spi-worker.md)                      | 专用 SPI worker(atomic 隔离执行)。注:ADR-029 编号有两篇(共享配置模块 + 专用 SPI worker),历史原因占用同一编号,文件名不重命名以免断链 |
| 030 | [ADR-030-content-verifier-spi.md](./ADR-030-content-verifier-spi.md)                     | 产物验收 SPI；Worker/Orchestrator 接线范围以当前实现和测试为准，不能笼统描述为仅有 SPI 或尚未接入 |
| 031 | [ADR-031-dual-track-pagination.md](./ADR-031-dual-track-pagination.md)                   | 双轨分页：列表用 offset 分页(可跳页),大数据导出 / 深翻用 cursor(keyset)分页,二者并存按场景选（Accepted） |
| 032 | [ADR-032-four-role-rbac-redesign.md](./ADR-032-four-role-rbac-redesign.md)               | 控制台 4 角色 RBAC 重设计:平台 vs 租户 × 写 vs 只读 二维矩阵(`ROLE_ADMIN`/`ROLE_AUDITOR`/`ROLE_TENANT_ADMIN`/`ROLE_TENANT_USER`)；旧角色仅在迁移中转换，运行时不兼容（Accepted） |
| 033 | [ADR-033-quartz-to-wheel-scheduler.md](./ADR-033-quartz-to-wheel-scheduler.md)           | Quartz 替换为时间轮方案（Superseded，运行路径已移除） |
| 034 | [ADR-034-cap-positioning.md](./ADR-034-cap-positioning.md)                               | CAP 定位:核心调度链路 = **CP**(任务 CLAIM / 状态机 / outbox / RBAC / 租户 / 审批必须强一致,牺牲可用),只读 / 观测层 = **AP**(Dashboard / trigger list 等走 `DownstreamFallback` 降级,允许 stale)。例外 + 落地机制 + 何时升级见 ADR(Accepted) |
| 035 | [ADR-035-tenant-self-hosted-worker-sdk.md](./ADR-035-tenant-self-hosted-worker-sdk.md)   | 租户自托管 Worker SDK 决策；SDK 已在 `sdk/` 多语言目录提供，旧实施表中的待合并/进行中状态已过期，当前发布与生产验收见 SDK 文档及 CI |
| 036 | [ADR-036-sdk-task-handler-templates.md](./ADR-036-sdk-task-handler-templates.md)         | SDK 任务处理器模板决策；以各语言 SDK 当前 API/测试为实现依据，ADR 中历史 PR 状态不作为当前进度 |
| 037 | [ADR-037-sdk-checkpoint-resume-and-reliable-commit.md](./ADR-037-sdk-checkpoint-resume-and-reliable-commit.md) | SDK 断点续跑与可靠提交决策；各语言实际支持范围以 SDK conformance 与当前 API 文档为准，不将提案阶段计划视为已交付 |
| 038 | [ADR-038-platform-worker-checkpoint-resume.md](./ADR-038-platform-worker-checkpoint-resume.md) | 平台 Worker 分片级续跑；ADR 已勘误早期“chunk 业务写与位点同事务”表述，当前一致性边界以 ADR 勘误和实现为准 |
| 039 | [ADR-039-credential-env-reference.md](./ADR-039-credential-env-reference.md)             | 凭据 env 引用决策；已实现范围与剩余 vault/schema/Console 支持按当前代码逐项区分，不能将 env 引用描述为已彻底消除明文凭据风险 |
| 040 | [ADR-040-batch-manifest-driven-arrival-group.md](./ADR-040-batch-manifest-driven-arrival-group.md) | Manifest 到达组决策；代码已有到达组扫描/治理路径，协议支持边界及真实链路验收以实施记录和专项验证为准 |
| 041 | [ADR-041-control-total-continuity-gate.md](./ADR-041-control-total-continuity-gate.md)   | 控制总额贯穿闸；Phase 1.1-1.5 的实现范围与 feature flag 默认值以 ADR 和配置事实源核实，不将阶段落地泛化为所有环境启用 |
| 042 | [ADR-042-admission-bounded-queue-backpressure.md](./ADR-042-admission-bounded-queue-backpressure.md) | 超容准入 DEFER；只有 WAITING/defer 与重评策略，尚无统一 pending 上限、等待 TTL 或超限终态，不能称为有界队列 |
| 043 | [ADR-043-dependency-aware-fire.md](./ADR-043-dependency-aware-fire.md) | 依赖感知触发决策；当前 readiness/defer 实现与端到端验收状态以代码及调度验证报告为准，不以 Proposed 标签推断未实现 |
| 044 | [ADR-044-instance-pause-resume-batch-day-serial.md](./ADR-044-instance-pause-resume-batch-day-serial.md) | 实例/工作流 pause-resume 决策；`PAUSED` 状态与相关 API/派发约束已存在，完整业务场景和并发验收需以专项测试报告为准 |
| 045 | [ADR-045-console-ai-ops-assistant.md](./ADR-045-console-ai-ops-assistant.md) | 控制台 AI 运维助手定位:只读问答(指路 + 取数)、默认关、不裁定业务对错/不写状态/不进主链、可无损下沉移除（Accepted） |
| 046 | [ADR-046-file-bundle-aggregation.md](./ADR-046-file-bundle-aggregation.md) | 文件束聚合；基础迁移、编排入口和集成测试已存在，完整 profile 覆盖及性能结论仍按专项测试/压测分别确认 |

### 历史优先级与范围边界

> ADR-012 / 021..027 的"做不做 / 什么时候做 / 边界在哪"决策档已归档：[`docs/archive/analysis/adr-012-021-027-priority-scope-2026-05-06.md`](../../archive/analysis/adr-012-021-027-priority-scope-2026-05-06.md)
>
> 系统定位一句话：**"批量运行控制面 + 文件 / 任务交付闭环"，不扩张为"企业数据治理 + 容器资源编排 + 合规审计平台"**。

| 阶段 | ADR | 越界风险 |
|---|---|---|
| **历史第 1 阶段优先级** | 025 静态校验 / 012 失败分类 / 023 多日历 | 这是 2026-05 的规划记录，不是当前待办清单 |
| **历史第 2 阶段范围** | 021 数据对账 / 026 dry-run / 022 Forensic | 实施状态应分别查看 ADR 的最新实施记录 |
| **历史第 3 阶段范围** | 024 冷热分层 / 027 资源亲和 | 保留原范围边界；是否触发仍按各 ADR 条件判断 |


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
