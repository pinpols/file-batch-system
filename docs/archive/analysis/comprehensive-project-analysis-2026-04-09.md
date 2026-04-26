# File Batch System 项目综合分析报告

> 分析基准日期：2026-04-09

---

## 一、安全漏洞分析

### [高危] S-1：V34 种子数据硬编码默认密码

**位置**：`batch-console-api/src/main/resources/db/migration/V34__create_console_user_account.sql`

**现象**：种子数据中 admin 用户密码为 `admin123` 的 Argon2id 哈希值，首次部署未强制修改即投入使用，攻击者可直接尝试该密码登录控制台。

**影响**：控制台 Admin 账户完全沦陷，可触发任意批量任务、查看所有租户数据。

**修复建议**：
- 种子数据仅写入占位符密码（不可登录的随机哈希），并在 `README` / 部署 Runbook 中明确要求首次部署后通过初始化脚本重置密码。
- 或在首次登录时强制跳转"修改密码"流程。

---

### [高危] S-2：CSRF 防护关闭 + Token 暴露于 Query Parameter

**位置**：`ConsoleSecurityConfiguration.java:35`、`ConsoleAuthenticationFilter.java:140-141`

**现象**：Spring Security CSRF 被显式 `disable()`；JWT Token 可通过 Query Parameter（`?token=...`）传递，导致 Token 可能出现在服务器日志、浏览器历史、Referer 头中。

**影响**：CSRF 攻击可诱导已登录用户执行状态变更操作；Token 泄漏可接管会话。

**修复建议**：
- 对 SPA + JWT 场景，若坚持关闭 CSRF，需严格检查 `Origin` / `Referer` + 要求 `X-Requested-With: XMLHttpRequest` 头，或使用 Double Submit Cookie。
- Token 只允许通过 `Authorization: Bearer` 头传递，不接受 Query Parameter。

---

### [高危] S-3：Demo / 测试模式鉴权绕过

**位置**：`ConsoleAuthenticationFilter.java:45-59`

**现象**：代码中存在 `demoMode` 或类似标志，启用后跳过所有鉴权检查；该标志可通过配置或环境变量开启，若生产环境误启用则完全绕过鉴权。

**影响**：生产环境任意用户可无需登录访问所有控制台接口。

**修复建议**：
- 删除 demo/test 鉴权绕过代码，或通过 Spring Profile `@Profile("!prod")` 严格隔离，且不允许在生产 profile 中加载。
- CI 中增加"生产配置扫描"，检测 `demoMode=true` 等危险配置键。

---

### [高危] S-4：Trigger API 完全无鉴权

**位置**：`TriggerController.java`、`TriggerManagementController.java`

**现象**：触发器 API（外部系统提交批量任务入口）未配置任何身份验证或 API Key 校验，任何能访问该端点的请求均可触发任务。

**影响**：任意外部系统（或攻击者）可无限制触发批量任务，可用于资源耗尽或数据污染攻击。

**修复建议**：
- 对 Trigger API 启用 API Key / HMAC 签名鉴权（与调用方约定共享密钥或使用 OAuth2 Client Credentials）。
- 或接入 API Gateway 层统一鉴权，Trigger 服务只接受来自 Gateway 的内部流量。

---

### [高危] S-5：JWT Secret 使用默认值

**位置**：`batch-console-api/src/main/resources/application.yml`（`jwt-secret: console-jwt-secret-change-me`）

**现象**：JWT 签名密钥使用可预测的默认值，若生产部署未覆盖该配置，攻击者可自签 JWT Token 伪造任意用户身份。

**影响**：Token 完全不可信，任意用户可伪造 Admin Token。

**修复建议**：
- 在启动时检测 secret 是否仍为默认值，若是则拒绝启动（`@PostConstruct` 校验）。
- 将 JWT secret 迁移到环境变量 / KMS / Vault，不允许硬编码在 `application.yml` 中。

---

### [中危] S-6：HTTP Dispatch 通道存在 SSRF 风险

**位置**：`HttpDispatchChannelAdapter.java:34`

**现象**：HTTP 派发通道的目标 URL 直接取自通道配置（数据库），未对目标域名 / IP 进行白名单校验，攻击者若能写入通道配置可将请求打到内网服务。

**影响**：Server-Side Request Forgery（SSRF），可探测内网拓扑、访问元数据服务（AWS IMDSv1 等）。

**修复建议**：
- 对 HTTP 派发目标 URL 进行域名白名单校验（`allowlist` 配置项）。
- 拒绝目标为 `localhost`、`127.x.x.x`、`10.x.x.x`、`169.254.x.x`（AWS metadata）等内网地址的请求。

---

### [中危] S-7：SFTP 主机密钥验证可被关闭

**位置**：`SftpDispatchChannelAdapter.java:20`

**现象**：`sftp_strict_host_key_checking=no` 可完全禁用主机密钥验证（设计上为测试便利），生产配置若误用此选项将无法防止中间人攻击。

**影响**：SFTP 传输数据可被中间人截获或篡改。

**修复建议**：
- 在通道配置写入时，若 `sftp_strict_host_key_checking=no` 且 `sftp_known_hosts_path` 为空，需在保存时输出强警告并在审计日志中记录。
- 生产 Runbook 中明确：`StrictHostKeyChecking=no` 仅允许用于测试环境，生产必须配置 `known_hosts`。

---

### ✅ [中危] S-8：SQL 模板 Schema 白名单可绕过（无前缀表）— 已修复

**位置**：`SqlTemplateExportSqlValidator.java`（原 :134）

**现象**：SQL 模板白名单基于表名前缀匹配（如 `biz.`），无前缀的表名（如直接写 `settlement_detail` 而非 `biz.settlement_detail`）可能绕过白名单检查，访问系统内部表。

**影响**：精心构造的导出模板可读取 `batch.job_task`、`batch.outbox_event` 等平台内部数据，造成信息泄漏。

**修复**（2026-04-08）：
- `checkAllowedSchemas` 中将 `if (dot > 0)` 改为 `if (dot <= 0) throw`，当 `allowedSchemas` 非空时，无 schema 前缀的表名直接抛出 `IllegalArgumentException`，不再静默放行。
- 更新 `SqlTemplateExportSqlValidatorTest`：`validate_allowsTableWithNoSchema_whenWhitelistSet` → `validate_throwsOnUnqualifiedTable_whenWhitelistSet`，断言抛出异常并包含表名。

---

### [中危] S-9：Worker ↔ Orchestrator 通信无鉴权

**位置**：`HttpTaskExecutionClient.java`、`HttpWorkerRegistryClient.java`

**现象**：Worker 向 Orchestrator 上报任务状态、注册 Worker 的 HTTP 请求均无任何身份验证（无 Token、无 API Key、无 mTLS），任何能访问 Orchestrator 端口的进程均可伪造上报。

**影响**：攻击者可伪造 Worker 上报，将恶意任务标记为成功、注入虚假 Worker、造成调度混乱。

**修复建议**：
- 短期：在 Worker ↔ Orchestrator 通信中增加共享 API Key（`X-Worker-Token` 头），通过 Secret 管理下发。
- 长期：内部服务间通信使用 mTLS 或 Service Mesh（Istio / Linkerd）。

---

### ✅ [低危] S-10：OkHttpClient 未配置超时— 已修复

**位置**：`HttpDispatchChannelAdapter.java`（原 :21）

**现象**：HTTP 派发通道使用的 `OkHttpClient` 未设置 `connectTimeout` / `readTimeout`，若目标服务无响应将导致派发线程永久阻塞。

**修复**（2026-04-08）：
- 新增 `HttpDispatchChannelProperties`（`@ConfigurationProperties(prefix = "batch.worker.dispatch.http-channel")`），提供 `connectTimeoutMillis=10000`、`readTimeoutMillis=30000`、`writeTimeoutMillis=30000` 三个可配置字段。
- `HttpDispatchChannelAdapter` 改为构造器注入 `HttpDispatchChannelProperties`，通过 `OkHttpClient.Builder` 设置超时，替换原来的 `new OkHttpClient()`。
- `dispatch-worker.yml` 补充默认值配置块，运维可通过环境变量或 profile overlay 覆盖。

---

### [低危] S-11：`.env` 文件包含敏感信息

**现象**：项目 `.env` / `docker-compose.env` 文件中包含数据库密码、Kafka 凭证等，若误提交到代码仓库将直接泄漏生产凭证。

**修复建议**：
- `.env` 文件加入 `.gitignore`，仅提供 `.env.example`（仅含占位符）。
- CI 中增加 secret 扫描（`gitleaks` / `truffleHog`）。

---

### [低危] S-12：Demo 模式堆栈信息暴露

**现象**：Demo/开发模式下异常堆栈完整返回到 HTTP Response，生产误启用后攻击者可获取内部实现细节（类路径、框架版本、数据库方言等）。

**修复建议**：全局异常处理器在非 dev Profile 下只返回业务错误码，不暴露堆栈。

---

## 二、架构问题分析

### ✅ A-1：Outbox 轮询吞吐瓶颈 — 已修复（自适应轮询）

**位置**：`OutboxPollScheduler.java`（原 5 秒固定轮询间隔）

**现象**：Outbox Forwarder 以 5 秒为固定间隔轮询，高并发场景下端到端延迟叠加明显（触发 → 执行可达 5-15 秒）。

**修复**（2026-04-08）：
- `OutboxProperties` 新增 `minPollIntervalMillis=200`（积压时下限）和 `backoffMultiplier=1.5`（空闲退避系数），原 `pollIntervalMillis=5000` 作为最大间隔上限。
- `OutboxPollScheduler` 从 `@Scheduled(fixedDelay)` 改为 `ScheduledExecutorService` 自调度：
  - 有事件（`attemptedEvents > 0`）→ 下次以 200ms 触发，高峰期延迟从 5s 降至约 200ms。
  - 无事件（空闲）→ 间隔 × 1.5，逐步退避至 5s，减少空转查询。
- 分布式互斥保留 ShedLock，行为与原实现完全兼容。
- 注：consistent hashing 动态分片需配合服务注册发现，当前静态分片已支撑多实例部署，暂不引入。

---

### ✅ A-2：Partition Lease Reclaim 两步 CAS 竞态（已验证：现有防护充分）

**位置**：`PartitionLeaseReclaimScheduler.java:78-86`

**现象**：超时分区回收流程为：先 `SELECT` 找到过期分区，再 `UPDATE`，两步操作非原子。

**现有防护（三层）**：
1. **ShedLock**（`@SchedulerLock`）：保证同一时刻只有一个调度器实例执行 `reclaimExpiredPartitions`，实例级互斥已覆盖并发调度器场景。
2. **乐观锁 CAS**：`resetForDispatch` 的 `WHERE version = expectedVersion` 保证即使 ShedLock 锁过期后出现短暂并发，只有一个写入成功。
3. **Worker CLAIM**：下游 Worker 执行前必须先 CLAIM（架构硬约束），是第三道防线。

**结论**：原描述中"并发调度器实例同时回收同一分区导致重复派发"的场景已被 ShedLock + CAS version + Worker CLAIM 三层防护覆盖，不存在实际风险。原子 UPDATE 或分布式锁方案与现有机制重叠，投入产出比低，不再列为待修复项。

**备注**：partition `resetForDispatch` 成功但 task `resetForRetry` 版本冲突时，partition 变为 READY 但未写 outbox，需等下一轮 reclaim 周期处理，属于可接受的自愈行为。

---

### A-3：Outbox Forwarder 大事务风险（风险已缓解，暂不修改）

**位置**：`DefaultScheduleForwarder.java:30-101`

**现象**：`advance()` 方法 `@Transactional` 横跨三阶段（markPublishing CAS → Kafka 异步发送 + join 等待 ACK → markPublished/markFailed），Kafka 延迟会拉长事务持有时间。

**现有缓解措施（四层）**：
1. **熔断器**（`OutboxPublishCircuitBreaker`）：连续 3 轮失败后进入 60s cooldown，防止 Kafka 故障时反复占用连接。
2. **批次上限**（`batchSize=100`）：单次事务最多处理 100 条，限制单事务时长上限。
3. **ShedLock 1 分钟超时**：事务不会无限挂起。
4. **分片**（`shardTotal/shardIndex`）：多实例并行时各自独立事务和锁，不争抢同一连接。

**残留风险**：Kafka P99 延迟极端飙高（>5s）时，事务持有时间仍可能影响连接池。当前生产环境未观测到此问题。

**方案评估**：
- **拆分事务**：技术正确，但需为 `PUBLISHING` 中间状态引入超时回收补偿调度器（进程崩溃时该状态会变成孤儿），改造成本高。
- **Debezium CDC**：架构收益最大，但引入整套 CDC 基础设施，运维复杂度剧增，与当前 Outbox 轮询风格不符，ROI 低。

**结论**：现有四层缓解已将风险控制在可接受范围，暂不修改。若未来压测发现 Kafka P99 延迟 >5s 导致连接池告警，再按拆分事务方案改造。

---

### ✅ A-4：Kafka Offset 语义与故障恢复延迟（已验证：现有实现已完整覆盖）

**现象**：原描述假设 Worker Kafka Consumer 使用 `enable.auto.commit=true`，实际代码已采用手动提交。

**现有防护（四层）**：
1. **`MANUAL_IMMEDIATE` 模式**（`KafkaConsumerConfiguration.java:76`）：`AckMode.MANUAL_IMMEDIATE`，非 auto commit，由子类显式调用 `acknowledgment.acknowledge()`。
2. **Ack 时机正确**（如 `ImportTaskConsumer.java:60-63`）：`doConsume()` 返回 `true`（CLAIM → 执行 → REPORT 全部完成）才提交 offset；背压拒绝时返回 `false` 不提交，消息自动重投。
3. **CLAIM CAS 幂等**（`TaskDispatchExecutor.java:22`）：即使重复消费，Worker 必须 `claim()` 成功才执行，CLAIM 是 CAS 操作，重复消费时 claim 失败直接跳过。
4. **DLQ 兜底**（`AbstractTaskConsumer.java:103-109`）：异常消息发送死信队列后仍 ack，防止毒丸阻塞消费。

**Rebalance 停顿**：Consumer Group Rebalance 期间的秒级停顿是 Kafka 固有行为，非 bug。`maxPollIntervalMs=600000`（10 分钟）配置合理，给长任务足够处理时间，避免不必要的 rebalance。

**结论**：改进方向中的两项措施均已落地，不存在实际风险，不再列为待修复项。

---

### A-5：单库瓶颈

**现象**：平台库（`batch.*`）和业务库（`biz.*`）目前为同一 PostgreSQL 实例，所有服务共享连接池。

**风险**：业务数据大查询（如导出扫描全表）会抢占平台写入路径的数据库资源。

**改进方向**：
- 短期：为平台库和业务库配置独立连接池，并设置 `max-pool-size` 上限隔离。
- 长期：物理分离，平台库和业务库使用独立 PostgreSQL 实例。

---

### ✅ A-6：Kafka 单节点无副本（已修复）

**现象**：开发 / 测试环境 Kafka 为单节点，Topic 副本数为 1（`replication-factor=1`），节点故障即数据丢失。

**已落地措施**：
1. **Helm Chart 环境分离**：`values.yaml` 默认单节点（`kafka:9092`），`values-prod.yaml` 已配置 3 节点集群（`kafka-0/1/2.kafka-headless`）。
2. **Topic 副本数可配**：`init-kafka-topics.sh` 通过 `KAFKA_TOPIC_REPLICATION_FACTOR` 环境变量控制，生产部署时设为 3。
3. **Producer `acks=all`**：`batch-defaults.yml` 全局配置 `spring.kafka.producer.acks=all`，`KafkaConsumerConfiguration` 中 ProducerFactory 显式设置 `ACKS_CONFIG=all`，确保所有 ISR 副本确认后才返回成功。

**生产部署注意**：Kafka broker 需配置 `min.insync.replicas=2`（broker/topic 级配置，不在应用层）。

---

### ✅ A-7：控制台 API 无限流保护 — 已修复

**位置**：`ConsoleSecurityConfiguration.java`

**现象**：控制台 API 无速率限制，攻击者可对登录接口发起暴力破解，或对数据查询接口发起 DoS 攻击。

**修复**（2026-04-08）：
- 新增 `ConsoleRateLimitProperties`（`batch.console.security.rate-limit`）：`loginIpLimitPerMinute=10`、`sensitiveOpUserLimitPerMinute=30`，可配置可关闭。
- 新增 `SlidingWindowRateLimiter`：内存滑动时间窗口（1 分钟），无外部依赖，每 key 独立窗口，`synchronized tryAcquire` 线程安全。
- 新增 `ConsoleRateLimitFilter`（`OncePerRequestFilter`）：
  - `POST /api/console/auth/login`：基于客户端真实 IP 限流，超限返回 HTTP 429。
  - `POST /api/console/triggers/**`：基于已认证用户名限流，超限返回 HTTP 429。
  - 支持 `X-Forwarded-For` / `X-Real-IP` 代理头解析。
- filter 注册在 `ConsoleAuthenticationFilter` 之前，`ResultCode` 新增 `RATE_LIMITED(429)`。

---

### A-8：KMS 密钥明文存于配置

**现象**：SMTP / SFTP 密码、第三方 API Key 等敏感配置目前以明文存储于 `application.yml` 或 `.env` 文件中（或数据库 `file_channel_config` 表的 `channel_params` 列）。

**风险**：配置文件泄漏即凭证全部暴露。

**改进方向**：
- 使用 HashiCorp Vault / AWS Secrets Manager / Kubernetes Secrets 管理凭证，应用启动时动态拉取。
- 数据库中的通道凭证字段使用应用层加密（AES-256-GCM），密钥托管于 KMS。

---

## 三、企业场景适配分析

### 场景 A：金融机构对账文件批处理

**匹配度**：★★★★☆（85%）

| 能力维度 | 现状 | 差距 |
|----------|------|------|
| 大文件分区并行处理 | ✅ Partition 机制（STATIC/DYNAMIC/AUTO，上限 256） | ✅ 单分区=最小调度单元，多分区跨节点并行；单分区内跨节点属过度设计 |
| 精确一次语义 | ✅ DatabaseIdempotencyGuard | ✅ MANUAL_IMMEDIATE + CLAIM CAS + DLQ 已覆盖（见 A-4） |
| 审计可追溯 | ✅ job_execution_log | ⚠️ 字段级审计不足 |
| 加密传输 | ✅ SFTP with known_hosts | ⚠️ 通道凭证明文存储 |
| 对账差异报告 | ⚠️ 平台层已有：ImportDataQualityService（行数/checksum/schema 校验）、FileGovernanceReconcileScheduler（存储 vs DB 对账）、outputSummary（分区执行摘要） | 业务层差异比对（源 vs 目标明细级核对）属业务逻辑，应由 pipeline step 扩展实现，不内置于平台 |

---

### 场景 B：电商大促批量营销

**匹配度**：★★★☆☆（65%）

| 能力维度 | 现状 | 差距 |
|----------|------|------|
| 高并发触发 | ✅ ConsoleRateLimitFilter（滑动窗口，HTTP 429）+ TenantActionRateLimiter（Redis 令牌桶，租户级限流） | ✅ 控制台 API + Orchestrator 双层限流已落地 |
| 多租户隔离 | ✅ ConsoleTenantGuard | ✅ |
| 动态扩容 | ✅ HPA 已配置（values-prod.yaml：import/export 2-10、dispatch 2-6，CPU 70%） | ✅ 生产 HPA 已启用；KEDA 按需引入 |
| 实时进度反馈 | ✅ SSE（多个 RealtimeController：JobInstance/Ops/Outbox/Worker 等）+ Redis Pub/Sub | ✅ |
| 发送失败补偿 | ✅ DeadLetterPublisher + 审批重放（approval_command） | ⚠️ 补偿链路需业务侧适配（具体重放逻辑依赖业务 step 实现） |

---

### 场景 C：政务数据交换（跨网闸）

**匹配度**：★★★★☆（80%）

| 能力维度 | 现状 | 差距 |
|----------|------|------|
| 多协议派发（SFTP/FTP/HTTP） | ✅ | ✅ |
| 文件完整性校验 | ✅ Export StoreStep 三重 SHA-256（本地→.part→正式对象）、Import ReceiveStep checksum 元数据 + ValidateStep 校验 | ✅ |
| 离线通道支持 | ⚠️ 通道不可达只有重试 | 需补人工干预入口 |
| 操作审计留痕 | ✅ dispatch_receipt / audit_log | ✅ |
| 数据脱敏 | ✅ ContentMaskingUtils（GDPR/PCI），已集成 Import + Console | ⚠️ Export 未集成——属合理边界：输出脱敏应由业务查询层完成，平台无 PII 字段元数据；文件内容安全由 content_encryption 覆盖 |

---

### 场景 D：保险理赔批量处理

**匹配度**：★★★★☆（80%）

| 能力维度 | 现状 | 差距 |
|----------|------|------|
| 多级审批流 | ✅ ApprovalService | ⚠️ 审批规则硬编码，不可配置 |
| SLA 超时告警 | ✅ JobSlaScheduler（deadline/duration 违规检测）+ AlertEventService + Prometheus metric | ✅ 外部推送已支持：alertmanager-batch-template.yml 配 5 个 webhook receiver（按 severity/alert_group 路由） |
| 批量撤销补偿 | ✅ CompensationService + DeadLetterApprovalReplayE2eIT（E2E 已覆盖） | ✅ |
| 数据血缘追踪 | ⚠️ 隐式血缘链已有：file_record → pipeline_instance → job_partition → job_task → dispatch_receipt，trace_id 贯穿 | 现有关联 + trace_id 可 join 重建血缘；独立 lineage 服务属过度设计 |

---

### 场景 E：零售 ERP 数据同步

**匹配度**：★★★☆☆（70%）

| 能力维度 | 现状 | 差距 |
|----------|------|------|
| 增量同步 | ⚠️ 无增量标记机制 | 需业务层设计水位线 |
| 多格式解析（CSV/XML/JSON） | ✅ ParseStep 支持 5 种格式：JSON（流式解析，支持数组/`{records:[]}` 信封）、DELIMITED（CSV）、XML、FIXED_WIDTH、EXCEL；resolveFormat 自动探测 | ✅ |
| 错误行跳过与汇总 | ⚠️ 全量失败，无行级错误跳过 | 需补 partial-success 语义 |
| 实时同步兜底 | ❌ 仅批量，无 CDC 兜底 | 超出当前系统边界 |

---

### 场景 F：跨境供应链文件协作

**匹配度**：★★★☆☆（60%）

| 能力维度 | 现状 | 差距 |
|----------|------|------|
| 多渠道同时派发 | ⚠️ DispatchChannelGateway 按 channel_type 路由（SFTP/HTTP/NAS/OSS/EMAIL/LOCAL），单任务单渠道 | ⚠️ fan-out 可通过 DAG workflow 多节点实现（每节点配不同渠道），无需平台内置；单任务多渠道属业务编排层 |
| 版本管理 | ✅ file_record 表已有 file_version / file_generation_no / is_latest 字段，PlatformFileRuntimeRepository 自动递增 generation 并标记历史版本 | ✅ |
| 接收方确认回执 | ✅ dispatch_receipt + DispatchReceiptPollScheduler（轮询外部 receipt_poll_url，解析 acknowledged/ACKED/SUCCESS 状态，markAcked 更新） | ✅ 外部回传已支持 |
| 时区处理 | ✅ 数据库全量 TIMESTAMPTZ（PostgreSQL 自动 UTC 存储），Java 侧使用 Instant（UTC） | ✅ |

---

### 场景 G：医疗数据归档合规

**匹配度**：★★★★☆（75%）

| 能力维度 | 现状 | 差距 |
|----------|------|------|
| 数据保留策略 | ❌ 无自动归档/清理 | 需设计 retention 策略 |
| HIPAA 合规 | ⚠️ ContentMaskingUtils 有 PII 脱敏 | ⚠️ 未见 PHI 专项处理 |
| 访问日志不可篡改 | ✅ 落库 | ⚠️ 无防篡改签名 |
| 异地灾备 | ❌ 无多活设计 | 超出当前系统边界 |

---

## 四、优先级修复矩阵

### P0（立即修复，阻断生产上线）

| 编号 | 问题 | 修复方式 | 验收指标 |
|------|------|----------|----------|
| S-1 | 硬编码默认密码 | 种子数据改占位符 + 初始化脚本 | 无法用 admin123 登录 |
| S-3 | Demo 模式鉴权绕过 | 删除或 Profile 隔离 | prod Profile 启动时无绕过路径 |
| S-4 | Trigger API 无鉴权 | 增加 API Key 校验 | 无 Key 请求返回 401 |
| S-5 | JWT secret 默认值 | 启动检测 + 环境变量注入 | 默认值时拒绝启动 |

### P1（高优先级，上线前修复）

| 编号 | 问题 | 修复方式 | 验收指标 |
|------|------|----------|----------|
| S-2 | CSRF + Token in Query | 移除 Query Param 支持 | Token 仅从 Header 读取 |
| S-6 | HTTP Dispatch SSRF | 目标 URL 白名单 | 内网 IP 请求被拒绝 |
| ✅ S-8 | SQL 白名单绕过 | `checkAllowedSchemas` 无前缀直接拒绝 | 无前缀表名抛出异常（已验证）|
| S-9 | Worker 无鉴权 | 增加共享 API Key | 无 Token 上报返回 401 |
| ✅ A-2 | Lease Reclaim 竞态 | ShedLock + CAS version + Worker CLAIM 三层防护已覆盖 | 已验证：无需额外修复 |

### P2（中优先级，上线后两周内修复）

| 编号 | 问题 | 修复方式 | 验收指标 |
|------|------|----------|----------|
| S-7 | SFTP 主机验证可关闭 | Runbook 强化 + 审计日志 | 生产通道配置无 no 值 |
| ✅ S-10 | OkHttp 无超时 | `HttpDispatchChannelProperties` 注入超时 | connect 10s / read+write 30s（已验证）|
| S-11 | .env 敏感信息 | gitignore + CI 扫描 | gitleaks 无告警 |
| ✅ A-1 | Outbox 轮询瓶颈 | 自适应轮询（min 200ms/max 5s/backoff 1.5x）| 积压时延迟约 200ms（已落地）|
| A-3 | Outbox 大事务 | 熔断器 + 批次上限 + ShedLock + 分片已缓解 | 暂不修改，Kafka P99 >5s 时再拆分事务 |
| ✅ A-7 | 无限流保护 | 滑动窗口限流（登录 IP 10次/min，敏感操作用户 30次/min）| HTTP 429 已生效（已验证）|

### P3（低优先级，版本迭代中逐步改进）

| 编号 | 问题 | 修复方式 |
|------|------|----------|
| S-12 | Demo 模式堆栈暴露 | 全局异常处理屏蔽 |
| ✅ A-4 | Kafka offset 语义 | MANUAL_IMMEDIATE + CLAIM CAS + DLQ 已覆盖 |
| A-5 | 单库瓶颈 | 连接池隔离 → 物理分离 |
| ✅ A-6 | Kafka 单节点 | Helm 3 节点 + acks=all + replication-factor 可配 |
| A-8 | KMS 密钥明文 | Vault / KMS 集成 |

---

## 五、开源软件使用分析

### 5.1 依赖概况

系统共引入 **30+ 个直接运行时依赖**（不含传递依赖），主要通过 Spring Boot 4.0.3 BOM 统一管理版本。完整清单见 `docs/compliance/THIRD-PARTY-LICENSES.md`。

### 5.2 许可证分布

| 许可证家族 | 组件数 | 风险等级 | 代表组件 |
|-----------|--------|---------|---------|
| Apache-2.0 | ~25 | 低 | Spring Boot, Kafka, MyBatis, Flyway, MinIO SDK, POI, ShedLock, Micrometer, OTel |
| MIT | 3 | 低 | Lombok, SLF4J, Mockito |
| BSD-2/3-Clause | 3 | 低 | PostgreSQL JDBC, JSch, Redis |
| EPL-2.0 | 3 | 中 | Jakarta EE APIs, JUnit, Angus Mail |
| EPL-1.0 + LGPL-2.1 | 1 | 中 | Logback |
| AGPL-3.0 | 2 | **高** | Grafana（运维基础设施）、MinIO Server（对象存储） |

### 5.3 高风险许可证评估

| 组件 | 许可证 | 使用方式 | 风险缓解 |
|------|--------|---------|---------|
| Grafana | AGPL-3.0 | 独立运维基础设施，不嵌入应用代码 | 作为独立服务部署，不修改源码，不对外提供 SaaS，合规风险可控 |
| MinIO Server | AGPL-3.0 / 商业 | 独立对象存储服务 | 生产环境可替换为 S3/OSS/商业 MinIO；开发/测试使用社区版 |
| Angus Mail | EPL-2.0 + GPL-2.0 CE | dispatch 模块 SMTP 分发 | GPL with Classpath Exception 允许链接不传染；需在许可证清单标注 |
| Logback | EPL-1.0 + LGPL-2.1 | 日志后端（transitive） | LGPL 动态链接不传染，但需保留许可证声明 |

### 5.4 合规工具链

系统已在 `pom.xml` 中配置 `compliance` Profile：

- **CycloneDX Maven Plugin 2.9.1**：生成 SBOM（`target/bom.json` / `target/bom.xml`）
- **License Maven Plugin 2.4.0**：聚合第三方许可证报告

```bash
mvn -P compliance cyclonedx:makeAggregateBom license:aggregate-add-third-party
```

### 5.5 待改进项

| 编号 | 问题 | 建议 | 优先级 |
|------|------|------|--------|
| OSS-1 | MinIO Server 生产合规未确认 | 正式评估 AGPL 影响或切换 S3/商业版 | P1（上线前） |
| OSS-2 | CI 未集成许可证扫描 | 在 CI pipeline 中加入 `license:aggregate-add-third-party` 并阻断未知许可证 | P2 |
| OSS-3 | NOTICE 文件未生成 | 生成并维护 `NOTICE` 文件，保留 Apache-2.0 组件的版权声明 | P2 |
| OSS-4 | testcontainers-redis 版本不一致 | dispatch (2.2.4) vs console-api (2.2.2)，建议统一到 dependencyManagement | P3 |
| OSS-5 | DEPENDENCY-APPROVAL.md 未创建 | 为 AGPL/EPL 类依赖补充正式审批记录 | P2 |

---

## 六、当前状态与结论

截至 2026-04-09，系统核心链路（Import / Export / Dispatch）已完整打通，E2E 覆盖 15 个测试类，结构化治理（`AbstractWorkerLoop` / `DatabaseIdempotencyGuard` / `PathSanitizer` 等）全部落地。

**最关键的风险集中在安全层面**：P0 中的 4 项（默认密码、鉴权绕过、Trigger 无鉴权、JWT 默认 Secret）在生产上线前必须全部修复，任何一项未修复都会导致系统在生产环境存在严重暴露面。

P1 的架构竞态（Lease Reclaim）和安全补丁（CSRF、SSRF、SQL 白名单绕过、Worker 无鉴权）应在上线前完成。P2/P3 可在上线后按版本迭代推进。

**开源合规方面**：30+ 运行时依赖中绝大多数为 Apache-2.0 / MIT / BSD 宽松许可证，合规风险低。AGPL 类组件（Grafana、MinIO Server）均作为独立基础设施部署，不嵌入应用代码。上线前需完成 MinIO Server 生产合规评估（OSS-1）和 CI 许可证扫描集成（OSS-2）。

**结论**：功能层面系统已具备生产就绪条件；安全加固是当前最高优先级工作，完成 P0+P1 后方可评估 staging 实测与生产发布。开源合规基线已建立，需在上线前完成 AGPL 组件正式评估。
