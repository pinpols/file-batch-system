# 后端深度扫描 Bug 与设计缺陷报告

扫描日期：2026-05-14  
范围：后端工程、Console BFF、Orchestrator/Trigger/Worker 内部调用、CI/契约检查、核心事务链路抽样。

## 结论

本轮按高风险面深扫后，未在 Trigger Outbox 主链路发现新的状态机缺陷；`DefaultTriggerService` 会明确写入 `OutboxPublishStatus.NEW`。

但 Console BFF 仍存在生产级阻断问题：高风险操作缺少角色授权、文件治理缺少租户防护、Console 到 Orchestrator 的大量 `/internal/**` 调用缺少 `X-Internal-Secret`，并且共享 `RestClient.Builder` 的设计会制造跨下游污染和并发不确定性。

## P0 必须整改

### 1. Console 高风险控制器缺少角色授权

`ConsoleSecurityConfiguration` 对未显式匹配的 `/api/console/**` 只要求登录，未兜底要求角色；多个控制器没有 `@PreAuthorize`。结果是任意已认证用户可访问审批、文件治理、批次日重放、结果版本 promote/reject 等高危操作。

证据：

- `batch-console-api/src/main/java/com/example/batch/console/config/ConsoleSecurityConfiguration.java:60`
- `batch-console-api/src/main/java/com/example/batch/console/web/ConsoleApprovalController.java:23`
- `batch-console-api/src/main/java/com/example/batch/console/web/ConsoleApprovalController.java:34`
- `batch-console-api/src/main/java/com/example/batch/console/web/ConsoleFileController.java:29`
- `batch-console-api/src/main/java/com/example/batch/console/web/ConsoleResultVersionController.java:26`
- `batch-console-api/src/main/java/com/example/batch/console/web/ConsoleBatchDayReplayController.java:26`

建议：所有写操作控制器补齐最小权限 `@PreAuthorize`；SecurityFilterChain 增加 `/api/console/**` 的保守兜底角色；补回越权回归测试。

### 2. 文件治理 BFF 缺少租户隔离校验

`DefaultConsoleFileApplicationService` 未注入或调用 `ConsoleTenantGuard`，直接信任 body/query 中的 `tenantId` 并转发到 Orchestrator。结合控制器缺少角色授权，已登录用户可构造其他租户的文件归档、删除、重派、预签上传、到达确认请求。

证据：

- `batch-console-api/src/main/java/com/example/batch/console/web/ConsoleFileController.java:40`
- `batch-console-api/src/main/java/com/example/batch/console/web/ConsoleFileController.java:48`
- `batch-console-api/src/main/java/com/example/batch/console/web/ConsoleFileController.java:86`
- `batch-console-api/src/main/java/com/example/batch/console/infrastructure/file/DefaultConsoleFileApplicationService.java:52`
- `batch-console-api/src/main/java/com/example/batch/console/infrastructure/file/DefaultConsoleFileApplicationService.java:58`
- `batch-console-api/src/main/java/com/example/batch/console/infrastructure/file/DefaultConsoleFileApplicationService.java:168`

建议：所有入口统一 `tenantGuard.resolveTenant(...)` 后再转发；禁止服务层直接使用请求体租户；补跨租户文件操作 403 测试。

### 3. Console 到 Orchestrator 内部接口缺少 `X-Internal-Secret`

Orchestrator 的 `/internal/**` 会在非 bypass 模式校验 `X-Internal-Secret`，但 Console 多个 Orchestrator 代理只设置 baseUrl，没有注入内部密钥。生产关闭 bypass 后，这些 Console 操作会 401。

证据：

- `batch-orchestrator/src/main/java/com/example/batch/orchestrator/config/InternalAuthFilter.java:41`
- `batch-console-api/src/main/java/com/example/batch/console/infrastructure/ops/DefaultConsoleOrchestratorProxyService.java:35`
- `batch-console-api/src/main/java/com/example/batch/console/infrastructure/ops/DefaultConsoleOrchestratorProxyService.java:135`
- `batch-console-api/src/main/java/com/example/batch/console/infrastructure/ops/DefaultConsoleApprovalApplicationService.java:117`
- `batch-console-api/src/main/java/com/example/batch/console/infrastructure/file/DefaultConsoleFileApplicationService.java:116`
- `batch-console-api/src/main/java/com/example/batch/console/web/ConsoleBatchDayReplayController.java:119`
- `batch-console-api/src/main/java/com/example/batch/console/web/ConsoleResultVersionController.java:121`

建议：抽一个 Orchestrator 专用 `RestClient` Bean 或 factory，固定 baseUrl 与 `X-Internal-Secret`；禁止业务类自行拼 builder。

## P1 高优先级

### 4. `RestClient.Builder` 是单例可变对象，存在并发污染

项目自定义了单例 `RestClient.Builder` Bean，多个服务在请求路径上反复调用 `baseUrl()` / `defaultHeader()`。`RestClient.Builder` 是可变 builder，不适合跨线程共享并动态修改；一个服务设置的 baseUrl/header 可能泄漏到另一个服务。

证据：

- `batch-common/src/main/java/org/springframework/boot/autoconfigure/web/client/RestClientAutoConfiguration.java:16`
- `batch-console-api/src/main/java/com/example/batch/console/infrastructure/ops/DefaultConsoleTriggerProxyService.java:34`
- `batch-console-api/src/main/java/com/example/batch/console/infrastructure/ops/DefaultConsoleOrchestratorProxyService.java:36`
- `batch-trigger/src/main/java/com/example/batch/trigger/config/QuartzTriggerConfiguration.java:20`

建议：改为 `ObjectProvider<RestClient.Builder>` 每次取新 builder，或定义 trigger/orchestrator/worker 专用不可变 `RestClient` Bean。

### 5. 批次日重放 entries/submit 租户处理不一致

`submit` 直接转发请求体，未解析当前登录租户；`entries` 不接收 tenantId、不调用 `ConsoleTenantGuard`，并用字符串拼 query。若内部接口未自行强租户校验，会出现跨租户查询/提交风险。

证据：

- `batch-console-api/src/main/java/com/example/batch/console/web/ConsoleBatchDayReplayController.java:36`
- `batch-console-api/src/main/java/com/example/batch/console/web/ConsoleBatchDayReplayController.java:98`
- `batch-console-api/src/main/java/com/example/batch/console/web/ConsoleBatchDayReplayController.java:103`

建议：submit 强制覆盖 body tenantId；entries 增加 tenantId 解析并使用 UriBuilder。

### 6. 结果版本与批次日重放写操作缺少 BFF 幂等保护

静态扫描发现部分 mutating endpoint 没有 `@Idempotent`，包括批次日重放 submit/approve/cancel、结果版本 promote/reject。下游可能有状态机保护，但 BFF 层没有统一重复请求记录和审计关联。

证据：

- `batch-console-api/src/main/java/com/example/batch/console/web/ConsoleBatchDayReplayController.java:36`
- `batch-console-api/src/main/java/com/example/batch/console/web/ConsoleBatchDayReplayController.java:47`
- `batch-console-api/src/main/java/com/example/batch/console/web/ConsoleBatchDayReplayController.java:66`
- `batch-console-api/src/main/java/com/example/batch/console/web/ConsoleResultVersionController.java:89`
- `batch-console-api/src/main/java/com/example/batch/console/web/ConsoleResultVersionController.java:105`

建议：写操作统一要求 `X-Idempotency-Key`；对不需要幂等的 telemetry/auth 单独列白名单。

## P2 建议整改

### 7. OpenAPI 与 Controller 漂移

自动契约脚本失败：Controller 存在 `POST /api/console/auth/logout`，但 `docs/api/console-api.openapi.yaml` 未声明，前端生成类型会漏接口。

证据：

- `batch-console-api/src/main/java/com/example/batch/console/web/ConsoleAuthController.java:65`
- `docs/api/console-api.openapi.yaml:125`

建议：补 OpenAPI logout 路径，并重新生成前端 `../batch-console/src/types/api.generated.ts`。

### 8. 内部密钥占位符校验仍不够严格

`BatchSecurityProperties.validateNotPlaceholder` 只拒绝大写 `CHANGE_ME` 前缀，未覆盖 `change-me` / `change_me` 等常见占位符，也没有对 `internal-secret` 做长度/强度下限。

证据：

- `batch-common/src/main/java/com/example/batch/common/config/BatchSecurityProperties.java:68`

建议：与 Console JWT secret 策略对齐，统一做 trim、大小写归一、占位符 contains、长度下限。

### 9. `jwtClockSkew` 配置未被使用

`ConsoleSecurityProperties` 暴露了 `jwtClockSkew`，但 JWT decoder 初始化没有设置 clock skew。运维调整该配置不会生效。

证据：

- `batch-console-api/src/main/java/com/example/batch/console/config/ConsoleSecurityProperties.java:58`
- `batch-console-api/src/main/java/com/example/batch/console/support/auth/ConsoleJwtService.java:96`

建议：给 `NimbusJwtDecoder` 配置带 clock skew 的 validator，或删除无效配置。

### 10. 自定义类占用 Spring Boot 官方包名

项目把自定义 `RestClientAutoConfiguration` 放在 `org.springframework.boot.autoconfigure.web.client` 包下。该命名空间属于 Spring Boot，未来升级可能发生 FQCN/自动装配语义冲突。

证据：

- `batch-common/src/main/java/org/springframework/boot/autoconfigure/web/client/RestClientAutoConfiguration.java:1`

建议：迁移到 `com.example.batch.common.config` 并通过 auto-configuration imports 注册。

## 验证记录

- 通过：`mvn -q -pl batch-console-api -DskipTests compile`
- 通过：`mvn -q -pl batch-console-api -Dtest=ConsoleSecurityConfigurationTest,ConsoleTenantGuardTest,ConsoleIdempotencyInterceptorTest test`
- 失败：`python3 scripts/ci/check-console-openapi-paths.py`
  - 原因：`POST /api/console/auth/logout` 在 Controller 中存在但 OpenAPI 缺失。
- 未发现残留软失败：`.github/workflows` 下未匹配到 `continue-on-error` / `exit-code: 0` / `soft_fail: true`。

