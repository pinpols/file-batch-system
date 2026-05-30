# 对标业界开源批量调度的能力差距分析

> 日期:2026-05-30。对标对象:Apache DolphinScheduler / Apache Airflow / XXL-Job / Temporal / Argo Workflows / Kestra。
> 方法:实际 grep + 读代码核实,非凭印象。每条给证据文件。

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

## 三、真实短板(核实存在,值得补)

### 真缺
| # | 缺口 | 证据(缺失) | 影响 | 建议 |
|---|---|---|---|---|
| 1 | **任务级在线日志查看** | 无 LogController / logContent API | 运维要 SSH 翻文件 / staging 表,定位慢 | **P0,~1 周**,console 嵌 log viewer,最高 ROI |
| 2 | **数据血缘 Lineage** | 无 OpenLineage / lineage 代码 | 故障定位 / 合规追溯缺"这 job 碰了哪些数据" | P1,已有 nodeOutput + OTel 做基础,接 emitter 成本低 |
| 3 | **GitOps / YAML workflow** | workflow 定义仅在 `batch.workflow_definition` 表 | 不能 Git diff / PR 改流程 | 谨慎,见 §四:做 export/import 而非 source-of-truth |

### 半成品
| # | 缺口 | 现状证据 | 差距 | 建议 |
|---|---|---|---|---|
| 4 | **DAG 拖拽编辑器** | `WorkflowMermaidViewer.vue` 只读;无 @vue-flow 依赖 | 只能看不能拖拽编排 | P0,Vue Flow(已在 P2 roadmap) |
| 5 | **动态 fan-out** | `DefaultPartitionDispatchService` 是资源驱动定态 1:N 分区 | ≠ Airflow `dynamic task mapping`(按上游输出生成 N task) | P1,真实表达力差距 |
| 6 | **多语言 task 原生 SDK** | `Shell/Http/Sql/StoredProc` 4 executor;HTTP 可调任意语言服务 | 无 Python/Node 原生薄壳 | P1,基于 HTTP executor 扩 Python SDK |

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

**P0(2-3 月,补了立刻能用):**
- 任务级 log viewer(~1 周)
- DAG 拖拽编辑 Vue Flow(已规划)

**P1(6 月,补表达力 / 可观测):**
- 动态 fan-out(dynamic task mapping)
- OpenLineage emitter(复用 nodeOutput + OTel)
- Python task SDK 薄壳(基于 HTTP executor)

**不做:**
- K8s 原生 executor(ADR-027 边界)
- workflow YAML 作为 source-of-truth(DB-centric 定位,顶多做 export/import)
