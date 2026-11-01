# 后端 Controller / Service / Entity 边界复核

## 基线与范围

- 仓库：`file-batch-system`；分支 `fix/bug-fixes`；HEAD `0258697d14229e9da1bddb547943acf9d2481338`。
- 工作树在本轮开始前已有大量未提交变更；本记录仅登记本次边界整改，未清理或覆盖其他变更。
- 静态依赖复核覆盖全仓 137 个生产 Controller，检查 Mapper / Repository / infrastructure 直接导入；全仓 `*Entity.java` 命名/位置候选；沿确认的策略入口追踪对应应用服务、内部响应映射和现有测试。业务策略逐项复核覆盖 Console API、Orchestrator、Trigger 中本报告列出的路径，不把依赖扫描夸大为 137 个 Controller 的逐方法语义审查。
- 目标规则：`docs/coding-conventions.md` §6.3、§8.2.2、§8.2.10；`docs/agent-baseline.md` §7.1、§7.4。

## 确认并已整改

| 边界 | 整改 | 行为约束 |
|---|---|---|
| 测试数据清理策略 | Orchestrator 的生产环境拒绝、ID 解析已在应用服务入口；补查发现 Console 镜像入口仍在 Controller 校验生产 profile 并拆分 ID，现移入 `ConsoleAdminTestDataApplicationService`。Controller 保留 HTTP 参数校验、审计和响应封装。 | 生产环境仍拒绝清理；精确租户 ID 仍按逗号拆分、trim、过滤空项后原样转发。 |
| 登录安全策略 | 登录体解密、明文登录配置、公钥开放策略放入认证应用服务；Controller 保留 cookie、HTTP 状态与响应适配。 | 不改变登录请求/响应契约。 |
| 请求限流 | Task 热路径和 Worker 注册的限流判断由 Controller 移至应用/注册服务入口；批量 Task 仍按 API Key 解析出的租户、一次 HTTP 请求计一次。 | 429 语义与限流动作/租户维度保持不变。 |
| Drain 策略 | Trigger 与 Orchestrator 的写入拒绝集中到应用服务；Trigger 并发准入后的排水复核在应用入口执行。 | 保留排队期间进入 draining 时拒绝的复核时点。 |
| 外部事件授权 | Trigger API Key 的事件类型、租户和 source 授权规则移入 `TriggerRequestPolicy`；Controller 仅解析认证上下文并委托。 | 保留原授权谓词及错误码。 |
| Controller 对基础设施依赖 | Readiness 恢复用例移到 Trigger Service；文件延迟指标由 File Governance 应用服务查询并映射响应；取证下载状态/文件存在性校验移入应用服务。 | 延迟指标 JSON 字段、取证下载状态码及缺失文件错误语义不变。 |
| 表行位置与误命名 | `MaintenanceStateEntity`、`PipelineProgressEntity` 移入各自 `domain/entity`；非表快照改名 `SdkIdempotencySnapshot`，导入坏行运行时模型改名 `ImportBadRecord`；API Key 查询候选使用 `ApiKeyCandidate`。 | 不改表结构、JSON 或数据格式；同步生产和测试引用。 |
| 其他 Entity 候选 | `ConfigReleaseEntity` 本就在 `domain/entity`，无需移动。名称扫描中位于 `entity` 目录之外的 `*Entity` 是抽象公共基类；目录位置扫描不能识别目录内误命名投影，见续轮复核。 | 不对合法模型做机械改名。 |

Console 用量摘要与 Asset Freshness Policy 的 Entity/Mapper 返回映射也已从 Controller/响应契约移入服务；Entity 不作为 HTTP 请求/响应签名。`ConsoleConfigBatchRecoveryController` 的幂等请求身份与原始路径处理属于 HTTP 请求身份适配，按现有路径/上下文语义保留。

## 覆盖检查结果

- 全仓 137 个生产 Controller 对 Mapper/Repository 的生产代码导入扫描：未发现。
- Controller 对 `infrastructure` 实现包的直接导入扫描：未发现。
- Controller 的公共请求/响应声明中未发现持久化 Entity 类型；`ResponseEntity` 命中是 HTTP 类型，不是 Entity。
- 全仓名称位置扫描后，仅 `AbstractLocalizedErrorEntity`、`AuditableEntity` 两个抽象基类留在 `entity` 目录之外；目录内仍须判断是否确为表行。
- Controller 内同方法响应映射的 Entity 候选与 HTTP 契约区分；只要不是请求/响应契约、未跨层修改或传递状态，不按“Entity 泄漏”机械整改。续轮发现 DataQualityRule 写路径仍从 Controller 下传可变 Entity，需继续整改。

## 验证

- `./mvnw -pl batch-trigger,batch-orchestrator,batch-console-api,batch-worker/import,sdk/java/core -am -DskipTests test-compile`：**BUILD SUCCESS**。覆盖上述模块及依赖闭包的生产和测试源码编译；`-DskipTests` 表示未执行测试。
- `git diff --check`：通过。
- 未运行单元/集成测试、完整 CI、服务容器或目标环境验证；编译成功不替代行为测试或发布验收。
- Maven 有私有 Feed 元数据 HTTP 401 警告；本地依赖可用，未阻断最终 test-compile。

## 边界

本记录覆盖本表所列 Controller→应用服务→持久化边界与 Entity 命名/位置候选，不代表该领域或后端 25 个工程规约工作包整体审计完成。SDK 幂等存储公开扩展点的快照类型及 `find` / `record` 方法签名已变更；按用户决定不提供旧接口兼容，使用旧接口的 SDK 客户需要调整实现。未改变 SDK wire、数据库迁移或对外运行环境。既有工作树其他差异不在本记录结论范围内。

## 续轮复核（进行中）

上一轮的导入和命名扫描不能证明全部职责边界已复核；续轮已发现 Import 对象到达 Controller 的扫描准入和并发合并、Trigger 领域命令对 Web 请求的依赖、心跳查询投影误命名为 Entity，以及应用服务引用 Controller 响应类型。正在按调用链整改并补充定向验证。Console AI 活动流、Telemetry 和其他 Service 候选仍需继续判定；此阶段不以历史编译结果标记全范围完成。

上一轮 Mapper / Repository 导入扫描的正则只识别类名恰为 `Mapper` 或 `Repository`，覆盖不足。本轮对 137 个生产 Controller 按类型名 `Mapper` / `Repository` 后缀重扫，结果为零；该结果只证明显式导入范围。

- Trigger 的三个领域命令改用独立不可变用例输入，HTTP 入口负责转换；外部事件历史快照 JSON 兼容由定向测试覆盖。与 Orchestrator 合并运行四个 Trigger 定向测试类，56 例通过；此后继续整改 Trigger 用例编排，需在最终代码稳定后重跑。数据库集成及全链路未运行；对应 E2E 调用点已同步，尚未单独编译。
- Import 到达通知的扫描准入、在途合并、指标和失败释放移入 `ImportEventArrivalService`；`./mvnw -pl batch-worker/import -am -Dtest=ImportEventArrivalControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`：7 例通过，覆盖开关、并发合并和失败后释放。
- 配置批量恢复结果的类型匹配移入应用服务；心跳和自定义任务类型查询投影迁入 `view`。Console 定向测试（AI 图片与活动流、Alertmanager、Telemetry、配置恢复、心跳与任务类型共 11 个测试类）70 例通过；后续 AI 授权和查询投影修改须重跑。SLA 候选与表行模型拆分已执行定向单测，真实数据库映射仍待验证。
- Orchestrator 的告警应用命令与 Controller 请求分离，Outbox 和调度快照响应迁入应用层；与 Trigger 合并定向运行时，Orchestrator 97 例通过。后续 DataQualityRule 与人工重放整改需重新验证相关路径。

续轮逐方法静态复核了 Orchestrator 33 个、Trigger 5 个生产 Controller：AdminTestDataCleanup、AlertInternal、Approval、BatchDayCatchupPreview、BatchDayOps、BatchDayReplay、CalendarDependencyWait、CapacityProfile、Compensation、DataQualityRule、DryRun、ExpectedWorkRegistration、ExternalEventExpectation、FileGovernance、FileGroupExpectation、ForensicExport、InstanceManagement、JobDependencyWaitingLaunch、Launch、LineageEvidence、OrchestratorDrain、OrchestratorReadiness、OutboxOps、PersistentWaitReadInternal、PipelineProgressInternal、ReadinessWait、Recovery、ResultVersion、SchedulerSnapshot、Task、Worker、WorkflowRunManagement、WorkflowRunReplay，以及 Trigger 的 InternalCatchUpVerification、InternalReadinessRecovery、Trigger、TriggerManagement、TriggerStatus。确认的 DataQuality 写入 Entity 跨层、手工重放去重键和 Trigger 准入编排已实施调整，定向测试进行中。该静态范围不等于调用链运行验收。

上述三项调整后的合并命令 `./mvnw -pl batch-orchestrator,batch-trigger -am -Dtest=ControllerEntityBoundaryBehaviorTest,ControllerResponseMappingTest,DataQualityRuleApplicationServiceTest,ManualReplayApplicationServiceTest,TriggerControllerTest,TriggerAdmissionDrainTest,DefaultTriggerServiceTest,DefaultLaunchAdapterServiceTest,TriggerCommandInputMapperTest,AlertInternalControllerTest,JobSlaViolationProcessorTest,JobSlaSchedulerTest,TenantSchedulerSnapshotServiceTest,TenantSchedulerSnapshotRecorderTest -Dsurefire.failIfNoSpecifiedTests=false test`：Trigger 62 例、Orchestrator 41 例通过。后续发现的 Trigger 409 重投路径仍在修复，尚未由这组结果覆盖。

Entity 候选覆盖：101 个 `*Entity.java` 已做命名/位置和聚合/JOIN 线索扫描；人工确认的投影或混合字段包括心跳、自定义任务类型、Worker、AI 月度用量、文件到达分组、分区错误、待补跑、OIDC 身份、SLA 候选和 JobDefinition 聚合。逐类状态见 [Entity 边界清单](backend-entity-boundary-inventory-2026-11-01.csv)；尚未对 101 个实体逐类完成 SQL/写入语义复核，扫描结果不能代表全量通过。

续轮人工核对了 Console 97/97 个 Controller 的公开方法职责，逐类见 [Console Controller 边界清单](console-controller-boundary-inventory-2026-11-01.csv)，并对命中候选追踪服务：确认的目录、维护、租户自助、认证、审批人、下载和 Mapper 参数边界已实施或正在验证。Entity 台账现已逐项记录 101 个基线候选；投影迁移和混合字段拆分尚待验证。逐类源码检查不等于所有业务路径、授权和环境已验证。

系统问题另记：Trigger 消费端原先将任意 HTTP 409 当作重复成功并确认消息；启动过程的 `markSatisfied` 可在 WorkflowRun 提交后因事件身份冲突返回 409，无法据此证明后续处理完成。消费端已改为进入既有重试与持久恢复流程；`./mvnw -pl batch-orchestrator -am -Dtest=TriggerLaunchConsumerTest,TriggerLaunchRetryExhaustionReproTest -Dsurefire.failIfNoSpecifiedTests=false test`：加强恢复断言后重跑 12 例通过。该命令使用模拟依赖；真实 Kafka/数据库路径尚未验证。相应面板及告警 fixture 已同步。

Console 重构过程中执行 `./mvnw -pl batch-console-api -am -DskipTests test-compile` 曾先后发现 JobDefinition 拆分的 3 处生产类型错误及 4 处测试旧类型错误；修正后重跑主源码通过，测试编译又发现资产新鲜度集成测试 2 处 Command/Mapper Param 混用，正在修正。该失败与后续结果分别记录，不把中断或旧成功视为当前代码通过。

随后包含 Console 定向和容器测试的 Maven 命令在生产编译阶段发现 `ConsoleTenantConfigPackageExcelController:74` 返回类型适配遗漏，测试未启动；正在修复。重跑前不会把本轮定向测试记为通过。

Orchestrator 异常分类及投影阶段验证：`./mvnw -pl batch-orchestrator -am -Dtest=BatchDayCatchupControlServiceTest,ExpectedWorkRegistrationServiceTest,ExternalEventExpectationServiceTest,FileGroupExpectationServiceTest,LaunchApplicationServiceTest,TriggerLaunchConsumerTest,TriggerLaunchRecoveryServiceTest,OrchestratorApiExceptionHandlerTest,TriggerLaunchRetryExhaustionReproTest,DefaultWorkerRegistryServiceTest,SpringTechnicalFailureClassificationAdapterTest,JobSlaViolationProcessorTest,JobSlaSchedulerTest,DispatchSecretSnapshotResolverTest,OrchestratorRedisSupportRevisionTest -Dsurefire.failIfNoSpecifiedTests=false test`：147 例通过。后续 Outbox 和 JobDefinition 改动尚未覆盖；真实数据库及 Kafka 验证未由此命令证明。

Outbox 及 JobDefinition 执行投影后的重跑：`./mvnw -pl batch-orchestrator -am -Dtest=OutboxOpsApplicationServiceTest,OutboxOpsControllerTest,OrchestratorRedisSupportRevisionTest,JobSlaViolationProcessorTest,JobSlaSchedulerTest,LaunchApplicationServiceTest,DefaultWorkflowRunLaunchServiceTest,TriggerLaunchConsumerTest,TriggerLaunchRecoveryServiceTest,TriggerLaunchRetryExhaustionReproTest,SpringTechnicalFailureClassificationAdapterTest,OrchestratorApiExceptionHandlerTest,DispatchSecretSnapshotResolverTest -Dsurefire.failIfNoSpecifiedTests=false test`：105 例通过；其中 `DefaultWorkflowRunLaunchServiceTest` 类不存在，未执行该名称的测试，需按实际测试类补验。Trigger 的 13/13 个 Service 已作入口及调用方静态复核，已修 Readiness 恢复身份校验；Orchestrator Service 逐类复核仍在进行，不能外推全部完成。

隔离容器集成：`./mvnw -pl batch-orchestrator -am -Dtest=DispatchSecretBindingMapperIntegrationTest,TriggerLaunchRecoveryMapperIntegrationTest,TriggerLaunchRecoveryIdentityIntegrationTest,JobMonitoringMapperIntegrationTest,JobSlaSchedulerRecoveryIntegrationTest,OrchestratorConfigCacheServiceIntegrationTest,OrchestratorConfigCacheRefillRaceIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`：62 例通过，依赖 Testcontainers PostgreSQL、Kafka、对象存储和 Redis；这些测试不等于全链路 Kafka 验收。Trigger 身份入口及既有准入测试 `./mvnw -pl batch-trigger -am -Dtest=ReadinessRecoveryServiceTest,InternalReadinessRecoveryControllerTest,TriggerAdmissionDrainTest,TriggerControllerTest,DefaultTriggerServiceTest,DefaultLaunchAdapterServiceTest,TriggerCommandInputMapperTest -Dsurefire.failIfNoSpecifiedTests=false test`：73 例通过。

Service 入口复核范围快照：Orchestrator 按 `*Service.java` 统计 100 个，已逐类确认 8 个；Trigger 13/13 已逐入口静态复核。Console 按 `*Service.java` 与 `@Service` 去重有 165 个候选，其中 29 个只做过部分方法或调用链阅读，尚不能计入完整逐类确认；Worker 同口径有 31 个候选尚未逐类确认。其他按职责归属的组件与 Port 仍需判定，不将候选数量等同缺陷数量。

## 暂停记录（2026-11-01 14:51 CST）

用户要求先停止并记录；当前工作树保留在 `fix/bug-fixes`，未提交，原有大量暂存及未暂存差异继续保留。本次审查与整改尚未完成，不应以先前阶段性通过结果作为当前完整工作树的验收结论。

- Controller：Console 97/97、Orchestrator 33/33、Trigger 5/5 已做公开方法职责静态复核；命中链路和运行行为仍按具体项验证。详见 [Console 清单](console-controller-boundary-inventory-2026-11-01.csv)。
- Entity：基线 101 项已逐项分类：82 表行、2 抽象基类、15 查询投影已迁待验证、2 混合表行拆分待验证。详见 [Entity 清单](backend-entity-boundary-inventory-2026-11-01.csv)。新增 Console 真实 Mapper 测试及 JobDefinition 多依赖导出修正尚未运行。
- Service：Trigger 13/13 入口已静态复核；Orchestrator 基线 100 项最近确认 31 项，剩 69 项。Console 165 个候选中 29 个仅部分阅读、仍需逐类确认；Worker 31 个候选中 17 项已核、14 项待核，见 [Worker 清单](backend-worker-service-boundary-inventory-2026-11-01.csv)。这些是候选和复核数，不是缺陷数量；非 Service 命名组件仍需判定。
- 当前未验证的代码包括 Console 下载/Excel 响应、审批人身份、认证、租户守卫、Webhook 限额、JobDefinition 投影及相关 Mapper；SSE 初始发布失败后的显式关闭与维护状态授权检查在暂停时有进行中修改，须先检查 diff 和测试是否完整，不可假定实现完成。
- 数据质量规则 0 行更新与持久化回读的修复已写入；`./mvnw -pl batch-orchestrator -am -Dtest=DataQualityRuleApplicationServiceTest,DataQualityRuleUpdateControllerTest,DataQualityRulePersistenceIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` 按用户要求中断，退出码 130。应用服务测试 2 例在中断前通过；真实 PostgreSQL 测试正在 Flyway 迁移，整条命令及其他测试未完成，须重新执行。
- Console 最近一次统一命令因 `ConsoleTenantConfigPackageExcelController:74` 类型错误在生产编译阶段失败，代理已报告修复，但尚未重编译。此前 `AssetFreshnessPolicyReferenceIntegrationTest` 的两处类型错误也报告已修复，待统一编译确认。完整命令选择应覆盖本轮 Console 新增 Controller/Service 测试及投影 PostgreSQL 集成测试；先核对是否存在并行工作中的半成品。
- 取证 `JSON` / `CSV` 请求实际仍生成 ZIP bundle，现有规范存在兼容别名与产物定义不一致；现有 UI 固定 BUNDLE。保留现状为待裁定候选，继续时核对日志中的请求格式和外部消费者，再确定文档或实现变更，不假定已修。
- 恢复工作时先重新确认分支、HEAD、工作树及上述半成品，再处理遗漏的 `DefaultWorkflowRunLaunchServiceReplayTest` 等实际测试类、Console 统一编译与定向/真实数据库测试、剩余 Service 台账，最后按改动文件范围核对格式与 `git diff --check`。不得把 105/62/73 等先前阶段结果外推到之后的修改。
