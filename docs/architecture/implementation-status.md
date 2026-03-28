# 设计文档与代码落地状态分析

分析日期：2026-03-22
分析基准：`docs/architecture/design-gap-audit.md`、全量 `src/main` 代码、测试补全结果（对话_5 完成后更新于 2026-03-25）

更新记录：
- 2026-03-25：补齐 K8s 健康探针（Actuator probes）默认配置；Worker 停机时先标记 DRAINING 并停止 Kafka Listener 拉取新消息；Worker 运行方式调整为可对外提供 Actuator HTTP 端点（用于 readiness/liveness 探针）。
- 2026-03-26：可扩展性 & 高可用 6 项改造全部完成（详见下文及 `scalability-ha-assessment.md`）。

---

## 整体结论

**主干功能已全部落地，剩余差距集中在"生产加固"层面，不是核心缺失。**

---

## 已落地（核心链路）

| 设计模块 | 代码状态 | 关键证据 |
|---|---|---|
| 调度主链（DB→Outbox→Kafka→CLAIM→EXECUTE→REPORT） | ✅ 完整 | `KafkaOutboxPublisher`、`OutboxPollScheduler`、`DefaultScheduleForwarder` |
| Orchestrator 状态机（作业/分区/任务） | ✅ 完整 | `DefaultStateMachine`、`PartitionLifecycleService`、`TaskExecutionService` |
| DAG 工作流推进（多节点、条件边、JOIN 模式） | ✅ 完整 | `DefaultWorkflowDagService`（ALL/ANY/N_OF JOIN；ALWAYS/SUCCESS/FAILURE/CONDITION 边） |
| 资源调度（窗口/并发/公平组/突发额度/配额重置/快照） | ✅ 完整 | `DefaultResourceScheduler`、`DefaultConcurrencyLimiter`、`TenantSchedulerSnapshotService`、V15 Flyway |
| Import Worker 链路（Receive→Preprocess→Parse→Validate→Load→Feedback） | ✅ 完整 | `ImportPreprocessPipeline`（UNZIP/GUNZIP/AES-GCM/RSA-SHA256/转码）、`ParseStep`（JSON/CSV/EXCEL/XML/FIXED_WIDTH）、`ImportLoadPlugin` |
| Export Worker 链路（Prepare→Generate→Store→Register→Complete） | ✅ 完整 | `GenerateStep`（DELIMITED/JSON/EXCEL/FIXED_WIDTH）、`StoreStep`（.part→校验→晋升→删临时）、`MinioExportStorage` |
| Dispatch Worker（API/SFTP/EMAIL/NAS/OSS/LOCAL + 熔断/健康/回执轮询） | ✅ 完整 | `DispatchChannelGateway`、`DispatchChannelCircuitBreaker`、`DispatchChannelHealthService`、`DispatchReceiptPollScheduler` |
| 补偿/重试/死信/重放/审批工作流 | ✅ 完整 | `DefaultCompensationService`（STEP 级重跑）、`DefaultRetryGovernanceService`、`ApprovalWorkflowService` |
| 多租户/安全/配置发布/密钥版本 | ✅ 完整 | `ConsoleTenantGuard`、`ConfigReleaseMapper`、`SecretVersionMapper`、V17 Flyway（文件模板安全开关） |
| AI 提示词网关（分类/脱敏/审计日志） | ✅ 完整 | `ConsoleAiPromptGuard`（REJECTED_DISABLED/SAFETY/SCOPE/APPROVED）、`DefaultConsoleAiAuditService` |
| SLA & 文件治理定时检查 + 告警落库 | ✅ 完整 | `JobSlaScheduler`、`FileGovernanceScheduler`、V18 Flyway（`batch.alert_event`） |
| Worker 排空/下线（drain/force-offline） | ✅ 完整 | `WorkerDrainGovernanceService`、`WorkerDrainTimeoutScheduler`、V19 Flyway（`drain_started_at`/`drain_deadline_at`） |
| K8s 健康探针 + Worker 优雅停机（停止接新活 + 等 in-flight） | ✅ 完整 | `batch-defaults.yml` 启用 Actuator probes；`GracefulKafkaShutdown`（stop listener → `awaitDrain` 120s）；`ActiveTaskLeaseRegistry.awaitDrain(Duration)` |
| Orchestrator 多实例 HA（乐观锁） | ✅ 完整 | `job_instance/partition/task` 状态转换均含 `version CAS`；mapper 返回 `int`，affected=0 静默放弃 |
| Kafka 消费并发 + 背压 | ✅ 完整 | 三类 worker `concurrency=4`、`max-poll-records`；`AbstractTaskConsumer` Semaphore + container pause/resume |
| 连接池角色隔离 | ✅ 完整 | `BusinessDataSourceConfiguration` 改造为 `HikariConfig @ConfigurationProperties`；各模块按角色定容（orchestrator platform=10；import biz=15；export biz=20；dispatch platform=10） |
| 临时文件兜底清理 | ✅ 完整 | `StaleTempFileCleanup`（ApplicationReadyEvent，清 `batch-*` 超 6h 孤儿文件） |
| 调度快照与控制台代理 | ✅ 完整 | `TenantSchedulerSnapshotService`、`ConsoleSchedulerSnapshotController` |
| 默认运行参数目录 | ✅ 完整 | V23 Flyway（`batch.batch_runtime_default_parameter`）、`runtime-default-parameters.md` |
| Flyway 完整迁移序列（V1–V23） | ✅ 完整 | 无重复版本号，V20/V21/V22 已在第 22 轮重编号修正 |
| 测试体系（单元 + 集成 + Testcontainers） | ✅ 本次补全 | 见下文测试覆盖章节 |

---

## 仍有差距（不影响核心运行）

### 高优——直接影响交付质量

| 差距项 | 现状 | 状态 |
|---|---|---|
| **文件对象加密 KMS 全链路** | `StoreStep` 已落地 BATCHENC 加密（AES-GCM）；`PreprocessStep` 新增 `BatchObjectCryptoService.decrypt()` 前置解密，在 `ImportPreprocessPipeline` 运行前透明解密 BATCHENC 包头格式，完成 export-encrypt / import-decrypt 闭环 | ✅ 已完成 |
| **§9.12 边查边写全量对齐** | `ParseStep.parseJsonObjectStreaming()` 已改为流式扫描 `{"records":[...]}` envelope，不再调用 `objectMapper.readTree()` 全量加载；`CustomerImportPayload` 转换保持逐条逻辑 | ✅ 已完成 |
| **导出格式矩阵完整性** | DELIMITED（引用/转义/分隔符策略）、FIXED_WIDTH（补位/截断/右对齐/record_length）、EXCEL（sheet名消毒/31字符限制/多页分页）、JSON（cursor 分页/快照）均已验证；`GenerateStepTest` 覆盖边界场景 | ✅ 已完成 |

### 中优——产品化/运营层面

| 差距项 | 现状 | 状态 |
|---|---|---|
| 审批台账产品化 | 审批单主干工作流已通（catch-up/compensation/DLQ replay/download） | ✅ 最小可用已补齐：批量审批（console）、审批列表查询（console）、SLA 告警查询（console）、运营汇总视图（console `/api/console/ops/summary`） |
| SFTP/EMAIL/HTTP 主动健康探测 | `probeSftp`（TCP socket）/`probeSmtp`（TCP socket）/`probeHttp`（HEAD 5s timeout）已落地；`DispatchChannelHealthProperties.probeChannelTypes` 默认包含全部六种渠道类型 | ✅ 已完成 |
| `masking_rule_set` PCI/GDPR 规则集 | `ContentMaskingUtils` 已新增 PCI（卡有效期）与 GDPR（IPv4/UK邮编/US ZIP）规则集；`maskPlainText(text, ruleSetCode)` 支持 STRICT/PCI/GDPR 三档 | ✅ 已完成 |
| Kafka lag 告警 | `prometheus-batch-rules.yml` 已启用 `BatchKafkaConsumerLagHigh` 规则 | ✅ 已完成 |

### 低优——交付/合规层面

| 差距项 | 现状 | 状态 |
|---|---|---|
| 生产部署产物 | Helm Chart `helm/batch-platform/`（6 个 Deployment/Service、ConfigMap/Secret、Ingress、HPA）；生产覆盖文件 `helm/values-prod.yaml` | ✅ 已完成 |
| 压测脚本与容量基线 | Gatling 3.12 Java DSL，`load-tests/` 独立模块；三个 Simulation（写入/查询/容量基线分级爬坡）；种子 SQL `docs/sql/load-test/load-test-seed.sql`；容量基线记录表 `docs/testing/load-test-capacity-baseline.md` | ✅ 脚本已完成，基线数据待实测填写 |
| Kafka 消息体完整 schema | `docs/architecture/kafka-topic-plan.md` 已补充 Envelope 规范、重试/死信完整 JSON 示例、序列化兼容策略（inline object + schemaVersion） | ✅ 已完成 |
| ELK / OpenTelemetry 接入 | OTEL Collector pipeline（Traces→Jaeger，Logs→Loki）；`batch-common` 新增 tracing bridge + OTLP exporter；`batch-defaults.yml` 统一 OTLP 端点 + 结构化 JSON 日志；Docker Compose observability overlay；Helm Collector 模板；Grafana 数据源自动注入 | ✅ 已完成 |
| `THIRD-PARTY-LICENSES / SBOM` | `docs/compliance/THIRD-PARTY-LICENSES.md`（43 组件，手工编译含许可证分类）；`docs/compliance/sbom.json`（CycloneDX 1.6 格式骨架）；根 `pom.xml` 新增 `compliance` Maven profile（`cyclonedx-maven-plugin 2.9.1` + `license-maven-plugin 2.4.0`），运行 `mvn -P compliance cyclonedx:makeAggregateBom license:aggregate-add-third-party` 可生成完整机器可读 SBOM | 合规交付物已完成 ✅ |
| 自动巡检/自愈脚本 | 巡检脚本体系完整落地：`inspect-all.sh`（主入口）、`inspect-db.sh`（Flyway/卡死作业/Outbox/死信/重试积压）、`inspect-workers.sh`（DRAINING 超时/心跳失联/孤儿任务）；自愈脚本：`heal-drain-timeout.sh`、`heal-dead-letters.sh`、`heal-stuck-outbox.sh`（均默认 dry-run，`BATCH_HEAL_DRY_RUN=false` 才执行）；`daily-inspection.md` 更新为脚本化 SOP | 脚本化巡检已完成 ✅ |

### 已知测试缺陷

无。`OutboxForwarderRetryE2eIT` 已通过 `@DynamicPropertySource` + `OrchestratorWireMockSupport.registerOrchestratorBaseUrls` 修复。`DedupJobLaunchE2eIT` 已补充顺序和并发两种 dedup 幂等场景。当前全套 E2E 无已知失败。

---

## 测试覆盖现状（2026-03-22 第三轮补全后）

### 单元测试（44 个）

| 测试类 | 所在模块 | 覆盖重点 |
|---|---|---|
| `WorkflowConditionEvaluatorTest` | orchestrator | 条件表达式（&&/\|\|/!/比较/in/contains 等） |
| `DefaultWorkflowDagServiceTest` | orchestrator | DAG 边类型路由、ALL/ANY/N_OF JOIN 就绪判断 |
| `DefaultWorkerRoutingPolicyTest` | orchestrator | Worker 路由优先级选择、不可用跳过 |
| `SequentialStepOrderResolverTest` | orchestrator | 步骤按 order 排序、null order 末尾 |
| `DefaultPipelineExecutorTest` | orchestrator | 步骤启用/禁用、路由覆盖、默认路由 |
| `DefaultSchedulePlanBuilderTest` | orchestrator | NONE/STATIC/DYNAMIC/AUTO 分区策略、上限 256 |
| `DefaultPrioritySchedulerTest` | orchestrator | 1–9 夹断、HIGH/MEDIUM/LOW 优先级带 |
| `DefaultStepRegistryTest` | orchestrator | 步骤注册与查找 |
| `DefaultStateMachineTest` | orchestrator | 状态机事件到目标状态映射、null/空白事件兜底 |
| `QuotaResetPolicyTest` | orchestrator | from()/isRuntimeManaged()/startOfCalendarDay() |
| `QuotaRuntimeStateServiceTest` | orchestrator | NONE/SLIDING_WINDOW/CALENDAR_DAY evaluateAndReserve、describe、reconcile |
| `DefaultWorkerDrainGovernanceServiceTest` | orchestrator | startDrain/forceOffline/takeoverAfterDrainTimeout、guard 条件 |
| `DefaultRetryGovernanceServiceTest` | orchestrator | scheduleRetryIfNecessary、dead letter 生成、dispatchDueRetries 并发保护 |
| `DefaultCompensationServiceTest` | orchestrator | submit 校验、compensationType 路由 guard |
| `DefaultApprovalWorkflowServiceTest` | orchestrator | PENDING→APPROVED/REJECTED→EXECUTED 状态流转 |
| `PartitionLeaseReclaimSchedulerTest` | orchestrator | 无过期分区/无 task/无 instance/重新分发 |
| `JobSlaSchedulerTest` | orchestrator | disabled 跳过、无候选、markSlaAlerted=0 跳过、deadline/duration 违规 |
| `DispatchChannelCircuitBreakerTest` | worker-dispatch | 开路阈值、冷却重置、按渠道隔离 |
| `DispatchChannelGatewayTest` | worker-dispatch | 健康阻断、熔断阻断、适配器选择、指标记录 |
| `DispatchReceiptPollSchedulerTest` | worker-dispatch | disabled/无行/null fileId/空 channelCode/无 pollUrl 跳过 |
| `ImportLoadPluginRegistryTest` | worker-import | 大小写归一、默认插件、重复 id 检测 |
| `ExportDataPluginRegistryTest` | worker-export | 同上（导出侧） |
| `ActiveTaskLeaseRegistryTest` | worker-core | 租约注册/移除/覆盖/null 防护；`awaitDrain` 正常返回 + 超时返回 |
| `AbstractTaskConsumerBackpressureTest` | worker-core | Semaphore permits 耗尽时 container 被暂停；release 后恢复消费 |
| `StaleTempFileCleanupTest` | worker-core | 超龄文件被删；未超龄文件保留；启动异常不影响应用 |
| `DefaultTaskExecutionWrapperTest` | worker-core | 租约生命周期、claim 委托、异常时仍移除租约 |
| `ConsoleAiPromptGuardTest` | console-api | 禁用/空白/超长/敏感词/领域词/分类/优先级 |
| `JobInstanceStatusTest` | common | 9 个枚举 code/label/name 一致性 |
| `TaskStatusTest` | common | 7 个枚举 code/label/name 一致性 |
| `PartitionStatusTest` | common | 9 个枚举 code/label/name 一致性 |
| `WorkerRegistryStatusTest` | common | ONLINE→DRAINING→DECOMMISSIONED 生命周期顺序 |
| `DeadLetterReplayStatusTest` | common | 5 个枚举 code/label/name 一致性 |
| `RetryScheduleStatusTest` | common | 6 个枚举 code/label/name 一致性 |
| `RetryPolicyTypeTest` | common | NONE/FIXED/EXPONENTIAL code/label/name 一致性 |
| `BatchObjectCryptoServiceTest` | common | AES-GCM 加解密往返（字节/流/文件）、魔数头校验、shouldEncrypt 逻辑、resolveKeyRef、缺失密钥材料异常 |
| `PreprocessStepKmsDecryptTest` | worker-import | BATCHENC 密文 payload → PreprocessStep 透明解密 → normalizedPayload 正确还原（KMS 闭环） |
| `ParseStepStreamingTest` | worker-import | JSON array / `{"records":[...]}` envelope 流式解析、大批量 500 条不全量加载、空 records 返回 PARSE_EMPTY、DELIMITED 逐行 |
| `GenerateStepTest` | worker-export | DELIMITED 含分隔符引用/双引号转义/换行引用/ALL 策略/Tab 分隔；FIXED_WIDTH 补位/截断/record_length；EXCEL sheet 名消毒/超长；JSON cursor 分页/快照/特殊字符 |
| `PrepareStepTest` | worker-export | payload 解析/复用、模板配置加载、文件名规则与 objectName 生成、exportSnapshot 生成 |
| `StoreStepTest` | worker-export | generatedFilePath 校验、上传/晋升正向路径（digest match）、本地文件清理 |
| `RegisterStepTest` | worker-export | file_record 幂等复用、checksum 冲突保护、pipeline 绑定与导出标记调用 |
| `ParseStepFixtureTest` | worker-import | 基于磁盘 fixture 文件的格式覆盖：CSV/Pipe/Tab/JSON-array/JSON-envelope/UTF-8 BOM/Excel（POI 动态生成）/bad-records（解析全量，校验延后到 ValidateStep） |
| `ImportPreprocessPipelineRsaTest` | worker-import | VERIFY_RSA_SHA256 步骤：有效签名通过、篡改 payload 失败、缺失公钥失败、签名从 metadata 注入、testingOpen 跳过验证 |

### 集成测试（当前仓库 35 个，以下列主链路代表用例）

| 测试类 | 所在模块 | 覆盖重点 |
|---|---|---|
| `OutboxPublishIntegrationTest` | orchestrator | Outbox→Kafka 消息到达 + `EventDeliveryLog` 落库 |
| `RetryScheduleIntegrationTest` | orchestrator | 重试计划 CRUD、markRunning 并发保护、markSuccess/markFailed |
| `WorkerRegistryIntegrationTest` | orchestrator | ONLINE→DRAINING→DECOMMISSIONED 数据库状态流转 |
| `QuotaRuntimeStateIntegrationTest` | orchestrator | NONE/SLIDING_WINDOW/CALENDAR_DAY 评估与 peak 追踪、reconcile |
| `ApprovalWorkflowIntegrationTest` | orchestrator | submit/approve/reject/markExecuted 全链路，幂等保护 |
| `BatchOrchestratorApplicationStartupIT` | orchestrator | Spring 上下文加载 |
| `ImportIngressScannerIntegrationTest` | worker-import | MinIO 文件发现→平台 FileRecord 落库（幂等） |
| `BatchWorkerImportApplicationIT` | worker-import | Spring 上下文加载 |
| `MinioExportStorageIntegrationTest` | worker-export | MinIO 写/SHA-256/复制/删除/存在性 |
| `BatchWorkerExportApplicationIT` | worker-export | Spring 上下文加载 |
| `DispatchChannelHealthServiceIntegrationTest` | worker-dispatch | 健康快照 HEALTHY/UNHEALTHY 状态落库、allowDispatch 行为 |
| `BatchWorkerDispatchApplicationIT` | worker-dispatch | Spring 上下文加载 |
| `ConsoleAiAuditServiceIntegrationTest` | console-api | AI 审计日志落库与多条件查询 |
| `AlertEventIntegrationTest` | console-api | alert_event 落库、按 severity/status/alertType 查询、limit |
| `JobInstanceQueryIntegrationTest` | console-api | job_instance 按状态/jobCode/traceId 查询、分页 |
| `DeadLetterQueryIntegrationTest` | console-api | dead_letter_task 按 replayStatus/sourceType/traceId 查询 |
| `ConsoleRetryScheduleQueryIntegrationTest` | console-api | retry_schedule 按 status/policy/relatedType 查询 |
| `BatchConsoleApiApplicationIT` | console-api | Spring 上下文加载 |

补充说明：

- 当前仓库实际共有 35 个集成测试；上表只列主链路代表用例
- 新增的 `batch-trigger` 首批门禁已包含 `TriggerServiceIntegrationIT`
- `batch-orchestrator`、`batch-worker-import`、`batch-worker-export`、`batch-worker-dispatch` 已补 `ShedLockConfigurationIT`

### 测试数据脚本

**Seed SQL（`batch-orchestrator/src/test/resources/db/testdata/`）**

| 文件 | 内容 |
|---|---|
| `job-instance-seed.sql` | 批量调度作业定义（IMPORT/EXPORT/DISPATCH × t1） |
| `worker-registry-seed.sql` | Worker 注册（ONLINE × 3 + DRAINING × 1，t1） |
| `quota-policy-seed.sql` | 租户配额策略（NONE/SLIDING_WINDOW/CALENDAR_DAY，t1） |
| `import-template-config-seed.sql` | 导入模板完整矩阵（CSV/Pipe/Tab/FW/Excel/XML/JSON-array/JSON-envelope/AES-encrypted/GZIP-pipe，t1） |
| `export-template-config-seed.sql` | 导出模板完整矩阵（CSV/Pipe/Tab-noheader/FW/Excel/JSON/AES+GZIP，t1） |
| `dispatch-channel-config-seed.sql` | 渠道配置（SFTP×2/API-push/API-pull/EMAIL/NAS/OSS/LOCAL，t1） |
| `multi-tenant-seed.sql` | 多租户扩展（t2 finance + t3 risk，含作业定义/配额/Worker/模板） |

**Fixture 文件（`batch-worker-import/src/test/resources/fixtures/`）**

| 文件 | 说明 |
|---|---|
| `import-customers.csv` | 逗号 CSV，10 条数据行，含中文 remark |
| `import-customers-pipe.csv` | Pipe 分隔，5 条 |
| `import-customers-tab.tsv` | Tab 分隔，5 条 |
| `import-customers.xml` | XML 格式（`<customers><record>…</record></customers>`），5 条 |
| `import-customers.txt` | FIXED_WIDTH 格式，100 字符每行，5 条 |
| `import-customers-array.json` | JSON 数组格式，5 条 |
| `import-customers-envelope.json` | JSON `{"records":[...]}` envelope，5 条 |
| `import-customers-bad-records.csv` | 含 8 类错误行（缺 customerNo/缺 name/非法 customerType/非法金额/非法状态/非法日期/非法 email/多余列）+ 2 条合法行 |
| `import-customers-utf8bom.csv` | UTF-8 BOM（EF BB BF）前缀，3 条数据 |
| `test-rsa-public.pem` | 2048-bit RSA 公钥（PEM，SubjectPublicKeyInfo 格式） |
| `test-rsa-private-pkcs8.pem` | 对应私钥（PKCS#8 PEM，仅测试用，不含生产密钥） |

**测试工具类**

- `TestExcelFileBuilder`（worker-export 和 worker-import 各一份）：基于 POI 动态构建 `.xlsx` 字节，避免提交二进制 fixture 文件；提供 `customerImport(List<Map>)` 和 `singleRow()` 快捷工厂方法

### 基础设施

- `AbstractIntegrationTest`：2×PostgreSQL + Kafka + MinIO（Testcontainers）
- `@BatchIntegrationTest`：`@Tag("integration") + @ActiveProfiles("test") + @Testcontainers`
- `OrchestratorWireMockSupport`：Worker IT 测试用 WireMock 存根 `/internal/**`

---

## 一句话总结

核心业务链路（调度主链、DAG、文件处理三链路、安全治理）已全部落地且编译通过。
对话_5（12 轮）完成：Worker 循环模板、Stage 异常契约、Outbox E2E、SQL 原子保护、三链路失败 E2E、多租户并发 E2E、HTTP 韧性、SQL CI 守卫、配置基线模块化、ADR 体系、产物验收 Verifier 框架 + Micrometer 指标。
2026-03-26 完成：可扩展性 & 高可用 6 项改造（乐观锁 / Kafka 并发 / 连接池隔离 / 优雅关闭 / 背压 / 临时文件清理）。
**当前测试体系（截至 2026-03-27）**：**67 单元** + **35 集成** + **13 E2E**。统一回归入口 `scripts/ci/run-full-regression.sh` 与 deploy smoke 已落地；live rollout / readiness 仍需真实 staging kube context 实跑留档。覆盖率口径待下一轮 JaCoCo / coverage 报告回填。

**未完成项（截至 2026-03-27）**：

| 优先级 | 项目 |
|---|---|
| 🟡 上线前补全 | 压测容量基线数据（脚本已有，需在 staging 环境实测并填写基线记录表） |
| 🟢 已完成 | Helm Chart / K8s 生产清单（`helm/batch-platform/` + `helm/values-prod.yaml`） |
| 🟢 已完成 | 可扩展性 & 高可用 6 项改造（详见 `scalability-ha-assessment.md`） |
| ⚪ 按需推进 | Quartz JDBC 集群模式（彻底消除触发器重复规划，`QRTZ_*` 表已存在） |
| ⚪ 按需推进 | Kafka lag 驱动 HPA（KEDA 或自定义 metrics，比 CPU 更精准） |
| 🟢 已完成 | ELK / OpenTelemetry 生产侧采集管道（OTEL Collector + Jaeger + Loki） |
