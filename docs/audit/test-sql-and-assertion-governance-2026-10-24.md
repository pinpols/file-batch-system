# 测试 SQL、JDBC 断言与脚本枚举集中收尾（2026-10-24）

## 原目标与范围

本阶段继续[用户 19 项总体治理](../plans/development-worktree-governance-2026-10-24.md)，落实第 7、9、17、18 项相关的已确认源码缺口，不替代数据库、模型、配置、事务及运行时导入的后续全量审查。

执行位置仍为 `/Users/dengchao/.codex/worktrees/workflow-daily-integration/file-batch-system` 的 `feature/workflow-daily-integration`，HEAD `ee01e97515b0`；保留已有暂存、未暂存及未跟踪改动，未新建分支/工作树、未操作 main 或提交。

本阶段源码范围为 1376 个现存 Java 测试文件。相较上阶段 1374 文件，清单按当前 Git 跟踪与未跟踪状态刷新，不恢复删除文件，不扫描 tmp/target/build/node_modules。实际改动 **102 个 Java 测试文件**，另修正 1 个 Python 守卫、补 1 个回归文件源码。只解析、核对和运行静态扫描；未执行项目编译、类型检查、单元/集成/E2E 或守卫测试。

## 已实施

| 内容 | 数量与说明 |
|---|---|
| SQL 实参单独承接 | 24 处、5 文件：19 个字面量和 5 个批量构造器最终字符串；固定 SQL 使用 text block，所有调用传具名 SQL |
| JDBC 结果与断言分离 | 1504 处、99 文件：1378 个 `queryForObject`、51 个 `queryForList`、22 个 `queryForMap`、3 个 `query`、50 个 `update`；使用明确的标量、集合或受影响行数类型 |
| 延迟求值边界 | 上述 62 处位于 Awaitility 的表达式回调内，改为原回调中的局部变量加断言；没有把查询移到轮询之前 |
| E2E 普通业务写入 | SDK 重启子进程改用 `JdbcTemplate` 和单连接数据源；保留原数据库、SQL、四个字符串参数的绑定顺序及连接关闭范围 |
| FQN | 归档测试 Mapper 字段/构造注入类型改用已核实的导入；Flyway 测试复用已有 `assertThatThrownBy` 静态导入 |
| Shell 守卫默认枚举 | 保留 Git cached/others 两类输入，过滤工作树已不存在路径，修复默认入口的 FileNotFoundError，不改规则或豁免 |

逐项来源、类型、局部变量、求值位置和当前源码 SHA-256 见[1529 项清单](test-sql-and-assertion-governance-2026-10-24.csv)。定位行为阶段快照；变量名和文件 SHA 用于核对当前源码。102 文件是各项去重结果，不将 99、5、1 简单相加。

SQL 仅调整 Java 表达形式：新增 text block 用末行续行符保留原来不带末尾换行的字符串值。没有在 SQL 里改状态、筛选条件、参数绑定、返回列、数据或删除范围。批量 fixture 的动态 VALUES 仍由原循环构造，本阶段仅承接最终 SQL，不新增公共动态 SQL 框架。

JDBC 写入改造属于用户指定的测试基础设施统一：Spring JDBC 负责语句释放和异常转换；不将其异常包装形式与原生 JDBC 宣称为逐字等价。原 SQL/参数和持久化结果预期保持，成功标记仍在数据源关闭之后写入。原生连接保留于需固定会话身份、显式事务、锁/崩溃窗口的故障场景，不能将连接池换会话引入这些场景。

## 语义核对与静态证据

1. 24 处 SQL 提取的 5 文件，改前与改后的全部字符串字面量值及顺序逐项一致，包括空格、转义和末尾换行；JDK 解析错误 0。
2. 1504 处初次提取后，将新变量回填到原断言并移除新增导入，99 文件与改前源码完全一致。
3. 随后仅对新增声明及对应断言片段使用仓库配置的 Palantir Java Format 2.92.0、GOOGLE 风格整理；没有格式化完整原文件。两次片段整理均对照解析 AST 一致，不改字符串值。
4. 99 文件最终静态方法调用序列与改前一致；新增局部变量作用域候选与改前对照，无新增重名。当前类型根据原 JDBC 重载的 class 参数及 row mapper 返回值确定，保留 boxed 类型，未引入拆箱。
5. 回调 62 处均核实为 `untilAsserted` 的无返回值回调。新增变量在回调块内，每次轮询继续独立执行查询及断言，不改变异常触发和事务位置。
6. 重扫 1376 个测试文件：已识别的 JDBC 查询/写入嵌套断言候选 0，实际 SQL 字面量/最终构造调用实参候选 0。扩展扫描仍有 76 个非 SQL 表达式：45 个 Mockito 匹配器、30 个事务回调、1 个连接回调；保留其 DSL/生命周期，不将它们改成 SQL 变量。

上述 AST 与方法序列不是类型检查或行为测试，不能证明所有测试的 fixture、并发协议、隔离和断言独立性均已审查。

### 实际命令与边界

- `python3 scripts/ci/check-shell-linux-portability.py`：默认全量入口退出 0，原已删除索引路径不再中断；本轮未新增宽泛排除目录。
- `python3 scripts/ci/check-java-text-block-style.py <本阶段102个Java文件>`：退出 0。
- `python3 scripts/ci/check-comment-language.py --base-ref origin/main`：退出 0，说明性注释增量检查；历史 761 行不继承为当前完成数字。
- AST 解析/调用序列/字面量值/作用域及文件哈希核对：使用临时只读分析工具，不运行项目符号分析、注解处理或生成项目 class 文件。
- 补充 `scripts/ci/tests/test_check_shell_linux_portability.py` 的 3 个回归源码：删除路径与未跟踪覆盖、未跟踪违规仍失败、已有 stat fallback 放行。按用户约束**未执行这些测试**，只做 Python AST 解析。
- 其他本轮静态扫描的结果追加于本报告后续段落；未执行的门禁不写成通过。

## 逐文件台账

| 文件 | JDBC结果提取 | SQL实参提取 | 其他 |
|---|---:|---:|---|
| [batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/job/mapper/JobMonitoringPolicyMapperIntegrationTest.java](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/job/mapper/JobMonitoringPolicyMapperIntegrationTest.java#L180) | 1 | 0 |  |
| [batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/ConsoleAssetFreshnessPolicyReferenceIntegrationTest.java](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/ConsoleAssetFreshnessPolicyReferenceIntegrationTest.java#L55) | 2 | 0 |  |
| [batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/ConsoleCalendarMutationIntegrationTest.java](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/ConsoleCalendarMutationIntegrationTest.java#L128) | 1 | 0 |  |
| [batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/ConsoleJobDefinitionMutationIntegrationTest.java](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/ConsoleJobDefinitionMutationIntegrationTest.java#L790) | 8 | 0 |  |
| [batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/ConsoleTenantConfigPackageExcelUploadIntegrationTest.java](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/ConsoleTenantConfigPackageExcelUploadIntegrationTest.java#L575) | 41 | 0 |  |
| [batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/JobPushDeliveryIntegrationTest.java](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/JobPushDeliveryIntegrationTest.java#L150) | 5 | 0 |  |
| [batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/JobPushIntakeIntegrationTest.java](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/JobPushIntakeIntegrationTest.java#L107) | 3 | 0 |  |
| [batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/TenantConfigBatchInitStrictAtomicityIntegrationTest.java](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/TenantConfigBatchInitStrictAtomicityIntegrationTest.java#L116) | 14 | 0 |  |
| [batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/TenantConfigDependencyRlsIntegrationTest.java](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/TenantConfigDependencyRlsIntegrationTest.java#L216) | 59 | 0 |  |
| [batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/TenantWipeScriptsIntegrityIntegrationTest.java](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/TenantWipeScriptsIntegrityIntegrationTest.java#L245) | 1 | 0 |  |
| [batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/TerminalNotificationRecoveryIntegrationTest.java](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/TerminalNotificationRecoveryIntegrationTest.java#L126) | 5 | 0 |  |
| [batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/WorkflowConfigApprovalHttpIntegrationTest.java](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/WorkflowConfigApprovalHttpIntegrationTest.java#L120) | 5 | 0 |  |
| [batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/WorkflowJobReferenceAdmissionIntegrationTest.java](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/WorkflowJobReferenceAdmissionIntegrationTest.java#L119) | 7 | 0 |  |
| [batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/WorkflowTerminalNotificationIntegrationTest.java](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/WorkflowTerminalNotificationIntegrationTest.java#L192) | 5 | 0 |  |
| [batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/AtomicTaskPipelineE2eIT.java](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/AtomicTaskPipelineE2eIT.java#L307) | 16 | 0 |  |
| [batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/DeadLetterApprovalReplayE2eIT.java](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/DeadLetterApprovalReplayE2eIT.java#L457) | 28 | 0 |  |
| [batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/DispatchPipelineE2eIT.java](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/DispatchPipelineE2eIT.java#L492) | 1 | 0 |  |
| [batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/ExportPipelineE2eIT.java](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/ExportPipelineE2eIT.java#L198) | 3 | 0 |  |
| [batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/ImportPipelineE2eIT.java](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/ImportPipelineE2eIT.java#L257) | 22 | 0 |  |
| [batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/JobPushProcessRecoveryE2eIT.java](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/JobPushProcessRecoveryE2eIT.java#L323) | 6 | 0 |  |
| [batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/LaunchProcessCrashRecoveryE2eIT.java](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/LaunchProcessCrashRecoveryE2eIT.java#L219) | 9 | 0 |  |
| [batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/OutboxForwarderE2eIT.java](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/OutboxForwarderE2eIT.java#L187) | 22 | 0 |  |
| [batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/ProcessFailurePipelineE2eIT.java](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/ProcessFailurePipelineE2eIT.java#L118) | 2 | 0 |  |
| [batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/ProcessPipelineE2eIT.java](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/ProcessPipelineE2eIT.java#L206) | 52 | 0 |  |
| [batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/ReplayAdmissionRecoveryE2eIT.java](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/ReplayAdmissionRecoveryE2eIT.java#L142) | 6 | 0 |  |
| [batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/SdkWorkerRestartChildProcess.java](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/SdkWorkerRestartChildProcess.java#L1) | 0 | 0 | E2E业务写入模板化 |
| [batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/SelfServiceJobCompensationHttpE2eIT.java](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/SelfServiceJobCompensationHttpE2eIT.java#L711) | 5 | 0 |  |
| [batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/StaleWorkerReportFenceE2eIT.java](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/StaleWorkerReportFenceE2eIT.java#L264) | 92 | 0 |  |
| [batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/TriggerAsyncLaunchFullChainE2eIT.java](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/TriggerAsyncLaunchFullChainE2eIT.java#L414) | 12 | 0 |  |
| [batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/WorkerDrainE2eIT.java](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/WorkerDrainE2eIT.java#L109) | 1 | 0 |  |
| [batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/WorkerReportLostAfterBusinessCommitE2eIT.java](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/WorkerReportLostAfterBusinessCommitE2eIT.java#L249) | 41 | 0 |  |
| [batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/WorkflowOccurrenceCatchUpApprovalE2eIT.java](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/WorkflowOccurrenceCatchUpApprovalE2eIT.java#L319) | 199 | 0 |  |
| [batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/WorkflowTerminalNotificationE2eIT.java](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/WorkflowTerminalNotificationE2eIT.java#L240) | 22 | 0 |  |
| [batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/WorkflowTerminalNotificationProcessRecoveryE2eIT.java](../../batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/WorkflowTerminalNotificationProcessRecoveryE2eIT.java#L225) | 5 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/engine/CountContinuityOutboxIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/engine/CountContinuityOutboxIntegrationTest.java#L79) | 6 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/file/BundleArrivalLauncherConcurrencyIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/file/BundleArrivalLauncherConcurrencyIntegrationTest.java#L264) | 16 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/ResourceAdmissionConcurrencyIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/ResourceAdmissionConcurrencyIntegrationTest.java#L174) | 5 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/sensor/SensorProbeTransactionExecutorIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/sensor/SensorProbeTransactionExecutorIntegrationTest.java#L400) | 12 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/sensor/WorkflowSensorApplicationContextRestartIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/sensor/WorkflowSensorApplicationContextRestartIntegrationTest.java#L152) | 61 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/AdminTestDataCleanupControllerIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/AdminTestDataCleanupControllerIntegrationTest.java#L158) | 6 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/ArchiveColdStorageIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/ArchiveColdStorageIntegrationTest.java#L95) | 2 | 9 | FQN简化 |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/BatchDayDryRunSchemaIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/BatchDayDryRunSchemaIntegrationTest.java#L139) | 2 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/BatchDayMetricsMapperIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/BatchDayMetricsMapperIntegrationTest.java#L185) | 26 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/BatchDayReplayOutputsOnlyIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/BatchDayReplayOutputsOnlyIntegrationTest.java#L168) | 1 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/BatchDaySettleMultiProcessIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/BatchDaySettleMultiProcessIntegrationTest.java#L164) | 4 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/BatchInsertGeneratedKeysIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/BatchInsertGeneratedKeysIntegrationTest.java#L285) | 1 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/BundleLaunchPartitionExpansionIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/BundleLaunchPartitionExpansionIntegrationTest.java#L235) | 15 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/CompensationFailureRecoveryIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/CompensationFailureRecoveryIntegrationTest.java#L92) | 5 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/CrossDayDependencyReconcilerIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/CrossDayDependencyReconcilerIntegrationTest.java#L250) | 21 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/DataQualityCheckReferenceIntegrityIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/DataQualityCheckReferenceIntegrityIntegrationTest.java#L165) | 1 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/DeferredJobRejectionIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/DeferredJobRejectionIntegrationTest.java#L138) | 15 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/FileGovernanceIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/FileGovernanceIntegrationTest.java#L214) | 7 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/JobLaunchToFinishLifecycleIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/JobLaunchToFinishLifecycleIntegrationTest.java#L265) | 4 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/JobMonitoringAlertEmissionRecoveryIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/JobMonitoringAlertEmissionRecoveryIntegrationTest.java#L131) | 1 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/JobMonitoringMapperIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/JobMonitoringMapperIntegrationTest.java#L314) | 2 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/JobNodeDispatchIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/JobNodeDispatchIntegrationTest.java#L227) | 150 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/JobRetryFlowIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/JobRetryFlowIntegrationTest.java#L187) | 4 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/JobSlaSchedulerRecoveryIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/JobSlaSchedulerRecoveryIntegrationTest.java#L328) | 1 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/JobTaskPartitionTenantIdentityIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/JobTaskPartitionTenantIdentityIntegrationTest.java#L145) | 31 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/JobTerminalOutboxIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/JobTerminalOutboxIntegrationTest.java#L134) | 7 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/LocalFlywayPlatformMigrationsIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/LocalFlywayPlatformMigrationsIntegrationTest.java#L100) | 24 | 6 | FQN简化 |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/MultiCalendarCoordinationIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/MultiCalendarCoordinationIntegrationTest.java#L230) | 18 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/MultiTenantIsolationIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/MultiTenantIsolationIntegrationTest.java#L150) | 16 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/PartitionJoinPromotionIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/PartitionJoinPromotionIntegrationTest.java#L186) | 6 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/PipelineProgressArchiveIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/PipelineProgressArchiveIntegrationTest.java#L101) | 2 | 4 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/ReplayExecutionIdentityIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/ReplayExecutionIdentityIntegrationTest.java#L412) | 24 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/RequiresNewTransactionBoundaryIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/RequiresNewTransactionBoundaryIntegrationTest.java#L191) | 1 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/RetryExecutionClosureIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/RetryExecutionClosureIntegrationTest.java#L134) | 8 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/SimSftpFixtureContractIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/SimSftpFixtureContractIntegrationTest.java#L110) | 1 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/TriggerRequestMapperIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/TriggerRequestMapperIntegrationTest.java#L252) | 10 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/TriggerTypeLaunchIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/TriggerTypeLaunchIntegrationTest.java#L122) | 53 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/WaitRequestRetentionIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/WaitRequestRetentionIntegrationTest.java#L399) | 8 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/WaitingLaunchIdentityIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/WaitingLaunchIdentityIntegrationTest.java#L350) | 18 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/WorkflowArchiveIntegrityIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/WorkflowArchiveIntegrityIntegrationTest.java#L177) | 6 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/WorkflowFrozenPartitionPlanIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/WorkflowFrozenPartitionPlanIntegrationTest.java#L97) | 8 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/WorkflowGraphTenantForeignKeyIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/WorkflowGraphTenantForeignKeyIntegrationTest.java#L171) | 11 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/WorkflowRunRerunSnapshotIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/WorkflowRunRerunSnapshotIntegrationTest.java#L273) | 2 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/WorkflowRunResumeRecoveryIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/WorkflowRunResumeRecoveryIntegrationTest.java#L245) | 13 | 0 |  |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/WorkflowTerminalLineageKafkaIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/WorkflowTerminalLineageKafkaIntegrationTest.java#L148) | 3 | 0 |  |
| [batch-test-support/src/test/java/io/github/pinpols/batch/common/stateful/StatefulBackendGuardIntegrationTest.java](../../batch-test-support/src/test/java/io/github/pinpols/batch/common/stateful/StatefulBackendGuardIntegrationTest.java#L111) | 1 | 0 |  |
| [batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/ExternalEventApiKeyHttpIntegrationTest.java](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/ExternalEventApiKeyHttpIntegrationTest.java#L290) | 2 | 0 |  |
| [batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/QuartzJdbcClusterIntegrationTest.java](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/QuartzJdbcClusterIntegrationTest.java#L175) | 3 | 0 |  |
| [batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/QuartzMisfireApplicationRestartIntegrationTest.java](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/QuartzMisfireApplicationRestartIntegrationTest.java#L418) | 10 | 0 |  |
| [batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/QuartzMisfireRecoveryIntegrationTest.java](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/QuartzMisfireRecoveryIntegrationTest.java#L406) | 21 | 0 |  |
| [batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/ScheduledOccurrenceMapperIntegrationTest.java](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/ScheduledOccurrenceMapperIntegrationTest.java#L164) | 26 | 0 |  |
| [batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/TriggerMisfirePendingMapperIntegrationTest.java](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/TriggerMisfirePendingMapperIntegrationTest.java#L373) | 1 | 0 |  |
| [batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/TriggerServiceIntegrationTest.java](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/TriggerServiceIntegrationTest.java#L250) | 51 | 0 |  |
| [batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/integration/StoredProcHardeningIntegrationTest.java](../../batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/integration/StoredProcHardeningIntegrationTest.java#L129) | 2 | 0 |  |
| [batch-worker/core/src/test/java/io/github/pinpols/batch/worker/core/mapper/PlatformFileRuntimeMapperStageSkipIntegrationTest.java](../../batch-worker/core/src/test/java/io/github/pinpols/batch/worker/core/mapper/PlatformFileRuntimeMapperStageSkipIntegrationTest.java#L213) | 9 | 0 |  |
| [batch-worker/core/src/test/java/io/github/pinpols/batch/worker/core/reportoutbox/WorkerReportOutboxCoordinatorTest.java](../../batch-worker/core/src/test/java/io/github/pinpols/batch/worker/core/reportoutbox/WorkerReportOutboxCoordinatorTest.java#L123) | 2 | 0 |  |
| [batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/integration/DispatchSecretVersionMapperIntegrationTest.java](../../batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/integration/DispatchSecretVersionMapperIntegrationTest.java#L133) | 1 | 0 |  |
| [batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/mapper/FileDispatchMapperTenantReferenceIntegrationTest.java](../../batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/mapper/FileDispatchMapperTenantReferenceIntegrationTest.java#L202) | 4 | 0 |  |
| [batch-worker/export/src/test/java/io/github/pinpols/batch/worker/exports/integration/S3ExportStorageIntegrationTest.java](../../batch-worker/export/src/test/java/io/github/pinpols/batch/worker/exports/integration/S3ExportStorageIntegrationTest.java#L140) | 2 | 0 |  |
| [batch-worker/export/src/test/java/io/github/pinpols/batch/worker/exports/plugin/ExportKeysetRangeIntegrationTest.java](../../batch-worker/export/src/test/java/io/github/pinpols/batch/worker/exports/plugin/ExportKeysetRangeIntegrationTest.java#L126) | 0 | 3 |  |
| [batch-worker/export/src/test/java/io/github/pinpols/batch/worker/exports/plugin/ExportPartitionSliceIntegrationTest.java](../../batch-worker/export/src/test/java/io/github/pinpols/batch/worker/exports/plugin/ExportPartitionSliceIntegrationTest.java#L118) | 0 | 2 |  |
| [batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/plugin/JdbcMappedImportCopyIntegrationTest.java](../../batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/plugin/JdbcMappedImportCopyIntegrationTest.java#L200) | 8 | 0 |  |
| [batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/cleanup/ProcessStagingCrossDatabaseCleanupRecoveryIntegrationTest.java](../../batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/cleanup/ProcessStagingCrossDatabaseCleanupRecoveryIntegrationTest.java#L277) | 4 | 0 |  |
| [batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/cleanup/ProcessStagingMapperIntegrationTest.java](../../batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/cleanup/ProcessStagingMapperIntegrationTest.java#L195) | 12 | 0 |  |
| [batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/integration/BatchWorkerProcessConfigRoutingIntegrationTest.java](../../batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/integration/BatchWorkerProcessConfigRoutingIntegrationTest.java#L70) | 1 | 0 |  |
| [batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/integration/BatchWorkerProcessTableRoutingIntegrationTest.java](../../batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/integration/BatchWorkerProcessTableRoutingIntegrationTest.java#L67) | 1 | 0 |  |
| [batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/stage/PlatformProcessInvocationFenceIntegrationTest.java](../../batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/stage/PlatformProcessInvocationFenceIntegrationTest.java#L177) | 3 | 0 |  |
| [batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/stage/ProcessStageSkipCrashResumeIntegrationTest.java](../../batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/stage/ProcessStageSkipCrashResumeIntegrationTest.java#L274) | 1 | 0 |  |

## 尚未结束的范围

本阶段关闭的是上述 SQL 传参、JDBC 嵌套断言及 G-01 枚举实现缺口。测试写死值是否为独立契约预期、公共 fixture 所有权、SQL 与文件资源分离、多 null 构造、剩余非 JDBC 方法嵌套，以及总体台账其他领域继续保留。用户要求全部完成的目标保持有效；不因本批候选归零或未运行测试而缩小原始范围。

## 本阶段最终核对

- `python3 scripts/ci/check-java-structured-string-concat.py <本阶段102个Java文件>`：退出 0；与 HEAD 比对新增结构化拼接，未执行项目测试。
- `python3 scripts/ci/check-script-governance.py`：退出 0，83 个已登记守卫、4 个直接可执行入口；未新增豁免或改脚本执行模式。
- 修改源码/文档 `git diff --check`：退出 0；新报告本地链接有效，1529 项整改 CSV 与482项条件 CSV 的路径及源码 SHA 全部匹配。
- 最后重扫 1376 个测试文件：解析错误 0，已识别的直接 JDBC 嵌套断言 0；非 SQL 表达式保留为前述 76 项，未把回调或 Mockito DSL 误改为 SQL。
- 最后核对 102 个改动 Java 文件解析错误 0；99 文件方法调用序列仍与提取前一致。诊断角色权限布尔结果明确命名为 `currentRoleHasNoRlsBypassPrivilege`，暂存分区关系名结果命名为 `resolvedStagingPartitionName`，避免用 SQL 首个关键字代替业务含义。

本轮为实质推进：修复了源码及守卫缺口并新增可追踪证据；全部19项的目标继续有效，未标记整体完成或阻塞。
