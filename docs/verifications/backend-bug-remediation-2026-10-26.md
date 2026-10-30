# 后端 Bug 运行复现与整改验证（2026-10-26）

## 结果与范围

在 `fix/wdi-current` 专用工作树完成[本轮审查](../review/backend-bug-review-2026-10-26.md)的 9 项问题修复。
HEAD 保持 `f3d5e400d3b5bd737124b57cf0a38e685432aab2`, 所有变更留在原工作树, 未暂存、提交或推送。
没有修改 main 或冻结的 `feature/workflow-daily-integration` 工作树。

本记录证明本地修复与定向验证, 不代表全工程测试、发布、性能容量或生产验收。所有环境操作只涉及
测试自行创建的容器、随机端口与场景数据; 未访问现有业务数据库、Kafka 或真实通知渠道。

## 逐项复现与修复

| ID | 旧实现运行证据 | 修复与最终证据 |
|---|---|---|
| BUG-20261026-01 | 最后租约移除后, 半开检查仍保持 OPEN, 新用例失败 | 空租约释放续租熔断, 交由 Outbox 自身退避探测; 单测及真实 SQLite/本地 HTTP 的失败落库→熔断→移除最后租约→补报清理通过 |
| BUG-20261026-02 | Go 背压后三条输入中 deferred/later 未完整回报, 用例超时 | 原地保留已拉取记录, 容量低水位后优先处理, 停机可退出; 生命周期与真实 Kafka 单并发三消息回归通过, race 无报告 |
| BUG-20261026-03 | Python 在 CLAIM 挂起时已返回 ACCEPTED, CLAIM 全失败仍返回 ACCEPTED | 消息提交等待认领屏障, 并发重复消息共享结果, 业务仍异步; 失败返回 RETRY_LATER, 真实消费循环 seek 原 offset 且不 commit; 定向与 SDK 回归通过 |
| BUG-20261026-04 | 真实 PostgreSQL 中按时完成归档后生成违约候选 | 候选按来源请求联合冷热运行, 热事实优先; 按时归档无告警、冷表迟开始/迟完成仍有证据、dry-run 排除通过 |
| BUG-20261026-05 | 双 Release 渲染显示两个 monitor 都匹配两套 Service | 应用/Collector 服务选择器增加 instance; 两项真实 Chart 渲染回归确认只选择本 Release, 范围标签与 Console 一致 |
| BUG-20261026-06 | 接受连接后延迟回包, 原恢复调用超出 2 秒测试预算 | 复用 Orchestrator 受控 HTTP 传输, 默认连接 2s/请求总预算 5s; 场景缩至 200ms 后有界失败, 下一次成功, 五类状态处置回归通过 |
| BUG-20261026-07 | 真实 PostgreSQL 101 条等待, 两轮都只检查前 100 条, released=0 | PENDING 按 updated_at 探测水位轮转; 第二轮第 101 条恢复, V359 部分索引支持新顺序 |
| BUG-20261026-08 | 禁用渠道仍进入发送路径, 拒发断言失败 | 编排入口拒发停用渠道, delivered=false/status=DISABLED, 不写成功回执; HTTP 接收正常结束主动停用, 缺渠道/真实失败仍 503 重试 |
| BUG-20261026-09 | 实际配置包 apply 返回更新, 数据库 description 仍为空 | description 纳入原 CAS 更新; 三条生产调用保留说明来源, 集成测试验证说明、版本递增与更新统计一致 |

上述先失败证据保存在本机临时日志: `wdi-bug-red-java.log`、`wdi-bug-red-remaining.log`、
`wdi-bug-red-console.log`、`wdi-bug-red-go.log`、`wdi-bug-red-python.log`。日志不是仓库交付物,
测试方法与源码保留在仓库, 可按下列命令重现。

## 实际验证

### Java 与真实依赖

- 定向回归 **87 项通过, 0 失败/错误/跳过**: Orchestrator 29、Worker Core 12、Console 46。
- readiness 双服务 E2E **2 项通过**: 真实 PostgreSQL、双 HTTP 上下文, 覆盖持久移交/恢复和超窗终结。
- Console 集成测试在隔离库完成 Flyway 到 **V359** 的迁移, 包含非事务并发索引。
- SQLite Outbox 回归使用真实本地 SQLite 和本地 HTTP 收件端, 不以 Mock 的发送计数代替记录清理。

```sh
./mvnw -ntp -fae -pl batch-orchestrator,batch-console-api -am \
  -Dtest=WorkerTaskLeaseRenewerTest,WorkerTaskLeaseRenewerStaleSnapshotTest,WorkerReportOutboxCoordinatorTest,WorkflowMonitoringMapperIntegrationTest,ReadinessWaitFairnessIntegrationTest,TriggerReadinessRecoveryClientTest,ReadinessWaitServiceTest,TriggerReadinessRecoveryAddressTest,AlertmanagerNotifyServiceTest,AlertmanagerNotifyControllerTest,WorkflowDefinitionWriteContractIntegrationTest,TenantConfigPackageExcelMonitoringPolicyApplyTest,WorkflowDefinitionWriteParamsTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -ntp -pl batch-e2e-tests -am -Dtest=ReadinessHandoverE2eIT \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

### SDK 与真实 Kafka

- Go 根 SDK `go test -race ./... -count=1` 的 client/contract/protocol 三包通过。
- Go 独立 Kafka 模块 `go test -race ./... -count=1 -timeout 120s` 在任务独占的 Apache Kafka 4.1.2 上通过,
  包含新背压回归、原适配器集成和提交上限用例。
- Python `uv run --offline pytest tests -q`: **502 通过、2 跳过**。跳过 Happy Eyeballs 外部矩阵和默认未配置的 live Kafka。
  同轮在独占 Kafka 上另行运行 `tests/test_kafka_live_integration.py`, **1 项通过**, 因而 live Kafka 缺口已在该本地层补验;
  外部 Happy Eyeballs 矩阵仍未验证。
- Python mypy: **68 个源文件无问题**; 受影响文件 ruff 检查与格式检查通过。
- Kafka 用回环随机端口及唯一容器名, 结束后确认本任务容器删除成功。未使用现有 Kafka。

```sh
# 在 sdk/go 中运行
 go test -race ./... -count=1
# 在 sdk/go/kafka 中, bootstrap 必须指向测试自建隔离 broker
 BATCH_SDK_KAFKA_BOOTSTRAP="$ISOLATED_KAFKA_BOOTSTRAP" go test -race ./... -count=1 -timeout 120s
# 在 sdk/python 中运行
 uv run --offline pytest tests -q
 uv run --offline mypy src
 BATCH_SDK_KAFKA_BOOTSTRAP="$ISOLATED_KAFKA_BOOTSTRAP" uv run --offline pytest tests/test_kafka_live_integration.py -q
```

### Helm 与静态检查

`python3 -m unittest scripts/ci/tests/test_helm_service_monitor_scope.py`: **2 项通过**。
该测试渲染真实 Chart 并比较实际 Service 选择集合, 不连接 Kubernetes。

配置默认值、Helm 环境映射、可观测性契约、Java 显式类型与测试约定检查通过。
Java 格式化只针对本任务涉及的文件, 不进行全仓格式改写。
其余收尾文档/迁移检查结果见本记录末尾。

## 验证中修正的夹具与工具问题

- 新增 Java 测试的泛型 varargs 警告被 `-Werror` 正确拦截, 改为分阶段 stub 后继续, 未关闭编译检查。
- Python 原 schema 测试以瞬间 in-flight 数证明通过, 在提交屏障后该时机不稳定; 改为断言明确的处置与实际 CLAIM 请求。
  新 Future 局部变量重名导致 mypy 报错, 已改名并重跑通过。
- Kafka 单端口开放不能证明控制器就绪, 且跨测试默认 Writer 元数据缓存会看不到新建主题。
  验证准备改为真实元数据/控制器探测, 夹具等待分区 leader 并为每个 Writer 使用独立 transport/缓存与关闭责任,
  修正后真实集成通过; 未通过扩大业务重试或放宽断言销项。
- 本机全局 Squawk 缺失可选二进制。按当前锁文件在工作树 `.github/ci-tools` 安装 2.65.0,
  使用本地入口验证, 不修改全局安装或 package-lock。全局入口仍不作为有效工具证据。

## 剩余边界

未执行全工程业务测试、完整 SDK 外部网络矩阵、真实 Kubernetes 采集/通知、真实渠道收件、生产规模查询计划
或发布部署。上述均不影响本轮 9 项本地修复结论, 也不被本轮通过结果替代。

当前状态回写 [TODO Master](../plans/todo-master.md); 审查快照保留旧实现证据, 不用修复后结果改写历史。


## 收尾核对

以下检查实际完成并通过:

- 当前工作树锁定的 `.github/ci-tools/node_modules/.bin/squawk` 为 **2.65.0**;
  对 `db/migration/V359__readiness_wait_probe_rotation.sql` 扫描 **0 问题**。
- 本任务文件的 `spotless:check` 通过; `check-java-readability.py`、`check-test-conventions.py` 通过。
- `check-config-defaults-sync.py --check`、`check-helm-env-sync.py`、`check-observability-contract.py` 通过。
- `check-readiness-doc-sync.py`、`check-script-governance.py`、`check-code-doc-references.py` 通过;
  Console OpenAPI 路由检查仍为 **431 条一致**。
- `check-docs-structure.py`、`check-doc-timestamp-policy.py`、`check-changelog-sync.py` 及受影响文件差异空白检查通过。
- 从实际 Surefire XML 汇总 14 个相关测试类, **89 项、0 失败/错误/跳过**, 包含前述 87 项和双服务 2 项。
- 最终 Python mypy、ruff 和 502 项回归通过; 2 项默认门控跳过的区别及另行完成的 Kafka 冒烟见上文。
- 本任务前缀 Kafka 容器清单为空; 仓库工具 package.json/package-lock 未变化, 安装只补忽略的依赖目录。
- HEAD 与分支未变化; Git 暂存差异 SHA-256 与审查收尾快照一致, 没有重新暂存用户已有变更。

最终本机证据日志包括 `wdi-bug-final-java.log`、`wdi-bug-readiness-e2e.log`、
`wdi-bug-go-all.log`、`wdi-bug-kafka-live.log`、`wdi-bug-python-all.log` 和 `wdi-bug-final-spotless.log`。

## 同轮前端 Bug 治理衔接

配对前端继续在 `fix/wdi-current` 工作树完成[前端 Bug 审查与修复验证](../../../batch-console/docs/reports/2026-10-26-frontend-bug-remediation.md)：维护状态回退、租户切换后的错误操作目标/迟到响应、订阅字段、配置包文件身份及自助触发器候选。前端记录保留其单元/组件回归、类型构建、浏览器夹具和未验边界：本轮前端 206 个测试文件、1,176 个用例通过，生产构建与两组隔离浏览器回归通过；这些数字是本日快照。规约门禁不计入 Bug 成果。

本节仅追加前后端证据入口，没有重新执行或改写上述后端运行结果；前端夹具验证不替代本报告的真实依赖证据，也不代表真实通知或生产维护验收。

## 全工程续审：补偿与死信

本批新增 BUG-20261026-10/11 两项本地修复，范围和根因见[审查续篇](../review/backend-bug-review-2026-10-26.md#全工程续审补偿与死信事务链)。沿用相同双仓工作树和 HEAD，未提交；没有修改 Flyway 或重建现有业务库。

- `shouldRollbackDeadLetterReplay_whenCompensationSuccessRecordingFails`：在真实 PostgreSQL 中只注入补偿 SUCCESS 记账失败。首红为命令 FAILED、任务/步骤/分区版本已变化；修复后命令失败证据保留，业务版本、Outbox、死信状态与计数均回滚。
- `shouldCommitDeadLetterReplayWithCompensationSuccess`：正常补偿的命令 SUCCESS、死信 SUCCESS 与 Outbox 增量共同提交。
- `shouldKeepSuccessfulReplayTerminal_whenStaleGiveUpArrives`：成功重放后模拟旧扫描更新；首红实际影响一行并覆盖成功状态，修复后影响零行且成功状态/计数保留。
- `shouldFenceConcurrentVerifiedReplaysAcrossTwoDeadLettersForSameSourceTask`：同一未知外部结果源任务的两条死信，通过就绪/启动屏障并发调用公共提交入口；一成功一冲突，只创建一个新请求。验证的是核实授权与重发请求唯一性，不宣称外部业务已重新执行。

测试集中在 [RetryExecutionClosureIntegrationTest](../../batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/integration/RetryExecutionClosureIntegrationTest.java)。最终命令：

```sh
./mvnw -ntp -pl batch-orchestrator -am \
  -Dtest=RetryExecutionClosureIntegrationTest,DefaultCompensationServiceTest,DeadLetterAutoRetryTest,CompensationFailureRecoveryIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

**69 项通过，0 失败、错误或跳过**。包括真实 PostgreSQL 集成、原直接重放失败记账、预算回退及补偿恢复，不把之前单独执行的同一用例重复累加。测试使用自行创建的容器和场景租户，未使用现有业务数据库。

本机首红日志为 `/private/tmp/wdi-bug-continuation-back-red.log`、`/private/tmp/wdi-bug-continuation-terminal-red.log`；最终关联回归为 `/private/tmp/wdi-bug-continuation-back-suite2.log`，另有单独源任务 CAS 验证。一次 Spotless 文件参数将 glob 当正则导致失败，改为精确文件正则后执行成功，未放宽格式规则。源文件变化后既有批次证据需按摘要复核；本轮不代表全工程 Bug 审查、全 reactor 测试或目标部署验收完成。

## 全工程续审：Outbox 写权限

BUG-20261026-12 已通过 Spring `@EnableMethodSecurity` 的真实代理复现并修复。新增参数化用例分别设置四类正式角色，调用清理与重投；拒绝时断言应用服务零调用，允许时核对原租户和事件 ID。该层没有连接生产数据库，也没有用字符串检查授权注解替代实际鉴权。

```sh
./mvnw -ntp -pl batch-console-api -am \
  -Dtest=ConsoleOpsControllerTest,DefaultConsoleOutboxOpsApplicationServiceTest,ConsoleSecurityConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

**13 项通过，0 失败、错误或跳过**；与本轮补偿/死信的 69 项为不同测试类，合计 82 项当前定向证据。不代表整个后端测试通过。首红 `/private/tmp/wdi-bug-outbox-auth-red.log`：6 项中 1 个 AUDITOR 参数用例失败；通过日志为 `wdi-bug-outbox-auth-green.log`。未进行真实登录会话/CSRF/部署联验，现有租户守卫和代理写入责任保留。

## 续审静态检查与工作区核对

- 前端 `npm run lint:check`、`npm run gen:api:check`、`npm run check:docs`、`npm run check:changelog` 通过；Java 本轮文件 `spotless:check`、显式类型/配置代理检查通过。后端文档结构、路径引用、日期命名和 Changelog 同步通过。
- 全局 `python3 scripts/ci/check-test-conventions.py --check-baseline docs/governance/test-conventions-baseline.txt` **失败**：同期新增、非本轮修改的 `RelatedFileReferenceValidatorTest.java` 有 3 个测试方法不符合命名约定；初始工作区快照中没有该文件。本轮没有改写该文件或放宽基线。直接使用同一守卫的 `scan_file` 核对本轮修改的 3 份测试，缺口为 0。此项为工程约定失败，不计业务 Bug，也不写为整体门禁通过。
- 已登记审查文件的 SHA-256 与当前源码一致；双仓 HEAD 和暂存差异均与本轮开始一致。本次源码、测试和文档继续留在原专用工作树，未提交、未发布。
- 自建浏览器会话关闭，核对命令和工作目录后仅停止本任务 15174 端口的 Vite；真实 PostgreSQL 仅使用测试隔离容器，不清理其他任务资源。

## 全工程续审：Worker 批次退出与遥测输入

本段承接 BUG-20261026-13/14，未改 main、冻结分支或数据库迁移。原始失败与修复分别保留：

- `DefaultTaskExecutionWrapperClaimLeaseTest.shouldReleaseQueuedClaims_whenFatalErrorAbortsBatch` 使用真实本地执行池和注册表，以 LinkageError 模拟业务类链接失败；原错误确实传播，但旧实现仍留下一个未启动租约。修复后未启动项清理，不能把致命错误包装成普通失败或继续执行业务。
- `ActiveTaskLeaseRegistryFreshnessTest.shouldOnlyRemoveMatchingQueuedClaim_whenBatchExits` 验证其他 invocation 和已开始项保留，只移除本次未启动项。
- `ConsoleTelemetryControllerTest.shouldRejectNullEvent_whenListContainsNull` 的两个输入先返回 500，修复后返回 400；没有更改合法 JSON 字段或错误处理策略。

```sh
./mvnw -ntp -pl batch-worker/core -am \
  -Dtest=DefaultTaskExecutionWrapperClaimLeaseTest,ActiveTaskLeaseRegistryFreshnessTest,ActiveTaskLeaseRegistryTest,TaskDispatchExecutorTest,DefaultTaskExecutionWrapperTest,TaskConsumerBatchExecutionCoordinatorTest,QueuedBatchClaimLeaseFenceTest,WorkerTaskLeaseRenewerTest,WorkerTaskLeaseRenewerStaleSnapshotTest,TaskReportBatcherTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -ntp -pl batch-console-api -am \
  -Dtest=ConsoleTelemetryControllerTest,TelemetryWritePathIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -ntp -DskipTests test
```

Worker **78 项通过**；遥测 **13 项通过**，其中 6 项为真实 PostgreSQL 的集成路径；各命令均为 0 失败/错误/跳过。整个后端 **17 模块生产与测试源码编译通过**，最后一条命令跳过测试执行，不能记为全 reactor 测试通过。本段没有运行真实 Kafka 故障回收、生产部署或容量验收。

用户要求完成后，上段单列的 3 个测试命名缺口已修正；只改方法名、保留测试体。`RelatedFileReferenceValidatorTest` **3 项通过**，全局测试命名守卫现为基线 0、当前 0、新增 0；Java 可读性守卫通过。本段合计 94 项不同用例，不将重复执行或前段相同类证据相加。

原始日志：`/private/tmp/wdi-bug-completion-batch-fatal-red.log`、`worker-green2.log`、`telemetry-red.log`、`telemetry-green.log`、`reactor-compile.log`、`naming-test.log` 和 `test-gate.log`，后六者均使用相同 `wdi-bug-completion-` 前缀。长期测试源和本记录保留可复核方法，临时日志不保证跨机器存在。


## 本段统一检查及共享工作树变化

- 前端最新全量 1,232 项、生产构建、lint、API 漂移、当前文档和 Changelog 检查通过；后端本次文件 Spotless、Java 可读性、测试命名、文档结构/引用/日期/Changelog 检查通过。未改基线、白名单或跳过有效错误。
- 双仓 HEAD 仍为本段开始版本；本任务没有执行暂存、提交或发布，但发现暂存区存在同期变化，保留该状态，不能再将“暂存差异与开始一致”当作当前结论。
- Worker 消费协调器在最初验证后发生同期日志脱敏变化；已复读新源码并重跑 78 项全部通过，更新其内容摘要。此轮重复执行不增加用例总数。前端已登记审查文件无内容漂移。
- 日期记录和覆盖清单分别保留已确认问题关闭、源文件覆盖与外部环境未验状态，整体逐文件目标仍未完成。

## 全工程续审：夏令时回拨业务日

BUG-20261026-15 的首红 `/private/tmp/wdi-bug-completion-calendar-red.log`：9 项中 1 项失败；回拨后的同一天返回 2026-10-31，预期为 2026-11-01。修复使用时区规则计算实际日切时间点，保留首次重叠日切及跳时缺口边界。

```sh
./mvnw -ntp -pl batch-trigger -am \
  -Dtest=CalendarBizDateResolverTest,ReadinessWaitSweeperTest,ReadinessRecoveryReproTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

`/private/tmp/wdi-bug-completion-calendar-green.log`：**14 项通过，0 失败、错误或跳过**。包含真实 PostgreSQL readiness 冻结版本与终态 occurrence 的 2 项集成验证；日历本身用固定 Instant 精确验证，不等待真实时钟或声称 Quartz 实时跨 DST 验收。此次在先前 17 模块编译之后发生，相关上游与 Trigger 已由上述命令重新编译；不把旧全 reactor 编译证据当作全部现有源码的最终快照。

## 全工程续审：Workflow 空元素与调度关联回归

BUG-20261026-16 的 `/private/tmp/wdi-bug-completion-workflow-null-red.log` 保留创建 HTTP 的两个 500 首红。HTTP fixture 复用正式 Controller/异常处理和真实 `WorkflowDagValidator`，替代服务只接入真实拓扑校验，不假造数据库写入；修复后请求在校验阶段被拒绝，服务无调用。

```sh
./mvnw -ntp -pl batch-console-api,batch-trigger -am \
  -Dtest=ConsoleWorkflowVersionsControllerTest,ConsoleWorkflowDefinitionControllerValidationTest,WorkflowDagValidatorTest,WorkflowDefinitionMutationValidationTest,WorkflowDefinitionWriteContractIntegrationTest,CalendarBizDateResolverTest,ReadinessWaitSweeperTest,ReadinessRecoveryReproTest,TriggerCalendarDefinitionProviderTest,TriggerOutboxReleaseBudgetTest,TriggerOutboxReleaseGovernorTest,TriggerOutboxRelayTest,TriggerLaunchLagMonitorTest,TriggerApiKeyVerifierTest,QuartzScheduledFireEnumeratorTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

`/private/tmp/wdi-bug-completion-workflow-scheduler-green.log`：**Trigger 53 项 + Console 48 项 = 101 项通过，0 失败/错误/跳过**。包括真实 PostgreSQL Workflow 字段/租户/版本写入契约 4 项，以及 readiness 冻结版本/终态恢复 2 项。包含前段 Calendar 的 14 项，不重复计新增用例。Relay、API Key 和 Quartz 枚举器的这批验证为单元层，不能写成真实 Kafka/定时回调验收。测试范围内生产与测试源码已重新编译。

当前日期审查还核对了 Relay 抢占/回写/重试与有界预算、Kafka lag 采样及客户端关闭责任；首轮代码中误贴的两个类注释已按真实职责修正，不将注释改正计业务缺陷。字段映射草案经 `toCommandOrNull` 传入后，IMPORT/EXPORT 两条草案生成器都会对空字段给出 warning 并跳过；作业依赖规则已有显式空元素拒绝；没有仅按 `List<@Valid>` 模式批量加注解。


## 本段最终验证快照

- 前端 `npm run test:unit`：**217 文件、1,289 用例通过，0 失败/跳过**；`npm run build` 通过（应用/测试类型、i18n、Vite/PWA）。最终日志 `/private/tmp/wdi-bug-completion-front-unit-final7.log`、`front-build-final7.log`。较早 1,283/1,287 快照被此结果替代，不累加重复执行。
- 认证测试夹具曾缺少登录适配器返回类型的必填 token，导致构建类型失败；补齐与邻近 fixture 一致的测试值后，全量回归和构建重新通过，没有放宽检查或修改登录业务以迁就测试。
- `npm run lint:check`、当前文档、Changelog 通过（`front-*-final6.log`）；API 漂移在同期生成类型未完成格式化时失败，保留首红，待生成结束后 `npm run gen:api:check` 通过（`front-api-final7.log`）。本任务没有覆盖或提交同期生成文件。首次核对时无随后变化；之后共享工作树的 dashboard、SLA 展示及 OpsSummary 共 5 份来源又有同期变动，后续补验单独记录，不再将较早快照视为这些新来源的证据。
- 后端最新 `./mvnw -ntp -DskipTests test`：**17 模块生产与测试源码编译通过**（`reactor-compile-final3.log`）；此命令跳过测试执行。Worker 78、遥测 13、Workflow/调度 101 与前段事务/权限结果保留各自命令和范围，不误写成全 reactor 测试通过。
- 双仓 HEAD 仍为本段既有版本；本任务未创建分支/工作树，未暂存、提交或发布。共享工作树有同期暂存、源码、契约及生成文件变化，均保留；新增入口纳入待审，不用先前通过快照覆盖新来源。
- 本段最后未再次启动真实浏览器、生产服务、Kafka 故障注入或部署验收。组件/API 替身、Store、真实 PostgreSQL 与编译证据分别标注；本报告列出的已确认项本地修复关闭，不代表全工程逐文件审查关闭。


### 同期 SLA 来源的后续验证

共享工作树后续改变了 dashboard/SLA 展示/运维汇总来源。保留这些改动后重新执行全量前端回归：**218 文件、1,290 项通过，0 失败/跳过**，生产构建通过。日志 `/private/tmp/wdi-bug-completion-front-unit-final9.log`、`front-build-final9.log`；这是晚于 1,289 快照的实际结果，不按新增文件数推算用例数。相关来源仍按审查范围保留待审，测试通过不将其语义审查销项。


### 共享构建产物的并发失效

全模块编译后同期调整了 `ConsoleSlaReportResponse`，补验 Console 时测试编译一度报告 AI、审批、RBAC 等大量现有包缺失（`/private/tmp/wdi-bug-completion-console-compile-final4.log`）。源码、package 和 import 均存在，失败后同一输出目录又恢复 1,791 个 class，包含原报错的 AI 配置与 Mapper；本任务没有在此间改这些源码或生成这些 class。记录为共享构建产物并发更新，不按报错改写正常业务引用。恢复后重跑结果另记，旧全模块通过不覆盖此首红。


恢复后执行 `./mvnw -ntp -pl batch-console-api -am -DskipTests test` **通过**，7 个受影响及依赖模块生产/测试源码编译成功、跳过测试（`/private/tmp/wdi-bug-completion-console-compile-final5.log`，21:14:28）。未改写报错引用，保留并发产物缺失首红。最后一次前端来源 mtime 复核没有晚于 `front-unit-final9.log` 的代码变化；已审/部分审查文件 SHA-256 与覆盖清单一致。全工程未审项继续见 TODO Master，不在此销项。


## 全工程续审：日切唯一所有权验证

BUG-20261026-17 的真实组件扫描先红：`TriggerSchedulingOwnershipTest` 发现 Trigger 注册 `BatchDayCutoffScheduler#scheduledCutoff`，不是仅凭文本搜索推断可达性。日志 `/private/tmp/wdi-cutoff-ownership-red.log`。

```sh
./mvnw -ntp -pl batch-trigger,batch-orchestrator -am \
  -Dtest=TriggerSchedulingOwnershipTest,MapperStatusDefaultsTest,BatchDayCutoffSchedulerTest,BatchDayCutoffMultiTenantIntegrationTest,BatchDayTimePolicyResolverTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

上述命令通过 **14 项，0 失败、错误或跳过**（Trigger 3、Orchestrator 11，含真实 PostgreSQL 2）。数据库回归核对到期记录 OPEN→CUTOFF 且版本 7→8，未来/冻结记录保持 OPEN 与版本 7，两轮扫描仅一条截止审计；多租户审计及 DST 解析既有用例保留。日志 `/private/tmp/wdi-cutoff-ownership-green.log`，2026-10-26 21:47:36 完成。测试使用独立容器，未更新现有业务库或部署服务；完整工程逐文件审核继续以覆盖清单为准。


## 全工程续审：准入队列跨越排水边界

BUG-20261026-18 的首轮测试构造中，补跑场景请求遗漏当前必填字段，未到达目标队列；补齐合法请求后，三入口全部先红，实际 HTTP 200，预期 409。有效首红为 `/private/tmp/wdi-trigger-admission-drain-red2.log`（3 项断言失败），保留初次夹具错误日志，不把验证层级混同。

```sh
./mvnw -ntp -pl batch-trigger -am \
  -Dtest=TriggerAdmissionDrainTest,TriggerApiAdmissionGuardTest,TriggerApiAdmissionConfigurationGuardTest,TriggerControllerTest,TriggerGracefulShutdownTest,TriggerSecurityFilterTest,TriggerSchedulingOwnershipTest,TriggerApiKeyVerifierTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

**45 项通过，0 失败/错误/跳过**，日志 `/private/tmp/wdi-trigger-admission-drain-green.log`，21:57:48 完成。新增回归用真实 bounded admission 队列与 `TriggerDrainState`/`TriggerGracefulShutdown`，以排队计数确认请求尚未进入业务，启动排水后释放占位许可；三入口均 409 且业务服务无调用，正常三入口各调用一次。Quartz 和业务服务用替身，HTTP 使用 MockMvc；没有宣称真实集群关闭或数据库事务被撤销。


### Trigger 真实应用装配补验

日切专用 Mapper 删除后，增量资源复制保留了 `target/classes/mapper/BatchDayInstanceMapper.xml`。确认对应源文件已退役且该资源引用旧 namespace 后，仅移除这一份残留生成资源，没有清空其他模块构建产物或改动源码以迁就缓存。随后运行：

```sh
./mvnw -ntp -pl batch-trigger -am \
  -Dtest=BatchTriggerApplicationIntegrationTest,TriggerAdmissionDrainTest,TriggerControllerTest,TriggerSchedulingOwnershipTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

**22 项通过**，含真实 Spring Boot 应用、PostgreSQL 和 Quartz JDBC JobStore 启动装配 2 项；日志 `/private/tmp/wdi-trigger-context-green.log`，22:07:22 完成。此命令与前述 45 项有重叠，不累计为独立覆盖用例数；未更新运行中的开发或生产服务。


### 本段最终快照与未完成范围

前端新增来源完成后重新运行全量测试：**221 文件 / 1,329 项通过，0 失败或跳过**；`npm run build`（含生产和脚本类型检查）、`npm run lint:check`、`npm run check:docs`（75 份当前文档）、`npm run check:changelog` 通过。日志前缀 `/private/tmp/wdi-bug-audit-front-`，分别为 `unit-current.log`、`build-current.log`、`lint-current.log`、`docs-current.log`、`changelog-current.log`。早前构建曾在新增组件测试的 CSS 选择器返回类型处失败，改为有名称的组件选择器后通过；未删除业务断言或改动生产类型配置。

后端文档结构、代码文档路径、日期策略、Changelog 检查通过，日志前缀 `/private/tmp/wdi-bug-audit-back-`。Trigger/Orchestrator 本段定向命令已包含生产与测试源码编译，没有以这些结果替代整个后端全量业务测试。

当前逐文件清单后端 **6,332**（103 已审、34 部分、6,195 待审），前端 **1,222**（85 已审、24 部分、1,113 待审）。原目标仍是两仓全部逐文件 Bug 审核；本地修复、自动化通过及已退役来源的裁定均不等于目标完成。配置同步超时/系统错误后的命令身份和审计回执尚待专项验证，页面输入并发修复不将该链路销项。


### 共享来源新变动的证据边界

最终来源核对发现 5 份后端待审来源又有同期更新，均保留并刷新清单摘要，仍为待审，没有套用旧编译或定向测试证据。前端清单来源摘要无漂移。本段未进行最新所有后端模块的全量测试；21:01 的 17 模块编译记录只证明当时快照。下列旧摘要用于保留范围变化证据：

| 路径 | 更新前 SHA-256 |
|---|---|
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/ops/service/ConsoleAtomicTaskTypeSchemaService.java` | `11577298389aab01405873e4a39e101d7e98fc92d7a3549269bad8bd12f536b8` |
| `batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageSheetSpecs.java` | `3bbfe82511407935a36729e96bf07a60b02a4b326d51978f6f879eb80b1fd49c` |
| `batch-console-api/src/test/java/io/github/pinpols/batch/console/domain/ops/web/ConsolePersistentWaitControllerTest.java` | `975df3fc9d78b2a3aaab5e5f404a735802461f4366a0792d8639b09fbc3b6923` |
| `batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/domain/query/PersistentWaitReadQuery.java` | `f26d1e367376d9f11bfeb378c6ebe685b920c2b636280cb0dd38a4069a67b957` |
| `batch-orchestrator/src/test/java/io/github/pinpols/batch/orchestrator/application/service/PersistentWaitReadServiceTest.java` | `d1ab7493ffebe04d396f3cab4461bcccceb1c9af7d142d4b4e767454f9f9b650` |


## 全工程续审：同步原请求、目标身份与事件冻结集合

### 配置同步目标身份

- 首轮 Bean Validation/应用边界回归：18 项中 7 项失败（`/private/tmp/wdi-sync-target-validation-red.log`），包括 null/空白未被拒绝以及应用越过预期校验点。应用替身没有提供后续元数据，因此只把其失败用于证明未在目标校验点拒绝，真实 HTTP 结果单独复现。
- 真实认证 HTTP/PostgreSQL 首红：3 项中 2 项失败，实际为 `[500, 1]`、`[200, 1]`，预期 `[400, 0]`（HTTP 状态、审计行数）。空串已有长度校验通过。日志 `/private/tmp/wdi-sync-target-http-red.log`。
- 修复后同步/初始化关联 **70 项通过，0 失败/错误/跳过**，含真实 PostgreSQL 7 项；日志 `/private/tmp/wdi-sync-recovery-validation-green.log`。本段新增查询同时覆盖原审计 RUNNING→终态、同键不重做目标、错误租户未找到及非管理员 403。

```sh
./mvnw -ntp -pl batch-console-api -am \
  -Dtest=ConfigSyncTargetIdentityValidationTest,ConfigSyncAuditRecoveryTest,ConsoleConfigSyncControllerTest,ConfigSyncAuditRecoveryIntegrationTest,DefaultConsoleTenantConfigInitApplicationServiceTest,ConfigSyncImportExecutorTest,DefaultConsoleConfigSyncBundleMappingTest,DefaultConsoleConfigSyncApplicationServicePaginationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

提取新代码的局部变量后，受影响单元和入口验证 **24 项通过**（`/private/tmp/wdi-sync-final-unit.log`）；与 70 项重叠，不累计。

### 事件冻结集合的数字成员查询

真实事件接纳后，通过 `/api/triggers/launch` 对冻结成员重投：首红期望 200、实际 409（`/private/tmp/wdi-event-numeric-target-red.log`）。修复后 **36 项通过**，含真实 API Key、Spring Security、HTTP、PostgreSQL 的 2 项；原请求/Outbox 共两行保持不重复，集合外 Workflow 仍被拒绝。日志 `/private/tmp/wdi-event-numeric-target-green.log`。

```sh
./mvnw -ntp -pl batch-trigger -am \
  -Dtest=ExternalEventApiKeyHttpIntegrationTest,DefaultTriggerServiceTest,TriggerMapperXmlTenantGuardArchTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

测试使用自建隔离环境；未修改运行中的业务数据库、服务或冻结工作树。OpenAPI 路由检查确认 432 条路径同步，前端类型由当前 OpenAPI 重新生成；没有手改生成类型。前端原命令恢复的组件与客户端证据见配对日期报告，未将分层证据称为真实浏览器丢包的完整端到端演练。


## 全工程续审：旧初始化提交边界与前端目标/结果

- 首红：`TenantConfigInitOutcomeTest` 2 项中 1 项失败，旧实现没有向上传播未知提交异常（`/private/tmp/wdi-legacy-init-outcome-red.log`）。
- 修复后初始化/严格事务/同步执行器/发布应用相关 **49 项通过，0 失败/错误/跳过**，其中真实 PostgreSQL 14 项（`/private/tmp/wdi-legacy-init-outcome-green.log`）。新见证测试在真实 commit 后注入确认异常，再经独立连接读取首个目标；执行器业务由替身提供，不宣称生产连接真实丢包。

```sh
./mvnw -ntp -pl batch-console-api -am \
  -Dtest=TenantConfigInitOutcomeTest,TenantConfigInitCommitOutcomeIntegrationTest,DefaultConsoleTenantConfigInitApplicationServiceTest,TenantConfigBatchInitStrictAtomicityIntegrationTest,ConfigSyncImportExecutorTest,DefaultConfigReleaseApplyServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

前端初始化 API 请求覆盖和错误完成提示的 8 项回归先红 5 项，修复后连同批量结果工具共 12 项通过；类型、受影响文件 lint、国际化及生产构建通过。当前全量前端 **225 文件 / 1,362 项通过**（`/private/tmp/wdi-tenant-init-all-unit-final.log`），是本地组件/客户端验证，不是所有页面的真实浏览器验收。

首次本批全量前端运行有 1 项配置同步测试失败，原因是第二次异步摘要确认的等待条件被旧确认调用满足；改为等待本次确认次数增加，原结果断言保留，单文件27项及全量重跑通过。首红 `/private/tmp/wdi-tenant-init-all-unit.log` 保留。较早快照223文件/1,354项也通过，但不能替代新增初始化修复后的本批结果。

格式首检发现6份本批改动来源不合规，限定这些来源运行现有 formatter。首次范围参数未匹配绝对路径，随后把参数误作 glob 导致工具错误；依据本机插件的正则匹配规则改正后实际格式化完成，未改格式规则或扩大白名单。各次日志 `/private/tmp/wdi-tenant-init-spotless.log`、`/private/tmp/wdi-tenant-init-format-java*.log` 保留。

配置关联最终合跑 **91 项通过、0失败/错误/跳过，含真实 PostgreSQL 21 项**（`/private/tmp/wdi-tenant-init-combined-final.log`）：在前段70项命令中追加 `TenantConfigInitOutcomeTest,TenantConfigInitCommitOutcomeIntegrationTest,TenantConfigBatchInitStrictAtomicityIntegrationTest,DefaultConfigReleaseApplyServiceTest`。与前述49及70项重叠，不累计相加。受影响 reactor `./mvnw -ntp -pl batch-console-api -am spotless:check` 最终通过（`/private/tmp/wdi-tenant-init-spotless-final.log`）。

### 逐文件范围与证据状态

补齐漏盘格式后，本地现存后端6,367份来源为132已审/37部分/6,198待审；前端1,254份为94已审/27部分/1,133待审。只将实际通读和追过必要调用的来源固定为已审；大DTO、API集合、语言包及局部触及的SQL/服务保留部分审查。新脚本/环境/HTML/CSS/模板与工具配置只补入待审，不能将盘点或编译计入语义审查。非代码缩略图仅从代码分母排除，文件保留。

本批更新前后端当前运维/用户文档、Changelog、TODO和原日期记录。两边仍是原 `fix/wdi-current`，后端HEAD `f3d5e400d`、前端 `662d254`；共享暂存/未暂存状态持续变化，本任务不做暂存、提交、回退或清理。全工程逐文件审核尚未完成。

### 2026-10-27 初始化面板作用域续审

配对前端 FE-BUG-40 新增10项边界回归先红（原5项通过），修复后组件/API/结果工具22项及全前端225文件/1,372项通过；类型与受影响文件ESLint通过。校验期间关闭、换目标/账号/权限/维护或改输入后不续发，重复点击只提交一次，旧响应不关闭重开的面板；已经发出的服务端业务不撤销。原日期报告延续记录，本段没有修改后端行为，前述91项后端结果保持原来源快照。
