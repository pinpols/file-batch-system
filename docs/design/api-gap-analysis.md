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

### 部分完成

- 全局熔断 / 限流：已有 outbox 熔断、launch/console 限流，但缺运行时管理接口
- 慢任务 / 长尾任务诊断：已支持按执行时长排序，动态阈值调整仍缺
- 事件总线 / 订阅：已有 webhook 订阅能力，但缺更通用的 Kafka/topic 消费指引或统一订阅接口
- 优雅停机 / 预热：优雅停机已完成，预热能力仍缺

### 仍未完成

- 通知订阅管理
- 配置审批流
- 环境间配置同步
- 自助租户管理
- 文件上传 / 到达确认
- API Key / 凭证自助管理

---

## 1. 生产运维（SRE / Ops）

| 缺口 | 说明 | 优先级 |
|---|---|---|
| ~~健康检查 & 就绪探针~~ | ~~已实现：actuator + liveness/readiness 已在 batch-defaults.yml 全局配置~~ | ~~已完成~~ |
| ~~优雅停机~~ / 预热（部分完成） | ~~已完成：`server.shutdown=graceful` + `OrchestratorGracefulShutdown` + `TriggerGracefulShutdown`（drain/resume/status）~~；预热/摘流量恢复能力仍缺 | ~~优雅停机已完成~~ / 预热 P1 |
| 全局熔断 / 限流（部分完成） | 已有 outbox 熔断、launch/console 限流；仍缺运行时动态调阈值、按 job/file channel 手动熔断接口 | P1 |
| ~~Kafka 消费积压查询~~ | ~~已完成：GET /api/console/ops/kafka-lag 查询 batch 相关 consumer group 积压~~ | ~~已完成~~ |
| Outbox 积压 & 清理 | 有 outbox retry/delivery 查询，缺少批量清理过期 outbox、手动重投指定批次的接口 | P2 |
| 慢任务 / 长尾任务诊断（部分完成） | 已支持 job instance 按运行时长排序；超时阈值动态调整仍缺 | P1 |
| ~~系统参数热更新~~ | ~~已完成：V44 migration + `ConsoleSystemParameterController` CRUD + Redis 缓存（`sys-param:{tenant}:{key}`，TTL 30min）~~ | ~~已完成~~ |
| 数据归档 / 清理策略 | 文件有归档接口，但历史 job_instance、workflow_run 等运行态数据缺少批量归档/清理 | P2 |
| 跨节点一致性检查 | 缺少检测 Orchestrator 多实例间状态一致性、lease 冲突的诊断接口 | P2 |

---

## 2. 配置管理（Config Admin）

| 缺口 | 说明 | 优先级 |
|---|---|---|
| ~~配置对比（Diff）~~ | ~~已完成：GET /api/console/config/releases/diff 对比两个版本 payload/grayScope/status~~ | ~~已完成~~ |
| ~~配置依赖分析~~ | ~~已完成：GET /api/console/config/dependencies 查询引用指定配置的 job 列表~~ | ~~已完成~~ |
| ~~批量启用 / 禁用~~ | ~~已完成：POST /api/console/job-definitions/batch-toggle 批量启停~~ | ~~已完成~~ |
| 配置导入 dry-run 校验 | Excel 有 preview，缺少更深度的 dry-run（跑全部校验规则，输出所有冲突和警告） | P2 |
| 配置模板 / 脚手架 | 缺少从已有 job 克隆并参数化生成新 job 的模板功能（copy 只是简单复制） | P2 |
| 环境间配置同步 | 缺少跨环境（dev -> staging -> prod）配置推送/拉取接口 | P1 |
| 配置审批流 | config release 有发布/灰度/回滚，缺少审批环节（谁批准了这次发布？） | P1 |
| 标签 / 分组管理 | 缺少对 job、workflow、channel 打标签、分组的 CRUD 接口，大规模管理时必要 | P2 |

---

## 3. 租户使用（Tenant / Business User）

| 缺口 | 说明 | 优先级 |
|---|---|---|
| 自助租户管理 | 有 tenant init，缺少租户自助修改配额、查看用量、申请扩容的接口 | P1 |
| 文件上传 / 到达确认 | 缺少租户侧主动上传文件、确认文件到达的接口（当前依赖 channel 自动感知） | P1 |
| ~~执行进度查询（轻量）~~ | ~~已完成：GET /api/console/dashboard/execution-progress 按 jobCode+bizDate 返回分区进度~~ | ~~已完成~~ |
| 自助重跑 / 补偿申请 | rerun/compensation 是运维操作，缺少租户自助提交补跑申请 -> 审批 -> 执行的流程 | P2 |
| SLA 报表 / 账单 | Dashboard 有 SLA compliance，缺少按租户维度的 SLA 达成率、处理量统计报表 | P2 |
| 通知订阅管理 | 缺少租户自助配置告警通知渠道（邮件/webhook/钉钉）、订阅/退订接口 | P1 |
| 文件处理结果下载 | 有 presign download，缺少批量导出处理结果、错误明细下载 | P2 |
| API Key / 凭证自助管理 | 有 secret rotate，缺少租户自助创建/吊销 API Key 的接口 | P2 |

---

## 4. 开发人员（Developer / Integration）

| 缺口 | 说明 | 优先级 |
|---|---|---|
| ~~OpenAPI / Swagger 文档~~ | ~~已完成：springdoc-openapi 依赖、swagger-ui 路径配置、安全白名单均已落地~~ | ~~已完成~~ |
| ~~Webhook 回调注册~~ | ~~已完成：V45 migration + 订阅 CRUD + `WebhookDispatcher` 异步投递（HMAC-SHA256）+ 投递日志 + `ConsoleWebhookDomainEventListener` 领域事件监听~~ | ~~已完成~~ |
| SDK / 客户端生成 | OpenAPI 已具备，但仓库内仍未提供官方生成/发布的 SDK | P2 |
| ~~沙箱 / 模拟执行~~ | ~~已完成：`TriggerRequest.dryRun` + `dryRunTrigger()` 校验链路（租户/jobCode/bizDate/triggerType/enabled 状态）~~ | ~~已完成~~ |
| ~~调试日志按需开启~~ | ~~已完成：actuator/loggers 端点已放行，ROLE_ADMIN 可动态调整日志级别~~ | ~~已完成~~ |
| 事件总线 / 订阅（部分完成） | 已支持 webhook 订阅；仍缺更通用的 Kafka topic 消费指引 / 统一订阅接口 | P1 |
| ~~批量 API~~ | ~~已完成：POST /api/console/jobs/batch-trigger + GET /api/console/query/instances/batch-status~~ | ~~已完成~~ |
| ~~幂等 key 支持~~ | ~~已实现：所有写操作已通过 X-Idempotency-Key header 支持~~ | ~~已完成~~ |
| API Versioning | 接口无版本号前缀（如 `/v1/`），后续升级有兼容性风险 | P2 |

---

## 改动量分析

### 重度改动（涉及新模型 / 跨模块改造）

| 缺口 | 改动点 | 预估工作量 |
|---|---|---|
| ~~Webhook 回调注册~~ | ~~已完成：V45 migration + 订阅 CRUD + `WebhookDispatcher` 异步投递（HMAC-SHA256、3 次指数退避重试）+ 投递日志 + `ConsoleWebhookDomainEventListener` 领域事件联动~~ | ~~已完成~~ |
| 配置审批流 | 在 config release 发布链路中插入审批状态机（待审批 → 批准 → 发布 / 拒绝）；审批人、审批记录、超时自动关闭建模；改变现有发布流程，存量逻辑有侵入 | 1-2 周 |
| 环境间配置同步 | 定义配置序列化/反序列化格式（跨环境 ID 不同，按 code 映射）；涉及 job、workflow、channel、template 全部配置实体；处理环境差异需要变量替换机制 | 2-3 周 |
| 通知订阅管理 | 新增 `notification_channel` + `subscription_rule` 两张表；告警模块改成先查订阅规则再分发；多渠道适配器（邮件、钉钉、企微、webhook）逐个开发 | 2 周 |
| 全局熔断 / 限流（部分完成） | 已有 outbox 熔断、launch/console 限流与部分限流配置；仍缺集中式运行时开关、按租户/job/channel 的管理接口 | 1 周（剩余） |

> **建议**：Webhook 回调已完成，通知订阅可复用 `WebhookDispatcher` 底层异步推送通道；配置审批流可复用现有 approval 模块扩展，避免重复建设。

### 轻量改动

| 缺口 | 改动方式 | 预估工作量 |
|---|---|---|
| ~~健康检查 + 就绪探针~~ | ~~已有：actuator + liveness/readiness 在 batch-defaults.yml 中全局配置~~ | ~~已完成~~ |
| ~~OpenAPI 文档~~ | ~~已完成：springdoc-openapi 依赖 + swagger-ui 路径配置 + 安全白名单~~ | ~~已完成~~ |
| ~~优雅停机~~ | ~~已完成：`server.shutdown=graceful` + `OrchestratorGracefulShutdown` + `TriggerGracefulShutdown`（drain/resume/status）~~ | ~~已完成~~ |
| ~~系统参数热更新~~ | ~~已完成：V44 migration + `ConsoleSystemParameterService` CRUD + Redis 缓存~~ | ~~已完成~~ |
| ~~Kafka 消费积压查询~~ | ~~已完成：ConsoleKafkaLagQueryService + GET /api/console/ops/kafka-lag~~ | ~~已完成~~ |
| 慢任务诊断（部分完成） | 已实现 `sortBy=duration`；超时阈值动态调整未完成 | 1 天（剩余） |
| ~~配置 Diff~~ | ~~已完成：GET /api/console/config/releases/diff 对比两个版本 payload/grayScope/status~~ | ~~已完成~~ |
| ~~配置依赖分析~~ | ~~已完成：GET /api/console/config/dependencies 查询引用指定配置的 job 列表~~ | ~~已完成~~ |
| ~~批量启停~~ | ~~已完成：POST /api/console/job-definitions/batch-toggle 批量启停~~ | ~~已完成~~ |
| ~~租户用量查询~~ | ~~已完成：GET /api/console/dashboard/tenant-usage 配置+实例+文件统计~~ | ~~已完成~~ |
| ~~执行进度查询（轻量）~~ | ~~已完成：GET /api/console/dashboard/execution-progress~~ | ~~已完成~~ |
| ~~批量 API~~ | ~~已完成：触发、状态查询均支持批量入参~~ | ~~已完成~~ |
| ~~幂等 key~~ | ~~已有：X-Idempotency-Key header 已在所有写操作中使用~~ | ~~已完成~~ |
| ~~沙箱模拟执行~~ | ~~已完成：`TriggerRequest.dryRun` + console 侧校验链路~~ | ~~已完成~~ |
| ~~调试日志按需开启~~ | ~~已完成：actuator/loggers 端点放行 + ROLE_ADMIN 鉴权~~ | ~~已完成~~ |
| 标签 / 分组管理 | 新增 tag 表 + CRUD 接口 | 2-3 天 |
| API Key 自助管理 | 新增 api_key 表 + 生成/吊销接口 | 2-3 天 |

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
9. **文件上传 / 到达确认** — 租户侧主动触发
10. **SLA 报表 + 用量统计** — 按租户维度运营
11. ~~沙箱模拟执行~~ — ~~已完成（console 入口 dryRun 校验链路）~~

### 第四批（精细化运维）

12. ~~Kafka 消费积压查询~~ + **慢任务诊断** — Kafka 已完成，慢任务诊断部分完成
13. **数据归档清理策略** — 长期运行后的存储治理
14. **标签 / 分组管理** — 大规模配置管理
15. **跨节点一致性检查** — 高可用场景保障
