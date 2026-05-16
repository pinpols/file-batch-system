# 后端深度扫描复核报告

- 日期：2026-05-16
- 范围：`file-batch-system` 后端全仓，含 Console API、Trigger、Orchestrator、Worker、Dispatch、迁移、CI、部署脚本、OpenAPI 与配对前端契约抽查。
- 方式：代码审计 + 静态脚本门禁 + Maven 编译验证 + 前端调用点抽查。
- 结论：本轮仍发现 2 个生产 P0、4 个 P1、3 个 P2。当前不建议进入生产验收，需先完成 P0/P1 整改并重跑门禁。

## P0-1 Console 触发链路未向 Trigger 注入内部密钥

**现象**

`batch-trigger` 对除 `/actuator/**` 外所有端点都要求 `X-Internal-Secret`。但 Console 调用 Trigger 的核心作业触发和 Catch-Up 审批路径没有设置该 header。

**证据**

- `batch-trigger/src/main/java/com/example/batch/trigger/config/TriggerSecurityConfiguration.java:41` 定义安全链。
- `batch-trigger/src/main/java/com/example/batch/trigger/config/TriggerSecurityConfiguration.java:45` 将非 actuator 请求设为 `authenticated()`。
- `batch-trigger/src/main/java/com/example/batch/trigger/config/TriggerSecurityConfiguration.java:86` 读取 `X-Internal-Secret`。
- `batch-trigger/src/main/java/com/example/batch/trigger/config/TriggerSecurityConfiguration.java:93` 密钥缺失或错误直接 401。
- `batch-console-api/src/main/java/com/example/batch/console/infrastructure/query/ConsoleJobOpsSupport.java:98` 构造 Trigger `RestClient`。
- `batch-console-api/src/main/java/com/example/batch/console/infrastructure/query/ConsoleJobOpsSupport.java:101` 调 `POST /api/triggers/launch`，只带幂等键、requestId、traceId。
- `batch-console-api/src/main/java/com/example/batch/console/infrastructure/job/DefaultConsoleJobApprovalService.java:149` 构造 Trigger `RestClient`。
- `batch-console-api/src/main/java/com/example/batch/console/infrastructure/job/DefaultConsoleJobApprovalService.java:152` 调 `POST /api/triggers/catch-up/approve`，同样缺内部密钥。

**影响**

生产 profile 关闭 bypass 后，Console 的手工触发、批量触发、自动/手动 Catch-Up 审批回调 Trigger 会稳定返回 401。前台表现为触发失败，后端不会进入 trigger outbox。

**整改建议**

- 所有 Console → Trigger 调用统一走一个 `TriggerInternalRestClient`，注入 baseUrl、`X-Internal-Secret`、连接/读取超时。
- 禁止业务类直接持有 `RestClient.Builder` 后自行拼 Trigger client。
- 增加集成测试：`batch.security.bypass-mode=false` 时 Console 触发路径必须带 `X-Internal-Secret`。

## P0-2 日历节假日导入在 V92 后必然写入失败

**现象**

迁移 V92 将 `batch.calendar_holiday.tenant_id` 设为 `NOT NULL`，`batchInsert` 也插入 `tenant_id`。但 Console 导入节假日时构造的 map 没有放入 `tenantId`。

**证据**

- `db/migration/V92__calendar_holiday_tenant_id.sql:18` 将 `tenant_id` 设为 `NOT NULL`。
- `batch-console-api/src/main/resources/mapper/CalendarHolidayMapper.xml:32` 批量插入包含 `tenant_id`。
- `batch-console-api/src/main/resources/mapper/CalendarHolidayMapper.xml:37` 从 `#{item.tenantId}` 取值。
- `batch-console-api/src/main/java/com/example/batch/console/infrastructure/config/DefaultConsoleCalendarApplicationService.java:121` 解析出 `tenantId`。
- `batch-console-api/src/main/java/com/example/batch/console/infrastructure/config/DefaultConsoleCalendarApplicationService.java:129` 到 `:134` 构造导入行，但未写 `tenantId`。
- `batch-console-api/src/main/java/com/example/batch/console/infrastructure/config/DefaultConsoleCalendarApplicationService.java:138` 调用 `holidayMapper.batchInsert(list)`。

**影响**

`POST /api/console/calendars/{id}/holidays` 在当前 schema 下会插入 `NULL tenant_id`，数据库拒绝写入。业务日历配置不可用，后续调度日历、补单日历、节假日跳过策略都受影响。

**整改建议**

- 导入 map 写入 `m.put("tenantId", tenantId)`。
- 补一个 Mapper 或 Service 测试，覆盖 V92 后的导入路径。
- 顺手补单条 `insert` 的 `tenant_id` 支持，避免未来复用单插路径再次踩坑。

## P1-1 日历节假日更新/删除缺少 holidayId 归属校验

**现象**

更新/删除接口只校验父日历 `id` 属于当前租户，但 `holidayId` 查询、更新、删除只按全局 id 操作，没有校验该 holiday 是否属于该 calendar/tenant。

**证据**

- `batch-console-api/src/main/java/com/example/batch/console/infrastructure/config/DefaultConsoleCalendarApplicationService.java:144` 进入更新节假日。
- `batch-console-api/src/main/java/com/example/batch/console/infrastructure/config/DefaultConsoleCalendarApplicationService.java:146` 校验 calendar 属于租户。
- `batch-console-api/src/main/java/com/example/batch/console/infrastructure/config/DefaultConsoleCalendarApplicationService.java:148` 仅按 `holidayId` 查询 holiday。
- `batch-console-api/src/main/java/com/example/batch/console/infrastructure/config/DefaultConsoleCalendarApplicationService.java:156` 仅按 `holidayId` 更新。
- `batch-console-api/src/main/java/com/example/batch/console/infrastructure/config/DefaultConsoleCalendarApplicationService.java:163` 进入删除节假日。
- `batch-console-api/src/main/java/com/example/batch/console/infrastructure/config/DefaultConsoleCalendarApplicationService.java:167` 仅按 `holidayId` 删除。
- `batch-console-api/src/main/resources/mapper/CalendarHolidayMapper.xml:15`、`:41`、`:56` 均没有 `tenant_id` 或 `calendar_id` 条件。

**影响**

具备日历管理权限的调用方可以传自己租户的 calendar id，同时传另一个 calendar/tenant 的 holiday id，造成跨日历或跨租户修改/删除。

**整改建议**

- Mapper 增加 `selectByTenantCalendarAndId`、`updateByTenantCalendarAndId`、`deleteByTenantCalendarAndId`。
- Service 在返回和缓存失效前确认受影响行数。
- 增加跨租户负向测试。

## P1-2 OpenAPI、Controller、前端调用已漂移

**现象**

脚本 `python3 scripts/ci/check-console-openapi-paths.py` 当前失败。OpenAPI 仍声明 Web Push 端点，但后端没有对应 Console Controller；后端新增 Mermaid 端点，但 OpenAPI 未声明。

**复现输出**

```text
Console OpenAPI vs Controller path mismatch.

In OpenAPI but not in controller code:
  GET /api/console/push/vapid-public-key
  POST /api/console/push/subscribe
  POST /api/console/push/unsubscribe

In Console*Controller but not in OpenAPI:
  GET /api/console/workflow-definitions/{id}/mermaid
```

**证据**

- `docs/api/console-api.openapi.yaml:7486` 声明 `GET /api/console/push/vapid-public-key`。
- `docs/api/console-api.openapi.yaml:7501` 声明 `POST /api/console/push/subscribe`。
- `docs/api/console-api.openapi.yaml:7526` 声明 `POST /api/console/push/unsubscribe`。
- `../batch-console/src/composables/useWebPush.ts:10` 到 `:12` 前端仍按上述端点实现。
- `batch-console-api/src/main/java/com/example/batch/console/web/ConsoleWorkflowDefinitionController.java:80` 暴露 `GET /api/console/workflow-definitions/{id}/mermaid`。
- `../batch-console/src/api/workflow.ts:140` 到 `:144` 前端已调用 Mermaid 端点。

**影响**

Web Push 前端功能运行时 404；Mermaid 功能虽后端存在，但生成的 TS 类型缺失，后续前端重新生成类型会丢契约或绕开类型系统。

**整改建议**

- 如果 Web Push 暂不交付，删除 OpenAPI 与前端入口；如果要交付，补 Controller、Service、存储、鉴权与测试。
- 将 Mermaid endpoint 补入 OpenAPI，并重新生成 `../batch-console/src/types/api.generated.ts`。

## P1-3 Kafka Topic 配置缺 verifier failure 专用 topic

**现象**

`BatchTopics` 新增 `batch.verifier.failure.v1`，Orchestrator outbox publisher 会将 `verifier.failure.v1` 事件路由到该专用 topic。但 `.env.prod` / `.env.example` / `.env.local` 的 `KAFKA_TOPICS` 未包含该 topic。

**复现输出**

```text
✅ 8 topics in .env.prod pass naming check
❌ ERROR: BatchTopics.java active constants missing from .env.prod KAFKA_TOPICS:
   - batch.verifier.failure.v1
```

**证据**

- `batch-common/src/main/java/com/example/batch/common/kafka/BatchTopics.java:23` 定义 `VERIFIER_FAILURE_V1`。
- `batch-orchestrator/src/main/java/com/example/batch/orchestrator/infrastructure/mq/KafkaOutboxPublisher.java:130` 到 `:135` 将 verifier failure 发送到专用 topic。
- `batch-orchestrator/src/main/java/com/example/batch/orchestrator/infrastructure/mq/KafkaOutboxPublisher.java:211` 到 `:215` 映射 `verifier.failure.v1`。
- `.env.prod:16` 的 `KAFKA_TOPICS` 未包含该 topic。
- `.env.example:58`、`.env.local:19` 同样缺失。

**影响**

禁用 Kafka auto-create 的生产集群中，ContentVerifier 失败告警事件会发布失败，outbox delivery 反复失败或最终告警缺失。该问题已有门禁脚本覆盖，但当前仓库未通过。

**整改建议**

- 同步三份 env 的 `KAFKA_TOPICS`。
- 同步 `scripts/data/init-kafka-topics.sh` 默认值。
- Console 事件目录也应补 `TASK_DISPATCH_PROCESS`、`TRIGGER_LAUNCH_V1`、`VERIFIER_FAILURE_V1`，避免运维 UI 展示不完整。

## P1-4 PR/Staging 安全扫描使用 `--skip-build`，fresh runner 可能没有 jar

**现象**

`security-scan` 是独立 Maven 模块，不在根 reactor 中。PR 与 Staging workflow 直接运行 `security-scan.sh --skip-build`，但 setup action 不构建 `security-scan` jar。

**证据**

- `pom.xml:24` 到 `:35` 根 `<modules>` 未包含 `security-scan`。
- `.github/actions/setup-build-env/action.yml:17` 到 `:21` 只做 OpenAPI 校验。
- `.github/actions/setup-build-env/action.yml:30` 到 `:34` 只可选编译 `load-tests`。
- `.github/workflows/pr-gate.yml:209` 到 `:212` 用 `security-scan.sh --skip-build -- --mode=secret`。
- `.github/workflows/staging-gate.yml:100` 到 `:105` 用 `security-scan.sh --skip-build -- --mode=dast`。
- `scripts/ci/security-scan.sh:66` 到 `:68` 只有未 skip 时才 `mvn -f security-scan/pom.xml package`。
- `scripts/ci/security-scan.sh:74` 到 `:77` jar 不存在会退出 1。

**影响**

干净 CI runner 上没有 `security-scan/target/security-scan-*.jar`，PR secret scan 或 Staging DAST 会在扫描前失败。Full CI 因先跑了一次不带 skip 的 secret scan，所以 deps 阶段相对安全，但 PR/Staging 不安全。

**整改建议**

- PR/Staging 去掉 `--skip-build`，或在 setup action 显式构建 `mvn -q -f security-scan/pom.xml package`。
- 为 `security-scan.sh --skip-build` 增加 CI 预检提示，避免误判为扫描失败。

## P2-1 `RestClient.Builder` prototype 修复未完全落地

**现象**

`RestClient.Builder` bean 已改为 prototype，但多个单例 Service 仍将 prototype builder 直接注入为字段。Spring 只会在创建单例时解析一次 prototype，因此这些类内部仍复用同一个可变 builder。

**证据**

- `batch-common/src/main/java/com/example/batch/common/config/BatchRestClientAutoConfiguration.java:38` 到 `:41` 声明 prototype builder。
- `batch-console-api/src/main/java/com/example/batch/console/infrastructure/ops/OrchestratorInternalRestClient.java:34` 持有 builder 字段。
- `batch-console-api/src/main/java/com/example/batch/console/infrastructure/ops/OrchestratorInternalRestClient.java:51` 到 `:63` 每次 build 都 mutate 同一字段实例。
- `batch-console-api/src/main/java/com/example/batch/console/infrastructure/ops/DefaultConsoleTriggerProxyService.java:25` 持有 builder 字段，`:31` 到 `:35` mutate。
- `batch-console-api/src/main/java/com/example/batch/console/infrastructure/query/ConsoleJobOpsSupport.java:65` 持有 builder 字段，`:98` 到 `:99` mutate。
- `batch-console-api/src/main/java/com/example/batch/console/infrastructure/job/DefaultConsoleJobApprovalService.java:42` 持有 builder 字段，`:149` 到 `:150` mutate。
- `batch-worker-core/src/main/java/com/example/batch/worker/core/infrastructure/HttpTaskExecutionClient.java:49` 持有 builder 字段，`:469` 到 `:474` mutate。
- `batch-console-api/src/main/java/com/example/batch/console/service/WebhookDispatcher.java:67` 持有 builder 字段，`:222` 到 `:231` mutate requestFactory。

**影响**

风险已低于全局 singleton builder，但在同一个单例内仍可能出现并发 mutate、header/baseUrl/requestFactory 残留、未来新增多 baseUrl 时串配置等问题。

**整改建议**

- 改为注入 `ObjectProvider<RestClient.Builder>`，每次构造 client 时 `getObject()`。
- 或直接定义不可变 `RestClient` Bean：`orchestratorInternalRestClient`、`triggerInternalRestClient`、`workerTaskRestClient`。
- 增加 ArchUnit/静态脚本：禁止单例字段直接注入 `RestClient.Builder`。

## P2-2 Telemetry 入站 payload 缺少大小和字段边界

**现象**

Telemetry endpoint 只限制 events 数量、type/name 长度；`userId`、`sessionId`、`page`、`ts`、`props` 没有大小限制，也没有 props key/value 数量和长度限制。Controller 将 props JSON 打入日志。

**证据**

- `batch-console-api/src/main/java/com/example/batch/console/web/request/auth/FrontendTelemetryRequest.java:10` 到 `:20` 定义请求体。
- `batch-console-api/src/main/java/com/example/batch/console/web/ConsoleTelemetryController.java:29` 接收 telemetry。
- `batch-console-api/src/main/java/com/example/batch/console/web/ConsoleTelemetryController.java:52` 到 `:59` 直接将 props 序列化进日志。
- `batch-console-api/src/main/java/com/example/batch/console/support/ratelimit/ConsoleRateLimitFilter.java:57` 到 `:82` 目前限流仅覆盖登录和触发类路径，不覆盖 telemetry。

**影响**

登录用户可发送大 props 造成日志放大、Loki/存储成本上升，或将敏感字段写入日志。若前端异常循环上报，后端日志压力会扩大。

**整改建议**

- 对 `userId/sessionId/page/ts` 加 `@Size`。
- props 限制 map size、key 长度、value JSON 总大小，并对 token/password/secret 等字段脱敏。
- Telemetry 加独立限流或采样。

## P2-3 Console 事件目录与实际 topic 常量不一致

**现象**

Console `/api/console/event-catalog/topics` 只列出部分 topic，缺 `TASK_DISPATCH_PROCESS`、`TRIGGER_LAUNCH_V1`、`VERIFIER_FAILURE_V1`。

**证据**

- `batch-console-api/src/main/java/com/example/batch/console/web/ConsoleEventCatalogController.java:52` 到 `:61` 构造 topic 列表。
- `batch-common/src/main/java/com/example/batch/common/kafka/BatchTopics.java:7` 定义 `TASK_DISPATCH_PROCESS`。
- `batch-common/src/main/java/com/example/batch/common/kafka/BatchTopics.java:16` 定义 `TRIGGER_LAUNCH_V1`。
- `batch-common/src/main/java/com/example/batch/common/kafka/BatchTopics.java:23` 定义 `VERIFIER_FAILURE_V1`。

**影响**

运维订阅目录不完整，事件总线 UI/文档会漏展示生产真实 topic。该问题不一定阻断核心链路，但会影响告警订阅和排障。

**整改建议**

- Event catalog 从 `BatchTopics` 单一来源生成，或补齐所有 active topic。
- 增加测试：catalog active topics 与 `BatchTopics` 保持同步，白名单只排除纯内部元数据。

## 已验证通过项

- `mvn -q -DskipTests compile`：通过。
- `python3 scripts/ci/check-config-defaults-sync.py --check`：通过。
- `bash scripts/ci/validate-flyway-schema.sh`：通过，但输出历史 gap warning：期望 V31 实际 V32。脚本最终认可 127 个迁移文件。
- `python3 scripts/codegen/gen-error-codes-dict.py --check`：通过，14 条同步。
- `bash scripts/ci/check-env-prod-sync.sh`：通过，但提示 `.env.prod` 有 prod-specific key 缺 `.env.example`，当前脚本判定为 WARN。

## 当前失败门禁

- `python3 scripts/ci/check-console-openapi-paths.py`：失败，见 P1-2。
- `bash scripts/ci/validate-kafka-topics.sh`：失败，见 P1-3。

## 建议整改顺序

1. 先修 P0-1：抽 `TriggerInternalRestClient`，统一注入 `X-Internal-Secret` 和超时。
2. 修 P0-2 / P1-1：日历 holiday 全链路补 `tenant_id + calendar_id` 条件。
3. 修 P1-2 / P1-3：让 OpenAPI 和 Kafka topic 两个门禁恢复绿色。
4. 修 P1-4：让 security-scan 在 PR/Staging fresh runner 上可运行。
5. 扫尾 P2：`RestClient.Builder` 注入模式、Telemetry 限制、Event catalog 同步。

## 验收标准

- P0/P1 全部有自动化测试或门禁脚本覆盖。
- 以上失败命令全部通过。
- 前端 `../batch-console/src/types/api.generated.ts` 与后端 OpenAPI 同步。
- 生产 profile 下手工触发、Catch-Up 审批、日历节假日导入、verifier failure outbox 发布至少各跑一条冒烟验证。
