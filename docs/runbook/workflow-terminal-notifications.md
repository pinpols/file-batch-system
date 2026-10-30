# Job 与 Workflow 终态通知

适用模型：全新数据库应用至 V346；Console 使用共享 `spring.kafka.bootstrap-servers`，以固定消费组 `batch-console-workflow-terminal-notifications` 消费 `batch.workflow.terminal.v1`。Job 使用独立 `batch.job.terminal.v1`（schema v1）和消费组 `batch-console-job-terminal-notifications`；Workflow 保持 schema v2。两者独立于 Worker 和 OpenLineage，消费端持久化失败保留 Kafka offset 重试。终态 Outbox 写入失败会回滚原平台终态事务；通知接收端故障不会反向回滚已经提交的运行结果。

## 接纳与投递

- Job 的 REPORT 收口、直接终态服务及派发业务拒绝收口在原事务内读取已持久化 Job 快照并写 JOB_TERMINAL Outbox；普通终态服务不能把 FAILED 直接改成 SUCCESS，修正须沿原 REPORT/进度守卫。业务订阅 JOB_SUCCESS 仅匹配正式 SUCCESS，JOB_FAILED 匹配正式 FAILED/PARTIAL_FAILED；取消、终止和 dry-run 留回执但不转正式成功/失败。
- Job 稳定事实键为 `tenant:job:jobInstanceId:terminal:instanceStatus`，载荷包含 terminalVersion、finishedAt、runAttempt、bizDate、JobCode、所属 WorkflowRun ID 和 trace。同实例/同终态重复收口保留首次事实；允许的失败→成功修正是另一事实，RERUN 新实例独立。历史事实可能重投或乱序到达，不用接收顺序覆盖当前状态；SLA/监控仍按当前 Job 查询。
- Job 和 Workflow 共用已有 `workflow_terminal_notification_receipt` 表和两类投递记录，键前缀及 schema/消费者校验区分实体，不互相覆盖。恢复页展示业务事件列，JOB_SUCCESS 与 WORKFLOW_SUCCESS 明确区分；原平台事务/接纳/认领/人工恢复协议一致。

- 正式 `SUCCESS` / `FAILED` 分别匹配既有 `WORKFLOW_SUCCESS` / `WORKFLOW_FAILED` 订阅。`TERMINATED`、`SUCCESS_DRY_RUN`、`FAILED_DRY_RUN` 保留原状态回执，不转换成正式成功/失败通知。
- 同一 `tenant:workflow:runId:terminal` 首次消费时，在一个平台事务内写消费回执和首次匹配的 Webhook/渠道规则待投递记录。Kafka offset 在事务返回后确认；重复消息不重新展开后来新增的订阅，冲突快照拒绝覆盖。
- 既有 Webhook relay 调用可靠终态投递器，沿用 `batch.webhook.relay` 的轮询、批量和最大次数设置。终态路径每次认领即计入次数；两分钟认领过期后可以恢复，旧 token 不能覆盖新认领结果。发送阶段不持有平台数据库事务。
- 正式 JOB_SUCCESS/JOB_FAILED/WORKFLOW_SUCCESS/WORKFLOW_FAILED 的 Webhook `Idempotency-Key` 是 payload `cursor`（稳定事件身份）的 UTF-8 URL-safe Base64（不带 padding）。普通 realtime changed 事件不使用该终态幂等头，避免把共享游标的不同变更误合并。同一终态事件各次投递 body 与键均不变；接收端必须按该键记录已处理结果。网络错误可能重复 HTTP 请求，唯一逻辑动作依赖接收端执行该协议。
- 渠道配置在发送时按原租户/渠道读取，支持密钥轮换。规则被禁用、删除或改派渠道时，原意图保留失败记录，不转发给另一个渠道。

## Job SLA 告警与事件目录

Job SLA 扫描产生 `JOB_SLA_VIOLATION` / `JOB_SLA_VIOLATION_ESCALATED` 告警，沿已有告警中心和启用的 Alertmanager 通道处理。
它们不由 Job/Workflow 终态 Topic 转成业务订阅事件。目录中无生产者的 `JOB_SLA_BREACH` 已撤下；规则及 Webhook 创建/更新拒绝这三个 SLA 编码并返回 `INVALID_ARGUMENT` 与通道指引。
规则先执行原有文本规范化，再校验实际保存值；不会把全角编码清洗成被禁止的订阅后静默保存。通配符及其它自定义事件的既有校验口径不变。 订阅候选按租户、启用状态和保守事件预筛选读取，之后按逗号分隔成员忽略大小写匹配；独立的 `*` 匹配所有事件，其他类型不因子串相同而命中。候选集规模和通知负载应在目标环境监测。
Job/Workflow 成功、失败仍按各自事实订阅。当前 AM 链路为可选配置，目录变化不自动启用 AM emit 或动态路由。

## 失败与诊断

`webhook_delivery_log` / `notification_delivery_log` 的 `terminal_event_key` 非空表示本链路的投递；`next_retry_at` 非空表示仍有自动投递义务。`delivery_claim_until` 和 `delivery_claim_token` 表示在途认领。

Webhook 失败在次数预算内退避重试，成功后清空下次时间；耗尽预算则为 `EXHAUSTED` 且 `next_retry_at=NULL`。无幂等协议的 Email/SMS 等 provider 首次失败或认领中断后，保留 `EXHAUSTED` 和结果不明原因，不自动重复发送。先在接收端核对结果；结果不明时保持停止，确认未处理后才使用下面的人工恢复入口。人工排队成功不代表接收端已完成。

以租户、`terminal_event_key`、WorkflowRun ID、traceId 和投递记录 ID 关联 Kafka 消费、平台事务与接收端收据。旧通用 Webhook 记录的 `terminal_event_key=NULL`，继续沿既有重试路径处理；本轮仅修复终态专属记录的可恢复认领。

## 人工核对后重投

Console「通知管理 → 终态通知恢复」展示原事件身份、投递状态、本轮次数和上次错误；原始业务载荷、渠道凭据不随该列表返回。所有 Console 角色只能读取获准租户；只有 ADMIN / TENANT_ADMIN 能提交重投。

1. 确认记录为 EXHAUSTED、已无自动重试且没有未到期认领。在接收端核对该稳定事件/幂等键，确认尚未处理。
2. 填写原因、接收端凭证或工单，勾选未处理确认。提交保持原事件/原订阅目标；渠道当前配置仍由原投递器校验，禁用/删除/改派不会转发给新渠道。
3. `POST /api/console/notifications/terminal-deliveries/{source}/{id}/retry?tenantId=...` 携带 UUID requestId、expectedAttempt、reason、reconciliationReference、confirmedNotDelivered=true。source 只接受 WEBHOOK/RULE；Cookie 认证沿既有 CSRF 保护。
4. 同一平台事务中锁定投递、核对状态/次数、开启一轮有界预算并强制写 `console_operation_audit`。审计失败会整体回滚；原错误保留，之前次数和稳定事件身份进入审计。V329 对 `(tenant, aggregateId, requestId)` 的该操作审计建唯一索引。相同请求/凭证重复提交返回成功但不重新开启预算；同键改凭证返回冲突。
5. HTTP 成功表示已记录审计并排队。刷新 `GET /api/console/notifications/terminal-deliveries` 确认最终 SUCCESS/EXHAUSTED；「审计记录」按 NOTIFICATION_DELIVERY 与 `source:id` 跳转既有审计页，回读操作者、请求 ID、原因和凭证。

网络结果不明时保留原请求身份/证据重试；需要修改凭证时，先刷新核对当前投递结果，再开始新的人工操作。该接口不重跑 Workflow、不扩大首次收件人集合。次数按本轮预算展示，历史人工轮次及此前次数在审计中回查。幂等记录依赖审计保留：日常归档不得先删除仍在人工恢复窗口内的这些审计，跨保留期联合验证仍由 R32/R36 跟踪。

## Web Push 的当前边界

Job Web Push 默认关闭。`ConsolePushJobNotifier` 只驱动持久设备队列，旧 lookback 扫描、旧 Mapper 与 `console_push_job_notification` 已移除。义务来自 Job terminal Kafka 首次消费，窗口外未完成项仍在队列中。

`console_push_job_delivery` 的 DECISION 记录首次开关/无目标决定，DEVICE 冻结原 tenant/operator/device、凭据 SHA-256 摘要及载荷；不复制 endpoint/key/auth。重复回执不加入后来设备，提交人冲突拒绝覆盖。dry-run 标志或终态任一表明演练时都不创建正式设备意图。

工作器按租户注册表分页，每轮受 batchSize 限制并轮转游标；每个受保护事务先绑定 PostgreSQL app.tenant_id。先提交两分钟 token 认领，再在事务外调用 provider；发送前重读原设备、用户及凭据指纹，撤销/换钥终结为 GONE。完成更新须匹配原 token；不依赖 ThreadLocal 代替数据库租户上下文。

明确暂时失败按既有 absoluteMaxAttempts 预算退避；NOT_READY 等待且退还本次发送次数。超时/断连、发送后提交失败及过期认领记 UNKNOWN、终结为 EXHAUSTED，不自动重投。接纳为 ACCEPTED；HTTP 接纳不证明浏览器展示或用户阅读，见 [RFC 8030 §5](https://www.rfc-editor.org/rfc/rfc8030.html#section-5)。

恢复列表 source=PUSH；DECISION 只供查看。EXHAUSTED 的设备项须经原权限、未处理确认、原因/凭证、expectedAttempt 和持久请求身份恢复，审计与重新排队同事务。重投仍只核对原设备，不能展开当前用户全部设备。前端区分 provider 接纳与展示，并在手机链接租户不匹配时拒绝加载；确认弹窗与异步返回均固定原目标。

V330 仅支持全新数据库：持 ACCESS EXCLUSIVE 锁确认旧账本为空后才删除；非空即失败，未实现旧数据迁移。定向测试包含空/非空保护、普通数据库角色、并发认领、批次限制、旧 token、停机恢复、设备撤销和人工审计回滚。本地真实 Web Push 加密 HTTP、VAPID 校验和原设备解密已有定向证据；真实 IMPORT→Job Kafka→加密 HTTP 的独立 Console 强杀后，认领自然到期停在 UNKNOWN 已 1/1 通过；B169 已补真实认证浏览器和 V346 保留生命周期本地验证；TLS/外部 provider 仍待 EX-04 签收，不能借用 Webhook 证据关闭。

发送器通过 ManagedWebPushClient 持有 HTTP 客户端，沿用锁定 web-push 库的签名和加密；按源地址规范保留非默认端口，默认端口不写入 VAPID aud。连接/读取/请求期限与单次等待共用三秒来源；禁用 HTTP 库自动重试及跟随重定向，次数预算只由设备队列管理。Spring 销毁时原子禁用并关闭客户端，在途关闭记 UNKNOWN，之后返回 NOT_READY。loopback 测试含真实网络及独立设备解密，不能视作外部 Push 服务或浏览器已确认送达。

后续验收见[整合计划 R32 Job Web Push](../plans/workflow-daily-integration-plan-2026-10-14.md#r32-job-web-push-的收口顺序2026-10-20)。

## Topic 与部署

新增 Job topic 已同步 BatchTopics、`.env.example`、本工作树 `.env.local` 与 init-kafka-topics 的 12-topic 清单；Helm 等现有载体的子集守卫通过。沿既有 Kafka 初始化入口创建 topic，再部署包含新生产者/消费者的构建；不依赖生产 Kafka 自动建 topic。没有新增静态开关或密钥，启动仍使用原 Kafka 地址和认证配置；运行时创建/部署验证留到冻结版本联合验收。

## 清理与验收边界

消费回执、Webhook、规则通知、Job Push 和相关人工恢复审计按同一终态事实整族归档。复用租户已有 `archive_policy.target_table=console_operation_audit` 策略：`retention_days` 决定热候选时间，`batch_size` 与既有 relay 批量共同限流。缺失、禁用或参数非法时不归档；`archive_enabled=true` 允许复制，`cleanup_enabled=true` 才允许全行一致性核对后的热表清理。策略可在现有归档策略入口维护。

仅已闭合且全部超过保留期的族可迁移：Webhook/规则 SUCCESS，Push DECISION 或设备 ACCEPTED/GONE；未决、EXHAUSTED、认领中、待重试及保留期内的人工审计都保留热表。复制顺序为回执、三类投递、审计，清理顺序相反。失败回滚整个族；仅复制时不会重复占用后续批次。

冷回执永久参与稳定身份去重，Kafka 迟到或新增订阅不会重新展开旧事实。原人工 requestId 仍可从冷审计识别；新请求只能恢复热表 EXHAUSTED 记录。历史列表及审计查询合并热冷并按原 ID 去重。未新增永久删除冷证据或业务重放截止期。

事件目录只列实际支持的 JOB_SUCCESS、JOB_FAILED、WORKFLOW_SUCCESS、WORKFLOW_FAILED、ALERT_ESCALATED；最后一项须显式启用既有升级通知回滚路径，默认关闭。目录收敛不删除历史订阅，custom/`*` 合同保持。

采用独立数据库运行/只读角色时，须授予 `archive` schema USAGE 和五张冷表 SELECT；归档角色另需冷表 INSERT、热族 DELETE/UPDATE。无需冷表 DELETE 或 RLS 旁路。默认同迁移用户部署不受专属测试角色缺 grant 的影响。

当前本地证据覆盖真实 PostgreSQL/Kafka/HTTP 的接纳、重投、503 恢复、并发、事务回滚、认领 fencing、次数上限和 RLS。真实 DAG 中每个 Job 成功/失败与根 Workflow 失败均有独立通知，RERUN 新实例、新根及两类 Kafka 重投、接收端提交后响应丢失已有端到端证据；独立 Console JVM 强杀并自然等待两分钟认领/锁到期后的自动恢复也已通过；人工核对/重投/审计 API 回读与前端组件已有定向证据；B169 已完成真实认证浏览器的冷热历史、人工核对重投与原身份审计回读，V346 的保留生命周期 PG 验证已完成；实际外部 provider/TLS 适配及目标运行观察仍待 EX-04 签收，见[验收矩阵 R32](../plans/workflow-daily-integration-acceptance-matrix-2026-10-14.md)。
