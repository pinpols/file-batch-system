# GitHub Security and quality 告警处置（2026-10-31）

## 范围与基线

- 仓库：`pinpols/file-batch-system`（后端）；配对前端 `pinpols/batch-console` 的 4 条 shell 注入告警由独立 PR #347 处理，本记录不覆盖。
- 扫描基线：`main` 提交 `28a69b9d57c567535b44a95607f244f3295d4ef8`（CodeQL 2.27.3，2026-10-31 04:56Z 分析，19 条结果）。
- 处置分支：`fix/security-quality-alerts`（本机工作树 `~/.codex/worktrees/security-quality/file-batch-system`）；提交 `aca410856`，PR [#1271](https://github.com/pinpols/file-batch-system/pull/1271)，后续状态以 PR 与 CI 实时结果为准。
- 告警来源：`gh api repos/pinpols/file-batch-system/code-scanning/alerts?state=open`、`.../dependabot/alerts?state=open`。

## 告警清点

| 类别 | 数量 | 处置 |
|---|---:|---|
| CodeQL `java/log-injection` | 4 | 已修复（复用 `LogSanitizer`） |
| CodeQL `java/user-controlled-bypass` | 4 | 逐条核对为误报，建议按编号关闭并保留依据 |
| CodeQL `java/csrf-unprotected-request-type` | 9 | 逐条核对为只读端点+既有 CSRF/认证防护，建议按编号关闭并保留依据 |
| OpenSSF Scorecard `Vulnerabilities`（RUSTSEC-2026-0418/0381/0386） | 1 | 已升级两个 Rust 锁文件中的 `libz-sys`/`webpki-roots`/`tokio` |
| Dependabot aiohttp（#8–#15） | 8 | `main` 的 `sdk/python` 已升级到 aiohttp 3.14.4，无需代码修改；待 Dependabot 刷新自动关闭 |

## 一、已修复：日志注入（4 条）

统一复用 `io.github.pinpols.batch.common.logging.LogSanitizer.value`（`String.valueOf(raw).replaceAll("\\R", "_")`，为 CodeQL log-injection 模型识别的净化形态，见 `LogSanitizer` 类注释与 `LogSanitizerTest`）。

| 告警 | 位置 | 修改 |
|---|---|---|
| #312 | `batch-console-api/.../application/realtime/ConsoleProgressSnapshotPublisher.java` | `tenant`、`stream` 日志参数经 `LogSanitizer.value` |
| #311 | `batch-common/.../common/rls/RlsTenantSessionSupport.java` | `tenantId` 日志参数经 `LogSanitizer.value` |
| #310 | `batch-trigger/.../trigger/service/TriggerRequestPersistence.java` | `tenantId`、`workflowDefinitionId`、`fireTime` 日志参数经 `LogSanitizer.value` |
| #309 | `batch-orchestrator/.../workflow/DefaultWorkflowDagService.java` | `fromNodeCode`、`workflowRunId`、`skipped` 日志参数经 `LogSanitizer.value` |

保留了全部原字段、日志模板、级别与业务判断；只改变对外输出前的换行清洗。另为 `ConsoleProgressSnapshotPublisher` 的“事件推送失败且错误推送也失败”兜底分支补充单测（`shouldLogSanitizedContext_whenErrorPublisherAlsoFails`），覆盖净化后的日志参数并满足 PR 差异覆盖率门禁。

## 二、已修复：供应链依赖（Scorecard `Vulnerabilities`，#296）

| RUSTSEC | 依赖 | 修复前 | 修复后 | 修复条件 |
|---|---|---|---|---|
| RUSTSEC-2026-0418 | `libz-sys` | 1.1.29 / 1.1.29 | 1.1.31 / 1.1.31 | `>= 1.1.31` |
| RUSTSEC-2026-0381 | `webpki-roots` | 1.0.9 / 1.0.8 | 1.0.11 / 1.0.11 | `>= 1.0.10`（或 0.26.11） |
| RUSTSEC-2026-0386 | `tokio` | 1.53.1 / 1.52.3 | 1.54.1 / 1.54.1 | `>= 1.51.6`；`>=1.53.0` 需 `>= 1.53.3` |

（左/右分别为 `sdk/rust/Cargo.lock` 与 `examples/self-hosted-sdk/sample-tenant-worker-rust/Cargo.lock` 的修复前后版本；均通过 `cargo update -p libz-sys -p webpki-roots -p tokio` 在原有语义版本约束内升级，未改 `Cargo.toml`。）

`Vulnerabilities` 告警只能在修复合入 `main` 并由下一次 Scorecard 扫描后关闭，当前未关闭。

## 三、Dependabot aiohttp（8 条）：main 已修复，无需改动

- `sdk/python/pyproject.toml` 已声明 `aiohttp>=3.14.4`；`sdk/python/uv.lock` 当前锁定 `3.14.4`（高于所有告警的首个修复版本 3.14.4）。
- `cd sdk/python && uv lock --check` 通过（Resolved 37 packages）。
- 告警仍显示 open 是 Dependabot 尚未按最新 `main` 刷新；合入后由平台自动关闭。此处不把“已升级”表述为“告警已关闭”。

## 四、误报判定：user-controlled-bypass（4 条）

| 告警 | 位置 | 判定依据 |
|---|---|---|
| #316 | `batch-trigger/.../config/TriggerSecurityConfiguration.java:141` | `InternalSecretFilter` 的 API Key 分支中，`apiKeyAuthenticationCanRun = verifier != null && isApiKeyAllowedUri(request)`；条件为假或 principal 为空时立即 `writeUnauthorized`(401) 并返回，不存在未认证继续链路。`isApiKeyAllowedUri` 仅放行 4 个精确的 method+path（`/api/triggers/launch`、`/launch/status`、`/events`、`/events/status`）。用户可控条件只区分“401”与“已认证”，不构成跳过认证。 |
| #315 | `batch-orchestrator/.../workflow/DefaultWorkflowRunLaunchService.java:172` | `capacitySnapshot` 来自 `WorkflowLaunchWaitService.findSnapshot(request)`：按 tenant+requestId 读取持久化的 pending `trigger_request` 与 `workflow_launch_wait`，要求 `LaunchRequestIdentityGuard.requirePending` 且 `snapshot.requireSameRequest(request)` 通过；非伪造输入。仅当存在容量等待快照时才要求 `requireCapacityReplayAuthorization`（校验 RUNNING 的 WORKFLOW_RUN 重放操作 id、类型、状态与 `targetRequestId`）；快照为空表示不是容量重放路径，后续仍走普通启动准入/定义校验。 |
| #314 | `batch-console-api/.../notification/service/ConsolePushSubscriptionService.java:75` | 该行属于 `requireValidSubscription` 的载荷合法性校验，非法载荷抛 `INVALID_ARGUMENT`，不承担认证。Controller `ConsolePushController.subscribe` 标注 `@PreAuthorize("isAuthenticated()")`；服务在写入前经 `TenantIdResolver.resolveTenant` 解析租户并持久化。拒绝非法请求不等于绕过认证，也未有非法请求落库路径。 |
| #313 | `batch-console-api/.../file/infrastructure/DefaultConsoleFileChannelApplicationService.java:137` | 所属 `update` 为 Console 管理配置更新，Controller 标注 `@PreAuthorize(ADMIN_OR_TENANT_ADMIN)`；`authType` 是合法的渠道配置字段，`isNotNull(request.getAuthType()) ? request.… : existing.…` 只在“更新字段”和“保留原值”间选择，不参与认证/授权判定。 |

以上均未修改校验逻辑，也没有为了消除告警删除校验。建议对以上 4 个编号按 GitHub code scanning 的 `false positive` 处置，并把本表作为理由；相关防护或调用链变化后需重新核对。

## 五、误报判定：CSRF（9 条）

共同结论：9 个被标记 handler 全部是 **GET 只读查询**，逐个追踪其服务调用链（含 `RlsTenantContextHolder.runWithTenant` / `TransactionTemplate` 包装）未发现业务写操作：仅 `SELECT` 与事务内 `set_config('app.tenant_id', …, true)`（会话级租户上下文，事务结束自动 reset，不是业务状态变更）。CSRF 需要“GET 触发业务状态改变”才可被利用，故判定为启发式误报。

认证/防护模型：

- Console：`ConsoleSecurityConfiguration` 第 191–198 行启用 `CookieCsrfTokenRepository` 双提交 CSRF，忽略清单仅覆盖无 cookie 的内部回调、登录前公开端点与本地 bypass-mode；主认证为 HttpOnly cookie JWT。
- Trigger：`TriggerSecurityConfiguration` 仅对 `X-Internal-Secret` 请求与 API Key 请求忽略 CSRF（这两类凭据在请求头中，浏览器不会自动携带）；`/api/triggers/events/status` 属 API Key 白名单。
- Orchestrator：`InternalSecurityConfiguration` 对 `/internal/*` 统一注册 `InternalAuthFilter` 与请求签名过滤器，内部端点不接受浏览器 cookie 凭据。

| 告警 | 端点（GET） | 只读核对 |
|---|---|---|
| #300 | `/api/console/jobs/bundle/export` | 控制器类 `@PreAuthorize(ADMIN_ONLY)`；`exportBundle → buildJobBundle` 只组装/读取配置包，无持久化 |
| #301 | `/api/console/notifications/terminal-deliveries` | `@PreAuthorize(ANY_CONSOLE_ROLE)`；`list` 仅 `bindTenant` + `list` 查询 |
| #302 | `/api/console/config-sync/requests/status` | `requestStatus → auditTransaction` 仅设置租户上下文 + `selectByRequestKey` |
| #303/#304 | `/internal/batch-days/catch-up-preview`、`/historical-gaps` | readOnly `TransactionTemplate`，`selectFirstByTenantAndCodeAndEnabled`/`selectBizDatesInRange` 等查询 |
| #305 | `/internal/calendar-dependency-waits` | `setTenantContext` + `selectRecent` |
| #306 | `/internal/file-group-expectations/upload-targets` | `setTenantContext` + `selectEnabledUploadTargets` |
| #307 | `/internal/job-dependency-waits` | `setTenantContext` + `selectRecent` |
| #308 | `/api/triggers/events/status` | `findExternalEventStatus` 仅按幂等键查询状态 |

建议对 #300–#308 按具体编号以 `false positive` 处置并附本表；不修改 GET/POST 语义（这些端点确为查询接口），不关闭 CSRF 防护。

## 六、验证记录

| 验证 | 命令/方式 | 结果 |
|---|---|---|
| Java 编译（含测试源码） | `./mvnw -T 1C -DskipTests test-compile`（本机新工作树，无既有 `target/`） | ✅ BUILD SUCCESS，02:30 |
| 日志净化单测 | `./mvnw -pl batch-common -Dtest=LogSanitizerTest test` | ✅ 2 个测试通过，0 失败 |
| 进度采样错误路径单测 | `./mvnw -pl batch-console-api -am -Dtest=ConsoleProgressSnapshotPublisherTest -Dsurefire.failIfNoSpecifiedTests=false test` | ✅ 4 个测试通过，0 失败（含新增净化分支） |
| Java 格式 | `./mvnw -pl batch-common,batch-trigger,batch-orchestrator,batch-console-api spotless:check` | ✅ 4 个受影响模块通过 |
| Rust SDK 单测 | `cd sdk/rust && cargo test` | ✅ 96 个测试通过（82 单测 + 1 constants + 4 contract + 9 runtime），0 失败 |
| 示例工程检查 | `cd examples/self-hosted-sdk/sample-tenant-worker-rust && cargo check` | ✅ `Finished dev profile`，03:00 |
| Python 锁文件 | `cd sdk/python && uv lock --check` | ✅ 通过，aiohttp 3.14.4 |
| 文档/LOC 门禁 | `check-docs-structure.py`、`check-doc-timestamp-policy.py`、`check-changelog-sync.py`、`check-loc-snapshot.py`、`check-script-governance.py` | ✅ 5/5；LOC 快照（`docs/stats/loc-current-lean.md`）已按门禁重新生成 |
| 13 条误报关闭 | `gh api -X PATCH repos/pinpols/file-batch-system/code-scanning/alerts/<编号> -f state=dismissed -f dismissed_reason="false positive" -f dismissed_comment=...` | ✅ 13/13 dismissed（#300–#308、#313–#316，2026-10-31 05:33–05:34Z，关闭评论含逐条依据） |
| PR 与 CI | PR #1271 | 已创建；required checks 状态以 PR 为准，不在本记录宣称通过 |
| CodeQL 复扫 | 由 PR #1271 触发 CodeQL（本机未安装 CodeQL CLI，未本地复现） | 未验证，等待 CI |
| Scorecard/Dependabot 关闭 | 需修复合入 `main` 后由平台下一次扫描刷新 | 未验证（不得提前宣称关闭） |

## 七、未运行与残余

- 未运行完整单元/集成/E2E、SIM、BE acceptance、真实环境验证与远端 CI。
- 13 条误报已由维护者账号 `pinpols` 于 2026-10-31 05:33–05:34Z 按具体编号以 `false positive` 关闭，关闭评论附本记录中的路径/防护依据；未批量操作、未修改校验逻辑。关闭后 `open` 的 CodeQL 仅剩 #309–#312 四条日志注入（由本分支修复，待合入 `main` 后下一次扫描自动关闭）。
- 本记录中“误报”是代码与配置层面的判定；CodeQL 扫描的最终确认仍需 PR 上的 CodeQL 结果，若后续扫描因代码/规则变化重新出现，需重新核对，不能沿用本次结论。
- PR #1271 已创建（base `main`），PR Gate/CodeQL 等 required checks 以 PR 实时状态为准；本记录不宣称 CI 通过或可合入。
- 首次 PR CI 的失败项为：`QuartzMisfireRecoveryIntegrationTest` 的时序波动（与本次日志字段清洗无关，需重跑确认）以及 Console 差异覆盖率门禁未覆盖 `ConsoleProgressSnapshotPublisher` 的兜底日志分支（已补充上述单测）。
- 前端仓库的 4 条 CodeQL shell 注入与 4 条 npm Dependabot 告警不在本记录范围（PR #347 正在处理 shell 注入）。
