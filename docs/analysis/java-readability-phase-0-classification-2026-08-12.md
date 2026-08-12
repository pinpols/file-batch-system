# Java 可读性治理阶段 0 分类总账（2026-08-12）

> 对应路线图：[Java 可读性与结构一致性治理路线图](../plans/java-readability-refactoring-roadmap-2026-08-12.md)。
>
> 机器快照：[Java 可读性治理扫描快照](./java-readability-inventory-2026-08-12.md)。本文件记录人工裁定、验证所有权和实施顺序，不由脚本覆盖。

## 1. 裁定口径

| 裁定 | 含义 | 后续动作 |
|---|---|---|
| 必须改 | 固定字段契约、隐藏代理边界或职责混合已经增加漂移风险 | 纳入阶段 1～3，独立 PR 完成并验证 |
| 经过即改 | 当前可工作，只有在相邻功能变更或调用链类型化时改造才有正收益 | 不单独制造大范围 churn，进入对应批次时处理 |
| 合理保留 | 动态结构、声明式规格、框架边界或测试 fixture | 登记理由，不以数量归零为目标 |

## 2. 阶段 1 候选

| 候选 | 裁定 | 验证所有权 |
|---|---|---|
| 命令/上下文 inline 构造 | 必须改，生产调用现场已由 `PositionalArgsConventionTest` 和 review 规则守护 | 各模块单测 + PMD/Spotless |
| 47 个 `@Configuration` 声明 | 逐类核对；不存在 bean 方法互调的类改为 `proxyBeanMethods = false` | Spring context/配置类单测 + 编译 |
| 13 个大于等于 700 行的类 | 经过即改；阶段 1 只补职责/WHY 注释，不拆类 | 模块单测，复杂类拆分留阶段 4 |
| `ConfigPackageSheetSpecs` | 合理保留；声明式 sheet 规格表，行数不是职责混合 | Excel 配置包测试 |
| 26 个宽参数 PMD 例外 | 经过即改；DTO/entity/mapper 映射优先保留，服务调用参数在相邻改动时改 Command/Context | PMD + 对应模块测试 |
| 215 个 suppression | 阶段 1 不机械清理，留阶段 6 逐项核对 | PMD + 编译 |

## 3. 阶段 2 自注入事务/AOP 所有权

| 类 | 代理语义 | 裁定 | 主要验证 |
|---|---|---|---|
| `ApiKeyVerifier` | `@Async` touch/legacy hash upgrade | 必须改为异步协作者 | `ApiKeyVerifierTest`、`InternalAuthFilterTest` |
| `QuotaRuntimeStateSnapshotScheduler` | 每条快照独立事务 | 必须改为事务写入协作者 | `QuotaRuntimeStateSnapshotSchedulerTest` |
| `SensorPollScheduler` | `REQUIRES_NEW` + `FOR UPDATE SKIP LOCKED` | 必须改；先补直接测试 | 新增 scheduler 事务协作测试 |
| `BatchDayReplayDispatcher` | entry 级 `REQUIRES_NEW`，单条失败隔离 | 必须改为 entry executor | `BatchDayReplayDispatcherTest` |
| `DefaultWorkerRegistryService` | heartbeat 内 register、deactivate 内 updateStatus 的代理调用 | 必须改；先确认是否真的需要跨代理 | `DefaultWorkerRegistryServiceTest` |
| `TaskDispatchOutboxService` | `MANDATORY` 重载间调用 | 必须消除自注入；公共实现收敛到单一事务入口 | `TaskDispatchOutboxService*Test`、`RequiresNewTransactionBoundaryIntegrationTest` |
| `DefaultCompensationService` | 多个 `REQUIRES_NEW` + handler 成功事务 | 必须改为显式事务执行器，高风险 | `DefaultCompensationServiceTest`、补偿 IT/sim |
| `TaskControllerApplicationService` | 批量 report 每项触发 `@Retryable` | 必须改为 report executor | `TaskControllerApplicationServiceTest`、`TaskBatchClaimReportIntegrationTest` |
| `DefaultWorkflowNodeDispatchService` | 递归节点派发进入事务代理 | 必须改；先锁定递归事务语义 | 三组 workflow dispatch 测试 + workflow E2E |
| `DefaultTaskOutcomeService` | outcome、node run start/finish 的事务重入 | 必须改为窄事务协作者，高风险 | 两组 outcome 单测、`PartitionJoinPromotionIntegrationTest`、关键 E2E/sim |
| `DefaultConsoleTenantConfigInitApplicationService` | tenant 级初始化事务与 strict 回滚 | 必须改为 tenant transaction executor | `DefaultConsoleTenantConfigInitApplicationServiceTest` + 真 PG IT |
| `TenantConfigInitApplyHandlers` | workflow/pipeline 定义事务写入 | 必须改为 config write executor | tenant config 初始化测试 + Excel 配置包 IT |

实施顺序遵循“低扇出异步/调度 → Console 初始化 → outbox/补偿 → task/workflow/outcome”。任何一项传播级别、回滚范围或锁持有时间无法由测试证明时，不与下一项合并。

## 4. 阶段 3 Public Map 契约分类

机器快照共识别 149 个 public Map 方法。以下按文件归类；具体方法和行号以机器快照为准。

### 4.1 必须类型化

这些类返回固定字段的 API、应用服务结果、数据库投影或 transport 响应，Map 会把字段错误推迟到运行期：

| 批次 | 文件/职责 | 验证 |
|---|---|---|
| Console 查询与诊断 | `DefaultConsoleQueryApplicationService`、`ConsoleClusterDiagnosticService`、`ConsoleKafkaLagQueryService` | Jackson 契约、Controller 测试、OpenAPI、前端 codegen |
| Console 文件 | `DefaultConsoleFileApplicationService`、`DefaultConsoleFileChannelApplicationService`、`DefaultConsoleFileTemplateApplicationService`、`ConsoleFileQueryService` | 文件 API 测试、OpenAPI、前端 smoke |
| Console 通知/配置 | `DefaultConsoleNotificationApplicationService`、`DefaultConsoleAlertRoutingApplicationService`、`DefaultConsoleConfigApprovalApplicationService` | 通知/审批测试、OpenAPI、前端 smoke |
| Console 运维代理 | `DefaultConsoleOrchestratorProxyService` | 代理契约测试、orchestrator internal OpenAPI |
| Orchestrator 操作接口 | `InstanceManagementApplicationService`、`WorkflowRunManagementApplicationService` 及对应 Controller | Controller/应用服务测试、内部 OpenAPI、worker/console 调用方 |
| Orchestrator 状态接口 | `OrchestratorDrainController`、`OrchestratorGracefulShutdown`、`TriggerGracefulShutdown`、`AtomicRuntimeStatus` | Actuator/Controller 测试、容器健康检查 |
| 文件治理与 lineage | `DefaultFileGovernanceService`、`LineageEvidenceService`、`FileGovernanceController`、`FileGovernanceScheduler`、`FileGovernanceMetricsCacheService` | 文件治理 IT、对象存储 sim、内部 OpenAPI |
| 固定数据库投影 | `FileGovernanceRepository`、`PlatformFileRuntimeRepository`、`FileDispatchRepository`、`DispatchChannelHealthRepository` | mapper/真 PG IT、import/export/dispatch E2E |
| Java SDK 固定 transport | `PlatformHttpClient` 的 register/heartbeat/deactivate/claim/report/renew 响应 | SDK contract、live transport、Java testkit |

### 4.2 合理保留 Map

| 边界 | 文件/职责 | 保留原因 |
|---|---|---|
| 通用动态工具 | `BatchKafkaProducerSupport`、`RunModeSupport`、`CursorCodec`、`JsonUtils`、`SecretMasking` | Kafka properties、运行参数、游标载荷和 JSON 本身就是动态键值 |
| 状态/诊断扩展 | `BatchRuntimeStatusEndpoint` | Actuator extension point 允许按组件动态聚合；不作为业务 API DTO |
| Console 兼容/工具 | `ConsoleJobOpsSupport.parsePayload`、`ConfigPackageExcelSchema.toExportRow`、`ConsoleQuerySupport`、`ConfigChangeLogBuilder`、`ConsoleMapSupport`、`ConsoleResponseFieldReader` | JSON/Excel 行、审计详情或兼容转换边界；不得继续向固定 API 扩散 |
| 运行参数/载荷 | `PartitionDispatchService.effectiveParams`、`WorkflowNodePayloadBuilder`、`MapJsonbTypeHandler` | job params、workflow payload、JSONB 为用户/插件可扩展结构 |
| worker 动态数据 | `ChannelConfigMerge`、`ExportConfigValueSupport`、两类 export data plugin、`ValidationConfigSupport`、`ValidationRuleSetMerger`、`ParseSupport` | 渠道配置、业务数据行、校验 DSL、解析 hints 字段不固定 |
| SDK 业务扩展 | `TaskDispatcher.progressSnapshot`、`SdkRowResult`、`SdkTypedParameters.toOutputMap`、`ProgressReporter`、`SdkTaskStoppedException.breakPosition` | progress/output/checkpoint 是租户 handler 扩展载荷 |
| Testkit | `FakeBatchPlatform.registrations` | 测试 fixture，可保留 Map 以检查 raw wire |

### 4.3 调用链核对后处理

| 文件 | 当前判断 | 决策条件 |
|---|---|---|
| `DefaultFileGovernanceService.createUploadSession` | 返回字段固定，随文件治理批次类型化 | 先确认 Console 与 worker 两个调用方 wire 是否相同 |
| `FileGovernanceRepository` | 多数查询列固定，应拆 typed projection；`operationDetail` 可能含动态 evidence | 固定列类型化，动态 evidence 留在具名字段 Map 内 |
| `FileGovernanceMetricsCacheService` | 外层 latency 指标固定、缓存载荷当前为 JSON Map | 对外 typed，缓存序列化边界可保留 Map 或改专用 snapshot |
| `PlatformFileRuntimeRepository` | 当前兼容 facade 聚合多个 repository | typed view 应在实际子 repository 定义，facade 只保留必要兼容重载 |
| `FileDispatchRepository` | 文件/渠道/dispatch/receipt poll 都是固定投影 | 按四个 view 分拆，不建立一个万能 DTO |

## 5. 大类裁定

| 裁定 | 类 |
|---|---|
| 阶段 4 优先 | `DefaultTaskOutcomeService`、`PreprocessStep`、Java SDK `TaskDispatcher`、`DefaultRetryGovernanceService`、`AbstractTaskConsumer`、`DefaultConsoleWorkflowDefinitionApplicationService`、`AbstractExportFormat` |
| 经过即改 | `ImportIngressScanner`、`RemoteFilesystemDispatchSupport`、`ConfigPackageExcelValidator`、`WorkflowGraphValidator`、`SqlTransformComputePlugin` |
| 合理保留 | `ConfigPackageSheetSpecs` |

## 6. 阶段 0 完成证据

- 运行 `python3 scripts/ci/report-java-readability-inventory.py --check docs/analysis/java-readability-inventory-2026-08-12.md`，证明机器快照与源码一致。
- 运行 `python3 scripts/ci/check-java-readability.py`、PMD 和 Spotless，证明既有硬规则不退化。
- 阶段 1～3 每次合并后重新生成机器快照；只有自注入归零、固定 Map 清单完成且保留项理由仍成立，才能关闭对应阶段。
