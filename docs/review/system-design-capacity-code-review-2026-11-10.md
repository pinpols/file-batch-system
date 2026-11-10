# 系统设计与容量整改代码复核（2026-11-10）

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
- 后端首次提交检查发现增量中文注释、Java 固定键和新增测试命名规范问题；已按原有契约修正，未修改门禁或基线。检查期间 Spotless 格式化了受影响 Java 文件，随后暂存并重新检查；首次钩子还遇到临时 Git 索引锁，未形成提交。
- 阶段计划保留分页用例的历史首红命令及原方法名；该方法现名为 `ImportIngressScannerPaginationTest#scan_shouldRegisterDiscoveredFiles_whenNewKeysKeepArriving`，重放时应使用现名。
- 推送前隔离副本 clean compile 通过；首次推送检查还发现授权清单来源、Mapper SQL 存放、集成测试注入及增量空值判断需要对齐现有门禁，具体复核以最终推送检查为准。V377 的扫描协调状态按周期清理，V383 是扫描游标；V382 的停用入口与历史清单仍参与运行时保护。以上表不应仅为消除推送脚本的宽泛提示而机械增加冷归档，长期审计留存应按业务要求另行确定。
- 对账游标 Mapper SQL 等价迁至 XML 后，`./mvnw -o -ntp -pl batch-test-support -am -Dtest=MyBatisSqlAnnotationConventionTest -Dsurefire.failIfNoSpecifiedTests=false test` 2/2 通过；`./mvnw -o -ntp -pl batch-orchestrator -am '-Dtest=FileGovernanceIntegrationTest#shouldNotOverwriteReconcileProgressFromAnOlderPage' -Dsurefire.failIfNoSpecifiedTests=false test` 使用隔离 PostgreSQL，1/1 通过、0 跳过，覆盖读写、record 映射和过期版本 CAS。授权清单按源码重新生成，仅来源路径变化，授权集合不变。
- 集成测试字段注入改为构造注入后，对 8 个受影响类各运行一个场景，8/8 通过、0 跳过：`./mvnw -o -ntp -pl batch-e2e-tests,batch-orchestrator,batch-worker/dispatch -am '-Dtest=AtomicHttpRetryLayersE2eIT#shouldCountHttpAndJobRetriesWhenServerContinuesToFail,DispatchPipelineE2eIT#shouldResolveApprovedReplayChannel_whenMetadataContainsNonChannelValue,ExportEncryptedArtifactE2eIT#shouldRegisterActualEncryptedObjectAndRecoverBusinessContent,ImportBomPartitionE2eIT#shouldWriteEachBomDelimitedRangeRecordOnce,RuntimeConfigImportDispatchE2eIT#shouldReadImportedWorkflowPolicyWhenEvaluatingRuntime,WorkerReportBatchHttpE2eIT#mixedReports_shouldBatchOverHttpAndCommitIndividually,MultiCalendarCoordinationIntegrationTest#shouldMaterializeFileAndEventExpectationsWhenBusinessDayFirstOpens,DispatchReceiptPollIntegrationTest#shouldKeepPendingUntilReceiverAcknowledges' -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false test`，日志 `/tmp/test-constructor-injection-final-20261110.log`。这是指定场景，不是八个类全部用例的验证。
- 空值判断调整后，`BATCH_SDK_KAFKA_BOOTSTRAP=127.0.0.1:39092 ./mvnw -o -ntp -pl batch-worker/import,batch-worker/process,batch-worker/dispatch,batch-worker/atomic,batch-orchestrator,batch-console-api,sdk/java/core -am '-Dtest=ImportScannerPropertiesTest,ImportIngressScannerTest,ImportIngressScannerPaginationTest,FileArrivalChannelNodeValidationTest,SensorStateMachineTest,HttpTaskExecutorTest,DefaultDispatchStageExecutorTest,SqlTransformComputeSqlBuilderTest,KafkaTaskConsumerRebalanceTest,KafkaTaskConsumerHeartbeatIntegrationTest,TaskDispatcherTest' -Dsurefire.failIfNoSpecifiedTests=false test` 共 132 项通过、0 跳过，其中 Kafka 心跳使用真实隔离 broker；日志 `/tmp/empty-checks-affected-tests-20261110.log`。该结果仅覆盖指定类。
