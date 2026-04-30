
# 架构基线文档（Architecture Truth）

> **单一事实源**：本文档描述系统的**当前真实状态**（As-Is）、目标状态（To-Be）以及两者之间的差距清单。
> 核心名词的统一定义请同时参考 [core-model.md](./core-model.md)。
> 最后更新：2026-04-09

---

## 目录

1. [执行摘要](#1-执行摘要)
2. [模块地图](#2-模块地图)
3. [技术栈基线](#3-技术栈基线)
4. [数据库 Schema 基线](#4-数据库-schema-基线)
5. [Kafka Topic 清单](#5-kafka-topic-清单)
6. [API 版本与端口](#6-api-版本与端口)
7. [测试覆盖基线](#7-测试覆盖基线)
8. [设计模式与反模式状态](#8-设计模式与反模式状态)
9. [As-Is → To-Be 差距清单](#9-as-is--to-be-差距清单)
10. [ADR 索引](#10-adr-索引)

---

## 1. 执行摘要

批量调度平台是一套**多租户、多工作类型**的企业级批量任务编排与执行系统。核心职责：

- **调度与编排**（batch-orchestrator）：接收触发请求、构建执行计划、通过 DAG 编排工作流节点、管理分区生命周期
- **任务执行**（batch-worker-import / export / process / dispatch）：从 Kafka 消费分发事件、执行业务阶段管道（PROCESS 走 WAP+bookends:prepare → compute → validate → commit → feedback;其它 worker 各自固定 stage 模板）、回报结果
- **文件治理**：延迟告警、到达组聚合、归档清理、SLA 监控
- **可观测性**：结构化日志（MDC 7 字段）、Prometheus 指标、Grafana 仪表盘、AlertManager 告警

**核心链路状态**：核心功能链路完整，单元/集成/E2E 测试体系建立，设计模式规范。

> **注意**：控制平面（Trigger 入口鉴权、Console 幂等设计、Webhook 可靠性）和运维平面的硬化程度
> 仍落后于核心执行链路。详见 `深度分析报告.md` §5 仍开放问题清单和 `docs/analysis/fix-report-v2.md`。

---

## 2. 模块地图

```
batch-common               ← 共享库：枚举、DTO、Kafka 消息、工具类、插件接口
    ↑ (依赖)
batch-orchestrator         ← 核心编排引擎（端口 8082）
    ├── trigger 消费 / launch 处理
    ├── workflow DAG 编排
    ├── 分区 / 任务生命周期
    ├── Outbox 轮询 → Kafka
    ├── 重试治理 / 死信管理
    ├── 文件治理 / SLA 调度
    ├── 补偿命令执行
    └── 资源调度 / 配额管理

batch-trigger              ← Quartz 调度器（端口 8081）
    └── 定时触发 → launch API

batch-worker-core          ← Worker 基础框架（库）
    ├── AbstractWorkerLoop（生命周期模板）
    ├── AbstractTaskConsumer（消费骨架）
    ├── AbstractPipelineStepExecutionAdapter（管道执行模板）
    ├── AbstractStageExecutor（阶段执行基类）
    └── StageFailureCode / StageExecutionContext（失败码契约）

batch-worker-import        ← 导入 Worker（端口 8083）
batch-worker-export        ← 导出 Worker（端口 8084）
batch-worker-dispatch      ← 分发 Worker（端口 8085）
batch-worker-process       ← 加工 Worker（端口 8086,WAP+bookends 5 段 + ProcessComputePlugin 扩展点）
    └── 各自实现插件注册表 + 阶段步骤

batch-console-api          ← 控制台 API + AI 网关（端口 8080）
    └── 查询接口 / 人工干预 / 审批工作流

batch-e2e-tests            ← 端到端测试套件（TestContainers）
```

**运行时依赖**：

| 基础设施 | 用途 | 默认地址 |
|---------|------|---------|
| PostgreSQL 16 | 平台 DB（`batch` + `quartz` schema） | localhost:15432 |
| Apache Kafka 4.1+ | 任务分发 / 结果回传 | localhost:19092 |
| MinIO | 导入/导出文件存储 | localhost:19000 |
| Redis | 分布式缓存、SSE 广播、配额快照 | localhost:6379 |
| Prometheus / Grafana | 指标采集与展示 | — |
| OTel Collector / Jaeger | 分布式追踪 | — |

---

## 3. 技术栈基线

| 层次 | 技术 | 版本 | 使用模块 | 备注 |
|------|------|------|---------|------|
| 框架 | Spring Boot | **4.0.3** | all | Parent BOM |
| 语言 | Java | **25** | all | Records、Sealed Classes、Pattern Matching |
| 持久化（定义层） | Spring Data JDBC | managed | orchestrator, console-api | `*Record` 对象，读多写少 |
| 持久化（运行时层） | MyBatis | 4.0.0 | orchestrator, workers, trigger, console-api | `*Entity` 对象，CAS 条件更新 |
| 数据库迁移 | Flyway | managed | all | PostgreSQL dialect |
| 消息 | Apache Kafka | 4.1.2 | orchestrator, worker-core, workers | Outbox 模式解耦 |
| 对象存储 | MinIO SDK | 8.6.0 | common, orchestrator, workers | — |
| 缓存 / Realtime | Spring Data Redis (Lettuce) | managed | orchestrator, console-api | 分布式缓存、SSE 广播 |
| 安全 | Spring Security + OAuth2 JOSE | managed | console-api | JWT 鉴权 |
| AI 集成 | Spring AI | 2.0.0-M3 | console-api | Console AI 网关 |
| 任务调度 | Quartz（JDBC JobStore） | managed | trigger | Cron / FixedRate |
| 分布式锁 | ShedLock | 6.3.0 | common | JDBC-based |
| HTTP 客户端 | OkHttp | 4.12.0 | export, dispatch | Worker → Orchestrator |
| 电子表格 | Apache POI | 5.4.0 | import, export, console-api | Excel 解析与生成 |
| SQL 解析 | JSqlParser | 4.5 | export | Schema 白名单校验 |
| SFTP | JSch (mwiede fork) | 0.2.23 | dispatch | SFTP 渠道适配 |
| 邮件 | Angus Mail | managed | dispatch | SMTP 分发 |
| 校验 | Hibernate Validator | managed | orchestrator | Bean Validation |
| 可观测（指标） | Micrometer + Prometheus Registry | managed | all | — |
| 可观测（追踪） | Micrometer Tracing → OpenTelemetry | managed | common | OTLP 导出 |
| 代码生成 | Lombok | 1.18.42 | all (provided) | — |
| 测试容器 | TestContainers | 1.21.4 | all (test) | PostgreSQL + Kafka + MinIO + Redis |
| 邮件测试 | GreenMail | 2.1.8 | dispatch (test) | SMTP mock |

---

## 4. 数据库 Schema 基线

**当前版本**：Flyway V40（`db/migration/`，跳过 V31）

| 版本 | 内容摘要 | 核心表 |
|------|---------|-------|
| V1 | Schema 初始化 | batch / quartz schema |
| V2 | 配置表 | resource_queue, tenant_quota_policy, batch_window, worker_registry |
| V3 | 定义表 | job_definition, workflow_definition, workflow_node, workflow_edge |
| V4 | 运行时表 | trigger_request, job_instance, job_partition, job_task, workflow_run, workflow_node_run |
| V5 | 文件表 | file_record, pipeline_definition, pipeline_step_definition, file_template_config, file_dispatch_record, file_audit_log |
| V6 | 运维表 | job_execution_log, retry_schedule, dead_letter_task, outbox_event |
| V7 | 索引 | 运行时 + 文件表全量索引 |
| V8 | 阶段码扩展 | pipeline_step_definition stage 枚举扩展 |
| V9 | 文件错误记录 | file_import_error_record |
| V10 | 控制台 AI 审计 | console_ai_audit_log |
| V11 | SLA 与到达治理 | file_arrival_group, file_sla_policy, file_arrival_group_member |
| V12 | 补偿 + 步骤运行时 | job_step_instance, compensation_command, compensation_command_detail |
| V13–V14 | 文件模板扩展 | chunk_size, preprocess_pipeline 字段 |
| V15 | 调度器快照 | tenant_scheduler_snapshot |
| V16 | 补偿唯一约束 | — |
| V17 | 文件模板安全标志 | content_encryption_enabled 等字段 |
| V18 | 告警事件 | alert_event |
| V19 | Worker 排空 | drain_started_at, drain_deadline_at 字段 |
| V20 | 事件 Outbox 日志 | event_outbox_log |
| V21 | 配置发布 & 密钥版本 | config_release, secret_version |
| V22 | API 推送渠道 | file_channel_config API_PUSH 类型支持 |
| V23 | 运行时默认参数 | batch_runtime_default_parameter（含种子数据） |
| V24 | 配额运行时状态 | quota_runtime_state |
| V25 | 渠道健康 | file_channel_health |
| V26 | 审批命令 | approval_command, approval_command_detail |
| V27 | 审批工作流 | approval_command 扩展字段 |
| V28 | 节点类型扩展 | workflow_node job_node_type 字段 |
| V29 | 文件模板插件引用 | file_template_config plugin_ref 字段 |
| V30 | ShedLock 表 | shedlock |
| V32 | 批处理日支持 | batch_day, batch_day_calendar 等 |
| V33 | 乐观锁版本字段 | job_instance / job_partition / job_task version 字段 |
| V34 | 控制台用户账户 | console_user_account |
| V35 | 控制台用户密码 Argon2id | console_user_account password_hash 算法升级 |
| V36 | job_task type CHECK 扩展 | 增加新 task_type 枚举值 |
| V37 | trigger_request dedup 约束修复 | 唯一约束重建 |
| V38 | 幂等记录表 | idempotency_record（`DatabaseIdempotencyGuard` 使用） |
| V39 | trigger_request status CHECK 扩展 | 增加 PENDING / PROCESSING 状态 |
| V40 | job_execution_log type CHECK 扩展 | 增加 COMPENSATION 类型 |

**关键唯一约束**（由 `SqlConsistencyIT` 持续守卫）：

| 表 | 约束 |
|----|------|
| job_instance | uk_job_instance_tenant_dedup |
| outbox_event | uk_outbox_event_key |
| job_definition | uk_job_definition_tenant_code |
| batch_runtime_default_parameter | uk on (module, parameter_key) |

---

## 5. Kafka Topic 清单

| Topic | 生产者 | 消费者 | Key 格式 |
|-------|-------|-------|---------|
| batch.task.dispatch.import | Orchestrator Outbox | ImportWorker | tenantId:jobCode:instanceNo:partitionId |
| batch.task.dispatch.export | Orchestrator Outbox | ExportWorker | tenantId:jobCode:instanceNo:partitionId |
| batch.task.dispatch.dispatch | Orchestrator Outbox | DispatchWorker | tenantId:jobCode:instanceNo:partitionId |
| batch.task.result | Worker（REPORT） | Orchestrator | tenantId:jobCode:instanceNo:taskId |
| batch.task.retry | Orchestrator Retry Scheduler | Worker | tenantId:jobCode:instanceNo:partitionId:attemptNo |
| batch.task.dead-letter | RetryGovernanceService | DLQ Consumer | tenantId:jobCode:instanceNo:partitionId:taskId |
| batch.outbox.event | OutboxPollScheduler | 各下游消费者 | tenantId:eventName:aggregateType:aggregateId |
| batch.worker.heartbeat | AbstractWorkerLoop | Orchestrator Registry | tenantId:workerCode:workerGroup |

**消息幂等键组成**：

| 场景 | 幂等键 |
|------|-------|
| 触发请求 | tenantId + jobCode + bizDate + requestId |
| 任务分发 | tenantId + jobCode + instanceNo + partitionId |
| Outbox 事件 | tenantId + eventName + aggregateType + aggregateId |
| 任务分发 outbox | tenantId + ":" + taskId |
| 重试分发 | tenantId + ":retry:" + retryScheduleId |
| 死信回放 | tenantId + ":dead-letter:" + deadLetterTaskId |

---

## 6. API 版本与端口

| 模块 | 端口 | 主要 API 前缀 |
|------|------|------------|
| batch-console-api | 8080 | `/api/console/` |
| batch-trigger | 8081 | `/api/trigger/` |
| batch-orchestrator | 8082 | `/api/orchestrator/` |
| batch-worker-import | 8083 | `/api/worker/` |
| batch-worker-export | 8084 | `/api/worker/` |
| batch-worker-dispatch | 8085 | `/api/worker/` |
| batch-worker-process | 8086 | `/api/worker/` |

**Worker → Orchestrator 关键调用**（由 `HttpTaskExecutionClient` 执行，韧性策略：5xx/IO 指数退避，429 立即终止）：

| 端点 | 操作 |
|------|------|
| POST `/api/orchestrator/tasks/{taskId}/claim` | CLAIM（assignWorker） |
| POST `/api/orchestrator/tasks/{taskId}/start` | markRunning |
| POST `/api/orchestrator/tasks/{taskId}/outcome` | applyTaskOutcome |
| POST `/api/orchestrator/tasks/{taskId}/heartbeat` | renewTaskLease |
| POST `/api/orchestrator/tasks/{taskId}/logs` | appendLog |

---

## 7. 测试覆盖基线

### 单元测试（无容器）

| 模块 | 测试类数 | 用例数 | 覆盖范围 |
|------|---------|-------|---------|
| batch-orchestrator | ~30 | 245 | 状态机、重试治理、调度计划、DAG、SLA、租约回收、Worker 路由等 |
| batch-worker-export | — | 38 | GenerateStep（4 格式矩阵）、ExportStageExecutor |
| batch-worker-import | — | 34 | ParseStep、Import 阶段执行 |
| batch-worker-dispatch | — | — | DispatchStageExecutor |
| batch-worker-core | — | 9 | AbstractWorkerLoop 生命周期 |

### 集成测试（TestContainers PostgreSQL + Kafka + MinIO）

| 测试类 | 场景 |
|-------|------|
| TriggerTypeLaunchIntegrationTest | 各 TriggerType 触发 → job_instance 创建 + outbox 写入 |
| SchedulingDecisionLaunchIntegrationTest | 无/有 Worker 时的调度决策差异 |
| ConcurrentTaskFinishIntegrationTest | 两线程并发 finishTask，CAS 保证只有一个成功 |
| RetryScheduleIntegrationTest | 重试调度全流程 |
| LaunchT2FailureIntegrationTest | **T1 已提交 + T2 失败 → 重试走 dedup 路径** |
| SqlConsistencyIT | Flyway 全量迁移 + 核心唯一约束断言 |
| MultiTenantIsolationIntegrationTest | 多租户数据隔离 |
| BatchOrchestratorApplicationStartupIT | Spring Boot 上下文完整加载 |

### E2E 测试（全栈 TestContainers）

| 测试类 | 场景 |
|-------|------|
| ImportPipelineE2eIT | Import 主链路成功 |
| ImportFailurePipelineE2eIT | Import 解析失败 |
| ImportFailureE2eIT | Import 业务数据非法 / 基础设施失败 + 死信 |
| ExportPipelineE2eIT | Export 主链路成功 |
| ExportFailurePipelineE2eIT | Export 模板/渠道缺失失败 |
| ExportContentVerificationE2eIT | Export 产物内容校验（settlement_no、金额汇总） |
| ExportStorageFailureE2eIT | Export MinIO 不可达 + 重试耗尽死信 |
| DispatchPipelineE2eIT | Dispatch 主链路 + receipt/audit 断言 |
| DispatchFailurePipelineE2eIT | Dispatch 渠道拒绝失败 |
| OutboxForwarderE2eIT | Outbox 真实调度器轮询（不调用 publishAllPending） |
| OutboxForwarderRetryE2eIT | Outbox 临时 Kafka 故障 → 恢复 / 耗尽 GIVE_UP |
| MultiTenantConcurrentE2eIT | 多租户并发隔离（job_instance / outbox / file_error_record） |

---

## 8. 设计模式与反模式状态

详见 [`design-patterns-evaluation.md`](./design-patterns-evaluation.md)。

**摘要**：

- 已合理使用设计模式：**15 个**
- P1/P2 模式引入：**6 处，全部完成**
- 反模式：**7 处核心链路反模式已清零（2026-03-25）；控制平面仍有开放项**
  - God Class 拆分（TaskExecution → 3 子服务 + Facade；Launch → ValidationService + DispatchService）
  - 大事务拆分（T1/T2 独立提交）
  - `@Transactional(REQUIRES_NEW)` 补偿链隔离
  - `Stateful` 接口替换状态反射
  - `AbstractStageExecutor` 消除三份拷贝
  - Export Strategy 注册表

---

## 9. As-Is → To-Be 差距清单

### 已关闭（P0 + P1，2026-03-25 全部完成）

| 差距 | 关闭方式 |
|------|---------|
| Worker 生命周期/消费者重复 | AbstractWorkerLoop + AbstractTaskConsumer |
| Stage 异常处理不统一 | StageFailureCode + StageExecutionContext 统一契约 |
| Outbox forwarder 未真实轮询 | OutboxForwarderE2eIT / OutboxForwarderRetryE2eIT |
| 关键状态更新无原子语义 | CAS WHERE status=#{expected} + ConcurrentTaskFinishIT |
| Import 失败链路无 E2E 覆盖 | ImportFailureE2eIT（业务非法 + 基础设施失败 + 死信） |
| Export/Dispatch 失败及内容验收 | ExportContentVerificationE2eIT + ExportStorageFailureE2eIT |
| 多租户并发 E2E 缺失 | MultiTenantConcurrentE2eIT |
| HTTP 调用韧性薄弱 | OrchestratorTaskClientProperties + 指数退避 + 429 中止 |
| SQL 一致性无自动守卫 | SqlConsistencyIT（Flyway + 约束断言 + upsert 探针） |
| God Class 技术债 | 三服务拆分 + Facade（见第 8 节） |
| T1/T2 大事务 | launch() 拆分（见 ADR-003） |
| 业务异常无 i18n（zh/en 双语持久化） | i18n 全栈(Phase 1-F + Phase 2):`BizException.of(key, args)` + 11 表 `error_key`/`error_args` JSONB 列 + `LocalizedErrorRenderer` 按 Locale 重渲染。详 `docs/design/i18n.md` |
| Workflow 节点间参数无显式串联 | ADR-009 受限 JSONPath DSL: `$.nodes.<X>.output.<key>` + V72 `workflow_node_run.output` 列 + `WorkflowParamResolver`。详 `docs/architecture/workflow-dependency-guide.md §10` |
| trigger → orchestrator 同步 HTTP 桥（鲁棒性短板） | ADR-010 trigger_outbox + Kafka 异步路径：V80 `trigger_outbox_event` + `TriggerOutboxRelay` + `KafkaTriggerEventPublisher` + `TriggerLaunchConsumer`；灰度开关 `batch.trigger.async-launch.enabled`，原 HTTP 路径 `@Deprecated forRemoval=true` 等 1 minor 物理删除 |

### 未关闭（P2，当前优先级）

| 差距 | 目标 | 对应轮次 |
|------|------|---------|
| 配置 drift（多模块重复配置） | `batch-config-defaults` 共享基线 + 模块 overlay | 第10轮 |
| 架构决策无记录 | ADR 目录（本文件 + `adr/` 子目录） | 第11轮（本轮） |
| 产物内容验收无框架 | 可插拔 `Verifier` + Micrometer 指标 | 第12轮 |

### 未关闭（P3，暂不计划）

| 差距 | 说明 |
|------|------|
| 生产部署制品 | Dockerfile/Helm/K8s 脚本（runbook 已有，自动化待补） |
| 容量测试 | 基线压测脚本 |
| 文件加密 KMS 全生命周期 | content_encryption_enabled 字段已就位，KMS 客户端初版，完整轮换待补 |
| 审批工作流生产化 | 批量审批、审批 SLA 告警 |

---

## 10. ADR 索引

所有架构决策记录存放于 [`adr/`](./adr/) 目录。

| ADR | 标题 | 状态 | 决策日期 |
|-----|------|------|---------|
| [ADR-001](./adr/ADR-001-dual-orm.md) | MyBatis + Spring Data JDBC 双 ORM（不用 JPA） | Accepted | 2026-03-01 |
| [ADR-002](./adr/ADR-002-transactional-outbox.md) | 事务发件箱模式（不直接发 Kafka，不用 CDC） | Accepted | 2026-03-01 |
| [ADR-003](./adr/ADR-003-launch-t1-t2-split.md) | launch() T1/T2 事务拆分与 CGLIB 自注入 | Accepted | 2026-03-25 |
| [ADR-004](./adr/ADR-004-worker-lifecycle-template.md) | Worker 生命周期模板方法（AbstractWorkerLoop） | Accepted | 2026-03-25 |
| [ADR-005](./adr/ADR-005-partition-count-resolver-chain.md) | PartitionCountResolver 责任链（替代嵌套 if/else） | Accepted | 2026-03-25 |
| [ADR-006](./adr/ADR-006-compensation-requires-new.md) | 补偿重试方法使用 REQUIRES_NEW 防死锁级联 | Accepted | 2026-03-25 |
| [ADR-007](./adr/ADR-007-dual-datasource.md) | 单 PostgreSQL 双 Schema 隔离（platform + quartz） | Accepted | 2026-03-01 |
| [ADR-008](./adr/ADR-008-god-class-decomposition.md) | God Class 拆分：子服务 + Facade 保持接口稳定 | Accepted | 2026-03-25 |
| [ADR-009](./adr/ADR-009-workflow-param-dsl.md) | Workflow 节点间参数串联 DSL（受限 JSONPath: `$.nodes.<X>.output.<key>` + `$.workflowRun.<key>`） | Accepted | 2026-04-29 |
| [ADR-010](./adr/ADR-010-trigger-async-decoupling.md) | Trigger → Orchestrator 异步解耦（trigger_outbox + Kafka，复用 ADR-002 模式） | Accepted | 2026-04-30 |
