# 系统设计与容量整改代码复核（2026-11-10）

## 合并后 Full CI 修复

- 后端主线 Full CI（运行 `42752340311`）的原子任务重投用例要求回报失败后写入 Worker Outbox，但测试应用未启用该功能；日志显示 `outbox_disabled`。现为该测试类启用 PostgreSQL Outbox，并保留回报记录与业务不重复执行断言。`./mvnw -o -ntp -pl batch-e2e-tests -am '-Dtest=AtomicTaskPipelineE2eIT' -Dsurefire.failIfNoSpecifiedTests=false test -l /tmp/e2e-atomic-report-outbox-fix-20261110.log` 使用隔离容器执行该类，14/14 通过、0 跳过；尚未验证修复后的远端 Full CI。
- 后端主线 Full CI（运行 `42746990731`）的 20 个 E2E 分片均在测试应用启动时缺少公共入站身份组件，业务断言未执行；8 个受影响测试应用已补齐组件扫描，两个导入应用补齐平台 Mapper 扫描。`./mvnw -o -ntp -pl batch-e2e-tests -am '-Dtest=SpringWiringGuardArchTest,AtomicHttpRetryLayersE2eIT#shouldCountHttpAndJobRetriesWhenServerContinuesToFail,KafkaConsumerNamespaceIsolationE2eIT#shouldAssignOnlyNamespacedTopics_whenLegacyTopicsAlsoExist,DispatchFailurePipelineE2eIT#shouldRejectLaunch_whenChannelConfigMissing,ExportEncryptedArtifactE2eIT#shouldRegisterActualEncryptedObjectAndRecoverBusinessContent,ImportBomPartitionE2eIT#shouldWriteEachBomDelimitedRangeRecordOnce,SensorWaitFixtureE2eIT#shouldContainValidWaitFileArrivalWorkflow_whenTaTenantSeedLoaded,ProcessFailurePipelineE2eIT#wap_sqlTransform_validationFailureAbortsCommit,ReadinessHandoverE2eIT#shouldKeepFirstDeadlineAcrossRepeatedHandover' -Dsurefire.failIfNoSpecifiedTests=false test` 13/13 通过、0 跳过：8 个应用各 1 个真实场景及装配守护 5 项；日志 `/tmp/postmerge-e2e-app-wiring-tests.log`。这不等于完整 E2E 全量通过。
- Full CI 另有 V376 定向迁移测试将后续未应用迁移误判为验证失败，以及文件登记测试夹具缺少 V378 新列。前者保持目标版本一致执行迁移和校验；后者复用权威 V378 热表 DDL 并增加渠道写入断言，未修改生产迁移。`./mvnw -o -ntp -pl batch-orchestrator,batch-worker/core -am '-Dtest=LocalFlywayPlatformMigrationsIntegrationTest#shouldReplaceOnlyLegacyWebhookRetryIndex_whenMigratingV376,PlatformFileRecordRepositoryIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test` 11/11 通过、0 跳过，日志 `/tmp/postmerge-flyway-file-record-tests.log`。最终 PR 门禁及合并后的 Full CI 仍需实际运行确认。

## 合并前 TypeScript 审查修复

- 远端审查发现慢 CLAIM 等待期间 KafkaJS `eachMessage` 不自动发送心跳，可能引发消费组重平衡；适配器在等待期间按间隔发送心跳，确认前不提交位点，并在等待结束时清理计时器。隔离 Kafka 的定向测试以 6 秒会话、9 秒认领等待验证成员保持稳定、位点不提前提交，释放后位点推进；该样本约 15 秒，仅证明指定场景，不是网络故障或生产容量验收。
- 停机期间 consumer 关闭会等待正在处理的 CLAIM 回调，可能超过排空预算；生命周期在关闭前将未决认领标记为暂扣，迟到成功不能进入续租或业务。模拟消费者等待当前回调的测试从超过 1 秒未返回变为 `stop(40)` 约 46 毫秒完成。停止中不再创建新的分区重试计时器；底层 Kafka 关闭、位点初始化及注销请求耗时仍各自受依赖行为影响，不能推导所有网络故障均严格按预算退出。
- 生命周期及相关定向测试在 Node 22、24 各 19 项通过。Node 24 执行 `BATCH_SDK_KAFKA_BOOTSTRAP=127.0.0.1:39092 node --test --test-concurrency=1 --experimental-strip-types tests/kafka-heartbeat.test.ts tests/kafka-adapter-anchor.test.ts tests/kafka-offset-policy.test.ts tests/kafka-withheld-integration.test.ts`，24/24、0 跳过，其中包括 6 个真实 Kafka 场景；Node 22 的相关非 broker 定向组 18/18，慢认领真实 Kafka 场景单独 1/1。最终远端 CI 尚待本次提交后确认；KafkaJS 本地运行仍出现 `TimeoutNegativeWarning`。

## 基线与范围

- 后端 `codex/system-design-capacity-closure`，HEAD `e7999b3645682f5fa075f63023d5ec07b80e164b`；包含该工作树当时的未提交和未跟踪改动。前端同名分支，HEAD `1b0cd9b10369`，当时有 10 个已跟踪文件修改。
- 按实际变更重点静态复核文件等待与入口归属、数据库迁移 V377–V383、Workflow 收口、任务执行围栏、五类 Worker 相关路径、Go/TypeScript SDK 消费、Console API 与配对页面、部署授权及负载脚本。未逐行覆盖全部改动或所有 SDK 传输实现。
- 关联阶段主记录：[系统设计与容量验证](../plans/system-design-capacity-validation-plan-2026-11-09.md)。

## 复核结果

已检查路径未确认新的可操作代码缺陷；这不等于全部改动已经逐行审完或系统不存在其他问题。

### 升级手册缺少历史文件例外

V378 将迁移前文件标为允许历史回退；[文件治理查询](../../batch-orchestrator/src/main/resources/mapper/FileGovernanceMapper.xml)对该类记录直接保留可见性。因此，若旧对象对账已提前登记受管入口文件，即使该文件尚未经过当前清单准入，仍可能参与文件等待与到达组。[设计文档](../design/file-pipeline-design.md)已明确这是兼容行为，对应测试也固定了该行为；本次不将其判为意外代码回归。

但[事件到达运行手册](../runbook/event-driven-arrival.md)的清单迁移段落笼统写清单缺失时文件等待，未明确指出迁移前旧对账记录的例外，也没有提供针对这些记录的识别、核查和处置步骤。升级前需要结合租户、存储桶、受管前缀、`metadata_json.reconciled` 和 `legacy_channel_fallback` 识别候选，并按实际业务确认文件及在途等待；不得批量改写或删除历史记录。是否要求历史记录也必须通过当前清单校验，需要单独决定迁移和兼容方案。

**整改状态（2026-11-10）**：已在[运行手册](../runbook/event-driven-arrival.md#历史对账文件的切换核查)说明例外，并补充迁移前后只读识别、权限与存储身份核对、清单及在途业务确认步骤。未更改历史记录或系统兼容行为；目标环境中的实际记录和升级过程尚未核验。

### 已知部署边界

新前端的 Workflow 编码筛选依赖新 Console API；连接旧版 API 时筛选参数可能被忽略。应先升级并核验所有 Console 实例，再升级前端；目标环境混合版本过程未验证。本轮初始快照中 Go SDK 在确认受阻后会重建消费者；该行为已由下方后续整改取代。

## 验证与限制

- 本轮为静态复核，未编译、未运行测试、未部署或操作运行环境；两仓分别执行 `git diff --check`，退出码均为 0，仅检查已跟踪差异的空白问题。
- 阶段主记录中的历史定向测试和本地业务验证各有其时间、构建与环境边界，不能等同于本轮重跑、完整回归、生产容量或目标环境验收。
- 文件扫描全部边界、全部 Worker 文件和 SDK 传输实现并未逐行复核；混合版本部署及真实故障恢复仍须按阶段主记录另行验收。

## SDK 消费补充复核与整改

以下是整改前后的历史快照；五语言后续实现和最新验证见本节末尾。

- Go：真实隔离 Kafka 的双分区用例先复现一条未确认消息使其他分区在等待窗口内无法处理。适配器已改为同一消费组下分区独立读取、单分区保留重试和原代际提交；提交失败不放行后续消息。无 broker 的定向测试和 Go race 检查通过；修复后两次真实复测均在创建主题后的分区写入阶段超时，未进入业务断言。隔离 Kafka 显示 unhealthy，尚不能认定真实双分区、重分配或断连恢复已验证。
- TypeScript：KafkaJS 2.2.4 在消息处理后检查暂停、下一条前检查 seek，原报告中“暂停后当前批次继续处理更高 offset”的前提与该实现不符。真实隔离 Kafka 的持续未确认及恢复定向测试 1 项通过；未据未复现路径修改 TypeScript。提交异常和重平衡的其他情况不由该用例证明。
- Java、Python、Rust 的未知版本及外租户消息采用分区提交上限后继续消费，可能扩大未提交消息和重投范围。Python、Rust 转交后重新取得分区时旧上限的恢复风险目前仅有源码证据，需单独定向验证；五语言处理节奏尚不完全一致。
- 已修正 Java、Python、Rust 中将消费节奏误写为与 Go / TypeScript 一致的注释；三者本轮没有行为修改。
- 已将 `.playwright-mcp/` 加入忽略规则；原有本地文件保留。

### 五语言统一与验证续记（2026-11-10）

- 五语言均按受阻分区暂缓并重试、其他分区可继续的原则处理未确认消息。Python 撤销时清理对应分区状态；Rust 根据实际分配及 broker 提交位置协调，位点查询或提交失败时先暂停回退再继续处理其他分区。Python、Rust、TypeScript 的最新位置模式在业务处理前固定当前读取位置，不确认当前消息。Java 消费入口等待 CLAIM 结果；无 handler 时先认领再携当前 invocation 回报失败，默认单次 poll 一条以限制串行认领等待的累积。TypeScript 区分全局暂停与分区暂扣，防止前者被分区暂停覆盖。
- 环境：原隔离容器 `systemvalidation5b3a31a5-kafka` 重启恢复健康，保留原数据；另建 `codex-sdk-validation-kafka2-20261110`，宿主回环 `127.0.0.1:39092`，测试主题和容器保留。第一个额外实例因 JVM 堆不足启动失败后保留；第二个调整堆和清理缓冲后启动。主机存在较高交换空间占用，不能把本地耗时作为容量结论。
- Go：`BATCH_SDK_KAFKA_BOOTSTRAP=127.0.0.1:29092 go test -run '^TestKafkaWithheld(DoesNotBlockOtherPartition|BoundedRetry|TransferProgress)$' -count=1 -timeout=180s -v`（`sdk/go/kafka`）在恢复的原 Kafka 上 3/3 通过。额外实例首次双分区测试以 1.5 秒限时失败，同分区重试与转交 2/2 通过；双分区测试等待上限改为 30 秒后，回读受阻和正常分区提交位置通过。该用例验证持续暂扣期间另一分区最终推进，不是延迟验收，也不能单独证明旧实现不会偶然通过；配合此前无 broker 分区隔离及 race 测试。
- Python：`BATCH_SDK_KAFKA_BOOTSTRAP=127.0.0.1:39092 .venv/bin/pytest -q tests/test_kafka_live_integration.py --tb=short`（`sdk/python`）最终 3/3 通过，覆盖未知版本、外租户与双成员转交；前一整轮 2 通过、1 失败，失败项随后单独通过，首轮完整错误未保留。无 broker 定向及共享契约 60 项通过；Ruff、格式和目标 mypy 通过。真实 HTTP 使用平台替身。
- TypeScript：`BATCH_SDK_KAFKA_BOOTSTRAP=127.0.0.1:39092 node --test --experimental-strip-types tests/kafka-withheld-integration.test.ts`（`sdk/typescript`）最终 4/4 通过，覆盖首次最新位置、分区隔离、暂扣重试与转交。更早运行存在首条未被观察、创建主题失败，以及转交用例固定期望 offset 2 而实际为 3；回读主题确认测试生产者发送了重复 `first`，改为基于分区实际末尾定位新消息并限制后续提交后重跑。无 broker 受影响单测 15/15、`npm run build` 通过；运行存在 KafkaJS `TimeoutNegativeWarning`，未作为通过证据忽略。
- Rust：`BATCH_SDK_KAFKA_BOOTSTRAP=127.0.0.1:39092 cargo test --offline --features kafka end_to_end_consume_against_real_broker -- --nocapture`（`sdk/rust`）1/1 通过，覆盖真实双分区；无 broker 的 Kafka 模块定向测试报告 18 项，其中一个真实 broker 测试无地址直接返回，其余包含 mock broker 注入。原 Kafka 的通告地址为 `localhost`，同一真实用例先因优先连接未监听的 IPv6 地址而失败，随后在通告 IPv4 地址的隔离实例通过；不代表该地址兼容问题已修复。Rust 分区转交及提交查询失败的恢复有 mock broker 证据，尚无真实双成员转交证据。
- Java：`./mvnw -o -ntp -pl sdk/java/core -am '-Dtest=TaskDispatcherTest,TaskDispatcherClaimRetryTest,TaskDispatcherP0HardeningTest,TaskDispatcherTenantMismatchTest,KafkaTaskConsumerCommitDecisionTest,KafkaTaskConsumerCapacityPauseTest,KafkaTaskConsumerRebalanceTest,KafkaTaskConsumerCrashTest' -Dsurefire.failIfNoSpecifiedTests=false test` 最终 67/67、0 跳过，日志 `/tmp/java-sdk-poll-handler-regression-20261110.log`；此前 27 项主链回归与其有重叠且运行于最后两项修复之前，不叠加统计。涉及 CLAIM、未注册处理器、分区暂停和转交的首红均已记录，真实 Kafka 心跳与极端自定义超时配置未验证。
- 限制：最新位置模式的首次安全起点提交本身失败且同时发生分区转交时，broker 可能尚无持久起点，该故障组合未实测。目标环境、长时压力和所有网络故障恢复未验证；上述结果不等同完整 CI 或生产验收。

### 真实转交、心跳及首次位点失败续记（2026-11-10）

- 用户决定将五种 SDK 新消费组及无有效已提交位点时的默认起点统一为最早保留位置。Python、Rust、TypeScript 已调整；已有有效位点的消费组仍从该位点恢复。首次位置保存失败时，业务不启动，随后分区转交可从保留区间的最早位置重读。该决定可能重读 Kafka 保留期内的历史派单，必须依靠认领与业务幂等；TypeScript 显式设置 `fromBeginning: false` 仍保留最新位置行为及首次保存失败后转交可能漏读的风险。
- Rust：`BATCH_SDK_KAFKA_BOOTSTRAP=127.0.0.1:39092 cargo test --offline --features kafka real_broker_two_member_partition_transfer -- --nocapture`（`sdk/rust`）1/1 通过，真实 Kafka 两成员间发生分区转交、另一成员提交后原成员重新取得分区并处理；回查 broker 位点。测试早期以旧最新位置及消息写入时序运行曾失败，调整默认值和夹具后通过。Kafka 模块离线定向测试报告 19 项通过，其中 2 项无 broker 时直接返回，不计作真实服务验证；`cargo fmt --check` 通过。
- Java：`BATCH_SDK_KAFKA_BOOTSTRAP=127.0.0.1:39092 ./mvnw -o -ntp -l /tmp/java-sdk-kafka-heartbeat-20261110.log -pl sdk/java/core -am '-Dtest=KafkaTaskConsumerHeartbeatIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test` 1/1 通过；使用真实 Kafka、6 秒会话超时和延迟 9 秒返回的本地 HTTP 认领替身，等待期间消费组保持同一成员且未提前提交，释放后两条任务和 broker 位点推进。初次编译因 Kafka 4.2 已弃用 API 被 Werror 拦截，测试改用现行 API 后通过；目标格式检查通过。该用例不是生产控制面或长时网络故障验证。
- Python：真实 Kafka 中人为注入首次安全位置保存失败并转交时，旧最新位置默认实测跳过首条；改为最早位置后，`BATCH_SDK_KAFKA_BOOTSTRAP=127.0.0.1:39092 .venv/bin/pytest -q tests/test_kafka_live_integration.py --tb=short`（`sdk/python`）4/4 通过。受影响单元与契约测试 58/58、Ruff、格式和目标 mypy 通过；本地平台仍为测试替身。
- TypeScript：同样在真实 Kafka 复现首次保存失败转交漏读，修改默认值后，`BATCH_SDK_KAFKA_BOOTSTRAP=127.0.0.1:39092 node --test --experimental-strip-types tests/kafka-withheld-integration.test.ts`（`sdk/typescript`）5/5 通过，包含显式最新位置的正常转交、默认最早位置的失败转交和分区隔离。受影响单测 15/15、`npm run build` 通过；KafkaJS 仍产生 `TimeoutNegativeWarning`。
- 交付前对 Console API、Orchestrator、V377–V383、五类 Worker 和 core 的高风险生产路径进行补充只读复核，未发现证据充分的新缺陷；未逐行覆盖全部差异。迁移后运行角色须按新增授权脚本配置；实际混合版本升级、目标环境、长时压力与生产故障恢复仍未验证。
- 本地增量 Sonar 未运行：交付准备时 `localhost:9000` 无法连接，运行中的容器也没有 Sonar 服务；该项未执行，不计为通过。
- 正式 PR 高风险操作核查：V380 两处 `DROP` 仅移除热表和归档表旧类别检查约束，之前分别新增扩展约束，V381 独立验证；存量 V376 升级至 V381 的隔离 PostgreSQL 样本已验证两表原有记录、合法新类别和非法类别拒绝。目标大表锁及回退未实测，恢复约束前须确认两表无 `EXPORT_TRIGGER` 或由业务确定数据处置并备份，见[运行手册](../runbook/event-driven-arrival.md#文件类别约束升级及回退)。维护脚本仅 owner 或授权成员可在显式进入维护且无活动租约后按指定范围和上限清理可重建观察记录，默认预览；中断后可续批、恢复旧观察须使用执行前备份。先前隔离库实测了预览、租约拒绝、分批及激活，但未验证目标库和受限角色。高风险清单仅登记这两处约束删除与一处范围受限的维护删除，不改变门禁规则。
- 后端首次提交检查发现增量中文注释、Java 固定键和新增测试命名规范问题；已按原有契约修正，未修改门禁或基线。检查期间 Spotless 格式化了受影响 Java 文件，随后暂存并重新检查；首次钩子还遇到临时 Git 索引锁，未形成提交。
- 阶段计划保留分页用例的历史首红命令及原方法名；该方法现名为 `ImportIngressScannerPaginationTest#scan_shouldRegisterDiscoveredFiles_whenNewKeysKeepArriving`，重放时应使用现名。
- 推送前隔离副本 clean compile 通过；首次推送检查还发现授权清单来源、Mapper SQL 存放、集成测试注入及增量空值判断需要对齐现有门禁，具体复核以最终推送检查为准。V377 的扫描协调状态按周期清理，V383 是扫描游标；V382 的停用入口与历史清单仍参与运行时保护。以上表不应仅为消除推送脚本的宽泛提示而机械增加冷归档，长期审计留存应按业务要求另行确定。
- 对账游标 Mapper SQL 等价迁至 XML 后，`./mvnw -o -ntp -pl batch-test-support -am -Dtest=MyBatisSqlAnnotationConventionTest -Dsurefire.failIfNoSpecifiedTests=false test` 2/2 通过；`./mvnw -o -ntp -pl batch-orchestrator -am '-Dtest=FileGovernanceIntegrationTest#shouldNotOverwriteReconcileProgressFromAnOlderPage' -Dsurefire.failIfNoSpecifiedTests=false test` 使用隔离 PostgreSQL，1/1 通过、0 跳过，覆盖读写、record 映射和过期版本 CAS。授权清单按源码重新生成，仅来源路径变化，授权集合不变。
- 集成测试字段注入改为构造注入后，对 8 个受影响类各运行一个场景，8/8 通过、0 跳过：`./mvnw -o -ntp -pl batch-e2e-tests,batch-orchestrator,batch-worker/dispatch -am '-Dtest=AtomicHttpRetryLayersE2eIT#shouldCountHttpAndJobRetriesWhenServerContinuesToFail,DispatchPipelineE2eIT#shouldResolveApprovedReplayChannel_whenMetadataContainsNonChannelValue,ExportEncryptedArtifactE2eIT#shouldRegisterActualEncryptedObjectAndRecoverBusinessContent,ImportBomPartitionE2eIT#shouldWriteEachBomDelimitedRangeRecordOnce,RuntimeConfigImportDispatchE2eIT#shouldReadImportedWorkflowPolicyWhenEvaluatingRuntime,WorkerReportBatchHttpE2eIT#mixedReports_shouldBatchOverHttpAndCommitIndividually,MultiCalendarCoordinationIntegrationTest#shouldMaterializeFileAndEventExpectationsWhenBusinessDayFirstOpens,DispatchReceiptPollIntegrationTest#shouldKeepPendingUntilReceiverAcknowledges' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false test`，日志 `/tmp/test-constructor-injection-final-20261110.log`。这是指定场景，不是八个类全部用例的验证。
- 空值判断调整后，`BATCH_SDK_KAFKA_BOOTSTRAP=127.0.0.1:39092 ./mvnw -o -ntp -pl batch-worker/import,batch-worker/process,batch-worker/dispatch,batch-worker/atomic,batch-orchestrator,batch-console-api,sdk/java/core -am '-Dtest=ImportScannerPropertiesTest,ImportIngressScannerTest,ImportIngressScannerPaginationTest,FileArrivalChannelNodeValidationTest,SensorStateMachineTest,HttpTaskExecutorTest,DefaultDispatchStageExecutorTest,SqlTransformComputeSqlBuilderTest,KafkaTaskConsumerRebalanceTest,KafkaTaskConsumerHeartbeatIntegrationTest,TaskDispatcherTest' -Dsurefire.failIfNoSpecifiedTests=false test` 共 132 项通过、0 跳过，其中 Kafka 心跳使用真实隔离 broker；日志 `/tmp/empty-checks-affected-tests-20261110.log`。该结果仅覆盖指定类。
- 游标 SQL 迁入 XML 后，通用租户守卫误将物理上没有 `tenant_id` 的 V383 全局存储扫描游标当作租户业务表。按既有同类游标约定，仅将此表登记为系统表并注明依据；`./mvnw -o -ntp -pl batch-orchestrator -am -Dtest=MapperXmlTenantGuardArchTest -Dsurefire.failIfNoSpecifiedTests=false test` 2/2 通过、0 跳过。SQL 的版本 CAS 和 Orchestrator 专属授权不变。
- 测试分组与耗时：4 个新增 E2E 类原先未进入 Full/Staging 显式分片，现分配至现有 3、6、8 号分片，保持文件系统专项分片不变；新增 Java 心跳、TypeScript 分区恢复和 Rust 转交真实 Kafka 测试接入现有主干 SDK 真实服务工作流，不增加 PR 同步慢测试。`bash scripts/ci/check-e2e-shard-coverage.sh` 的 Full 与 Staging 清单均覆盖 58 类；`python3 -m unittest scripts/ci/tests/test_e2e_shard_coverage.py scripts/ci/tests/test_e2e_method_completeness.py scripts/ci/tests/test_sdk_orchestrator_e2e_scripts.py` 25/25 通过，三个变更工作流的 actionlint 通过。本地四类单次耗时各约 60–64 秒，包含独立容器和应用启动；参考两次历史 Full CI 样本选择分片，不能推导远端新增耗时。Ubuntu runner 的 Kafka 连接、新分片墙钟时间以及工作流实际结果待远端验证。
- 推送架构守卫后续发现扫描器深层表达式、测试长构造及查询对象多空占位问题，已提取局部值并沿用查询对象 Builder；`./mvnw -o -ntp -pl batch-console-api,batch-worker/dispatch,batch-worker/import -am '-Dtest=WorkflowRunRequestQueryIntegrationTest,DefaultDispatchStageExecutorTest,ImportIngressScannerTest,ImportIngressScannerPaginationTest' -Dsurefire.failIfNoSpecifiedTests=false test` 30/30 通过、0 跳过，覆盖真实数据库工作流筛选、扫描与分发受影响测试。
- 后端 PR 的 Node 22 SDK 契约任务发现停机排空测试因未完成 Promise 而取消，本地 Node 22.23.4 复现。修复排空等待计时器不保活且结束后及时清理；`npx --yes node@22 --test --experimental-strip-types 'tests/**/*.test.ts'` 和 Node 24 的 `npm test` 均为 173 通过、0 失败、6 跳过；`npm run build`、`npm run format:check` 通过。上述两轮未配置真实 Kafka，相关集成用例跳过；远端任务须在推送后重新确认。

### 正式 PR 门禁修复续记（2026-11-10）

- PR 从草稿转为可审查后，[正式门禁运行](https://github.com/pinpols/file-batch-system/actions/runs/42735321285)发现多个问题：Import 扫描方法 7 个参数触发 PMD；直接读取配置键和平台库 JDBC 边界不符合现行约定；响应映射测试将新增内部执行尝试标识误当作公开响应字段；高风险操作清单与本次实际脚本、迁移尚未同步。Trigger/Worker 集成分片以及 Import/Export/Atomic 分片在该次运行中通过，其余失败项不能视为通过。
- 扫描方法按原执行顺序在登记方法入口解析业务日，删除重复传参。`./mvnw -o -ntp -pl batch-worker/import -am '-Dtest=ImportIngressScannerTest,ImportIngressScannerPaginationTest' -Dsurefire.failIfNoSpecifiedTests=false test pmd:check spotless:check` 的测试 16/16 和 PMD 通过，命令最后因一处换行格式失败；按提示修正后 `./mvnw -o -ntp -pl batch-worker/import -am spotless:check` 通过。该证据早于同一文件后续类型化配置接线，不代表最终提交的全量检查。
- 入口存储身份改用既有类型化配置，保留 namespace、后端、bucket 和 S3 endpoint／文件系统绝对路径的排序、标准化与摘要编码。扫描归属查询和扫描持久化 SQL 迁入平台库 MyBatis Mapper；ACTIVE 范围仅使用启用绑定，RETIRED 范围与禁用绑定仍参与对账保护。授权清单重新生成，实际角色权限和授权 SQL 未变化，`python3 scripts/db/generate-platform-worker-grants.py` 通过。
- Orchestrator 定向测试首轮发现测试夹具用 Mockito 模拟 final 身份对象失败，且受限测试应用未导入新增公共组件及 Mapper。单元夹具改用真实类型化配置，测试应用按生产装配补齐 common 组件、属性与 Mapper 扫描；生产 `BatchOrchestratorApplication` 原已覆盖这些包。重跑 `./mvnw -o -ntp -pl batch-orchestrator -am '-Dtest=ImportIngressOwnershipTest,FileArrivalSensorPolicyTest,FileGovernanceRepositoryStatusTest,FileArrivalPatternIntegrationTest,FileGovernanceIntegrationTest#shouldProtectIngressAndManifestWithoutStarvingOrdinaryOrphans' -Dsurefire.failIfNoSpecifiedTests=false test` 共 27/27、0 跳过：其中 3 项验证身份与范围、14 项为 Sensor 和仓储单测，9 项使用真实 PostgreSQL 验证传感器，1 项通过真实对象存储及数据库核对入口范围和范围外清单保护。日志 `/tmp/pr1339-orchestrator-ownership-final-20261110.log`。
- Spring 扫描事务首轮因共用归属仓储声明为 `final` 而无法创建持久化异常转换代理；移除 `final` 后执行 `./mvnw -o -ntp -pl batch-worker/import,batch-console-api -am '-Dtest=ImportIngressSpringTransactionIntegrationTest,ImportIngressSqliteTransactionIntegrationTest,JdbcTemplateBoundaryGuardTest' -Dsurefire.failIfNoSpecifiedTests=false test`，扫描平台事务 1/1、SQLite 发件箱隔离类 2/2、JDBC 边界守卫 1/1 通过，0 跳过。日志 `/tmp/pr1339-mybatis-migration-retest.log`；真实 PG 扫描持久化测试单独运行。
- 首轮 Import Mapper 测试执行 `./mvnw -o -ntp -pl batch-worker/import,batch-orchestrator,batch-console-api -am '-Dtest=ImportIngressScanPersistenceIntegrationTest,ImportIngressSpringTransactionIntegrationTest,ImportIngressSqliteTransactionIntegrationTest,ImportIngressScannerTest,ImportIngressScannerPaginationTest,WorkerImportMapperXmlTenantGuardArchTest,ControllerResponseMappingTest,JdbcTemplateBoundaryGuardTest' -Dsurefire.failIfNoSpecifiedTests=false test`，日志 `/tmp/pr1339-mybatis-migration-first.log` 显示 PostgreSQL 持久进度与围栏类 37/37、分页类 2/2、扫描单测 14/14、Import Mapper 租户守卫 2/2 通过，0 跳过；该次 Maven 运行因同一批后续 Spring/SQLite 测试遇到上述 `final` 代理错误而整体失败，不能把该次运行记为整体通过。上述具体类的通过证据均早于移除仓储 `final`。
- 正式 PR 的编排差异覆盖检查暴露现有文件治理及文件到达集成测试未进入 PR 选测。编排补充片在原有 Maven 组复用两类中六个方法，覆盖受管入口保护、失败页重试、持续新增对象时回扫、文件准入、渠道匹配及历史回退；未改变覆盖门槛或排除范围。`./mvnw -o -ntp -pl batch-orchestrator -am '-Dtest=FileGovernanceIntegrationTest#shouldProtectIngressAndManifestWithoutStarvingOrdinaryOrphans+shouldRetryFailedReconcilePageBeforeAdvancing+shouldRevisitEarlierOrphansWhileObjectsKeepArriving,FileArrivalPatternIntegrationTest#shouldWaitForScannerAcceptanceOfExistingReconciledFileInOwnedScope+shouldMatchTrustedChannelWithinTenant+shouldMarkLegacyChannelFallback' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.destFile=/tmp/pr1339-ownership-coverage-unique.exec -Djacoco.append=true test` 在隔离 PostgreSQL、Kafka、MinIO、Valkey 下 6/6 通过、0 跳过，Maven 总耗时 1 分 03 秒；日志 `/tmp/pr1339-ownership-coverage-6.log`。单次耗时包括测试上下文启动，不能作为远端增量耗时。JaCoCo 生成成功；与历史 CI 报告混合计算只能用于初步估计，最终覆盖率和分片耗时仍须以同一版本的远端 CI 为准。路由自测 `python3 -m unittest scripts.ci.tests.test_pr_coverage_integration` 为 7/7 通过。
- 针对增量覆盖缺口，补充公共归属仓储的范围与绑定组装、退休范围保护，以及 Java SDK 慢认领中断和停机期间认领完成的业务测试；复用任务执行开始与 Dispatch 结果测试。`./mvnw -o -ntp -pl batch-common,sdk/java/core,batch-orchestrator,batch-worker/dispatch -am '-Dtest=ImportIngressOwnershipRepositoryTest,TaskDispatcherClaimRetryTest,DefaultTaskAssignmentExecutionStartTest,DispatchStageOutcomeTest' -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.destFile=/tmp/pr1339-isolated-coverage/targeted.exec -Djacoco.append=true test` 共 37/37、0 跳过，使用新建独立 exec，未覆盖既有共享 JaCoCo 数据；日志 `/tmp/pr1339-isolated-coverage/run.log`。
- 只读下载同一生产源码 HEAD `6930aee63b` 的 [PR CI 覆盖产物](https://github.com/pinpols/file-batch-system/actions/runs/42739168655)，按仓库 `check-diff-coverage.py` 逐源文件及行号 OR 合并：旧 A 分片为 122/206，与前述独立测试及文件治理 6 个场景数据合并后估计 182/206（88.3%）；旧 B1 分片为 127/169，叠加独立测试数据后估计 158/169（93.5%）。独立 exec 以现有 JaCoCo 0.8.16 和各模块 class 单独导出 XML，并检查 class ID 匹配。该估算不改变门槛或排除范围；后续源码格式、行号、测试选择或运行环境变化可能造成偏差，不能替代新提交的远端覆盖结果。
- 上述为本地定向检查；重新推送后的正式 PR 门禁、最终完整差异以及目标环境部署尚待验证。扫描平台库使用 PostgreSQL，SQLite 验证范围是独立回报发件箱的数据源与事务隔离，不表示扫描库支持 SQLite。
