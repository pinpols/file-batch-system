# 对标业界开源批量调度的能力差距分析

> 日期:2026-05-30(2026-06-29 复核更新)。对标对象:Apache DolphinScheduler / Apache Airflow / XXL-Job / Temporal / Argo Workflows / Kestra。
> 方法:实际 grep + 读代码核实,非凭印象。每条给证据文件。
> 后续实施边界与验证计划见 [roadmap](../plans/bfs-open-source-scheduler-boundary-roadmap-2026-06-29.md)。
> **2026-06-29 复核**:原 §三 6 条短板中 #1 日志查看 / #2 Lineage / #4 DAG 编辑 / #6 多语言 SDK 已落地,移入 §二;仅 #3 YAML 与 #5 动态 fan-out 仍缺。

## 一、定位

file-batch-system = **批量运行控制面 + 文件/任务交付闭环**(见 CLAUDE.md / ADR)。
不是通用 workflow 引擎,不扩张为数据治理 / K8s 编排 / 合规审计平台。
评估差距时,凡落在「明确范围边界外」的缺失**不算缺陷**(见 §四)。

## 二、已具备(核实通过,别丢)

| 能力 | 证据 | 完成度 |
|---|---|---|
| Backfill 历史回填 | `BatchDayReplayController` / `BatchDayReplayService`(ADR-020) | ✅ 按 bizDate 选候选 + 分批 + 审批/自动双模 + 复用结果版本 |
| Catchup 追赶 | `LaunchBatchDayService.catchUpLaunch` / `BatchDaySettleScheduler.catchUpPolicy` | ✅ misfire 补偿 + catchupCount 指标 + 延迟容忍窗 |
| 事件驱动触发(4 sensor) | `FileArrivalSensorPolicy` / `HttpPollSensorPolicy` / `KafkaOffsetSensorPolicy` / `DbRowExistsSensorPolicy` + `SensorPollScheduler` | ✅ FILE_ARRIVAL / HTTP_POLL / DB_ROW_EXISTS / KAFKA_OFFSET 四类 |
| 跨任务数据传递(XCom 类) | `WorkflowRunContext` / `WorkflowParamResolver` / `nodeOutput()` | ✅ 一等公民,`$.nodes.<code>.output.<key>` 引用 |
| OpenTelemetry 标准 trace | `OtelTraceContext`(用 `io.opentelemetry.api`) | ✅ 非自研 traceId,接 Tempo/Jaeger,与业务表 trace_id 同步 |
| 数据质量校验 | `DataQualityCheckExecutor` / `ImportDataQualityService` / `data_quality_check` 表 | ✅ 行级 / 跨表 / 跨日期三层 |
| 审批节点 | `ApprovalWorkflowService` / `ApprovalController` | ✅ submit/approve/reject/markExecuted |
| dry-run | `DryRunGuard`(贯穿 import/export/process) | ✅ 跳插件 + 染色审计 |
| forensic 取证 | `ForensicExportService` | ✅ 按 bizDate 圈取证包 |
| Outbox + 强一致状态机 | 主链 DB→Outbox→Kafka→CLAIM→EXECUTE→REPORT | ✅ 竞品常见的"task 状态丢失"你天然没有 |
| 多租 UNIQUE 守护 + ArchUnit | `MultiTenantIsolationIntegrationTest` / MapperXmlTenantGuardArchTest | ✅ 竞品几乎没有这层静态守护 |
| archive 冷表对齐 + drift check | `ArchiveSchemaDriftCheck` 启动期 fail-fast | ✅ 竞品归档多为手工 |
| 任务级在线日志查看(2026-06 补) | 后端 `ConsoleQueryController` `/execution-logs` + `/job-execution-logs`;前端 `ExecutionLog.vue` / `ExecutionLogsTab.vue` / `MExecutionLog.vue` | ✅ 原 §三 Gap#1 已落地,运维无需 SSH 翻文件 |
| 数据血缘 Lineage(2026-06 补) | `OpenLineageEmitter` + `OpenLineageProperties`,接入 `WorkflowTerminalOutboxService` 终态发射 | ✅ 原 §三 Gap#2 已落地,接 OpenLineage 标准 emitter |
| DAG 可视化编排(2026-06 补) | `WorkflowDesigner.vue` + `designer/inspector/{JobNodeForm,FileStepNodeForm,GatewayNodeForm}.vue`(自研画布,非 @vue-flow) | ✅ 原 §三 Gap#4 已从只读升级为可编排 |
| 多语言 task SDK(2026-06 补) | `sdk/{go,java,python,rust,typescript}` 五语言契约核 + 运行时引擎 | ✅ 原 §三 Gap#6 已落地,五语言全链路实测绿 |

## 三、真实短板(核实存在,值得补)

> 2026-06-29 复核:原 6 条短板中 #1 日志查看 / #2 Lineage / #4 DAG 编辑器 / #6 多语言 SDK **均已落地**(上移到 §二)。下表只保留经 origin/main 核实仍缺的两项。

### 真缺
| # | 缺口 | 证据(缺失) | 影响 | 建议 |
|---|---|---|---|---|
| 3 | **GitOps / YAML workflow** | workflow 定义仅在 `batch.workflow_definition` 表 | 不能 Git diff / PR 改流程 | 谨慎,见 §四:做 export/import 而非 source-of-truth |

### 半成品
| # | 缺口 | 现状证据 | 差距 | 建议 |
|---|---|---|---|---|
| 5 | **动态 fan-out** | `DefaultPartitionDispatchService` 是资源驱动定态 1:N 分区 | ≠ Airflow `dynamic task mapping`(按上游输出生成 N task) | P0,真实表达力差距;已被 [roadmap §2.3](../plans/bfs-open-source-scheduler-boundary-roadmap-2026-06-29.md) 接管 |

## 四、范围边界外(不算缺陷,坚持不做)

| 能力 | 竞品有 | 为何你不做 |
|---|---|---|
| K8s 原生 task executor(按 task 拉 pod) | Argo / Airflow KubernetesPodOperator | **ADR-027 明确"挑 worker 不挑机器"**,自研 K8s 调度越界 |
| 通用图灵完备 workflow code | Temporal workflow code | 你是"文件/任务交付闭环",JSON DAG + JoinMode 够用 |
| 多 cluster federation | Temporal namespaces | 当前单 cluster 定位,无 SaaS 多区域需求前不做 |

## 五、架构层面的真风险(非功能,但要心里有数)

1. **scheduler 单点**:Quartz + ShedLock 锁,只有一个 active scheduler,水平扩 0。当前规模够,但量级上来要拆 leader/follower。
2. **DB-centric 状态机**:`workflow_run`/`job_instance` 在 PG 上锁,5万+ 并发实例时 PG 成瓶颈(Temporal 用 Cassandra,Argo 用 etcd)。
3. **Kafka 硬依赖**:跨网/混合云时 MQ 是刚性中间件,无可插拔 task queue 抽象。
4. **多租是表级 column 隔离**:无 namespace 物理隔离,SaaS 共享 PG 时邻居效应无防护。

## 六、行动建议(按 ROI)

**已完成(2026-06 落地,见 §二):**
- ~~任务级 log viewer~~ ✅ `/execution-logs` + 前端 ExecutionLog
- ~~DAG 可视化编排~~ ✅ WorkflowDesigner(自研画布)
- ~~OpenLineage emitter~~ ✅ OpenLineageEmitter
- ~~多语言 task SDK~~ ✅ 五语言(go/java/python/rust/typescript)

**仍待补(剩余真实短板):**
- 动态 fan-out(dynamic task mapping)— P0,已被 [roadmap §2.3](../plans/bfs-open-source-scheduler-boundary-roadmap-2026-06-29.md) 接管

**不做:**
- K8s 原生 executor(ADR-027 边界)
- workflow YAML 作为 source-of-truth(DB-centric 定位,顶多做 export/import)
