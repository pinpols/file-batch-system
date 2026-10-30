# Workflow 功能收尾定向验证（2026-10-25）

## 基线与范围

后端工作树分支
`fix/wdi-current`，HEAD `f3d5e400d` 加当前未提交改动。独立 feature 工作树冻结。
本轮覆盖 readiness 持久等待、Kafka 重试耗尽、WR-04 授权消费、Java SDK 故障边界及配置导入行为。
没有提交、推送或创建 PR。正式 R01–R36 的状态仍以验收矩阵为准。

## 修复与证据

| 项目 | 当前证据与结论 |
|---|---|
| Readiness 持久移交 | 实际 HTTP／PostgreSQL 首红确认 `ReadinessWaitMapper.insertIgnore` 的单数依赖列名与 V351 复数列名不符，移交返回 500；已修正 Mapper。双上下文 2 项通过：完整快照落库，重建恢复服务后不依赖 Quartz 回调恢复原 fire；超时结束等待与 occurrence，不再阻塞该计划义务。 |
| 恢复地址 | 原 `TriggerInternalProperties` 默认端口 8082 且没有接入已存在的统一地址变量；已绑定 `BATCH_TRIGGER_BASE_URL`，未指定完整地址时使用 `BATCH_TRIGGER_PORT`／默认 18081。实际应用 YAML 绑定 2 项通过；Compose apps＋replica 配置解析通过，Orchestrator 得到 `http://trigger:18081`。 |
| Kafka 重试耗尽 | 恢复锚点持久化失败必须抛回 error handler，不能确认跳过消息。此前错误处理／服务定向测试及真实 PG 持久层 3 项通过。新增真实 Kafka 场景 1 项通过：prepare 前异常使实际消费耗尽，密文恢复锚点持久化；恢复扫描用原请求创建唯一根运行并标记 DONE，原消息重投仍只有一个根。初次夹具的已接纳请求日期与投递日期不同，身份守护正确拒绝；修正夹具后通过，未放宽生产身份守护。 |
| WR-04 | 最初的补偿审批指纹测试只覆盖换目标提交，不能证明 Worker 开始时的根请求绑定。新增真实 PG 首红复现另一根请求复制授权标识后仍获准开始外部动作。修复在外部动作开始前按源任务版本、父实例、租户、业务日和实际根请求消费授权，与开始标记同事务；缺失授权的 UNKNOWN 父实例重跑也拒绝。定向 2 项通过，覆盖错误根拒绝、绑定根首次消费、开始写库失败全回滚、重复开始幂等及消费后重新阻断额外补偿；完整分派／重试回归 62 项（含上述两项）及真实 Console 审批恢复 1 项通过。 |
| 普通 Java SDK | 独立 JVM 向独立业务 PG 提交后由父进程实际强杀，租约回收为 UNKNOWN，重复消息不能再次产生动作；REPORT 经真实 HTTP 转发到平台提交后关闭响应连接，SDK 实际收到 EOF 并用原幂等键和载荷重试，业务效果仍唯一。两项新场景和原有 3 项 Error／安全恢复场景共 5 项通过；加入 WR-04 守护后相同 5 项再次通过，按 5 个不同用例计数。 |
| 平台 Worker 故障边界 | 本轮较早阶段、WR-04 开始守护加入前，`WorkerReportLostAfterBusinessCommitE2eIT` 的 REPORT 提交后确认丢失，以及 SQL／存储过程提交后实际进程强杀共 3 项通过，未重复运行。新守护后的故障回归以普通 SDK 5 项及审批恢复 1 项为准，两批证据分别报告。 |
| 配置导入 | 70 项通过、0 跳过，其中单测 55 项，真实 PostgreSQL／Redis 联合测试 15 项。批量初始化提交清缓存，回滚及 dry-run 不清缓存、不意外创建托管 Workflow；Excel 公共 Apply 提交后清缓存，回调登记后失败会回滚数据库、保留缓存并释放上传会话。详见配置导入影响审计。 |

## 命令与原始记录

所有 Maven 命令使用当前工作树的 `./mvnw -o -ntp`，按模块串行执行，参数
`-Dsurefire.failIfNoSpecifiedTests=false test` 仅允许依赖模块没有选定测试，不跳过选定用例。

- 配置、地址和第一轮故障场景：`-pl batch-e2e-tests -am`，选定
  `TriggerReadinessRecoveryAddressTest,DefaultConsoleTenantConfigInitApplicationServiceTest,ConfigSyncImportExecutorTest,ConsoleConfigCacheInvalidationServiceTest,TenantConfigPackageExcelMonitoringPolicyApplyTest,TenantConfigPackageExcelApplyAuthorizationTest,DefaultConsoleTenantConfigCopyServiceTest,TenantConfigBatchInitStrictAtomicityIntegrationTest,ConfigPackageApplyCacheTransactionIntegrationTest,ReadinessHandoverE2eIT,TriggerLaunchRecoveryE2eIT,SdkOrdinaryLeaseRecoveryE2eIT`。
  配置 70、地址 2、readiness 2、SDK 5 通过；Kafka 日期夹具首轮失败保留，不能将此命令整体记为成功。
- WR-04 首红及首绿：`-pl batch-orchestrator -am`，选定
  `RetryExecutionClosureIntegrationTest#shouldConsumeUnknownResolutionOnlyFromBoundWorkflowRoot`；
  首绿另加 `+consumedExternalOutcomeResolution_blocksFurtherSourceReruns`，2 项通过。
- 最终受影响模块回归：`-pl batch-e2e-tests -am`，选定
  `JdbcExceptionSupportTest,ScheduledOccurrenceMapperIntegrationTest#shouldRepeatedObservedFireKeepOriginalScheduleSnapshotAndRequestIdentity,DefaultTaskAssignmentServiceTest,RetryExecutionClosureIntegrationTest,TriggerLaunchRecoveryE2eIT,SdkOrdinaryLeaseRecoveryE2eIT,DeadLetterApprovalReplayE2eIT#shouldReplayUnknownExternalOutcomeOnce_whenVerifiedNotExecutedThroughConsoleApproval`。
  在临时源码快照完成 JDBC 8、调度 1、分派 29、重试 33 共 71 项；完整命令随后因 E2E 中 12 个时间源调用未适配而编译失败。沿现有适配器补齐该测试文件调用，保留 71 项证据。
- 只重跑剩余 E2E：同模块和 Maven 参数，选定
  `TriggerLaunchRecoveryE2eIT,SdkOrdinaryLeaseRecoveryE2eIT,DeadLetterApprovalReplayE2eIT#shouldReplayUnknownExternalOutcomeOnce_whenVerifiedNotExecutedThroughConsoleApproval`。
  7 项通过、0 失败／错误／跳过，完整 reactor `BUILD SUCCESS`。上述两段组成 78 个不同受影响用例，不能把第一段整条命令写成成功，也不重复累加已经通过的配置 70 项。
- 原始日志、首红 XML、最终 XML 和源码摘要归档目录：
  `logs/verification/wdi-functional-2026-10-25/`。首次共享构建目录被另一轮 `clean` 删除造成的
  `NoSuchFileException` 单独保存为构建冲突；另有一处调度测试未适配现有时间源接口的编译错误，
  已沿现有 Quartz adapter 修复并纳入上述单方法回归，未改变生产调度规则。

为避免共享构建目录干扰，最终模块／E2E 在临时源码快照构建；只有确认的 E2E 时间源调用适配修复被同步一次。
最终核对当前工作树的 4701 个源码及 POM 文件，全部与验证快照 SHA-256 一致；源码摘要、原始日志和 XML
保存于上述证据目录。快照不是新 Git 分支，也未创建提交。

结束时按本轮日志中的容器 ID 与临时快照路径核实：专属测试容器运行数 0、专属 Java 进程数 0。
测试生成的临时数据库故障触发器／函数在 finally 中移除；快照中的私有环境文件已删除，日志和源码摘要保留。

## 验证边界

本轮为本地隔离应用、真实依赖和独立进程验证。配置变更的多实例传播时延、生产认证与部署、CI、
压测、整轮 SIM 和整轮 WARN 审计不在本次定向结果之内；没有用局部结果改写正式 36 项数量。
规约治理仍归原会话，本轮只格式化自己改动的源码并修复用户指出的 FQN。
