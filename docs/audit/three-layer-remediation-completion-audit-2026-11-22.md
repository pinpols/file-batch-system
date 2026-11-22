# 三层整改完成度审计（2026-11-22）

本审计对 2026-11-22 的三层后端整改做完成度核对：性能与容量前置修正、五类 Worker 设计整改、后端架构审核整改，以及随后 PR4 的验收补强。结论只覆盖本地代码、自动化测试与可复现的 PostgreSQL 证据；目标环境、混版、真实外部接收端和同构建容量复测单独列出，不因代码完成而视为验收关闭。

## 结论摘要

| 层 | 代码完成度 | 本地验收 | 未关闭项 |
|---|---|---|---|
| 性能与容量（PR1） | 三项前置修正已落地 | 根请求统计、子 Job 绑定、图定义复用的定向单测与最小 PostgreSQL 样本通过 | 12/6 同构建压测 A/B、JDBC 往返实测；PR4 已补汇总 SQL 自动化用例 |
| Worker 设计（PR2） | 分页、空批水位已修；PR4 补“晚完成低 OUT”；PR6 补 Process HTTP E2E 与复合游标崩溃续跑对账 | 分页与水位真库用例通过；PR4 补乱序完成与跨归档最大值用例；PR6 两条 E2E 各 1/1 通过 | JOIN 展开重复行/页大小组合、登记中断窗口与第 3 批恢复合同的目标环境证据 |
| 架构整改（PR3） | 10 项整改已落地 | 导出、补槽、等待准入、人工恢复、两类 Outbox、SDK、取证 Helm 均有定向证据 | 双 Pod 取证下载、混版旧发布器退出顺序、Kafka ACK 与真实 DB 交错链路；PR4 已补准入上限 1 与 forwarder 交错用例 |

## 1. 性能与容量

代码提交 `d0e81965d`（PR1）。已完成：

- 根请求统计改按根请求关联 `workflow_run`，子 Job 与直接 Job 入口分行计数；根请求无运行/无完成样本输出 `INVALID`。
- 子 Job 启动返回后只核验节点绑定，已绑定同实例不再执行同值 UPDATE；保留补绑、冲突和租户校验。
- 根运行准入事务内复用同一份节点与边，校验、START 路由和冻结快照不再重复读取。

证据：[容量复核](backend-capacity-analysis-2026-11-22.md)记录定向单测（`ChildJobLaunchSupportTest`、`DefaultWorkflowRunLaunchServiceReplayTest`、`DefaultWorkflowDagServiceTest`、`WorkflowGraphValidatorTest`）和隔离 PostgreSQL 的绑定行版本、统计 SQL 对照。

PR4 补充：`EndToEndSummarySqlIntegrationTest` 在真实 PostgreSQL 上执行 `load-tests/sql/p2-run-end-to-end-summary.sql`，覆盖根 Workflow/子 Job/直接 Job 分行计数与 `INVALID` 状态。

仍未关闭：

- 12/6 同构建、同资源配置的容量 A/B 与 1,500 根请求复测。
- 图复用减少的 JDBC 往返耗时实测；`DefaultWorkflowRunLaunchServiceReplayTest` 已用 Mapper 调用次数确认节点和边各读一次，但不证明端到端延迟收益。
- 增量水位改为取最大有效 OUT 后，查询计划与索引成本需要在目标规模实测；V388 仍只服务有效成功行的范围裁剪。
- 汇总 SQL 在生产规模数据上的执行计划与耗时。

## 2. 五类 Worker

代码提交 `4a76109b9`（PR2）与 PR4 补充。已完成：

- Export 两种内置插件：单列 keyset 游标在页边界同值时明确失败，支持可选唯一排序后缀；分页与续跑使用同一元组。
- Process 增量水位：空批成功不遮住上一轮有效 OUT；热表与归档表均可追溯；排除重放、补跑、部分失败和 dry-run。
- PR4 将启动水位改为热/冷表各取数值最大的有效 OUT，较晚完成的较低 OUT 不再回退后续 IN；新增乱序完成与跨归档最大值真库用例。

证据：[Worker 设计复核](worker-design-review-2026-11-22.md)、[整改方案](../plans/worker-design-remediation-plan-2026-11-22.md)第 1、2 批实施记录，以及 `ExportKeysetRangeIntegrationTest`、`IncrementalWatermarkMapperIntegrationTest`。

仍未关闭：

- Process 插件级增量业务 SQL 已补（IN/OUT、空批）；完整 Worker 管道发布与回报的 HTTP E2E 已由 PR6（`ProcessPipelineE2eIT#httpLaunch_fullPipeline_publishesAndReportsOverHttp`）补齐。
- Export 复合游标崩溃续跑对账已由 PR6（`ExportKeysetRangeIntegrationTest#jdbcMapped_compositeCursor_resumesAfterCrashAndReconciles`）补齐；JOIN 展开重复行、页大小 1 以外边界组合与登记中断窗口仍开放。
- 第 3 批恢复合同：Import 分块提交与位点推进窗口、Export 制品摘要确认、Process 暂存代回收、Dispatch 渠道确认、Atomic 外部执行身份，仍需目标环境或外部系统证据。

## 3. 后端架构整改

代码提交 `af467622b`（PR3）。10 项整改及两处文档校准已完成，详见[整改方案实施状态](../plans/backend-architecture-remediation-2026-11-22.md#实施状态2026-11-22)。PR4 补充：

- 准入上限 1：满载时等待不领取，释放唯一槽后原请求启动。
- 派发 Outbox：Kafka ACK 与 DB 回写交错，旧发布者领取 1 的成功回写 0 行，新发布者领取 2 回写 1 行。

仍未关闭：

- 双 Pod 取证：A 生成、B 下载、重建 A 后再次下载，两次 SHA-256 与库中记录一致，跨租户下载被拒绝。
- 混版期间旧告警发布器全部退出后的完整保护确认。
- 真实 Kafka ACK 与 DB 回写交错链路（当前为 forwarder 单测 + Mapper 真库用例）。

## PR4 本地补强清单

| 项 | 用例/改动 |
|---|---|
| 晚完成较低 OUT 不回退 | `selectMaxEffectiveHighWaterMarkOut`；`lateLowerOut_doesNotRegressWatermark`、`takesMaxAcrossHotAndArchive` |
| 准入上限 1 | `capacityWait_shouldClaimWaitingRequestWhenOnlySlotIsReleased` |
| Kafka ACK/DB 交错 | `DefaultScheduleForwarderSetBasedTest#shouldRejectStalePublisher_afterEventReclaimed` |
| 汇总 SQL 自动化 | `EndToEndSummarySqlIntegrationTest` |

## PR4 验证记录（2026-11-22）

| 用例/命令 | 结果 |
|---|---|
| `IncrementalWatermarkMapperIntegrationTest` | 8/8 通过（含乱序完成、跨归档最大值） |
| `EndToEndSummarySqlIntegrationTest` | 2/2 通过（真实 PostgreSQL 执行汇总 SQL） |
| `JobNodeDispatchIntegrationTest#capacityWait_shouldClaimWaitingRequestWhenOnlySlotIsReleased` | 1/1 通过 |
| `DefaultScheduleForwarderSetBasedTest` | 7/7 通过（含 ACK/DB 交错） |
| `ExportKeysetRangeIT`（可变表 + 分区表） | 14/14 通过 |
| `SqlTransformComputePluginIntegrationTest`（增量业务 SQL） | 21/21 通过 |
| `spotless:check`、文档结构/引用/日期检查 | 通过 |

## 关闭判定

本地可验证的代码与自动化验收已在 PR4 收口；上表“仍未关闭”项均依赖目标环境、混版窗口、真实接收端或同构建容量复测，继续保留在 [TODO Master](../plans/todo-master.md)，不以本地单测替代。
