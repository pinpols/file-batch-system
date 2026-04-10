# 设计文档与现有代码差距核查

核查日期：2026-03-22；**修订**：2026-03-22（同步第 22 轮 Flyway 与参数基线、修正已过时的缺口描述）

## 核查范围

- 设计文档全章节：`docs/批量调度系统设计说明书（完整版）-20260321.md`
- 架构真相文档：`docs/architecture/architecture-truth.md`（含 ADR-001 ~ ADR-008）
- 核心模型统一口径：`docs/architecture/core-model.md`
- 实现状态跟踪：`docs/architecture/implementation-status-2026-03-22.md`
- 代码现状：8 个 Maven 模块全部 `src/main` 代码
- 校验结果：`mvn -q compile` 通过

## 当前结论

- 调度主链、Outbox/Kafka、CLAIM/REPORT、DAG 最小推进、补偿/重试/死信、文件治理、控制台查询、AI 网关、租户与安全边界，已经具备可运行基础。
- 主要差距已经不在“有没有骨架”，而在“离设计文档的完整生产版还有多远”。
- 剩余缺口主要集中在：
  - 文件链路高级能力
  - 流式处理与内存防线
  - 高级资源公平调度
  - 高级补偿和人工治理
  - 文件内容安全
  - 可观测、压测、部署和合规交付

## 章节差距

### 8. 资源调度与运行控制

- 已实现：窗口检查、租户/队列并发、**突发额度**（`tenant_quota_policy.burst_limit` / `partition_burst_limit`，`resource_queue.burst_limit`）、**公平共享组跨租户作业上限**（`fair_share_group` + `group_shared_max_running_jobs`，`JobInstanceMapper.countActiveByFairShareGroup`）、**`quota_reset_policy` 运行时闭环**（`CALENDAR_DAY / SLIDING_WINDOW` 的 `quota_runtime_state` 落库、调度器评估与周期重置）、**`worker_registry.current_load`** + 心跳 DTO **`currentLoad`**、**Worker 选择优先低负载再比心跳**、**调度实时快照与历史**（`TenantSchedulerSnapshotService` / `tenant_scheduler_snapshot` 表 + 定时写入）、控制台 **`/api/console/scheduler/snapshot`** 代理 Orchestrator。
- 部分缺失：
  - 其他高级资源公平与容量评估项仍按后续轮次继续补齐
- **已补齐（原审计误记为缺失）**：Worker **排空超时与扫描间隔** 由 Orchestrator 配置 **`batch.worker.drain.default-timeout-seconds`**、**`check-interval-millis`**（环境变量 `BATCH_WORKER_DRAIN_TIMEOUT_SECONDS`、`BATCH_WORKER_DRAIN_CHECK_INTERVAL_MILLIS`）及 Flyway **`V19__worker_registry_drain.sql`**（`drain_started_at` / `drain_deadline_at`）承载；控制台代理 **`/api/console/workers/{code}/drain|force-offline|claimed-tasks`**。**新任务派发**仅选 `worker_registry.status=ONLINE`（见 `DefaultWorkerSelector`），`DRAINING` 节点不会接收新路由。
- 代码证据：
  - [DefaultResourceScheduler.java](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/java/com/example/batch/orchestrator/scheduler/DefaultResourceScheduler.java)
  - [DefaultConcurrencyLimiter.java](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/java/com/example/batch/orchestrator/scheduler/DefaultConcurrencyLimiter.java)
  - [DefaultPartitionThrottle.java](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/java/com/example/batch/orchestrator/scheduler/DefaultPartitionThrottle.java)
  - [DefaultWorkerSelector.java](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/java/com/example/batch/orchestrator/scheduler/DefaultWorkerSelector.java)
  - [TenantSchedulerSnapshotService.java](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/java/com/example/batch/orchestrator/scheduler/snapshot/TenantSchedulerSnapshotService.java)
  - [SchedulerSnapshotController.java](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/java/com/example/batch/orchestrator/controller/SchedulerSnapshotController.java)
  - [ConsoleSchedulerSnapshotController.java](/Users/dengchao/Downloads/file-batch-system/batch-console-api/src/main/java/com/example/batch/console/web/ConsoleSchedulerSnapshotController.java)
  - Flyway [V16__scheduler_fair_share_snapshot_load.sql](/Users/dengchao/Downloads/file-batch-system/db/migration/V16__scheduler_fair_share_snapshot_load.sql)
  - Worker 排空：[WorkerDrainGovernanceService](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/DefaultWorkerDrainGovernanceService.java)、[WorkerDrainTimeoutScheduler](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/java/com/example/batch/orchestrator/scheduler/WorkerDrainTimeoutScheduler.java)、[docs/runbook/rolling-upgrade-workers.md](/Users/dengchao/Downloads/file-batch-system/docs/runbook/rolling-upgrade-workers.md)

### 9. 文件处理链路

- **阶段命名与说明书 §9.3 / §9.4 对齐**：导入 `Receive → … → Load → Feedback`，导出 `Prepare → Generate → Store → Register → Complete`；**LOAD / GENERATE 的上下游差异**通过 **`ImportLoadPlugin` / `ExportDataPlugin`** + 模板路由字段落地。详见 [worker-plugins.md](./worker-plugins.md)（含 **generic JDBC** 与说明书差异摘要）。
- 导入链路（相对说明书 §9.3 的**现状**）：
  - **`PREPROCESS`**：已实现 **`ImportPreprocessPipeline`**（模板 `preprocess_pipeline` 或隐式 `compress_type` / `encrypt_type`）：UNZIP、GUNZIP、AES-GCM 解密、摘要校验、RSA-SHA256 验签、字符集转码等；**不是**「仅换行归一化」。
  - **`PARSE`**：支持 **`JSON`、`DELIMITED`、`EXCEL`（XSSF）、`XML`、`FIXED_WIDTH`**；流式阶段写出 NDJSON 临时文件。
  - **中间表示**：通用 **`jdbc_mapped`** 路径的 PARSE / VALIDATE / LOAD 已保留 **`Map<String,Object>`** 逻辑行，适合任意列名导入；部分存量示例模板仍可继续落到 **`CustomerImportPayload`**（Jackson），用于字段强绑定场景。
  - **`LOAD`**：**`ImportLoadPlugin`**（含 **`jdbc_mapped`**、`batchUpdate`）；说明书 **「仅中间表 + 异步合并」** 非内置，需 staging + 下游作业或专用插件。
- 代码证据：
  - [ImportPreprocessPipeline.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-import/src/main/java/com/example/batch/worker/imports/preprocess/ImportPreprocessPipeline.java)
  - [PreprocessStep.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-import/src/main/java/com/example/batch/worker/imports/stage/PreprocessStep.java)
  - [ParseStep.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-import/src/main/java/com/example/batch/worker/imports/stage/ParseStep.java)
  - [LoadStep.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-import/src/main/java/com/example/batch/worker/imports/stage/LoadStep.java)

- 导出链路（相对说明书 §9.4）：
  - **GENERATE**：当前输出 **`JSON` / `DELIMITED` / `EXCEL` / `FIXED_WIDTH`**；DELIMITED 使用 **`csv()`**，并支持模板级 **`delimiter` / `quotePolicy` / `escapePolicy` / `headerRows`**；Excel 与定长格式也已补齐。
  - **STORE**：已实现 **`.part` 临时对象 → 摘要校验 → `copy` 转正 → 删临时**（见 `StoreStep` + `MinioExportStorage`），与说明书「临时对象、校验、晋升」**方向一致**。
  - **快照元数据**：`PrepareStep` / `GenerateStep` 已写入 **`snapshotMode` / `snapshotTs` 等**导出上下文（见 `PrepareStep`）；与说明书「跨分片一致读、`sourcePartitions` 强语义」的**完全对齐**仍待验收。
- 代码证据：
  - [GenerateStep.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-export/src/main/java/com/example/batch/worker/exports/stage/GenerateStep.java)
  - [StoreStep.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-export/src/main/java/com/example/batch/worker/exports/stage/StoreStep.java)

- 分发链路（相对说明书文件分发章节）：
  - **`API` / `API_PUSH`**：**`HttpDispatchChannelAdapter`**（OkHttp POST；`API_PUSH` 可配 `api_push_api_key` / `authorization` 头）。
  - **`LOCAL`**：**`LocalDispatchChannelAdapter`**（本地目录信封）。
  - **`SFTP`**：**`SftpDispatchChannelAdapter`**（JSch 上传 `file_record` 内容）。
  - **`EMAIL`**：**`SmtpEmailDispatchChannelAdapter`**（Angus/Jakarta Mail SMTP + 附件）。
  - **`NAS`**：**`NasDispatchChannelAdapter`**（共享目录写入，支持 `nas_remote_directory` / `nas_remote_file_name`）。
  - **`OSS`**：**`OssDispatchChannelAdapter`**（MinIO / S3 兼容对象写入，支持 `oss_bucket` / `oss_object_prefix`）。
  - **熔断 / 健康 / 指标**：**`DispatchChannelGateway`** 集成 **`DispatchChannelCircuitBreaker`**、**`DispatchChannelHealthService`**、Micrometer **`batch.dispatch.deliveries`**、Gauge **`batch.dispatch.circuits.open`**；健康表 **`batch.file_channel_health`** 记录失败退避与恢复探测。
  - **异步回执轮询**：**`DispatchReceiptPollScheduler`** 轮询 `receipt_poll_url`（合并自 `config_json`），条件：`file_dispatch_record` 为 `SENT` + `PENDING`。
  - **仍缺**：SFTP / EMAIL / HTTP 的主动健康探测与更复杂的分级退避策略仍可继续增强。
- 代码证据：
  - [DispatchChannelGateway.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-dispatch/src/main/java/com/example/batch/worker/dispatchs/infrastructure/channel/DispatchChannelGateway.java)
  - [HttpDispatchChannelAdapter.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-dispatch/src/main/java/com/example/batch/worker/dispatchs/infrastructure/channel/HttpDispatchChannelAdapter.java)
  - [SftpDispatchChannelAdapter.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-dispatch/src/main/java/com/example/batch/worker/dispatchs/infrastructure/channel/SftpDispatchChannelAdapter.java)
  - [SmtpEmailDispatchChannelAdapter.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-dispatch/src/main/java/com/example/batch/worker/dispatchs/infrastructure/channel/SmtpEmailDispatchChannelAdapter.java)
  - [LocalDispatchChannelAdapter.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-dispatch/src/main/java/com/example/batch/worker/dispatchs/infrastructure/channel/LocalDispatchChannelAdapter.java)
  - [NasDispatchChannelAdapter.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-dispatch/src/main/java/com/example/batch/worker/dispatchs/infrastructure/channel/NasDispatchChannelAdapter.java)
  - [OssDispatchChannelAdapter.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-dispatch/src/main/java/com/example/batch/worker/dispatchs/infrastructure/channel/OssDispatchChannelAdapter.java)
  - [DispatchChannelHealthService.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-dispatch/src/main/java/com/example/batch/worker/dispatchs/infrastructure/channel/DispatchChannelHealthService.java)
  - [DispatchChannelHealthScheduler.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-dispatch/src/main/java/com/example/batch/worker/dispatchs/infrastructure/channel/DispatchChannelHealthScheduler.java)
  - [DispatchChannelHealthRepository.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-dispatch/src/main/java/com/example/batch/worker/dispatchs/infrastructure/channel/DispatchChannelHealthRepository.java)
  - [DispatchReceiptPollScheduler.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-dispatch/src/main/java/com/example/batch/worker/dispatchs/infrastructure/DispatchReceiptPollScheduler.java)

### 9.12 边查边写与禁止全量加载

- 与设计 §9.12 **完全**一致仍有差距；已部分缓解处如下。
- **导入**：PARSE → VALIDATE → LOAD 可走 **NDJSON 临时文件**流式链路；通用 **`jdbc_mapped`** 已能在主路径保留 **`Map<String,Object>`** 逻辑行并按 chunk + **`batchUpdate`** 入库。存量示例模板仍可能使用 **`CustomerImportPayload`**，但不再是通用导入的硬约束。
- **导出**：明细 **分页查询 + 按页写文件**，避免单次加载全表；JSON snapshot 仍是按上下文对象序列化，不再额外做整表聚合。
- 代码证据：
  - [ParseStep.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-import/src/main/java/com/example/batch/worker/imports/stage/ParseStep.java)
  - [ValidateStep.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-import/src/main/java/com/example/batch/worker/imports/stage/ValidateStep.java)
  - [LoadStep.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-import/src/main/java/com/example/batch/worker/imports/stage/LoadStep.java)
  - [ImportDataQualityService.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-import/src/main/java/com/example/batch/worker/imports/infrastructure/ImportDataQualityService.java)
  - [GenerateStep.java](/Users/dengchao/Downloads/file-batch-system/batch-worker-export/src/main/java/com/example/batch/worker/exports/stage/GenerateStep.java)

### 12. 补偿、状态机与任务实例

- 已实现：统一补偿入口、`job_step_instance`、重试/死信/重放；**`STEP` 级补偿**在 `DefaultCompensationService.rerunStep` 中按 `job_step_instance` 解析 `jobTaskId` 并调用 **`RetryGovernanceService.retryTask`**（不再存在 `NOT_IMPLEMENTED` 空洞）。**审批单**已通过 `batch.approval_command`、`ApprovalWorkflowService`、控制台审批接口串起 catch-up / compensation / DLQ replay / download 的统一状态机。
- 仍有缺口：
  - 审批列表页、批量审批、审批 SLA 与告警联动仍可继续产品化。
- 代码证据：
  - [DefaultCompensationService.java](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/java/com/example/batch/orchestrator/application/service/DefaultCompensationService.java)

### 15. 多租户与安全

- 已实现：租户注入、角色权限、配置发布、密钥版本、预签名下载；**文件模板安全开关**已通过 Flyway **`V17__file_template_security_flags.sql`** 落库，并在 **`DefaultFileGovernanceService`**、控制台查询/预签名请求、**`ImportDataQualityService`** 等路径参与**预览/错误行/日志脱敏与下载门禁**。
- 另已补 **平台级测试开关 `batch.security.testing-open`**，用于前期联调时放开 console 认证、preview / error-line 脱敏与 import 侧预解密校验。
- 仍有缺口（相对说明书「端到端生产级」）：
  - **`content_encryption_enabled` + `encryption_key_ref`** 的对象落盘 **KMS/加解密全链路**与密钥版本轮换的**可证明**闭环（当前偏策略、审计与门禁字段）。
  - **`masking_rule_set`** 的可插拔规则引擎与命名规则集（如 PCI 类）若需对标合规，仍待加强。
  - **下载审批**与补偿侧已接入统一审批单工作流，后续可补更完整的审批台账与运营视图。

### 16. 可观测性与运行手册

- 已实现：actuator 暴露、部分 Micrometer 指标、SLA/file governance 定时检查；**告警落库**（`batch.alert_event`，Flyway **`V18__alert_event.sql`**）；**结构化日志 pattern** 在 console/import 等模块 `application.yml` 中已统一字段占位；仓库内已有 **runbook**（[`docs/runbook/`](/Users/dengchao/Downloads/file-batch-system/docs/runbook)）、**Prometheus/Grafana 基线**（[`docs/observability/prometheus-grafana-baseline.md`](/Users/dengchao/Downloads/file-batch-system/docs/observability/prometheus-grafana-baseline.md)、`grafana-dashboard-batch.json`）；**第 7 轮补齐**了 [`prometheus-batch-rules.yml`](/Users/dengchao/Downloads/file-batch-system/docs/observability/prometheus-batch-rules.yml)、[`alertmanager-batch-template.yml`](/Users/dengchao/Downloads/file-batch-system/docs/observability/alertmanager-batch-template.yml)、[`structured-logging-pipeline.md`](/Users/dengchao/Downloads/file-batch-system/docs/observability/structured-logging-pipeline.md)、[`scripts/ops/inspect-observability.sh`](/Users/dengchao/Downloads/file-batch-system/scripts/ops/inspect-observability.sh)。
- 仍有缺口：
  - 集中式日志管道如果要对接 ELK / OpenTelemetry，可在现有示例上继续补生产侧接入细节。
  - Kafka lag 若要接入平台统一告警，仍建议再补 Kafka exporter 或 Prometheus adapter。
- 代码/仓库证据：
  - [JobSlaScheduler.java](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/java/com/example/batch/orchestrator/infrastructure/sla/JobSlaScheduler.java)、[FileGovernanceScheduler.java](/Users/dengchao/Downloads/file-batch-system/batch-orchestrator/src/main/java/com/example/batch/orchestrator/infrastructure/file/FileGovernanceScheduler.java)
  - [docs/runbook/daily-inspection.md](/Users/dengchao/Downloads/file-batch-system/docs/runbook/daily-inspection.md)、[docs/runbook/incident-response.md](/Users/dengchao/Downloads/file-batch-system/docs/runbook/incident-response.md)

### 17 / 18 / 19 / 20.11 实施交付面

- 已实现：本地 `docker-compose` 和基础依赖初始化脚本；**Worker 排空**控制台与 Orchestrator 内部 API（见 §8）；**默认运行参数基线**：Flyway **`V23__batch_runtime_default_parameter.sql`** 表 **`batch.batch_runtime_default_parameter`** + 文档 **[runtime-default-parameters.md](./runtime-default-parameters.md)**。
- 仍有缺口：
  - 生产部署脚本 / Dockerfile / Helm / K8s 产物
  - 压测脚本与容量基线
  - 自动巡检/自愈脚本（runbook 已有可执行说明，脚本化仍可加强）
  - `THIRD-PARTY-LICENSES.md / NOTICE / SBOM`
- 仓库证据：
  - [docker-compose.yml](/Users/dengchao/Downloads/file-batch-system/docker-compose.yml)、[scripts/local/](/Users/dengchao/Downloads/file-batch-system/scripts/local)
  - [docs/runbook/rolling-upgrade-workers.md](/Users/dengchao/Downloads/file-batch-system/docs/runbook/rolling-upgrade-workers.md)

### 测试与质量门禁

**已完成（截至 2026-03-27）**：
- 单元测试：67 个（覆盖状态机、调度规则、文件链、分发链、安全、加解密、流式解析、trigger 链路等）
- 集成测试：35 个（基于 Testcontainers 的真实 PostgreSQL/Kafka/MinIO 协作 + 启动 smoke + ShedLock 配置校验）
- E2E 测试（`batch-e2e-tests`）：
  - `ImportPipelineE2eIT`、`ExportPipelineE2eIT`、`DispatchPipelineE2eIT`（主链路）
  - `OutboxForwarderE2eIT`（outbox 自动轮询）
  - `OutboxForwarderRetryE2eIT`（outbox 失败重试）
  - `ImportFailureE2eIT`、`ImportFailurePipelineE2eIT`、`ExportFailurePipelineE2eIT`、`ExportStorageFailureE2eIT`、`DispatchFailurePipelineE2eIT`（失败分支）
  - `ExportContentVerificationE2eIT`（内容级验证，含 MinIO 文件断言）
  - `MultiTenantConcurrentE2eIT`（多租户并发隔离）
  - `DedupJobLaunchE2eIT`（顺序 + 并发 dedup 幂等）
- SQL 一致性守卫：`SqlConsistencyIT`（batch-orchestrator，校验唯一约束、ON CONFLICT 兼容性）
- 统一回归入口：`scripts/ci/run-full-regression.sh`
- deploy smoke：Helm `lint + template`，并支持可选 live rollout / readiness 校验
- 测试基建：`AbstractIntegrationTest`（2×PG+Kafka+MinIO）、`E2eVerifier`/`ExportFileVerifier`/`DispatchReceiptVerifier` 验收框架
- 完整 seed 数据：7 张 seed SQL（全格式矩阵 + 多租户）、11 个 import fixture 文件

详细覆盖分析见 `docs/testing/full-project-test-matrix.md`、`docs/testing/test-strategy.md` 和 `docs/testing/release-gate.md`。

### Flyway 与文档漂移

- **已治理（第 22 轮）**：原重复的 `V11__create_event_outbox_logs`、`V12__create_config_release_and_secret_version`、`V14__file_channel_type_api_push` 已分别重编号为 **`V20` / `V21` / `V22`**，版本序列可与 Flyway 唯一校验兼容。新增 **`V24__batch_runtime_default_parameter.sql`** 承载默认参数目录表与种子数据。
- **后续扩展（V27–V40，跳过 V31）**：ShedLock 表（V30）、批处理日（V32）、乐观锁版本字段（V33）、控制台用户账户（V34–V35）、多个 CHECK 约束扩展（V36–V37、V39–V40）、幂等记录表（V38，供 `DatabaseIdempotencyGuard` 使用）。当前最新版本为 **V40**。
- **升级注意**：若某环境曾在旧命名下只执行过重复版本中的**一部分**，需对照 `flyway_schema_history` 人工核对后再迁移（详见 [runtime-default-parameters.md](./runtime-default-parameters.md)）。
- [README.md](/Users/dengchao/Downloads/file-batch-system/README.md) 已指向设计说明书、审计文档与补全对话；模块细节仍以代码与 `docs/` 为准。

## 当前优先级（截至 2026-04-08）

以下核心缺口已全部完成：
- ✅ Worker 生命周期/消费者模板抽象（AbstractWorkerLoop / AbstractTaskConsumer / AbstractStageExecutor）
- ✅ Stage 异常处理契约统一（StageFailureCode / ExecutionContext / StageExecutionContext）
- ✅ 配置基线模块化（batch-defaults.yml + spring.config.import）
- ✅ 架构真相文档与 ADR 体系（architecture-truth.md + ADR-001~ADR-008）
- ✅ 产物内容级验收标准化（ExportFileVerifier / DispatchReceiptVerifier + Micrometer 指标）
- ✅ Outbox 自动轮询 E2E、失败分支 E2E、多租户并发 E2E、SQL 一致性守卫
- ✅ 审批台账产品化（批量审批、审批 SLA 告警、运营视图 `/api/console/ops/summary`）
- ✅ SFTP/EMAIL/HTTP/OSS/NAS 渠道主动健康探测与分级退避（`DispatchChannelHealthService`）
- ✅ ELK / OpenTelemetry 生产侧采集管道（OTEL Collector + Jaeger + Loki，`docs/observability/`）
- ✅ 全局幂等层（`DatabaseIdempotencyGuard` + V38 `idempotency_record`）
- ✅ 路径遍历防护（`PathSanitizer`，SFTP StrictHostKeyChecking 默认 yes）
- ✅ 死信队列重放 E2E（`DeadLetterApprovalReplayE2eIT`）
- ✅ 所有 P0/P1 安全与竞态修复（C-1~C-8、H-1~H-10、D-1~D-4，见 `批量系统问题实施路线图.md`）

**剩余未完成（按需推进，不影响核心运行）**：
- 真实 staging 集群 live rollout / readiness 实跑留档与回滚 smoke
- 压测脚本容量基线实测数据（脚本已有，需在 staging 环境实测填写）
