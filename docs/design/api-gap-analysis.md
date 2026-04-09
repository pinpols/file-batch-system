# API 缺口分析（按角色视角）

> 基于现有 250+ 接口盘点，从四个核心角色视角识别缺失接口。
> 状态校准日期：2026-04-10（按当前仓库代码核对）。
> 标记说明：已完成 = 已有可用接口/实现；部分完成 = 已有 MVP 或部分链路，但未完全覆盖本文最初目标。

## 当前完成情况（2026-04-09）

### 已完成

- OpenAPI / Swagger 文档
- 优雅停机（Orchestrator / Trigger drain + graceful shutdown + `server.shutdown=graceful`）
- Kafka 消费积压查询
- 配置 Diff
- 配置依赖分析
- 批量启停
- 租户用量查询
- 执行进度查询（轻量）
- 批量 API（批量触发、批量状态查询）
- 幂等 key 支持
- 调试日志按需开启
- 系统参数热更新（CRUD + Redis 缓存 + `ConsoleSystemParameterController`）
- 沙箱 / 模拟执行（console 入口 `dryRun=true` 校验不执行）
- Webhook 回调注册（订阅 CRUD + HMAC 签名 + 异步投递 + 投递日志 + 领域事件监听）
- 慢任务诊断（`sortBy=duration` + `minDurationSeconds` 阈值过滤）
- 标签 / 分组管理（V46 `resource_tag` 表 + CRUD + 按标签反查）
- API Key 自助管理（V47 `api_key` 表 + 创建/列表/吊销 + SHA-256 哈希存储）

### 部分完成 → 已全部完成

- ~~全局熔断 / 限流~~ → ConsoleGovernanceController
- ~~事件总线 / 订阅~~ → webhook + ConsoleEventCatalogController
- ~~优雅停机 / 预热~~ → GracefulShutdown + Worker warmup endpoint

### 仍未完成

- 通知订阅管理
- 配置审批流
- 环境间配置同步

### 部分完成 → 已全部完成（本轮）

- ~~Outbox 积压 & 清理~~ → `ConsoleOpsController` outbox/stats + outbox/cleanup + outbox/republish
- ~~SLA 报表~~ → `ConsoleDashboardController` GET /sla-report
- ~~文件错误记录导出~~ → `ConsoleFileDownloadController` GET /{fileId}/errors/export
- ~~配置导入深度校验~~ → Excel preview 增加 queue/calendar/window 跨引用校验
- ~~配置脚手架（Clone Job）~~ → `ConsoleJobDefinitionController` POST /{id}/clone + `JobDefinitionCopyRequest`
- ~~SDK 客户端生成~~ → Maven `openapi-codegen` profile + `scripts/ci/generate-sdk.sh`

---

## 1. 生产运维（SRE / Ops）

| 缺口 | 说明 | 优先级 |
|---|---|---|
| ~~健康检查 & 就绪探针~~ | ~~已实现：actuator + liveness/readiness 已在 batch-defaults.yml 全局配置~~ | ~~已完成~~ |
| ~~优雅停机 / 预热~~ | ~~已完成：优雅停机 + Worker warmup endpoint `POST /api/console/workers/{workerCode}/warmup`~~ | ~~已完成~~ |
| ~~全局熔断 / 限流~~ | ~~已完成：`ConsoleGovernanceController` 管理 8 项 governance 参数（outbox/dispatch CB、login/sensitive/launch/release 限流）~~ | ~~已完成~~ |
| ~~Kafka 消费积压查询~~ | ~~已完成：GET /api/console/ops/kafka-lag 查询 batch 相关 consumer group 积压~~ | ~~已完成~~ |
| ~~Outbox 积压 & 清理~~ | ~~已完成：GET /api/console/ops/outbox/stats + POST /cleanup + POST /republish~~ | ~~已完成~~ |
| ~~慢任务 / 长尾任务诊断~~ | ~~已完成：`sortBy=duration` + `minDurationSeconds` 阈值过滤参数~~ | ~~已完成~~ |
| ~~系统参数热更新~~ | ~~已完成：V44 migration + `ConsoleSystemParameterController` CRUD + Redis 缓存（`sys-param:{tenant}:{key}`，TTL 30min）~~ | ~~已完成~~ |
| ~~数据归档 / 清理策略~~ | ~~已完成：V48 migration + `ConsoleArchivePolicyController` CRUD（target_table CHECK 约束 + retention_days/archive/cleanup/batch_size）~~ | ~~已完成~~ |
| ~~跨节点一致性检查~~ | ~~已完成：`ConsoleClusterDiagnosticController`（shedlock 租约 + worker 一致性 + outbox 健康）~~ | ~~已完成~~ |

---

## 2. 配置管理（Config Admin）

| 缺口 | 说明 | 优先级 |
|---|---|---|
| ~~配置对比（Diff）~~ | ~~已完成：GET /api/console/config/releases/diff 对比两个版本 payload/grayScope/status~~ | ~~已完成~~ |
| ~~配置依赖分析~~ | ~~已完成：GET /api/console/config/dependencies 查询引用指定配置的 job 列表~~ | ~~已完成~~ |
| ~~批量启用 / 禁用~~ | ~~已完成：POST /api/console/job-definitions/batch-toggle 批量启停~~ | ~~已完成~~ |
| ~~配置导入 dry-run 校验~~ | ~~已完成：Excel preview 增加 queue_code/calendar_code/window_code 跨引用校验~~ | ~~已完成~~ |
| ~~配置模板 / 脚手架~~ | ~~已完成：POST /api/console/job-definitions/{id}/clone 支持字段覆盖（JobDefinitionCopyRequest）~~ | ~~已完成~~ |
| 环境间配置同步 | 缺少跨环境（dev -> staging -> prod）配置推送/拉取接口 | P1 |
| 配置审批流 | config release 有发布/灰度/回滚，缺少审批环节（谁批准了这次发布？） | P1 |
| ~~标签 / 分组管理~~ | ~~已完成：V46 `resource_tag` 表 + `ConsoleResourceTagController` 打标签/按标签反查~~ | ~~已完成~~ |

---

## 3. 租户使用（Tenant / Business User）

| 缺口 | 说明 | 优先级 |
|---|---|---|
| ~~自助租户管理~~ | ~~已完成：`ConsoleTenantSelfServiceController`（quota/usage/quota-request）~~ | ~~已完成~~ |
| ~~文件上传 / 到达确认~~ | ~~已完成：`presignUpload` + `confirmArrival` in `ConsoleFileController`~~ | ~~已完成~~ |
| ~~执行进度查询（轻量）~~ | ~~已完成：GET /api/console/dashboard/execution-progress 按 jobCode+bizDate 返回分区进度~~ | ~~已完成~~ |
| ~~自助重跑 / 补偿申请~~ | ~~已完成：`ConsoleSelfServiceJobController`（rerun-request/compensation-request → 审批工作流）~~ | ~~已完成~~ |
| ~~SLA 报表 / 账单~~ | ~~已完成：GET /api/console/dashboard/sla-report 按 job 维度 SLA 达成率、成功/失败/耗时统计~~ | ~~已完成~~ |
| 通知订阅管理 | 缺少租户自助配置告警通知渠道（邮件/webhook/钉钉）、订阅/退订接口 | P1 |
| ~~文件处理结果下载~~ | ~~已完成：GET /api/console/files/{fileId}/errors/export 导出错误记录 CSV~~ | ~~已完成~~ |
| ~~API Key / 凭证自助管理~~ | ~~已完成：V47 `api_key` 表 + `ConsoleApiKeyController` 创建/列表/吊销~~ | ~~已完成~~ |

---

## 4. 开发人员（Developer / Integration）

| 缺口 | 说明 | 优先级 |
|---|---|---|
| ~~OpenAPI / Swagger 文档~~ | ~~已完成：springdoc-openapi 依赖、swagger-ui 路径配置、安全白名单均已落地~~ | ~~已完成~~ |
| ~~Webhook 回调注册~~ | ~~已完成：V45 migration + 订阅 CRUD + `WebhookDispatcher` 异步投递（HMAC-SHA256）+ 投递日志 + `ConsoleWebhookDomainEventListener` 领域事件监听~~ | ~~已完成~~ |
| ~~SDK / 客户端生成~~ | ~~已完成：Maven profile `openapi-codegen`（springdoc export + openapi-generator typescript-fetch）+ `scripts/ci/generate-sdk.sh`~~ | ~~已完成~~ |
| ~~沙箱 / 模拟执行~~ | ~~已完成：`TriggerRequest.dryRun` + `dryRunTrigger()` 校验链路（租户/jobCode/bizDate/triggerType/enabled 状态）~~ | ~~已完成~~ |
| ~~调试日志按需开启~~ | ~~已完成：actuator/loggers 端点已放行，ROLE_ADMIN 可动态调整日志级别~~ | ~~已完成~~ |
| ~~事件总线 / 订阅~~ | ~~已完成：webhook 订阅 + `ConsoleEventCatalogController`（event-types/topics 目录）~~ | ~~已完成~~ |
| ~~批量 API~~ | ~~已完成：POST /api/console/jobs/batch-trigger + GET /api/console/query/instances/batch-status~~ | ~~已完成~~ |
| ~~幂等 key 支持~~ | ~~已实现：所有写操作已通过 X-Idempotency-Key header 支持~~ | ~~已完成~~ |
| ~~API Versioning~~ | ~~已完成：`ConsoleApiVersionConfiguration`（`/api/v1/console/**` → `/api/console/**` 重写 + `X-API-Version` 头）~~ | ~~已完成~~ |

---

## 改动量分析

### 重度改动（涉及新模型 / 跨模块改造）

| 缺口 | 改动点 | 预估工作量 |
|---|---|---|
| ~~Webhook 回调注册~~ | ~~已完成：V45 migration + 订阅 CRUD + `WebhookDispatcher` 异步投递（HMAC-SHA256、3 次指数退避重试）+ 投递日志 + `ConsoleWebhookDomainEventListener` 领域事件联动~~ | ~~已完成~~ |
| 配置审批流 | 在 config release 发布链路中插入审批状态机（待审批 → 批准 → 发布 / 拒绝）；审批人、审批记录、超时自动关闭建模；改变现有发布流程，存量逻辑有侵入 | 1-2 周 |
| 环境间配置同步 | 定义配置序列化/反序列化格式（跨环境 ID 不同，按 code 映射）；涉及 job、workflow、channel、template 全部配置实体；处理环境差异需要变量替换机制 | 2-3 周 |
| 通知订阅管理 | 新增 `notification_channel` + `subscription_rule` 两张表；告警模块改成先查订阅规则再分发；多渠道适配器（邮件、钉钉、企微、webhook）逐个开发 | 2 周 |
| ~~全局熔断 / 限流~~ | ~~已完成：ConsoleGovernanceController 管理 8 项 governance 参数~~ | ~~已完成~~ |

> **建议**：Webhook 回调已完成，通知订阅可复用 `WebhookDispatcher` 底层异步推送通道；配置审批流可复用现有 approval 模块扩展，避免重复建设。

### 轻量改动

| 缺口 | 改动方式 | 预估工作量 |
|---|---|---|
| ~~健康检查 + 就绪探针~~ | ~~已有：actuator + liveness/readiness 在 batch-defaults.yml 中全局配置~~ | ~~已完成~~ |
| ~~OpenAPI 文档~~ | ~~已完成：springdoc-openapi 依赖 + swagger-ui 路径配置 + 安全白名单~~ | ~~已完成~~ |
| ~~优雅停机~~ | ~~已完成：`server.shutdown=graceful` + `OrchestratorGracefulShutdown` + `TriggerGracefulShutdown`（drain/resume/status）~~ | ~~已完成~~ |
| ~~系统参数热更新~~ | ~~已完成：V44 migration + `ConsoleSystemParameterService` CRUD + Redis 缓存~~ | ~~已完成~~ |
| ~~Kafka 消费积压查询~~ | ~~已完成：ConsoleKafkaLagQueryService + GET /api/console/ops/kafka-lag~~ | ~~已完成~~ |
| ~~慢任务诊断~~ | ~~已完成：`sortBy=duration` + `minDurationSeconds` 阈值过滤~~ | ~~已完成~~ |
| ~~配置 Diff~~ | ~~已完成：GET /api/console/config/releases/diff 对比两个版本 payload/grayScope/status~~ | ~~已完成~~ |
| ~~配置依赖分析~~ | ~~已完成：GET /api/console/config/dependencies 查询引用指定配置的 job 列表~~ | ~~已完成~~ |
| ~~批量启停~~ | ~~已完成：POST /api/console/job-definitions/batch-toggle 批量启停~~ | ~~已完成~~ |
| ~~租户用量查询~~ | ~~已完成：GET /api/console/dashboard/tenant-usage 配置+实例+文件统计~~ | ~~已完成~~ |
| ~~执行进度查询（轻量）~~ | ~~已完成：GET /api/console/dashboard/execution-progress~~ | ~~已完成~~ |
| ~~批量 API~~ | ~~已完成：触发、状态查询均支持批量入参~~ | ~~已完成~~ |
| ~~幂等 key~~ | ~~已有：X-Idempotency-Key header 已在所有写操作中使用~~ | ~~已完成~~ |
| ~~沙箱模拟执行~~ | ~~已完成：`TriggerRequest.dryRun` + console 侧校验链路~~ | ~~已完成~~ |
| ~~调试日志按需开启~~ | ~~已完成：actuator/loggers 端点放行 + ROLE_ADMIN 鉴权~~ | ~~已完成~~ |
| ~~标签 / 分组管理~~ | ~~已完成：V46 migration + `ConsoleResourceTagController` CRUD + 按标签反查~~ | ~~已完成~~ |
| ~~API Key 自助管理~~ | ~~已完成：V47 migration + `ConsoleApiKeyController` 创建/查看/吊销 + SHA-256 哈希存储~~ | ~~已完成~~ |
| ~~Outbox 积压 & 清理~~ | ~~已完成：outbox stats/cleanup/republish 三端点 + `OutboxEventMapper`~~ | ~~已完成~~ |
| ~~SLA 报表~~ | ~~已完成：`slaJobReport` 聚合查询 + `GET /sla-report` 端点~~ | ~~已完成~~ |
| ~~文件错误记录导出~~ | ~~已完成：`GET /files/{fileId}/errors/export` CSV 导出~~ | ~~已完成~~ |
| ~~配置导入深度校验~~ | ~~已完成：Excel preview 增加 queue/calendar/window 跨引用校验~~ | ~~已完成~~ |
| ~~配置脚手架（Clone Job）~~ | ~~已完成：`POST /clone` 支持字段覆盖的 `JobDefinitionCopyRequest`~~ | ~~已完成~~ |
| ~~SDK 客户端生成~~ | ~~已完成：Maven `openapi-codegen` profile + `scripts/ci/generate-sdk.sh`~~ | ~~已完成~~ |

---

## 建议实施路线

### 第一批（基础设施级，所有角色受益）

1. ~~健康检查 + 优雅停机~~ — ~~已完成~~
2. ~~OpenAPI 文档~~ — ~~已完成~~
3. ~~系统参数热更新~~ — ~~已完成（V44 migration + CRUD + Redis 缓存）~~

### 第二批（集成与运营效率）

4. ~~Webhook 回调注册~~ — ~~已完成（订阅 CRUD + 异步投递 + HMAC 签名 + 领域事件监听）~~
5. ~~配置依赖分析 + Diff~~ — ~~已完成~~
6. ~~批量 API + 幂等 key~~ — ~~已完成~~
7. **配置审批流 + 环境间同步** — 配置管理闭环

### 第三批（租户自助 & 体验提升）

8. **租户自助进度查询 + 通知订阅** — 进度查询已完成，通知订阅仍缺
9. ~~文件上传 / 到达确认~~ — ~~已完成（presignUpload + confirmArrival）~~
10. **SLA 报表 + 用量统计** — 按租户维度运营
11. ~~沙箱模拟执行~~ — ~~已完成（console 入口 dryRun 校验链路）~~

### 第四批（精细化运维）

12. ~~Kafka 消费积压查询 + 慢任务诊断~~ — ~~已完成~~
13. ~~数据归档清理策略~~ — ~~已完成（V48 archive_policy + CRUD）~~
14. ~~标签 / 分组管理~~ — ~~已完成（V46 resource_tag + CRUD）~~
15. ~~跨节点一致性检查~~ — ~~已完成（ConsoleClusterDiagnosticController）~~
