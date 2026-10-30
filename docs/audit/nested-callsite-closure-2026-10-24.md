# 嵌套调用可读性全量复核与集中整改（2026-10-24）

## 任务与结果

原始范围：配对仓库生产代码中阅读收益明确的嵌套方法/构造调用；保持语义，保留普通 receiver/fluent 链、简单 getter、直接 DTO 映射、声明式配置和短工厂。核对方式按用户要求限定为源码、AST、差异和求值顺序，不执行项目编译或测试。

本次全量候选分类中确认需要提取的剩余项已集中处理，未留下已确认的待整改项。新增改动覆盖后端仓库 55 个源码文件（51 Java、1 Python、1 TypeScript、2 Rust）和前端 10 个源码文件。保留项仍有 AST 命中，这些位置不作为待整改数量。

工作树与分支：

- 后端：`~/.codex/worktrees/workflow-daily-integration/file-batch-system`，`feature/workflow-daily-integration`，HEAD `ee01e97515b0`。
- 前端：`~/.codex/worktrees/workflow-daily-integration/batch-console`，`feature/workflow-daily-integration`，HEAD `662d254e2f8b`。
- 本次没有创建分支/工作树，没有操作 main，也没有暂存、提交或清理既有改动。表内记录基于本轮开始前的独立源码副本对比，不能把当前工作树其他差异归入本轮。

## 全量口径与旧统计更正

以 `git ls-files --cached --others --exclude-standard -z` 去重后的现存源码为输入；Java 仅取 `src/main/java`，排除 `tmp/target/build/logs/node_modules`；前端取手写 `src` 的 TS/TSX/Vue，排除生成类型、声明文件和 test/spec。SDK 使用各语言的 src/非测试 Go 文件；Rust 候选定位排除末尾 `#[cfg(test)]` 测试模块。Java examples 与 test-support 的 main 源码参与定位，测试源码没有改动。

逐文件覆盖、源码 SHA256、嵌套/深层/模板位置数、本轮是否整改及保留类别，见 [完整覆盖清单](nested-callsite-coverage-2026-10-24.csv)。本轮共覆盖 3441 个源码文件。

此前仓库根目录扫描把 `tmp/` 历史快照混入 Java 文件数，不能再以那个计数说明当前生产源码。此前前端“55 处”使用的临时 AST 遍历还存在回调返回值截断遍历的问题，且与本轮实参口径不一致；该结论被本报告覆盖。仓库现有调用门禁的遍历实现未受此临时工具问题影响。

Java AST 同时定位方法参数、构造参数与复杂 return；访问每个回调体，但嵌套深度不跨 lambda/匿名类边界，也不沿 receiver 链累计。位置可因父子表达式重复，不等同独立业务调用或缺陷。前端脚本按所有实参扫描，Vue template 独立提取表达式，不将 JSX/VNode/响应式声明当作业务转换。

### Java 模块覆盖

| 模块 | 源码文件 | 嵌套表达式位置 | 深度至少 3 |
|---|---:|---:|---:|
| batch-common | 358 | 756 | 30 |
| batch-console-api | 1083 | 9211 | 703 |
| batch-orchestrator | 715 | 8151 | 220 |
| batch-test-support | 20 | 103 | 19 |
| batch-trigger | 112 | 935 | 12 |
| batch-worker/atomic | 28 | 288 | 14 |
| batch-worker/core | 126 | 971 | 14 |
| batch-worker/dispatch | 78 | 746 | 16 |
| batch-worker/export | 57 | 736 | 33 |
| batch-worker/import | 74 | 1078 | 16 |
| batch-worker/process | 53 | 345 | 1 |
| examples/self-hosted-sdk/sample-tenant-worker-java | 6 | 58 | 5 |
| examples/self-hosted-sdk/sample-tenant-worker-java-spring | 3 | 5 | 0 |
| examples/task-spi-plugin/file-checksum-executor | 1 | 4 | 0 |
| examples/task-spi-plugin/sftp-push-executor | 1 | 12 | 0 |
| sdk/java/core | 86 | 453 | 4 |
| sdk/java/spring | 3 | 0 | 0 |
| sdk/java/testkit | 5 | 30 | 1 |
| security-scan | 9 | 55 | 2 |

### 配对前端及其他语言

| 范围 | 文件/表达式 | 当前候选与结论 |
|---|---|---|
| 前端 TS/TSX/Vue script | 503 文件、501 script block | 1,100 个实参位置；深度至少 3 为 0；其余按下方保留类别分类 |
| Vue template | 239 文件、11,557 个表达式 | 12 个嵌套位置；均为状态/样式映射、Boolean/String 归一或本地化插值 |
| TypeScript SDK | 17 文件 | 26 个实参位置；签名时钟采样提取，其余为配置/协议值映射、回调配置和日志 |
| Python SDK | 68 文件 | 123 个实参位置；UTF-8 解码与 JSON 解析分步，其余为协程包装、标准容器/值转换、结果构造、日志和可注入时钟预算 |
| Go/Rust SDK | 17 Go、18 Rust 文件 | 词法定位 130 个候选（含类型/属性噪声），按源码复核；Rust 执行结果归一与 HMAC key hash 分步，其余为 options、标准 tagged value、错误/结果工厂、头构造与时间/限流算法原语 |

Go/Rust 定位使用词法清单配合源码阅读，不报告成全量 AST 或类型检查。Rust 本轮修改的两个文件另经 rustfmt 解析，格式边界见验证表。

## 本轮整改与语义核对

| 领域 | 显式化的阶段 | 保持的语义 |
|---|---|---|
| Common 冻结快照/脱敏 | 递归复制、标量校验/脱敏、collection 写入 | 原分支、迭代顺序、每项转换一次、不可变包装时点均保持；未合并各分支写入 |
| Console 查询与导出 | 查询→渲染/Excel；下载→写流；Kafka group 查询→收集 | 查询/异常仍在原 try 范围内，一次请求的查询次数、stream 写入与 fallback 不变 |
| Console 鉴权与审计 | tenant/menu/capability/password 画像→响应；session version→token；decoder→cache；redaction→JSON；command→audit record | 各调用仍按原左到右次序；decoder 仍在 encoder 发布后构造；审计与业务返回/回滚位置不变 |
| 配置复制/导入 | 双侧 spec 归一→比较；impact/error-row 聚合→响应；JSON parse→输入拒绝 | 两侧转换各一次；source fileName 先读；非法配置错误与异常 catch 不变 |
| Orchestrator | cross-day snapshot/merge→校验/结果；实例 summary→进度 CAS；成功输出聚合→版本写入；租约第二次 claim→是否成功 | 先读取原 mapper，builder 字段按原顺序赋值；summary 在原位置求值，CAS/retry/事务及异常边界不变 |
| Lineage/Forensic | DTO/coverage 生成→响应；dataset→收集；JSON 编码→ZIP entry | row 排序、去重、序列化/写入顺序、ZIP entry 名和内容保持 |
| Trigger | occurrence 状态回读→MISFIRE_PENDING 判定 | 只在 update 未成功分支读一次状态，原返回逻辑保持 |
| Worker | frozen channel 解密→返回/归档；dispatch metadata→状态写入；charset 文本化→解析；JSON record→chunk；文件 hash→属性写入；generation UUID 校验→SQL 参数 | 每个原 guard 下的重复文本化/UUID 读取均按原次数保留；checksum 原 catch、ACK/retry 分支与 RLS 校验点保持 |
| Java SDK | 输出 message→map→result；checkpoint hook→commit；cross-day 解析/冻结→结果 collection | successMessage 在 outputMap 前；checkpoint hook 的动态分派、重复调用次数、recordBatch/commit/clear/markCompleted 顺序保持 |
| 非 Java SDK | Rust execute→stopped mapping、长 HMAC key→digest→block copy；TS clock sample→text；Python decode→JSON | 单次 handler/clock/hash、字节编码、错误映射、contextlib suppress 和 report 时点保持 |
| 前端 | Outbox fetch→统计解析；maintenance fetch→state；PEM→key import；definition↔graph→store/JSON；preview payload→API；draft→JSON；recent title path→label | await/catch/finally、Pinia action 顺序保持；先取 edges receiver 再 createEdge，displayPath 两次调用未合并；表单/租户字段仍在原调用点读取 |

### 本轮源码文件清单

这里只列本轮独立差异，不把先前累计重构重复计入。完整源码覆盖与保留项见 CSV。

| 仓库 | 文件 | 本轮差异起始行 |
|---|---|---|
| backend | [batch-common/src/main/java/io/github/pinpols/batch/common/dto/FrozenDispatchChannelSnapshot.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/dto/FrozenDispatchChannelSnapshot.java) | 116, 119, 122, 133, 136, 139 |
| backend | [batch-common/src/main/java/io/github/pinpols/batch/common/dto/FrozenFileTemplateSnapshot.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/dto/FrozenFileTemplateSnapshot.java) | 112, 124 |
| backend | [batch-common/src/main/java/io/github/pinpols/batch/common/utils/SecretMasking.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/utils/SecretMasking.java) | 82, 299, 309, 392 |
| backend | [batch-console-api/src/main/java/io/github/pinpols/batch/console/application/audit/ConsoleAiTools.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/application/audit/ConsoleAiTools.java) | 194, 202 |
| backend | [batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/ConsoleClusterDiagnosticService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/ConsoleClusterDiagnosticService.java) | 139 |
| backend | [batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/ConsoleOrchestratorPort.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/ConsoleOrchestratorPort.java) | 139 |
| backend | [batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/infrastructure/DefaultConsoleKafkaLagQueryService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/infrastructure/DefaultConsoleKafkaLagQueryService.java) | 76 |
| backend | [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/audit/infrastructure/ai/DefaultConsoleAiApplicationService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/audit/infrastructure/ai/DefaultConsoleAiApplicationService.java) | 542, 828, 933 |
| backend | [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/audit/support/AuditAspect.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/audit/support/AuditAspect.java) | 3, 273 |
| backend | [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/application/contract/response/ConsoleFileProjectionMapper.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/application/contract/response/ConsoleFileProjectionMapper.java) | 53 |
| backend | [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/service/TwilioSmsProvider.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/service/TwilioSmsProvider.java) | 166 |
| backend | [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/observability/infrastructure/DefaultConsoleReportExcelApplicationService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/observability/infrastructure/DefaultConsoleReportExcelApplicationService.java) | 77, 82, 89, 94, 101, 106 |
| backend | [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/service/ConsoleAuthApplicationService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/service/ConsoleAuthApplicationService.java) | 94, 99 |
| backend | [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/support/ConsoleJwtService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/support/ConsoleJwtService.java) | 193, 219 |
| backend | [batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleConfigApplicationService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleConfigApplicationService.java) | 517 |
| backend | [batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleTenantConfigCopyService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleTenantConfigCopyService.java) | 351, 359, 1001 |
| backend | [batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultTenantConfigPackageExcelService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultTenantConfigPackageExcelService.java) | 797, 802, 808 |
| backend | [batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigInitTenantExecutor.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigInitTenantExecutor.java) | 76 |
| backend | [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/engine/VerifierFailureOutboxService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/engine/VerifierFailureOutboxService.java) | 80 |
| backend | [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/forensic/ForensicExportService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/forensic/ForensicExportService.java) | 193, 214, 236 |
| backend | [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultCompensationService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultCompensationService.java) | 294 |
| backend | [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/lineage/LineageEvidenceService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/lineage/LineageEvidenceService.java) | 111, 114 |
| backend | [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/WorkflowRunReplaySnapshotResolver.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/WorkflowRunReplaySnapshotResolver.java) | 123 |
| backend | [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/DefaultTaskAssignmentService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/DefaultTaskAssignmentService.java) | 690 |
| backend | [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/TaskOutcomeInstanceProgressor.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/TaskOutcomeInstanceProgressor.java) | 25, 191, 200 |
| backend | [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/TaskOutcomeTerminalFinalizer.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/TaskOutcomeTerminalFinalizer.java) | 14, 75 |
| backend | [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowNodeDispatchService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowNodeDispatchService.java) | 469 |
| backend | [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/archive/ArchiveSchemaDriftCheck.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/archive/ArchiveSchemaDriftCheck.java) | 120 |
| backend | [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/lineage/OpenLineageEmitter.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/lineage/OpenLineageEmitter.java) | 227 |
| backend | [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DispatchChannelSnapshotService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DispatchChannelSnapshotService.java) | 133 |
| backend | [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/JobDependencyWaitingLaunchService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/JobDependencyWaitingLaunchService.java) | 361, 367 |
| backend | [batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/ScheduledOccurrenceRecorder.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/ScheduledOccurrenceRecorder.java) | 221 |
| backend | [batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/FrozenDispatchChannelResolver.java](../../batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/FrozenDispatchChannelResolver.java) | 44 |
| backend | [batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/stage/AckDispatchStep.java](../../batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/stage/AckDispatchStep.java) | 98, 109 |
| backend | [batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/GenerateStep.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/GenerateStep.java) | 347 |
| backend | [batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/StoreStep.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/StoreStep.java) | 363 |
| backend | [batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/plugin/GenericJdbcMappedImportLoadPlugin.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/plugin/GenericJdbcMappedImportLoadPlugin.java) | 494 |
| backend | [batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/LoadStep.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/LoadStep.java) | 260 |
| backend | [batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ParseStep.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/ParseStep.java) | 514 |
| backend | [batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/PreprocessStep.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/PreprocessStep.java) | 317, 322 |
| backend | [batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputePlugin.java](../../batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputePlugin.java) | 171, 220, 308, 370, 431, 610, 785 |
| backend | [batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/stage/DefaultProcessStageExecutor.java](../../batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/stage/DefaultProcessStageExecutor.java) | 156 |
| backend | [sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/typed/SdkAbstractTypedDispatchHandler.java](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/typed/SdkAbstractTypedDispatchHandler.java) | 10, 94 |
| backend | [sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/typed/SdkAbstractTypedExportHandler.java](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/typed/SdkAbstractTypedExportHandler.java) | 5, 115, 123, 126 |
| backend | [sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/typed/SdkAbstractTypedImportHandler.java](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/typed/SdkAbstractTypedImportHandler.java) | 159 |
| backend | [sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/typed/SdkAbstractTypedProcessHandler.java](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/typed/SdkAbstractTypedProcessHandler.java) | 123, 133 |
| backend | [sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/typed/SdkAbstractTypedRowStreamHandler.java](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/typed/SdkAbstractTypedRowStreamHandler.java) | 46 |
| backend | [sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/typed/SdkTypedTaskHandler.java](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/typed/SdkTypedTaskHandler.java) | 7, 71 |
| backend | [sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/SdkCrossDayResult.java](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/SdkCrossDayResult.java) | 36, 50, 57 |
| backend | [sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/SdkTaskContext.java](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/SdkTaskContext.java) | 103 |
| backend | [sdk/java/testkit/src/main/java/io/github/pinpols/batch/sdk/testkit/FakeBatchPlatform.java](../../sdk/java/testkit/src/main/java/io/github/pinpols/batch/sdk/testkit/FakeBatchPlatform.java) | 219, 231 |
| backend | [sdk/python/src/batch_worker_sdk/testkit/fake_platform.py](../../sdk/python/src/batch_worker_sdk/testkit/fake_platform.py) | 247 |
| backend | [sdk/rust/src/client/signing.rs](../../sdk/rust/src/client/signing.rs) | 182 |
| backend | [sdk/rust/src/client/testkit.rs](../../sdk/rust/src/client/testkit.rs) | 167 |
| backend | [sdk/typescript/src/client/signing.ts](../../sdk/typescript/src/client/signing.ts) | 84 |
| frontend | [src/components/common/CommandPalette.vue](../../../batch-console/src/components/common/CommandPalette.vue) | 215 |
| frontend | [src/utils/loginCrypto.ts](../../../batch-console/src/utils/loginCrypto.ts) | 80, 83 |
| frontend | [src/views/observability/OutboxList.vue](../../../batch-console/src/views/observability/OutboxList.vue) | 582 |
| frontend | [src/views/system/AtomicTaskTypeCenter.vue](../../../batch-console/src/views/system/AtomicTaskTypeCenter.vue) | 330 |
| frontend | [src/views/system/MaintenanceSettings.vue](../../../batch-console/src/views/system/MaintenanceSettings.vue) | 190 |
| frontend | [src/views/system/components/TenantCopyConfigDialog.vue](../../../batch-console/src/views/system/components/TenantCopyConfigDialog.vue) | 426, 438 |
| frontend | [src/views/workflow/designer/WorkflowDesigner.vue](../../../batch-console/src/views/workflow/designer/WorkflowDesigner.vue) | 310 |
| frontend | [src/views/workflow/designer/store/useDesignerStore.ts](../../../batch-console/src/views/workflow/designer/store/useDesignerStore.ts) | 262 |
| frontend | [src/views/workflow/designer/templates/TemplateLibrary.vue](../../../batch-console/src/views/workflow/designer/templates/TemplateLibrary.vue) | 48 |
| frontend | [src/views/workflow/designer/toolbar/JsonSyncPanel.vue](../../../batch-console/src/views/workflow/designer/toolbar/JsonSyncPanel.vue) | 60, 93 |

## 保留项分类与剩余边界

| 分类 | 典型位置 | 保留理由 |
|---|---|---|
| 简单字段/DTO 投影 | request/record getter、ResultSet 读值、Map 字段默认值、claim/lease DTO | 数据来源直接，单步读取或映射已清楚；不为括号数新增变量 |
| 声明式配置/短工厂 | Map.entry/EnumSet 状态表、参数 binding map、Spring/Security/Kafka 配置、类型令牌、短 typed result factory | 相邻字段共同表达配置；拆散后难以对照声明 |
| 边界包装 | Optional、CommonResponse、短 service/port facade、charset/Boolean/String 默认值 | 具名方法本身已表达一个适配动作；没有多阶段数据来源混杂 |
| 日志/显示 | safeDisplay、异常摘要、翻译插值、日期/数值格式、样式状态 projection | 就地单步呈现；不把展示细节提前到业务 guard 前 |
| 回调/异步结构 | RLS/transaction callback、Vue Query computed/queryFn、Promise/asyncio 包装、调度 options、onError | 生命周期与异常/同步 throw 处理由框架包装表达；不提升惰性回调内计算 |
| 算法与协议原语 | clamp、enum tag、byte copy、比较器、padding/签名字段、expiry helper | 标准算法表达清楚；保留已有命名边界与取样次数 |
| 可覆盖 provider | `AbstractTaskConsumer.workerConfiguration()` | 获取本身可以被覆盖；保持原调用次数/位置，不集中缓存 |

旧记录中 `batch-worker/core` 的 956 个深度 2 位置已按上述字段投影、SQL 参数绑定、配置/框架委托、消息元数据与短工厂分类；不是 956 个待修缺陷。当前 core 共 971 个重叠位置（其中深度至少 3 为 14），其余位置不列为整改项。

当前确认清单的待整改项为 0；普通链调用、测试源码、生成类型和与此任务无关的 FQN/参数对象/架构治理不扩入本次。这个结论是当前源码快照的可读性分类结论，不宣称编译、业务测试、CI 或运行验收通过。

## 实际验证

| 核对 | 结果/边界 |
|---|---|
| Git 分支/工作树 | 两仓均为现有专用工作树 `feature/workflow-daily-integration` |
| Java JDK AST parse-only | 2,818 个 main 源文件，parse errors 0；按 Git 清单排除历史快照/生成目录 |
| Java 本轮调用序列对照 | 51 个修改文件的 986 个方法记录，按 receiver/实参求值后记录方法名与参数数；前后序列逐记录相同。结合源代码检查 guard、try/catch、循环、重复 getter/时间/provider 调用，不将序列相同单独当作行为证明 |
| Java 新局部变量 | parse-only 作用域候选与改前对照，无新增重复声明；原有 pattern-variable flow-scope 误报不作为本次问题 |
| 前端/TS SDK AST | 前端 503 文件/501 script block 与 SDK 17 文件均解析无错误；本轮 11 个 TS/Vue 文件静态调用序列相同，无新增局部重复声明 |
| Vue template | 239 文件、11,557 表达式全量提取，未解析表达式 0；12 个命中按显示/状态/本地化类别保留 |
| Python AST | 68 个源码解析无错误；decode/loads 仍在同一 suppress 中，先 UTF-8 后 JSON |
| Rust rustfmt | 两个修改文件可解析；`rustfmt --check --edition 2021` 因 signing.rs 第 40 行原有 format! 参数排版返回非零，改前副本复跑输出同一差异；本轮新增行未增加该差异，也未改写这段既有格式 |
| `git diff --check` | 本轮后端/前端源码与文档目标通过；不把整个累计工作树当作本轮独立差异 |
| 项目编译/类型检查/测试/服务验收 | 按用户要求均未执行；没有运行 Maven/Cargo/Go/npm 构建或任何测试 |

## 后续复合判断阶段与证据归属

本报告保留 65 个生产源码文件的嵌套调用整改归属。随后同一工作树另完成[复合判断集中整改](compound-condition-readability-2026-10-24.md)，该阶段 197 个源码文件，与本阶段去重后共 246 个。覆盖 CSV 的 SHA-256 已刷新至后续阶段当前源码，嵌套数量、候选分类与 65 文件归属保持本阶段口径；后续条件命名及日志整改由各自台账说明。

[用户 19 项治理](../plans/development-worktree-governance-2026-10-24.md)尚未整体完成；本报告的“确认项完成”仅指上述生产嵌套表达式范围，不包含全部测试嵌套表达式、数据库重建、所有配置或事务审查。

### 后续模型阶段的快照归属

随后实施[实体与构造治理](entity-and-constructor-governance-2026-10-24.md)，本报告配套CSV的源码SHA刷新至该阶段；嵌套位置/条件计数仍属于本报告原阶段，不作为后续当前代码的新计数。Builder声明式组装及受控工厂按原规则分类，不能据此推导全部19项完成。
