# API 缺口分析（按角色视角）

> 基于现有 250+ 接口盘点，从四个核心角色视角识别缺失接口。

---

## 1. 生产运维（SRE / Ops）

| 缺口 | 说明 | 优先级 |
|---|---|---|
| ~~健康检查 & 就绪探针~~ | ~~已实现：actuator + liveness/readiness 已在 batch-defaults.yml 全局配置~~ | ~~已完成~~ |
| 优雅停机 / 预热 | Worker 有 drain，但 Orchestrator 和 Trigger 缺少优雅下线、流量摘除接口 | P0 |
| 全局熔断 / 限流 | 缺少运行时动态调整限流阈值、手动熔断某个 file channel 或 job 的接口 | P1 |
| ~~Kafka 消费积压查询~~ | ~~已完成：GET /api/console/ops/kafka-lag 查询 batch 相关 consumer group 积压~~ | ~~已完成~~ |
| Outbox 积压 & 清理 | 有 outbox retry/delivery 查询，缺少批量清理过期 outbox、手动重投指定批次的接口 | P2 |
| 慢任务 / 长尾任务诊断 | 缺少按执行时长排序的 running task 列表、超时阈值动态调整 | P1 |
| 系统参数热更新 | 缺少运行时修改系统级参数（重试次数、超时阈值、并发度）的接口，改完不用重启 | P1 |
| 数据归档 / 清理策略 | 文件有归档接口，但历史 job_instance、workflow_run 等运行态数据缺少批量归档/清理 | P2 |
| 跨节点一致性检查 | 缺少检测 Orchestrator 多实例间状态一致性、lease 冲突的诊断接口 | P2 |

---

## 2. 配置管理（Config Admin）

| 缺口 | 说明 | 优先级 |
|---|---|---|
| 配置对比（Diff） | 有 config release，缺少两个版本间的 diff 对比接口 | P1 |
| 配置依赖分析 | 修改一个 file channel 会影响哪些 job、workflow？缺少影响面分析接口 | P1 |
| 批量启用 / 禁用 | 单个 toggle 有，缺少按标签/分组批量启停 job definition 的接口 | P2 |
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
| OpenAPI / Swagger 文档 | 缺少 springdoc/swagger 配置，开发者无自动生成的 API 文档 | P0 |
| Webhook 回调注册 | 缺少注册回调 URL 的接口，让外部系统在 job 完成/失败时收到通知 | P1 |
| SDK / 客户端生成 | 无 API schema 就无法自动生成客户端 SDK | P2 |
| 沙箱 / 模拟执行 | 缺少 dry-run 接口，开发联调时无法模拟触发 job 但不真正执行 | P1 |
| ~~调试日志按需开启~~ | ~~已完成：actuator/loggers 端点已放行，ROLE_ADMIN 可动态调整日志级别~~ | ~~已完成~~ |
| 事件总线 / 订阅 | 有 SSE 实时流，缺少通用的事件订阅接口（webhook 或 Kafka topic 消费指引） | P1 |
| 批量 API | 很多操作只支持单条，缺少批量触发、批量查询状态的 batch API | P1 |
| ~~幂等 key 支持~~ | ~~已实现：所有写操作已通过 X-Idempotency-Key header 支持~~ | ~~已完成~~ |
| API Versioning | 接口无版本号前缀（如 `/v1/`），后续升级有兼容性风险 | P2 |

---

## 改动量分析

### 重度改动（涉及新模型 / 跨模块改造）

| 缺口 | 改动点 | 预估工作量 |
|---|---|---|
| Webhook 回调注册 | 新增 `webhook_subscription` 表（URL、事件类型、重试策略、签名密钥）；所有关键状态变更点埋发送逻辑；异步发送 + 重试 + 死信等于再建一套小型 outbox；跨 orchestrator、worker、trigger 多模块 | 2-3 周 |
| 配置审批流 | 在 config release 发布链路中插入审批状态机（待审批 → 批准 → 发布 / 拒绝）；审批人、审批记录、超时自动关闭建模；改变现有发布流程，存量逻辑有侵入 | 1-2 周 |
| 环境间配置同步 | 定义配置序列化/反序列化格式（跨环境 ID 不同，按 code 映射）；涉及 job、workflow、channel、template 全部配置实体；处理环境差异需要变量替换机制 | 2-3 周 |
| 通知订阅管理 | 新增 `notification_channel` + `subscription_rule` 两张表；告警模块改成先查订阅规则再分发；多渠道适配器（邮件、钉钉、企微、webhook）逐个开发 | 2 周 |
| 全局熔断 / 限流 | 引入集中式开关/限流存储（Redis）；orchestrator 分发、worker 拉取、trigger 触发三个入口加拦截；粒度控制（按租户、按 job、按 channel） | 1-2 周 |

> **建议**：Webhook 回调与通知订阅可合并设计（底层共用异步推送通道）；配置审批流可复用现有 approval 模块扩展，避免重复建设。

### 轻量改动

| 缺口 | 改动方式 | 预估工作量 |
|---|---|---|
| ~~健康检查 + 就绪探针~~ | ~~已有：actuator + liveness/readiness 在 batch-defaults.yml 中全局配置~~ | ~~已完成~~ |
| ~~OpenAPI 文档~~ | ~~已完成：springdoc-openapi 依赖 + swagger-ui 路径配置 + 安全白名单~~ | ~~已完成~~ |
| 优雅停机 | Orchestrator / Trigger 增加 shutdown hook + drain 端点 | 1-2 天 |
| 系统参数热更新 | 基于数据库/Redis 的动态参数 CRUD + 缓存刷新 | 2-3 天 |
| ~~Kafka 消费积压查询~~ | ~~已完成：ConsoleKafkaLagQueryService + GET /api/console/ops/kafka-lag~~ | ~~已完成~~ |
| ~~慢任务诊断~~ | ~~已完成：JobInstanceQuery 支持 sortBy=duration 按运行时长降序~~ | ~~已完成~~ |
| ~~配置 Diff~~ | ~~已完成：GET /api/console/config/releases/diff 对比两个版本 payload/grayScope/status~~ | ~~已完成~~ |
| ~~配置依赖分析~~ | ~~已完成：GET /api/console/config/dependencies 查询引用指定配置的 job 列表~~ | ~~已完成~~ |
| ~~批量启停~~ | ~~已完成：POST /api/console/job-definitions/batch-toggle 批量启停~~ | ~~已完成~~ |
| ~~租户用量查询~~ | ~~已完成：GET /api/console/dashboard/tenant-usage 配置+实例+文件统计~~ | ~~已完成~~ |
| ~~执行进度查询（轻量）~~ | ~~已完成：GET /api/console/dashboard/execution-progress~~ | ~~已完成~~ |
| 批量 API | 触发、状态查询支持批量入参 | 2 天 |
| ~~幂等 key~~ | ~~已有：X-Idempotency-Key header 已在所有写操作中使用~~ | ~~已完成~~ |
| 沙箱模拟执行 | trigger 接口增加 dryRun 参数，走校验不执行 | 1-2 天 |
| ~~调试日志按需开启~~ | ~~已完成：actuator/loggers 端点放行 + ROLE_ADMIN 鉴权~~ | ~~已完成~~ |
| 标签 / 分组管理 | 新增 tag 表 + CRUD 接口 | 2-3 天 |
| API Key 自助管理 | 新增 api_key 表 + 生成/吊销接口 | 2-3 天 |

---

## 建议实施路线

### 第一批（基础设施级，所有角色受益）

1. **健康检查 + 优雅停机** — 生产部署必备，工作量小
2. **OpenAPI 文档** — 加 springdoc 依赖即可，所有角色受益
3. **系统参数热更新** — 运维高频需求，避免重启

### 第二批（集成与运营效率）

4. **Webhook 回调注册** — 外部系统集成的核心需求
5. **配置依赖分析 + Diff** — 避免配置变更引发事故
6. **批量 API + 幂等 key** — 开发集成体验关键改善
7. **配置审批流 + 环境间同步** — 配置管理闭环

### 第三批（租户自助 & 体验提升）

8. **租户自助进度查询 + 通知订阅** — 减少运维工单量
9. **文件上传 / 到达确认** — 租户侧主动触发
10. **SLA 报表 + 用量统计** — 按租户维度运营
11. **沙箱模拟执行** — 开发联调效率

### 第四批（精细化运维）

12. **Kafka 消费积压查询 + 慢任务诊断** — 深度排查能力
13. **数据归档清理策略** — 长期运行后的存储治理
14. **标签 / 分组管理** — 大规模配置管理
15. **跨节点一致性检查** — 高可用场景保障
