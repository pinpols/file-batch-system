# 后端上线深度复核报告

审计日期：2026-05-18 13:04 CST  
范围：`file-batch-system` 后端全仓库、Helm/CI/安全脚本、配对前端 `../batch-console` 中与 Console API 直接相关的调用点。  
结论：核心批量调度主链路已经具备灰度上线候选条件；全量生产上线前需要先处理 Push 契约、Webhook 可靠性、Telemetry payload 上限与 DAST 有效性问题。

## 0. 上线结论

建议状态：**有条件上线，不建议直接全量放量**。

核心链路（Console 登录/RBAC、配置发布、触发、Trigger outbox、Orchestrator launch、Worker claim/renew/report、文件分发、worker report outbox、Helm prod secret、Kafka topic/config/Flyway 基线）本轮未发现新的 P0 阻断。

需要上线前完成或明确关闭的事项：

| 等级 | 问题 | 上线影响 | 建议 |
| --- | --- | --- | --- |
| P1 | Web Push 前后端契约不一致 | PWA 推送订阅无法成功；移动端告警/审批通知会失效 | 上线前修复，或关闭 Push 功能并从前端入口移除 |
| P2 | Webhook 进程内队列满/重启会丢事件，且队列拒绝没有 delivery log | 订阅方可能漏收业务事件，事后无法完整追溯 | 若 Webhook 承诺可靠通知，改 outbox；若只 best-effort，需要产品/运维明确 |
| P2 | 前端 telemetry `props` 仍无总字节/深度/字段数上限 | 登录用户可提交大 JSON 消耗内存/日志链路资源 | 增加请求体/props 总大小与深度限制 |
| P2 | Staging DAST 仍是 unauthenticated ZAP baseline | “0 alert”不能代表受保护 Console API 被扫过 | 上线前补 authenticated DAST 或把报告标记为非门禁覆盖 |
| P3 | HTTP 渠道健康探测存在 DNS 校验后按域名连接的 TOCTOU 残留 | 健康探测面仍弱于实际派发面，主要是 SSRF 加固一致性问题 | 让 probeHttp 与实际 dispatch 一样固定解析 IP |

## 1. 已确认修复的历史阻断

### 1.1 生产 Helm 安全密钥注入

旧问题：`values-prod.yaml` 启用 `prod` profile，但 Helm Secret 未注入 `BATCH_INTERNAL_SECRET` / `BATCH_CONSOLE_JWT_SECRET`，生产会 fail-close 启动失败。

当前状态：已修复。

证据：

- `helm/batch-platform/templates/secret.yaml:18` 使用 `required` 强制 `security.internalSecret` 非空。
- `helm/batch-platform/templates/secret.yaml:19` 使用 `required` 强制 `security.consoleJwtSecret` 非空。
- `helm/values-prod.yaml:8` 到 `:9` 已写明 `helm --set security.internalSecret` 与 `security.consoleJwtSecret`。
- `helm template` 已验证可渲染出 `BATCH_INTERNAL_SECRET` 与 `BATCH_CONSOLE_JWT_SECRET`。

### 1.2 NAS/OSS 分发适配器路由歧义

旧问题：真实 NAS/OSS adapter 与 stub adapter 同时支持 NAS/OSS，可能因 bean 顺序导致生产被 stub 接管。

当前状态：已修复。

证据：

- `StubRemoteFilesystemDispatchChannelAdapter` 仅在 `local,test` profile 生效，`@Order(1000)`，且只支持 NAS。
- `NasDispatchChannelAdapter` 为 `!local & !test`，`@Order(10)`。
- `OssDispatchChannelAdapter` 为 `!local`，`@Order(20)`。

### 1.3 CI 安全扫描软失败

旧问题：security/deps/hadolint/trivy/DAST/checkov 等软失败，无法作为生产门禁。

当前状态：主要门禁已硬化。

证据：

- `.github/workflows/full-ci-gate.yml:36` 到 `:58`：secret/deps/hadolint/trivy 没有 `continue-on-error`，Trivy `exit-code: '1'`。
- `.github/workflows/staging-gate.yml:108` 到 `:114`：Checkov `soft_fail: false`。
- `.github/workflows/pr-gate.yml:209` 到 `:214`：secret scan 不再 `--skip-build`。

残留：DAST 是 baseline 且未配置登录态，见 P2-4。

### 1.4 KEDA Orchestrator backlog 查询 schema

旧问题：KEDA backlog SQL 指向 `biz.outbox_event`，但平台 outbox 在 `batch` schema。

当前状态：已修复。

证据：`helm/batch-platform/values.yaml:192` 为：

```sql
SELECT COUNT(*) FROM batch.outbox_event WHERE publish_status IN ('NEW','FAILED')
```

### 1.5 Flyway 文档版本与 Mockito agent

当前状态：已修复。

证据：

- `docs/architecture/architecture-truth.md:122` 写明当前版本为 Flyway V122。
- `pom.xml:207` 已为 Surefire 配置 Mockito `-javaagent`，规避未来 JDK 动态 agent 默认禁用风险。

## 2. 本轮新增/残留缺陷

### P1-1 Web Push 前后端契约不一致，订阅链路无法成功

业务链路：

```text
PWA 用户点击启用通知
  -> GET /api/console/push/vapid-public-key
  -> navigator.pushManager.subscribe()
  -> POST /api/console/push/subscribe
  -> 后端持久化 console_push_subscription
  -> 告警/审批/Catch-up 调 ConsolePushSender
```

问题点：

- 后端 `ConsolePushController.vapidPublicKey()` 返回 `CommonResponse<ConsolePushVapidPublicKeyResponse>`，实际 JSON 是 `{"code":...,"data":{"publicKey":"..."}}`。前端 `../batch-console/src/composables/useWebPush.ts:67` 到 `:69` 直接解构顶层 `{ publicKey }`，会拿到 `undefined`。
- 后端 `subscribe` / `unsubscribe` 要求 query 参数 `tenantId`：`batch-console-api/src/main/java/com/example/batch/console/web/ConsolePushController.java:55` 到 `:63`、`:68` 到 `:74`。前端 `useWebPush.ts:79` 到 `:83` 与 `:95` 到 `:99` 没有传 `tenantId`，会 400。
- 后端注释声称 VAPID 公钥是“公开端点”：`ConsolePushController.java:39` 到 `:43`。但安全兜底 `ConsoleSecurityConfiguration.java:68` 到 `:85` 只放行 auth/login/logout/static，`/api/console/push/vapid-public-key` 实际仍要求 Console 角色。若产品期望登录前订阅，当前会 401。
- 前端文件顶部仍写“当前后端这 3 个端点还没上”：`useWebPush.ts:15` 到 `:17`，说明契约没有被回归验证。

影响：

- PWA Web Push 订阅不可用。
- 如果移动端告警、审批、Catch-up 通知依赖 Push，则上线后是用户可见故障。

整改建议：

1. 前端统一使用 Console API 客户端，解包 `CommonResponse.data.publicKey`。
2. `tenantId` 从当前登录上下文/tenant store 传入，或后端改为从 `ConsoleRequestMetadataResolver` 当前租户解析，避免 query 参数。
3. 明确 VAPID 公钥是否登录前公开：若公开，在 `ConsoleSecurityConfiguration` 加 permit；若不公开，修正文档/前端调用时机。
4. 加一条前后端契约测试：mock 后端 CommonResponse + 400 tenantId 缺失场景。

### P2-1 Webhook 是 best-effort，但当前产品/上线口径容易误认为可靠通知

业务链路：

```text
业务事件产生
  -> WebhookDispatcher.dispatchAsync()
  -> 进程内 ThreadPoolExecutor 队列
  -> 查询订阅
  -> HTTP POST callback
  -> delivery log SUCCESS/FAILED/EXHAUSTED
  -> WebhookDeliveryRelay 接力 EXHAUSTED
```

问题点：

- `WebhookDispatcher.java:72` 注释写“CallerRunsPolicy”，但实际 `ThreadPoolExecutor` 未传 `RejectedExecutionHandler`，默认是 `AbortPolicy`。
- `WebhookDispatcher.java:93` 到 `:100` 捕获 `RejectedExecutionException` 后只打 WARN 并丢弃事件。
- delivery log 只在进入 `deliverWithRetry()` 后写入：`WebhookDispatcher.java:150` 到 `:188`。队列满、进程重启、Pod kill 时，事件既不会投递，也不会留下 delivery log。
- 类注释 `WebhookDispatcher.java:37` 到 `:48` 已承认 best-effort，但上线材料需要把这个语义显式同步给产品/运维。

影响：

- Webhook 订阅方可能漏收关键事件。
- 漏收事件没有数据库审计，排障只能依赖应用日志。

整改建议：

- 若 Webhook 是产品承诺能力：改为 `webhook_outbox` 持久化事件，再由 relay 扫描投递。
- 如果保持 best-effort：至少把拒绝策略改成 `CallerRunsPolicy` 或在拒绝时写一条 `DROPPED` delivery log，并暴露 drop counter/alert。

### P2-2 Frontend Telemetry `props` 无总大小/深度限制

业务链路：

```text
前端埋点
  -> POST /api/console/telemetry/events
  -> Bean Validation
  -> ConsoleTelemetryController 逐事件写结构化日志
  -> Promtail/Loki
```

已修复点：

- `ConsoleTelemetryController.java:38` 到 `:42` 不再把完整 props 写进日志，只记录 key 数量。
- `FrontendTelemetryRequest.java:15` 到 `:23` 对 app/userId/sessionId/events/type/name/ts/page 做了 `@Size` 限制。

残留问题：

- `FrontendTelemetryRequest.java:24` 到 `:27` 注释说 props 总字节数在 controller 序列化后检查，但 `ConsoleTelemetryController.java:27` 到 `:67` 没有任何总字节数、深度、字段数量限制。
- `Map<String,Object> props` 可携带深层嵌套/大数组，虽然不进日志，仍会被 Spring/Jackson 反序列化进内存。

影响：

- 登录用户可用大 payload 消耗 console-api 内存/CPU。
- 如果网关没有严格 request body limit，这会成为低成本 DoS 面。

整改建议：

- 为 `/api/console/telemetry/events` 设置独立 body limit。
- 在 controller 或 custom validator 中限制 `props` 序列化后字节数、最大嵌套深度、每个 event 最大 props key 数。
- 只允许白名单 props key，敏感字段直接拒绝或脱敏。

### P2-3 HTTP 渠道健康探测 SSRF 加固弱于实际派发

实际派发链路：

```text
DispatchCommand
  -> HttpDispatchChannelAdapter
  -> DnsResolveGuard.resolveAndValidate()
  -> OkHttp Dns 固定到已校验 InetAddress
  -> POST target_endpoint
```

当前状态：实际派发已做 resolve-then-connect，`HttpDispatchChannelAdapter.java:101` 到 `:107` 会把 OkHttp DNS 固定到已校验 IP。

残留问题：

- 健康探测 `RemoteFilesystemDispatchSupport.probeHttp()` 在 `:377` 到 `:381` 只校验域名解析结果，随后 `:382` 到 `:389` 用 Java `HttpClient` 对原 URI 发 HEAD，未固定到已校验 IP。

影响：

- 健康探测路径仍存在 DNS rebinding TOCTOU 理论窗口。
- 由于只是 probe，不是文件实际派发，等级低于实际 dispatch SSRF。

整改建议：

- `probeHttp` 与 `HttpDispatchChannelAdapter` 统一实现，使用同一个 resolve-then-connect 工具。
- 或禁用生产环境对非 allowlist 域名的 HTTP probe。

### P2-4 Staging DAST 没有认证态，不能证明 Console API 被扫过

证据：

- `security-scan/src/main/java/com/example/batch/securityscan/ScanStep.java:82` 到 `:86` 只执行 `zap-baseline.py -t <target> -r <report>`。
- `.github/workflows/staging-gate.yml:100` 到 `:106` 只传 `--mode=dast --target-url=...`，没有登录、cookie、JWT、context、OpenAPI import。
- `docs/runbook/security-scan.md:186` 也写到若要扫完整控制台路径，需要先登录或配置 context。

影响：

- Console API 大多在 `/api/console/**` 下受角色保护，未认证 ZAP baseline 很可能只扫到 health/static/401 页面。
- “0 alerts”不能作为上线安全验收结论。

整改建议：

- 为 staging DAST 配置测试账号登录流程，导出 authenticated context。
- 导入 OpenAPI 并带 Cookie/JWT 扫描 `/api/console/**`。
- 至少把 Admin、Tenant User、Auditor 三类角色各跑一遍只读/写入边界扫描。

### P3-1 Calendar holiday mapper 层仍缺少 tenant/calendar 复合条件

当前服务层已修复：

- `DefaultConsoleCalendarApplicationService` 在更新/删除 holiday 前校验 parent calendar 属于当前租户，再校验 holiday 属于该 calendar。

残留设计问题：

- `CalendarHolidayMapper.xml` 仍有按全局 `id` 直接 `selectById/update/delete` 的 SQL。
- 只要未来有新调用方绕过 service 守卫，就会重新暴露越权风险。

整改建议：

- Mapper 层补 `calendar_id` 条件，更新/删除改成 `where id = ? and calendar_id = ?`。
- 或删除裸 `selectById/update/delete`，只暴露带 parent scope 的方法。

## 3. 业务场景链路验收

### 3.1 Console 登录、JWT Cookie、RBAC

结论：可上线。

检查点：

- `ConsoleSecurityConfiguration` 禁用了 HTTP Basic，Console 走 HttpOnly Cookie JWT。
- `/api/console/**` 默认要求有效角色，避免新 controller 漏 `@PreAuthorize` 直接裸露。
- 高危端点如 actuator loggers、worker drain/force offline、config publish、tenant 管理均有更严格角色约束。
- `ConsoleAiController` 无 `@PreAuthorize` 但有 `/api/console/**` 兜底与 `ConsoleAiAuthorizationService.assertAllowed()`，不是裸露接口。

风险：

- Push 公钥公开性与实际 Security 配置不一致，归入 P1-1。

### 3.2 Console API 与前端契约

结论：主体可上线，Push 契约阻断对应功能。

验证结果：

- `python3 scripts/ci/check-console-openapi-paths.py` 通过：OpenAPI 与 `Console*Controller` 的 304 个 `/api/console` 路由一致。
- 前端配对仓库检查发现 Push composable 与后端实际返回/参数不一致。

### 3.3 配置治理、租户初始化、发布审批

结论：可上线。

检查点：

- 关键写接口有 `@Idempotent`。
- `ConsoleTenantGuard` 在配置、API Key、Push、下载等服务层做租户解析/隔离。
- Config release/publish/gray/rollback 均限 ADMIN。

残留：

- Calendar holiday mapper 层防御可继续硬化，见 P3-1。

### 3.4 手动触发、补跑、Catch-up 审批

结论：可上线。

检查点：

- Console 到 Trigger 已通过 `TriggerInternalRestClient` 注入 `X-Internal-Secret`，不再复用污染的 `RestClient.Builder`。
- `DefaultTriggerService` 采用 trigger request + trigger outbox 同事务落库，提交后异步 relay 到 Kafka。
- Trigger outbox mapper 使用 `FOR UPDATE SKIP LOCKED`，并发 relay 不会重复抢同一批行。
- Catch-up 审批路径也使用内部 secret client。

### 3.5 Orchestrator launch、任务状态机、Outbox

结论：可上线。

检查点：

- Launch 有重复实例/批次日门闩/资源分配/dispatch 标记。
- Task claim 从 READY 到 RUNNING 使用状态与 version 条件。
- Worker renew 要求 RUNNING、worker owner、partition invocation id。
- Task outcome 只接受 RUNNING 状态上报，并校验 worker owner 与 invocation id；成功/失败/重试/timeout 路径都有 CAS。
- Outbox 积压 metrics 与 KEDA SQL 指向 `batch.outbox_event`。

### 3.6 Worker 消费、执行、租约续约、上报

结论：可上线。

检查点：

- Kafka listener 前置 `Semaphore.tryAcquire()`，实例内并发达到上限时暂停消费，避免无界堆积。
- 执行线程池有任务超时与 cancel 机制，listener 释放 permit。
- Orchestrator 5xx/网络异常不进 DLQ，避免 rolling restart 时把可恢复任务打死信。
- Worker report HTTP 全部失败后可写入 worker report outbox，poller claim 后重投，stale PUBLISHING 可恢复。

关注项：

- `HttpTaskExecutionClient` 仍注入单个 `RestClient.Builder` 并构建一次 client。当前它只在单处集中设置 baseUrl/header/factory，未发现 builder 被跨请求修改；不是上线阻断。长期建议与 Console internal client 风格统一改 `ObjectProvider<RestClient.Builder>`。

### 3.7 文件接收、处理、分发

结论：可上线。

检查点：

- NAS dispatch 解析 real path，可选 sandbox root，copy 有超时保护。
- OSS dispatch 使用 MinIO client，object key 归一化。
- HTTP/API dispatch 已做 DNS guard + OkHttp fixed DNS，实际派发 SSRF 主风险已控制。
- Stub/real adapter profile 与 order 已区分，生产不会被 stub 接管。

残留：

- HTTP probe SSRF 加固一致性，见 P2-3。

### 3.8 Webhook、Push、告警通知

结论：告警核心可上线；Webhook/Push 需要按功能开关处理。

检查点：

- Webhook 有签名、DNS guard、HTTP timeout、最多 3 次 burst retry、EXHAUSTED 后 relay 接力。
- Push sender 有 VAPID 初始化、失效 endpoint 清理。

阻断/风险：

- Push 前后端契约不通，见 P1-1。
- Webhook 不是可靠队列，见 P2-1。

### 3.9 Helm、配置、迁移、CI

结论：主要上线工程链路可用。

验证结果：

- `helm template` 使用 prod values + 必填 secret 可渲染。
- `validate-flyway-schema.sh` 通过 128 个迁移文件校验。
- `check-config-defaults-sync.py --check` 通过。
- `check-env-prod-sync.sh` 通过但提示 `.env.prod` 有生产专属 key；这属于期望差异，需要运维确认变量来源。
- `validate-kafka-topics.sh` 通过。
- `security-scan.sh --mode=secret` 通过，gitleaks 无泄漏。

残留：

- DAST 覆盖不足，见 P2-4。

## 4. 本轮执行的验证命令

| 命令 | 结果 |
| --- | --- |
| `python3 scripts/ci/check-console-openapi-paths.py` | 通过，304 routes 一致 |
| `bash scripts/ci/validate-kafka-topics.sh` | 通过，9 topics 一致 |
| `python3 scripts/ci/check-config-defaults-sync.py --check` | 通过 |
| `bash scripts/ci/check-env-prod-sync.sh` | 通过，带生产专属 key 提示 |
| `bash scripts/ci/validate-flyway-schema.sh` | 通过，128 migration files |
| `python3 scripts/codegen/gen-error-codes-dict.py --check` | 通过，14 条同步 |
| `mvn -q -DskipTests compile` | 通过 |
| `helm template batch-platform ./helm/batch-platform -f helm/values-prod.yaml ...` | 通过，渲染 1224 行 manifest，Secret keys 存在 |
| `bash scripts/ci/security-scan.sh -- --mode=secret` | 通过，gitleaks no leaks found |
| `git status --short` | 干净 |

## 5. 上线前整改清单

必须在全量上线前处理：

1. 修复 Web Push 前后端契约，或生产关闭 Push 功能并移除入口。
2. 明确 Webhook 产品语义：可靠通知则改 outbox；best-effort 则加 drop 指标/告警/文档。
3. 给 telemetry events 增加 body/props 限制。
4. 补 authenticated DAST，至少覆盖 Admin/Tenant User/Auditor 的 Console API。

建议在灰度前处理：

1. `probeHttp` 与 dispatch HTTP 复用 resolve-then-connect。
2. Calendar holiday mapper 改为 parent-scoped SQL。
3. Worker `HttpTaskExecutionClient` 改 `ObjectProvider<RestClient.Builder>`，与其他 internal client 保持一致。

可作为运维上线 checklist：

1. Helm install/upgrade 必须传入 `security.internalSecret` 与 `security.consoleJwtSecret`。
2. 生产 Redis/Kafka/PostgreSQL/MinIO 密钥由 Secret 管理，不从默认值继承。
3. 首批灰度只开核心批量链路；Push/Webhook 若未整改，功能开关关闭或标注 best-effort。
4. 上线当天重点看 outbox backlog、worker lease renew failure、worker report outbox give_up、webhook dropped/failed、console 4xx/5xx。
