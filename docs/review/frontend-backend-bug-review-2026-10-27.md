# 前后端 Bug 审查与验证汇总（2026-10-27）

## 2026-11-01：本地关键边界与前后端联验续查

**后续串行复验（覆盖下方对应的历史待验状态）**：修复复制弹窗租户切换和作业包迟到响应后，`npx vitest run --maxWorkers=2 src/utils/configBatchRecovery.test.ts src/api/configBatchRecovery.test.ts src/api/configBatchMutationKeys.test.ts src/views/system/components/TenantCopyConfigDialog.test.ts src/views/job/JobBundleContextRisks.test.ts` 共 5 个文件、109 项通过，日志 `/private/tmp/wdi-config-batch-frontend-retest-20261101.log`。前述真实 HTTP 回查暴露的 JSON 序列化问题修复后，`./mvnw -ntp -pl batch-console-api -am '-Dtest=ConfigBatchOperationRecoveryServiceTest,ConsoleConfigBatchRecoveryControllerTest,ConfigSyncAuditRecoveryIntegrationTest#shouldRecoverAuthenticatedJobBundleImportWithRepeatedTargets+shouldRecoverAuthenticatedTenantCopyWithoutRepeatingTargetWrites+shouldRecoverAuthenticatedJobBundleCreate+shouldRecoverConfirmedStrictRollbackAfterTargetWrite' -Dsurefire.failIfNoSpecifiedTests=false test` 共 18 项通过，包含 4 项真实认证 HTTP 和隔离 PostgreSQL 用例，日志 `/private/tmp/wdi-config-batch-http-fixed-20261101.log`。这些结果不等同于网络断连后的真实浏览器联验。

**补充静态检查**：当前前端源码 `npm run typecheck`、`npm run check:i18n`、`npm run gen:api:check`、`npm run check:docs` 及本批受影响文件 ESLint 均通过；后端文档结构和脚本治理检查通过。脚本治理首次发现实际触发观察记录的两个 SQL 未列入索引，补充 `scripts/db/README.md` 后重跑通过。两仓的工作区和暂存区 `git diff --check` 均通过。

**后端受影响类串行复验**：`./mvnw -ntp -pl batch-console-api -am '-Dtest=ConfigBatchOperationMapperIntegrationTest,ConsoleIdempotencyRequestIdentityTest,ConsoleIdempotencyInterceptorTest,TenantConfigInitOutcomeTest,DefaultConsoleTenantConfigInitApplicationServiceTest,ConfigBatchOperationRecoveryServiceTest,ConsoleConfigBatchRecoveryControllerTest,ConsoleTenantConfigInitValidationTest,DefaultConsoleTenantConfigCopyServiceTest,DefaultConsoleJobBundleApplicationServiceTest,ConsoleJobBundleControllerTest,TenantWipeScriptsIntegrityIntegrationTest,ConfigSyncAuditRecoveryIntegrationTest#shouldRecoverAuthenticatedJobBundleImportWithRepeatedTargets+shouldRecoverAuthenticatedTenantCopyWithoutRepeatingTargetWrites+shouldRecoverAuthenticatedJobBundleCreate+shouldRecoverConfirmedStrictRollbackAfterTargetWrite' -Dsurefire.failIfNoSpecifiedTests=false -Dtestcontainers.reuse.enable=false test` 退出 1：114 项中 110 项通过、4 项错误、0 断言失败、0 跳过。4 项均属 `ConfigSyncAuditRecoveryIntegrationTest`，首个错误为隔离 PostgreSQL 容器等待启动超时，Spring 上下文未能初始化，业务未执行；剩余 3 项受上下文失败阈值影响。其余包括真实数据库 Mapper 5 项和租户清理脚本 3 项通过；日志 `/private/tmp/wdi-console-target-full-20261101.log`。这次失败不能覆盖同一 4 项此前成功的记录，也不能称本轮全部通过。

超时的 4 项随后以 `./mvnw -ntp -pl batch-console-api -am '-Dtest=ConfigSyncAuditRecoveryIntegrationTest#shouldRecoverAuthenticatedJobBundleImportWithRepeatedTargets+shouldRecoverAuthenticatedTenantCopyWithoutRepeatingTargetWrites+shouldRecoverAuthenticatedJobBundleCreate+shouldRecoverConfirmedStrictRollbackAfterTargetWrite' -Dsurefire.failIfNoSpecifiedTests=false -Dtestcontainers.reuse.enable=false test` 单独重跑，4 项通过，日志 `/private/tmp/wdi-http-four-retry-20261101.log`；此前 114 项整批命令仍记录为失败。

**边界与新增测试状态**：双独立 Orchestrator 进程缓存失效及独立 Worker 请求在途时导入渠道配置的测试已通过，证据见下。浏览器探针已补容器匿名卷清理和断连断言，运行结果见下。仅针对实际复现或确定的契约缺陷修复；复制弹窗跨标签账号切换候选尚无该路径复现，不据此添加生产防御。

**真实浏览器首次尝试**：使用当前源码构建的 Console 可执行包、专属 PostgreSQL/Valkey 与前端快照执行探针，空库迁移成功、Console 就绪且匿名请求返回 401；页面提交和断连阶段探针报错，未取得恢复结果，退出 1。脱敏记录 `/private/tmp/wdi-browser-recovery-run-20261101.log`，本次隔离进程和容器按 owner 清理；具体原因仍在定位，不能作为业务或浏览器联验通过。构建命令 `./mvnw -ntp -pl batch-console-api,batch-worker/dispatch -am -Dmaven.test.skip=true package` 成功，该命令跳过测试。

**真实浏览器第二次尝试**：修复临时 Vite 字体目录白名单并增加阶段诊断后，探针在导入弹窗选择目标租户时超时，尚未提交；日志 `/private/tmp/wdi-browser-recovery-retry-20261101.log`。该次 Vite 日志有依赖优化引起页面重载，但不能据此断定它就是选择超时的原因。测试夹具已增加导航观察与失败诊断，待重新验证；尚无浏览器端原键回查通过证据。

**真实浏览器第三次尝试**：已观测到开发服务器触发主页面重载；重载后探针尝试再次打开导入弹窗时，已有弹窗仍存在而点击超时，尚未发送业务请求。日志 `/private/tmp/wdi-browser-recovery-retry2-20261101.log`；本轮不能证明或否定业务恢复能力，临时夹具待修正。

**真实浏览器最终重跑通过（快照边界）**：修正多选框的实际聚焦、标签选择和目标确认后，`python3 /private/tmp/wdi-browser-recovery-draft/runner.py` 退出 0；真实 Chromium 登录、CSRF、两个目标正式导入，拦截到后端成功及数据库写入后丢弃浏览器响应，刷新页面以原键只读回查，核对页面未知提示、完成提示、逐目标结果、原请求完成标记、配置及修订号，未重复写入。日志 `/private/tmp/wdi-browser-recovery-retry4-20261101.log`；仅清理本轮 owner 资源。此为作业包导入本地链路，不代表租户复制已做同等浏览器断连复验或目标环境验收。运行期间另一任务恢复对共享前端工作树的改动：浏览器使用临时前端副本和此前构建的 Console JAR，隔离库实际迁移至新增 V375；这不是两个仓库最新源码的原子快照，不能外推为最新整仓联验。

**端到端测试编译问题**：双进程用例首次编译因该模块没有 Lombok 而失败，已改用现有 SLF4J 依赖；第二次暴露现有 SDK 测试将列表传给字符串类型的清理服务参数，已按当前服务契约传入单个租户字符串。两次均未运行目标用例，原日志分别为 `/private/tmp/wdi-two-orchestrators-20261101.log` 和 `/private/tmp/wdi-two-orchestrators-retry-20261101.log`；修正后的定向验证结果另记。

**独立进程缓存联验通过**：`./mvnw -ntp -pl batch-e2e-tests -am '-Dtest=RuntimeConfigImportCacheE2eIT#shouldInvalidateIndependentOrchestratorCachesForImportedTenant' -Dsurefire.failIfNoSpecifiedTests=false -Dtestcontainers.reuse.enable=false test` 退出 0，1 项通过。两个不同 Orchestrator JVM 各自预热两个租户缓存，正式导入后核对数据库及目标回执、两进程失效事件、新配置读取和另一租户原值。日志 `/private/tmp/wdi-two-orchestrators-retry2-20261101.log`。本测试采用 120 秒本地缓存和 1 小时对账间隔以隔离自然过期/周期对账，使用测试安全旁路；不代表生产参数、鉴权、混版或目标环境验证。

**独立 Worker 在途导入联验通过**：`./mvnw -ntp -pl batch-e2e-tests -am '-Dtest=WorkerProcessRestartRecoveryE2eIT#shouldCompleteInFlightDispatchWhenChannelIsImported+shouldContinueDispatching_whenWorkerProcessRestarted' -Dsurefire.failIfNoSpecifiedTests=false -Dtestcontainers.reuse.enable=false test` 退出 0，2 项通过。独立 Dispatch Worker 的旧地址请求被真实接收后，测试执行正式导入并确认新地址及目标回执，再放行旧请求，核对任务成功和新地址未收到旧请求；原有进程重启用例同时通过。日志 `/private/tmp/wdi-worker-inflight-target-20261101.log`。导入通过应用服务执行，此项不覆盖 Console HTTP 鉴权或目标部署环境。

**原键回查整改进行中**：已为租户复制及作业包创建、导入接入按原 HTTP 身份登记和逐目标回执；成功回执与目标写入同事务，缺失项保持未知，重复目标按序号区分。前端在正式提交前保存原键，支持同账号、原请求租户及同标签页刷新后的只读回查；用户可确认停止跟踪，该操作不改变服务端结果。历史请求、无键操作、试运行、跨标签页及服务端未登记的请求不保证可回查。当前不保存原请求正文，不自动续跑未确认目标；混合新旧版本、独立角色完整部署及目标环境未验证。

**本批验证与未完成项**：前端 `npx vitest run --maxWorkers=2 src/utils/configBatchRecovery.test.ts src/api/configBatchRecovery.test.ts src/api/configBatchMutationKeys.test.ts src/views/system/components/TenantCopyConfigDialog.test.ts src/views/job/JobBundleContextRisks.test.ts`：5 个文件、94 项通过（`/private/tmp/wdi-config-batch-frontend-final-20261101.log`）；前端 i18n、文档与 API 生成一致性检查通过。首批后端定向运行中，真实 PostgreSQL Mapper 事务及普通角色隔离 5 项通过；另有 1 项纯单测因 Mockito 缺少空回执桩报错，补齐后 `ConfigBatchOperationRecoveryServiceTest` 5 项通过。随后包含真实认证 HTTP 三个新用例的重跑失败：隔离 PostgreSQL 启动超时，三项均未进入业务；同次运行后段出现多个 class 缺失，同时有其他 Maven 进程使用同一项目和 `target` 目录，不能把该次失败或既有单项通过作为当前完整结果。日志分别为 `/private/tmp/wdi-config-batch-recovery-target-20261101.log`、`/private/tmp/wdi-config-batch-service-rerun-20261101.log`、`/private/tmp/wdi-config-batch-recovery-full-target-20261101.log`。待其他任务结束并稳定源码后，应串行重跑受影响类及真实认证用例，再以新版制品运行已准备的真实 HTTP 响应断连回查探针；当前尚无该修复的真实浏览器与后端联验结论。

本批最终前端类型检查因资源竞争由本任务终止，exit 143；补充“停止跟踪”前的类型检查曾通过，但不代表最终源码已复验。停止时误将另一前端任务的 `vue-tsc` 进程识别为本任务进程并发送 SIGTERM，该检查被中断，未修改其文件；随后已停止本任务自己的重复类型检查。两任务的类型检查均需各自复验。

并发期间，工作树索引新增了本批 V373、回查代码和其他任务的 V374 等新文件；本任务未执行暂存，保持观察到的索引状态，不据此推断用户希望提交。其他任务仍在修改和构建同一工作树时，不重新使用共享 Maven `target` 或覆盖其文件。

**租户重置衔接待验**：两份租户重置 SQL 已补删除被清理目标租户的 V373 回执，并删除被清理审计租户的操作及其级联回执；未被清理的审计操作保留原目标列表，已删回执的目标仍为未知。`TenantWipeScriptsIntegrityIntegrationTest` 已补相应隔离数据库案例，但尚未运行。脚本需要已应用 V373 且执行账号具备跨租户清理权限，不曾在共享数据库执行。混版期间旧实例不会登记新回执，旧实例仍处理写请求时不能承诺原键恢复覆盖所有请求。

**前端续核待整改**：复制抽屉的同步 watcher 在租户 store 更新、localStorage 尚未写入时读取请求租户，可能错误显示租户不一致并隐藏待查记录；需保持旧结果同步失效，待 store 写入完成后刷新列表并补真实 setter 顺序的组件回归。作业包页面处理回查和提交的迟到响应时，未再次比较实际请求租户头；跨标签切换后的影响以及成功后本地记录清理失败的提示需补针对性复核和修复。保存原键与拦截器读取租户头之间的跨标签交错尚未复现；该拦截器为同步执行，不能按普通异步微任务竞态认定为现有 Bug。上述事项因配对前端工作树正由另一任务使用，尚未修改或验证。

**并行任务暂停后的复验进展**：复制弹窗用真实 Pinia setter 的切租户用例首红（1 项失败，日志 `/private/tmp/wdi-copy-tenant-switch-red-20261101.log`）；作业包用例模拟另一标签切换请求租户后，旧成功响应错误清除原记录，首红 1 项（`/private/tmp/wdi-job-recovery-late-tenant-first-red-20261101.log`）。两个修复已写入，修复后测试尚待运行。后端 4 条真实认证 HTTP/隔离 PostgreSQL 用例已进入业务，但全部失败：实际响应把 Jackson 2 的 JSON 节点按普通对象输出；原目标写入和已知回滚已发生，首次原始响应的重复目标更新计数为 1，失败位于回查结果或摘要。日志 `/private/tmp/wdi-config-batch-http-retry-20261101.log`。已将响应边界改为目标回执 record 和普通元数据对象；需修正相关测试并重跑后才能称为修复通过。另一标签切换账号时复制弹窗的迟到响应目前只有源码候选，尚无该具体路径的真实浏览器复现。

**原始目标**：完成本地可执行的配置导入、缓存失效和在途任务；事务、锁、租约与副作用；SQL 状态、租户隔离、归档恢复；配置复制与作业包未知结果、跨账号权限、浏览器真实后端操作及告警最终收件验证。分别记录当前源码、实测层级、未验证和目标环境依赖，不以独立测试结果推断完整链路。

**工作区与授权**：后端 `/Users/dengchao/Downloads/file-batch-system`、前端 `/Users/dengchao/Downloads/batch-console` 开始时均为 `perf/local-loadtest-2026-10-31`；审查期间两仓当前分支被重命名为 `fix/bug-fixes`，HEAD 和工作区路径未变，既有未提交改动保留。隔离 Testcontainers 和本地测试接收端用于本轮验证；本机现有共享 Compose 与其数据不作为写入目标。未发送外部通知。

**环境重建后的角色用例复验**：`./mvnw -ntp -pl batch-console-api -am '-Dtest=ConfigCacheInvalidationOutboxIntegrationTest#shouldRequireTableGrantsAndPolicy_whenDeliveryRoleClaims' -Dsurefire.failIfNoSpecifiedTests=false test`：隔离 PostgreSQL 等真实依赖启动，指定方法 1 项通过、0 失败、0 错误、0 跳过，exit 0；日志 `/private/tmp/wdi-outbox-role-method-20261101.log`。该结果只覆盖该方法的表授权、RLS 策略和领取边界，不代表分角色部署的整条链路已验收。

**待业务决定**：共享文件多作业归档的回看口径（首次、逐作业快照或最新状态），以及显式补跑作业的请求日历与历史冻结日历归属，均不预设答案、不按 Bug 修改。两项分别需要业务方裁定；其他验证继续。

**隔离数据库定向验证 PASS**：`./mvnw -ntp -pl batch-orchestrator -am '-Dtest=WorkflowArchiveIntegrityIntegrationTest#shouldSerializeWorkflowNodeAttachmentAgainstJobInstanceDelete+shouldRetainHotTreeAndRollbackColdNodesWhenRootCopyFails+shouldAcceptMatchingColdCopiesWhenRetryingArchive,ArchiveColdStorageIntegrationTest#shouldSerializeCompensationAssociationWithArchive+shouldNotLoseCommittedStepUpdateDuringArchive+shouldRetainHotTree_whenLogReferencesForeignTenantInstance,MultiTenantIsolationIntegrationTest#shouldNotEnforceTimeout_whenLastRunningTaskWasReclaimedAfterScan+shouldNotExposeJobInstanceAcrossTenants+shouldRejectForgedCrossTenantWorkflowAssociation' -Dsurefire.failIfNoSpecifiedTests=false test`：9 项通过，0 失败、0 错误、0 跳过；PostgreSQL 17.11 隔离容器从空库应用 371 个 Flyway 迁移。覆盖归档并发锁、复制失败回滚和重试、跨租户保护及租约回收后的旧超时候选；不代表全部事务或目标环境通过。完整日志 `logs/verification/local-boundaries-2026-11-01/archive-lock-isolation.log`。

**前端提示 PASS，恢复仍未完成**：配置复制、作业包导入和创建向导的正式请求遇网络中断、5xx 或冲突时提示结果可能未知并要求核对目标；明确 400 和预演不误提示。两个组件测试文件 61 项通过，定向 ESLint、i18n、`npm run check:docs` 和最后一次 `npm run typecheck` 通过。该提示不保存原请求或支持跨刷新回查，不能称为持久回执恢复；同版真实浏览器与后端待执行。

**缓存失效竞态首红（历史）**：`OrchestratorConfigCacheRefillRaceIntegrationTest#shouldNotRefillOldDefinitionAfterInvalidation` 在隔离真实 Redis 和受控 Mapper 下固定旧读取→失效→旧读取回填，后续读期望“新配置”实际得到“旧配置”；1 项失败、0 错误、0 跳过，Maven exit 1，首红日志 `/private/tmp/config-cache-refill-race-first-20261101.log`。该首红确认缓存层交错缺陷；后续补充了下述真实 PostgreSQL 配置导入联合验证。相同源码模式涉及作业、Workflow、日历、窗口、配额及本地正负缓存；后续已实现局部修复并完成下述定向验证；Cluster 键槽、混合版本及容量边界仍须分别验证。

**失效发布故障首红及后续验证**：修复前源码中 Console 在配置提交后先删除旧键，再推进 Redis 全局和键版本并广播；异常只记录日志/指标，ConfigSync receipt 对账不补发失效，也不覆盖直接初始化、复制、Excel 及常规编辑。全局版本推进后键版本失败、或 Redis 整段不可用期间提交后恢复，可能使旧 v2 值继续命中；首轮定向注入因测试 JDBC 使用未规范化大写策略码查询不到行而失败，未到恢复断言，日志 `/private/tmp/config-invalidation-key-failure-first-20261101.log`。夹具修正后已取得有效首红：真实 PG/Redis 的配置导入和成功回执均已确认，定向注入一次配额 key revision 写入失败，全局版本 1→2、键版本 1→1；真实 subscriber 对账后 30 秒仍读取旧配额 10，而数据库新值为 20。1 项因 Awaitility 超时报错、exit 1，日志 `/private/tmp/config-invalidation-key-failure-rerun-20261101.log`；另在专属非复用 Valkey 容器暂停期间实际提交导入和成功回执，恢复后清本地缓存，全局/配额版本均 1→1，30 秒仍读旧值 10（预期 20）；1 项 Awaitility 超时报错、exit 1，日志 `/private/tmp/config-invalidation-outage-first-20261101.log`。测试确认自有容器已解除暂停并恢复读取。两类故障均已复现；后续持久队列的同 JVM 真实 PG/Valkey 恢复用例已通过，见下文。旧实例兼容与目标环境仍需核查。

**事务缓存定向首红**：`OrchestratorConfigCacheServiceTest` 的事务隔离与活跃事务回源场景 5 项失败、0 错误、0 跳过，显示 RR/Serializable 仍会读缓存，READ COMMITTED 事务的旧正值和旧缺失仍可回填本地缓存；日志 `/private/tmp/config-cache-transaction-first-20261101.log`。这属于受控单测；真实 PostgreSQL/MyBatis 会话交错的联合用例随后已通过，见下文。Workflow 重放预览及启动存在明确的固定快照调用路径，性能与故障恢复边界仍需另验。

**状态契约及 SDK 恢复局部 PASS**：本轮 `LeaseParentLifecycleSqlContractTest`、`CapacityProfileMapperXmlTest#shouldMatchJobLifecycle_whenClassifyingCapacityProfiles`、`JobInstanceStatusSqlContractTest` 共 6 项通过，属于 Mapper XML 与状态枚举集合对照；`SdkOrdinaryLeaseRecoveryE2eIT` 的 3 个指定方法通过，覆盖隔离独立进程提交后强杀、已提交报告响应断连及幂等任务经 Outbox/Kafka 恢复。日志 `logs/verification/local-boundaries-2026-11-01/sdk-dispatch-status.log`；这不是所有 Mapper/真实供应商验收。独立 Dispatch 的本地 API_PUSH 接收持久化后崩溃用例也通过：重领后收敛为 UNKNOWN，接收台账仅一条；执行 JAR 与当前编译输出的 Dispatch 136、worker-core 204、common 417 个 class 逐项一致。完整命令：`./mvnw -ntp -pl batch-e2e-tests -am '-Dtest=LeaseParentLifecycleSqlContractTest,CapacityProfileMapperXmlTest#shouldMatchJobLifecycle_whenClassifyingCapacityProfiles,JobInstanceStatusSqlContractTest,SdkOrdinaryLeaseRecoveryE2eIT#shouldBlockUnsafeEffectAfterSdkProcessIsKilled+shouldRecoverSdkReportAfterCommittedResponseIsDisconnected+shouldRecoverSafeEffectThroughProductionOutboxAndKafka,WorkerReportLostAfterBusinessCommitE2eIT#shouldNotRedeliverAfterWorkerCrash_whenApiPushAcceptedBeforeOutcomePersisted' -Dsurefire.failIfNoSpecifiedTests=false test`，SQL 6 项和 E2E 4 项通过，exit 0。

**缓存修复定向单测 PASS**：`./mvnw -ntp -pl batch-orchestrator -am '-Dtest=OrchestratorConfigCacheServiceTest,OrchestratorRedisSupportRevisionTest,OrchestratorConfigInvalidationSubscriberTest' -Dsurefire.failIfNoSpecifiedTests=false test`：当前源码 35 项通过、0 失败/错误/跳过；日志 `/private/tmp/config-cache-unit-final-20261101.log`（此前 34 项日志 `/private/tmp/config-cache-unit-fixed-20261101.log`）。局部修复为按租户/类型的通配和精确版本区分共享缓存、本地失效代次保护回填、显式固定快照事务直接查库及禁止活跃事务回源污染缓存，并对已确认的 Redis 命令超时作降级。该结果不证明真实 PG 并发、双实例/Worker 联合、Redis 故障期间提交后的恢复、key revision 部分失败、版本回退、混合旧实例或性能；上述边界仍开放。

**未知结果恢复的现状和约束**：ConfigSync 在目标提交同一事务中写目标回执，并以原请求键建立审计身份；复制与作业包旧入口没有复用该身份。复制会重新读取源配置，故新请求可能对应不同快照；ConfigSync 当前也不在服务端保存完整导入正文。可靠的原键对账需明确操作类型和键命名空间，防止污染既有同步日志或跨入口键冲突；仅所有目标都有收据时可确认终态，缺收据仍为未知。自动补跑还需要保护原始快照并围栏在途请求，不能依据找不到状态就自动换键重发。此段为当前源码核查和待实施设计约束，非已完成恢复。

**持久失效测试首次环境失败与重试**：`./mvnw -ntp -pl batch-console-api -am -Dtest=ConfigCacheInvalidationOutboxIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` 在专属 Testcontainers Kafka 启动时等待 `RECOVERY → RUNNING` 超时，3 项均为上下文初始化错误，未执行业务断言；exit 1，首错日志 `/private/tmp/config-outbox-isolation-20261101.log`。此前同类旧版源码 3 项通过不覆盖本次测试隔离改动。随后按受影响的三个类进行单次串行重试，同机专属 Kafka 启动成功，当前版本 `ConfigCacheInvalidationOutboxIntegrationTest` 3 项通过、0 失败/错误/跳过；两类 Redis E2E 随后也通过，整体命令 exit 0，日志 `/private/tmp/config-invalidation-three-target-retry-20261101.log`。未清理用户的共享服务或将环境失败归因于业务逻辑。

**持久失效本地定向复验 PASS**：`./mvnw -ntp -pl batch-e2e-tests -am '-Dtest=ConfigCacheInvalidationOutboxIntegrationTest,ConfigCacheInvalidationRecoveryE2eIT,ConfigInvalidationReconcileOrderingE2eIT' -Dsurefire.failIfNoSpecifiedTests=false test` exit 0；真实 PostgreSQL 的队列事务和领取隔离 3 项、真实 PG/Valkey 的局部版本写入失败与 Redis 暂停恢复 2 项、广播丢失与对账交错 1 项，合计 6 项通过、0 失败/错误/跳过。日志 `/private/tmp/config-invalidation-three-target-retry-20261101.log`；故障容器已解除暂停。该验证为隔离依赖及同 JVM 服务/订阅器，不覆盖独立进程、分角色部署、Redis 已确认后的数据回退、混版或吞吐容量。

**独立主库角色授权工具 PASS**：新增 `scripts/db/config-cache-invalidation-role.sql`，由 DBA 显式指定既有可信角色和 grant/revoke，不创建账号、不授权 PUBLIC，也不更改已有 schema、V232 或其他业务权限；重复执行和冲突检查均在同一事务内。`./mvnw -ntp -pl batch-console-api -am '-Dtest=ConfigCacheInvalidationRoleSqlIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test` exit 0，隔离 PostgreSQL 空库执行全部 372 项 Flyway 迁移，2 项通过、0 失败/错误/跳过，覆盖含空格和引号角色、权限与 RLS 隔离、重复授权/撤销以及非法参数和策略冲突。日志 `/private/tmp/wdi-config-role-sql-target-rename-20261101.log`；`check-script-governance.py`、文档结构和差异空白检查通过。此为脚本和数据库局部验证，未对共享或目标数据库执行授权，Spring 分账号部署及原有 V232 函数权限仍待验证。

**持久失效运行成本**：当前 V372 对受保护配置表变更写入待投递记录，成功提交后既有立即失效和 relay 后续投递可能各推进一次版本并发布事件。重复投递是现有实现事实，是否影响吞吐和资源尚未测量，不能推断性能瓶颈；也不为消除此重复引入未经验证的确认协议。

**持久失效独立数据库角色验证**：V372 默认只对 Flyway 执行账号建立策略，而 Console primary 账号可独立配置。隔离 PostgreSQL 空库执行真实 372 项迁移后，以独立 LOGIN、NOSUPERUSER、NOBYPASSRLS 账号和真实 MyBatis Mapper 验证：仅有表权限但无专属 RLS 策略时 `lockNext()` 返回空；授予可信角色专属策略后可领取、认领和确认，普通角色仍不可见；手动写入另需 INSERT 与序列 USAGE。探针退出 0，日志 `/private/tmp/wdi-config-role-20261101-ZtHfBk/run.log`，专属容器已清理。新增的同类 JUnit 仍未运行；该探针不证明 Spring 分账号部署或 V232 等既有函数权限已配置。当前未修改生产授权，不能以默认同账号的通过结果覆盖分角色部署。

**作业包提交后响应丢失已复现**：使用当前 Console 可执行 JAR、独立 PostgreSQL/Valkey 和真实登录、CSRF，经本机代理转发真实 Job Bundle 导入；代理在收到服务端 200 和完整结果后关闭客户端连接。两个目标的提交由原始响应和数据库独立核对：相同原键再次提交返回 409、两目标 revision 未变化；换新键再次提交时两目标 revision 均增加。`python3 /private/tmp/wdi_loss_runner.py` exit 0；探针及 SQL、日志保留于 `logs/verification/local-boundaries-2026-11-01/job-bundle-response-loss/`，探针 SHA-256 为 `ce4ed444f1a3b1af5ac4bb2b656ae28db989daa615a936ce2ad51dce9f2c8171`。4 次此前尝试分别停在文件存储配置、PG 初始化 stderr 缺失、内部密钥配置和临时探针命名冲突；均未提交业务，首错保留于 `/private/tmp/wdi-loss-run-{yy04qev6,sqo336rc,krp_gj0w,bno79i7_}/`。本轮仅证实这个实际 HTTP 丢响应路径及现有键行为，不代表原键回查已实现、浏览器通过或部分目标事务异常已复现；专属容器与进程已清理。

**未知结果恢复的未定方案**：Job Bundle 和租户复制原键回查接口及持久恢复尚未实现。两条针对未定接口的新增测试草稿未执行、不会证明真实网络断连，已备份至 `/tmp/wdi-unimplemented-recovery-tests.patch` 后从测试类移出，原有测试内容保留。随后针对现有作业包导入的真实响应丢失用例执行单方法测试，但专属 Kafka 启动超时，测试上下文未完成、业务断言未执行，首错 `/private/tmp/wdi-response-loss-20261101.log`；本轮新增的未验证测试已另行备份并从工作区移出。复制预览只能核对当前数据，不能证明原请求的逐项目标结果。

**前端 E2E 类型检查边界**：`npx tsc --noEmit -p e2e/tsconfig.json` 失败，首错为已有测试缺 Node 类型；显式加入 Node 类型后仍有其他存量 E2E 类型或路径错误。仅针对 `closure-recovery-real.spec.ts` 及其依赖用临时配置补 Node 与路径别名检查通过，不代表全 E2E 类型通过；真实浏览器执行仍待完成。

**真实浏览器隔离环境，执行中**：当前源码以 `./mvnw -ntp -pl batch-console-api -am -DskipTests package` 打包成功（测试跳过，不计为测试通过），仅用于专属 PG/Valkey、Console 与 Vite 的浏览器验证；不会复用本机共享 Compose。隔离库已迁移至 V371，Console 关闭安全旁路且匿名 `/api/console/auth/me` 返回 401；原浏览器用例首轮因缺少项目版本 Chromium 未进入测试，首错 `/private/tmp/wdi-closure-20261101-wXMNeR/playwright-original.log`，已在私有临时目录安装对应浏览器。原有 ConfigSync 用例随后进入业务，两个目标提交和回执断言执行后，页面等待同步日志标签超时；当时 Vite 正在依赖优化并重载，环境因素是待验证候选，尚未排除页面问题。该轮日志 `/private/tmp/wdi-closure-20261101-wXMNeR/playwright-original-retry.log`，现正单例复验；测试独占租户和故障触发器清理计数均为 0；本次 Console/Vite 进程已退出、19880/15175 无监听、专属标签容器为 0。首错与通过日志检查无测试口令后已复制到忽略目录 `logs/verification/local-boundaries-2026-11-01/`。

**跨账号会话风险，修复中**：真实浏览器 trace 显示登录页在 logout 204 后再次执行兜底 logout，两次复用同一自动幂等键而出现 409；自动键未包含会话身份，快速跨账号切换存在错误复用风险。另有旧配置页请求在登出后返回 401 并触发 token 刷新；旧会话 401 在账号切换后仍发起刷新已由受控测试先红（`/private/tmp/wdi-session-stale-first-20261101.log`）。现针对 logout 停止自动注入幂等键，并让请求捕获会话代次、阻止旧错误刷新/重试/提示；`interceptors.integration.test.ts` 与 `auth.test.ts` 共 43 项通过，目标 ESLint 通过。真实浏览器进一步复现跨标签页 Cookie 竞态：旧标签页的 logout 204 被延迟，新标签页成功登录且 `/auth/me` 返回 200，释放旧响应后新会话变为 401；首红 `/private/tmp/wdi-closure-20261101-wXMNeR/playwright-cookie-first.log`。内存代次本身不能阻止浏览器处理 Set-Cookie；当前版本已以同源 Web Locks 协调密码登录/登出/换发，并以 Chromium 双标签验证该复现时序。旧标签、无锁/非安全上下文、OIDC 回调和超时后响应仍未覆盖；refresh 等其他写 Cookie 路径尚未完整验证。本轮仅使用隔离账号与数据。

**认证追踪续项**：后端 `/auth/token` 成功响应不返回 accessToken，前端此前却要求该字段，真实形态单测先红 `/private/tmp/wdi-refresh-cookie-first-20261101.log`；修复应按解包后的成功响应判断，不能向浏览器脚本泄露 token。登录页挂载时无条件 logout 会使另一已登录标签失效，组件测试先红 `/private/tmp/wdi-login-page-logout-first-20261101.log`，已移除该历史兜底；失效 Cookie 公开登录先有过滤器测试，现也已通过隔离 Chromium 真实登录专项。当前认证、登录页、维护和降级等 6 个受影响测试文件合计 66 项通过，日志 `/private/tmp/wdi-auth-final-66-20261101.log`；最终 `npm run typecheck` 也已通过（`/private/tmp/wdi-auth-final-typecheck-20261101.log`）。

**第二次隔离浏览器环境首错**：Console 启动时遗漏 `BATCH_PLATFORM_DB_*`，错误尝试默认 `localhost:15432` 并因空密码认证失败，未完成认证或执行 SQL；浏览器因此收到 502，未进入业务。原日志 `/private/tmp/wdi-auth-lock-20261101-eNQEbt/playwright.log`。随后确认独立启用的 read-replica 使用独立配置，通用数据源变量不能覆盖其默认地址；第二次进程启动日志仍出现该默认目标。已停进程、核对并禁用该功能，重启时所有 DB/Redis/存储指向专属目标或不可达端口，实际建立 TCP 连接仅见专属 PG 61073，匿名请求返回 401。浏览器专项结束后测试租户及故障触发器均为 0，专属 Console/Vite 已退出、19880/15175 无监听、专属标签容器为 0。不得把本次写成全程未尝试共享地址。

**跨标签 Cookie 协调，待浏览器复验**：对当前版本同源页面的密码登录、登出和换发请求使用 Web Locks 排队，锁等待支持超时和取消，不抢占持锁请求；旧登录加密迟到和排队期间会话变化均有受控首红。当前源码的 8 个受影响前端测试文件 74 项、ESLint 和 typecheck 通过，日志 `/private/tmp/wdi-auth-lock-snapshot-final-20261101.log`、`/private/tmp/wdi-auth-lock-snapshot-typecheck-20261101.log`。无 Locks/非安全上下文沿用原行为，旧标签、未参与客户端、OIDC 回调与超时后迟到响应均不能视为已覆盖；隔离 Chromium 已通过两个专项：真实旧登出响应扣留时新标签登录排队并最终保持新账号，以及失效 HttpOnly Cookie 下不自动登出并成功登录；日志 `/private/tmp/wdi-auth-lock-20261101-eNQEbt/playwright-final.log`。该结果不覆盖旧标签/OIDC/其他浏览器或无 API 环境。标准语义见 [Web Locks 规范](https://www.w3.org/TR/web-locks/)，适配边界见前端浏览器支持文档。

**跨标签复现数据库范围**：临时认证复现环境迁移时读取到了正在编写、尚未稳定的 V372；它仅作为旧 Console JAR 的隔离认证复现数据，不构成迁移成功或同版后端验证。最终迁移验证必须在 V372 稳定后使用全新隔离库重建。

**跨标签身份状态候选**：浏览器 Cookie 在同一存储分区共享，但当前 `authSession.ts` 代次按页面内存保存。一个标签切账号后，另一个标签可能继续展示旧 profile/权限，而请求使用新 Cookie；隔离 Chromium 双标签已实测旧页显示 admin、同页 `/auth/me` 实际为 auditor；正常关闭引导后从旧页面真实登出，会将新标签 auditor 会话从 200 变为 401。日志 `/private/tmp/wdi-cross-account-20261101-F5zWmQ/playwright-second.log`；首轮因引导遮罩未能完成操作的日志亦保留。该轮使用先前 Console JAR 与尚未稳定的 V372 临时库，仅证明认证身份错配，不构成当前后端或迁移验收；本轮 fixture 租户及故障触发器均清理为 0，专属进程、端口和标签容器已退出。需核对跨标签共享代次、休眠恢复与在途请求，不把锁串行或单页账号切换视为该问题已解决。

**跨标签当前版本浏览器局部 PASS**：前端共享会话代次、恢复时身份复核和旧登出确认拦截，相关受影响前端测试 89 项、typecheck 和目标 ESLint 通过。当前版本使用同版 Console JAR、专属 PG/Valkey 和 Chromium 验证：首轮 3 项中认证锁及失效 Cookie 登录 2 项通过，跨标签项在认证断言前被延迟新手引导遮挡；只修正引导等待并重跑该项，1 项通过。该项核对旧 admin 确认框打开后 auditor 登录、旧页及 `/auth/me` 变为 auditor，确认旧框不发送 logout 且 auditor 会话仍有效。测试源码两次间只改引导等待，后端和前端业务源码摘要一致；首错与复验分别见 `logs/verification/local-boundaries-2026-11-01/current-auth-playwright-first.log`、`current-auth-playwright-retry.log`，fixture 租户及故障触发器均清理为 0。未协调的旧标签/客户端、OIDC、超时迟到响应和其他业务确认框不在本次通过范围；补充源码复核：配置发布、租户复制和作业包页面已监听主体或权限变化并使旧操作代次失效；不能仅凭局部确认函数推断这些页面会以新身份提交。文件重派、归档和到达确认等其他操作仍需实际双账号时序验证，尚未证实越权写入，未据此添加防御。

**JWT 撤销容忍期已复现，修复中**：真实签发/解码器与受控撤销存储测试证实普通 token 的撤销 TTL 少覆盖 clock skew，且过 exp 但仍在容忍期的 token 注销后仍可认证；日志 `logs/verification/local-boundaries-2026-11-01/auth-first-red.log`。已令撤销 TTL 覆盖实际接受窗口并保留存储异常传播；首红方法及新增边界 6 项通过，两类完整回归共 36 项通过；真实 Redis 和多节点时钟仍未验证。

**认证过滤器异常已复现，修复中**：有效 Cookie 的 `filterChain.doFilter` 与鉴权共用 `catch(Exception)`；受控负例证实下游异常逸出时公开路径执行两次、私有路径吞掉异常并误处理为鉴权失败。适用范围限未被下游框架处理的异常；首红日志 `logs/verification/local-boundaries-2026-11-01/auth-first-red.log`，已将下游调用移出认证异常捕获；首红方法及新增边界 6 项通过，两类完整回归共 36 项通过。

**认证相关类完整定向回归 PASS**：`./mvnw -ntp -pl batch-console-api -am '-Dtest=ConsoleAuthenticationFilterTest,ConsoleJwtServiceTest' -Dsurefire.failIfNoSpecifiedTests=false test` exit 0；过滤器 16 项、JWT 20 项均通过，日志 `logs/verification/local-boundaries-2026-11-01/auth-classes.log`。无撤销 store、无 jti 沿用原兼容行为，极端 skew 配置及真实 Redis/多节点时钟不在通过范围。

**认证响应类型校正**：生成 OpenAPI 类型保留可选 `accessToken` 供旧客户端兼容，当前后端 Cookie 响应不返回它。已删除前端内部错误必填字段和未被生产调用方消费的 token 返回值；相关 9 个测试文件 84 项、ESLint 和 typecheck 通过，日志 `/private/tmp/wdi-auth-wire-final-suite-20261101.log`、`/private/tmp/wdi-auth-wire-final-typecheck-20261101.log`。最近两项浏览器通过先于该内部类型变更，未在此变更后重跑。

**ConfigSync 真实浏览器局部 PASS**：隔离浏览器与关闭安全旁路的同版 Console、独占 PostgreSQL/Valkey 联验，`closure-recovery-real.spec.ts` 原有“真实 ConfigSync 核对”单例 1/1 通过：真实登录与 CSRF、真实 HTTP 导入和受控审计故障后，两个目标及回执提交，浏览器核对转 SUCCESS，刷新及数据库回读无重复。首次导入由 `page.request` 发起，不是页面按钮；日志 `/private/tmp/wdi-closure-20261101-wXMNeR/playwright-original-direct.log`。此前经仪表盘导航的两次超时保留，不据此认定根因；页面按钮与跨账号专项最终 1/1 通过（43.2 秒）：页面提交两个独占目标、预设审计故障后回读两个目标及回执，页面切换 auditor/admin 验证写入口权限和原待核查隔离，刷新后按同键核对至 SUCCESS，数据库无重复；网络哨兵仅允许该请求的预期 500。最终日志 `/private/tmp/wdi-closure-20261101-wXMNeR/playwright-page-fifth.log`。首次选择器遮挡及第三轮 logout 409 / 旧请求 401 首错分别保留于 `/private/tmp/wdi-closure-20261101-wXMNeR/playwright-page-first.log` 和 `playwright-page-third.log`。该浏览器结果产生于移除登录页预清理之前，最后源码版本需重跑；证据限此隔离环境和 ConfigSync 场景，不能外推旧复制/作业包持久恢复或所有账号并发路径。

**CI 分片静态检查 PASS**：五个新增 E2E 已登记到 Full CI 与 Staging 的独立分片；`bash scripts/ci/check-e2e-shard-coverage.sh` 验证两份清单覆盖当前 54 个类、无漂移，`actionlint .github/workflows/full-ci-gate.yml .github/workflows/staging-gate.yml` 退出 0。该结果不代表 E2E 实际通过或远端 CI 已执行。

**隔离 Redis 复验局部 PASS**：上述联合 reactor 中 `OrchestratorConfigCacheRefillRaceIntegrationTest` 与 `OrchestratorConfigCacheServiceIntegrationTest` 各 3 项、合计 6 项通过，覆盖已复现的迟到旧读取及缓存基础路径；后三个跨服务 E2E 仍未结束。缓存键与事务语义已同步至 [配置治理运行手册](../runbook/config-governance.md)，其中明确 Redis 故障、旧实例和性能限制。

**配置导入与缓存本地联合 PASS**：`RuntimeConfigImportCacheE2eIT` 4 项通过、0 失败/错误/跳过；真实 PG/Redis 下核对配置同步与目标回执、同 JVM 两份独立缓存和订阅器、固定快照事务及 READ COMMITTED/MyBatis 的旧正值/旧缺失，含只读 REQUIRES_NEW 与外层事务恢复。日志 `/private/tmp/config-runtime-joined-20261101.log`。这是修复后结果；不等同两个独立进程或 Redis 故障恢复验收；Dispatch 结果见下。

**真实 Alertmanager 本地联合 PASS**：`AlertmanagerDeliveryChainE2eIT` 1 项通过、0 失败/错误/跳过。隔离 PostgreSQL 中经真实 Orchestrator 告警服务提交，异步发布到真实 Alertmanager，再经真实 Console HTTP 到本地 Webhook；接收端先返 503 后成功，验证同阶段幂等键、发生及关闭后的恢复通知和数据库回执。应用共进程装配且租户权限为测试替身、本地接收端非真实供应商；不代表生产权限、独立部署或外部供应商验收。日志 `/private/tmp/config-runtime-joined-20261101.log`，配置 E2E 结果另见本节。

**修正测试装配后联合 PASS**：`./mvnw -ntp -pl batch-e2e-tests -am '-Dtest=OrchestratorConfigCacheRefillRaceIntegrationTest,OrchestratorConfigCacheServiceIntegrationTest,RuntimeConfigImportCacheE2eIT,RuntimeConfigImportDispatchE2eIT,AlertmanagerDeliveryChainE2eIT' -Dsurefire.failIfNoSpecifiedTests=false test` exit 0、BUILD SUCCESS；真实 Redis 6 项和三类 E2E 共 6 项全部通过，0 失败/错误/跳过。Dispatch 场景先冻结旧渠道，再实际导入新配置并核对回执，最后由真实 Worker 在旧 LOCAL 目录投递而新目录无信封；使用隔离 PG/Redis/Kafka、同 JVM Worker 和手动驱动出箱，非独立进程或外部渠道。日志 `/private/tmp/config-runtime-joined-20261101.log`。

**验证快照说明**：SDK/SQL reactor 启动后，`OrchestratorRedisSupport.java` 的 import 顺序和日志换行曾被调整；本批复用格式调整前的类文件，已核对仅为空白与 import 顺序变化。后续格式检查及相关验证应按最终源码重核，不将格式调整后的源码误称为本批重新编译。

**首次运行失败记录**：`./mvnw -ntp -pl batch-e2e-tests -am '-Dtest=RuntimeConfigImportCacheE2eIT,RuntimeConfigImportDispatchE2eIT,AlertmanagerDeliveryChainE2eIT' -Dsurefire.failIfNoSpecifiedTests=false test`；原始日志 `/private/tmp/wdi-local-boundary-e2e-20261101.log`。首个告警用例 FAIL：复用测试应用时无关的 Import Worker 在就绪事件自注册读超时；双缓存用例 FAIL：MOCK Web 环境未绑定 Worker 端口。Dispatch 用例 FAIL：测试专用 Console 扫描漏掉 `DownstreamFallback`。三类均是测试应用装配问题，业务断言尚未执行，整个命令 exit 1；随后修正测试装配并按下述联合 reactor 定向复验通过，首错不直接归因于生产逻辑。

## 2026-11-01：短断言、薄包装与 Map 候选复核续记

两仓分别位于 `/Users/dengchao/Downloads/file-batch-system` 和 `/Users/dengchao/Downloads/batch-console`；开始时为 `perf/local-loadtest-2026-10-31`，审查中同一 HEAD 上重命名为 `fix/bug-fixes`，保留已有未提交改动。原始分母是短断言 321 项、后端薄包装 110 项和 public Map 返回值 60 项，逐编号或逐方法结论仍以本报告相应台账为准，不外推到清单以外的测试、Map 入参和接口。

- 后端短断言 B001–B104 对照当前方法、输入、断言和相邻测试逐项复核，涉及 54 个文件的摘要均与台账快照一致；99 项保留，5 项保留先前增强。B105–B208 对照当前方法及相关上下文，四个已删除的 Worker 上下文非空用例仍由对应真实启动与注册端口测试覆盖。B123/B124 补验证参数错误码及必填国际化 key；B136 补充空 Workflow ID 时节点、连线及作业 Mapper 无调用；B144 补充 misfire 日志中的实际触发名；B156 更正固定哈希样本的说明和名称，避免误称实时读取迁移；B194 同时验证故障渠道熔断和正常渠道放行。B143 的必填 Bean 注入在同类测试中虽会先执行，仍保留其独立装配检查以支持单独运行和定位。相关受影响类已随下述定向命令通过。
- 前端短断言 F001–F113 对照当前断言与相邻场景复核；F015 更名为准确描述的只读回退，F023/F024 验证实际 Plus/Menu 组件，F068/F069 精确验证密码强度边界与上限，F100–F103 验证具体日志及仅在属性中出现的筛选词。`npx vitest run src/utils/iconResolve.test.ts src/utils/passwordGenerator.test.ts src/utils/logger.test.ts src/constants/theme.test.ts`：4 文件 40 项通过，日志 `/private/tmp/wdi-short-assertion-fe-final-20261101.log`；四个文件的 Prettier 检查和差异空白检查通过。未运行浏览器 E2E。
- 60 项 public Map 返回值的当前文件摘要与台账一致，逐项对照方法实现及已记录的调用边界；保留动态参数、插件和 JSON/Excel 既有契约，不因词法形式强行改为对象。110 项后端薄包装已逐方法对照当前实现和调用位置：原有 9 项已简化，本轮再移除 2 项纯转发，其余 99 项保留；生产 profile 判断和配额策略转换保留原求值分支及回退语义。受影响类已随下述定向命令通过。

后端受影响类验证：`./mvnw -ntp -pl batch-console-api,batch-orchestrator,batch-trigger,batch-worker/atomic,batch-worker/dispatch -am '-Dtest=ConsoleAiPromptGuardTest,ConsolePasswordHasherTest,WorkflowGraphValidatorTest,MisfireHandlerIntegrationTest,DispatchChannelCircuitBreakerTest,AtomicRuntimeStatusServiceTest,DatabaseQuotaRuntimeStateServiceTest' -Dsurefire.failIfNoSpecifiedTests=false test`，exit 0，7 类合计 98 项通过，0 失败、0 错误、0 跳过；日志 `/private/tmp/wdi-candidate-final-targeted-20261101.log`。其中 misfire 测试使用隔离 Testcontainers，其他为受控单测；编译依赖模块不代表运行了全部模块测试。

台账的 SHA-256 仍是原建表快照，并非本轮修改后的摘要；本轮增强和更名会使对应文件摘要变化。候选结论为源码/测试设计判断；只有明确列出并完成的运行命令才是执行证据。

## 2026-11-01：候选逐项台账与验证进度

两仓在原 `perf/local-loadtest-2026-10-31` 工作区继续，当前分支名为 `fix/bug-fixes`。原 321 个短断言候选已按[逐项台账](#2026-11-01短断言候选逐项处置台账)记录处理：290 项保留，22 项增强，4 项删除，1 项合并并增强，4 项更名；此分母包含先前批次的改动，不代表全工程所有测试。原 110 个后端薄包装和清单生成器列出的 60 个 public Map 返回候选也已逐项登记；Map 入参、隐式 public 和清单之外的其他形式不在该分母内。下方各批“未完成”描述为当时状态，以本节及各台账为当前进度。

本轮另将 TypeScript SDK 默认错误码断言改用独立协议字面量；前端时间戳测试补 UTC 精确值，HTML 清洗测试验证清洗器输出而非仅验证恒等 mock，认证状态测试更名以反映 session 标记。诊断页 E2E 的集群区块断言限定于集群卡片标题并使用现有国际化来源；E2E 用例列表可加载、对应组件测试通过，真实后端浏览器执行尚未完成。

4 个 Worker 保留的注册端口测试均在隔离容器的真实依赖上通过；OTel 空上下文定向测试通过。5 个字符串转换包装的受影响类测试已通过；浏览器 E2E 尚未执行。

## 2026-11-01：本轮受影响测试验证

- `./mvnw -ntp -pl batch-orchestrator,batch-worker/import -am '-Dtest=ImportRecordGovernanceServiceTest,ImportDataQualityServiceTest,DefaultWorkflowDagServiceTest,DefaultRetryGovernanceServiceTest,FileGovernanceMetricsCacheServiceTest' -Dsurefire.failIfNoSpecifiedTests=false test`：五个测试类合计 90 项通过，0 失败、0 错误、0 跳过。新增 `ImportDataQualityServiceTest` 核查缺失上下文、缺失载荷和非字符串载荷；日志 `/private/tmp/string-wrappers-review-20261101.log`。
- `./mvnw -ntp -pl batch-worker/atomic,batch-worker/dispatch,batch-worker/import,batch-worker/export -am '-Dtest=BatchWorkerAtomicApplicationIntegrationTest,BatchWorkerDispatchApplicationIntegrationTest,BatchWorkerImportApplicationIntegrationTest,BatchWorkerExportApplicationIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test`：四个保留的注册端口用例均通过，各 1 项，0 失败、0 错误、0 跳过；真实中间件由隔离 Testcontainers 提供，日志 `/private/tmp/worker-context-review-20261101.log`。
- `./mvnw -ntp -pl batch-common '-Dtest=OtelTraceContextTest' test`：4 项通过、0 失败、0 错误、0 跳过；无活跃链路场景显式使用根上下文并在结束时恢复。
- `node --test --experimental-strip-types tests/handler.test.ts`（`sdk/typescript`）：4 项通过，默认错误码使用独立预期值。
- `npx vitest run src/utils/datetime.test.ts`（前端）：21 项通过；`npx vitest run src/utils/safeHtml.test.ts src/stores/auth.test.ts`：27 项通过；`npx vitest run src/views/ops/OpsDiagnostic.test.ts`：20 项通过。
- `npx playwright test e2e/ops-diagnostic.spec.ts --list`（前端）仅确认 4 个用例可被加载，未执行浏览器测试。诊断页组件测试使用 mock；本轮未针对真实后端执行浏览器 E2E，不能视为端到端通过。

## 2026-11-01：薄包装清单续核

后端原始 110 个词法候选按[逐项台账](#2026-11-01后端薄包装历史候选逐项台账)核对当前方法及直接用途：11 项已简化，其余 99 项因具名业务判断、必要的格式转换、边界封装或测试夹具而保留。新增简化 `BatchWindowGateTest.mockWorkerSelector` 和 `LoadStepTest.mockPayload`，并将 5 个仅做可空字符串转换的单次包装替换为 `Objects.toString(value, null)`；输入只求值一次，空值与非空值语义保持不变。该结论仅覆盖原始 110 项。

`./mvnw -ntp -pl batch-orchestrator,batch-worker/import -am '-Dtest=BatchWindowGateTest,LoadStepTest' -Dsurefire.failIfNoSpecifiedTests=false test`：分别 4 项、18 项通过，0 失败、0 错误、0 跳过。Map 60 项当前逐方法结论见[Map 台账](#2026-11-01当前-public-map-候选逐项台账)，结论仅针对列出的返回类型候选，输入参数、隐式 public 等不在分母内。短断言当前结果以文首和逐项台账为准。

## 2026-11-01：测试候选与 Map 台账续核

- `PreprocessRangeSliceTest.shouldConcatenatePartitionsWithoutDuplicateOrGap` 原有四分片输入和无损结果保留，复用同类已有的切片辅助断言，移除重复的测试内切片循环。`./mvnw -ntp -pl batch-worker/import -am '-Dtest=PreprocessRangeSliceTest' -Dsurefire.failIfNoSpecifiedTests=false test`：15 项通过。
- `ProcessStagingPartitionMaintenanceTest` 原来仅凭清理数为零不能证明默认开关关闭：测试实例未配置清理依赖，开关错误开启也可能返回零。现增加默认开关为 false 和 mapper 无操作断言。`./mvnw -ntp -pl batch-worker/process -am '-Dtest=ProcessStagingPartitionMaintenanceTest' -Dsurefire.failIfNoSpecifiedTests=false test`：4 项通过。
- `HikariPgSessionSupportTest` 的长度断言改为检查截断后的完整预期字符串，保留原 63 字符上限并验证前缀。`./mvnw -ntp -pl batch-common '-Dtest=HikariPgSessionSupportTest' test`：3 项通过。以上均未修改生产逻辑。
- 文件切片末行无换行、空行和 CRLF 分别保留；回放集成测试的暂停、状态不匹配、外部结果未知和不同载荷属于不同场景；租户清理测试的同租户、跨租户和冷数据也不能因使用同一辅助方法删除。`RetryScheduleStatusTest` 的数量断言用于检出新增状态，与现有各编码断言不重复。
- public Map 原始 60 个候选现已逐方法列入[当前台账](#2026-11-01当前-public-map-候选逐项台账)，包含源码摘要、60 个当前已核查保留、0 个待核查项；Kafka producer 配置需透传和覆盖 client 参数，诊断端点保持现有条件字段和 wire 格式，JSON 转换及递归脱敏承载不定字段，故不强制改成固定对象。

候选台账中待核查不代表缺陷。其余短断言、后端薄包装及 Map 项仍未全部逐一裁定，用户目标尚未完成。

## 2026-11-01：短断言与动态 Map 再核对

- `TriggerOutboxReleaseGovernorTest.disabled_returnsConfiguredLimitWithoutReadingLag` 原来只断言返回配置上限，未验证名称所述的不读取 lag；保留原断言并补充对 lag monitor 无调用的验证。`./mvnw -ntp -pl batch-trigger -am '-Dtest=TriggerOutboxReleaseGovernorTest' -Dsurefire.failIfNoSpecifiedTests=false test`：7 项通过，0 失败、0 错误、0 跳过。生产逻辑未变。
- `CorrelationIdsTest` 的裁剪、非法字符、长度与回退输入不同；`ProcessPublishedCountVerifierTest` 的数字和数字字符串覆盖不同载荷类型；前端 `statusTagResolve` 的显式默认值、隐式默认值与未知状态、引导完成状态和权限空列表分别具有独立断言，保留。
- `BatchDayReplayService.failedStatuses` 与 `isCancellable` 对应失败重放范围及可取消状态的不同业务判断，保留具名方法。`MapJsonbTypeHandler` 的三个方法实现 MyBatis 的结果读取重载；`ParseSupport.parseHints/readJsonObject` 分别处理不同格式和插件可扩展 JSON；SDK `ProgressReporter.latest` 承载执行线程写入、续租线程读取的动态进度，均保留 Map。上述 Map 与包装结论为静态源码及直接调用关系核查。

本轮未对其余候选逐一完成上下文裁定，不代表原始短断言、包装与 Map 清单已全部关闭。

## 2026-11-01：短断言及包装、Map 续核

- `datetime.test.ts` 中两个空字符串用例调用和断言完全相同，且名为 typed 的用例没有额外输入或类型覆盖；删除一项，保留另一项。`npx vitest run src/utils/datetime.test.ts`：21 项通过。相邻的 null、undefined、无效日期及时间戳输入不同，继续保留。
- `queryFormOptions.test.ts` 的空输入、提取 undefined、去重排序及空白裁剪分别检查不同路径；`formatAiUsd.test.ts`、`errorCatalog.test.ts` 的零金额/普通金额与网络/未知错误分别属于不同场景，保留。
- `workerActions.test.ts` 的 ONLINE、DRAINING、OFFLINE 断言对应不同的操作集，保留；ONLINE 用例标题原来漏写预期中的排空动作，已更名以匹配原有断言。`npx vitest run src/views/worker/workerActions.test.ts`：4 项通过。两个 Worker fingerprint 空响应测试分别调用列表和汇总接口；`structuredSummary` 和 `pipelineStepSummary` 的短断言分别覆盖文本截断、各阶段字段与异常输入，保留。
- `DefaultWorkflowNodeDispatchService.isJobNode/isWaitNode` 命名不同节点分支；HTTP transport 的 `sameHost` 在 DNS 回调中统一处理 IPv6 括号和大小写比较，保留，避免将业务或网络边界嵌入调用点。
- 文件渠道和模板的 `toMap` 由 `TenantConfigPackageExcelApplyService` 在旧配置与导入列合并时调用，保留其旧 snake_case/缺失键行为；日历 `toExportRow` 被配置包 Excel 导出消费，保留最终行的 Map 格式。上述为静态调用关系核查，未更改后端代码，也未运行后端测试。

本轮只对上述已列位置作出结论；其余短断言、后端包装及 Map 候选仍未逐项核完，不能宣称全部整改完成。

## 2026-11-01：候选续核与幂等键测试修正

- 前端 `src/utils/idempotency.test.ts` 的非空和类型断言原先分别调用两次生成器，现对同一个返回值断言；无 `crypto` 场景使用 `finally` 恢复全局状态，断言失败也不会污染后续测试。`npx vitest run src/utils/idempotency.test.ts`：4 项通过；本批只改测试，不改变幂等键实现。
- 后端薄包装抽查：`CalendarBizDateResolver.previousWorkday` 和 `nextWorkday` 分别命名节假日向前、向后滚动，并传入对应方向和诊断文本；`ActiveTaskLeaseRegistry` 的状态更新和原子值读取包装承载租约状态操作边界，保留。这些结论不代表其余包装均已核查。
- 使用 `python3 scripts/ci/report-java-readability-inventory.py --output /private/tmp/readability-candidates-20261101.md` 重新生成当前词法清单，退出码 0；指定范围有 60 个 public Map 返回候选，分布于 44 文件。`CursorCodec.decode` 承载可扩展分页排序键，其调用方包括会话分页和按 id 的通用查询；`RunModeSupport.copyWithDefault` 复制原有业务参数并规范化运行模式，调用方是启动参数解析；两者保留 Map。数字是词法候选而非缺陷，也不覆盖所有 Map 入参与私有方法。

本轮仍未逐项完成全部短断言、后端薄包装和 Map 候选；历史清单与当前代码可能存在漂移，后续以当前函数、调用方及测试夹具为准，不能按候选数量批量删改。用户要求的目标尚未完成。

## 2026-11-01：重复判空和事件处理候选续改

在当前后端 `perf/local-loadtest-2026-10-31` 工作区和配对前端工作区继续处理。历史 71 个重复判空模式中，前两批已简化 10 处；本批按实际类型、工具方法对 null 的处理和表达式求值核对后，又简化 39 个文件中的 57 处局部值或不可变记录字段判断。当前源码扫描剩余 4 处，均直接读取请求对象可变字段 `scheduleExpr`；为保持原读取次数和求值行为，本批保留。扫描模式和范围与原始候选相同，不代表其他防御判断均已审完。

前端 `MyWorkers.onRefresh` 和审批表格的 `selectableRow` 确认为简单转发，调用处改为直接使用原函数。配对前端此前检出 `main`，本批先撤回对这两个文件的修改；用户随后指定使用与后端同名分支，现已在原前端工作区创建并切换至 `perf/local-loadtest-2026-10-31`，重新应用相同修改，保留既有未提交内容。`npx vitest run src/views/approvals/ActionContextRisks.test.ts` 在切换分支前、相同修改内容下运行：1 个文件、26 项通过；该测试不构成 MyWorkers 页面刷新交互验证。前端旧候选清单含模板事件回调，很多回调内部仍有筛选、重置及加载操作；不能根据单行正则将其视为无用包装。

前端原 84 个包装语法候选按当前函数声明和调用形态核对：78 个调用搜索、重置或刷新辅助函数并提供场景回调，需保留事件参数与筛选、分页或加载行为；上述 2 个直接转发已简化。其余 4 个中，`ProTable.scheduleRecompute` 保留 `nextTick` 调度，`useTenantReload.onErr` 同时用于同步异常和异步拒绝，文档生成脚本的 `escapeLabel` 命名 Markdown 转义规则，E2E 的 `ts` 每次调用生成时间戳；不按一次调用删除。历史行号已有漂移，本结论针对候选名称在当前文件中的实现，不覆盖候选之外的其他抽象。

后端定向测试：`./mvnw -ntp -pl batch-console-api,batch-orchestrator,batch-trigger,batch-worker/dispatch,batch-worker/export,batch-worker/import,batch-worker/process,sdk/java/core -am '-Dtest=AckDispatchStepTest,AlertNotificationReceiptServiceTest,AlertmanagerNotifyServiceTest,BatchDayCatchupPreviewServiceTest,BatchDayOpenSchedulerTest,BatchDaySettleSchedulerTest,BatchManifestTest,ConfigSyncAuditRecoveryTest,ConsoleAiPropertiesTest,ConsolePushApprovalNotifierTest,ControlTotalEvaluatorTest,DefaultConsoleApprovalApplicationServiceTest,DefaultConsoleTenantConfigInitApplicationServiceTest,DefaultWorkerRegistryServiceTest,DefaultWorkflowDagServiceTest,DefaultWorkflowNodeDispatchServiceTest,JobDependencyWaitingLaunchServiceTest,LaunchParamResolverTest,PersistentWaitReadServiceTest,ProcessStagingGenerationCleanupFenceTest,QuartzMisfireRecoveryConfigurationTest,SdkTaskContextTest,StoreStepTest,SystemPipelineCatalogTest,TaskDispatcherTest,TrailerControlRecordTest,TriggerApiKeyVerifierTest,TriggerSchedulerFacadeTest,WorkflowDefinitionMutationValidationTest,WorkflowNodeReplayTargetResolverTest,WorkflowRunReplaySourceResolverTest' -Dsurefire.failIfNoSpecifiedTests=false test` 成功，实际合计 433 项、0 失败、0 错误、0 跳过；完整类名和命令见日志 `/private/tmp/wdi-overdefense-batch-20261101.log`。Maven 按依赖编译上游模块，此结果不代表完整测试或全部变更位置均有独立用例。

后续核对 `WorkflowDagValidatorTest`：旧 Pipeline 已存在的测试并未构造该前提，其输入和错误码与邻近的 FILE_STEP 退役测试相同；删除重复测试，保留同时检查异常码和消息键的测试。`./mvnw -ntp -pl batch-console-api -am '-Dtest=WorkflowDagValidatorTest,WorkflowConditionEvaluatorTest' -Dsurefire.failIfNoSpecifiedTests=false test` 成功，实际仅 `WorkflowDagValidatorTest` 运行 22 项并通过：该 reactor 不包含 orchestrator，因此并未运行后者。`WorkflowConditionEvaluator.findAtomicOperator` 只转发至 `AtomicOperator.find`，调用处改为直接调用；随后执行 `./mvnw -ntp -pl batch-orchestrator -am '-Dtest=WorkflowConditionEvaluatorTest' -Dsurefire.failIfNoSpecifiedTests=false test`，23 项通过。

原 321 个短断言和其余前后端包装、Map 等候选尚未全部完成上下文裁定；候选命中不等于问题，也不应凭此宣布全部完成。

补充逐组核对：`resourceCodeBuilder.test.ts` 的短断言分别约束规范化、长度和首字符边界、版本后缀与资源码拼装；`metaEnumPick.test.ts` 区分未匹配、缺失输入、空数组和无候选键；`taskHeartbeat.test.ts` 区分百分数、比例换算、四舍五入和字段优先级。`HttpTaskExecutorIpBlockTest` 中的地址覆盖不同协议族、网段及公网反例，不能只因断言形式相同删除。这些结论依据对应完整测试类及其输入与断言，未据此推断其余短断言已完成核查。

另核对 `DelimitedCodecTest` 的普通字段、引号中分隔符、末尾空字段和自定义分隔符；`QuotaResetPolicyTest` 的空值、空白、未知配置和不同策略托管状态；`ValidTenantIdTest`、`ValidResourceCodeTest` 的 null、空白及长度边界；以及前端 `text.test.ts`、`safeJson.test.ts`、`theme.test.ts`、`lastApiMeta.test.ts` 的字符截断、JSON 输入、主题迁移和缺省值边界。各自测试不同输入或状态，保留；本次没有因其断言短而合并或删除。

## 2026-10-30：批量日补跑根请求修复复核

沿 `BUG-20261030-BATCHDAY-CATCHUP-ROOT` 及 `BUG-20261030-BATCHDAY-PARENT-IDENTITY` 核对当前源码：批日调度器生成 `tenant:batch-day-catchup:calendar:date:job` 键；根请求读取及失败日扫描都按租户、该键、`CATCH_UP` 类型和空父 Workflow 限定，避免同键子 Job 遮蔽根请求。V369 部分唯一索引覆盖该键前缀的同租户 `CATCH_UP` 根请求，不限制子请求。Trigger 通用按键回查也排除子请求，但保留别名匹配和原有排序/单条选择；其业务范围比批日根请求查询更广，不能把两条 SQL 简化为同一口径。

本次仅核对现有 SQL、索引和直接调用链，未重复运行此前成功的真实数据库、启动服务及独立进程回查测试；具体证据和测试环境限制见[治理记录](../audit/backend-25-package-governance-2026-10-26.md)。这不证明所有 Trigger 请求类型或目标环境已完成验证。

## 2026-10-30：通知投递日志直接消费者复核

核对 `BUG-20261030-NOTIFICATION-JSONB-MASK` 与 `BUG-20261030-NOTIFICATION-LOG-UI`：后端以 snake_case 返回已遮罩的 JSON 值，OpenAPI 和前端生成类型将两个动态字段声明为 `unknown`；前端 API 适配器原样传递 JSON，并将渠道、尝试次数和错误摘要映射为页面使用的字段。当前通知投递日志页面展示渠道、事件、状态、次数和错误摘要，筛选使用事件与渠道，并不展示或字符串化这两个 JSON 字段。租户切换时页面用请求作用域排除过期响应。

本次为当前源码与直接消费者静态复核，前端适配器、页面及适配器测试的 SHA-256 前 12 位分别为 `039b3ceeffba`、`a5e1a19f639b`、`d21c0add56fb`；未重复运行此前成功的测试。隔离数据库与真实 HTTP 遮罩验证、前端模拟客户端和组件测试的范围分别见[治理记录](../audit/backend-25-package-governance-2026-10-26.md)相应条目；真实浏览器与真实后端同次联验及生产权限仍未验证。

## 2026-10-30：文件身份解析修复的直接调用链核对

针对 `BUG-20261029-FILE-ID-NUMERIC` 核对当前启动、任务创建和结果回报代码：三处身份转换均先转文本并以 `Long.parseLong` 解析，只接受正整数；小数、溢出及无效值不会通过数值截断关联到其他文件。启动时有效父实例按租户查询，未解析时沿既有空值分支处理；文件身份由引用校验器检查。任务创建对同一解析结果执行引用校验并写入步骤，结果回报将解析结果传入步骤进度更新。Workflow 启动的父实例也使用同一启动参数解析器和租户查询。

本次保留现有优先级：普通任务按正值候选回退；DISPATCH 遇到已提供的 `bundleSourceFileId` 或 `fileId` 时使用相应字段的解析结果；启动参数则先选择首个非空字段再解析。没有更改无效值应否拒绝及候选回退语义。三处生产源码 SHA-256 前 12 位分别为 `4e7fbafa3e76`、`973507789425`、`89d034f9f401`，对应启动解析、任务创建、结果载荷解析。此为源码与直接调用链复核，未重复运行先前成功的 51 项定向测试及两个真实数据库测试；历史执行证据和跨进程未验证边界见[治理记录](../audit/backend-25-package-governance-2026-10-26.md)。

## 2026-10-30：四项证据新鲜度核对

本次核对下节四份来源：Trigger Relay 与 Mapper 的 SHA-256 仍分别为 `619c7405d0cb`、`9c67d71e95f7`；配置初始化集成测试和前端生成类型已变化，当前摘要分别为 `44a5fab394c5`、`9b6e3423cb63`。当前测试仍包含真实数据库 savepoint 回滚首项、提交第二项及统计断言；这只是源码核对，不能单凭它认定变化后的整类测试已运行。前端类型随后因 Workflow 节点名称契约变更重新生成并做过生成漂移及类型检查，见[治理记录](../audit/backend-25-package-governance-2026-10-26.md#已执行验证与未关闭项)的 `BUG-20261030-WORKFLOW-NODE-NAME`；不推断全部页面交互已验证。本次未重复运行业务测试或前端检查。

## 2026-10-28：剩余四项定向核查

本次核查以下当前源码和直接契约，未发现新增确认缺陷，未修改生产代码：

1. `TenantConfigInitCommitOutcomeIntegrationTest` 新增 savepoint 反例：实际回滚首项、提交第二项，并检查数据库与 created/failed 统计；`TenantConfigInitApplyHandlers.applySpecs` 在嵌套事务完成后才记录成功。已有真实 PostgreSQL 先红后绿及六分支证据见[治理记录](../audit/backend-25-package-governance-2026-10-26.md#已执行验证与未关闭项)，该证据对应当时快照，本次未重跑。
2. `TriggerOutboxRelay` 与 `TriggerOutboxEventMapper.xml`：当前批次领取生成 UUID，成功、失败和放弃按状态及 token 围栏更新，超时回收清除 token；写入型 RETURNING 声明刷新缓存。V361 同步主表与归档镜像。历史真实 PostgreSQL 重领反例与 Kafka 定向证据见同一治理记录的 BUG-20261027-TRG-02；未复验双实例锁失效和存量升级。
3. `src/types/api.generated.ts`：对配对后端本地 OpenAPI 执行 `npm run gen:api:check`，生成比较通过（`/private/tmp/wdi-four-api-drift-20261028.log`）。这是契约生成一致性检查，不代表完整前端交互验收。

读取时四份来源 SHA-256 依次为 `615a6e16452ff016fc90b71182329cae3271a4ec6523c351e0fa8a4c33efde18`、`619c7405d0cb42bf18c4d61c2a72892e517a47cf92a88b0ac5a7ea19eb59c4a6`、`9c67d71e95f78f91473f8b17bcd24f3574be992cae469021d082b69864dee80b`、`fee525808716d9c3dfc39609b30dcf90e1f6fcef4e681b645d2046b8d15d47b6`。按用户要求，仅针对新发现问题的类运行测试；本次未发现新增确认问题，未重跑业务类测试。下方10月27日待复核状态为当时记录，以本节为最新核查结论。

## 2026-10-28：前后端及未跟踪源码模式扫描

按用户要求只记录，本次未修改业务或测试代码，未运行测试。通过 Git 已跟踪与未跟踪且未忽略的文件清单扫描后端 4,548 份、前端 1,044 份 Java/TypeScript/JavaScript/Vue 源码，排除依赖与构建产物；该分母是文件枚举，不等于逐文件语义审核。以下行号为扫描快照，后续变动时应重新定位。

- **重复判空**：后端71处、45个文件符合 `isNull(x) || isBlank/isEmpty(x)` 模式；已核对工具对 null 的处理，简单局部变量的重复分支可作为精简候选。逐处仍须检查类型重载、求值顺序、上下文及实际维护状态。
- **一次调用的私有薄包装**：后端110处语法候选，仅以单文件出现次数和单行 return 初筛；有领域命名、递归、方法引用、反射、接口/代理或职责边界时可能合理，不等同110项缺陷。
- **前端包装**：84处语法候选；Vue 模板、事件绑定、导出和测试调用可能不以脚本中的普通调用表达式出现，数量不等于无用包装，必须核对模板及外部引用。只针对单行函数形态筛选，其他防御与抽象尚未语义审完。

### 重复判空候选

| 文件 | 候选位置 |
|---|---|
| [batch-common/src/main/java/io/github/pinpols/batch/common/enums/SystemPipelineDefinition.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/enums/SystemPipelineDefinition.java) | 10、19 |
| [batch-common/src/main/java/io/github/pinpols/batch/common/kafka/BatchKafkaTopicNamespace.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/kafka/BatchKafkaTopicNamespace.java) | 33 |
| [batch-common/src/main/java/io/github/pinpols/batch/common/storage/NamespacedObjectStore.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/storage/NamespacedObjectStore.java) | 126、130 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/DefaultConsoleApprovalApplicationService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/DefaultConsoleApprovalApplicationService.java) | 192 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/config/ConsoleAiProperties.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/config/ConsoleAiProperties.java) | 227 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/application/progress/PipelineProgressQueryService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/application/progress/PipelineProgressQueryService.java) | 26 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/infrastructure/DefaultConsoleFileChannelApplicationService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/infrastructure/DefaultConsoleFileChannelApplicationService.java) | 183 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/job/entity/JobDefinitionEntity.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/job/entity/JobDefinitionEntity.java) | 27、33 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/service/AlertNotificationReceiptService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/service/AlertNotificationReceiptService.java) | 27 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/service/AlertmanagerNotifyService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/service/AlertmanagerNotifyService.java) | 200 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/support/ConsolePushApprovalNotifier.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/support/ConsolePushApprovalNotifier.java) | 134 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/workflow/application/contract/request/WorkflowDefinitionSaveRequest.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/workflow/application/contract/request/WorkflowDefinitionSaveRequest.java) | 67、79 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/workflow/application/contract/request/WorkflowScheduleUpdateRequest.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/workflow/application/contract/request/WorkflowScheduleUpdateRequest.java) | 56、84、96 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleConfigSyncApplicationService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleConfigSyncApplicationService.java) | 125、249 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleTenantConfigInitApplicationService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleTenantConfigInitApplicationService.java) | 40 |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigInitTenantExecutor.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigInitTenantExecutor.java) | 168 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/PersistentWaitReadService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/PersistentWaitReadService.java) | 41 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/batchday/BatchDayCatchupPreviewService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/batchday/BatchDayCatchupPreviewService.java) | 46、48、61、63 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/WorkflowNodeReplayTargetResolver.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/WorkflowNodeReplayTargetResolver.java) | 33 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/WorkflowRunReplaySourceResolver.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/WorkflowRunReplaySourceResolver.java) | 45 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowDagService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowDagService.java) | 361、376 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowNodeDispatchService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowNodeDispatchService.java) | 516、622、970 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/query/JobDependencyRule.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/query/JobDependencyRule.java) | 13 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/router/DefaultWorkerRouter.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/router/DefaultWorkerRouter.java) | 29 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/BatchDayOpenScheduler.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/BatchDayOpenScheduler.java) | 471 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/BatchDaySettleScheduler.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/BatchDaySettleScheduler.java) | 145、381 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/JobDependencyWaitingLaunchReconciler.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/JobDependencyWaitingLaunchReconciler.java) | 61 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DefaultWorkerRegistryService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DefaultWorkerRegistryService.java) | 269、404 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/JobDependencyWaitingLaunchService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/JobDependencyWaitingLaunchService.java) | 69、73、84、377、403、411、468、502 |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/LaunchParamResolver.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/LaunchParamResolver.java) | 89 |
| [batch-trigger/src/main/java/io/github/pinpols/batch/trigger/auth/TriggerApiKeyVerifier.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/auth/TriggerApiKeyVerifier.java) | 50 |
| [batch-trigger/src/main/java/io/github/pinpols/batch/trigger/config/QuartzMisfireRecoveryConfiguration.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/config/QuartzMisfireRecoveryConfiguration.java) | 40 |
| [batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/TriggerSchedulerFacade.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/TriggerSchedulerFacade.java) | 422 |
| [batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/application/TaskDispatchExecutor.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/application/TaskDispatchExecutor.java) | 43、86、88 |
| [batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/DefaultTaskExecutionWrapper.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/DefaultTaskExecutionWrapper.java) | 196、198、232 |
| [batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/stage/AckDispatchStep.java](../../batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/stage/AckDispatchStep.java) | 70 |
| [batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/StoreStep.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/StoreStep.java) | 172 |
| [batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/ControlTotalEvaluator.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/ControlTotalEvaluator.java) | 41 |
| [batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/TrailerControlRecord.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/TrailerControlRecord.java) | 59 |
| [batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/BatchManifest.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/BatchManifest.java) | 83 |
| [batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/cleanup/ProcessStagingGenerationCleanupFence.java](../../batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/cleanup/ProcessStagingGenerationCleanupFence.java) | 48 |
| [batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/stage/PlatformProcessStagingGenerationCoordinator.java](../../batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/stage/PlatformProcessStagingGenerationCoordinator.java) | 90 |
| [sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcher.java](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcher.java) | 586 |
| [sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/SdkCrossDayResult.java](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/SdkCrossDayResult.java) | 22 |
| [sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/SdkTaskContext.java](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/SdkTaskContext.java) | 78 |

### 后端薄包装候选

| 文件 | 候选位置 |
|---|---|
| [batch-common/src/main/java/io/github/pinpols/batch/common/kafka/BatchTopics.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/kafka/BatchTopics.java) | 42 `sanitizeTopicSegment` |
| [batch-common/src/main/java/io/github/pinpols/batch/common/security/RequestSignatures.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/security/RequestSignatures.java) | 90 `upper` |
| [batch-common/src/main/java/io/github/pinpols/batch/common/stateful/StatefulBackendGuard.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/stateful/StatefulBackendGuard.java) | 255 `matches` |
| [batch-common/src/main/java/io/github/pinpols/batch/common/storage/FilesystemObjectStore.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/storage/FilesystemObjectStore.java) | 406 `syntheticEtag` |
| [batch-common/src/main/java/io/github/pinpols/batch/common/storage/NamespacedObjectStore.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/storage/NamespacedObjectStore.java) | 125 `physicalMarker`、129 `logicalMarker` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/ConsoleClusterDiagnosticService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/ConsoleClusterDiagnosticService.java) | 335 `isTerminal` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/infrastructure/DefaultConsoleKafkaLagQueryService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/infrastructure/DefaultConsoleKafkaLagQueryService.java) | 126 `cacheSegment` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/config/ReadReplicaRoutingDataSource.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/config/ReadReplicaRoutingDataSource.java) | 143 `observeReplicaConnection` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/audit/infrastructure/ai/ConsoleAiCostService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/audit/infrastructure/ai/ConsoleAiCostService.java) | 252 `unpriced` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/job/infrastructure/DefaultConsoleJobDefinitionApplicationService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/job/infrastructure/DefaultConsoleJobDefinitionApplicationService.java) | 853 `resolveSoftRuntimeSeconds` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/infrastructure/DefaultConsoleAlertApplicationService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/infrastructure/DefaultConsoleAlertApplicationService.java) | 106 `normalizeStatus` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/service/TerminalNotificationDeliveryService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/service/TerminalNotificationDeliveryService.java) | 275 `notification` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/support/ConsolePushApprovalNotifier.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/support/ConsolePushApprovalNotifier.java) | 165 `nullToDash`、169 `truncate` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/observability/realtime/ConsoleRealtimeEventHub.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/observability/realtime/ConsoleRealtimeEventHub.java) | 281 `normalizeStream`、285 `normalizeFilter` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/service/ConsoleOidcIdentityService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/service/ConsoleOidcIdentityService.java) | 101 `isAscii` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/support/ConsoleAuthenticationFilter.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/support/ConsoleAuthenticationFilter.java) | 223 `isPublicAuthPath` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/support/ConsoleLoginService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/support/ConsoleLoginService.java) | 90 `invalidCredentials` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/support/ConsoleSessionRegistry.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/support/ConsoleSessionRegistry.java) | 184 `sessionStateTtl` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultTenantConfigPackageExcelService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultTenantConfigPackageExcelService.java) | 832 `requiredHeaders`、1029 `fileNameOrDefault` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigMatrixBuilder.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigMatrixBuilder.java) | 212 `first` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantJobDefinitionApplySupport.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantJobDefinitionApplySupport.java) | 514 `resolveSoftRuntime` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageExcelValidator.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageExcelValidator.java) | 1111 `resourceQueueExists` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/rbac/ConsoleOidcAuthenticationSuccessHandler.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/rbac/ConsoleOidcAuthenticationSuccessHandler.java) | 85 `isAscii` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/rbac/RedisOidcAuthorizationRequestRepository.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/rbac/RedisOidcAuthorizationRequestRepository.java) | 184 `validBrowserBinding` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/shared/approval/OrchestratorApprovalClient.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/shared/approval/OrchestratorApprovalClient.java) | 160 `hasText` |
| [batch-console-api/src/main/java/io/github/pinpols/batch/console/support/ratelimit/ConsoleRateLimitFilter.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/support/ratelimit/ConsoleRateLimitFilter.java) | 153 `isExpensivePath` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/engine/OutboxEventKeyGenerator.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/engine/OutboxEventKeyGenerator.java) | 116 `truncate` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/plan/DefaultSchedulePlanBuilder.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/plan/DefaultSchedulePlanBuilder.java) | 268 `clampMaxPartitionCount` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/ratelimit/TokenBucketRateLimiter.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/ratelimit/TokenBucketRateLimiter.java) | 203 `configSupplierFor` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/scheduler/TenantSchedulerSnapshotService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/scheduler/TenantSchedulerSnapshotService.java) | 246 `emptyBacklog` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/dryrun/DefaultDryRunPlanService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/dryrun/DefaultDryRunPlanService.java) | 116 `defaultParamsHas` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultCompensationService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultCompensationService.java) | 1244 `firstText` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultFileGovernanceService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultFileGovernanceService.java) | 741 `safeUrlQuery` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultRetryGovernanceService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultRetryGovernanceService.java) | 721 `stringValue` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/BatchDayReplayService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/BatchDayReplayService.java) | 873 `failedStatuses`、1182 `isCancellable` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/WorkflowRunReplaySnapshotResolver.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/WorkflowRunReplaySnapshotResolver.java) | 309 `readProtectedDispatchChannelSnapshots` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/DefaultPartitionDispatchService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/DefaultPartitionDispatchService.java) | 93 `buildContext`、220 `initialPartitionStatus` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/version/ResultVersionWriter.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/version/ResultVersionWriter.java) | 194 `buildBusinessKey` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowDagService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowDagService.java) | 664 `stringValue` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowNodeDispatchService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowNodeDispatchService.java) | 921 `isJobNode`、925 `isWaitNode` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowConditionEvaluator.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowConditionEvaluator.java) | 324 `findAtomicOperator` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowRunExecutionSnapshot.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowRunExecutionSnapshot.java) | 668 `readProtectedDispatchChannelSnapshots` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/auth/ApiKeyVerifier.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/auth/ApiKeyVerifier.java) | 226 `verifyCacheKey` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceLatencyMetrics.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceLatencyMetrics.java) | 64 `text` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceRepository.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceRepository.java) | 476 `defaultText` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceScheduler.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceScheduler.java) | 550 `defaultText` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceStorageMaintenance.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceStorageMaintenance.java) | 316 `sanitizeTrace` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/governance/AlertmanagerEmitPublisher.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/governance/AlertmanagerEmitPublisher.java) | 232 `stripTrailingSlash` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/http/OkHttpOrchestratorExternalHttpTransport.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/http/OkHttpOrchestratorExternalHttpTransport.java) | 118 `sameHost` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/quota/DatabaseQuotaRuntimeStateService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/quota/DatabaseQuotaRuntimeStateService.java) | 292 `normalizePolicy` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/quota/QuotaReservationSupport.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/quota/QuotaReservationSupport.java) | 54 `notApplicable` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/redis/OrchestratorConfigInvalidationSubscriber.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/redis/OrchestratorConfigInvalidationSubscriber.java) | 230 `eventKey` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/CrossDayDependencyReconciler.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/CrossDayDependencyReconciler.java) | 165 `loadWorkflowRun` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/DefaultResourceScheduler.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/DefaultResourceScheduler.java) | 254 `isWaitingRetry` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/DefaultWorkerSelector.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/DefaultWorkerSelector.java) | 182 `currentLoadOrZero` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/GlobalJobAdmissionGuard.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/GlobalJobAdmissionGuard.java) | 38 `hasHardCap` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/observability/JobLifecycleMetricsRecorder.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/observability/JobLifecycleMetricsRecorder.java) | 164 `resolveErrorCode` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/security/RequestSignatureVerifier.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/security/RequestSignatureVerifier.java) | 101 `hasValidSignatureShape` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DefaultWorkerRegistryService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DefaultWorkerRegistryService.java) | 354 `firstHeartbeat` |
| [batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/LaunchBatchDayService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/LaunchBatchDayService.java) | 423 `isMissingLaunchContext`、427 `isIncompleteBatchDay` |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/capacity/CapacityProfileServiceTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/capacity/CapacityProfileServiceTest.java) | 201 `eqInt` |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/replay/WorkflowReplayJobDefinitionResolverTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/replay/WorkflowReplayJobDefinitionResolverTest.java) | 178 `snapshot` |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceArrivalGroupGuardTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceArrivalGroupGuardTest.java) | 401 `bizDate` |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/http/OkHttpOrchestratorExternalHttpTransportTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/http/OkHttpOrchestratorExternalHttpTransportTest.java) | 258 `selections` |
| [batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/BatchWindowGateTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/BatchWindowGateTest.java) | 189 `mockWorkerSelector` |
| [batch-test-support/src/test/java/io/github/pinpols/batch/common/arch/MapperStatusWriteConventionTest.java](../../batch-test-support/src/test/java/io/github/pinpols/batch/common/arch/MapperStatusWriteConventionTest.java) | 111 `withoutComments` |
| [batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/QuartzMisfireRecoveryListener.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/QuartzMisfireRecoveryListener.java) | 301 `isDedicatedRecoveryJob` |
| [batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/TriggerStatusQueryFacade.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/TriggerStatusQueryFacade.java) | 126 `unknown` |
| [batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/CalendarBizDateResolver.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/CalendarBizDateResolver.java) | 88 `resolveCutoffTime`、105 `previousWorkday`、109 `nextWorkday` |
| [batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/QuartzMisfireApplicationRestartIntegrationTest.java](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/QuartzMisfireApplicationRestartIntegrationTest.java) | 960 `jdbcUrlWithApplicationName` |
| [batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/runtime/AtomicRuntimeStatusService.java](../../batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/runtime/AtomicRuntimeStatusService.java) | 113 `isProdProfile` |
| [batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/spark/SparkSubmitTaskExecutor.java](../../batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/spark/SparkSubmitTaskExecutor.java) | 455 `isLocalPath` |
| [batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/ActiveTaskLeaseRegistry.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/ActiveTaskLeaseRegistry.java) | 537 `startExecution`、541 `lastConfirmedNanosValue` |
| [batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/TaskConsumerRoutingPolicy.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/TaskConsumerRoutingPolicy.java) | 192 `escapeRegex` |
| [batch-worker/core/src/test/java/io/github/pinpols/batch/worker/core/infrastructure/DefaultTaskExecutionWrapperTest.java](../../batch-worker/core/src/test/java/io/github/pinpols/batch/worker/core/infrastructure/DefaultTaskExecutionWrapperTest.java) | 830 `actualReplayCapability` |
| [batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/SmtpEmailDispatchChannelAdapterTest.java](../../batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/SmtpEmailDispatchChannelAdapterTest.java) | 145 `port` |
| [batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/infrastructure/S3ExportStorage.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/infrastructure/S3ExportStorage.java) | 135 `digestToHex` |
| [batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/region/ExportRegionResolver.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/region/ExportRegionResolver.java) | 109 `firstNonBlank` |
| [batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/sql/SqlTemplateExportSqlValidator.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/sql/SqlTemplateExportSqlValidator.java) | 225 `isParameterStart`、229 `isParameterPart` |
| [batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/PrepareStep.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/PrepareStep.java) | 233 `defaultText` |
| [batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/ImportDataQualityService.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/ImportDataQualityService.java) | 142 `asString` |
| [batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/ImportRecordGovernanceService.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/ImportRecordGovernanceService.java) | 466 `stringValue` |
| [batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java) | 610 `isManifestMode` |
| [batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/PreprocessStep.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/PreprocessStep.java) | 217 `hasTemplateConfigSnapshot` |
| [batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/stage/LoadStepTest.java](../../batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/stage/LoadStepTest.java) | 353 `mockPayload` |
| [batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputeSpec.java](../../batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputeSpec.java) | 390 `values` |
| [sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/internal/RequestSigner.java](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/internal/RequestSigner.java) | 77 `upper` |
| [sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/contract/JsonFixtureContractTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/contract/JsonFixtureContractTest.java) | 109 `fixturesDir`、113 `openapiPath` |
| [sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/contract/JsonFixtureRequestSideContractTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/contract/JsonFixtureRequestSideContractTest.java) | 51 `fixturesDir` |
| [sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/contract/SharedConstantsParityTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/contract/SharedConstantsParityTest.java) | 60 `yamlPath` |
| [sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/dispatcher/KafkaConsumerAuthFailureTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/dispatcher/KafkaConsumerAuthFailureTest.java) | 49 `mockConsumer` |
| [sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcherTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcherTest.java) | 79 `objectMap` |
| [sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/handler/atomic/SqlAtomicHandlerTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/handler/atomic/SqlAtomicHandlerTest.java) | 48 `ctxNoSql` |
| [sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/internal/PlatformHttpClientTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/internal/PlatformHttpClientTest.java) | 499 `attemptedAddresses` |
| [sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/scheduler/LeaseRenewalSchedulerTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/scheduler/LeaseRenewalSchedulerTest.java) | 51 `objectMap` |
| [sdk/java/spring/src/test/java/io/github/pinpols/batch/sdk/autoconfigure/BatchWorkerSdkAutoConfigurationTest.java](../../sdk/java/spring/src/test/java/io/github/pinpols/batch/sdk/autoconfigure/BatchWorkerSdkAutoConfigurationTest.java) | 185 `registeredHandlers` |
| [sdk/java/testkit/src/main/java/io/github/pinpols/batch/sdk/testkit/FakeBatchPlatform.java](../../sdk/java/testkit/src/main/java/io/github/pinpols/batch/sdk/testkit/FakeBatchPlatform.java) | 473 `asMap` |

### 前端薄包装候选

| 文件 | 候选位置 |
|---|---|
| [e2e/c-plus-coverage.spec.ts](../../../batch-console/e2e/c-plus-coverage.spec.ts) | 22 `ts` |
| [scripts/lib/docs-publication.mjs](../../../batch-console/scripts/lib/docs-publication.mjs) | 114 `escapeLabel` |
| [src/components/table/ProTable.vue](../../../batch-console/src/components/table/ProTable.vue) | 389 `scheduleRecompute` |
| [src/composables/useTenantReload.ts](../../../batch-console/src/composables/useTenantReload.ts) | 27 `onErr` |
| [src/views/approvals/components/CatchUpApprovalsTab.vue](../../../batch-console/src/views/approvals/components/CatchUpApprovalsTab.vue) | 210 `onSearch`、219 `onReset` |
| [src/views/approvals/components/GeneralApprovalsTab.vue](../../../batch-console/src/views/approvals/components/GeneralApprovalsTab.vue) | 424 `selectableRow`、482 `onSearch`、489 `reset` |
| [src/views/config/ConfigReleaseList.vue](../../../batch-console/src/views/config/ConfigReleaseList.vue) | 640 `onSearch`、647 `reset` |
| [src/views/file-center/ArrivalGroupList.vue](../../../batch-console/src/views/file-center/ArrivalGroupList.vue) | 224 `onSearch`、233 `onReset` |
| [src/views/file-center/FileList.vue](../../../batch-console/src/views/file-center/FileList.vue) | 934 `applyStatusFilter`、956 `applyTodayFilter`、1041 `onSearch`、1057 `reset` |
| [src/views/file-center/FilePipelineObservability.vue](../../../batch-console/src/views/file-center/FilePipelineObservability.vue) | 777 `onSearch`、785 `onReset` |
| [src/views/file-center/FileTemplateList.vue](../../../batch-console/src/views/file-center/FileTemplateList.vue) | 982 `onChannelSearch`、986 `resetChannels`、994 `onSearch`、1001 `reset` |
| [src/views/governance/QueueConfig.vue](../../../batch-console/src/views/governance/QueueConfig.vue) | 819 `onTabSearch`、827 `onTabReset` |
| [src/views/governance/QuotaPanel.vue](../../../batch-console/src/views/governance/QuotaPanel.vue) | 268 `onQuotaSearch`、275 `onQuotaReset`、284 `onQuotaRefresh` |
| [src/views/job/JobDefinitionList.vue](../../../batch-console/src/views/job/JobDefinitionList.vue) | 786 `onSearch`、792 `reset`、805 `onRefreshDefinitions` |
| [src/views/monitor/JobInstanceList.vue](../../../batch-console/src/views/monitor/JobInstanceList.vue) | 757 `resetQuery`、775 `searchInstances` |
| [src/views/monitor/JobStepInstanceList.vue](../../../batch-console/src/views/monitor/JobStepInstanceList.vue) | 140 `onQuerySearch`、147 `onQueryReset` |
| [src/views/monitor/PartitionView.vue](../../../batch-console/src/views/monitor/PartitionView.vue) | 236 `applyFilter`、244 `resetFilter` |
| [src/views/monitor/WorkflowRunDetail.vue](../../../batch-console/src/views/monitor/WorkflowRunDetail.vue) | 696 `applyNodeFilter`、705 `resetNodeFilter` |
| [src/views/monitor/WorkflowRunList.vue](../../../batch-console/src/views/monitor/WorkflowRunList.vue) | 318 `search`、325 `resetQueryBar` |
| [src/views/observability/AlertList.vue](../../../batch-console/src/views/observability/AlertList.vue) | 525 `search`、538 `reset` |
| [src/views/observability/AlertRoutingPanel.vue](../../../batch-console/src/views/observability/AlertRoutingPanel.vue) | 283 `onSearch`、292 `onReset`、303 `onRefresh` |
| [src/views/observability/AuditList.vue](../../../batch-console/src/views/observability/AuditList.vue) | 230 `search`、237 `reset` |
| [src/views/observability/OperationAuditList.vue](../../../batch-console/src/views/observability/OperationAuditList.vue) | 268 `search`、275 `reset` |
| [src/views/observability/components/ChannelReceiptsTab.vue](../../../batch-console/src/views/observability/components/ChannelReceiptsTab.vue) | 204 `applyFilter`、213 `resetFilter` |
| [src/views/observability/components/DeadLettersTab.vue](../../../batch-console/src/views/observability/components/DeadLettersTab.vue) | 380 `applyFilter`、389 `resetFilter` |
| [src/views/observability/components/RetriesTab.vue](../../../batch-console/src/views/observability/components/RetriesTab.vue) | 188 `applyFilter`、197 `resetFilter` |
| [src/views/scheduler/BatchDayList.vue](../../../batch-console/src/views/scheduler/BatchDayList.vue) | 459 `onSearch`、466 `reset` |
| [src/views/system/AiChat.vue](../../../batch-console/src/views/system/AiChat.vue) | 487 `onAuditSearch`、497 `onAuditReset` |
| [src/views/system/ApiKeyList.vue](../../../batch-console/src/views/system/ApiKeyList.vue) | 327 `onSearch`、334 `onReset` |
| [src/views/system/SystemParameterList.vue](../../../batch-console/src/views/system/SystemParameterList.vue) | 189 `onSearch`、195 `onReset` |
| [src/views/system/TenantList.vue](../../../batch-console/src/views/system/TenantList.vue) | 328 `onSearch`、337 `onReset` |
| [src/views/system/TriggerList.vue](../../../batch-console/src/views/system/TriggerList.vue) | 211 `onSearch`、218 `onReset` |
| [src/views/system/UserAccountList.vue](../../../batch-console/src/views/system/UserAccountList.vue) | 455 `onSearch` |
| [src/views/system/components/NotificationChannelsTab.vue](../../../batch-console/src/views/system/components/NotificationChannelsTab.vue) | 414 `applyChannelFilter`、422 `resetChannelFilter` |
| [src/views/system/components/NotificationDeliveryLogsTab.vue](../../../batch-console/src/views/system/components/NotificationDeliveryLogsTab.vue) | 171 `applyLogFilter`、179 `resetLogFilter` |
| [src/views/system/components/NotificationRulesTab.vue](../../../batch-console/src/views/system/components/NotificationRulesTab.vue) | 366 `applyRuleFilter`、374 `resetRuleFilter` |
| [src/views/system/components/NotificationWebhooksTab.vue](../../../batch-console/src/views/system/components/NotificationWebhooksTab.vue) | 391 `applyWebhookFilter`、399 `resetWebhookFilter` |
| [src/views/worker/MyWorkers.vue](../../../batch-console/src/views/worker/MyWorkers.vue) | 89 `onRefresh` |
| [src/views/worker/WorkerManagement.vue](../../../batch-console/src/views/worker/WorkerManagement.vue) | 707 `onWorkerSearch`、713 `resetWorkers`、948 `onChannelSearch`、955 `resetChannels` |
| [src/views/workflow/WorkflowDefinitionList.vue](../../../batch-console/src/views/workflow/WorkflowDefinitionList.vue) | 992 `onSearch`、999 `reset` |

## 2026-10-28：过度实现初筛

本次对相对当前 HEAD 有差异的 1,060 份已跟踪后端生产 Java 文件做定向模式初筛，关注重复空值判断和仅使用一次的私有薄包装；这是候选筛选，不代表全量语义审查，也不包括未跟踪文件。配对前端本次未做同等范围扫描。

- **重复空值判断**：`isNull(x) || isBlank(x)` 与 `isNull(x) || isEmpty(x)` 命中61处、37份文件。`EmptyChecks.isBlank` 和集合 `isEmpty` 已处理 null；例如 `NamespacedObjectStore.physicalMarker/logicalMarker`、`TaskDispatchExecutor` 的批量入参判断中的额外 `isNull` 可省。这里的判空本身仍有业务意义，应仅去掉重复部分；61处是机械候选，修改前须逐处核对重载、求值与调用现场。
- **一次性薄包装候选**：`DefaultTenantConfigPackageExcelService.requiredHeaders` 仅转发 `ConfigPackageSheetSpecs.requiredImportHeaders`，只有一处调用，调用点可直接使用后者。是否精简还应结合邻近代码可读性决定；不按单次调用机械删除具有领域含义或代理边界的方法。
- 本次只扫描、未修改业务代码、未跑业务测试；其他防御校验、前端、测试 mock、未跟踪源码和更复杂的抽象尚未完成审查，不能据此声称过度实现已全部找出。

## 2026-10-30：测试必要性初步核查

在配对 `fix/wdi-current` 工作树，对 Git 已跟踪及未跟踪、未忽略测试文件做字节级重复检查：后端 1,434 份 `*Test.java`，前端 410 份 `.test.ts` / `.spec.ts`，未发现完全相同的文件。此项不能识别不同写法的行为重复；不包含其他命名、脚本及所有测试资源，不代表全量语义审查。

- **建议合并，不能直接删除**：[UpdateBatchDayReplayEntryStatusParamTest](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/domain/param/UpdateBatchDayReplayEntryStatusParamTest.java) 的 `shouldBuildWhenRequiredFieldsArePresent` 与后续 `shouldBuildLegalStateShapesFromNamedFactories` 都以 `succeededWithResult` 验证成功状态和时间；但前者独有 ID 断言。可将该断言移入后者并保留必填字段负例，再考虑移除前者；当前未修改测试，也未验证合并。
- **保留**：前端 [storageKeys.test.ts](../../../batch-console/src/constants/storageKeys.test.ts) 以独立预期保护已发布持久化键兼容；不是简单重复常量。后端 Worker DTO 测试中的上限、集合快照及参数工厂测试中的必填字段/状态形态均有独立契约作用，不能仅凭 getter 断言判断无用。

本次只读代码、登记结果，未运行测试；尚未逐项比较全部测试的行为覆盖，不能推断其他测试均有必要或存在多少可删除项。

## 短断言候选复核进展

对原321项短断言候选检查了索引，已核实以下代表性场景；尚未逐项完成全部上下文审查，不能据此认定其余项可删或应保留。本轮仅记录，未修改、删除或运行测试。

- `iconResolve.test.ts` 的 `toBeDefined()` 和 `not.toBeNull()` 分别排除 `undefined` 和 `null`，并非重复；此前将其称为重复候选的口头判断已更正。该测试对具体图标身份的证明仍弱于其标题，是否加强应另评估。
- `lastApiMeta.test.ts` 对 `null` 和 `undefined` 分别测试输入边界，`metaEnumPick.test.ts` 同理；虽然当前实现统一处理，两个公开输入不应仅因输出相同而删除。
- `HttpTaskExecutorIpBlockTest` 不同地址涉及 IPv4、IPv6、映射地址、元数据地址与公网反例，应保留各自安全边界；`PreprocessRangeSliceTest` 的无尾换行、空行和 CRLF 也分别测试不同文件格式。
- `RetryScheduleStatusTest.shouldContainSixValues` 锁定枚举总数，六个编码断言只证明已列举值的编码，新增第七项时不会失败，因此不能认定数量断言与编码断言重复。
- `resourceCodeBuilder.test.ts` 中空值、最小/最大长度及首字符规则为不同边界；`theme.test.ts` 的默认值、已保存值和旧值迁移为不同状态，不因代码短而合并。

## 测试候选上下文复核续记

**10组后端相同正文**：第1组分别实例化 Job 与 Workflow 通知消费者，验证各自无效身份不产生业务动作；第2组分别执行 HTTP 与 Shell 执行器的日志脱敏；第3、4、5、7、8组的 ArchUnit 规则分别导入各模块的生产类；第9组分别启动五类 Worker 并验证各自端口，以上保留各自覆盖。第6组 Workflow DAG 两个 FILE_STEP 拒绝用例与第10组 Readiness 两个空结果用例的重复结论见下文；保留其更完整断言或按真实业务状态补充反例后再考虑合并，不直接删除。

**9组前端相同正文**：第1、2、3、4、6、7、8组分别处于不同路由、页面或功能区域；第9组处于资源标签和搜索标签两个不同 tab，不能仅凭正文相同删除。第5组两条 Outbox stats 200 检查的请求相同，但分别处于 Trigger 链和 Outbox 故障恢复的串行场景，且后者先创建故障数据；可讨论合并通用存活检查，但需先确认单独运行、失败定位和阶段前置条件，暂不判为可删。多处 UI 测试在控件不可见时提前返回，或仅断言容器存在；应作为低有效性改进候选，不能把它们的成功视为实际操作成功。

**仍未完成**：以上针对已有19组相同正文候选核对业务上下文；另有后端208、前端113个短断言语法候选，尚未逐个核实，不应报告整个候选集已完成。未修改、删除或执行测试；以上仅基于当前代码静态核对。

## 2026-10-30：测试重复性全量索引与上下文复核

只读前后端 Git 已跟踪及未跟踪、未忽略的 `*Test.java`、`.test.ts`、`.spec.ts`：后端 1,434 个文件、前端 410 个文件。简单解析器识别后端 8,646 个、前端 2,011 个测试体，按去除空白的正文计算相同组；未识别测试、其他命名和动态生成测试不在测试体计数内。相同正文未必测试相同对象，短断言也未必无效；以下为筛选和有限上下文核查，**不代表全量测试已逐项语义审核**。没有删除或修改测试，也未执行测试。

### 已核实的重复及处置建议

1. `WorkflowDagValidatorTest.shouldFail_whenFileStepPipelineCodeMissing` 与 `shouldFail_whenFileStepPipelineNotFound`：同类、相同输入与断言，且不存在不同的类级 fixture；可合并为一个退役节点拒绝用例。后者标题称旧 Pipeline 存在，但该测试没有建立这种状态；如该场景仍需保障，应补真实可区分前提，而不是将当前测试视为证据。
2. `ReadinessServiceTest.shouldNotBeReady_whenRerunNotEffective` 与 `shouldNotBeReady_whenNoAssetPartition`：相同 mock 返回空、相同调用与断言；同类 `shouldNotBeReady_whenAssetPartitionNotEffective` 覆盖该结果且断言更多。可考虑保留覆盖更完整的用例；若要证明“重跑未生效”，应另用能表达重跑状态的真实场景。
3. 上一轮登记的 `UpdateBatchDayReplayEntryStatusParamTest.shouldBuildWhenRequiredFieldsArePresent` 与具名工厂测试部分重复；先迁移独有 ID 断言，再评估合并。

### 不应按正文重复直接删除

- ArchUnit 的相同规则在各模块以不同 `CLASSES` 包范围执行；五种 Worker 的端口测试分别启动不同应用，均不能因正文相同而合并删除。
- 前端 `tag-ops.spec.ts` 两个 Tag Key 测试的正文相同，但 `beforeEach` 分别进入资源标签与搜索标签，作用于不同 tab，应保留独立业务覆盖。其他跨页面 E2E 相同正文同样须先确认路由和上下文。
- 前端页面操作测试存在条件不满足直接 return、只断言容器存在的低信息候选；这属于测试真实性改进线索，不能直接作为删除依据，也不能当作真实业务链通过证据。

### 相同正文的候选组

后端10组、前端9组，以下保留全部初筛位置供逐项复核。行号是扫描快照；除上文明确说明外，均未认定可删除。

**后端**

1. [JobTerminalNotificationConsumerTest.java:72](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/notification/infrastructure/JobTerminalNotificationConsumerTest.java) `shouldDropInvalidIdentityWithoutBusinessAction`；[WorkflowTerminalNotificationConsumerTest.java:72](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/notification/infrastructure/WorkflowTerminalNotificationConsumerTest.java) `shouldDropInvalidIdentityWithoutBusinessAction`
2. [HttpTaskExecutorTest.java:96](../../batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutorTest.java) `shouldMaskUnexpectedErrorInLog`；[ShellTaskExecutorTest.java:63](../../batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/shell/ShellTaskExecutorTest.java) `shouldMaskUnexpectedErrorInLog`
3. [CodingConventionsArchTest.java:20](../../batch-test-support/src/test/java/io/github/pinpols/batch/common/arch/CodingConventionsArchTest.java) `shouldForbidDefaultTimezoneReads_whenOutsideTimezoneInfrastructure`；[OrchestratorCodingConventionsArchTest.java:18](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/arch/OrchestratorCodingConventionsArchTest.java) `shouldFindNoJvmDefaultTimeZoneUsage_whenRuleChecked`；[TriggerCodingConventionsArchTest.java:18](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/arch/TriggerCodingConventionsArchTest.java) `zoneIdSystemDefault_isForbidden`；[ConsoleCodingConventionsArchTest.java:24](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/arch/ConsoleCodingConventionsArchTest.java) `shouldAvoidJvmDefaultTimeZone_whenRuleChecked`
4. [CodingConventionsArchTest.java:26](../../batch-test-support/src/test/java/io/github/pinpols/batch/common/arch/CodingConventionsArchTest.java) `shouldForbidEncodingLookupByName_whenOutsideEncodingUtils`；[OrchestratorCodingConventionsArchTest.java:24](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/arch/OrchestratorCodingConventionsArchTest.java) `shouldFindNoNameBasedCharsetConstruction_whenRuleChecked`；[TriggerCodingConventionsArchTest.java:24](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/arch/TriggerCodingConventionsArchTest.java) `charsetForName_isForbidden`；[ConsoleCodingConventionsArchTest.java:30](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/arch/ConsoleCodingConventionsArchTest.java) `shouldAvoidCharsetLookupByName_whenRuleChecked`
5. [CodingConventionsArchTest.java:32](../../batch-test-support/src/test/java/io/github/pinpols/batch/common/arch/CodingConventionsArchTest.java) `shouldForbidRecordSuffix_whenNamingPersistentOrDomainClasses`；[OrchestratorCodingConventionsArchTest.java:30](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/arch/OrchestratorCodingConventionsArchTest.java) `shouldFindNoRecordSuffixTypeName_whenRuleChecked`；[TriggerCodingConventionsArchTest.java:30](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/arch/TriggerCodingConventionsArchTest.java) `recordSuffix_isForbidden`；[ConsoleCodingConventionsArchTest.java:36](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/arch/ConsoleCodingConventionsArchTest.java) `shouldAvoidRecordSuffixTypeName_whenRuleChecked`
6. [WorkflowDagValidatorTest.java:168](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/workflow/validation/WorkflowDagValidatorTest.java) `shouldFail_whenFileStepPipelineCodeMissing`；[WorkflowDagValidatorTest.java:179](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/workflow/validation/WorkflowDagValidatorTest.java) `shouldFail_whenFileStepPipelineNotFound`
7. [OrchestratorCodingConventionsArchTest.java:36](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/arch/OrchestratorCodingConventionsArchTest.java) `shouldFindNoTransactionalEventListener_whenRuleChecked`；[TriggerCodingConventionsArchTest.java:36](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/arch/TriggerCodingConventionsArchTest.java) `transactionalOnEventListener_isForbidden`；[ImportCodingConventionsArchTest.java:23](../../batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/arch/ImportCodingConventionsArchTest.java) `shouldRejectTransactional_whenEventListenerMethodAnnotated`；[ConsoleCodingConventionsArchTest.java:42](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/arch/ConsoleCodingConventionsArchTest.java) `shouldAvoidTransactionalOnEventListener_whenRuleChecked`
8. [OrchestratorCodingConventionsArchTest.java:42](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/arch/OrchestratorCodingConventionsArchTest.java) `shouldFindNoTransactionalScheduledMethod_whenRuleChecked`；[TriggerCodingConventionsArchTest.java:42](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/arch/TriggerCodingConventionsArchTest.java) `transactionalOnScheduled_isForbidden`；[ImportCodingConventionsArchTest.java:29](../../batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/arch/ImportCodingConventionsArchTest.java) `shouldRejectTransactional_whenScheduledMethodAnnotated`；[ConsoleCodingConventionsArchTest.java:48](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/arch/ConsoleCodingConventionsArchTest.java) `shouldAvoidTransactionalOnScheduledMethod_whenRuleChecked`
9. [BatchWorkerAtomicApplicationIntegrationTest.java:35](../../batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/integration/BatchWorkerAtomicApplicationIntegrationTest.java) `shouldRegisterActualBoundPort`；[BatchWorkerDispatchApplicationIntegrationTest.java:31](../../batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/integration/BatchWorkerDispatchApplicationIntegrationTest.java) `shouldRegisterActualBoundPort`；[BatchWorkerProcessApplicationIntegrationTest.java:50](../../batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/integration/BatchWorkerProcessApplicationIntegrationTest.java) `shouldRegisterActualBoundPort`；[BatchWorkerImportApplicationIntegrationTest.java:32](../../batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/integration/BatchWorkerImportApplicationIntegrationTest.java) `shouldRegisterActualBoundPort`；[BatchWorkerExportApplicationIntegrationTest.java:31](../../batch-worker/export/src/test/java/io/github/pinpols/batch/worker/exports/integration/BatchWorkerExportApplicationIntegrationTest.java) `shouldRegisterActualBoundPort`
10. [ReadinessServiceTest.java:92](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/readiness/ReadinessServiceTest.java) `shouldNotBeReady_whenRerunNotEffective`；[ReadinessServiceTest.java:107](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/readiness/ReadinessServiceTest.java) `shouldNotBeReady_whenNoAssetPartition`

**前端**

1. [scheduler-governance-ops.spec.ts:153](../../../batch-console/e2e/scheduler-governance-ops.spec.ts) `状态筛选 → 查询`；[alert-outbox-ops.spec.ts:154](../../../batch-console/e2e/alert-outbox-ops.spec.ts) `重试 tab — 状态筛选`
2. [scheduler-governance-ops.spec.ts:199](../../../batch-console/e2e/scheduler-governance-ops.spec.ts) `刷新`；[scheduler-governance-ops.spec.ts:291](../../../batch-console/e2e/scheduler-governance-ops.spec.ts) `刷新`；[alert-outbox-ops.spec.ts:178](../../../batch-console/e2e/alert-outbox-ops.spec.ts) `刷新`；[trigger-ops.spec.ts:29](../../../batch-console/e2e/trigger-ops.spec.ts) `刷新`；[file-ops.spec.ts:54](../../../batch-console/e2e/file-ops.spec.ts) `刷新按钮`；[tag-ops.spec.ts:58](../../../batch-console/e2e/tag-ops.spec.ts) `刷新`；[log-ops.spec.ts:19](../../../batch-console/e2e/log-ops.spec.ts) `刷新按钮可用`
3. [scheduler-governance-ops.spec.ts:204](../../../batch-console/e2e/scheduler-governance-ops.spec.ts) `关键字筛选 → 查询`；[alert-outbox-ops.spec.ts:146](../../../batch-console/e2e/alert-outbox-ops.spec.ts) `重试 tab — 关键字搜索`；[file-ops.spec.ts:215](../../../batch-console/e2e/file-ops.spec.ts) `关键字搜索 → 查询`
4. [scheduler-governance-ops.spec.ts:212](../../../batch-console/e2e/scheduler-governance-ops.spec.ts) `启用 / 停用切换 → 确认 → toast`；[scheduler-governance-ops.spec.ts:272](../../../batch-console/e2e/scheduler-governance-ops.spec.ts) `启用 / 停用切换 → 确认 → toast`
5. [08-outbox-stuck-republish.spec.ts:92](../../../batch-console/e2e/flows/08-outbox-stuck-republish.spec.ts) `1. 查 outbox stats — 通道存活`；[01-trigger-instance.spec.ts:57](../../../batch-console/e2e/flows/01-trigger-instance.spec.ts) `4. outbox stats 接口可访问 → 发布通道存活`
6. [user-account-ops.spec.ts:28](../../../batch-console/e2e/user-account-ops.spec.ts) `刷新`；[worker-ops.spec.ts:47](../../../batch-console/e2e/worker-ops.spec.ts) `刷新按钮`
7. [ops-shard-catalog-crud.spec.ts:29](../../../batch-console/e2e/ops-shard-catalog-crud.spec.ts) `页可达 + 新建入口可见`；[ops-tenant-placement-crud.spec.ts:23](../../../batch-console/e2e/ops-tenant-placement-crud.spec.ts) `页可达 + 新建入口可见`
8. [monitor-ops.spec.ts:150](../../../batch-console/e2e/monitor-ops.spec.ts) `分片列表加载`；[worker-ops.spec.ts:118](../../../batch-console/e2e/worker-ops.spec.ts) `文件渠道列表加载`；[log-ops.spec.ts:15](../../../batch-console/e2e/log-ops.spec.ts) `页面正常打开并展示表格`
9. [tag-ops.spec.ts:42](../../../batch-console/e2e/tag-ops.spec.ts) `Tag Key 输入 → 查询`；[tag-ops.spec.ts:73](../../../batch-console/e2e/tag-ops.spec.ts) `Tag Key 搜索`

后端另有208个、前端113个短断言语法候选，尚未逐一确认；短小不等于无效，边界、状态机、持久化键及独立协议预期应保留。下一步应优先核对这些候选的具体行为和相邻测试，而非按数字批量删除。

## 2026-11-01：测试与规约候选续核

核对基线：后端 `/Users/dengchao/Downloads/file-batch-system` 的 `perf/local-loadtest-2026-10-31`，HEAD `0258697d14229e9da1bddb547943acf9d2481338`，治理台账有既有未提交修改；前端配对仓库 `main`，HEAD `e44c3b2d29e29805d95a48bafc167e8d5725129d`。只读代码并在本报告留记录，未修改业务或测试代码。

### 确认可以提出的规约整改

| 位置 | 证据与建议 | 范围和验证要求 |
|---|---|---|
| [BatchKafkaTopicNamespace](../../batch-common/src/main/java/io/github/pinpols/batch/common/kafka/BatchKafkaTopicNamespace.java) `apply` | `isNull(name) || isBlank(name)` 对同一个方法参数重复检查；`isBlank` 经 `Texts.hasText` 对 null 返回 true。可删除前半项，保留空白拒绝。 | 仅检查该表达式；实施时验证 null、空白和合法命名空间语义及异常。 |
| [NamespacedObjectStore](../../batch-common/src/main/java/io/github/pinpols/batch/common/storage/NamespacedObjectStore.java) `physicalMarker`、`logicalMarker` | `isNull(marker) || isBlank(marker)` 的 null 分支被后者包含；可保留后者并维持 null、空白 marker 原值返回。 | 不删除判空功能，不改 prefix/marker 处理或对象存储契约。 |
| [PipelineProgressQueryService](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/application/progress/PipelineProgressQueryService.java) `normalize` | `isEmpty(ids)` 对 null 和空集合均返回 true，前面的 `isNull(ids)` 可省；后续 size 与元素判断的短路顺序保留。 | 验证 null、空、超限及非法 ID；不能提前执行 stream。 |
| [DefaultTenantConfigPackageExcelService](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultTenantConfigPackageExcelService.java) `requiredHeaders` | 私有方法只转发 `ConfigPackageSheetSpecs.requiredImportHeaders`，当前类只有一个调用，可考虑在调用处直接使用目标方法。 | 可读性建议；保持实参求值次数、异常和表头校验，非业务 Bug。 |

### 确认不应机械整改

- 前端 [ChannelReceiptsTab.vue](../../../batch-console/src/views/observability/components/ChannelReceiptsTab.vue) 的 `applyFilter`、`resetFilter` 被模板的搜索、重置和回车事件引用，并承载草稿/已应用条件与刷新流程；旧的脚本内调用次数不能证明其无用。
- `iconResolve.test.ts` 的非 undefined 和非 null 断言排除不同值；`lastApiMeta` 对 null 和 undefined 是独立输入边界。短断言和测试体相似本身不构成删除理由。
- 历史 321 条短断言的名称和记录的正文片段均能在当前配对仓库找到；这是存在性检查，不证明 fixture、生产调用方、断言覆盖或源码快照整体未变，更不代表逐项审查完成。
- Map 已有处理及动态 Map 合理保留的分类见[25 包治理记录](../audit/backend-25-package-governance-2026-10-26.md)，不把 public Map 词法命中直接计为违规。

**尚未完成**：原321个短断言、71个重复判空、后端110个及前端84个薄包装候选尚未逐项完成当前上下文核验；上述为本次可独立定位的结果，不将候选总数等同需整改数。未运行编译或测试；将来实施时仅对实际受影响范围做验证。

## 2026-11-01：Worker 判空续改与证据重放范围

`TaskDispatchExecutor` 的 `workerId` 和 `messages`、`DefaultTaskExecutionWrapper` 的 `results`、`items` 与 `tasks` 都是同一表达式内的参数或局部值；`isBlank/isEmpty` 已处理 null，移除六个重复的 `isNull`，保留认领、租约和原来的后续短路条件。`./mvnw -ntp -pl batch-worker/core -am '-Dtest=TaskDispatchExecutorTest,DefaultTaskExecutionWrapperTest,DefaultTaskExecutionWrapperClaimLeaseTest' -Dsurefire.failIfNoSpecifiedTests=false test`：三类41项通过，0失败、错误、跳过；日志 `/private/tmp/wdi-overdefense-worker-20261101.log`。此测试针对本次代码改动，不是对历史45个编号重放。

用户要求历史“首红、复验待证”只指定测试类，不重复运行；当前台账提供的按主题测试载体为：

| 主题 | 可指定的测试类或方法 | 局限 |
|---|---|---|
| 批日补跑、审批、日切 | `ConsoleBatchDayCatchUpHttpIntegrationTest`、`BatchDayCutoffMultiTenantIntegrationTest` | 两项历史业务口径仍需先裁定；不声称覆盖全部编号。 |
| 文件束与分发 | `DispatchPipelineE2eIT#shouldDeliverEveryFile_whenBundleDispatchTaskExpandsIntoSubtasks` | 用例有真实依赖，范围限对应分发路径。 |
| 传感器、演练计划 | `WorkflowSensorApplicationContextRestartIntegrationTest`、`DefaultDryRunPlanServiceTest` | 不自动覆盖其他超时和重试编号。 |
| 归档血缘 | `SameJobMultiRunArchiveLineageIntegrationTest` | 跨作业共享文件历史口径仍待裁定。 |
| 配置初始化/同步 | `TenantConfigInitCommitOutcomeIntegrationTest`、`TenantConfigBundleCompletenessIntegrationTest` | 只覆盖各自事务结果和配置包场景。 |
| 内容脱敏 | `ContentMaskingUtilsTest` 以及按具体编号选取对应服务/HTTP 集成测试 | 工具类测试不能替代各输出通道和真实 HTTP 的证据；未对28个编号逐个映射。 |

上述清单来自[25包交付锚点](../audit/backend-25-package-governance-2026-10-26.md#交付锚点)，是选择性测试载体，不是45编号的完整一对一映射。本次没有重放这些历史证据，也不将历史首红变为当前通过。

## 2026-11-01：已确认候选的首批整改

在后端当前工作区 `perf/local-loadtest-2026-10-31`（HEAD `0258697d`）处理本报告已明确可简化项：Kafka 名称和对象存储 marker 的重复 null/blank 判断、Pipeline 进度参数的重复 null/empty 判断保留 `isBlank`/`isEmpty` 语义及短路；配置包表头的私有薄转发移除并以局部变量承接原结果。

测试只合并已证实同前提、同调用和同断言的 Workflow DAG、Readiness 用例；参数工厂用例合并时保留 ID 与 now 断言。前端 `iconResolve.test.ts` 合并重复的已知图标用例，保留非 undefined 与非 null 的两个独立断言及不同 fallback 对照；`npx vitest run src/utils/iconResolve.test.ts` 3/3 通过（`/private/tmp/wdi-overdefense-icon-20261101.log`）。未删除测试类；被删除测试名称所描述的“历史 Pipeline 已存在”“重跑未生效”并未由其原夹具分别构造，不把合并当成这些场景已有证据。

定向验证：`./mvnw -ntp -pl batch-console-api,batch-orchestrator -am '-Dtest=BatchKafkaTopicNamespaceTest,NamespacedObjectStoreTest,PipelineProgressQueryServiceTest,DefaultTenantConfigPackageExcelServiceWorkbookTest,UpdateBatchDayReplayEntryStatusParamTest,WorkflowDagValidatorTest,ReadinessServiceTest' -Dsurefire.failIfNoSpecifiedTests=false test` 成功，7 类合计41项，0失败、0错误、0跳过；日志 `/private/tmp/wdi-overdefense-target-20261101.log`。此命令按依赖编译上游模块，不等于完整 reactor 全量测试。原71处重复判空词法模式目前仍有67处、42文件；其余短断言、前后端薄包装及 Map 候选尚未逐项裁定，本批完成不等于全部整改。

## 当前核查结论

本报告为本轮前后端审查与验证的唯一记录，合并原后端审查、后端验证和前端整改三份10月27日报告，并删除被替代文件；问题、首红、命令、历史覆盖数字和环境限制保留在下文。10月26日独立历史记录保留。当前执行范围以[差异审查清单](../plans/todo-master.md#当前差异审查清单)为准，下文历史“继续全仓”安排不再作为本轮执行指令。

- **工作位置**：配对 `wdi-current` 工作树，分支均为 `fix/wdi-current`；后端HEAD `f3d5e400d3b5bd737124b57cf0a38e685432aab2`，前端HEAD `662d254e2f8b97f93021de548815470d2291c997`。未修改main或冻结参考树，未暂存、提交或发布。
- **本次完成**：原14项待复核来源的读取快照已核对，未发现新增的已确认Bug；收尾时13项仍与读取内容一致，1份测试被并发追加用例，新增内容待复核。未修改生产或测试代码。此结论限于下表关注点，不将这些文件全文或其所在模块标为全部已审。
- **验证边界**：按“只跑有问题的类”执行。此次没有确认新的代码缺陷，也没有因此启动Java/前端测试、编译或全模块回归。下文运行结果为历史源码快照的证据，本次没有重新产生业务测试通过数。
- **比较限制**：原14项中10项当前摘要不同、4项与待复核台账摘要相同；4项相同只说明自登记后未继续漂移，不等于此前已通过。旧摘要未附完整旧文件正文，不能宣称已恢复并逐字证明所有旧新变换等价；本次依据当前源码、只读冻结参考内容、原问题及反例核对所列不变量。
- **复用范围**：已核对日切原首红/修复后命令与日志、Worker生命周期/领取CAS、配置事务及前端静态路由历史证据的归属；没有将旧编译、旧全量测试或文件哈希一致当作当前完整运行保证。

### 原14项风险片段复核

表中摘要固定本次读取内容，完整旧摘要仍保留在原CSV。该CSV继续作为历史覆盖记录，不重写为本轮全文件审核完成。

| 来源 | 本次核对结论 | 本次读取快照SHA-256前12位 |
|---|---|---|
| [DefaultConsoleConfigSyncApplicationService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleConfigSyncApplicationService.java) | 目标身份先验拒绝；原键查询受租户约束；typed审计行、独立审计事务及收据核对仍衔接 | `026a63680ceb` |
| [TenantConfigCopyTypeLocaleTest.java](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigCopyTypeLocaleTest.java) | 两种默认语言与三类协议值断言保留，finally恢复Locale | `8a1f9d1927c5` |
| [TenantConfigInitCommitOutcomeIntegrationTest.java](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigInitCommitOutcomeIntegrationTest.java) | 原提交后确认异常路径已核对；收尾并发新增savepoint回滚统计用例，新增内容待复核 | `d0345c6979d4` |
| [TenantConfigInitOutcomeTest.java](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigInitOutcomeTest.java) | 未知提交停止扇出并恢复上下文；已知回滚继续；非法目标零执行断言保留 | `c3f8f370f5b2` |
| [TenantConfigBundleCompletenessIntegrationTest.java](../../batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/TenantConfigBundleCompletenessIntegrationTest.java) | 201队列、最高版本及合法150节点/296边断言保留，SQL参数顺序与本租户清理核对 | `8848586855c8` |
| [BatchDayCutoffMultiTenantIntegrationTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/BatchDayCutoffMultiTenantIntegrationTest.java) | 多租户各自审计、版本推进一次及未来/冻结项保持断言保留 | `6170b2a2449a` |
| [CalendarBizDateResolver.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/CalendarBizDateResolver.java) | 按实际Instant比较日切；重叠取首次、缺口取跳时边界；原DST及等于日切反例仍在 | `3b4e5429a8c3` |
| [TriggerAdmissionDrainTest.java](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/config/TriggerAdmissionDrainTest.java) | 排队后再次检查排水；三入口各有拒绝零调用与正常接纳反例，线程收尾仍在 | `ccb24198c9c7` |
| [TriggerSchedulingOwnershipTest.java](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/infrastructure/scheduler/TriggerSchedulingOwnershipTest.java) | 实际组件扫描有非空正例，并断言Trigger不注册日切状态任务；不等同全部调度实现证明 | `e2eef600fe29` |
| [ExternalEventApiKeyHttpIntegrationTest.java](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/ExternalEventApiKeyHttpIntegrationTest.java) | 认证/来源绑定、固定业务日、原事件重投及范围外Workflow拒绝、请求/Outbox计数断言保留 | `61f49716eea4` |
| [TaskDispatchExecutor.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/application/TaskDispatchExecutor.java) | 空输入仍短路；仅认领成功项执行；批末清理未执行项，不重跑已执行业务 | `158dbab885cd` |
| [ActiveTaskLeaseRegistry.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/ActiveTaskLeaseRegistry.java) | 清理仅匹配tenant/worker/invocation且未启动项；判断与移除共用写锁，已启动项保留 | `f1e6261ab2c4` |
| [WorkerRuntimeState.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/WorkerRuntimeState.java) | computeIfPresent内仍用current != expected判实例身份，迟到心跳不能替换新注册 | `f77787a08a3a` |
| [WorkerReportOutboxCoordinator.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/reportoutbox/WorkerReportOutboxCoordinator.java) | 成功/失败/损坏处理传递完整领取行；凭据CAS仍由Repository约束，计数仅在生效后增加 | `e8af342dbed1` |

### 新变化与未验证边界

`TenantConfigInitCommitOutcomeIntegrationTest.java` 在本次读取后新增 `shouldCountOnlyCommittedItems_whenNestedItemRollsBackAtCompletion`，不能沿用读取快照的核查结论覆盖新用例。表中保留本次读取摘要，原确认异常用例结论仍限其当时内容；该文件最新新增内容及对应运行验证单列待复核，未覆盖其他工作的修改或并行启动编译。

对原已审/部分审查来源只做摘要新鲜度检查，后端278项、前端140项；此操作不是新增418项语义审核。另发现以下3项与原登记摘要不同，不沿用旧通过结论：

- 后端 `TriggerOutboxRelay.java` 与 `TriggerOutboxEventMapper.xml`：当前变化内容待专项核对，未据文件变化提前认定Bug，也未额外启动Trigger模块测试。
- 前端 `src/types/api.generated.ts`：生成契约当前摘要变化，未重跑生成漂移或前端全量测试；未据此宣称已有组件证据覆盖新契约。

上述3项及收尾新增测试内容均仅登记变化，共4处待复核，不自动扩大当前核查为新的全仓扫描。URI/JDBC/计量/Nginx等原候选仍按主清单判定与本轮修复的直接关系后安排，不计为已修复问题。

### 合并与轻量检查

本次迁移仅重组文档标题、链接与当前状态；三份原报告的正文证据及命令保留，后端命令在后端根目录、前端命令在前端根目录执行。合并检查不与业务运行验证混计。

- `bash scripts/python.sh scripts/ci/check-docs-structure.py`：后端文档结构通过。
- `python3 scripts/ci/check-doc-timestamp-policy.py`：文档日期命名通过。
- 前端 `npm run check:docs`：75份当前文档通过。
- 迁移完整性核对：原报告225行非标题正文、7个代码块和70个原章节映射均保留；三份原文件已删除，已迁移链接/锚点核对通过。
- 收尾来源核对：13份与本次读取内容一致；1份测试的新用例和另外3份变化保留待复核，未新增业务测试通过结论。

- [后端问题与历史审查](#backend-review-record)
- [后端历史运行验证](#backend-validation-record)
- [前端问题与历史运行验证](#frontend-review-record)

<a id="backend-review-record"></a>

<a id="backend-review-后端代码-bug-续审2026-10-27"></a>

## 后端问题与历史审查

> 以下保留各批次当时的发现和验证，不将历史进度或继续审查安排当作当前完成结论。

> **2026-10-27范围调整**：全工程逐文件续审已停止，后续以[当前差异审查清单](../plans/todo-master.md#当前差异审查清单)为准，优先核对已有修复及直接影响。本报告保留各批次当时的发现、覆盖数字与验证；正文中的继续审查安排属于原计划，不代表当前执行范围。代码审查仍暂停，本次仅调整清单。

<a id="backend-review-范围与状态"></a>

### 范围与状态

继续[原逐文件任务](../plans/todo-master.md#前后端全工程逐文件-bug-续审2026-10-26)，只审查Bug与运行风险。既有配对 `wdi-current` 工作树均为 `fix/wdi-current`，后端HEAD `f3d5e400d3b5bd737124b57cf0a38e685432aab2`，前端HEAD `662d254e2f8b97f93021de548815470d2291c997`。保留共享暂存/未暂存/未跟踪改动，未提交或发布。

本日截至后端定位与产物验收批次确认并修复十六项，运行证据见[当日验证](#backend-validation-record)。全工程逐文件审查仍未完成，持续清单沿用[原任务内容摘要台账](backend-bug-file-coverage-2026-10-26.csv)，日期为任务起点而非当前源码冻结日期；源文件变化后需复核。

<a id="backend-review-确认问题"></a>

### 确认问题

| ID | 触发与旧行为 | 修复与证据 |
|---|---|---|
| BUG-20261027-01 / P2 | 配置复制服务用 `PageRequest(1,5000)` 读取完整包，但公共请求将页大小限制为200，且只读取第一页；同步导出、复制/差异和矩阵依赖同一入口，会漏配置，Workflow边也被截断 | 复用既有Mapper空分页全量读取契约，保留租户与只读重复读快照、普通列表200条限制；真实PG验证201条队列及合法150节点/296边完整读取 |
| BUG-20261027-02 / P2 | 矩阵显式指定展示列表外的基准时，只读取展示租户，未加载基准，所有行可能得到空 `driftFields` | 额外读取基准供比较，响应仍只包含请求租户；基准缺少作业按缺失事实计算存在性差异。两个新增用例先红后绿，原默认/显式列表内基准用例保留 |
| BUG-20261027-03 / P1 | 源租户同时作为复制目标时，UPSERT将去除环境字段的渠道包回写源租户；真实认证HTTP返回200且原端点/配置标记均被清空 | 服务在读取源包前拒绝源目标重合，HTTP400且原配置不变；直接内部调用同样拒绝，中英文错误键验证通过。前端更换源时移除重合项，FE-BUG-42记录同一风险链的页面守卫 |
| BUG-20261027-04 / P2 | 复制、预览、矩阵及作业包导入的目标/作业元素缺少非空白约束；前者可返回200/500，作业包旧HTTP返回200且已写入前一合法租户 | 租户/作业元素增加NotBlank，初始化应用服务在任何目标事务前校验全部身份；类型null原已在HTTP反序列化层拒绝，NotNull仅补齐Bean声明，不计独立运行时Bug；可选空集合原语义保留 |
| BUG-20261027-05 / P2 | 配置类型大小写转换依赖JVM默认语言，土耳其语/阿塞拜疆语环境拒绝合法小写类型 | 使用Locale.ROOT；两环境回归先出现非法类型异常，修复后解析与兼容简写一致，默认Locale在测试后恢复 |
| BUG-20261027-06 / P1 | Workflow导入使用模糊编码搜索的第一条结果定位写入，UPSERT可改名并改图到相似编码，SKIP_EXISTING误跳过应创建的编码 | 增加精确编码最高版本查询；两种模式的真实PG回归均先红，修复后创建正确编码并保留原相似定义及图 |
| BUG-20261027-07 / P2 | 创建去重只查版本1；当前行升版后同码创建返回200并新增独立定义 | 创建按精确编码检查当前最高版本，真实认证HTTP返回409且仍一条定义；没有删除历史快照或修改迁移 |
| BUG-20261027-08 / P2 | 配置包导出同编码多版本物理定义为多条无版本Spec，旧定义可在目标重复应用并覆盖当前意图 | 按租户/编码只读最高版本及其图；当前版本停用仍保留停用，不回退旧启用版本。真实PG单项导出回归先红后绿 |
| BUG-20261027-09 / P2 | 执行池强停或停机线程被中断后，shutdownNow只移走排队任务，Future未终结，监听方继续等待到任务执行超时 | 取消shutdownNow返回的未启动Future，两条强停路径先红后绿；保留运行中业务的协作中断和原有宽限，不将Future取消等同于业务线程结束 |
| BUG-20261027-10 / P2 | 心跳请求在途时下线移除注册，迟到响应无条件put恢复旧注册；同标识新注册也被覆盖，并执行旧平台指令 | 缓存按原注册实例原子更新，已删除或替换时忽略回包及其指令；真实线程交错两项先红后绿，结构相等的新对象同样受保护 |

<a id="backend-review-影响路径"></a>

### 影响路径

- [复制与配置包服务](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultConsoleTenantConfigCopyService.java) → 同步导出/预览、租户复制/差异和作业矩阵。九类当前配置以及Workflow节点/边完整读取；首批复用既有空分页，后续增加精确编码和每编码最高版本两条查询，未修改Flyway。
- [作业矩阵构建器](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigMatrixBuilder.java) → [矩阵请求](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/application/contract/request/config/TenantConfigMatrixRequest.java) 的显式基准语义。OpenAPI描述与配对生成类型同步，无结构字段或路由新增。
- 前端复制面板的校验、写入及三类预览异步边界另记[前端FE-BUG-41/42](#frontend-review-record)，不把后端数据完整性与前端状态修复混成一个问题。

<a id="backend-review-排除与剩余范围"></a>

### 排除与剩余范围

首个图夹具使用203节点，超过现行DAG的200节点上限；虽然数据库可保存，其失败不作为正常业务缺陷证明。现使用实际DAG校验器通过的150节点/296边图。节点上限继续有效，不能据此限制边数或放宽节点准入。

新增原始队列/图测试先红，切换完整读取后旧Mock严格匹配非空分页的三项回归首轮失败；改为严格匹配空分页契约，业务断言不变。没有通过扩大页大小、取消公共上限或忽略失败解决问题。

源版本选择、源目标重合及请求元素由本批关闭；完整配置读取的目标容量/N+1、各配置类别的字段覆盖、目标环境字段保留、选项数据变化及旧复制入口持久未知结果恢复继续追查。真实浏览器跨账号联验、目标部署、所有SDK/脚本和全工程逐文件审查未完成。

<a id="backend-review-排除的候选与验证边界"></a>

### 排除的候选与验证边界

`appendCreatedVersionSnapshot`没有给临时请求设置nodes/edges，不等于快照丢图：实际消费者`WorkflowDefinitionWriteSupport.appendVersionSnapshot`在同一事务中重新读取节点/边并序列化。沿消费者取证后排除，未据此改代码。

配置身份阶段合跑140项，其中真实PostgreSQL61项；租户XML守卫、格式、432路由及配对类型漂移另列验证记录。现有物理历史行未被清理，普通列表的模糊搜索与历史版本接口保留原语义；本轮只改配置定位与当前版本读取。

<a id="backend-review-当前覆盖快照"></a>

### 当前覆盖快照

后端6,369项：157已审、59部分、6,152待审、1待复核；前端1,255项：95已审、30部分、1,130待审。新增Locale回归计入分母。并发改动的`BatchDayCutoffMultiTenantIntegrationTest`保留原证据与旧摘要，未替其他修改者销项。本批未提交、暂存或清理现有改动；共享索引内容不保证静止。

<a id="backend-review-配对作业包与向导续审"></a>

### 配对作业包与向导续审

前端FE-BUG-43至47继续沿作业包调用方核对异步反馈、关联选项、步骤校验和管理员权限入口，首红与最新227文件/1,417项等证据见[配对当日报告](#frontend-review-作业包与向导续审fe-bug-43至47)。后端本批只维护API/OpenAPI和运维说明；没有放宽ADMIN_ONLY或新增Java行为，因此不增加后端Bug计数或将旧140项重算为新运行结果。

最新前端清单1,256项：99已审、31部分、1,126待审；后端仍6,369项：157已审、59部分、6,152待审、1待复核。前端清单新增一份组件测试，权限store及路由库存测试实际全文核对，主路由与大组件仍部分。

<a id="backend-review-worker执行租约与心跳续审"></a>

### Worker执行、租约与心跳续审

两项新增问题分别定位于`TaskExecutionPool`的强停队列处理，以及`DefaultHeartbeatService`/`WorkerRuntimeState`的注册身份。排队Future的取消只针对shutdownNow实际移出的任务；`DefaultTaskExecutionWrapper`已有CancellationException路径负责返回取消结果，没有自动重跑业务或把运行中线程标为已退出。

下线与重新注册的回归使用真实线程、Latch和真实并发缓存，远程renew由可控替身阻塞；证明本地交错，未据此宣称真实Orchestrator网络、Pod强杀或全部停机组合已验证。Worker Core全模块370项通过，上游755项通过、5项环境条件跳过；完整命令及首红见当日验证记录。

续租HTTP明确404/409拒绝与其他错误传播已沿实现核对；原注释将所有4xx概括成不可认领并不符合代码，已修正说明，不计新增运行时Bug。注册缓存snapshot的消费者当前只读，没有仅因其返回视图而改集合语义。大执行包装器、完整HTTP客户端、抽象循环及共享关闭流程仍按实际片段留部分范围；五类Worker的全部业务执行、SDK与脚本继续审查。

<a id="backend-review-worker批次覆盖快照"></a>

#### Worker批次覆盖快照

后端6,369来源：177已审、61部分、6,127待审、4待复核；前端1,256来源：99已审、31部分、1,126待审。Worker核心新增23项全文审查完成，其他读取片段继续标部分。没有将Core370项测试通过自动记为该模块所有文件已审，原并发变化的日切测试仍待复核。本批未新增代码文件，未修改前端语义来源，未提交或暂存。

<a id="backend-review-收尾来源变化"></a>

#### 收尾来源变化

`TriggerAdmissionDrainTest`、`TriggerSchedulingOwnershipTest`及`ExternalEventApiKeyHttpIntegrationTest`在收尾核对时被并发修改，原已审内容摘要保留并转待复核，未覆盖改动；原日切测试待复核保留。最新后端177已审、61部分、6,127待审、4待复核，共6,369；前端99已审、31部分、1,126待审，共1,256。本轮Worker源码摘要未变，测试结果不外推到新Trigger内容。共享E2E变化只更新待审摘要。

<a id="backend-review-worker回报队列的执行身份与领取所有权"></a>

### Worker回报队列的执行身份与领取所有权

**BUG-20261027-11 / P1，已修复并验证。** 回报领取只携带行ID和载荷，确认删除及失败更新未绑定领取者。相同执行再次入队，或陈旧回收后由新轮询领取，旧HTTP响应仍可删除新报告、消耗新领取者重试预算或将其标为GIVE_UP。PostgreSQL和SQLite真实SQL均复现。现在每次领取生成claim_token，读取尝试次数、删除、重试、损坏载荷与耗尽放弃都要求ID、PUBLISHING及凭据一致；重新入队和退出领取时使旧凭据失效。写入返回行的MyBatis语句声明affectData/flushCache，确认属于事务写操作。放弃计量只在CAS实际生效后累加，同时覆盖耗尽路径，迟到失败不再产生虚假的重试/放弃告警。

**BUG-20261027-12 / P1，已修复并验证。** 原唯一键为租户/任务，旧执行的迟到回报会覆盖新执行的结果，反向顺序同样丢失一份已完成结果。现按租户/任务/partition_invocation_id去重；同执行仍更新一行，不同执行各自保存并独立确认。无执行身份统一以空串作为存储键，JSON载荷原有null不变。回报重投只提交结果，不重跑业务；过期执行仍由Orchestrator拒绝，不绕过服务端围栏。

新增V360仅用于ADR-048全新数据库、配套版本停旧启新。非空旧PG队列在任何结构修改前拒绝迁移并保留原数据；SQLite不自动升级、清空或搬运旧文件，校验不兼容结构后失败关闭。同版本文件重启保留待发结果。此前自动升级SQLite的草案已撤销，没有将兼容旧运行队列纳入实现。

核心实现为WorkerReportOutboxRepository、两个Mapper/XML及Coordinator，真实PG/SQLite参数化回归覆盖旧确认、陈旧回收、失败预算、损坏放弃、乱序执行、空身份去重及合法重试/放弃。同版本SQLite重开与两种旧存储拒绝另有正反例。Worker Core当前393项通过；上游755项通过、5项环境跳过；全新PG完整Flyway测试4项通过。首红、夹具错误和命令见当日验证记录。

载荷为合法JSON但业务身份缺失、极端重试配置溢出、BackendGuard完整切换组合及更大范围关闭组合尚未完成验证；前两项目前只列候选，不能计为已确认Bug。Repository和Coordinator本批在清单保留部分审查，不因定向回归而关闭全部输入与配置边界。平台迁移快照只更新实测发生变化的列、约束、索引三个摘要类别，未重生成种子或其他快照。

<a id="backend-review-bug-20261027-13--p2主机语言导致后端守卫误判"></a>

#### BUG-20261027-13 / P2：主机语言导致后端守卫误判

WorkerReportOutboxBackendGuard将存储枚举按默认语言转小写。土耳其语/阿塞拜疆语把SQLITE转为sqlıte，与原sqlite不相等；共享StatefulBackendGuard按已登记backend/identity精确比较，因此相同本地库在换语言重启后会要求不存在的后端切换。已改Locale.ROOT，保留原路径、功能键和切换策略。新增4项覆盖两语言及两存储，SQLite两项先失败、PG两项原本通过；原3项继续保留，定向7项和最终Worker Core397项通过。当前验证没有宣称真实多副本后端迁移已演练。

<a id="backend-review-本批范围盘点修正"></a>

#### 本批范围盘点修正

清单补入此前扩展名过滤漏掉的60份后端、8份前端自有文本来源，包括本批4份新源码/迁移，以及Spring服务注册、环境/HCL、schema摘要/种子、测试数据、包声明、Nginx模板、PWA清单和治理辅助资产。法律声明等辅助文本也显式列入，不隐含已审；二进制、依赖/构建/IDE产物、文档/Skill不计代码分母。当前后端6,429项：194已审、70部分审查、6,157待审、8待复核；前端1,264项的最新状态见配对报告。新增盘点不计审查完成，旧报告中分母为其当时快照。全工程目标仍未完成。

<a id="backend-review-下一批候选初查"></a>

#### 下一批候选初查

沿后端身份消费者初读StatefulBackendIdentity，发现对完整数据库位置、Redis Sentinel主名等统一小写，可能将大小写有业务含义的资源合并；尚未完成真实消费者及切换契约取证，不计为确认Bug。初读PipelineVerifierHook的默认语言转换，尚须核对内置及扩展适配器是否会传入合法小写类型；不能只凭toUpperCase无Locale就认定生产可绕过验证。这两份来源按实际阅读片段标部分，后续继续验证。

回报批次最终内容摘要核对又发现TenantConfigCopyTypeLocaleTest、TenantConfigInitCommitOutcomeIntegrationTest、TenantConfigInitOutcomeTest及TenantConfigBundleCompletenessIntegrationTest已被共享修改，保留旧摘要转待复核，与既有4份合计8份。没有覆盖这些测试或沿用配置批次旧通过结论；Outbox本批来源摘要未变。

<a id="backend-review-有状态后端与产物验收续审"></a>

### 有状态后端与产物验收续审

<a id="backend-review-bug-20261027-14--p2资源名折叠大小写绕过切换保护"></a>

#### BUG-20261027-14 / P2：资源名折叠大小写绕过切换保护

StatefulBackendIdentity原先把整个数据库地址、Sentinel主名、文件系统桶名和对象存储接入地址统一转小写。资源名仅大小写不同的两个目标因此产生相同身份；真实PostgreSQL登记后，四种无凭据切换都错误返回已验证。现在仅归一化协议与主机名，资源名称、路径和原始百分号编码保留；数据库认证段、查询及片段仍按原规则剥离。不同位置再次受既有一次性凭据和审计约束，失败拒绝保留原登记与唯一基线历史，不自动修改历史绑定。

单元四个反例先失败，真实PG四个拒绝反例也先失败，修复后均通过。此处验证的是定位序列化与持久守卫，不是四类远端存储的数据搬迁或容量演练。S3接入查询/片段的敏感值处理仍列进一步审查，不以本批宣称所有URI输入已完整关闭。

<a id="backend-review-bug-20261027-15--p1致命失败被可空错误码软化"></a>

#### BUG-20261027-15 / P1：致命失败被可空错误码软化

PipelineVerifierHook以firstFatalCode非空代替致命失败事实。合法失败元数据缺少错误码时，真实注册器仍返回失败，但Hook返回NO_FATAL；使用真实Hook/注册器的适配器回归实际返回成功。若后面还有一个带码的致命失败，首个失败信息也会被覆盖。

现在用独立boolean保留首个致命失败，其错误码和消息仍可空，由适配器原有VERIFIER_FATAL兜底处理。修复后适配器发出失败落库调用、返回失败且不发成功调用；这是单元级仓储替身验证，没有把该断言写成真实Pipeline数据库收敛。原有软告警、通过、未注册和未知类型反例继续保留。

<a id="backend-review-bug-20261027-16--p2redis生效配置未进入后端身份"></a>

#### BUG-20261027-16 / P2：Redis生效配置未进入后端身份

RuntimeInfrastructureInspector仅从Environment读取独立host/port/database和扁平Sentinel字符串，忽略URL优先级、索引列表、Cluster、MasterReplica及DataRedisConnectionDetails覆盖。客户端可连接新位置，守卫却继续拿旧属性或localhost身份比较；真实PG对五种配置来源变化均错误放行。

探针现使用与仓库当前Boot客户端相同的DataRedisConnectionDetails，依框架顺序选择Sentinel、Cluster、MasterReplica或Standalone；只读取定位，不读取用户名密码或建立连接。无有效定位时失败关闭。ComponentScan与AutoConfiguration回退均更新，Quota守卫直接消费该身份，原坐标DTO和重复属性解析移除。当前独立节点/Sentinel身份格式保留，新增两类拓扑有明确前缀；未增加环境变量或默认值，未改业务Redis连接、Quota算法或导入语义。

本地锁定Boot 4.1.1的真实配置绑定10项及真实PG守卫15项验证此边界；后者含五类来源变化、四类大小写变化和原六项。真实Redis配额6项、真实数据库配额12项及Quota守卫7项通过。没有验证真实Sentinel/Cluster/MasterReplica迁移、多副本切换或容量；JDBC自定义连接覆盖与手写客户端工厂仍待核对。

<a id="backend-review-bug-20261027-13扩展与排除的候选"></a>

#### BUG-20261027-13扩展与排除的候选

同一默认语言根因还影响对象存储FILESYSTEM和Quota REDIS两处后端标识。两组各7项中2个合法大写配置先抛不支持类型，S3/DATABASE及原分支为通过反例；两处仅改Locale.ROOT，沿用13号，不重复计Bug。

Pipeline类型转换的语言候选已排除：DictEnum.fromCode按equalsIgnoreCase匹配，土耳其语/阿塞拜疆语下小写import仍匹配并执行致命校验。两个真实注册器反例通过，未机械修改toUpperCase；已核对内置四类文件Worker的固定类型来源。后续继续审查注册器计量异常与失败返回的隔离、URI查询敏感值及JDBC覆盖，不把候选提前算修复。

<a id="backend-review-本批最新内容摘要快照"></a>

### 本批最新内容摘要快照

后端6,430来源：206已审、72部分、6,138待审、14待复核；前端1,265来源：102已审、38部分、1,125待审。本批各新增一份验证来源。ConfigSync应用服务、Calendar业务日解析器、TaskDispatchExecutor、ActiveTaskLeaseRegistry、WorkerRuntimeState、OutboxCoordinator六份共享生产来源又发生变化，保留旧摘要转待复核，与原八份测试合计14份；未替其他修改者销项。已核对WorkerRuntimeState仍按注册对象身份比较，Outbox的领取凭据和CAS仍在，其他片段继续审查。

上述分母是当前清单，不回写前段历史批次数字。整仓仍未完成逐文件Bug审核；Source盘点、配置/注入门禁和局部测试通过均不替代人工审查。当前专题继续保留URI查询敏感值、JDBC覆盖、计量异常及Nginx代理/日志候选。无提交、暂存、发布或主工作树修改。

<a id="backend-validation-record"></a>

<a id="backend-validation-后端-bug-续审验证2026-10-27"></a>

## 后端历史运行验证

> 以下保留各批次当时的发现和验证，不将历史进度或继续审查安排当作当前完成结论。

> **2026-10-27范围调整**：后续执行范围见[当前差异审查清单](../plans/todo-master.md#当前差异审查清单)。以下验证仍限于各自源码与环境快照；本次重新制定清单没有重跑编译或测试，也没有将旧结果升级为当前全工程通过。

本记录对应[当日确认问题](#backend-review-record)，延续原配对 `fix/wdi-current` 工作树。只使用测试容器、随机端口和自有场景数据，没有修改运行中的业务数据库或冻结工作树。

<a id="backend-validation-首个失败与根因"></a>

### 首个失败与根因

- `/private/tmp/wdi-config-bundle-completeness-red.log`：真实PostgreSQL的资源队列预期201条、实际200条。最初图夹具203节点也被截断，但超过当前节点准入上限，未将其计为正常业务证据；随后改为DAG校验器实际通过的150节点、296条边。
- `/private/tmp/wdi-config-bundle-completeness-green.log`：真实PG两个用例通过，旧复制Mock中的三个用例因仍匹配非空分页而失败。改为严格匹配空分页，保留依赖、日历、渠道与去凭据的原断言。
- `/private/tmp/wdi-config-matrix-baseline-red.log`：基准在展示范围外的两项新增用例失败，原列表内基准两项通过；旧代码实际返回空差异。

<a id="backend-validation-首批业务验证"></a>

### 首批业务验证

首批 **39项通过、0失败/错误/跳过**，其中 **22项真实PostgreSQL**：配置完整性2项、同步收据/认证HTTP7项、严格初始化与缓存事务13项。日志 `/private/tmp/wdi-tenant-copy-backend-final.log`。完整集合阶段另有17项通过，与39项重叠，不累计。

```sh
./mvnw -ntp -pl batch-console-api -am \
  -Dtest=TenantConfigBundleCompletenessIntegrationTest,DefaultConsoleTenantConfigCopyServiceTest,TenantConfigMatrixBuilderTest,DefaultConsoleConfigSyncBundleMappingTest,ConfigSyncAuditRecoveryIntegrationTest,TenantConfigBatchInitStrictAtomicityIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

真实PG验证201条队列全部进入配置包和差异Overlay，合法图保留150节点及296条边。矩阵新增测试验证外部基准加载与缺失作业事实，输出不额外加入基准行。

前端定向17项及全量 **226文件/1,389项通过**，实际命令、首红、构建与边界见[配对报告](#frontend-review-record)。前端组件/API替身、后端真实数据库和认证HTTP分别取证，未将其称为真实浏览器完整旅程。

<a id="backend-validation-限制"></a>

### 限制

没有执行容量、生产网络故障或目标部署验收，没有修改迁移。全量集合的内存/延迟规模、其余配置类别全部真实数据矩阵、当批尚待审的源版本选择与源目标重合已由后续批次修复；旧入口未知结果恢复继续审查。全工程内容摘要台账只更新实际读过的范围；盘点和本地全量单测不是全工程语义审查。

<a id="backend-validation-补充门禁与来源边界"></a>

### 补充门禁与来源边界

- `./mvnw -ntp -pl batch-console-api -am spotless:check`通过（`/private/tmp/wdi-tenant-copy-spotless-final.log`）。
- `python3 scripts/ci/check-console-openapi-paths.py`通过，432条路由一致（`/private/tmp/wdi-tenant-copy-openapi-paths.log`）。
- 配对生成类型按当前OpenAPI描述重生成，漂移检查通过。前端最终226文件/1,389项、构建和受影响文件lint通过；维护预览的初始组件假设已按服务端POST冻结契约更正，首红与最终结果见配对报告，未修改后端维护白名单。
- 首批清单快照后端6,368（146已审、55部分、6,167待审）、前端1,255（95已审、28部分、1,132待审）。新来源和并发变化只盘点；实际只读过分页片段的Mapper、请求元素和源版本仍标部分。全工程未完成。

<a id="backend-validation-后续批次源目标保护集合元素类型语言与workflow身份"></a>

### 后续批次：源目标保护、集合元素、类型语言与Workflow身份

<a id="backend-validation-首红及根因"></a>

#### 首红及根因

- `/private/tmp/wdi-copy-self-target-red.log`：认证HTTP/PG及内部调用两项均失败。真实响应200，原渠道`target_endpoint`与配置标记均变为null，预期400且两项保留；这不是仅静态推断。修复后读取源包前拒绝重合，新增中英文错误解析用例通过。
- `/private/tmp/wdi-copy-request-elements-red.log`：23项中15失败、8通过，包括9项Bean元素校验及14项认证HTTP。旧HTTP有200/500，也有已在其他边界返回400的反例；未把原本已拒绝的分支计新缺陷。现为租户/作业元素增加NotBlank、类型元素增加NotNull，另补可选集合省略/为空仍合法的正例。
- `/private/tmp/wdi-copy-type-locale-red.log`：土耳其语及阿塞拜疆语两项均因合法小写类型抛出IllegalArgumentException而错误退出。使用Locale.ROOT后通过；测试使用资源锁并恢复默认Locale，没有改变宿主机配置。
- `/private/tmp/wdi-workflow-definition-identity-red.log`：真实PG三项先失败；UPsert改写相似编码，SKIP_EXISTING跳过应建编码，升版后创建返回200/两条定义，预期409/一条。
- `/private/tmp/wdi-workflow-current-definition-red.log`：补齐有效原图夹具并加入同码多版本导出后，四项均失败。修复精确编码查询、全版本去重及最高版本导出后通过；停用的最高版本不会回退到旧启用版本。

独立阶段51项（公共10＋Console41）、集合/语言阶段78项、Workflow阶段92项均按各自快照保留日志；与最终合跑重叠，不累计相加。

<a id="backend-validation-workflow身份阶段合跑"></a>

#### Workflow身份阶段合跑

**129项通过、0失败/错误/跳过**，为`batch-common`11项加`batch-console-api`118项；其中真实PostgreSQL55项（认证/收据23、严格事务15、完整配置包3、Workflow变更10、版本写契约4）。日志 `/private/tmp/wdi-copy-workflow-governance-final.log`。

```sh
./mvnw -ntp -pl batch-console-api -am \
  -Dtest=TenantConfigBatchInitStrictAtomicityIntegrationTest,ConfigSyncAuditRecoveryIntegrationTest,TenantConfigBundleCompletenessIntegrationTest,WorkflowDefinitionMutationValidationTest,DefaultConsoleTenantConfigInitApplicationServiceTest,DefaultConsoleTenantConfigCopyServiceTest,WorkflowDefinitionWriteContractIntegrationTest,ConsoleWorkflowDefinitionMutationIntegrationTest,ConfigSyncTargetIdentityValidationTest,TenantConfigCopyTypeLocaleTest,DefaultConsoleConfigSyncBundleMappingTest,TenantConfigMatrixBuilderTest,BizMessageResolverTest,MessagesPropertiesParityTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

原Mock改用精确编码/最高版本查询，保留正常创建、跳过、托管单作业目标和版本CAS断言；创建后精确版本读取夹具直接返回刚保存实体，避免旧的多次返回序列提前截断后续图/快照路径。未删测试、扩大白名单或改变普通列表模糊搜索。

<a id="backend-validation-门禁及配对边界"></a>

#### 门禁及配对边界

- `MapperXmlTenantGuardArchTest`通过（`/private/tmp/wdi-workflow-identity-tenant-guard.log`），实际SQL租户谓词与参数绑定纳入检查。
- 受影响reactor `spotless:check`通过（`/private/tmp/wdi-copy-identity-spotless.log`）。
- `python3 scripts/ci/check-console-openapi-paths.py`通过，432路由一致（`/private/tmp/wdi-copy-identity-openapi.log`）。
- OpenAPI说明更新后生成配对类型，`npm run gen:api:check`通过（`/private/tmp/wdi-copy-identity-api-drift.log`）。
- 前端新增三项先红、修复后20项及全量226文件/1,392项通过；构建、类型、i18n与受影响文件ESLint通过，详见配对FE-BUG-42记录。

本批未修改Flyway、没有自动清理已有同码物理行或历史快照。没有验证实际部署容量、完整浏览器旅程、所有配置字段及环境覆盖策略，全工程逐文件审查继续进行。

<a id="backend-validation-类型null反例的后续复核"></a>

#### 类型null反例的后续复核

补测复制、差异、同步导出和同步预览四个真实HTTP入口，未加同步DTO NotNull时均已返回400；日志是反序列化层的InvalidNull错误。`/private/tmp/wdi-sync-type-elements-red.log`共5项仅1项Bean声明预期失败，4项HTTP通过，不是新API缺陷。四个选择器补齐NotNull声明属于契约元数据一致性，不计新增Bug；保留真实HTTP反例，并更正了早期可能成功的静态推断。集合元素Bug仍由那些已复现的租户/作业200/500及写入行为证明。

<a id="backend-validation-作业包导入的整组目标校验"></a>

#### 作业包导入的整组目标校验

`/private/tmp/wdi-job-bundle-target-elements-red.log`：6项中5失败、1通过。两个真实认证HTTP反例在目标列表为“合法目标＋null/空白”时返回200，且查询确认第一个目标已写入资源队列；空串已被原Size约束拒绝，不能算新失败。三个应用层用例原本未抛参数异常。

作业包DTO补齐NotBlank，初始化应用服务在任何目标事务前检查整个列表；未扩大事务到全批，合法目标的业务失败隔离及未知提交结果向上传播保持原语义。`/private/tmp/wdi-job-bundle-target-elements-green.log`定向8项通过（真实HTTP/PG3项，应用层5项）；最终关联结果另列，不与早期计数累加。

<a id="backend-validation-配置身份阶段最终合跑与清单"></a>

### 配置身份阶段最终合跑与清单

`/private/tmp/wdi-config-identity-transaction-final.log`完整命令退出0：**140项通过，0失败、错误、跳过**（公共模块11项、Console129项）。其中真实PostgreSQL **61项**：认证/审计/请求28、严格事务15、Workflow变更10、版本写契约4、完整配置包3、未知提交1。这个合跑包含前述129/131项和定向8项，不将重叠批次相加。

```sh
./mvnw -ntp -pl batch-console-api -am \
  -Dtest=TenantConfigBatchInitStrictAtomicityIntegrationTest,ConfigSyncAuditRecoveryIntegrationTest,TenantConfigBundleCompletenessIntegrationTest,WorkflowDefinitionMutationValidationTest,DefaultConsoleTenantConfigInitApplicationServiceTest,DefaultConsoleTenantConfigCopyServiceTest,WorkflowDefinitionWriteContractIntegrationTest,ConsoleWorkflowDefinitionMutationIntegrationTest,ConfigSyncTargetIdentityValidationTest,TenantConfigCopyTypeLocaleTest,DefaultConsoleConfigSyncBundleMappingTest,TenantConfigMatrixBuilderTest,BizMessageResolverTest,MessagesPropertiesParityTest,TenantConfigInitOutcomeTest,TenantConfigInitCommitOutcomeIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

当前清单后端6,369项（157已审、59部分、6,152待审、1待复核），前端1,255项（95已审、30部分、1,130待审）；以CSV内容摘要为准。只新增一份Locale测试；并发变化的日切测试保留旧摘要待复核。大服务、Mapper XML及作业包面板按已读取片段继续标部分，没有把测试通过当作全文审查。前端本批语义代码的全量单测仍为226文件/1,392项；本次后续仅更新OpenAPI生成说明。

<a id="backend-validation-收尾检查"></a>

#### 收尾检查

以下命令均观察到退出0，日志前缀为`/private/tmp/wdi-config-identity-`：

| 实际命令 | 结果/日志后缀 |
|---|---|
| `./mvnw -ntp -pl batch-console-api -am spotless:check` | 受影响reactor通过，`final-spotless.log` |
| `./mvnw -ntp -pl batch-console-api -am -Dtest=MapperXmlTenantGuardArchTest -Dsurefire.failIfNoSpecifiedTests=false test` | 2项通过，`final-tenant-guard.log` |
| `python3 scripts/ci/check-console-openapi-paths.py` | 432路由通过，`doc-routes.log` |
| `python3 scripts/ci/check-docs-structure.py` | 通过，`doc-structure-final.log` |
| `python3 scripts/ci/check-doc-timestamp-policy.py` | 通过，`doc-date.log` |
| `python3 scripts/ci/check-code-doc-references.py` | 通过，`doc-references.log` |
| `python3 scripts/ci/check-changelog-sync.py` | 通过，`doc-changelog-final.log` |
| 前端 `npm run typecheck` | 当前生成类型通过，`final-frontend-typecheck.log` |
| 前端 `npm run check:docs`、`npm run check:changelog` | 通过，`frontend-docs.log`、`frontend-changelog.log` |

作业包说明更新后再次执行前端`npm run gen:api`及`npm run gen:api:check`，两项退出0，日志为`/private/tmp/wdi-job-bundle-identity-api-gen.log`及`/private/tmp/wdi-job-bundle-identity-api-drift.log`。两仓`git diff --check`退出0；CSV行尾提示不代表运行缺陷，没有重写其他治理清单。全量后端业务测试、真实浏览器旅程和目标部署未在本批执行。

<a id="backend-validation-配对作业包向导继续验证"></a>

### 配对作业包/向导继续验证

后续FE-BUG-43..47仅改前端行为与后端接口说明，本批未重新执行Java测试；前一140项/真实PG61项保留原作用范围。前端最终227文件/1,417项、构建、定向lint及重新生成API类型漂移通过，独立首红和命令见[当日前端报告](#frontend-review-record)。全工程内容摘要清单以最新CSV和TODO为准，不沿用前段1,255项前端分母。

<a id="backend-validation-worker生命周期续审验证"></a>

### Worker生命周期续审验证

<a id="backend-validation-首红和定向恢复"></a>

#### 首红和定向恢复

- `./mvnw -ntp -pl batch-worker/core -am -Dtest=TaskExecutionPoolConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test`：首红`/private/tmp/wdi-worker-pool-shutdown-red.log`共5项，新增2项失败、原3项通过。正常强停和调用线程已中断两条路径的排队Future都未取消；实际排队业务没有执行。修复后同命令5项通过（`/private/tmp/wdi-worker-pool-shutdown-green.log`）。
- `./mvnw -ntp -pl batch-worker/core -am -Dtest=DefaultHeartbeatServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`：首红`/private/tmp/wdi-worker-heartbeat-late-red.log`9项中新增2项失败、原7项通过。真实线程让移除/替换发生在renew已发出而响应未返回时，旧put复活注册或覆盖新对象；修复后9项通过（`/private/tmp/wdi-worker-heartbeat-late-green.log`）。新注册字段相等但实例不同，不能用Data生成的equals替代所有权身份。
- 期间15类关联回归102项通过（`/private/tmp/wdi-worker-lifecycle-regression.log`），该快照早于心跳修复；与最终完整模块重叠，不加总。

<a id="backend-validation-完整受影响模块"></a>

#### 完整受影响模块

```sh
./mvnw -ntp -pl batch-worker/core -am test
```

命令退出0，日志`/private/tmp/wdi-worker-core-all-final.log`：**Worker Core 47份报告/370项全部通过，无失败、错误、跳过**，包含真实PG的Report Outbox 3项。上游`batch-common`692项中690通过、2跳过；`batch-test-support`68项中65通过、3跳过。整个reactor记录1,130项，其中**1,125通过、5跳过，零失败/错误**。没有将其他模块旧XML报告混入本次统计，模块聚合与命令日志一致。

5项均为已有条件化活体验证：2项缺少`BIZ_SHARD_0_URL`，3项缺少`S3_COMPAT_POC_ENDPOINT`。本轮没有设置环境变量绕过条件、删除测试或放宽断言；多分片路由及外部S3供应商活体契约仍未验证。

后续只修正claim/renew接口和实现中的注释：明确404/409才是业务拒绝，其他HTTP错误传播；修正非幂等起点方法的复制错误说明。行为未变，当前`./mvnw -ntp -pl batch-worker/core -am spotless:check`退出0（`/private/tmp/wdi-worker-lifecycle-spotless-final.log`）。没有执行全部五类Worker、全后端业务测试、真实网络故障/Pod滚动或目标环境验收；不可中断业务线程仍沿原看门狗与运维处理。

<a id="backend-validation-worker批次清单与剩余范围"></a>

#### Worker批次清单与剩余范围

当前后端6,369来源：177已审、61部分、6,127待审、4待复核；前端1,256来源：99已审、31部分、1,126待审。清单逐项使用当前文件摘要；未读完的HTTP客户端、执行包装器、抽象循环和跨组件关闭组合继续标部分。未执行另外四类Worker的全模块测试，也未执行完整前端测试：本批前端源码摘要未变，沿用前批227文件/1,417项限定快照。全工程目标继续进行。

<a id="backend-validation-收尾来源变化"></a>

#### 收尾来源变化

`TriggerAdmissionDrainTest`、`TriggerSchedulingOwnershipTest`及`ExternalEventApiKeyHttpIntegrationTest`在收尾核对时被并发修改，原已审内容摘要保留并转待复核，未覆盖改动；原日切测试待复核保留。最新后端177已审、61部分、6,127待审、4待复核，共6,369；前端99已审、31部分、1,126待审，共1,256。本轮Worker源码摘要未变，测试结果不外推到新Trigger内容。共享E2E变化只更新待审摘要。

<a id="backend-validation-worker批次文档检查"></a>

#### Worker批次文档检查

`python3 scripts/ci/check-docs-structure.py`、`check-doc-timestamp-policy.py`、`check-code-doc-references.py`、`check-changelog-sync.py`全部退出0；日志前缀`/private/tmp/wdi-worker-lifecycle-doc-`分别接`structure/timestamp/references/changelog.log`。配对前端`npm run check:docs`退出0，日志`/private/tmp/wdi-worker-lifecycle-frontend-docs.log`。当前`git diff --check`退出0。最初收尾脚本在发现已审来源漂移时主动中止，未据旧状态销项；随后显式转待复核并更新上述清单。

<a id="backend-validation-回报队列的领取cas与执行身份验证"></a>

### 回报队列的领取CAS与执行身份验证

<a id="backend-validation-首红与夹具问题分别保留"></a>

#### 首红与夹具问题分别保留

| 日志（均位于`/private/tmp/`） | 实际结果与归因 |
|---|---|
| `wdi-report-outbox-ownership-red.log` | 初始11项/8失败，其中4个陈旧回收失败含时间夹具错误，不能直接作为有效首红 |
| `wdi-report-outbox-ownership-valid-red.log` | 时间改为实际领取时刻后，11项中8项失败、3通过，证实两存储的迟到确认/失败/放弃覆盖 |
| `wdi-report-outbox-invocation-identity-red.log` | 15项中2失败，PG及SQLite不同执行互相覆盖，其他13项通过 |
| `wdi-report-outbox-stale-metric-red.log` | SimpleMeterRegistry不支持try-with-resources导致测试编译失败，仅夹具错误 |
| `wdi-report-outbox-giveup-metrics-valid-red.log` | 5项中2失败，失效领取的损坏指标误增、合法重试耗尽指标漏增 |
| `wdi-report-outbox-nonempty-cutover-red.log` | 非空旧PG队列拒绝的单项失败；为新迁移补上ADR-048切换前置守卫，不作为新增运行时Bug编号 |
| `wdi-report-outbox-core-all-final.log` | 22个PG/SQLite夹具错误，Spring SQL分隔器无法执行DO块；改用真实Flyway及隔离测试资源目录，未删迁移或跳过负例 |
| `wdi-report-outbox-guarded-focused-final.log` | 最终定向27项通过：Repository22（PG12、SQLite10）＋Coordinator5，无失败/错误/跳过 |
| `wdi-report-outbox-fresh-schema-red.log` | 全新数据库迁移成功，结构摘要3类别与旧预期不同；核对本次列/唯一键DDL后仅更新这3行，保留首红 |

<a id="backend-validation-最终受影响模块及全新数据库"></a>

#### 最终受影响模块及全新数据库

以下顺序执行且均观察到退出0，不与早期定向或生命周期批次重复加总：

```sh
./mvnw -ntp -pl batch-worker/core -am test
./mvnw -ntp -pl batch-orchestrator -am \
  -Dtest=LocalFlywayPlatformMigrationsIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

日志分别为`/private/tmp/wdi-report-outbox-core-all-verified.log`和`/private/tmp/wdi-report-outbox-fresh-migrations-verified.log`。当前Worker Core **393项全部通过**；上游common692项中690通过/2跳过，test-support68项中65通过/3跳过。reactor共1,153项，**1,148通过、5跳过**，零失败/错误。跳过仍为已有多业务分片2项及外部S3活体3项，未伪造环境或取消条件。

平台真实PostgreSQL完整Flyway迁移测试 **4项全部通过**，执行当前全链到V360，并核对结构/种子快照。仅更新fresh-platform-schema-manifest.sha256的COLUMN（4,958→4,959）、CONSTRAINT和INDEX三个实测摘要；未使用批量重写快照开关。Worker测试资源只复制V96/V360到独立fixture目录，通过Flyway执行，避免测试目录被当作完整迁移链自动扫描。

V360非空旧队列负例、SQLite旧结构拒绝及原载荷保留、同版本文件重开均由定向测试验证；本轮没有改共享数据库或尝试旧库升级。没有验证真实网络/多Pod故障、全五类Worker、容量或目标部署；配对前端无本批语义修改，旧227文件/1,417项结果只保留原快照。

<a id="backend-validation-后端身份语言复核后的最终结果"></a>

#### 后端身份语言复核后的最终结果

`./mvnw -ntp -pl batch-worker/core -am -Dtest=WorkerReportOutboxBackendGuardTest -Dsurefire.failIfNoSpecifiedTests=false test`首红日志为`/private/tmp/wdi-report-outbox-backend-locale-red.log`：7项中SQLite两语言2失败、5通过，实际得到sqlıte而非sqlite。Locale.ROOT修复后同命令7项通过，日志`wdi-report-outbox-backend-locale-green.log`。

再次执行`./mvnw -ntp -pl batch-worker/core -am test`，日志`/private/tmp/wdi-report-outbox-core-locale-verified.log`，退出0：**Worker Core397项全部通过**，上游仍755通过/5环境跳过，总1,157项中**1,152通过、5跳过**，零失败/错误。此为当前完整模块证据，覆盖前一393项，不累计相加；此前全新迁移4项仍对应相同V360与schema摘要。当前`./mvnw -ntp -pl batch-worker/core -am spotless:check`退出0，日志`wdi-report-outbox-last-spotless.log`。

补齐漏盘文本来源后分母后端6,429、前端1,264，不将新的待审资产算完成。当前状态及源码摘要以两仓CSV和TODO为准；当前PG/SQLite测试不替代全工程逐文件审查。

<a id="backend-validation-回报批次收尾门禁"></a>

#### 回报批次收尾门禁

以下检查均观察到退出0：后端`python3 scripts/ci/check-docs-structure.py`、`check-doc-timestamp-policy.py`、`check-code-doc-references.py`、`check-changelog-sync.py`（日志前缀`/private/tmp/wdi-outbox-docs-`，后缀分别为`structure/timestamp/references/changelog-final.log`）；`check-schema-governance-assets.py`通过。仓库锁定Squawk对V360单文件检查0问题（`/private/tmp/wdi-outbox-final-migration.log`）。前端`npm run check:docs`检查75份现行文档通过（`/private/tmp/wdi-outbox-frontend-docs-final.log`）。两仓`git diff --check`退出0；配对旧治理CSV有CRLF提示，未重写该文件。

测试后只修正PG/SQLite测试中的过期缓存说明注释，不改变测试体；继续核对全文后该测试来源标为已审。最新后端6,429项：194已审、70部分审查、6,157待审、8待复核；前端1,264项：101已审、35部分审查、1,128待审。已审/部分来源摘要均与当前文件一致；8份并发变动测试继续保留旧摘要待复核。本批全部写入既有配对wdi-current工作树，没有提交、暂存或修改main工作树。

<a id="backend-validation-后端定位与致命验收结果验证"></a>

### 后端定位与致命验收结果验证

<a id="backend-validation-独立首红与合法反例"></a>

#### 独立首红与合法反例

| 日志（`/private/tmp/`） | 实际结果 |
|---|---|
| `wdi-backend-resource-identity-red.log` | 身份单元6项中4失败/2通过；Maven快速失败导致下游未运行，未算真实PG证据 |
| `wdi-backend-resource-guard-red.log` | 单独执行真实PG守卫10项，新增4个大小写切换均未拒绝而失败，原6项通过 |
| `wdi-object-backend-locale-red.log`、`wdi-quota-backend-locale-red.log` | 两组各7项，2个配置解析错误、其余5项通过；错误是实际不支持fılesystem/redıs，不是环境问题 |
| `wdi-pipeline-fatal-null-code-red.log` | Hook/真实注册器9项中2失败，分别为致命事实丢失、首个失败信息被覆盖 |
| `wdi-pipeline-fatal-result-red.log` | 适配器5项中1失败，实际success=true；修复后验证失败调用、无成功调用及原兜底码 |
| `wdi-redis-effective-identity-red.log` | 真实Boot绑定10项中8失败，默认与扁平Sentinel两项为通过反例；临时入口等价委托原坐标读取，未把缺符号编译错误当首红 |
| `wdi-redis-effective-guard-red.log` | 真实PG15项中新增五类配置变化均未被拒绝，其他10项通过 |

原坐标DTO及重复解析在最终实现中移除，框架连接信息供探针与客户端共用。绑定测试禁用真实Redis客户端类加载，只验证框架属性、优先级、覆盖和凭据不进入身份；真实Redis业务结果另由集成类验证。

<a id="backend-validation-最终运行结果"></a>

#### 最终运行结果

```sh
./mvnw -ntp -pl batch-worker/core -am test
```

`/private/tmp/wdi-identity-core-current-final.log`退出0：**Worker Core402项全部通过**；common711项中709通过/2跳过；test-support77项中74通过/3跳过。共1,190项，**1,185通过、5环境跳过**，零失败/错误。5项仍为多业务分片2项及外部S3供应商活体3项；未删除或绕过条件。此为本批最新完整模块结果，覆盖前一400项及旧397/393项，不能重复加总。

```sh
./mvnw -ntp -pl batch-orchestrator -am \
  -Dtest=QuotaRuntimeBackendGuardTest,RedisQuotaRuntimeStateIntegrationTest,QuotaRuntimeStateIntegrationTest,StatefulBackendIdentityTest,StatefulBackendGuardIntegrationTest,RuntimeInfrastructureInspectorTest,ObjectStorageBackendGuardTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

`/private/tmp/wdi-identity-quota-runtime-final.log`退出0：common24、test-support15、Orchestrator25项全部通过；Orchestrator为守卫7＋真实PG配额12＋真实Redis配额6。上游39项与完整模块重叠，未累计为额外通过数。之后AutoConfiguration回退与Component路径定向39项通过（`wdi-identity-auto-wiring-final.log`），最终完整模块包含更新后的配置和夹具。

<a id="backend-validation-共享来源与格式首错"></a>

#### 共享来源与格式首错

`/private/tmp/wdi-identity-spotless-final.log`首次失败于共享修改后的WorkerReportOutboxRepository：null判定已换为EmptyChecks.isNull，但三元表达式排版不符合格式器。保存当时文件到`/private/tmp/wdi-shared-outbox-repository-format-before.java`，仅应用排版，保留该判定；已读isNull实现等价于引用==null。Outbox定向34项随后通过（`wdi-identity-outbox-shared-recheck.log`），最终完整模块也包含这些用例。

当前`./mvnw -ntp -pl batch-worker/core,batch-orchestrator -am spotless:check`退出0（`wdi-identity-spotless-current-final.log`）。没有扩大格式目标到无关来源或改白名单。新增PG测试SQL使用独立命名文本块，回读结果先承接再断言；fixture命名空间由本类共享，契约预期独立保留。

<a id="backend-validation-证据限制与后续"></a>

#### 证据限制与后续

本批未修改Flyway、API或SDK协议，也未执行全部Orchestrator/Console/五类Worker、真实Sentinel/Cluster迁移、容量和目标部署。前轮全新Flyway4项是相同迁移来源的独立证据；本批不能代替发布验收。URI查询敏感值、JDBC覆盖与注册器计量故障继续列候选。配对Nginx缺失静态制品的真实HTTP回归见当日前端记录，鉴权替身不能证明真实认证。

<a id="backend-validation-定位批次清单与来源变化"></a>

#### 定位批次清单与来源变化

当前后端6,430项（206已审、72部分、6,138待审、14待复核）、前端1,265项（102已审、38部分、1,125待审）。新增的两份验证代码进入原清单。六份共享生产来源在本批核对时发生内容变化，转待复核且保留原摘要；不能用本批测试结论为这些新内容整体销项。已读到的Worker注册身份与Outbox CAS关键条件仍保留，后续全文复核继续进行。

配置治理、默认值同步、Java注入与测试配置来源四项实际命令退出0，日志依次为`/private/tmp/wdi-identity-config-governance.log`、`wdi-identity-config-defaults.log`、`wdi-identity-injection.log`、`wdi-identity-test-config-sources.log`。默认值同步中的8项网络拓扑差异和既有1项实例命名白名单按原设计报告，未修改白名单。它们是静态门禁，不是全工程语义审查。

<a id="frontend-review-record"></a>

<a id="frontend-review-前端-bug-续审租户复制上下文2026-10-27"></a>

## 前端问题与历史运行验证

> 以下保留各批次当时的发现和验证，不将历史进度或继续审查安排当作当前完成结论。

> **2026-10-27范围调整**：原全工程逐文件执行方式已停止，改按[当前差异审查清单](../plans/todo-master.md#当前差异审查清单)核对已有修复及直接影响。本文保留历史批次及其证据，原继续审查安排不再是当前执行指令。代码审查仍暂停，本次只调整清单。

<a id="frontend-review-范围与基线"></a>

### 范围与基线

继续[原前后端逐文件任务](../plans/todo-master.md#前后端全工程逐文件-bug-续审2026-10-26)。现有 `wdi-current/batch-console`、`fix/wdi-current`，HEAD `662d254e2f8b97f93021de548815470d2291c997`；后端配对HEAD `f3d5e400d3b5bd737124b57cf0a38e685432aab2`。已有改动保留，未提交或发布。[26日原始证据](../../../batch-console/docs/reports/2026-10-26-frontend-bug-remediation.md)继续保留。

<a id="frontend-review-fe-bug-41--p2复制操作与预览异步失效"></a>

### FE-BUG-41 / P2：复制操作与预览异步失效

[复制面板](../../../batch-console/src/views/system/components/TenantCopyConfigDialog.vue)原先在校验完成后才设置忙碌状态，重复点击能排出多笔写请求；关闭、编辑、账号/权限或维护变化后，旧校验仍继续复制。旧复制响应可关闭重开的面板，三类预览也能恢复旧输入的结果并覆盖最新读取。

修复绑定面板、输入、主体和读取代次，写操作额外绑定写权限代次。校验前即占用本次操作；输入、账号或面板变化立即清空旧预览，旧读取收尾不能解除新请求的忙碌状态。发送使用独立目标与类型数组，迟到写结果不作用于新面板。这些预览端点虽为业务只读，但均使用POST，因此与正式复制/试运行一起按服务端HTTP方法策略冻结。已发送业务不因关闭面板撤销。

<a id="frontend-review-证据"></a>

### 证据

- 首红 `/private/tmp/wdi-tenant-copy-scope-red.log`：14项中13失败、1正常复制正例通过。
- 修复后17项通过（`/private/tmp/wdi-tenant-copy-scope-green2.log`），包括旧读取不解除新忙碌及矩阵无作业不发送的反例；维护边界在后续服务端复核中修正，见下。
- `npm run test:unit` 全量 **226文件/1,389项通过**（`/private/tmp/wdi-tenant-copy-all-unit-final.log`）；较早1,386项快照不代替补充反例后的结果。
- 类型、受影响文件ESLint和生产构建按实际命令执行，日志 `/private/tmp/wdi-tenant-copy-typecheck.log`、`/private/tmp/wdi-tenant-copy-lint-final.log`、`/private/tmp/wdi-tenant-copy-build.log`。后续协议说明及生成类型同步另保留最终构建/漂移结果。

后端配置包完整集合读取和外部矩阵基准为两项独立修复，见[后端当日报告](#backend-review-record)和[真实数据库验证](#backend-validation-record)。没有把组件替身称为真实浏览器跨账号联验。

<a id="frontend-review-未覆盖与下一步"></a>

### 未覆盖与下一步

旧复制入口没有同步导入的持久原键与目标收据恢复能力；未知结果仍需人工核对。源目标重合在下段FE-BUG-42处理，后端源版本选择另见当日续审；选项数据变化、配置字段和环境覆盖等继续追查。组件标记部分审查，全工程逐文件任务保持进行，不将本轮定向/全量单测通过作为全部人工审核完成。

<a id="frontend-review-fe-bug-41维护契约复核与当批证据"></a>

#### FE-BUG-41维护契约复核与当批证据

初版组件用例认为业务只读预览可在维护期间发送，但没有核对服务端。随后通读 `MaintenanceModeFilter`，确认其按HTTP方法统一冻结POST，三个预览并无旁路；组件替身的放行不能证明真实系统可用。修正预期后先红1项/原16项通过（`/private/tmp/wdi-tenant-copy-maintenance-red.log`），再让前端遵循服务端冻结契约，未改变过滤器或扩大维护白名单。

最终 `npm run test:unit` **226文件/1,389项通过，无失败/跳过**（`/private/tmp/wdi-tenant-copy-all-unit-verified.log`）；`npm run build`及受影响文件ESLint通过（`/private/tmp/wdi-tenant-copy-build-verified.log`、`/private/tmp/wdi-tenant-copy-lint-verified.log`）。OpenAPI新增外部基准描述后重新生成类型，`npm run gen:api:check`通过（`/private/tmp/wdi-config-matrix-api-drift.log`），后端432条路由核对通过。纯契约描述变更没有增加接口结构或权限。

<a id="frontend-review-fe-bug-42--p2更换源后保留重合目标"></a>

### FE-BUG-42 / P2：更换源后保留重合目标

选项列表虽过滤源租户，原表单目标值并未随源变化移除重合项；再次点击复制可把处理后的包回写源。现更换源时只移除重合项，保留其他目标，提交前再复核；不会通过更改已有在途请求的目标数组实现保护。本项是同一风险链的前端守卫，后端实际数据破坏和读写前拒绝见BUG-20261027-03，不重复称为两次生产事故。

首红 `/private/tmp/wdi-copy-source-target-ui-red.log`：新增3项失败、原17项通过；修复后20项通过（`/private/tmp/wdi-copy-source-target-ui-green.log`），覆盖目标移除、异常重合状态拒绝和其余目标保留。

最终 `npm run test:unit` **226文件/1,392项通过、0失败/跳过**（`/private/tmp/wdi-copy-source-target-front-all.log`），`npm run build`及受影响组件/测试/语言包ESLint通过（`/private/tmp/wdi-copy-identity-front-build.log`、`/private/tmp/wdi-copy-identity-front-lint.log`）；构建含类型与i18n检查。OpenAPI说明重新生成类型后漂移检查通过。

当前用户说明、API协议和运行手册已同步。后端最终关联140项、真实PG61项为独立层级证据；未进行真实浏览器跨账号旅程或目标部署验证。旧复制未知结果持久恢复、目标环境字段保留与完整字段覆盖仍待继续审查。

<a id="frontend-review-fe-bug-42阶段清单与契约说明收尾"></a>

### FE-BUG-42阶段清单与契约说明收尾

当批1,255个来源：95已审、30部分、1,130待审。作业包列表与向导只初读提交/目标/结果片段，异步上下文及完整映射仍待验证，不因接口单测通过升级为全文已审。

后端新增作业包整组目标校验说明后重新执行`npm run gen:api`与`npm run gen:api:check`均退出0（日志`/private/tmp/wdi-job-bundle-identity-api-gen.log`、`/private/tmp/wdi-job-bundle-identity-api-drift.log`）；`npm run typecheck`退出0（`/private/tmp/wdi-config-identity-final-frontend-typecheck.log`）。后续只更新生成注释，未改变组件语义，沿用上段1,392项当前语义快照，不重复计数。

<a id="frontend-review-作业包与向导续审fe-bug-43至47"></a>

### 作业包与向导续审：FE-BUG-43至47

继续同一`fix/wdi-current`配对工作树，本批后端只同步接口和运维说明，未变更Java或数据库行为。原140项后端结果只属于上一批源码快照；本批前端单测与构建单独取证。

| ID | 触发与旧行为 | 修复范围 |
|---|---|---|
| FE-BUG-43 / P2 | 作业包导入发出后切租户、账号、权限/维护代次、改输入或关闭重开，旧成功仍提示、关闭新面板并刷新新上下文；完成时还读取当前试运行开关 | 绑定输入/面板修订与租户生命周期，反馈只作用于发起范围；请求及结果提示使用发送时模式 |
| FE-BUG-44 / P2 | 打包新建等待时修改作业编码，旧成功用新编码导航；切换上下文或卸载后仍提示/导航，处理器缺少在途重复守卫 | 固定提交编码，绑定输入/步骤/主体/写权限修订与租户生命周期；旧反馈失效，在途提交零重复发送 |
| FE-BUG-45 / P2 | 元数据或关联渠道的旧租户响应覆盖当前队列/选项，切租户后旧选择保留到新读取完成 | 整个元数据→关联读取共享范围代次；切租户/操作者同步清空旧选项和选择，旧请求不能续发读取、覆盖结果或解除新读取忙碌 |
| FE-BUG-46 / P2 | 异步步骤校验期间多次点击叠加推进；旧租户/操作者的校验继续推进当前步骤 | 校验期间锁定一次推进，校验完成核对输入/上下文及原步骤；输入变化需重新校验 |
| FE-BUG-47 / P2 | 前端允许租户管理员进入打包向导，普通读取角色也看到可用导出动作；服务端控制器当前为ADMIN_ONLY | 向导路由、写入及导出动作对齐已有ROLE_ADMIN契约；普通单作业创建仍按原配置写权限提供，不放宽服务端授权 |

<a id="frontend-review-首红与回归"></a>

#### 首红与回归

- `/private/tmp/wdi-job-bundle-context-red.log`：16项中14失败、2正常路径通过，真实组件配合可控Promise复现旧响应和重复提交，旧导航实际携带JOB_B而提交的是JOB_A。
- `/private/tmp/wdi-job-wizard-associations-red.log`：新增3项失败、原16通过，旧渠道/队列覆盖和旧选择残留均由组件可观察结果证明。
- `/private/tmp/wdi-job-wizard-step-red.log`：新增3项失败、原19通过，重复点击实际叠加推进两格，旧上下文校验仍推进一格。
- `/private/tmp/wdi-job-bundle-permission-red.log`：组件入口与路由各1项失败，原25项通过。导出单项首红`/private/tmp/wdi-job-bundle-export-permission-red.log`按名称只执行新增1项并失败，其余23项被过滤，不算通过或删除测试。
- `/private/tmp/wdi-job-bundle-permission-green.log`：最终定向2文件/28项通过、无跳过（作业包组件24项、路由库存4项）。权限依据服务端`ConsoleJobBundleController`的现有ADMIN_ONLY与前端路由消费者，不把standalone MockMvc当作真实方法授权验证。

当前用户指南、角色矩阵、根Changelog、API协议与运维手册同步。前端的失效判断不会取消已发送的服务端业务；响应不确定仍需核对原目标，旧复制/作业包入口尚未具有配置同步的完整持久原键/收据恢复。关联类型完整字段映射、容量与真实浏览器/跨账号/目标环境验证继续开放。

<a id="frontend-review-本批最终验证与覆盖"></a>

#### 本批最终验证与覆盖

- `npm run test:unit`：**227文件/1,417项通过，无失败/跳过**，日志`/private/tmp/wdi-job-bundle-context-all-final.log`。前一1,414项快照在补齐角色/导出反例之前，不作为最终计数；各阶段用例重叠，不累加。
- `npm run build`：类型、i18n及生产/PWA构建通过，日志`/private/tmp/wdi-job-bundle-context-build-final.log`。
- `npx eslint src/views/job/JobDefinitionList.vue src/views/job/JobDefinitionWizard.vue src/views/job/JobBundleContextRisks.test.ts src/router/index.ts src/router/routeInventory.test.ts`退出0，日志`/private/tmp/wdi-job-bundle-context-lint-all-final.log`。
- 更新OpenAPI说明后执行`npm run gen:api`及`npm run gen:api:check`均退出0，日志`/private/tmp/wdi-job-bundle-context-api-gen.log`、`/private/tmp/wdi-job-bundle-context-api-drift.log`。

最新清单**1,256来源：99已审、31部分、1,126待审**。新增一份24项组件测试；路由库存测试、权限store及其测试完成全文核对。大列表、向导和路由主文件仍按实际片段标部分。全工程逐文件审查保持进行，当前前端单测不等同于真实后端、浏览器或全部文件审查。

<a id="frontend-review-文档与契约收尾"></a>

#### 文档与契约收尾

`npm run check:docs`及`npm run check:changelog`均退出0，日志`/private/tmp/wdi-job-bundle-context-docs.log`、`/private/tmp/wdi-job-bundle-context-changelog.log`。配对后端实际执行`python3 scripts/ci/check-docs-structure.py`、`check-doc-timestamp-policy.py`、`check-code-doc-references.py`、`check-changelog-sync.py`与`check-console-openapi-paths.py`，全部退出0；日志前缀`/private/tmp/wdi-job-bundle-context-be-doc-`分别接`structure/date/references/changelog/routes.log`。路由一致性为432条。两仓源码摘要复核均无漂移，已有待复核项仍保留；两仓`git diff --check`退出0。当前无新增编译或运行失败，不将尚未执行的浏览器/真实账号边界、全部后端测试及剩余逐文件审查记为通过。

<a id="frontend-review-部署文本与清单补漏续审"></a>

### 部署文本与清单补漏续审

补入此前扩展名过滤遗漏的8份文本资产：Nginx模板、E2E声明、PWA清单、示例数据及辅助治理文本。当前1,264项：101已审、35部分审查、1128待审；新增盘点不计语义审查。声明已逐项对照运行配置导出，PWA入口及7个图标引用核对；Nginx模板和片段、E2E配置仍按实际读取范围标部分。

当前没有新增已确认前端Bug或语义修改，未重跑组件测试/构建；原227文件/1,417项及构建仅保留原来源快照。静态资源不存在时的Nginx全局404回退列为待运行验证候选，不能仅凭模板推断计为已确认Bug；真实代理、长连接、浏览器PWA安装/离线及目标部署仍未验。后端当前回报队列批次见[配对验证](#backend-validation-回报队列的领取cas与执行身份验证)。

<a id="frontend-review-静态制品错误状态续审"></a>

### 静态制品错误状态续审

<a id="frontend-review-fe-bug-48--p2缺失静态制品错误返回成功入口"></a>

#### FE-BUG-48 / P2：缺失静态制品错误返回成功入口

真实Nginx复验确认，缺失JS、字体、图片和version.json先被各location判404，却被server级error_page重定向为200的SPA HTML；icons/manifest则因通用history回退返回同样的错误内容。脚本/版本读取因此得到内容类型和内容不符的成功响应，不能据HTTP200判断制品可用。

删除全局404转入口，只由普通前端路径承担history回退。icons前缀和manifest精确路径独立检查存在性，固定文件名仍使用no-cache；已存在资源内容、hash资源缓存、相对/docs跳转和API转发保留。对新指令显式包含原安全头片段，未更改登录、权限或后端契约。Dockerfile和模板注释同步实际BACKEND_UPSTREAM_HOST变量名，镜像版本和构建指令未改。

<a id="frontend-review-首红最终回归及排除的推断"></a>

#### 首红、最终回归及排除的推断

- `/private/tmp/wdi-nginx-routing-red.log`最初9场景中2失败，实际/assets缺失JS和缺失version.json均200/SPA HTML。
- 扩展后的`wdi-nginx-routing-extended-red.log`及仓库回归`wdi-nginx-routing-contract-red.log`覆盖15个HTTP子场景，6失败/9通过。父测试随子失败，不能把父层再计一个业务缺陷。
- `npm run test:nginx-routing`最终退出0，`/private/tmp/wdi-nginx-routing-contract-final.log`记录15个HTTP子场景全通过；Node连父层共16条，无失败/取消/跳过。使用Dockerfile声明的不可变Nginx 1.30.5/Alpine镜像`sha256:0985e772fb9f729e6fa0980da05fca5d9c468e870eed43071545afa9d2e27d94`，实际加载仓库nginx.conf、模板和片段，启动后另执行nginx -t。
- 已登录文档缺页原本就是404，API404也原本正确；初始关于文档会落入SPA的静态推断已由运行反例排除，没有算作新修复。相对/docs的301、正常文档/脚本/图标/清单及普通前端路径作为正例保留。

测试脚本只生成独占临时制品和鉴权204替身，发布随机回环端口，清理先核对本例容器标签。缺失清单仅删除生成的临时文件；未写共享应用环境或删除生产制品。它验证真实Nginx HTTP路由，不证明真实认证、根用户限制、完整应用镜像、PWA安装、浏览器交互或目标部署。本批没有新增Vue/TS行为修改，未重跑组件全集或生产bundle；旧227文件/1,417项保留原快照，不重算为本批结果。

<a id="frontend-review-剩余范围"></a>

#### 剩余范围

Nginx上游Host/转发端口与OIDC、访问日志中的查询数据、SSE/上传/完整代理链仍待追查；模板及相关配置继续按实际范围标部分。新增HTTP回归来源计入原逐文件清单，不把某一服务器回归当全前端审核完成。配对后端定位与产物致命失败的证据见[后端当日记录](#backend-validation-后端定位与致命验收结果验证)。

<a id="frontend-review-静态制品批次清单与轻量检查"></a>

#### 静态制品批次清单与轻量检查

最新前端1,265来源：102已审、38部分、1,125待审；新增一份Node/Docker HTTP回归，模板、核心配置、构建与代理相关入口仍按实际阅读范围标部分。后端清单6,430来源中14份共享变动待复核，不将旧证据套到新内容。

`npx eslint scripts/tests/nginx-routing.test.mjs`、`npx prettier --check scripts/tests/nginx-routing.test.mjs`、`npm run check:env`、`npm run check:test-fixture-sources`均退出0；日志前缀为`/private/tmp/wdi-nginx-routing-`，分别为`eslint.log`、`prettier.log`、`env.log`和`fixture-sources.log`。未新增默认CI/发布阻断策略，专用回归由独立npm命令运行。

## 2026-11-01：短断言历史候选定位索引

以下仅保存 2026-10-30 筛选快照的文件和测试名称，便于逐项定位；名称和测试体可能已变化或合并。此表**不是逐项审核结论**，已核查与已整改结果以上文具体记录为准。路径分别相对后端仓库或配对前端仓库。

### file-batch-system

| 测试文件 | 快照中的候选测试 |
|---|---|
| `batch-common/src/test/java/io/github/pinpols/batch/common/config/HikariPgSessionSupportTest.java` | `shouldTruncateApplicationName_whenExceedingSixtyThreeChars` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/constants/BatchFileConstantsPartitionTagTest.java` | `shouldInsertTagBeforeExtension`、`shouldAppendTag_whenNoExtension`、`shouldReturnUnchanged_whenSinglePartition` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/enums/CatchUpPolicyTypeTest.java` | `fromCodeOrDefault_unknownCode_returnsNone` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/enums/ConfigLifecycleStatusTest.java` | `shouldContainFiveValues` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/enums/DeadLetterReplayStatusTest.java` | `shouldContainFiveValues` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/enums/JobTypeTest.java` | `shouldNotExposeWorkflowAsExecutableJobType`、`shouldProvideNonNullBatchType_whenEnumeratingEveryJobType` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/enums/PartitionStatusTest.java` | `shouldContainNineValues` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/enums/RetryPolicyTypeTest.java` | `shouldContainThreeValues` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/enums/RetryScheduleStatusTest.java` | `shouldContainSixValues` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/enums/TaskStatusTest.java` | `shouldContainSevenValues` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/enums/WorkflowJoinModeTest.java` | `fromCodeOrDefault_unknownCode_returnsAll` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/i18n/MessagesPropertiesParityTest.java` | `shouldRejectDuplicateKeysAndBlankValues` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/jdbc/JdbcMappedSqlValidatorTest.java` | `shouldNormalizeValidIdentifierToLowerCase`、`shouldQuotePgIdentifier` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/logging/LogSanitizerTest.java` | `shouldReplaceEveryUnicodeLineBreakWithUnderscore` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/page/CursorCodecTest.java` | `shouldReturnNull_whenCursorIsEmpty` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/security/CredentialEnvResolverTest.java` | `returnsNull_whenNull` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/security/DnsResolveGuardTest.java` | `shouldAllow_whenDocumentationGlobalIpv6` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/service/BatchObjectCryptoServiceTest.java` | `decryptNullBytes_shouldReturnNull`、`decryptEmptyBytes_shouldReturnEmpty`、`resolveKeyRef_fallsBackToDefault_whenSecurityMapEmpty` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/spi/task/TaskResultTest.java` | `shouldDefaultOutputToEmptyMap_whenOutputAbsent` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/stateful/StatefulBackendIdentityTest.java` | `shouldPreserveResourceCase_whenBuildingBackendIdentity` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/storage/S3ObjectStoreExceptionMappingTest.java` | `shouldReportMissing_whenKeyDoesNotExist` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/utils/ContentMaskingUtilsTest.java` | `shouldApplyTextRulesToMalformedJsonLikeInput` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/utils/CorrelationIdsTest.java` | `normalize_trimsAndKeepsSafeCharacters`、`normalize_replacesUnsafeCharactersAndControls`、`normalize_capsAtHeaderColumnLength`、`normalize_usesFallbackWhenInputBlankOrInvalid` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/utils/IdGeneratorTest.java` | `shouldReturn32HexChars_whenGeneratingTraceId`、`shouldReturn32HexChars_whenGeneratingInvocationId` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/utils/OtelTraceContextTest.java` | `shouldReturnNull_whenNoActiveSpan`、`shouldFallBackToGeneratedId_whenNoActiveSpan` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/validation/ValidBizDateValidatorTest.java` | `shouldTrimWhitespace_whenValidating` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/validation/ValidResourceCodeTest.java` | `shouldReject_whenCodeNull`、`shouldReject_whenCodeEmpty`、`shouldReject_whenCodeBlank` |
| `batch-common/src/test/java/io/github/pinpols/batch/common/validation/ValidTenantIdTest.java` | `shouldReject_whenTenantIdNull`、`shouldReject_whenTenantIdBlank`、`shouldAccept_whenTenantIdAtMaxLength`、`shouldReject_whenTenantIdExceedsMaxLength` |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/config/ConsoleOidcPropertiesTest.java` | `shouldAllowEmptySettings_whenOidcIsDisabled` |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/audit/service/ConsoleAiPromptGuardTest.java` | `shouldThrowBizExceptionForNullPrompt`、`shouldThrowBizExceptionForBlankPrompt` |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/job/support/BuiltinTaskTypeGuardTest.java` | `shouldAllowType_whenJobTypeNullOrBlank`、`shouldAllowBuiltinType_whenAnyGrantedRoleIsPlatformAdmin` |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/ops/infrastructure/TriggerProxyJsonContractTest.java` | `shouldReturnEmptyList_whenDownstreamRowsAreNull` |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/ops/service/WorkerCompatibilityEvaluatorTest.java` | `shouldReportOk_whenMajorOneFormatsVary` |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/rbac/support/ConsolePasswordHasherTest.java` | `shouldMatchFlywaySeedArgon2Hash` |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/rbac/support/TenantScopeTest.java` | `shouldReturnTenantId_whenNonBlank` |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/rbac/support/captcha/TencentCaptchaVerifierTest.java` | `shouldReportTencent_whenQueryingProviderName` |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/workflow/param/WorkflowDefinitionWriteParamsTest.java` | `shouldRejectMissingRequiredField` |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/workflow/web/support/WorkflowMermaidRendererTest.java` | `shouldReturnHeaderOnly_whenDetailMissing` |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/ReadReplicaHappyPathIntegrationTest.java` | `shouldRouteToReplica_whenTransactionReadOnly` |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/support/cache/ConsoleQueryCacheServiceTest.java` | `keySegment_keepsShortSafeValuesReadable` |
| `batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/arch/PipelineWorkerAtomicIsolationOptInArchTest.java` | `shouldOptInIsolationCheck_whenImportWorker`、`shouldOptInIsolationCheck_whenExportWorker`、`shouldOptInIsolationCheck_whenProcessWorker`、`shouldOptInIsolationCheck_whenDispatchWorker` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/scheduler/DryRunSchedulingPriorityTest.java` | `shouldKeepConfiguredPriority_whenFormalTask`、`shouldKeepNullPriority_whenFormalTaskHasNoPriority` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/DefaultCompensationServiceTest.java` | `shouldThrowWhenCommandIsNull` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/DefaultWorkerDrainGovernanceServiceTest.java` | `shouldThrowWhenTenantIdIsBlankOnStartDrain`、`shouldThrowWhenWorkerCodeIsBlankOnStartDrain`、`shouldThrowWhenTenantIdIsBlankOnForceOffline`、`shouldThrowWhenWorkerCodeIsBlankOnListClaimedTasks`、`warmup_throwsWhenTenantBlank` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/DefaultWorkflowDagServiceTest.java` | `shouldReturnEmptyWhenDefinitionIdIsNull`、`shouldReturnEmptyWhenCurrentNodeCodeIsBlank`、`shouldReturnFalseWhenWorkflowRunIdIsNull`、`shouldReturnFalseWhenDefinitionIdIsNull`、`shouldReturnFalseWhenNodeCodeIsBlank` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/WorkflowConditionEvaluatorTest.java` | `shouldMatchEqualityWithDoubleEquals`、`shouldReturnFalseWhenPayloadKeyMissing` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/sensor/HttpPollSensorPolicyTest.java` | `matchExpr_missingPath_returnsFalseNotThrow` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/workflow/BizDateArithmeticTest.java` | `shouldReturnNull_whenNamedOffsetUnknown`、`shouldReturnEmpty_whenRangeTagUnknown`、`shouldReturnEmpty_whenBizDayCountExceedsCap` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowFanOutSupportTest.java` | `parseSpec_nullWhenItemsExprBlank` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowGraphValidatorTest.java` | `shouldReportClean_whenWorkflowIdMissing` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowWaitSensorSpecValidatorTest.java` | `shouldIgnoreNonWaitNode`、`shouldRejectUnknownSensorType` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/OrchestratorGracefulShutdownTest.java` | `shouldNotBeDrainingInitially` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/mq/BatchTopicResolverTest.java` | `shouldReturnNull_whenWorkerTypeUnsupported` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/router/DefaultWorkerRoutingPolicyTest.java` | `shouldReturnNullForNullCandidates`、`shouldReturnNullForEmptyCandidates` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/statemachine/DefaultLifecycleEventMapperTest.java` | `shouldResolveEnumTargetAsState`、`shouldAllowTerminalSelfLoop` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/AdminTestDataCleanupControllerIntegrationTest.java` | `shouldRejectAmbiguousInstanceId_whenNeighborIsInSameTenant`、`shouldRejectAmbiguousInstanceId_whenNeighborIsInAnotherTenant`、`shouldRejectAmbiguousInstanceId_whenNeighborIsArchived` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/ConcurrentTaskClaimIntegrationTest.java` | `assignWorker_onlyOneWorkerWins_acrossRepeatedLaunches` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/RetryExecutionClosureIntegrationTest.java` | `shouldRejectRetryWhenDryRunPayloadUnsafe`、`shouldRejectRetryWhenFormalPayloadClaimsDryRun`、`shouldRollbackFileRedispatchWhenParentIsPaused`、`shouldRollbackFileRedispatchWhenTaskCannotReset`、`shouldRejectFileRedispatchWhenExternalOutcomeIsUnknown`、`shouldRejectFileRedispatchWhenRecordIsNotKnownFailed`、`shouldRejectFileRedispatchWhenSingleTaskHasNoFileAssociation`、`shouldRejectRedispatchWhenPayloadTargetDiffers` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/StartupSelfCheckIntegrationTest.java` | `shouldExposeSelfCheckComponent_whenContextStarts` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/scheduler/DefaultPrioritySchedulerTest.java` | `shouldReturnDefaultPriorityFiveWhenRequestIsNull`、`shouldReturnMediumBandWhenPriorityIsNull` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/scheduler/quota/QuotaResetPolicyTest.java` | `shouldReturnNoneWhenValueIsNull`、`shouldReturnNoneWhenValueIsBlank`、`shouldReturnNoneWhenValueIsUnknown`、`shouldReportNoneAsNotRuntimeManaged`、`shouldReportCalendarDayAsRuntimeManaged`、`shouldReportSlidingWindowAsRuntimeManaged` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/security/RequestSignatureVerifierTest.java` | `shouldRejectRequest_whenSignatureHeaderMissing` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/service/LaunchParamResolverTest.java` | `shouldReturnNullForNullDeadline` |
| `batch-test-support/src/test/java/io/github/pinpols/batch/common/rls/RlsClosedWorldCheckIntegrationTest.java` | `shardIdentity_defaultExempted` |
| `batch-trigger/src/test/java/io/github/pinpols/batch/trigger/application/TriggerOutboxReleaseGovernorTest.java` | `disabled_returnsConfiguredLimitWithoutReadingLag` |
| `batch-trigger/src/test/java/io/github/pinpols/batch/trigger/infrastructure/TriggerGracefulShutdownTest.java` | `shouldNotBeDrainingInitially` |
| `batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/BatchTriggerApplicationIntegrationTest.java` | `contextLoads_succeeds`、`quartzScheduler_isStarted` |
| `batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/MisfireHandlerIntegrationTest.java` | `shouldWireMisfireHandlerBean`、`shouldLoadQuartzLaunchJobForMisfireAuditPath`、`shouldNotThrowWhenHandlingMisfire` |
| `batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutorIpBlockTest.java` | `rejects_loopback_ipv4`、`rejects_loopback_ipv6`、`rejects_link_local_metadata_ipv4`、`rejects_link_local_metadata_ipv4_mapped_ipv6`、`rejects_private_class_a`、`rejects_private_class_c`、`rejects_any_local`、`accepts_public_literal`、`accepts_public_literal_ipv6`、`rejects_ipv6_ula_aws_metadata`、`rejects_ipv6_ula_fc00_range`、`rejects_ipv6_ula_fd_range`、`rejects_ipv6_link_local` |
| `batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/integration/BatchWorkerAtomicApplicationIntegrationTest.java` | `shouldExposeApplicationContext_whenStarted` |
| `batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/runtime/AtomicIsolationStartupCheckTest.java` | `noDualUseEnabled_noThrow` |
| `batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/spark/SparkSubmitTaskExecutorTest.java` | `taskType_isSparkSubmit` |
| `batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/sql/SqlTaskExecutorHardeningTest.java` | `shouldClassifyAsDdl_whenStatementIsDoBlock`、`shouldClassifyAsDdl_whenDoBlockLowercase` |
| `batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/sql/SqlTaskExecutorTest.java` | `shouldReturnSingleStatement_whenNoSemicolon` |
| `batch-worker/core/src/test/java/io/github/pinpols/batch/worker/core/infrastructure/HttpTaskReportBatchTest.java` | `reconcilesEachResponseIdentity` |
| `batch-worker/core/src/test/java/io/github/pinpols/batch/worker/core/infrastructure/checkpoint/CheckpointPartitionGuardTest.java` | `nullMeansSinglePartition_allow` |
| `batch-worker/core/src/test/java/io/github/pinpols/batch/worker/core/reportoutbox/WorkerReportOutboxPgIntegrationTest.java` | `claimNext_returnsEmptyWhenNothingDue` |
| `batch-worker/core/src/test/java/io/github/pinpols/batch/worker/core/support/ActiveTaskLeaseRegistryTest.java` | `shouldReturnEmptySnapshotWhenNoLeases` |
| `batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/DispatchTaskExecutorTest.java` | `shouldReportDispatchTaskType_whenTaskTypeQueried` |
| `batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/DispatchChannelCircuitBreakerTest.java` | `shouldAllowWhenNoFailuresRecorded`、`shouldIsolateFailuresByChannelKey` |
| `batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/DispatchChannelTypePolicyTest.java` | `shouldReturnCanonicalType_whenInputHasSpacesAndLowercase`、`shouldReturnEmpty_whenTypeUnknown` |
| `batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/DispatchManifestSupportTest.java` | `shouldKeepNull_whenBusinessDateMissing` |
| `batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/integration/BatchWorkerDispatchApplicationIntegrationTest.java` | `shouldLoadApplicationContext_whenStarted` |
| `batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/integration/DispatchSpiEquivalenceIntegrationTest.java` | `shouldContainDispatchTaskType_whenListingRegisteredTypes` |
| `batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/stage/AckDispatchStepTest.java` | `stage_returnsAck` |
| `batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/stage/CompensateDispatchStepTest.java` | `stage_returnsCompensate` |
| `batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/stage/CompleteDispatchStepTest.java` | `stage_returnsComplete` |
| `batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/stage/DeliverDispatchStepTest.java` | `stage_returnsDispatch` |
| `batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/stage/PrepareDispatchStepTest.java` | `stage_returnsPrepare` |
| `batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/stage/RetryDispatchStepTest.java` | `stage_returnsRetry` |
| `batch-worker/export/src/test/java/io/github/pinpols/batch/worker/exports/infrastructure/ExportTaskExecutorTest.java` | `shouldReportExportTaskType_whenReadingTaskType` |
| `batch-worker/export/src/test/java/io/github/pinpols/batch/worker/exports/integration/BatchWorkerExportApplicationIntegrationTest.java` | `shouldLoadContext_whenApplicationStarts` |
| `batch-worker/export/src/test/java/io/github/pinpols/batch/worker/exports/integration/ExportSpiEquivalenceIntegrationTest.java` | `shouldRegisterExportTaskType_whenContextLoads` |
| `batch-worker/export/src/test/java/io/github/pinpols/batch/worker/exports/sql/SqlTemplateExportSqlValidatorTest.java` | `validate_ignoresParameterTextOutsideSqlParameters` |
| `batch-worker/export/src/test/java/io/github/pinpols/batch/worker/exports/stage/format/GenerateCursorCodecTest.java` | `roundTrip_preservesBoolean` |
| `batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/config/ImportScannerPropertiesTest.java` | `doneFileSuffix_defaultsToDotDone`、`doneFileNaming_defaultsToAppendFullName` |
| `batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/infrastructure/ImportTaskExecutorTest.java` | `shouldReportImportTaskType_whenReadingTaskType` |
| `batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/ValidationCoercionsTest.java` | `decimalValue_returnsNull_forNonNumericText` |
| `batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/integration/BatchWorkerImportApplicationIntegrationTest.java` | `shouldLoadContext_whenApplicationStarts` |
| `batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/integration/ImportSpiEquivalenceIntegrationTest.java` | `shouldRegisterImportTaskType_whenContextLoads` |
| `batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/stage/FeedbackStepTest.java` | `shouldReturnStageFeedback_whenStageAccessed` |
| `batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/stage/LoadStepTest.java` | `shouldReturnLoadStage` |
| `batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/stage/PreprocessRangeSliceTest.java` | `shouldSliceLosslessly_whenLastLineHasNoTrailingNewline`、`shouldSliceLosslessly_whenEmptyLinesPresent`、`shouldSliceLosslessly_whenCrlfLineEndings` |
| `batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/stage/PreprocessStreamEligibilityTest.java` | `shouldAllowDirectStreaming_whenTemplateMissing` |
| `batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/stage/ValidateStepTest.java` | `shouldReturnValidateStage` |
| `batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/cleanup/ProcessStagingPartitionMaintenanceTest.java` | `shouldKeepRowsUntilDeletionIsEnabled` |
| `batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/infrastructure/ProcessTaskExecutorTest.java` | `shouldReportProcessTaskType_whenTaskTypeQueried` |
| `batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/infrastructure/verifier/ProcessPublishedCountVerifierTest.java` | `shouldPass_whenPublishedCountPositive`、`shouldPass_whenPublishedCountIsNumericString` |
| `batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/integration/ProcessSpiEquivalenceIntegrationTest.java` | `shouldContainProcessTaskType_whenListingRegisteredTypes` |
| `batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/integration/ProcessStagingCleanupYamlIntegrationTest.java` | `shouldDisableStagingOrphanCleaner_whenTestProfileActive` |
| `batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/stage/CommitStepTest.java` | `shouldReturnStageCommit_whenStageAccessed` |
| `batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/stage/FeedbackStepTest.java` | `shouldReturnStageFeedback_whenStageAccessed` |
| `examples/task-spi-plugin/sftp-push-executor/src/test/java/io/github/pinpols/batch/ext/sftp/SftpPushTaskExecutorTest.java` | `shouldReturnSftpPushType_whenTaskTypeQueried`、`shouldDeclareNetAndDiskCapability_whenCapabilityQueried` |
| `sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/client/BatchPlatformClientConfigValidationTest.java` | `shouldAcceptAllDefaults` |
| `sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/client/TaskTypeDescriptorAssemblyTest.java` | `shouldReturnNullDescriptor_whenHandlerDoesNotDeclare` |
| `sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/client/WorkerFingerprintTest.java` | `shouldReturnCurrentJvmPid_whenReadingProcessId` |
| `sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcherClaimJitterTest.java` | `shouldReturnZero_whenBaseDelayIsZero` |
| `sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/handler/builtin/support/DelimitedCodecTest.java` | `shouldParsePlainFields`、`shouldParseQuotedFieldContainingDelimiter`、`shouldYieldEmptyTrailingField`、`shouldSupportCustomDelimiter` |
| `sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/idempotent/IdempotencyKeyResolverTest.java` | `shouldReturnLiteral_whenNoPlaceholder` |
| `sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/task/SdkTaskResultTest.java` | `shouldReturnEmptyOutput_whenOutputAbsent` |
| `sdk/typescript/tests/decide-backpressure.test.ts` | `decideBackpressure default paused=false: no resume in band` |
| `sdk/typescript/tests/handler.test.ts` | `handler: taskFailure default errorCode = EXECUTION_FAILED` |
| `sdk/typescript/tests/kafka-offset-policy.test.ts` | `loweredCeiling: first withhold sets the ceiling to the record offset`、`commitBlockedByWithheld: no ceiling → never blocked`、`resolveFromBeginning: defaults to false (latest), parity with the 4 other SDKs`、`resolveFromBeginning: an explicit value always wins` |
| `sdk/typescript/tests/shared-constants-parity.test.ts` | `schema_versions_supported parity`、`worker_runtime_states parity`、`sensitive_keywords parity`、`task_statuses parity`、`report_error_codes parity` |

### batch-console

| 测试文件 | 快照中的候选测试 |
|---|---|
| `e2e/alert-outbox-ops.spec.ts` | `重试 tab 默认激活` |
| `e2e/c-plus-coverage.spec.ts` | `actuator/health 暴露 200` |
| `e2e/excel-import.spec.ts` | `下载配置包模板按钮可见`、`导出当前配置包按钮可见` |
| `e2e/file-ops.spec.ts` | `默认展示运行列表 tab` |
| `e2e/flows/01-trigger-instance.spec.ts` | `1. 查到一个可触发的 jobDefinition` |
| `e2e/notification-management.spec.ts` | `旧 webhooks 路由重定向` |
| `e2e/ops-diagnostic.spec.ts` | `集群诊断区块存在` |
| `e2e/ops-shard-catalog-crud.spec.ts` | `页可达 + 新建入口可见` |
| `e2e/ops-tenant-placement-crud.spec.ts` | `页可达 + 新建入口可见` |
| `e2e/reports-ops.spec.ts` | `展示所有报表卡片`、`header 显示 tenantId 标签` |
| `e2e/smoke.spec.ts` | `根路径重定向到运营概览` |
| `src/api/adapters.test.ts` | `returns correct slice for page 2`、`returns partial last page` |
| `src/api/atomicTaskTypes.test.ts` | `returns empty array when payload is empty` |
| `src/api/customTaskTypes.test.ts` | `returns null for invalid JSON` |
| `src/api/meta.test.ts` | `payload 不是对象时返回 {}(防御后端异常)` |
| `src/api/taskHeartbeat.test.ts` | `reads percent in 0-100 range`、`treats 0-1 floats as ratios and scales up`、`rounds to 0.1`、`prefers percent over progress when both present` |
| `src/api/workerFingerprint.test.ts` | `listWorkerFingerprints normalizes null response to empty array`、`getWorkerFingerprintSummary normalizes null response to empty array` |
| `src/components/common/EmptyState.test.ts` | `没有操作时不渲染空的操作容器` |
| `src/components/common/SensitiveFieldAlert.test.ts` | `handles invalid JSON string gracefully (no render)` |
| `src/components/common/docsRegistry.test.ts` | `docKey 全为 kebab-case(避免 camelCase / snake_case 混用)`、`未知 docKey 返回空字符串(避免 iframe 加载未定义页)` |
| `src/components/common/statusTagResolve.test.ts` | `falls back to fallback arg when value empty`、`falls back to em-dash when value empty and no fallback`、`returns info for unknown values when no local label override` |
| `src/composables/useFormValidate.test.ts` | `required 默认 blur`、`formRef 未挂载时 validate() 返回 false 不抛` |
| `src/composables/useOnboardingTour.test.ts` | `首次访问且无完成标记时展示引导`、`已完成引导时不再展示` |
| `src/constants/theme.test.ts` | `defaults to dark when no user preference is stored`、`preserves stored user preference`、`migrates legacy system default to dark once` |
| `src/directives/permission.test.ts` | `keeps element when permissions list empty` |
| `src/stores/auth.test.ts` | `isLoggedIn is false when no token`、`hasPermission returns false when no userInfo` |
| `src/stores/tabs.test.ts` | `returns -1 for unknown key` |
| `src/utils/aiMessagePresentation.test.ts` | `keeps approved answer content` |
| `src/utils/datetime.test.ts` | `handles epoch ms number`、`returns raw string for invalid date` |
| `src/utils/errorCatalog.test.ts` | `falls back to network`、`returns undefined when nothing matches` |
| `src/utils/formatAiUsd.test.ts` | `uses cents for zero and ordinary amounts` |
| `src/utils/iconResolve.test.ts` | `已知图标名:返回对应 EP 图标 Component`、`未知图标名:返回 fallback(Menu)而非 undefined,保菜单可渲染` |
| `src/utils/idempotency.test.ts` | `returns a non-empty string` |
| `src/utils/lastApiMeta.test.ts` | `stores a valid meta object`、`sets value to null when called with undefined`、`sets value to null when called with null` |
| `src/utils/logRedact.test.ts` | `sanitizeParams returns undefined for null/undefined` |
| `src/utils/logger.test.ts` | `logClick writes type=click`、`keeps legitimate error messages`、`filters by keyword in name`、`filters by keyword in props`、`returns all when no filter` |
| `src/utils/metaEnumPick.test.ts` | `returns first matching non-empty group`、`skips missing keys and returns first hit`、`skips empty arrays`、`returns [] when no key matches`、`returns [] for undefined input`、`returns [] for null input`、`returns [] when called with no keys` |
| `src/utils/passwordGenerator.test.ts` | `throws when length < 4`、`excludes ambiguous characters (0/O/I/l/1)`、`extra point at length ≥ 12`、`caps at 4 (extreme)` |
| `src/utils/pipelineStepSummary.test.ts` | `PARSE 步骤取 parsedCount`、`GENERATE 取 recordCount`、`未映射 stage(如 PREPARE)且 summary 含计数 → 回退优先级列表`、`已解析对象入参(非字符串)也支持`、`计数字段非数字(脏数据)→ 跳过不误显` |
| `src/utils/queryFormOptions.test.ts` | `returns sorted unique non-empty values`、`returns empty array for empty input`、`filters out undefined picks` |
| `src/utils/resourceCodeBuilder.test.ts` | `uppercases lower`、`keeps digits + underscores`、`empty → EMPTY`、`< 3 chars → LENGTH`、`> 30 chars → LENGTH`、`digit-leading rejected (BIZ_FORMAT requires letter first)`、`exact 30 chars boundary OK`、`accepts v-prefixed`、`accepts bare digits`、`builds without version`、`builds with version`、`normalizes biz input on the fly`、`drops bad version silently`、`empty existing → empty` |
| `src/utils/safeHtml.test.ts` | `returns the sanitized string unchanged` |
| `src/utils/safeJson.test.ts` | `returns empty string unchanged`、`falls back to original string on malformed JSON` |
| `src/utils/sensitiveKeys.test.ts` | `returns empty for clean object`、`detects direct sensitive keys`、`parses JSON string input` |
| `src/utils/structuredSummary.test.ts` | `truncates plain text summaries` |
| `src/utils/text.test.ts` | `keeps short values unchanged`、`truncates CJK text by characters rather than bytes`、`does not split a surrogate pair` |
| `src/utils/userErrorMessage.test.ts` | `preserves non-API client-side validation errors` |
| `src/views/monitor/canReplayFailedWorkflowJobNode.test.ts` | `allows only a failed JOB node with a linked instance in a failed Workflow run` |
| `src/views/monitor/workflowNodeSkipReason.test.ts` | `marks old skipped rows without a recorded reason`、`does not show skip copy for other node states` |
| `src/views/scheduler/batchDayPresentation.test.ts` | `builds leap-year month boundaries in local time` |
| `src/views/system/atomicRuntimeModel.test.ts` | `不可用时即使混有executor字段也不展示为真实状态` |
| `src/views/worker/workerActions.test.ts` | `在线节点只展示下线和接管动作`、`排空中节点不再展示排空和预热`、`离线节点只允许预热` |
| `src/views/workflow/designer/codec/mermaidExporter.test.ts` | `empty snapshot → header only` |

## 2026-11-01：当前 public Map 候选逐项台账

本表固定 11 月 1 日清单的 60 个返回类型词法候选及读取时源文件摘要；行号可能漂移，重载按行区分。已核查结论对应前文所列源码和调用边界；待核查不代表违规。输入参数、隐式 public 接口等不在此分母内。

| 文件和行 | 方法 | SHA-256 前 12 位 | 结论 |
|---|---|---|---|
| [BatchKafkaProducerSupport.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/config/BatchKafkaProducerSupport.java#L36) | `stringProducerConfig` | `fd7f26eb0d1e` | 保留：传递任意 Kafka client 配置并供调用方覆盖 |
| [RunModeSupport.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/context/RunModeSupport.java#L17) | `copyWithDefault` | `358ea291406c` | 保留：复制不定业务参数并规范化模式 |
| [BatchRuntimeStatusEndpoint.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/diagnostics/BatchRuntimeStatusEndpoint.java#L51) | `status` | `3228b82711cf` | 保留：Actuator 现有 wire 及条件出现的诊断字段 |
| [CursorCodec.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/page/CursorCodec.java#L45) | `decode` | `e92c60c35010` | 保留：不同分页调用方的动态排序键 |
| [JsonUtils.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/utils/JsonUtils.java#L91) | `toMap` | `83fb7fc5ea33` | 保留：对任意对象提供通用 JSON 转换 |
| [SecretMasking.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/utils/SecretMasking.java#L65) | `maskSensitiveKeys` | `ebeb1927bf06` | 保留：递归处理不定字段，重载分别提供自定义敏感键 |
| [SecretMasking.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/utils/SecretMasking.java#L71) | `maskSensitiveKeys` | `ebeb1927bf06` | 保留：递归处理不定字段，重载分别提供自定义敏感键 |
| [SensitiveEvidenceMasking.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/utils/SensitiveEvidenceMasking.java#L22) | `maskMetadata` | `c1bbb6bf6836` | 保留：血缘响应中的不定 metadata 脱敏 |
| [FileChannelConfigQueryView.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/view/FileChannelConfigQueryView.java#L26) | `toMap` | `acd8910bc2af` | 保留：Excel 导入旧值合并兼容键和缺省值 |
| [FileTemplateConfigQueryView.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/file/view/FileTemplateConfigQueryView.java#L61) | `toMap` | `109914737586` | 保留：Excel 导入旧值合并兼容键和缺省值 |
| [ConsoleJobConfigViews.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/job/view/ConsoleJobConfigViews.java#L42) | `toMap` | `d2ac11479f8b` | 保留：批量窗口 Excel 行按旧键和 null 语义导出 |
| [ConsoleJobOpsSupport.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/ops/infrastructure/ConsoleJobOpsSupport.java#L525) | `parsePayload` | `e4ddc76a4f26` | 保留：接收调用方可变业务参数 JSON |
| [ResourceQueueRow.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/ops/view/ResourceQueueRow.java#L43) | `toMap` | `524267332ca1` | 保留：Excel 列读取及 null 单元格契约 |
| [ConfigPackageExcelSchema.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageExcelSchema.java#L133) | `toExportRow` | `08da296bda0a` | 保留：日历 Excel 导出行 |
| [ConsoleMapSupport.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/support/web/ConsoleMapSupport.java#L18) | `mapOf` | `8e40cd9b97c4` | 保留：组装多类配置审计 JSON 的动态 detail |
| [PartitionDispatchService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/PartitionDispatchService.java#L55) | `effectiveParams` | `395ed8f676da` | 保留：T1 参数传递至 T2 并构建动态任务载荷 |
| [WorkflowNodePayloadBuilder.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowNodePayloadBuilder.java#L299) | `nodeOutput` | `0868cea66d76` | 保留：工作流表达式读取各节点动态输出 |
| [WorkflowNodePayloadBuilder.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowNodePayloadBuilder.java#L304) | `workflowRunFields` | `0868cea66d76` | 保留：工作流表达式上下文的可选字段 |
| [WorkflowNodePayloadBuilder.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowNodePayloadBuilder.java#L333) | `nodeOutput` | `0868cea66d76` | 保留：工作流表达式读取各节点动态输出 |
| [WorkflowNodePayloadBuilder.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowNodePayloadBuilder.java#L338) | `workflowRunFields` | `0868cea66d76` | 保留：工作流表达式上下文的可选字段 |
| [WorkflowNodePayloadBuilder.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowNodePayloadBuilder.java#L362) | `parsePayloadMap` | `0868cea66d76` | 保留：Workflow 作业原始动态载荷解析 |
| [WorkflowRunExecutionSnapshot.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowRunExecutionSnapshot.java#L584) | `dispatchSecretBindings` | `a3fb85b85ea6` | 保留：按节点返回渠道编码索引的冻结密钥绑定 |
| [FileGovernanceViews.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/view/FileGovernanceViews.java#L99) | `toCacheValues` | `30a1cc16740a` | 保留：序列化并消费既有延迟样本缓存结构 |
| [FileGovernanceViews.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/view/FileGovernanceViews.java#L119) | `toCacheValues` | `30a1cc16740a` | 保留：序列化并消费既有延迟样本缓存结构 |
| [SensorFileArrivalView.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/view/SensorFileArrivalView.java#L18) | `toProbeOutput` | `7227d388aafe` | 保留：Sensor 命中输出供动态表达式消费 |
| [MapJsonbTypeHandler.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/mybatis/MapJsonbTypeHandler.java#L37) | `getNullableResult` | `230a48c9eec6` | 保留：MyBatis 不同结果来源的动态 JSONB 重载 |
| [MapJsonbTypeHandler.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/mybatis/MapJsonbTypeHandler.java#L43) | `getNullableResult` | `230a48c9eec6` | 保留：MyBatis 不同结果来源的动态 JSONB 重载 |
| [MapJsonbTypeHandler.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/mybatis/MapJsonbTypeHandler.java#L48) | `getNullableResult` | `230a48c9eec6` | 保留：MyBatis 不同结果来源的动态 JSONB 重载 |
| [DispatchChannelSnapshotService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DispatchChannelSnapshotService.java#L41) | `pin` | `c084f72aa7df` | 保留：在业务参数中冻结动态渠道快照 |
| [DispatchChannelSnapshotService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DispatchChannelSnapshotService.java#L166) | `pinWorkflowReplay` | `c084f72aa7df` | 保留：在重放业务参数中应用批准的渠道快照和绑定 |
| [DispatchSecretSnapshotResolver.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DispatchSecretSnapshotResolver.java#L34) | `pin` | `4587d4a3e29d` | 保留：在业务参数中按渠道冻结密钥版本 |
| [FileTemplateSnapshotService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/FileTemplateSnapshotService.java#L41) | `pin` | `4fcc703e3415` | 保留：业务参数中冻结模板快照 |
| [FileTemplateSnapshotService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/FileTemplateSnapshotService.java#L90) | `pinWorkflowReplay` | `4fcc703e3415` | 保留：重放业务参数中应用批准的模板快照 |
| [PlatformFileRecordRepository.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/PlatformFileRecordRepository.java#L31) | `loadFileRecord` | `cc2ab89dc58e` | 保留：具名投影转成共享阶段上下文和插件既有键值契约 |
| [PlatformFileRecordRepository.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/PlatformFileRecordRepository.java#L48) | `loadFileRecordByStoragePath` | `cc2ab89dc58e` | 保留：共享文件记录格式供导入与导出恢复读取 |
| [PlatformFileTemplateRepository.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/PlatformFileTemplateRepository.java#L18) | `loadLatestTemplateConfig` | `6704c9fc9f92` | 保留：模板投影供解析器和插件消费现有配置键 |
| [PlatformPipelineRunRepository.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/PlatformPipelineRunRepository.java#L102) | `loadLatestSucceededStepOutputSummary` | `d68ebabd9f2a` | 保留：不同阶段的 JSON 输出供跳过恢复透传 |
| [PlatformFileRecordView.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/mapper/PlatformFileRecordView.java#L40) | `toPluginValues` | `853b28c9f13b` | 保留：向现有 Worker 文件插件边界转换 snake_case 字段 |
| [PlatformFileTemplateConfigView.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/mapper/PlatformFileTemplateConfigView.java#L61) | `toPluginValues` | `764b9f0b5bd7` | 保留：向现有 Worker 模板插件边界转换字段 |
| [FrozenFileTemplateResolver.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/FrozenFileTemplateResolver.java#L31) | `resolve` | `ea17a46960ae` | 保留：冻结与实时来源统一为动态模板配置 |
| [ChannelConfigMerge.java](../../batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/ChannelConfigMerge.java#L106) | `merge` | `a02cfb20e29e` | 保留：合并列和渠道 JSON 并保留插件配置契约 |
| [FileDispatchRepository.java](../../batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/FileDispatchRepository.java#L36) | `loadFile` | `938069df6a9c` | 保留：分发准备将文件字段放入阶段插件上下文 |
| [FileDispatchRepository.java](../../batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/FileDispatchRepository.java#L44) | `loadFile` | `938069df6a9c` | 保留：分发准备将文件字段放入阶段插件上下文 |
| [FileDispatchRepository.java](../../batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/FileDispatchRepository.java#L52) | `loadChannel` | `938069df6a9c` | 保留：渠道配置用于合并并供派发和回执消费 |
| [DispatchChannelConfigView.java](../../batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/mapper/DispatchChannelConfigView.java#L25) | `toPluginValues` | `76c13107e046` | 保留：渠道投影用于派发和回执插件配置 |
| [ExportConfigValueSupport.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/config/ExportConfigValueSupport.java#L19) | `toMap` | `8b228a22660e` | 保留：导出模板的动态 JSON 或 Map 规范化 |
| [GenericJdbcMappedExportDataPlugin.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/plugin/GenericJdbcMappedExportDataPlugin.java#L83) | `loadBatch` | `3177938e44cc` | 保留：插件查询按模板选择列并返回动态批次行 |
| [SqlTemplateExportDataPlugin.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/plugin/SqlTemplateExportDataPlugin.java#L91) | `loadBatch` | `96c18ed23cad` | 保留：实现导出插件契约并传入生成阶段 |
| [ValidationConfigSupport.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/ValidationConfigSupport.java#L20) | `toMap` | `c278f4d42fc4` | 保留：读取 Map 或 JSON 的动态校验配置 |
| [ValidationConfigSupport.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/ValidationConfigSupport.java#L42) | `firstMap` | `c278f4d42fc4` | 保留：按候选键提取动态规则 |
| [ValidationConfigSupport.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/ValidationConfigSupport.java#L52) | `payloadToMap` | `c278f4d42fc4` | 保留：客户载荷供按字段名的规则求值使用 |
| [ValidationRuleSetMerger.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/ValidationRuleSetMerger.java#L32) | `merge` | `e31799c29ad1` | 保留：合并动态字段映射和显式规则集 |
| [ParseSupport.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/ParseSupport.java#L62) | `parseHints` | `4e51523686f6` | 保留：不同格式解析器读取各自动态提示 |
| [ParseSupport.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/format/ParseSupport.java#L76) | `readJsonObject` | `4e51523686f6` | 保留：Map 和 JSON 文本均可传入的配置边界 |
| [TaskDispatcher.java](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcher.java#L618) | `progressSnapshot` | `989784d9a66d` | 保留：续租请求携带 handler 自定义进度快照 |
| [SdkRowResult.java](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/SdkRowResult.java#L63) | `toOutput` | `69342074d3c4` | 保留：转为任务结果 output 的既有字段契约 |
| [SdkTypedParameters.java](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/handler/typed/SdkTypedParameters.java#L71) | `toOutputMap` | `d5aa6605663a` | 保留：将业务泛型结果转为可变结构的 output |
| [ProgressReporter.java](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/ProgressReporter.java#L29) | `latest` | `84274e0c30ed` | 保留：跨线程可扩展的进度快照 |
| [SdkTaskStoppedException.java](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/task/SdkTaskStoppedException.java#L24) | `breakPosition` | `46afd4757d0e` | 保留：携带 handler 定义的续跑断点并进入取消结果 |
| [FakeBatchPlatform.java](../../sdk/java/testkit/src/main/java/io/github/pinpols/batch/sdk/testkit/FakeBatchPlatform.java#L233) | `registrations` | `2f58c33e8612` | 保留：记录接收到的注册 JSON 请求体供测试读取 |

## 2026-11-01：后端薄包装历史候选逐项台账

以下为 10 月 28 日 110 个词法候选，摘要为建表时当前文件内容。每个候选单独裁定，待核查不等于问题；源码变化后须重验。

| 文件 | 方法 | 当前 SHA-256 前 12 位 | 结论 |
|---|---|---|---|
| [TokenBucketRateLimiter.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/ratelimit/TokenBucketRateLimiter.java) | `configSupplierFor` | `0b7b8be09af8` | 保留：命名并缓存限流桶配置供应器 |
| [FilesystemObjectStore.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/storage/FilesystemObjectStore.java) | `syntheticEtag` | `25720c586bc2` | 保留：命名文件大小和时间生成的本地 ETag |
| [TenantConfigMatrixBuilder.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantConfigMatrixBuilder.java) | `first` | `ddaece8f2600` | 保留：空列表安全选取首项 |
| [SqlTemplateExportSqlValidator.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/sql/SqlTemplateExportSqlValidator.java) | `isParameterStart` | `edaa845aaf28` | 保留：命名 SQL 参数首字符规则 |
| [SqlTemplateExportSqlValidator.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/sql/SqlTemplateExportSqlValidator.java) | `isParameterPart` | `edaa845aaf28` | 保留：命名 SQL 参数后续字符规则 |
| [OkHttpOrchestratorExternalHttpTransport.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/http/OkHttpOrchestratorExternalHttpTransport.java) | `sameHost` | `5d7370669615` | 保留：DNS 回调中的地址规范化比较 |
| [DefaultWorkerRegistryService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/DefaultWorkerRegistryService.java) | `firstHeartbeat` | `c8c61cdfd7aa` | 保留：明确注册时服务端时间和忽略 Worker 时钟的约束 |
| [MapperStatusWriteConventionTest.java](../../batch-test-support/src/test/java/io/github/pinpols/batch/common/arch/MapperStatusWriteConventionTest.java) | `withoutComments` | `38399abfb7ac` | 保留：命名检查前 XML 注释剔除步骤 |
| [ImportRecordGovernanceService.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/ImportRecordGovernanceService.java) | `stringValue` | `0bd63406a1d5` | 已简化：使用标准库保持空值与字符串转换语义 |
| [TriggerStatusQueryFacade.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/TriggerStatusQueryFacade.java) | `unknown` | `2530a33ef0a9` | 保留：为未知触发状态构建具名结果 |
| [BatchDayReplayService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/BatchDayReplayService.java) | `failedStatuses` | `8a258c5cfe36` | 保留：命名失败重放范围 |
| [BatchDayReplayService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/BatchDayReplayService.java) | `isCancellable` | `8a258c5cfe36` | 保留：命名可取消状态业务判断 |
| [WorkflowRunReplaySnapshotResolver.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/replay/WorkflowRunReplaySnapshotResolver.java) | `readProtectedDispatchChannelSnapshots` | `443dca888f59` | 保留：区分渠道快照与模板快照的领域语义 |
| [DefaultWorkflowDagService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowDagService.java) | `stringValue` | `c71e37547f75` | 已简化：使用标准库保持空值与字符串转换语义 |
| [PreprocessStep.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/stage/PreprocessStep.java) | `hasTemplateConfigSnapshot` | `7f33ed33d5df` | 保留：命名模板快照存在条件 |
| [ConsoleLoginService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/support/ConsoleLoginService.java) | `invalidCredentials` | `9506606b6a60` | 保留：命名认证失败异常和错误键 |
| [WorkflowRunExecutionSnapshot.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowRunExecutionSnapshot.java) | `readProtectedDispatchChannelSnapshots` | `a3fb85b85ea6` | 保留：区分渠道快照与模板快照的领域语义 |
| [FileGovernanceArrivalGroupGuardTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceArrivalGroupGuardTest.java) | `bizDate` | `e119b8a9c69d` | 保留：测试行数据中的日期转换 |
| [DefaultConsoleJobDefinitionApplicationService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/job/infrastructure/DefaultConsoleJobDefinitionApplicationService.java) | `resolveSoftRuntimeSeconds` | `04b03cc0d7c2` | 保留：命名请求值与监控默认值的选择 |
| [TerminalNotificationDeliveryService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/service/TerminalNotificationDeliveryService.java) | `notification` | `5478eae0afdd` | 保留：具名构造通知目的地及其可空字段 |
| [AlertmanagerEmitPublisher.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/governance/AlertmanagerEmitPublisher.java) | `stripTrailingSlash` | `3b4f94447486` | 保留：命名地址末尾斜线规范化 |
| [DefaultConsoleAlertApplicationService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/infrastructure/DefaultConsoleAlertApplicationService.java) | `normalizeStatus` | `d19fb8ae1abe` | 保留：命名告警状态归一及缺省口径 |
| [StatefulBackendGuard.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/stateful/StatefulBackendGuard.java) | `matches` | `92cf8b7b847f` | 保留：命名后端及身份同时匹配的条件 |
| [DefaultWorkflowNodeDispatchService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowNodeDispatchService.java) | `isJobNode` | `2c2014fad378` | 保留：命名 JOB 分支判断 |
| [DefaultWorkflowNodeDispatchService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/DefaultWorkflowNodeDispatchService.java) | `isWaitNode` | `2c2014fad378` | 保留：命名 WAIT 分支判断 |
| [DefaultRetryGovernanceService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultRetryGovernanceService.java) | `stringValue` | `a3930e16154c` | 已简化：使用标准库保持空值与字符串转换语义 |
| [JsonFixtureContractTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/contract/JsonFixtureContractTest.java) | `fixturesDir` | `04eb34dcd58c` | 保留：命名 SDK 契约 fixture 路径 |
| [JsonFixtureContractTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/contract/JsonFixtureContractTest.java) | `openapiPath` | `04eb34dcd58c` | 保留：命名权威 OpenAPI 路径 |
| [ConsoleSessionRegistry.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/support/ConsoleSessionRegistry.java) | `sessionStateTtl` | `f0b6d998580c` | 保留：命名会话状态 TTL 及默认策略 |
| [FileGovernanceScheduler.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceScheduler.java) | `defaultText` | `8cd602fffdc5` | 保留：对空白配置使用指定回退值 |
| [CalendarBizDateResolver.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/CalendarBizDateResolver.java) | `resolveCutoffTime` | `3b4e5429a8c3` | 保留：命名日历截止时间默认策略 |
| [CalendarBizDateResolver.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/CalendarBizDateResolver.java) | `previousWorkday` | `3b4e5429a8c3` | 保留：明确向前滚动和诊断方向 |
| [CalendarBizDateResolver.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/service/CalendarBizDateResolver.java) | `nextWorkday` | `3b4e5429a8c3` | 保留：明确向后滚动和诊断方向 |
| [SmtpEmailDispatchChannelAdapterTest.java](../../batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/SmtpEmailDispatchChannelAdapterTest.java) | `port` | `0cba3e27f436` | 保留：封装 SMTP 测试服务器监听端口 |
| [ConsoleOidcAuthenticationSuccessHandler.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/rbac/ConsoleOidcAuthenticationSuccessHandler.java) | `isAscii` | `1341a4ec47e3` | 保留：命名 OIDC 身份安全字符判断 |
| [ConsoleRateLimitFilter.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/support/ratelimit/ConsoleRateLimitFilter.java) | `isExpensivePath` | `4f124920e7e6` | 保留：命名昂贵请求路径策略 |
| [BatchWindowGateTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/BatchWindowGateTest.java) | `mockWorkerSelector` | `e6c04f11f3ad` | 已简化：直接创建局部 mock，移除单次转发 |
| [CrossDayDependencyReconciler.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/CrossDayDependencyReconciler.java) | `loadWorkflowRun` | `c63b9fc30cf3` | 保留：命名跨日依赖读取 Workflow 入口 |
| [BatchWorkerSdkAutoConfigurationTest.java](../../sdk/java/spring/src/test/java/io/github/pinpols/batch/sdk/autoconfigure/BatchWorkerSdkAutoConfigurationTest.java) | `registeredHandlers` | `358446979ac4` | 保留：封装测试读取已注册处理器的类型转换 |
| [QuartzMisfireApplicationRestartIntegrationTest.java](../../batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/QuartzMisfireApplicationRestartIntegrationTest.java) | `jdbcUrlWithApplicationName` | `ec1fc3e5f3c2` | 保留：命名 JDBC 连接参数拼接规则 |
| [ImportDataQualityService.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/infrastructure/ImportDataQualityService.java) | `asString` | `e6aede729991` | 已简化：使用标准库保持空值与字符串转换语义 |
| [ConsolePushApprovalNotifier.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/support/ConsolePushApprovalNotifier.java) | `nullToDash` | `10c29a6435b9` | 保留：命名通知内容空白回退规则 |
| [ConsolePushApprovalNotifier.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/notification/support/ConsolePushApprovalNotifier.java) | `truncate` | `10c29a6435b9` | 保留：命名通知文本长度裁剪规则 |
| [DefaultFileGovernanceService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultFileGovernanceService.java) | `safeUrlQuery` | `e9e6f226ee68` | 保留：命名查询参数编码与空值策略 |
| [ConfigPackageExcelValidator.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageExcelValidator.java) | `resourceQueueExists` | `4f12b9b4cad2` | 保留：命名配置包引用校验的资源队列存在性 |
| [DefaultConsoleKafkaLagQueryService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/infrastructure/DefaultConsoleKafkaLagQueryService.java) | `cacheSegment` | `d9bcfe37b2b1` | 保留：命名缓存键空过滤值回退策略 |
| [WorkflowConditionEvaluator.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowConditionEvaluator.java) | `findAtomicOperator` | `1bc8f11251c2` | 已简化：调用点直接使用枚举方法 |
| [RequestSigner.java](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/internal/RequestSigner.java) | `upper` | `2c9cfc3848b3` | 保留：明确签名方法的空值与固定 Locale 规范化 |
| [WorkflowReplayJobDefinitionResolverTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/replay/WorkflowReplayJobDefinitionResolverTest.java) | `snapshot` | `38ca0d6362e9` | 保留：命名测试的工作流运行快照构造 |
| [TenantSchedulerSnapshotService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/scheduler/TenantSchedulerSnapshotService.java) | `emptyBacklog` | `2ddb3214b402` | 保留：命名空积压快照并隐藏多零构造参数 |
| [RequestSignatures.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/security/RequestSignatures.java) | `upper` | `14657130a62e` | 保留：明确签名方法的空值与固定 Locale 规范化 |
| [AtomicRuntimeStatusService.java](../../batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/runtime/AtomicRuntimeStatusService.java) | `isProdProfile` | `6676c3c02779` | 已简化：原调用处直接使用统一 profile 判断 |
| [FileGovernanceStorageMaintenance.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceStorageMaintenance.java) | `sanitizeTrace` | `7ae5b9ed527a` | 保留：命名清理任务标识字符清洗规则 |
| [DefaultWorkerSelector.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/DefaultWorkerSelector.java) | `currentLoadOrZero` | `1d00baca90b4` | 保留：命名 Worker 负载缺省值 |
| [PlatformHttpClientTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/internal/PlatformHttpClientTest.java) | `attemptedAddresses` | `e25ccd4239bd` | 保留：测试读取连接地址的不可变快照 |
| [ReadReplicaRoutingDataSource.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/config/ReadReplicaRoutingDataSource.java) | `observeReplicaConnection` | `23fc8c36bac3` | 保留：命名副本连接代理的类型边界 |
| [OrchestratorApprovalClient.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/shared/approval/OrchestratorApprovalClient.java) | `hasText` | `10126f07810d` | 保留：多个校验共享非空文本判断 |
| [OutboxEventKeyGenerator.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/engine/OutboxEventKeyGenerator.java) | `truncate` | `20402acd735d` | 保留：命名事件键最大长度约束 |
| [ImportIngressScanner.java](../../batch-worker/import/src/main/java/io/github/pinpols/batch/worker/imports/runtime/ImportIngressScanner.java) | `isManifestMode` | `4ef9b1cd5aa2` | 保留：命名导入扫描完成文件模式 |
| [ConsoleAuthenticationFilter.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/support/ConsoleAuthenticationFilter.java) | `isPublicAuthPath` | `2787ed9b541d` | 保留：命名认证过滤器的公开路径策略 |
| [JobLifecycleMetricsRecorder.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/observability/JobLifecycleMetricsRecorder.java) | `resolveErrorCode` | `fe6e9cf355a1` | 保留：命名指标错误码回退规则 |
| [RequestSignatureVerifier.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/security/RequestSignatureVerifier.java) | `hasValidSignatureShape` | `60659e510bc3` | 保留：命名签名结构校验 |
| [DefaultResourceScheduler.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/DefaultResourceScheduler.java) | `isWaitingRetry` | `986583cbaab2` | 保留：命名重试等待条件 |
| [DefaultDryRunPlanService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/dryrun/DefaultDryRunPlanService.java) | `defaultParamsHas` | `39e79ade78f1` | 保留：命名作业默认参数存在判断 |
| [ConsoleOidcIdentityService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/rbac/service/ConsoleOidcIdentityService.java) | `isAscii` | `75c944069896` | 保留：命名 OIDC 身份字符集判断 |
| [DefaultTaskExecutionWrapperTest.java](../../batch-worker/core/src/test/java/io/github/pinpols/batch/worker/core/infrastructure/DefaultTaskExecutionWrapperTest.java) | `actualReplayCapability` | `4addff71f8e6` | 保留：与声明能力对照的实际能力测试夹具 |
| [ConsoleClusterDiagnosticService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/application/ops/ConsoleClusterDiagnosticService.java) | `isTerminal` | `7b0f1c0c40ed` | 保留：命名诊断中的终态分类 |
| [TenantJobDefinitionApplySupport.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/TenantJobDefinitionApplySupport.java) | `resolveSoftRuntime` | `a54f233a1070` | 保留：命名请求与监控默认值的选择 |
| [QuotaReservationSupport.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/quota/QuotaReservationSupport.java) | `notApplicable` | `b1f4f3d57156` | 保留：具名构造不适用的预检结果 |
| [TaskConsumerRoutingPolicy.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/TaskConsumerRoutingPolicy.java) | `escapeRegex` | `92b5c8f68c12` | 保留：命名主题路由正则转义 |
| [SparkSubmitTaskExecutor.java](../../batch-worker/atomic/src/main/java/io/github/pinpols/batch/worker/atomic/spark/SparkSubmitTaskExecutor.java) | `isLocalPath` | `56aac06fd59f` | 保留：命名本地输出路径条件 |
| [LeaseRenewalSchedulerTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/scheduler/LeaseRenewalSchedulerTest.java) | `objectMap` | `33fe2db0a28c` | 保留：集中测试载荷类型转换并局部抑制泛型告警 |
| [ExportRegionResolver.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/region/ExportRegionResolver.java) | `firstNonBlank` | `4cc33bd536b3` | 保留：命名区域配置的别名优先级 |
| [ConsoleRealtimeEventHub.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/observability/realtime/ConsoleRealtimeEventHub.java) | `normalizeStream` | `47a4ff78b293` | 保留：命名实时流默认名称规则 |
| [ConsoleRealtimeEventHub.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/observability/realtime/ConsoleRealtimeEventHub.java) | `normalizeFilter` | `47a4ff78b293` | 保留：命名实时流筛选条件归一规则 |
| [PrepareStep.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/stage/PrepareStep.java) | `defaultText` | `1cf66dc8834b` | 保留：命名导出批次文本默认值 |
| [FileGovernanceRepository.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceRepository.java) | `defaultText` | `2ece931b7b11` | 保留：命名文件治理审计字段回退值 |
| [ActiveTaskLeaseRegistry.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/ActiveTaskLeaseRegistry.java) | `startExecution` | `f1e6261ab2c4` | 保留：封装租约执行状态的原子转换 |
| [ActiveTaskLeaseRegistry.java](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/ActiveTaskLeaseRegistry.java) | `lastConfirmedNanosValue` | `f1e6261ab2c4` | 保留：封装续租时间原子读取 |
| [S3ExportStorage.java](../../batch-worker/export/src/main/java/io/github/pinpols/batch/worker/exports/infrastructure/S3ExportStorage.java) | `digestToHex` | `e3ec201d9c9c` | 保留：命名校验摘要的十六进制表示 |
| [SharedConstantsParityTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/contract/SharedConstantsParityTest.java) | `yamlPath` | `52ccf4af50b3` | 保留：命名多语言共享契约的权威文件 |
| [NamespacedObjectStore.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/storage/NamespacedObjectStore.java) | `physicalMarker` | `f0ebb9a7f826` | 保留：命名分页游标到物理命名空间的转换 |
| [NamespacedObjectStore.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/storage/NamespacedObjectStore.java) | `logicalMarker` | `f0ebb9a7f826` | 保留：命名物理分页游标到逻辑命名空间的转换 |
| [ConsoleAiCostService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/audit/infrastructure/ai/ConsoleAiCostService.java) | `unpriced` | `399e48b0b5a1` | 保留：具名表示无定价的预留结果 |
| [QuartzMisfireRecoveryListener.java](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/infrastructure/QuartzMisfireRecoveryListener.java) | `isDedicatedRecoveryJob` | `366506667e70` | 保留：命名误触发恢复作业识别规则 |
| [SqlTransformComputeSpec.java](../../batch-worker/process/src/main/java/io/github/pinpols/batch/worker/processes/sql/SqlTransformComputeSpec.java) | `values` | `e023bd74b064` | 保留：命名按指定列提取源行值的过程 |
| [ResultVersionWriter.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/version/ResultVersionWriter.java) | `buildBusinessKey` | `b8868c529292` | 保留：命名结果版本业务身份构造 |
| [RedisOidcAuthorizationRequestRepository.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/rbac/RedisOidcAuthorizationRequestRepository.java) | `validBrowserBinding` | `c4cc6fb570f2` | 保留：命名浏览器绑定令牌格式验证 |
| [DefaultPartitionDispatchService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/DefaultPartitionDispatchService.java) | `buildContext` | `7b12fdfcc583` | 保留：封装关联派发上下文与分区的构造 |
| [DefaultPartitionDispatchService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/DefaultPartitionDispatchService.java) | `initialPartitionStatus` | `7b12fdfcc583` | 保留：命名分区初始状态决策 |
| [CapacityProfileServiceTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/capacity/CapacityProfileServiceTest.java) | `eqInt` | `d300e97aa1a6` | 保留：集中参数匹配类型转换以配合原有测试 |
| [DatabaseQuotaRuntimeStateService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/quota/DatabaseQuotaRuntimeStateService.java) | `normalizePolicy` | `52651883e3b4` | 已简化：原分支直接承接同一枚举转换结果 |
| [BatchTopics.java](../../batch-common/src/main/java/io/github/pinpols/batch/common/kafka/BatchTopics.java) | `sanitizeTopicSegment` | `46320967644d` | 保留：命名 Kafka 主题片段字符规范 |
| [DefaultSchedulePlanBuilder.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/plan/DefaultSchedulePlanBuilder.java) | `clampMaxPartitionCount` | `d114c375582d` | 保留：命名分区数量上限及缺省规则 |
| [FakeBatchPlatform.java](../../sdk/java/testkit/src/main/java/io/github/pinpols/batch/sdk/testkit/FakeBatchPlatform.java) | `asMap` | `2f58c33e8612` | 保留：测试平台解析可变报文的安全快照 |
| [OrchestratorConfigInvalidationSubscriber.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/redis/OrchestratorConfigInvalidationSubscriber.java) | `eventKey` | `acd4a64f4ba0` | 保留：命名缓存失效事件的身份键 |
| [SqlAtomicHandlerTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/handler/atomic/SqlAtomicHandlerTest.java) | `ctxNoSql` | `763f963534e9` | 保留：命名无 SQL 测试上下文构造 |
| [KafkaConsumerAuthFailureTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/dispatcher/KafkaConsumerAuthFailureTest.java) | `mockConsumer` | `72974af1a7d7` | 保留：局部封装泛型 mock 的 unchecked 警告 |
| [LoadStepTest.java](../../batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/stage/LoadStepTest.java) | `mockPayload` | `84ff83f5eec5` | 已简化：直接创建局部 mock，移除单次转发 |
| [OkHttpOrchestratorExternalHttpTransportTest.java](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/http/OkHttpOrchestratorExternalHttpTransportTest.java) | `selections` | `3a4cd12bec5b` | 保留：封装测试代理选择计数器读取 |
| [DefaultCompensationService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultCompensationService.java) | `firstText` | `a35391dbebc0` | 保留：命名重跑批次号优先级回退 |
| [LaunchBatchDayService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/LaunchBatchDayService.java) | `isMissingLaunchContext` | `36983152976c` | 保留：命名启动上下文缺失判断 |
| [LaunchBatchDayService.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/service/LaunchBatchDayService.java) | `isIncompleteBatchDay` | `36983152976c` | 保留：命名批次日身份缺失判断 |
| [DefaultTenantConfigPackageExcelService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultTenantConfigPackageExcelService.java) | `requiredHeaders` | `fba6a4b428ec` | 已简化：调用点直接使用原方法 |
| [DefaultTenantConfigPackageExcelService.java](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/config/DefaultTenantConfigPackageExcelService.java) | `fileNameOrDefault` | `fba6a4b428ec` | 保留：命名配置包上传文件名回退 |
| [FileGovernanceLatencyMetrics.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/file/FileGovernanceLatencyMetrics.java) | `text` | `1ef9239b20c4` | 已简化：使用标准库保持空值与字符串转换语义 |
| [ApiKeyVerifier.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/auth/ApiKeyVerifier.java) | `verifyCacheKey` | `edc0680a3842` | 保留：命名验证缓存的密钥摘要及租户身份 |
| [TaskDispatcherTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcherTest.java) | `objectMap` | `1d1b6c615f57` | 保留：集中测试载荷类型转换并局部抑制泛型告警 |
| [JsonFixtureRequestSideContractTest.java](../../sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/contract/JsonFixtureRequestSideContractTest.java) | `fixturesDir` | `9b317fff942d` | 保留：命名 SDK 请求契约 fixture 路径 |
| [GlobalJobAdmissionGuard.java](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/scheduler/GlobalJobAdmissionGuard.java) | `hasHardCap` | `6e45da3ec0e8` | 保留：命名全局并发上限是否启用 |

## 2026-11-01：短断言候选逐项处置台账

固定 10 月 30 日的 321 项短断言语法候选；每项的源文件摘要取建表时当前内容，均已逐项给出处置结论。候选短不意味着无效或应删除。路径分别相对后端或前端仓库。

| 编号 | 仓库 / 文件 | 测试名称 | 当前文件 SHA-256 前 12 位 | 结论 |
|---|---|---|---|---|
| B001 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/page/CursorCodecTest.java` | `shouldReturnNull_whenCursorIsEmpty` | `5e376d3789ce` | 保留：null 与空排序键编码为无游标 |
| B002 | `file-batch-system/batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/workflow/web/support/WorkflowMermaidRendererTest.java` | `shouldReturnHeaderOnly_whenDetailMissing` | `7d31e8a0febc` | 保留：空 Workflow 详情的图表头输出 |
| B003 | `file-batch-system/sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/idempotent/IdempotencyKeyResolverTest.java` | `shouldReturnLiteral_whenNoPlaceholder` | `f0954dc6697b` | 保留：无占位符幂等键原样返回 |
| B004 | `file-batch-system/sdk/typescript/tests/handler.test.ts` | `handler: taskFailure default errorCode = EXECUTION_FAILED` | `b6ea7fc5c20d` | 已增强：独立断言默认错误码协议字面量 |
| B005 | `file-batch-system/batch-console-api/src/test/java/io/github/pinpols/batch/console/integration/ReadReplicaHappyPathIntegrationTest.java` | `shouldRouteToReplica_whenTransactionReadOnly` | `a54f2e61b6aa` | 保留：真实只读事务路由副本，与主库写事务不同 |
| B006 | `file-batch-system/batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/stage/RetryDispatchStepTest.java` | `stage_returnsRetry` | `81d7bfa15a17` | 保留：分发重试阶段的标识路由契约 |
| B007 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/DefaultWorkerDrainGovernanceServiceTest.java` | `shouldThrowWhenTenantIdIsBlankOnStartDrain` | `6a6890f9370a` | 保留：开始排空时空租户拒绝 |
| B008 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/DefaultWorkerDrainGovernanceServiceTest.java` | `shouldThrowWhenWorkerCodeIsBlankOnStartDrain` | `6a6890f9370a` | 保留：开始排空时空 Worker 编码拒绝 |
| B009 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/DefaultWorkerDrainGovernanceServiceTest.java` | `shouldThrowWhenTenantIdIsBlankOnForceOffline` | `6a6890f9370a` | 保留：强制下线时空租户拒绝 |
| B010 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/DefaultWorkerDrainGovernanceServiceTest.java` | `shouldThrowWhenWorkerCodeIsBlankOnListClaimedTasks` | `6a6890f9370a` | 保留：查询认领任务时空 Worker 编码拒绝 |
| B011 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/DefaultWorkerDrainGovernanceServiceTest.java` | `warmup_throwsWhenTenantBlank` | `6a6890f9370a` | 保留：预热时空租户拒绝 |
| B012 | `file-batch-system/sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/handler/builtin/support/DelimitedCodecTest.java` | `shouldParsePlainFields` | `6e81291668c1` | 保留：分隔、引号、空尾或自定义分隔符各有边界 |
| B013 | `file-batch-system/sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/handler/builtin/support/DelimitedCodecTest.java` | `shouldParseQuotedFieldContainingDelimiter` | `6e81291668c1` | 保留：分隔、引号、空尾或自定义分隔符各有边界 |
| B014 | `file-batch-system/sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/handler/builtin/support/DelimitedCodecTest.java` | `shouldYieldEmptyTrailingField` | `6e81291668c1` | 保留：分隔、引号、空尾或自定义分隔符各有边界 |
| B015 | `file-batch-system/sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/handler/builtin/support/DelimitedCodecTest.java` | `shouldSupportCustomDelimiter` | `6e81291668c1` | 保留：分隔、引号、空尾或自定义分隔符各有边界 |
| B016 | `file-batch-system/batch-worker/core/src/test/java/io/github/pinpols/batch/worker/core/infrastructure/HttpTaskReportBatchTest.java` | `reconcilesEachResponseIdentity` | `3fa12c4bae0c` | 保留：参数化覆盖批量响应乱序、外来、重复、缺失与格式异常 |
| B017 | `file-batch-system/batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/infrastructure/ProcessTaskExecutorTest.java` | `shouldReportProcessTaskType_whenTaskTypeQueried` | `8ef9eee25d7f` | 保留：PROCESS 执行器注册类型 |
| B018 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/RetryExecutionClosureIntegrationTest.java` | `shouldRejectRetryWhenDryRunPayloadUnsafe` | `4ae0d6d3989a` | 保留：真实数据库演练任务非法载荷多种边界及不变状态 |
| B019 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/RetryExecutionClosureIntegrationTest.java` | `shouldRejectRetryWhenFormalPayloadClaimsDryRun` | `4ae0d6d3989a` | 保留：正式任务被错误标为演练的相反方向 |
| B020 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/RetryExecutionClosureIntegrationTest.java` | `shouldRollbackFileRedispatchWhenParentIsPaused` | `4ae0d6d3989a` | 保留：父作业暂停导致重派失败及回滚 |
| B021 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/RetryExecutionClosureIntegrationTest.java` | `shouldRollbackFileRedispatchWhenTaskCannotReset` | `4ae0d6d3989a` | 保留：任务非失败状态阻止重派及回滚 |
| B022 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/RetryExecutionClosureIntegrationTest.java` | `shouldRejectFileRedispatchWhenExternalOutcomeIsUnknown` | `4ae0d6d3989a` | 保留：任务外部结果未知时的重派阻断 |
| B023 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/RetryExecutionClosureIntegrationTest.java` | `shouldRejectFileRedispatchWhenRecordIsNotKnownFailed` | `4ae0d6d3989a` | 保留：不同投递记录状态及未知结果的阻断 |
| B024 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/RetryExecutionClosureIntegrationTest.java` | `shouldRejectFileRedispatchWhenSingleTaskHasNoFileAssociation` | `4ae0d6d3989a` | 保留：任务缺失文件关联时阻断 |
| B025 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/RetryExecutionClosureIntegrationTest.java` | `shouldRejectRedispatchWhenPayloadTargetDiffers` | `4ae0d6d3989a` | 保留：普通和 bundle 文件或渠道目标不一致 |
| B026 | `file-batch-system/batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/stage/ValidateStepTest.java` | `shouldReturnValidateStage` | `bc6da7e6c7f2` | 保留：IMPORT VALIDATE 阶段标识 |
| B027 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/mq/BatchTopicResolverTest.java` | `shouldReturnNull_whenWorkerTypeUnsupported` | `124c230cc946` | 保留：未知 Worker 类型不生成主题 |
| B028 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/WorkflowConditionEvaluatorTest.java` | `shouldMatchEqualityWithDoubleEquals` | `9108c174484f` | 保留：双等号表达式的独立兼容语法 |
| B029 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/WorkflowConditionEvaluatorTest.java` | `shouldReturnFalseWhenPayloadKeyMissing` | `9108c174484f` | 保留：条件载荷缺失键时不匹配 |
| B030 | `file-batch-system/batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/stage/FeedbackStepTest.java` | `shouldReturnStageFeedback_whenStageAccessed` | `be24d611f98d` | 保留：PROCESS FEEDBACK 阶段标识 |
| B031 | `file-batch-system/sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/client/BatchPlatformClientConfigValidationTest.java` | `shouldAcceptAllDefaults` | `6c3504d3219f` | 保留：必需连接配置齐全时默认时限配置应有效 |
| B032 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowWaitSensorSpecValidatorTest.java` | `shouldIgnoreNonWaitNode` | `15e00f1b019f` | 保留：非 WAIT 节点不运行传感器校验 |
| B033 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowWaitSensorSpecValidatorTest.java` | `shouldRejectUnknownSensorType` | `15e00f1b019f` | 保留：未知 WAIT 类型返回专属错误码 |
| B034 | `file-batch-system/examples/task-spi-plugin/sftp-push-executor/src/test/java/io/github/pinpols/batch/ext/sftp/SftpPushTaskExecutorTest.java` | `shouldReturnSftpPushType_whenTaskTypeQueried` | `1bc0106e7f04` | 保留：扩展 SFTP 执行器注册类型 |
| B035 | `file-batch-system/examples/task-spi-plugin/sftp-push-executor/src/test/java/io/github/pinpols/batch/ext/sftp/SftpPushTaskExecutorTest.java` | `shouldDeclareNetAndDiskCapability_whenCapabilityQueried` | `1bc0106e7f04` | 保留：SFTP 所需网络与磁盘能力 |
| B036 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/StartupSelfCheckIntegrationTest.java` | `shouldExposeSelfCheckComponent_whenContextStarts` | `65710ad365cf` | 保留：单独定位启动自检 Bean 装配 |
| B037 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/validation/ValidBizDateValidatorTest.java` | `shouldTrimWhitespace_whenValidating` | `6874e286d40a` | 保留：业务日期去除前后空白后校验 |
| B038 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/DefaultCompensationServiceTest.java` | `shouldThrowWhenCommandIsNull` | `6812f4d2c821` | 保留：补偿请求为 null 时拒绝 |
| B039 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/ConcurrentTaskClaimIntegrationTest.java` | `assignWorker_onlyOneWorkerWins_acrossRepeatedLaunches` | `f60ad57eae7b` | 保留：真实并发认领在多个新批次重复验证唯一胜出 |
| B040 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/workflow/BizDateArithmeticTest.java` | `shouldReturnNull_whenNamedOffsetUnknown` | `1cfd3f1558d3` | 保留：未知命名日期偏移返回 null |
| B041 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/workflow/BizDateArithmeticTest.java` | `shouldReturnEmpty_whenRangeTagUnknown` | `1cfd3f1558d3` | 保留：未知日期范围标签返回空范围 |
| B042 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/workflow/BizDateArithmeticTest.java` | `shouldReturnEmpty_whenBizDayCountExceedsCap` | `1cfd3f1558d3` | 保留：超限营业日范围拒绝扩张 |
| B043 | `file-batch-system/batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/infrastructure/ImportTaskExecutorTest.java` | `shouldReportImportTaskType_whenReadingTaskType` | `044553edd04d` | 保留：IMPORT 执行器注册类型 |
| B044 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/AdminTestDataCleanupControllerIntegrationTest.java` | `shouldRejectAmbiguousInstanceId_whenNeighborIsInSameTenant` | `d63870c9ae14` | 保留：同租户重号时清理隔离 |
| B045 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/AdminTestDataCleanupControllerIntegrationTest.java` | `shouldRejectAmbiguousInstanceId_whenNeighborIsInAnotherTenant` | `d63870c9ae14` | 保留：跨租户重号时清理隔离 |
| B046 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/AdminTestDataCleanupControllerIntegrationTest.java` | `shouldRejectAmbiguousInstanceId_whenNeighborIsArchived` | `d63870c9ae14` | 保留：冷历史重号时清理隔离 |
| B047 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/i18n/MessagesPropertiesParityTest.java` | `shouldRejectDuplicateKeysAndBlankValues` | `cf23a9b3dd11` | 保留：独立中英文资源文件的重复键及空值约束 |
| B048 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/runtime/AtomicIsolationStartupCheckTest.java` | `noDualUseEnabled_noThrow` | `90a0df12cfd7` | 保留：无双用途执行器时自检放行 |
| B049 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/enums/RetryScheduleStatusTest.java` | `shouldContainSixValues` | `d3340bcde9d7` | 保留：枚举数量约束与各编码检查互补 |
| B050 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/enums/WorkflowJoinModeTest.java` | `fromCodeOrDefault_unknownCode_returnsAll` | `73c0de5bde7a` | 保留：未知 Join 模式的默认解析，与抛错入口不同 |
| B051 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/service/BatchObjectCryptoServiceTest.java` | `decryptNullBytes_shouldReturnNull` | `a7b5d81bfbd0` | 保留：null 解密输入返回 null |
| B052 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/service/BatchObjectCryptoServiceTest.java` | `decryptEmptyBytes_shouldReturnEmpty` | `a7b5d81bfbd0` | 保留：空数组解密输入返回空数组 |
| B053 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/service/BatchObjectCryptoServiceTest.java` | `resolveKeyRef_fallsBackToDefault_whenSecurityMapEmpty` | `a7b5d81bfbd0` | 保留：安全上下文为空时选择默认密钥引用 |
| B054 | `file-batch-system/batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/infrastructure/quality/ValidationCoercionsTest.java` | `decimalValue_returnsNull_forNonNumericText` | `a8dc48db19c1` | 保留：非数字输入的转换回退 |
| B055 | `file-batch-system/sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/task/SdkTaskResultTest.java` | `shouldReturnEmptyOutput_whenOutputAbsent` | `03a57a6c41d7` | 保留：SDK 空输出契约 |
| B056 | `file-batch-system/batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/BatchTriggerApplicationIntegrationTest.java` | `contextLoads_succeeds` | `c883e0501dd9` | 保留：触发器启动装配 Quartz 和迁移回调 |
| B057 | `file-batch-system/batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/BatchTriggerApplicationIntegrationTest.java` | `quartzScheduler_isStarted` | `c883e0501dd9` | 保留：Quartz 已启动状态与装配不同 |
| B058 | `file-batch-system/batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/stage/PreprocessRangeSliceTest.java` | `shouldSliceLosslessly_whenLastLineHasNoTrailingNewline` | `3947f1f65bf1` | 保留：末行、空行和 CRLF 为不同切片输入 |
| B059 | `file-batch-system/batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/stage/PreprocessRangeSliceTest.java` | `shouldSliceLosslessly_whenEmptyLinesPresent` | `3947f1f65bf1` | 保留：末行、空行和 CRLF 为不同切片输入 |
| B060 | `file-batch-system/batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/stage/PreprocessRangeSliceTest.java` | `shouldSliceLosslessly_whenCrlfLineEndings` | `3947f1f65bf1` | 保留：末行、空行和 CRLF 为不同切片输入 |
| B061 | `file-batch-system/batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/ops/service/WorkerCompatibilityEvaluatorTest.java` | `shouldReportOk_whenMajorOneFormatsVary` | `ca646f6a86a0` | 保留：参数化覆盖 SDK 主版本格式及前缀后缀 |
| B062 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/utils/OtelTraceContextTest.java` | `shouldReturnNull_whenNoActiveSpan` | `26c97c53ff6b` | 已增强：显式空上下文并恢复线程上下文 |
| B063 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/utils/OtelTraceContextTest.java` | `shouldFallBackToGeneratedId_whenNoActiveSpan` | `26c97c53ff6b` | 已增强：显式空上下文验证追踪号回退 |
| B064 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/validation/ValidResourceCodeTest.java` | `shouldReject_whenCodeNull` | `d82262411118` | 保留：null、空串和空白输入各自受验证约束 |
| B065 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/validation/ValidResourceCodeTest.java` | `shouldReject_whenCodeEmpty` | `d82262411118` | 保留：null、空串和空白输入各自受验证约束 |
| B066 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/validation/ValidResourceCodeTest.java` | `shouldReject_whenCodeBlank` | `d82262411118` | 保留：null、空串和空白输入各自受验证约束 |
| B067 | `file-batch-system/batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/cleanup/ProcessStagingPartitionMaintenanceTest.java` | `shouldKeepRowsUntilDeletionIsEnabled` | `074590cc5cc9` | 已增强：检查默认开关和无删除操作 |
| B068 | `file-batch-system/batch-worker/export/src/test/java/io/github/pinpols/batch/worker/exports/infrastructure/ExportTaskExecutorTest.java` | `shouldReportExportTaskType_whenReadingTaskType` | `193944a1840a` | 保留：EXPORT 执行器注册类型 |
| B069 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/config/HikariPgSessionSupportTest.java` | `shouldTruncateApplicationName_whenExceedingSixtyThreeChars` | `d99161ecff6b` | 已增强：校验截断内容及长度 |
| B070 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/scheduler/DryRunSchedulingPriorityTest.java` | `shouldKeepConfiguredPriority_whenFormalTask` | `a9638875be36` | 保留：正式任务显式优先级 |
| B071 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/scheduler/DryRunSchedulingPriorityTest.java` | `shouldKeepNullPriority_whenFormalTaskHasNoPriority` | `a9638875be36` | 保留：正式任务缺省优先级 |
| B072 | `file-batch-system/batch-console-api/src/test/java/io/github/pinpols/batch/console/config/ConsoleOidcPropertiesTest.java` | `shouldAllowEmptySettings_whenOidcIsDisabled` | `1f01b25a2311` | 保留：关闭 OIDC 时允许空配置 |
| B073 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/validation/ValidTenantIdTest.java` | `shouldReject_whenTenantIdNull` | `0c5b75e92a48` | 保留：必填与长度边界各自独立 |
| B074 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/validation/ValidTenantIdTest.java` | `shouldReject_whenTenantIdBlank` | `0c5b75e92a48` | 保留：必填与长度边界各自独立 |
| B075 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/validation/ValidTenantIdTest.java` | `shouldAccept_whenTenantIdAtMaxLength` | `0c5b75e92a48` | 保留：必填与长度边界各自独立 |
| B076 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/validation/ValidTenantIdTest.java` | `shouldReject_whenTenantIdExceedsMaxLength` | `0c5b75e92a48` | 保留：必填与长度边界各自独立 |
| B077 | `file-batch-system/batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/DispatchTaskExecutorTest.java` | `shouldReportDispatchTaskType_whenTaskTypeQueried` | `2706d7128038` | 保留：DISPATCH 执行器注册类型 |
| B078 | `file-batch-system/batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/rbac/support/captcha/TencentCaptchaVerifierTest.java` | `shouldReportTencent_whenQueryingProviderName` | `383d082af595` | 保留：腾讯验证码提供方标识 |
| B079 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/scheduler/quota/QuotaResetPolicyTest.java` | `shouldReturnNoneWhenValueIsNull` | `e92c1207c211` | 保留：空配额策略回退 NONE |
| B080 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/scheduler/quota/QuotaResetPolicyTest.java` | `shouldReturnNoneWhenValueIsBlank` | `e92c1207c211` | 保留：空白配额策略回退 NONE |
| B081 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/scheduler/quota/QuotaResetPolicyTest.java` | `shouldReturnNoneWhenValueIsUnknown` | `e92c1207c211` | 保留：未知配额策略回退 NONE |
| B082 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/scheduler/quota/QuotaResetPolicyTest.java` | `shouldReportNoneAsNotRuntimeManaged` | `e92c1207c211` | 保留：NONE 策略不由运行时托管 |
| B083 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/scheduler/quota/QuotaResetPolicyTest.java` | `shouldReportCalendarDayAsRuntimeManaged` | `e92c1207c211` | 保留：日历日配额策略由运行时托管 |
| B084 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/scheduler/quota/QuotaResetPolicyTest.java` | `shouldReportSlidingWindowAsRuntimeManaged` | `e92c1207c211` | 保留：滑动窗口配额策略由运行时托管 |
| B085 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/scheduler/DefaultPrioritySchedulerTest.java` | `shouldReturnDefaultPriorityFiveWhenRequestIsNull` | `1551e3019e08` | 保留：空调度请求的默认优先级 |
| B086 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/scheduler/DefaultPrioritySchedulerTest.java` | `shouldReturnMediumBandWhenPriorityIsNull` | `1551e3019e08` | 保留：空优先级的默认优先级档位 |
| B087 | `file-batch-system/batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/stage/LoadStepTest.java` | `shouldReturnLoadStage` | `84ff83f5eec5` | 保留：IMPORT LOAD 阶段标识 |
| B088 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/security/CredentialEnvResolverTest.java` | `returnsNull_whenNull` | `6767dc650826` | 保留：空凭据引用的解析结果 |
| B089 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/enums/CatchUpPolicyTypeTest.java` | `fromCodeOrDefault_unknownCode_returnsNone` | `f9104d818911` | 保留：未知补跑策略的默认解析 |
| B090 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/sql/SqlTaskExecutorTest.java` | `shouldReturnSingleStatement_whenNoSemicolon` | `07cfc21da3de` | 保留：无分号 SQL 不被错误拆分 |
| B091 | `file-batch-system/batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/DispatchManifestSupportTest.java` | `shouldKeepNull_whenBusinessDateMissing` | `3d3b1401d5a6` | 保留：无业务日的 manifest 字段仍为 null |
| B092 | `file-batch-system/batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/arch/PipelineWorkerAtomicIsolationOptInArchTest.java` | `shouldOptInIsolationCheck_whenImportWorker` | `4f2ee2f001ab` | 保留：IMPORT Worker 必须注册隔离启动检查 |
| B093 | `file-batch-system/batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/arch/PipelineWorkerAtomicIsolationOptInArchTest.java` | `shouldOptInIsolationCheck_whenExportWorker` | `4f2ee2f001ab` | 保留：EXPORT Worker 必须注册隔离启动检查 |
| B094 | `file-batch-system/batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/arch/PipelineWorkerAtomicIsolationOptInArchTest.java` | `shouldOptInIsolationCheck_whenProcessWorker` | `4f2ee2f001ab` | 保留：PROCESS Worker 必须注册隔离启动检查 |
| B095 | `file-batch-system/batch-e2e-tests/src/test/java/io/github/pinpols/batch/e2e/arch/PipelineWorkerAtomicIsolationOptInArchTest.java` | `shouldOptInIsolationCheck_whenDispatchWorker` | `4f2ee2f001ab` | 保留：DISPATCH Worker 必须注册隔离启动检查 |
| B096 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutorIpBlockTest.java` | `rejects_loopback_ipv4` | `fa37feaf0d22` | 保留：不同协议族、网段及公网反例 |
| B097 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutorIpBlockTest.java` | `rejects_loopback_ipv6` | `fa37feaf0d22` | 保留：不同协议族、网段及公网反例 |
| B098 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutorIpBlockTest.java` | `rejects_link_local_metadata_ipv4` | `fa37feaf0d22` | 保留：不同协议族、网段及公网反例 |
| B099 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutorIpBlockTest.java` | `rejects_link_local_metadata_ipv4_mapped_ipv6` | `fa37feaf0d22` | 保留：不同协议族、网段及公网反例 |
| B100 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutorIpBlockTest.java` | `rejects_private_class_a` | `fa37feaf0d22` | 保留：不同协议族、网段及公网反例 |
| B101 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutorIpBlockTest.java` | `rejects_private_class_c` | `fa37feaf0d22` | 保留：不同协议族、网段及公网反例 |
| B102 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutorIpBlockTest.java` | `rejects_any_local` | `fa37feaf0d22` | 保留：不同协议族、网段及公网反例 |
| B103 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutorIpBlockTest.java` | `accepts_public_literal` | `fa37feaf0d22` | 保留：不同协议族、网段及公网反例 |
| B104 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutorIpBlockTest.java` | `accepts_public_literal_ipv6` | `fa37feaf0d22` | 保留：不同协议族、网段及公网反例 |
| B105 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutorIpBlockTest.java` | `rejects_ipv6_ula_aws_metadata` | `fa37feaf0d22` | 保留：不同协议族、网段及公网反例 |
| B106 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutorIpBlockTest.java` | `rejects_ipv6_ula_fc00_range` | `fa37feaf0d22` | 保留：不同协议族、网段及公网反例 |
| B107 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutorIpBlockTest.java` | `rejects_ipv6_ula_fd_range` | `fa37feaf0d22` | 保留：不同协议族、网段及公网反例 |
| B108 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/http/HttpTaskExecutorIpBlockTest.java` | `rejects_ipv6_link_local` | `fa37feaf0d22` | 保留：不同协议族、网段及公网反例 |
| B109 | `file-batch-system/sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/client/WorkerFingerprintTest.java` | `shouldReturnCurrentJvmPid_whenReadingProcessId` | `90663e395c35` | 保留：Worker 进程号与当前 JVM 进程身份一致 |
| B110 | `file-batch-system/batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/stage/AckDispatchStepTest.java` | `stage_returnsAck` | `0d587c9aa46b` | 保留：分发 ACK 阶段标识 |
| B111 | `file-batch-system/batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/integration/DispatchSpiEquivalenceIntegrationTest.java` | `shouldContainDispatchTaskType_whenListingRegisteredTypes` | `ba6650656b87` | 保留：DISPATCH Worker 的 SPI 注册 |
| B112 | `file-batch-system/batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/stage/CompensateDispatchStepTest.java` | `stage_returnsCompensate` | `dfb2f2e5ce08` | 保留：分发 COMPENSATE 阶段标识 |
| B113 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/enums/TaskStatusTest.java` | `shouldContainSevenValues` | `190bbfaa9d25` | 保留：任务状态数量约束与各状态编码断言互补 |
| B114 | `file-batch-system/batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/stage/CompleteDispatchStepTest.java` | `stage_returnsComplete` | `98ffb7300a7f` | 保留：分发 COMPLETE 阶段标识 |
| B115 | `file-batch-system/batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/config/ImportScannerPropertiesTest.java` | `doneFileSuffix_defaultsToDotDone` | `2e39b538462b` | 保留：导入完成文件默认后缀 |
| B116 | `file-batch-system/batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/config/ImportScannerPropertiesTest.java` | `doneFileNaming_defaultsToAppendFullName` | `2e39b538462b` | 保留：导入完成文件默认命名策略 |
| B117 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/utils/ContentMaskingUtilsTest.java` | `shouldApplyTextRulesToMalformedJsonLikeInput` | `51b6c32ad14c` | 保留：损坏 JSON 文本中的敏感字段脱敏 |
| B118 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/integration/BatchWorkerAtomicApplicationIntegrationTest.java` | `shouldExposeApplicationContext_whenStarted` | `3592df593af6` | 已删除：同类端口验证已覆盖上下文启动和注入 |
| B119 | `file-batch-system/batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/integration/BatchWorkerDispatchApplicationIntegrationTest.java` | `shouldLoadApplicationContext_whenStarted` | `c8a42a0b4c7c` | 已删除：同类端口验证已覆盖上下文启动和注入 |
| B120 | `file-batch-system/batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/job/support/BuiltinTaskTypeGuardTest.java` | `shouldAllowType_whenJobTypeNullOrBlank` | `90317f255af8` | 保留：内置类型入口对缺失类型的既有行为 |
| B121 | `file-batch-system/batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/job/support/BuiltinTaskTypeGuardTest.java` | `shouldAllowBuiltinType_whenAnyGrantedRoleIsPlatformAdmin` | `90317f255af8` | 保留：多角色包含平台管理员时的授权 |
| B122 | `file-batch-system/sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/dispatcher/TaskDispatcherClaimJitterTest.java` | `shouldReturnZero_whenBaseDelayIsZero` | `d5f19b5c5db1` | 保留：零基础延迟下不得生成抖动 |
| B123 | `file-batch-system/batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/audit/service/ConsoleAiPromptGuardTest.java` | `shouldThrowBizExceptionForNullPrompt` | `c5fe12376b12` | 已增强：空提示词拒绝时验证参数错误码及必填国际化 key |
| B124 | `file-batch-system/batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/audit/service/ConsoleAiPromptGuardTest.java` | `shouldThrowBizExceptionForBlankPrompt` | `c5fe12376b12` | 已增强：空白提示词拒绝时验证参数错误码及必填国际化 key |
| B125 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/enums/RetryPolicyTypeTest.java` | `shouldContainThreeValues` | `4d4163c5e874` | 保留：重试策略枚举项数量约束 |
| B126 | `file-batch-system/batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/stage/PreprocessStreamEligibilityTest.java` | `shouldAllowDirectStreaming_whenTemplateMissing` | `ce656393c7c5` | 保留：无模板时直接流式导入 |
| B127 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/enums/DeadLetterReplayStatusTest.java` | `shouldContainFiveValues` | `563648867408` | 保留：死信重放状态数量约束 |
| B128 | `file-batch-system/batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/integration/ImportSpiEquivalenceIntegrationTest.java` | `shouldRegisterImportTaskType_whenContextLoads` | `9ec8f7170391` | 保留：IMPORT Worker 的 SPI 注册 |
| B129 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/OrchestratorGracefulShutdownTest.java` | `shouldNotBeDrainingInitially` | `c2e301e1e6a9` | 保留：编排器关闭协调初始非排空状态 |
| B130 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/enums/JobTypeTest.java` | `shouldNotExposeWorkflowAsExecutableJobType` | `577ba0414ac9` | 保留：WORKFLOW 不作为可执行 Job 类型 |
| B131 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/enums/JobTypeTest.java` | `shouldProvideNonNullBatchType_whenEnumeratingEveryJobType` | `577ba0414ac9` | 保留：逐枚举验证批类型映射非空 |
| B132 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/jdbc/JdbcMappedSqlValidatorTest.java` | `shouldNormalizeValidIdentifierToLowerCase` | `cf523ed482a4` | 保留：SQL 标识符标准化 |
| B133 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/jdbc/JdbcMappedSqlValidatorTest.java` | `shouldQuotePgIdentifier` | `cf523ed482a4` | 保留：PostgreSQL 标识符引用格式 |
| B134 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/spi/task/TaskResultTest.java` | `shouldDefaultOutputToEmptyMap_whenOutputAbsent` | `1e3f863fb77c` | 保留：Worker 通用任务结果的空输出契约 |
| B135 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/sensor/HttpPollSensorPolicyTest.java` | `matchExpr_missingPath_returnsFalseNotThrow` | `679e2ea9eec6` | 保留：HTTP 传感表达式缺失路径返回 false |
| B136 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowGraphValidatorTest.java` | `shouldReportClean_whenWorkflowIdMissing` | `ba75a942f4bc` | 已增强：缺失 Workflow ID 不产生错误图，且不查询节点、连线和作业 |
| B137 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/constants/BatchFileConstantsPartitionTagTest.java` | `shouldInsertTagBeforeExtension` | `8f05cc000053` | 保留：文件扩展名前插入分片标记 |
| B138 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/constants/BatchFileConstantsPartitionTagTest.java` | `shouldAppendTag_whenNoExtension` | `8f05cc000053` | 保留：无扩展名文件追加分片标记 |
| B139 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/constants/BatchFileConstantsPartitionTagTest.java` | `shouldReturnUnchanged_whenSinglePartition` | `8f05cc000053` | 保留：单分片文件名不变 |
| B140 | `file-batch-system/batch-worker/export/src/test/java/io/github/pinpols/batch/worker/exports/integration/ExportSpiEquivalenceIntegrationTest.java` | `shouldRegisterExportTaskType_whenContextLoads` | `54fb45624b71` | 保留：EXPORT Worker 的 SPI 注册 |
| B141 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/spark/SparkSubmitTaskExecutorTest.java` | `taskType_isSparkSubmit` | `fa2b1abf6088` | 保留：Spark 执行器任务类型 |
| B142 | `file-batch-system/batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/MisfireHandlerIntegrationTest.java` | `shouldWireMisfireHandlerBean` | `ab7ffc7ad343` | 保留：misfire handler 实现类型的装配契约 |
| B143 | `file-batch-system/batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/MisfireHandlerIntegrationTest.java` | `shouldLoadQuartzLaunchJobForMisfireAuditPath` | `ab7ffc7ad343` | 保留：QuartzLaunchJob 在启动上下文中的单独装配检查 |
| B144 | `file-batch-system/batch-trigger/src/test/java/io/github/pinpols/batch/trigger/integration/MisfireHandlerIntegrationTest.java` | `shouldNotThrowWhenHandlingMisfire` | `ab7ffc7ad343` | 已增强：未知触发名的 misfire 调用不中断调度并记录对应触发名 |
| B145 | `file-batch-system/batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/rbac/support/TenantScopeTest.java` | `shouldReturnTenantId_whenNonBlank` | `ceaad68afe0c` | 保留：非空租户 ID 原样返回 |
| B146 | `file-batch-system/batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/infrastructure/verifier/ProcessPublishedCountVerifierTest.java` | `shouldPass_whenPublishedCountPositive` | `09cd8a28d4a1` | 保留：数字与数字字符串为不同输入类型 |
| B147 | `file-batch-system/batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/infrastructure/verifier/ProcessPublishedCountVerifierTest.java` | `shouldPass_whenPublishedCountIsNumericString` | `09cd8a28d4a1` | 保留：数字与数字字符串为不同输入类型 |
| B148 | `file-batch-system/batch-console-api/src/test/java/io/github/pinpols/batch/console/support/cache/ConsoleQueryCacheServiceTest.java` | `keySegment_keepsShortSafeValuesReadable` | `fc94f54ae1c7` | 保留：安全短缓存键的可读性 |
| B149 | `file-batch-system/batch-worker/export/src/test/java/io/github/pinpols/batch/worker/exports/stage/format/GenerateCursorCodecTest.java` | `roundTrip_preservesBoolean` | `e4cd42db83d8` | 保留：游标 Boolean 类型往返 |
| B150 | `file-batch-system/sdk/typescript/tests/decide-backpressure.test.ts` | `decideBackpressure default paused=false: no resume in band` | `c76ed32647cb` | 保留：未暂停时背压区间不触发恢复 |
| B151 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/utils/IdGeneratorTest.java` | `shouldReturn32HexChars_whenGeneratingTraceId` | `c227afd20a8d` | 保留：一般追踪号格式契约，与明确无 Span 场景不同 |
| B152 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/utils/IdGeneratorTest.java` | `shouldReturn32HexChars_whenGeneratingInvocationId` | `c227afd20a8d` | 保留：调用号格式契约，与追踪号是不同入口 |
| B153 | `file-batch-system/batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/DispatchChannelTypePolicyTest.java` | `shouldReturnCanonicalType_whenInputHasSpacesAndLowercase` | `daffd532ab83` | 保留：渠道类型空格与大小写归一 |
| B154 | `file-batch-system/batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/DispatchChannelTypePolicyTest.java` | `shouldReturnEmpty_whenTypeUnknown` | `daffd532ab83` | 保留：未知渠道类型不被接受 |
| B155 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/stateful/StatefulBackendIdentityTest.java` | `shouldPreserveResourceCase_whenBuildingBackendIdentity` | `d867dbc6a70d` | 保留：参数化验证大小写敏感后端资源身份 |
| B156 | `file-batch-system/batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/rbac/support/ConsolePasswordHasherTest.java` | `shouldMatchFlywaySeedArgon2Hash` | `bb7e3f7e298f` | 已更名为 `shouldMatchKnownArgon2Hash`：固定 Argon2id 样本验证；该用例不读取 Flyway 迁移 |
| B157 | `file-batch-system/sdk/typescript/tests/shared-constants-parity.test.ts` | `schema_versions_supported parity` | `926060cb2022` | 保留：TS schema 版本与独立 YAML 契约一致 |
| B158 | `file-batch-system/sdk/typescript/tests/shared-constants-parity.test.ts` | `worker_runtime_states parity` | `926060cb2022` | 保留：TS Worker 运行状态与独立 YAML 契约一致 |
| B159 | `file-batch-system/sdk/typescript/tests/shared-constants-parity.test.ts` | `sensitive_keywords parity` | `926060cb2022` | 保留：TS 敏感词清单与独立 YAML 契约一致 |
| B160 | `file-batch-system/sdk/typescript/tests/shared-constants-parity.test.ts` | `task_statuses parity` | `926060cb2022` | 保留：TS 任务状态与独立 YAML 契约一致 |
| B161 | `file-batch-system/sdk/typescript/tests/shared-constants-parity.test.ts` | `report_error_codes parity` | `926060cb2022` | 保留：TS 上报错误码与独立 YAML 契约一致 |
| B162 | `file-batch-system/batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/integration/BatchWorkerImportApplicationIntegrationTest.java` | `shouldLoadContext_whenApplicationStarts` | `59f99f546a77` | 已删除：同类端口验证已覆盖上下文启动和注入 |
| B163 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/logging/LogSanitizerTest.java` | `shouldReplaceEveryUnicodeLineBreakWithUnderscore` | `a504808b2d43` | 保留：多种 Unicode 换行日志注入清理 |
| B164 | `file-batch-system/sdk/java/core/src/test/java/io/github/pinpols/batch/sdk/client/TaskTypeDescriptorAssemblyTest.java` | `shouldReturnNullDescriptor_whenHandlerDoesNotDeclare` | `d84813d775d5` | 保留：SDK handler 未声明 descriptor 的缺省值 |
| B165 | `file-batch-system/batch-test-support/src/test/java/io/github/pinpols/batch/common/rls/RlsClosedWorldCheckIntegrationTest.java` | `shardIdentity_defaultExempted` | `5f088a3860b8` | 保留：分片身份表 RLS 默认例外配置 |
| B166 | `file-batch-system/batch-worker/core/src/test/java/io/github/pinpols/batch/worker/core/support/ActiveTaskLeaseRegistryTest.java` | `shouldReturnEmptySnapshotWhenNoLeases` | `6384309003f6` | 保留：无租约时续租快照为空 |
| B167 | `file-batch-system/batch-worker/core/src/test/java/io/github/pinpols/batch/worker/core/infrastructure/checkpoint/CheckpointPartitionGuardTest.java` | `nullMeansSinglePartition_allow` | `20a6d3dc5cec` | 保留：空分区数按单分区处理 |
| B168 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/utils/CorrelationIdsTest.java` | `normalize_trimsAndKeepsSafeCharacters` | `41f677ca52aa` | 保留：规范化、非法字符、长度或回退规则 |
| B169 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/utils/CorrelationIdsTest.java` | `normalize_replacesUnsafeCharactersAndControls` | `41f677ca52aa` | 保留：规范化、非法字符、长度或回退规则 |
| B170 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/utils/CorrelationIdsTest.java` | `normalize_capsAtHeaderColumnLength` | `41f677ca52aa` | 保留：规范化、非法字符、长度或回退规则 |
| B171 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/utils/CorrelationIdsTest.java` | `normalize_usesFallbackWhenInputBlankOrInvalid` | `41f677ca52aa` | 保留：规范化、非法字符、长度或回退规则 |
| B172 | `file-batch-system/sdk/typescript/tests/kafka-offset-policy.test.ts` | `loweredCeiling: first withhold sets the ceiling to the record offset` | `d196f165bac9` | 保留：首次阻止提交建立 offset 下界 |
| B173 | `file-batch-system/sdk/typescript/tests/kafka-offset-policy.test.ts` | `commitBlockedByWithheld: no ceiling → never blocked` | `d196f165bac9` | 保留：无 offset 上界时不阻止提交 |
| B174 | `file-batch-system/sdk/typescript/tests/kafka-offset-policy.test.ts` | `resolveFromBeginning: defaults to false (latest), parity with the 4 other SDKs` | `d196f165bac9` | 保留：Kafka 缺省从最新位置消费 |
| B175 | `file-batch-system/sdk/typescript/tests/kafka-offset-policy.test.ts` | `resolveFromBeginning: an explicit value always wins` | `d196f165bac9` | 保留：显式 Kafka 起始策略优先于默认值 |
| B176 | `file-batch-system/batch-worker/export/src/test/java/io/github/pinpols/batch/worker/exports/sql/SqlTemplateExportSqlValidatorTest.java` | `validate_ignoresParameterTextOutsideSqlParameters` | `837e408afa03` | 保留：SQL 参数之外的相似文本不受参数校验干扰 |
| B177 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/service/LaunchParamResolverTest.java` | `shouldReturnNullForNullDeadline` | `9b170a4af1f3` | 保留：缺失截止时间解析结果 |
| B178 | `file-batch-system/batch-trigger/src/test/java/io/github/pinpols/batch/trigger/application/TriggerOutboxReleaseGovernorTest.java` | `disabled_returnsConfiguredLimitWithoutReadingLag` | `5d5e12952331` | 已增强：断言禁用时无 lag 读取 |
| B179 | `file-batch-system/batch-worker/export/src/test/java/io/github/pinpols/batch/worker/exports/integration/BatchWorkerExportApplicationIntegrationTest.java` | `shouldLoadContext_whenApplicationStarts` | `5e5ba8e238e7` | 已删除：同类端口验证已覆盖上下文启动和注入 |
| B180 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/enums/PartitionStatusTest.java` | `shouldContainNineValues` | `c409ffac974f` | 保留：分区状态枚举数量约束 |
| B181 | `file-batch-system/batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/stage/PrepareDispatchStepTest.java` | `stage_returnsPrepare` | `ff63ff8c51ee` | 保留：分发 PREPARE 阶段标识 |
| B182 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/storage/S3ObjectStoreExceptionMappingTest.java` | `shouldReportMissing_whenKeyDoesNotExist` | `a29c22ebfdbb` | 保留：S3 不存在与无权限错误的区别 |
| B183 | `file-batch-system/batch-trigger/src/test/java/io/github/pinpols/batch/trigger/infrastructure/TriggerGracefulShutdownTest.java` | `shouldNotBeDrainingInitially` | `c49897f6b707` | 保留：Trigger 优雅停机初始非排空状态 |
| B184 | `file-batch-system/batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/integration/ProcessSpiEquivalenceIntegrationTest.java` | `shouldContainProcessTaskType_whenListingRegisteredTypes` | `3cd79f380cd5` | 保留：PROCESS Worker 的 SPI 注册 |
| B185 | `file-batch-system/batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/stage/DeliverDispatchStepTest.java` | `stage_returnsDispatch` | `7d330a4127bf` | 保留：分发 DISPATCH 阶段标识 |
| B186 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/DefaultWorkflowDagServiceTest.java` | `shouldReturnEmptyWhenDefinitionIdIsNull` | `752a065113d4` | 保留：缺少定义 ID 不解析后续节点 |
| B187 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/DefaultWorkflowDagServiceTest.java` | `shouldReturnEmptyWhenCurrentNodeCodeIsBlank` | `752a065113d4` | 保留：空节点编码不解析后续节点 |
| B188 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/DefaultWorkflowDagServiceTest.java` | `shouldReturnFalseWhenWorkflowRunIdIsNull` | `752a065113d4` | 保留：缺少 Workflow 运行 ID 不允许派发 |
| B189 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/DefaultWorkflowDagServiceTest.java` | `shouldReturnFalseWhenDefinitionIdIsNull` | `752a065113d4` | 保留：缺少定义 ID 不允许派发 |
| B190 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/DefaultWorkflowDagServiceTest.java` | `shouldReturnFalseWhenNodeCodeIsBlank` | `752a065113d4` | 保留：空节点编码不允许派发 |
| B191 | `file-batch-system/batch-worker/core/src/test/java/io/github/pinpols/batch/worker/core/reportoutbox/WorkerReportOutboxPgIntegrationTest.java` | `claimNext_returnsEmptyWhenNothingDue` | `83910f28a209` | 保留：真实数据库无到期报告时不认领 |
| B192 | `file-batch-system/batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/ops/infrastructure/TriggerProxyJsonContractTest.java` | `shouldReturnEmptyList_whenDownstreamRowsAreNull` | `7f2044c5bd64` | 保留：下游空列表的租户过滤结果 |
| B193 | `file-batch-system/batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/DispatchChannelCircuitBreakerTest.java` | `shouldAllowWhenNoFailuresRecorded` | `386f4ca7ce35` | 保留：无故障的渠道熔断器初始状态 |
| B194 | `file-batch-system/batch-worker/dispatch/src/test/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/DispatchChannelCircuitBreakerTest.java` | `shouldIsolateFailuresByChannelKey` | `386f4ca7ce35` | 已增强：同时验证故障渠道已熔断和正常渠道仍放行 |
| B195 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/statemachine/DefaultLifecycleEventMapperTest.java` | `shouldResolveEnumTargetAsState` | `bfb40b8c8197` | 保留：枚举状态到目标状态的映射 |
| B196 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/statemachine/DefaultLifecycleEventMapperTest.java` | `shouldAllowTerminalSelfLoop` | `bfb40b8c8197` | 保留：终态自环幂等 |
| B197 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/enums/ConfigLifecycleStatusTest.java` | `shouldContainFiveValues` | `5a72fc143e66` | 保留：配置生命周期状态数量约束 |
| B198 | `file-batch-system/batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/integration/ProcessStagingCleanupYamlIntegrationTest.java` | `shouldDisableStagingOrphanCleaner_whenTestProfileActive` | `15dcdec92ee6` | 保留：测试 profile 关闭 PROCESS 清理调度 |
| B199 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/security/RequestSignatureVerifierTest.java` | `shouldRejectRequest_whenSignatureHeaderMissing` | `eabe044a51e5` | 保留：缺少签名请求头时拒绝 |
| B200 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/router/DefaultWorkerRoutingPolicyTest.java` | `shouldReturnNullForNullCandidates` | `423801bfd418` | 保留：null Worker 候选 |
| B201 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/infrastructure/router/DefaultWorkerRoutingPolicyTest.java` | `shouldReturnNullForEmptyCandidates` | `423801bfd418` | 保留：空 Worker 候选集合 |
| B202 | `file-batch-system/batch-worker/process/src/test/java/io/github/pinpols/batch/worker/processes/stage/CommitStepTest.java` | `shouldReturnStageCommit_whenStageAccessed` | `86e923aa9c7e` | 保留：PROCESS COMMIT 阶段标识 |
| B203 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/sql/SqlTaskExecutorHardeningTest.java` | `shouldClassifyAsDdl_whenStatementIsDoBlock` | `c758caca1909` | 保留：大写 PostgreSQL DO 块为 DDL |
| B204 | `file-batch-system/batch-worker/atomic/src/test/java/io/github/pinpols/batch/worker/atomic/sql/SqlTaskExecutorHardeningTest.java` | `shouldClassifyAsDdl_whenDoBlockLowercase` | `c758caca1909` | 保留：小写 PostgreSQL DO 块为 DDL |
| B205 | `file-batch-system/batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/workflow/param/WorkflowDefinitionWriteParamsTest.java` | `shouldRejectMissingRequiredField` | `31524b01e74d` | 保留：参数化检查 Workflow 写入的各必填字段 |
| B206 | `file-batch-system/batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/workflow/WorkflowFanOutSupportTest.java` | `parseSpec_nullWhenItemsExprBlank` | `c3c26efdd198` | 保留：空 fan-out 表达式不生成展开配置 |
| B207 | `file-batch-system/batch-worker/import/src/test/java/io/github/pinpols/batch/worker/imports/stage/FeedbackStepTest.java` | `shouldReturnStageFeedback_whenStageAccessed` | `604b15ab5922` | 保留：IMPORT FEEDBACK 阶段标识 |
| B208 | `file-batch-system/batch-common/src/test/java/io/github/pinpols/batch/common/security/DnsResolveGuardTest.java` | `shouldAllow_whenDocumentationGlobalIpv6` | `f7138dc50641` | 保留：文档用途 IPv6 的现行放行契约 |
| F001 | `batch-console/src/utils/aiMessagePresentation.test.ts` | `keeps approved answer content` | `b8387a88bec5` | 保留：批准的 AI 回答正文应展示 |
| F002 | `batch-console/src/utils/lastApiMeta.test.ts` | `stores a valid meta object` | `92c85329e965` | 保留：有效值、null 和 undefined 独立输入 |
| F003 | `batch-console/src/utils/lastApiMeta.test.ts` | `sets value to null when called with undefined` | `92c85329e965` | 保留：有效值、null 和 undefined 独立输入 |
| F004 | `batch-console/src/utils/lastApiMeta.test.ts` | `sets value to null when called with null` | `92c85329e965` | 保留：有效值、null 和 undefined 独立输入 |
| F005 | `batch-console/src/utils/datetime.test.ts` | `handles epoch ms number` | `3a3cb1ad5298` | 已增强：保留默认格式检查并用 UTC 校验 epoch 实际值 |
| F006 | `batch-console/src/utils/datetime.test.ts` | `returns raw string for invalid date` | `3a3cb1ad5298` | 保留：无效日期按原文回退 |
| F007 | `batch-console/src/utils/errorCatalog.test.ts` | `falls back to network` | `f59ce4df4ec2` | 保留：网络错误与无匹配路径不同 |
| F008 | `batch-console/src/utils/errorCatalog.test.ts` | `returns undefined when nothing matches` | `f59ce4df4ec2` | 保留：网络错误与无匹配路径不同 |
| F009 | `batch-console/src/utils/formatAiUsd.test.ts` | `uses cents for zero and ordinary amounts` | `46db042ecd0f` | 保留：金额格式化边界 |
| F010 | `batch-console/src/utils/queryFormOptions.test.ts` | `returns sorted unique non-empty values` | `13c48ce5ade1` | 保留：空输入、空值和去重排序不同场景 |
| F011 | `batch-console/src/utils/queryFormOptions.test.ts` | `returns empty array for empty input` | `13c48ce5ade1` | 保留：空输入、空值和去重排序不同场景 |
| F012 | `batch-console/src/utils/queryFormOptions.test.ts` | `filters out undefined picks` | `13c48ce5ade1` | 保留：空输入、空值和去重排序不同场景 |
| F013 | `batch-console/src/constants/theme.test.ts` | `defaults to dark when no user preference is stored` | `b214db7a0a70` | 保留：缺省、保存值和历史迁移不同状态 |
| F014 | `batch-console/src/constants/theme.test.ts` | `preserves stored user preference` | `b214db7a0a70` | 保留：缺省、保存值和历史迁移不同状态 |
| F015 | `batch-console/src/constants/theme.test.ts` | `migrates legacy system default to dark once` | `b214db7a0a70` | 已更名：无标记历史 system 偏好读取回退深色，读取本身不执行迁移 |
| F016 | `batch-console/src/utils/logRedact.test.ts` | `sanitizeParams returns undefined for null/undefined` | `ab4106a1cb6a` | 保留：null 与 undefined 参数不生成日志对象 |
| F017 | `batch-console/e2e/alert-outbox-ops.spec.ts` | `重试 tab 默认激活` | `693423e3785b` | 保留：Outbox 初始重试 tab 状态 |
| F018 | `batch-console/src/stores/tabs.test.ts` | `returns -1 for unknown key` | `3994cb80f664` | 保留：关闭不存在的 tab 返回 -1 |
| F019 | `batch-console/e2e/excel-import.spec.ts` | `下载配置包模板按钮可见` | `9d85670a1912` | 保留：默认页面模板下载入口可见，独立于场景选择后下载 |
| F020 | `batch-console/e2e/excel-import.spec.ts` | `导出当前配置包按钮可见` | `9d85670a1912` | 保留：默认身份配置包导出入口可见，与指定角色回导场景独立 |
| F021 | `batch-console/src/api/workerFingerprint.test.ts` | `listWorkerFingerprints normalizes null response to empty array` | `1b59278f03f6` | 保留：两个接口各自的空响应契约 |
| F022 | `batch-console/src/api/workerFingerprint.test.ts` | `getWorkerFingerprintSummary normalizes null response to empty array` | `1b59278f03f6` | 保留：两个接口各自的空响应契约 |
| F023 | `batch-console/src/utils/iconResolve.test.ts` | `已知图标名:返回对应 EP 图标 Component` | `2ca9db934b7a` | 已合并并增强：已知图标精确解析为 Plus，未知名称回退 Menu |
| F024 | `batch-console/src/utils/iconResolve.test.ts` | `未知图标名:返回 fallback(Menu)而非 undefined,保菜单可渲染` | `2ca9db934b7a` | 已增强：未知名称及空值均精确回退 Menu |
| F025 | `batch-console/src/components/common/SensitiveFieldAlert.test.ts` | `handles invalid JSON string gracefully (no render)` | `ec474dc11d5e` | 保留：无效敏感字段 JSON 不渲染告警 |
| F026 | `batch-console/src/utils/idempotency.test.ts` | `returns a non-empty string` | `083d0f89d82f` | 已增强：单次生成结果的类型和非空一致断言 |
| F027 | `batch-console/e2e/ops-shard-catalog-crud.spec.ts` | `页可达 + 新建入口可见` | `3cebf6614a54` | 保留：串行 CRUD 前的独立分片目录入口冒烟 |
| F028 | `batch-console/src/utils/resourceCodeBuilder.test.ts` | `uppercases lower` | `0cce0297aacb` | 保留：规范化、校验边界、版本或拼装不同场景 |
| F029 | `batch-console/src/utils/resourceCodeBuilder.test.ts` | `keeps digits + underscores` | `0cce0297aacb` | 保留：规范化、校验边界、版本或拼装不同场景 |
| F030 | `batch-console/src/utils/resourceCodeBuilder.test.ts` | `empty → EMPTY` | `0cce0297aacb` | 保留：规范化、校验边界、版本或拼装不同场景 |
| F031 | `batch-console/src/utils/resourceCodeBuilder.test.ts` | `< 3 chars → LENGTH` | `0cce0297aacb` | 保留：规范化、校验边界、版本或拼装不同场景 |
| F032 | `batch-console/src/utils/resourceCodeBuilder.test.ts` | `> 30 chars → LENGTH` | `0cce0297aacb` | 保留：规范化、校验边界、版本或拼装不同场景 |
| F033 | `batch-console/src/utils/resourceCodeBuilder.test.ts` | `digit-leading rejected (BIZ_FORMAT requires letter first)` | `0cce0297aacb` | 保留：规范化、校验边界、版本或拼装不同场景 |
| F034 | `batch-console/src/utils/resourceCodeBuilder.test.ts` | `exact 30 chars boundary OK` | `0cce0297aacb` | 保留：规范化、校验边界、版本或拼装不同场景 |
| F035 | `batch-console/src/utils/resourceCodeBuilder.test.ts` | `accepts v-prefixed` | `0cce0297aacb` | 保留：规范化、校验边界、版本或拼装不同场景 |
| F036 | `batch-console/src/utils/resourceCodeBuilder.test.ts` | `accepts bare digits` | `0cce0297aacb` | 保留：规范化、校验边界、版本或拼装不同场景 |
| F037 | `batch-console/src/utils/resourceCodeBuilder.test.ts` | `builds without version` | `0cce0297aacb` | 保留：规范化、校验边界、版本或拼装不同场景 |
| F038 | `batch-console/src/utils/resourceCodeBuilder.test.ts` | `builds with version` | `0cce0297aacb` | 保留：规范化、校验边界、版本或拼装不同场景 |
| F039 | `batch-console/src/utils/resourceCodeBuilder.test.ts` | `normalizes biz input on the fly` | `0cce0297aacb` | 保留：规范化、校验边界、版本或拼装不同场景 |
| F040 | `batch-console/src/utils/resourceCodeBuilder.test.ts` | `drops bad version silently` | `0cce0297aacb` | 保留：规范化、校验边界、版本或拼装不同场景 |
| F041 | `batch-console/src/utils/resourceCodeBuilder.test.ts` | `empty existing → empty` | `0cce0297aacb` | 保留：规范化、校验边界、版本或拼装不同场景 |
| F042 | `batch-console/e2e/smoke.spec.ts` | `根路径重定向到运营概览` | `b4feddbf35cd` | 保留：根路径跳转到运维概览 |
| F043 | `batch-console/src/utils/pipelineStepSummary.test.ts` | `PARSE 步骤取 parsedCount` | `db777d80109a` | 保留：不同阶段字段及异常输入 |
| F044 | `batch-console/src/utils/pipelineStepSummary.test.ts` | `GENERATE 取 recordCount` | `db777d80109a` | 保留：不同阶段字段及异常输入 |
| F045 | `batch-console/src/utils/pipelineStepSummary.test.ts` | `未映射 stage(如 PREPARE)且 summary 含计数 → 回退优先级列表` | `db777d80109a` | 保留：不同阶段字段及异常输入 |
| F046 | `batch-console/src/utils/pipelineStepSummary.test.ts` | `已解析对象入参(非字符串)也支持` | `db777d80109a` | 保留：不同阶段字段及异常输入 |
| F047 | `batch-console/src/utils/pipelineStepSummary.test.ts` | `计数字段非数字(脏数据)→ 跳过不误显` | `db777d80109a` | 保留：不同阶段字段及异常输入 |
| F048 | `batch-console/src/utils/safeHtml.test.ts` | `returns the sanitized string unchanged` | `3dc117efb48b` | 已增强：清洗器返回不同文本时原样传递清洗结果 |
| F049 | `batch-console/src/api/meta.test.ts` | `payload 不是对象时返回 {}(防御后端异常)` | `1ccdd5049e9e` | 保留：元数据枚举接口空响应回退空对象 |
| F050 | `batch-console/src/utils/metaEnumPick.test.ts` | `returns first matching non-empty group` | `b775cb7392ca` | 保留：候选键、缺失输入和空分组边界 |
| F051 | `batch-console/src/utils/metaEnumPick.test.ts` | `skips missing keys and returns first hit` | `b775cb7392ca` | 保留：候选键、缺失输入和空分组边界 |
| F052 | `batch-console/src/utils/metaEnumPick.test.ts` | `skips empty arrays` | `b775cb7392ca` | 保留：候选键、缺失输入和空分组边界 |
| F053 | `batch-console/src/utils/metaEnumPick.test.ts` | `returns [] when no key matches` | `b775cb7392ca` | 保留：候选键、缺失输入和空分组边界 |
| F054 | `batch-console/src/utils/metaEnumPick.test.ts` | `returns [] for undefined input` | `b775cb7392ca` | 保留：候选键、缺失输入和空分组边界 |
| F055 | `batch-console/src/utils/metaEnumPick.test.ts` | `returns [] for null input` | `b775cb7392ca` | 保留：候选键、缺失输入和空分组边界 |
| F056 | `batch-console/src/utils/metaEnumPick.test.ts` | `returns [] when called with no keys` | `b775cb7392ca` | 保留：候选键、缺失输入和空分组边界 |
| F057 | `batch-console/src/stores/auth.test.ts` | `isLoggedIn is false when no token` | `beedb463ec52` | 已更名：未设置 session 标记的登录状态 |
| F058 | `batch-console/src/stores/auth.test.ts` | `hasPermission returns false when no userInfo` | `beedb463ec52` | 保留：缺少用户信息时无权限 |
| F059 | `batch-console/e2e/file-ops.spec.ts` | `默认展示运行列表 tab` | `5663bc67b7cb` | 保留：流水线观测运行列表默认 tab |
| F060 | `batch-console/src/views/monitor/workflowNodeSkipReason.test.ts` | `marks old skipped rows without a recorded reason` | `e28ec3ed77e5` | 保留：历史跳过节点缺少原因的兼容文案 |
| F061 | `batch-console/src/views/monitor/workflowNodeSkipReason.test.ts` | `does not show skip copy for other node states` | `e28ec3ed77e5` | 保留：非跳过节点不显示跳过文案 |
| F062 | `batch-console/src/api/atomicTaskTypes.test.ts` | `returns empty array when payload is empty` | `f9d606c29c00` | 保留：原子任务类型空列表响应 |
| F063 | `batch-console/src/api/customTaskTypes.test.ts` | `returns null for invalid JSON` | `4bce8222129d` | 保留：自定义任务描述非法 JSON 解析回退 |
| F064 | `batch-console/src/views/system/atomicRuntimeModel.test.ts` | `不可用时即使混有executor字段也不展示为真实状态` | `74f78eaff53a` | 保留：运行状态不可用时不误展示静态字段 |
| F065 | `batch-console/src/views/workflow/designer/codec/mermaidExporter.test.ts` | `empty snapshot → header only` | `7ef62b26c858` | 保留：空 Workflow 图的 Mermaid 头部 |
| F066 | `batch-console/src/utils/passwordGenerator.test.ts` | `throws when length < 4` | `de6d00da4499` | 保留：密码长度低于下限时拒绝生成 |
| F067 | `batch-console/src/utils/passwordGenerator.test.ts` | `excludes ambiguous characters (0/O/I/l/1)` | `de6d00da4499` | 保留：密码不包含易混字符 |
| F068 | `batch-console/src/utils/passwordGenerator.test.ts` | `extra point at length ≥ 12` | `de6d00da4499` | 已增强：比较字符类别相同的 11、12 字符密码强度 |
| F069 | `batch-console/src/utils/passwordGenerator.test.ts` | `caps at 4 (extreme)` | `de6d00da4499` | 已增强：极长复杂密码的强度精确为上限 4 |
| F070 | `batch-console/src/views/worker/workerActions.test.ts` | `在线节点只展示下线和接管动作` | `79bc5544a7dd` | 已更名：标题与 ONLINE 原有操作断言一致 |
| F071 | `batch-console/src/views/worker/workerActions.test.ts` | `排空中节点不再展示排空和预热` | `79bc5544a7dd` | 保留：不同 Worker 状态对应不同操作集 |
| F072 | `batch-console/src/views/worker/workerActions.test.ts` | `离线节点只允许预热` | `79bc5544a7dd` | 保留：不同 Worker 状态对应不同操作集 |
| F073 | `batch-console/e2e/flows/01-trigger-instance.spec.ts` | `1. 查到一个可触发的 jobDefinition` | `ce6052a17152` | 保留：真实触发流程对作业定义存在的前置检查 |
| F074 | `batch-console/src/api/taskHeartbeat.test.ts` | `reads percent in 0-100 range` | `7ba0db0a5c78` | 保留：比例、百分数、精度及优先级不同场景 |
| F075 | `batch-console/src/api/taskHeartbeat.test.ts` | `treats 0-1 floats as ratios and scales up` | `7ba0db0a5c78` | 保留：比例、百分数、精度及优先级不同场景 |
| F076 | `batch-console/src/api/taskHeartbeat.test.ts` | `rounds to 0.1` | `7ba0db0a5c78` | 保留：比例、百分数、精度及优先级不同场景 |
| F077 | `batch-console/src/api/taskHeartbeat.test.ts` | `prefers percent over progress when both present` | `7ba0db0a5c78` | 保留：比例、百分数、精度及优先级不同场景 |
| F078 | `batch-console/src/utils/sensitiveKeys.test.ts` | `returns empty for clean object` | `c144b2c3a67b` | 保留：无敏感字段对象的反例 |
| F079 | `batch-console/src/utils/sensitiveKeys.test.ts` | `detects direct sensitive keys` | `c144b2c3a67b` | 保留：直接敏感字段识别 |
| F080 | `batch-console/src/utils/sensitiveKeys.test.ts` | `parses JSON string input` | `c144b2c3a67b` | 保留：JSON 文本输入的敏感字段识别 |
| F081 | `batch-console/src/api/adapters.test.ts` | `returns correct slice for page 2` | `97302b9898c2` | 保留：分页第二页切片 |
| F082 | `batch-console/src/api/adapters.test.ts` | `returns partial last page` | `97302b9898c2` | 保留：分页不足一页的尾页 |
| F083 | `batch-console/src/utils/structuredSummary.test.ts` | `truncates plain text summaries` | `5bf1d96ef232` | 保留：文本截断行为 |
| F084 | `batch-console/e2e/reports-ops.spec.ts` | `展示所有报表卡片` | `947db579c576` | 保留：报表导出卡片总数 |
| F085 | `batch-console/e2e/reports-ops.spec.ts` | `header 显示 tenantId 标签` | `947db579c576` | 保留：报表中心展示租户标签 |
| F086 | `batch-console/src/utils/safeJson.test.ts` | `returns empty string unchanged` | `ecf5fe539fb7` | 保留：空文本与非法 JSON 行为不同 |
| F087 | `batch-console/src/utils/safeJson.test.ts` | `falls back to original string on malformed JSON` | `ecf5fe539fb7` | 保留：空文本与非法 JSON 行为不同 |
| F088 | `batch-console/e2e/c-plus-coverage.spec.ts` | `actuator/health 暴露 200` | `886f1907a8f1` | 保留：Console 服务 health 端点状态 |
| F089 | `batch-console/src/components/common/statusTagResolve.test.ts` | `falls back to fallback arg when value empty` | `8a169b9c2830` | 保留：状态为空使用指定默认标签 |
| F090 | `batch-console/src/components/common/statusTagResolve.test.ts` | `falls back to em-dash when value empty and no fallback` | `8a169b9c2830` | 保留：状态为空使用隐式默认标签 |
| F091 | `batch-console/src/components/common/statusTagResolve.test.ts` | `returns info for unknown values when no local label override` | `8a169b9c2830` | 保留：未知状态的默认颜色 |
| F092 | `batch-console/src/composables/useOnboardingTour.test.ts` | `首次访问且无完成标记时展示引导` | `acaf78e70608` | 保留：首次访问展示引导 |
| F093 | `batch-console/src/composables/useOnboardingTour.test.ts` | `已完成引导时不再展示` | `acaf78e70608` | 保留：完成标记阻止重复展示 |
| F094 | `batch-console/src/views/monitor/canReplayFailedWorkflowJobNode.test.ts` | `allows only a failed JOB node with a linked instance in a failed Workflow run` | `6ab6409f2d1e` | 保留：失败 Workflow 中关联作业节点可重放的正例 |
| F095 | `batch-console/e2e/ops-diagnostic.spec.ts` | `集群诊断区块存在` | `92ac3c31a029` | 已增强：限定集群诊断卡片标题而非匹配任意诊断文案 |
| F096 | `batch-console/src/directives/permission.test.ts` | `keeps element when permissions list empty` | `5081b134793f` | 保留：空权限列表的显式准入语义 |
| F097 | `batch-console/src/components/common/EmptyState.test.ts` | `没有操作时不渲染空的操作容器` | `88710e9f5c5a` | 保留：无操作时空状态不生成操作容器 |
| F098 | `batch-console/src/utils/userErrorMessage.test.ts` | `preserves non-API client-side validation errors` | `2a196371a12b` | 保留：前端本地验证异常原文 |
| F099 | `batch-console/src/utils/logger.test.ts` | `logClick writes type=click` | `cf4674799e76` | 保留：点击日志类型 |
| F100 | `batch-console/src/utils/logger.test.ts` | `keeps legitimate error messages` | `cf4674799e76` | 已增强：断言实际保留的错误名称 |
| F101 | `batch-console/src/utils/logger.test.ts` | `filters by keyword in name` | `cf4674799e76` | 已增强：断言名称关键词筛选返回的具体条目 |
| F102 | `batch-console/src/utils/logger.test.ts` | `filters by keyword in props` | `cf4674799e76` | 已增强：关键词只存在于属性，验证属性筛选结果 |
| F103 | `batch-console/src/utils/logger.test.ts` | `returns all when no filter` | `cf4674799e76` | 已增强：无筛选时验证全部具体日志条目 |
| F104 | `batch-console/src/composables/useFormValidate.test.ts` | `required 默认 blur` | `fbf2e873b8d6` | 保留：必填规则默认 blur 触发器 |
| F105 | `batch-console/src/composables/useFormValidate.test.ts` | `formRef 未挂载时 validate() 返回 false 不抛` | `fbf2e873b8d6` | 保留：未挂载表单的安全失败 |
| F106 | `batch-console/src/components/common/docsRegistry.test.ts` | `docKey 全为 kebab-case(避免 camelCase / snake_case 混用)` | `c5c768c197b1` | 保留：文档注册键命名规则 |
| F107 | `batch-console/src/components/common/docsRegistry.test.ts` | `未知 docKey 返回空字符串(避免 iframe 加载未定义页)` | `c5c768c197b1` | 保留：未知文档键返回空路径 |
| F108 | `batch-console/src/utils/text.test.ts` | `keeps short values unchanged` | `22ae23420c9d` | 保留：短文本、中文和代理对边界 |
| F109 | `batch-console/src/utils/text.test.ts` | `truncates CJK text by characters rather than bytes` | `22ae23420c9d` | 保留：短文本、中文和代理对边界 |
| F110 | `batch-console/src/utils/text.test.ts` | `does not split a surrogate pair` | `22ae23420c9d` | 保留：短文本、中文和代理对边界 |
| F111 | `batch-console/e2e/ops-tenant-placement-crud.spec.ts` | `页可达 + 新建入口可见` | `59572125f679` | 保留：串行 CRUD 前的独立租户分片入口冒烟 |
| F112 | `batch-console/src/views/scheduler/batchDayPresentation.test.ts` | `builds leap-year month boundaries in local time` | `34dac61712f4` | 保留：本地月份范围包含闰年末日 |
| F113 | `batch-console/e2e/notification-management.spec.ts` | `旧 webhooks 路由重定向` | `6f944632a3cb` | 保留：旧 Webhook 路由兼容跳转 |
