# 后端安全与运行资源深度审查（2026-11-23）

本轮基线为远端 `main` 的 `df5a875673d5ddd970e8d7468d53450eb9f15a7b`（#1458）。覆盖 Console、Trigger、Orchestrator、Common、Worker Core 与五类 Worker，以及五语言 SDK 的权限和传输边界。以 [安全模型](../architecture/security-model.md)、[模块边界审查](backend-module-boundary-review-2026-11-22.md) 和 [上轮系统审查](full-system-review-2026-11-13.md) 定位关键链路。按生产配置、凭据身份、调用方、实际提交和资源释放顺序复核。

枚举 Git 中 3,157 个 `src/main` Java 文件（含工具、示例及测试支撑）。在全部跟踪源码中筛出 45 处线程池/队列候选、94 处 JVM 持有与缓冲候选、105 处 PostgreSQL Mapper 锁相关行，再沿高风险调用链人工核查。命中数表示定位范围。一条语句可能包含多个命中，也可能已有容量限制或释放路径。

## 高优先级发现

### P1 · Go 和 Rust SDK 的 HTTP 重定向会保留自定义 API Key（RISK-20261123-01）

Go SDK 用默认 `net/http.Client` 重定向行为，并在请求上设置 `X-Batch-Api-Key`。Rust SDK 将同名请求头放进 reqwest 0.12.28 的默认头，客户端创建时未设置重定向策略。两者跨主机转发时会特殊处理标准认证头，SDK 的自定义头未列入过滤范围。当控制面或经过的网关返回跨主机跳转时，客户端可能把租户凭据交给跳转目标。已核对实现与当前绑定版本，未发起外部重定向测试。Java SDK 明确关闭自动跳转，TypeScript 使用 Node 单次 HTTP 请求，Python 的默认 transport 由 HTTPX 创建。[Go 构造与请求](../../sdk/go/client/transport.go) · [Rust 构造](../../sdk/rust/src/client/reqwest_transport.rs) · [Java 对照](../../sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/internal/PlatformHttpClient.java) · [Go Client 文档](https://pkg.go.dev/net/http#Client) · [reqwest 0.12.28 重定向实现](https://github.com/seanmonstar/reqwest/blob/v0.12.28/src/redirect.rs)

收口建议为按 Java SDK 的控制面契约，拒绝自动跳转。把 3xx 作为显式传输结果处理，避免非幂等请求被隐式重发。验收使用专用测试凭据和受控测试替身，对照同源、跨源、正常请求和报错内容，确认跳转目标无请求，日志无凭据。

### P1 · Worker 接管可能再次执行已有外部副作用的任务（RISK-20261123-02）

Worker 强制下线、接管与排空超时共同执行 `reclaimTask`。在租约已到期、任务仍为 RUNNING 且已记录非幂等启动、外部结果尚未返回时，常规重派主要检查 `EXTERNAL_OUTCOME_UNKNOWN` 错误码。此时任务尚无该错误码。重派可重置分区与任务并写派发 Outbox，任务原启动时间戳未清除。新 Worker 认领设置新的 invocation，登记非幂等启动时对当前认领身份校验成功后，会把已有的启动时间戳视为同一次执行并返回成功，Worker 可再次调用外部执行器。自动租约回收在同样的状态下会关闭为结果不明，两个入口的安全裁定不一致。当前已追至调度 SQL 和执行器门槛，尚未通过外部替身完成端到端验证。[接管入口](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultWorkerDrainGovernanceService.java) · [任务接管](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultRetryGovernanceService.java) · [重派协调](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/RetryRequeueCoordinator.java) · [任务复位及认领](../../batch-orchestrator/src/main/resources/mapper/JobTaskMapper.xml) · [启动校验](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/task/DefaultTaskAssignmentService.java) · [自动租约回收](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/lease/PartitionReclaimUnit.java) · [Worker 外部执行门槛](../../batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/infrastructure/DefaultStepExecutionAdapter.java)

用当前执行代际与副作用标记统一手工接管和自动回收的裁定。已有未确认外部结果时进入既有结果核对流程。验收让首次执行确认启动、外部侧记录一次动作、回报缺失，分别走三种接管入口，核对结果不明终态和外部动作次数。另测可重放任务照常接管，以及旧回报与接管交错。

## 其他发现

| 优先级与证据 | 发现 | 整改与验收 |
|---|---|---|
| **P2 · RISK-20261123-03 · 告警、版本和实际依赖解析** | GitHub 开放告警 #296 来源为 Scorecard。Rust SDK 和示例的锁文件均固定 `url 2.5.8`，`cargo tree` 确认启用 HTTP 时由 reqwest 引入。RustSec 的公告指出 2.5.9 已修复受影响的 origin 计算路径。本轮在 SDK 与锁定 HTTP 依赖源码未找到直接调用该受影响函数的路径，实际产品可达性待核实。[告警](https://github.com/pinpols/file-batch-system/security/code-scanning/296) · [RustSec](https://rustsec.org/advisories/RUSTSEC-2026-0592.html) · [SDK 锁文件](../../sdk/rust/Cargo.lock) · [示例锁文件](../../examples/self-hosted-sdk/sample-tenant-worker-rust/Cargo.lock) | 升级到包含修复的版本，重新解析 SDK 与示例的依赖，验证启用 HTTP 的构建和行为，复查扫描告警。 |
| **P2 · RISK-20261123-04 · 权限设计缺口** | 内部认证按 Worker/Task 路径前缀和读写 scope 放行，执行凭证可进入同租户的强制下线、接管及任务取消等运维入口。Controller 后续绑定租户，当前证据仅限同租户。现行 ADR 明确采用路径前缀，因此这是凭据权限颗粒度问题。[认证入口](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/config/InternalAuthFilter.java) · [Worker 运维](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/controller/WorkerController.java) · [任务取消](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/controller/TaskController.java) · [ADR-035](../architecture/adr/ADR-035-tenant-self-hosted-worker-sdk.md) | 核对租户现有运维集成后，采用按操作授权或专用运维 scope。用执行凭据、只读凭据、运维凭据和不同租户覆盖正常执行、应拒绝的运维操作及数据隔离。 |
| **P2 · RISK-20261123-05 · 连接饥饿已用 Spring/Hikari 对照复现** | Orchestrator 的 Worker 接管外层 `@Transactional` 持有平台连接，逐条回收又用 `REQUIRES_NEW` 借同池连接，超时被逐条捕获。Console 失败审计在独立事务内登记使用量回调，`afterCommit` 同步再开独立事务时审计连接仍在占用。连接池被相同外层调用占满时，内层等待借连接，可能拖长接管和请求延迟，回收失败后还可能继续标记 Worker 下线。单连接 Hikari 试验中，提交前和 `afterCommit` 内层均超时，外层返回后可正常借用。这个现象发生在客户端连接池等待。[接管和回收](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultWorkerDrainGovernanceService.java) · [回收事务](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/application/service/governance/DefaultRetryGovernanceService.java) · [审计回调](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/audit/support/AuditAspect.java) · [使用量事务](../../batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/observability/service/ConsoleUsageDailyRecorder.java) · [Spring 7.0.10 提交后资源语义](https://docs.spring.io/spring-framework/docs/7.0.10/javadoc-api/org/springframework/transaction/support/TransactionSynchronization.html#afterCommit()) | 接管协调避免持外层连接逐项开启新事务，并保留任务归属和进度核对。派生使用量改为能在事务资源释放后收敛的有界路径。用小池并发验收业务结果、失败审计、派生计数、超时及连接归还。 |
| **P2 · RISK-20261123-06 · 探针覆盖饥饿和队列滞留** | Dispatch 固定按租户和渠道取排序头部 1,000 条，Java 取得候选后才跳过退避渠道。最小 SQL 对照中连续三轮都漏掉第 1,001 条。另有固定 8 线程探针和无界提交队列。每组超时会取消 Future，若 NAS 文件系统调用持续占线程，后续排队的取消项可继续留在队列。JDK 最小对照显示被取消的排队 Future 默认仍在队列。尚未测真实挂载延迟。[候选 SQL](../../batch-worker/dispatch/src/main/resources/mapper/DispatchChannelHealthMapper.xml) · [探针池与批次](../../batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/DispatchChannelHealthService.java) · [NAS 探针](../../batch-worker/dispatch/src/main/java/io/github/pinpols/batch/worker/dispatchs/infrastructure/channel/RemoteFilesystemDispatchSupport.java) | 候选只取到期渠道并保证跨批覆盖，对探针在途、排队以及一轮总时间设边界。验收超过批上限和单渠道长期阻塞场景，确认其他渠道仍能推进且队列不增长。 |
| **P3 · RISK-20261123-07 · 已见 JVM 常驻保留路径** | Trigger 与 Orchestrator 的已验证凭据缓存有上限与 TTL，旁边记录最后使用时间的节流 Map 随成功使用过的不同 Key ID 增长，生产路径无逐出。高轮换环境会保留过期 ID，尚无堆样本证明实际线上体量。[Trigger 节流](../../batch-trigger/src/main/java/io/github/pinpols/batch/trigger/auth/TriggerApiKeyVerifier.java) · [Orchestrator 节流](../../batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/auth/ApiKeyVerifier.java) | 用有界、可过期的按 Key 节流状态，保留同一 Key 的并发节流语义。验证轮换后持有量和更新数据库频率。 |
| **P3 · RISK-20261123-08 · SDK 响应缓存无字节上限** | Go 使用全量读取且丢弃读取错误，Rust 聚合为完整文本，TypeScript 收集所有块后再拼接。请求超时和响应字节预算相互独立，异常代理或服务仍可能瞬间抬高堆占用。属于单请求峰值与并发放大风险。本轮未执行大型响应压力测试。[Go 读取](../../sdk/go/client/transport.go) · [Rust 读取](../../sdk/rust/src/client/reqwest_transport.rs) · [TypeScript 读取](../../sdk/typescript/src/client/transport.ts) | 在读取层限制字节数，显式传递失败并释放响应体。用小阈值覆盖准确边界、分块、读取中断及错误行为，不缓存超限正文。 |

## 审查覆盖与证据

| 维度 | 本轮证据与边界 |
|---|---|
| 安全与漏洞 | 追踪 Console JWT 与安全入口、Trigger 事件 Key、Orchestrator API Key scope、租户绑定与签名 nonce、内部共享密钥及出站 DNS/重定向，抽查五语言传输。主线 Java CodeQL 作业完成，开放的 GitHub 扫描告警为 Scorecard #296，Dependabot 开放计数为 0。应用级授权覆盖以本轮已追链路及范围内静态证据为准。 |
| 数据与职责边界 | 复核 #1458 合入的 Workflow 配置写入、文件 CAS 和 Trigger 列权限，追查本轮新增的 Worker 运维与非幂等启动边界。依赖方向、受限账号授权生成、业务表 strict RLS 守卫均通过。Kubernetes 实际网络隔离及真实跨租户角色实跑待验证。 |
| 线程池、内存与回收 | 核对调度池、执行器拒绝策略、取消清理、容器关闭和缓冲。Java SDK 虽用固定池，但提交前有许可量上限。Common 调度器开启取消剔除。本轮复现的是特定探针任务排队滞留，另发现 Key Map 生命周期和 SDK 响应峰值。运行堆转储、JFR 和长期趋势属于目标环境证据。 |
| 连接池 | 核对 Hikari 平台与业务分片池、关闭责任、会话超时、读副本回落及嵌套事务。分片创建失败会尝试关闭已建池。两条连接获取路径已在隔离 JVM 验证相同框架行为，实际默认池大小上的吞吐及雪崩阈值未测。 |
| PostgreSQL 死锁与锁顺序 | 核对 REPORT、租约回收、任务认领、Workflow 派发与结果版本的锁入口。REPORT 与自动回收在更新子行前获取同一实例 advisory lock，回收仍保留任务行 `NOWAIT`。本轮暂未确认新的 PostgreSQL `40P01` 环路。已有复现针对当前分支的执行结果见下节。 |
| 消息与恢复 | 核对 Outbox 领取代次、上报/回收、优雅退出及非幂等重放门槛。本轮新增问题在手工接管链路。实际 Kafka 重平衡、网络分区、HA 切换和跨语言混版仍需各自验证。 |

## 已执行验证

- `gitleaks git --redact=100 --no-banner --report-format json` 扫描 2,570 个提交，未报密钥命中。报告仅保存在私有临时目录。
- `cargo tree --manifest-path sdk/rust/Cargo.toml --features http --locked --offline --invert url` 确认 `reqwest 0.12.28 → url 2.5.8` 及 `tower-http` 的传递依赖。[Java CodeQL 运行](https://github.com/pinpols/file-batch-system/actions/runs/45183204188)
- `python3 scripts/ci/check-<名称>.py` 运行 `biz-table-tenant-rls`、`dependency-boundaries`、`direct-client-boundaries`、`config-governance`、`helm-env-sync`、`production-capacity-governance` 和 `trivy-ignore-expiry`，均退出 0。业务基表 strict RLS 覆盖 9/9。`python3 scripts/db/generate-platform-worker-grants.py` 核对现有授权生成产物，通过。
- 在临时目录通过 `java -cp … PoolProbe.java` 执行 Spring 7.0.10 与 HikariCP 7.0.2，对照单连接池、JDBC 替身及 JDK 21 固定线程池。外层返回后连接可重新租借，三个被取消的排队对象须清理或出队才消失。SQLite 内存库运行展开后的渠道查询，三轮结果一致，证明排序与截断行为。
- `./mvnw -o -ntp -pl batch-orchestrator -am -Dtest=OutcomeVsReclaimDeadlockIntegrationTest,DefaultWorkerDrainGovernanceServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` 成功。Worker 接管 31 个单测、REPORT 与自动租约回收 2 个 PostgreSQL 17.11 集成用例，共 33 项通过，0 失败及跳过。两个既有类均未覆盖本轮新增的非幂等手工接管反例或连接饥饿整链。
- 同提交 Java CodeQL 和 Full CI 的 `security-scan` job 已通过。Full CI 整体失败，失败项是 E2E 5 的上游模块安装步骤，E2E 5 不能记为已执行通过。目标环境 DAST、外部凭据路径、真实 NAS 阻塞、长期堆/连接监控仍需独立证据。[Full CI](https://github.com/pinpols/file-batch-system/actions/runs/45183204098)

## 整改（2026-11-23）

### RISK-20261123-01 · Go/Rust 控制面重定向

Go SDK 在 `NewHTTPTransport` 对最终 `http.Client` 做浅拷贝并设置 `CheckRedirect = http.ErrUseLastResponse`；调用方通过 `WithHTTPClient` 注入的客户端同样被收口，原对象不被修改。`call` 在 `ClassifyHTTP` 之前把 3xx 转为新增的 `RedirectError`，不跟随、不重试。Rust SDK 在 reqwest `ClientBuilder` 设置 `redirect::Policy::none()`，`classify_response` 把 3xx 映射为终止的 `ClientError`；决策核与共享 fixture 不变。Python（HTTPX 默认不跟随）与 TypeScript（单次 `http.request`）已核对无需改动。

验证：

- `cd sdk/go && go vet ./... && go test ./...` 通过（client/contract/protocol）。
- `TestHTTPTransport_DoesNotFollowRedirectOrRetry`：302 原站仅 1 次请求、跳转目标 0 请求、返回 `RedirectError(302)`。
- `TestHTTPTransport_OverridesCallerRedirectPolicy`：调用方默认跟随跳转的客户端仍不访问目标。
- `cd sdk/rust && cargo test --features http --offline` 与默认特性 `cargo test --offline` 通过；`redirect_is_not_followed_and_never_reaches_the_target`（跳转目标 0 请求）、`three_xx_is_terminal_client_error_not_retry`（300/301/302/303/307/308 均为终止）通过。

未执行：真实网关/代理返回跨主机跳转的部署级验证；日志凭据检查沿用代码审查（错误信息只含 op 与状态码）。

### RISK-20261123-02 · Worker 接管重复执行非幂等任务

强制下线、手工接管与排空超时统一走 `reclaimTask`。现在当任务为 RUNNING 且 `non_idempotent_started_at` 非空时不再重新入队，而是通过新增的 `UnknownExternalOutcomeCloser` 以 partition 当前 invocation 调 `applyTaskOutcome(EXTERNAL_OUTCOME_UNKNOWN)`，与自动租约回收共用同一收口路径并进入既有核对、补偿与死信流程；收口未落库时本轮接管失败并等待下一轮。`resetForRetry` / `resetForLeaseReclaim` 的共用复位片段同时清空 `non_idempotent_started_at`，使已回报失败后的合法重试可以重新登记自己的执行入口。收口器通过 `ObjectProvider<TaskOutcomeService>` 断开“接管→收口→结果服务→接管”的构造环。

验证：

- 单测 51 项通过：`UnknownExternalOutcomeCloserTest` 3、`PartitionReclaimUnitTest` 8、`DefaultRetryGovernanceServiceTest` 35、`DeadLetterAutoRetryTest` 5。
- 真实 PostgreSQL 17.11（`RetryExecutionClosureIntegrationTest`）3 项通过：
  - 已开始非幂等动作且租约过期的 RUNNING 任务经 `reclaimTask` 收口为 `FAILED` / `EXTERNAL_OUTCOME_UNKNOWN`，`JOB_TASK` 派发 Outbox 不增加；
  - 未开始非幂等动作的同类任务仍回到 `READY` 并新增 1 条派发 Outbox；
  - `resetForRetry` 清空 `non_idempotent_started_at`。
- 复跑既有 59 项通过、0 失败：`DefaultWorkerDrainGovernanceServiceTest` 31、`RetryRequeueCoordinatorTest` 23、`OutcomeVsReclaimDeadlockIntegrationTest`（真实 PostgreSQL）2、`BoundedRetryResetSqlContractTest` 3。

未执行：外部替身记录“实际外部动作次数”的整链验收；旧回报与接管交错的并发用例；真实 Worker 进程三入口演练。

### RISK-20261123-05 · 连接池饥饿

Worker 接管：`forceOffline` / `takeover` / `takeoverAfterDrainTimeout` 不再声明外层事务，接管循环直接调用 `reclaimTask`（`REQUIRES_NEW`），单条回收使用一条连接且不叠加外层连接；任务归属、逐条回收集合与最终 `markDecommissioned` 语义保留，单条失败仍逐条告警继续。

Console 审计：新增 `ConsoleUsageProjectionDispatcher` 与有界 `usageTaskExecutor`（2 线程、队列 500、`AbortPolicy`）。审计 `afterCommit` 只把派生用量提交到该线程池，审计线程先归还事务连接，异步线程再以 `REQUIRES_NEW` 借连接；队列满时丢弃派生写并告警，操作审计仍是事实源。

验证：

- `DefaultWorkerDrainGovernanceServiceTest` 32 项通过，新增反射守卫：三个接管入口不得带 `@Transactional`；原有 31 项接管行为用例保持通过。
- `ConsoleUsageProjectionDispatcherTest` 2 项通过：提交任务执行；队列满丢弃且不抛异常。
- `AuditAspectTenantFallbackTest` 7 项通过（投影改为经调度器，保留同步执行与失败抑制语义）。
- 编排器相关单测 78 项通过、0 失败；`spotless:check`、`pmd:check`、`git diff --check` 通过。

未执行：Hikari 单连接/小池的应用级并发验收（审查阶段已用独立 Spring/Hikari 探针复现旧行为；修复后的同构探针与目标环境池容量压测待补）。

### RISK-20261123-06 · Dispatch 探针覆盖与队列

`findEnabledProbeChannels` 改为左连接 `file_channel_health`，只取没有健康行或 `next_probe_at <= now` 的到期渠道，并按 `next_probe_at nulls first, tenant_id, channel_code` 排序；健康或退避中的渠道不再占满前 1,000 条候选，尾部到期渠道可以在后续轮次进入。探针超时（Future 取消）除计数外也会写失败退避，避免超时渠道永久停留在到期集合。探针线程池改为有界提交队列（8 线程、队列 64、`AbortPolicy`），并为单轮设置预算（`min(60s, probeInterval)`）；预算用尽或队列满时停止本轮，剩余到期渠道留到下一轮。

验证：

- 真实 PostgreSQL `DispatchChannelHealthServiceIntegrationTest#shouldSelectOnlyDueProbeChannels`：无健康行与已过期渠道进入候选，未到期渠道被排除，顺序为 `nulls first` + 最早到期。
- `DispatchChannelHealthServiceTest` 4 项、`DispatchChannelHalfOpenHoldTest` 1 项通过；集成类 1 项通过。
- `spotless:check`、`pmd:check`、`git diff --check` 通过。

未执行：1,001+ 渠道的真实候选覆盖压测、真实 NAS 阻塞下的队列与单轮预算观测。

### RISK-20261123-03 · Rust `url` 依赖

`sdk/rust/Cargo.lock` 与 `examples/self-hosted-sdk/sample-tenant-worker-rust/Cargo.lock` 通过 `cargo update -p url --precise 2.5.9` 升级到 `url 2.5.9`（伴随 `form_urlencoded 1.2.3`），`Cargo.toml` 的版本要求不变。`cargo tree --features http --invert url` 确认 `url v2.5.9` 仍由 `reqwest 0.12.28` 与 `tower-http 0.6.11` 引入。

验证：

- `cd sdk/rust && cargo test --features http`：127 项通过（113 + 1 + 4 + 9），0 失败。
- `cd examples/self-hosted-sdk/sample-tenant-worker-rust && cargo check --locked`：通过。
- `cargo audit` 未安装，未执行本地 RustSec 扫描；GitHub 扫描告警 #296 需等下一次分析确认关闭。

### RISK-20261123-07 · API Key 节流状态滞留

Trigger 与 Orchestrator 的 `lastTouchNanos` 从无界 `ConcurrentHashMap` 改为 Caffeine 缓存：`maximumSize(10_000)` + `expireAfterWrite(60s)`（与 `last_used_at` 写节流窗口一致）。同一 Key 在窗口内的并发节流仍由 `compute` / CAS 语义保证；轮换后不再使用的 Key ID 在写入过期或容量压力下被逐出，不再常驻。

验证：

- `TriggerApiKeyVerifierTest` 3 项、`ApiKeyVerifierTest` 27 项通过。
- `spotless:check`、`pmd:check` 通过。

未执行：高轮换环境的实际堆占用测量（审查阶段未取得堆样本）。

### RISK-20261123-08 · SDK 响应字节上限

Go、Rust、TypeScript 的控制面传输在读取层增加 8 MiB 上限：

- Go：`io.LimitReader(limit+1)`，超限返回新增的 `ResponseTooLargeError`；`call` 在 HTTP 分类前直接返回该错误，不重试。读取错误也不再被丢弃。
- Rust：`Response::take(limit+1)` 有界读取，超限返回内部状态 `RESPONSE_TOO_LARGE_STATUS`，`classify_response` 映射为终止的 `FailFast`；响应体立即释放。
- TypeScript：累计字节超过上限时立即 `reject(ResponseTooLargeError)` 并销毁响应/请求，`req` 的 error 事件不会把它降级为可重试的 transport error。

验证：

- Go：`go vet ./...` + `go test ./...` 通过；新增超大响应用例（8 MiB+1，命中 1 次，返回 `ResponseTooLargeError`）。
- Rust：`cargo test --features http` 129 项通过（115 + 1 + 4 + 9），含 8 MiB+1 超限与分类用例。
- TypeScript：`npm run build`、`npm run format:check`、`npm test` 通过（181 通过 / 7 跳过 / 0 失败），新增“超大响应立即失败且不重试”。

未执行：真实代理返回超大响应的端到端压测、单请求堆峰值测量。

### RISK-20261123-04 · 执行凭据权限偏宽

`InternalAuthFilter` 的 API-Key 通道改为显式端点分类：

- 执行端点（register / heartbeat / deactivate / claim / non-idempotent-started / report / renew 及批量变体）：写方法要求 `worker.execute`，读方法接受 `worker.read` 或 `worker.execute`。
- 运维变更端点（status / drain / force-offline / takeover / warmup / resume / task cancel）：只接受新增的 `worker.ops`。
- 只读查询 `claimed-tasks`：`worker.read` 或 `worker.execute`。
- 其它 workers/tasks 路径直接 401，未来的新端点不会被前缀自动放行；`X-Internal-Secret` 平台内部通道与 `*` 通配存量 key 保持原语义。

验证：

- `InternalAuthFilterTest` 15 项、`ApiKeyVerifierTest` 27 项通过（执行 key 拒绝运维、ops key 放行接管、cancel 拒绝、未知路径不触达校验）。
- `spotless:check`、`pmd:check`、`git diff --check` 通过。

兼容性：使用 API-Key 执行租户运维的集成方需为运维操作发放 `worker.ops` scope；平台内部 secret 通道不受影响。ADR-035 已补充该 scope 约定。

### 综合校验（2026-11-24）

- `./mvnw -ntp -DskipTests test-compile`（根 reactor 全模块）：BUILD SUCCESS，跨模块签名改动（`AuditAspect`、`DispatchChannelHealthMapper`、`PartitionReclaimUnit`、`InternalAuthFilter`）编译一致。
- `spotless:check`、`pmd:check`：`batch-console-api`、`batch-orchestrator`、`batch-trigger`、`batch-worker/dispatch` 均通过。
- 规范门禁：`check-changelog-sync`（新增 `docs/changelog.md` 条目）、`check-docs-structure`、`check-doc-timestamp-policy`、`check-code-doc-references`、`check-utf8-encoding`、`check-comment-language`、`check-test-conventions`、`check-test-config-sources`、`check-java-readability`、`check-java-contract-governance`、`check-mybatis-generated-key-columns`、`check-sdk-config-env-parity`、`check-repository-hygiene` 通过；`check-no-positional-insert-select-star.py` 需 Python ≥3.10，本地默认 3.9.6 无法启动，改用 python3.15 运行通过（脚本本身未改动）。
- 格式化后复跑受影响单测：orchestrator 125 项、console 9 项、trigger 3 项、dispatch 5 项，0 失败。
- 改动仍保留在独立工作树，未提交、未建 PR；目标环境、真实拆分账号、外部副作用计数与压测项未执行。
