# 能力开关运维手册

> 汇总跨模块运行时能力开关：配置 key、实际默认、启用条件、风险、验证和回滚。索引随实现持续维护，不再用固定数量描述范围。
>
> 配套阅读：[`docs/architecture/rework-classification.md`](../architecture/rework-classification.md) Phase 2 章节、[`docs/coding-conventions.md` §13.3](../coding-conventions.md) 配置归属决策。

---

> **配置优先级**：显式环境变量 > docker-compose `:-` 回退 > application.yml `${VAR:fallback}` > Java 字段默认（`@Data` 字段初始化值，回退中的回退）。
>
> **本文档以代码 + yml 为权威**。`rework-classification.md` Phase 2 表格用作交叉对照（已于 2026-04-25 与本文档对齐）。
>
> **容器透传契约**：本表登记的公共环境变量必须由 `docker/compose/app.yml` 显式透传；生产 Chart 一等开关必须由 `helm/batch-platform/templates/configmap.yaml` / `secret.yaml` 显式渲染。`feature-switch-registry.yml` 是 CI required-vars 的唯一登记源；`scripts/ci/check-config-defaults-sync.py --check` 检查 compose 默认值漂移，`scripts/ci/check-helm-env-sync.py` 检查 Helm env 漂移和缺失入口。

### 0. 最关键开关速查（按影响面）

> 完整索引见 §1；这里只列“动它之前必须读对应小节”的开关。

| 类别 | 开关 | 默认 | 一句话 |
|---|---|---|---|
| 有状态后端 | `batch.storage.backend` | s3 | 对象存储后端（s3/filesystem）；切换需迁移 + 一次性 `BATCH_STORAGE_BACKEND_CUTOVER_ID` |
| 有状态后端 | `batch.quota.runtime-store` | redis | 配额运行时后端；切换需 snapshot 核对 + `BATCH_QUOTA_BACKEND_CUTOVER_ID` |
| 有状态后端 | `batch.worker.report-outbox.enabled` | false | worker 上报 Outbox；启停需排空 + `BATCH_WORKER_REPORT_OUTBOX_CUTOVER_ID` |
| 有状态后端 | `batch.shedlock.provider` | redis | 调度锁后端（redis/jdbc）；**必须全停→切配置→全起**，双 provider 会重复触发 |
| 调度 | `batch.mq.routing.mode` | TENANT | 派发 topic 分流；切换必须先升 consumer 再切 producer |
| 正确性 | `batch.worker.checkpoint.enabled` | true | 断点续跑总开关（P0 默认开，显式 false 回滚） |
| 正确性 | `batch.resource-scheduler.default-exceeded-strategy` | QUEUE_DEFER | 超限策略；REJECT 是旧行为，可回退 |
| 安全 | `batch.request-signing.enabled` | false | 内部写请求签名防重放；灰度必须先升 SDK |
| 安全 | `batch.console.ai.enabled` | false | Console AI 入口总开关 |
| 安全 | `batch.console.captcha.provider` | none | 登录验证码（none/selfhosted/tencent/aliyun） |
| 弹性 | `batch.quota.redis.failure-mode` | FAIL_CLOSED | Redis 故障时配额行为；生产禁止 FAIL_OPEN |
| 弹性 | `batch.console.read-replica.enabled` | true | 读副本路由；无从库部署建议显式关闭 |

---

## 1. 开关索引

> **重要度**：**P0** = 有状态后端/迁移义务或安全红线，动前必读对应小节；**P1** = 常见生产运维开关；**P2** = 低风险调优。
> **枚举值** = 该开关的合法取值；布尔开关为 `true` / `false`。
> **设置方式**：环境变量（compose / .env / Helm）或 yml 均可；标注“需 cutover-id”的开关必须同时提供一次性切换 ID。
> **测试（IT/E2E 视角）**：✅ = 有集成/端到端测试真实跑该开关路径（Testcontainers / 完整链路）；⚠️ = 仅单测（mock/组件级），或 IT 只是把开关关掉；❌ = 无任何测试。

### 1.A 有状态后端切换（P0，需迁移 + cutover-id 或全停切换）

| 配置 key | 枚举值 | 默认 | 作用 | env | 测试 |
|---|---|---|---|---|---|
| `batch.storage.backend` | `s3` / `filesystem` | **s3** | 对象存储后端：S3 协议全系（MinIO/AWS S3/阿里 OSS/腾讯 COS）或本地 NAS/文件系统；切换需迁移对象 + `BATCH_STORAGE_BACKEND_CUTOVER_ID` | `BATCH_STORAGE_BACKEND` | ✅ |
| `batch.quota.runtime-store` | `redis` / `database` | **redis** | 配额限流运行时状态后端（Lua 原子 vs PG 乐观锁）；切换需 snapshot 核对 + `BATCH_QUOTA_BACKEND_CUTOVER_ID` | `BATCH_QUOTA_RUNTIME_STORE` | ✅ |
| `batch.worker.report-outbox.enabled` | `true` / `false` | **false** | worker 上报走事务性 Outbox（防 Kafka 写失败丢报告）；启停/换存储需排空 + `BATCH_WORKER_REPORT_OUTBOX_CUTOVER_ID` | `BATCH_WORKER_REPORT_OUTBOX_ENABLED` | ✅ |
| `batch.shedlock.provider` | `redis` / `jdbc` | **redis** | 调度锁后端（48 处 `@SchedulerLock`）；**必须全停 → 切配置 → 全起**，双 provider 会重复触发任务 | `BATCH_SHEDLOCK_PROVIDER` | ⚠️ |

### 1.B 调度与正确性

| 配置 key | 枚举值 | 默认 | 作用 | 重要度 | env | 测试 |
|---|---|---|---|---|---|---|
| `batch.mq.routing.mode` | `SINGLE` / `TENANT` / `PRIORITY` | **TENANT** | 派发 Kafka topic 分流粒度；切换必须先升 consumer 再切 producer | **P0** | `BATCH_MQ_ROUTING_MODE` | ⚠️ |
| `batch.worker.checkpoint.enabled` | `true` / `false` | **true** | 断点续跑总开关（P0 默认开，显式 false 回滚到全量重跑） | **P0** | `BATCH_WORKER_CHECKPOINT_ENABLED` | ✅ |
| `batch.worker.checkpoint.stage-skip.enabled` | `true` / `false` | **false** | PROCESS 阶段级续跑（仅 COMPUTE+VALIDATE，多分片自动降级） | P1 | `BATCH_WORKER_CHECKPOINT_STAGE_SKIP_ENABLED` | ✅ |
| `batch.resource-scheduler.default-exceeded-strategy` | `QUEUE_DEFER` / `REJECT` | **QUEUE_DEFER** | 超配额租户的默认策略（REJECT 为旧行为，可回退） | P1 | `BATCH_RESOURCE_SCHEDULER_DEFAULT_EXCEEDED_STRATEGY` | ⚠️ |
| `batch.worker.lease.renew-batch-max-items` | 正整数 | **256** | 单次 renew-batch HTTP 最多携带任务数，超出自动拆单 | P2 | `BATCH_WORKER_LEASE_RENEW_BATCH_MAX_ITEMS` | ⚠️ |

### 1.C 安全

| 配置 key | 枚举值 | 默认 | 作用 | 重要度 | env | 测试 |
|---|---|---|---|---|---|---|
| `batch.security.bypass-mode` | `true` / `false` | **false** | 安全旁路（认证/脱敏/加解密/审批/渠道校验全放宽）；**仅本地/E2E**，生产 profile 拒绝 true | **P0** | `BATCH_SECURITY_BYPASS_MODE` | ✅ |
| `batch.request-signing.enabled` | `true` / `false` | **false** | 内部写请求 HMAC 签名+ts+nonce 防重放；灰度先升 SDK 再开服务端 | P1 | `BATCH_REQUEST_SIGNING_ENABLED` | ⚠️ |
| `batch.rate-limit.enabled` | `true` / `false` | **true** | 租户级固定窗口限流总开关（高水位防盗刷） | P1 | `BATCH_RATE_LIMIT_ENABLED` | ✅ |
| `batch.console.ai.enabled` | `true` / `false` | **false** | Console AI 入口总开关（开启后仍受角色白名单/独立限流约束） | P1 | `BATCH_CONSOLE_AI_ENABLED` | ⚠️ |
| `batch.console.ai.provider` | `anthropic` / `openai` | **ANTHROPIC** | AI provider；枚举绑定，拼写错误启动失败 | P2 | `BATCH_CONSOLE_AI_PROVIDER` | ⚠️ |
| `batch.console.captcha.provider` | `none` / `selfhosted` / `tencent` / `aliyun` | **none** | 登录验证码实现；任一时刻只装一个；tencent/aliyun 需站点 key + 外联 | P1 | `BATCH_CONSOLE_CAPTCHA_PROVIDER` | ⚠️ |

### 1.D 弹性 / 性能 / 观测

| 配置 key | 枚举值 | 默认 | 作用 | 重要度 | env | 测试 |
|---|---|---|---|---|---|---|
| `batch.quota.redis.failure-mode` | `FAIL_CLOSED` / `FAIL_OPEN` | **FAIL_CLOSED** | Redis 故障时配额行为；FAIL_OPEN 等同关闭限流，**生产禁止** | **P0** | `BATCH_QUOTA_REDIS_FAILURE_MODE` | ⚠️ |
| `batch.console.read-replica.enabled` | `true` / `false` | **true** | 读副本路由；无从库部署建议显式 false 避免反复探测 | P1 | `BATCH_CONSOLE_READ_REPLICA_ENABLED` | ✅ |
| `batch.storage.startup-check.enabled` | `true` / `false` | **true** | 启动冒烟自检（put/exists/statSize/get/list/delete），失败 fail-fast | P1 | `BATCH_STORAGE_STARTUP_CHECK_ENABLED` | ⚠️ |
| `batch.storage.encryption.decorator-enabled` | `true` / `false` | **false** | BATCHENC 整对象加密装饰层；开启后 presign 直传禁用、range 读退化 | P1 | `BATCH_STORAGE_ENCRYPTION_DECORATOR_ENABLED` | ⚠️ |
| `batch.storage.s3.auto-create-bucket` | `true` / `false` | **true** | 启动自动建桶；AWS/OSS/COS 等托管云**必须 false** | P1 | `BATCH_S3_AUTO_CREATE_BUCKET` | ⚠️ |
| `batch.scheduler.worker-cache.enabled` | `true` / `false` | **true** | ONLINE worker 列表缓存（Redis 故障 fail-open 直通 DB） | P2 | `BATCH_SCHEDULER_WORKER_CACHE_ENABLED` | ⚠️ |
| `batch.quota.snapshot.enabled` | `true` / `false` | **true** | Redis 配额状态周期快照到 PG（审计 / 降级数据源） | P2 | `BATCH_QUOTA_SNAPSHOT_ENABLED` | ⚠️ |

### 1.E 分片路由 / 导入扫描 / 原子任务

| 配置 key | 枚举值 | 默认 | 作用 | 重要度 | env | 测试 |
|---|---|---|---|---|---|---|
| `batch.datasource.business.routing.enabled` | `true` / `false` | **false** | 业务库租户分片路由（未配 shard 启动失败） | P1 | `BATCH_DATASOURCE_BUSINESS_ROUTING_ENABLED` | ⚠️ |
| `batch.datasource.business.routing.placement-source` | `CONFIG` / `TABLE` | **CONFIG** | 分片放置来源（CONFIG=hash+silo；TABLE=在线维护表） | P1 | `BATCH_DATASOURCE_BUSINESS_ROUTING_PLACEMENT_SOURCE` | ⚠️ |
| `batch.worker.import.scanner.done-file-format` | `MARKER` / `MANIFEST` / `JSON` | **MARKER** | done 文件格式；MANIFEST/JSON 走 sidecar 清单强校验 | P2 | `BATCH_WORKER_IMPORT_SCANNER_DONE_FILE_FORMAT` | ⚠️ |
| `batch.worker.import.scanner.done-file-suffix` | 字符串 | **`.done`** | done 文件后缀 | P2 | `BATCH_WORKER_IMPORT_SCANNER_DONE_FILE_SUFFIX` | ⚠️ |
| `batch.worker.import.scanner.batch-manifest-enabled` | `true` / `false` | **false** | 扫描期强校验批次清单（文件完整性） | P1 | `BATCH_WORKER_IMPORT_SCANNER_BATCH_MANIFEST_ENABLED` | ✅ |
| `batch.file-governance.arrival.require-verified` | `true` / `false` | **false**（jar）/ **true**（helm） | 到达组要求文件已校验通过才放行 | P1 | `BATCH_FILE_GOVERNANCE_ARRIVAL_REQUIRE_VERIFIED` | ⚠️ |
| `batch.worker.atomic.enabled-task-types` | 白名单：`shell`/`sql`/`stored_proc`/`http` | **空（全部启用）** | atomic 执行器白名单 | P1 | `BATCH_WORKER_ATOMIC_ENABLED_TYPES` | ⚠️ |
| `batch.worker.executors.http.max-request-body-bytes` | 正整数 | **1048576** | atomic HTTP 任务请求体上限 | P2 | `BATCH_WORKER_ATOMIC_HTTP_MAX_REQUEST_BODY_BYTES` | ⚠️ |
| `batch.sensor.enabled` | `true` / `false` | **true** | Sensor 轮询调度总开关（不影响已有 WAIT 数据） | P2 | `BATCH_SENSOR_ENABLED` | ⚠️ |

### 1.F 限流阈值（P2 调参类）

| 配置 key | 默认 | 作用 | env | 测试 |
|---|---|---|---|---|
| `batch.rate-limit.max-{new,register,release,claim,report}-requests-per-tenant-per-minute` | launch/release 3000、register 300、claim/report 12000 | 租户级高水位限流；<=0 关闭单项 | `BATCH_RATE_LIMIT_MAX_*_REQUESTS_PER_TENANT_PER_MINUTE` | ⚠️ |
| `batch.console.security.rate-limit.expensive-op-user-limit-per-minute` | 10 | 导出/导入/Excel/报表按用户限流（fail-open） | `BATCH_CONSOLE_SECURITY_RATE_LIMIT_EXPENSIVE_OP_USER_LIMIT_PER_MINUTE` | ⚠️ |
| `batch.console.security.rate-limit.file-op-user-limit-per-minute` | 60 | `/api/console/files/` 子树按用户限流（fail-open） | `BATCH_CONSOLE_SECURITY_RATE_LIMIT_FILE_OP_USER_LIMIT_PER_MINUTE` | ⚠️ |
| `batch.console.security.rate-limit.redis-failure-threshold` / `redis-circuit-open-seconds` | 3 / 15s | Redis 连续失败短路与冷却期（fail-open） | `BATCH_CONSOLE_SECURITY_RATE_LIMIT_REDIS_FAILURE_THRESHOLD` / `BATCH_CONSOLE_SECURITY_RATE_LIMIT_REDIS_CIRCUIT_OPEN_SECONDS` | ⚠️ |

> **已移除开关（历史，勿再配置）**：`batch.trigger.quartz-datasource.enabled`（2026-04-25，Phase 2 半成品）、`batch.trigger.async-launch.enabled`（2026-05-02，异步路径固化）。
>
> **per-template / per-channel 开关不在此表**：ADR-041 的 trailer 笔数校验、控制金额对账、出站 trailer、投递后回读是模板/渠道级配置，归 [`../design/file-pipeline-design.md`](../design/file-pipeline-design.md)；本表只收全局 yml/env 开关。

### 1.1 有状态后端切换守护

Quota、Worker Report Outbox 和对象存储不是普通布尔开关。应用启动时会把当前 backend 与非敏感定位信息登记到 `batch.stateful_backend_binding`；后续启停、后端或定位变化若未提供新的单次 `cutover-id`，启动直接失败。每次接受的切换写入 `batch.stateful_backend_cutover_history`，同一功能不能复用旧 ID。

`cutover-id` 只表示“运维已完成并接受本次切换”，不会代替数据迁移。正确顺序是：停止写入并排空、迁移或核对历史状态、设置新的唯一 ID、滚动重启、验证新后端，再清除该环境变量。首次升级只登记当前基线，不需要 ID；不要在首次部署 V193 的同时改变后端配置。

```sql
SELECT feature_key, backend, backend_identity, generation, updated_at
  FROM batch.stateful_backend_binding
 ORDER BY feature_key;
```

### 1.2 Worker checkpoint（P0 默认启用）

`batch.worker.checkpoint.enabled` 默认 **true**（P0，2026-07），改动需重启 worker 生效。系统未上线故不做影子期 / 按租户
渐进灰度：sim/e2e 全链验证通过后直接默认启用，开关保留作回滚（显式 `false` + 重启即退回今天行为）。
Import 在 LOAD 前校验插件幂等能力；`NONE/UNKNOWN` 会以 `IMPORT_LOAD_CONFIG_INVALID` 拒跑，不能绕过。
`PARTITION_REPLACE_COPY` 与行号续跑互斥，须对该模板显式设 `false`。
多分区任务（`partitionCount>1`，含 ADR-046 bundle 展开）自动降级不续跑（共享 `pipeline_instance` 位点会互撞），无需配置——续跑只对单分区任务生效；`partitionCount` **缺失**=单分区放行（常态），**present 但非法**（非数字/`<=0`）走 fail-closed 降级（2026-07 数据正确性补丁）。

**与 `compensate_on_failure` 的交互（2026-07）**：`compensate_on_failure=true` 模板 + 本开关开是**安全组合**——反向补偿前先作废该实例 checkpoint 位点，成功后才允许删本 run 业务数据。位点作废失败则停止反向删除，避免“业务数据已删但重试仍跳过”。无需额外配置，详见 howto「与 compensate_on_failure / 多分区 / 文件指纹的交互」。

上线后观测：`batch.worker.checkpoint.operations.total{operation="load",outcome="resumable"}`（命中率）、
`batch.worker.checkpoint.resume.skipped.records.total`（省下的重复处理量）、全部 `outcome="failure"` 是否持续为 0。
关闭开关不会删除 `batch.pipeline_progress`，下次开启仍可读取未完成位点。完整约束、SQL 验证与回滚见
[`platform-worker-checkpoint-howto.md`](./platform-worker-checkpoint-howto.md)。

### 1.3 限流防盗刷（高水位，2026-06-24）

防接口盗刷的第一道闸门：api_key 泄漏后靠按租户限流把"被打爆"挡在租户级。阈值都设在**远高于合法峰值**的高水位，只拦 runaway 滥用，不误伤压测/高峰；需更严按 env 下调，<=0 关闭单项。

| 入口 | action / key | 默认 | 维度 | 说明 |
|---|---|---|---|---|
| orchestrator `/internal/triggers·launch` | `LAUNCH` | 3000/min | 租户 | launch 消费本就单线程是瓶颈，正常远到不了 |
| orchestrator `/internal/workers/register` | `WORKER_REGISTER` | 300/min | 租户 | worker 注册低频，5/s 已是异常风暴 |
| orchestrator dispatch release | `DISPATCH_RELEASE` | 3000/min | 租户 | — |
| orchestrator `/internal/tasks/*/claim`·`claim-batch` | `TASK_CLAIM` | 12000/min | 租户 | 热路径，**按绑定 api_key 的租户聚合**（workerId 可伪造故不按 worker）；批量按 HTTP 调用计 1 |
| orchestrator `/internal/tasks/*/report`·`report-batch` | `TASK_REPORT` | 12000/min | 租户 | 同上 |
| console-api 导出/导入/Excel/报表 | `expensive:user:*` | 10/min | 用户 | 前缀可配 `expensive-op-path-prefixes`；任意 HTTP 方法（导出常为 GET）；fail-open |
| console-api 文件操作（`/api/console/files/` 下载/错误导出/归档/重派/到达组） | `fileop:user:*` | 60/min | 用户 | 防 token 直连脚本盗刷下载/导出；前缀可配 `file-op-path-prefixes`；任意 HTTP 方法；未认证 presign 下载（`fs-download`）自然跳过；fail-open |

- **总开关**：orchestrator `BATCH_RATE_LIMIT_ENABLED`（默认 true）、console `batch.console.security.rate-limit.enabled`（默认 true）。
- **超额响应**：HTTP 429；orchestrator 走 `ResponseStatusException`，console 走标准 `CommonResponse`（`ResultCode.RATE_LIMITED`）。
- **Redis 故障**：console 限流 fail-open（放行 + WARN，见 §1.1）；orchestrator 固定窗口计数同理不阻断业务。
- **时钟回拨保护**：orchestrator `TokenBucketRateLimiter` 检测 ≥100ms 回拨即拒当次（防 stale 窗口叠加击穿）。

### 1.4 请求签名防重放（方案 A，opt-in，2026-06-24）

防接口盗刷第二层：对**自托管 SDK / 脚本类客户端**（api_key 鉴权）的写请求强制签名，挡住"裸 curl 重放/篡改"。

- **方案 A**：以 api_key 本身为 HMAC 密钥（零 schema 改动）。请求头里已有 `X-Batch-Api-Key`，服务端取来重算 HMAC 验证。
- **契约**（服务端 `RequestSignatures` 与各 SDK 唯一权威源，逐字节一致）：
  - `canonical = UPPER(method) "\n" path "\n" timestamp "\n" nonce "\n" hex(sha256(body))`
  - `signature = hex(hmacSha256(apiKey, canonical))`
  - 头：`X-Batch-Timestamp`（epoch millis）、`X-Batch-Nonce`、`X-Batch-Signature`
- **校验顺序**：缺头 → 时钟偏移（`clock-skew-seconds` 默认 300）→ 签名 → nonce 一次性（Redis SETNX，TTL=2×窗口）。签名先于 nonce，避免错签污染 nonce 空间。
- **作用范围**：仅 api_key 鉴权 + 写方法（POST/PUT/PATCH/DELETE）；内部 `X-Internal-Secret`（可信网络）与读请求不强制。
- **边界**：方案 A 不防 api_key 被盗后冒充（盗 key 也能签）；那由 TLS + key 轮换 + 限流覆盖。本机制职责是**防重放 + 防篡改**。
- **灰度**：先把租户 SDK 升到带签名版本并设 `BATCH_SDK_REQUEST_SIGNING_ENABLED=true`，确认全部带签名后再开服务端 `BATCH_REQUEST_SIGNING_ENABLED=true`；否则存量 worker 写请求会被 401。

### 1.5 Fail-open 速查（代码核实，2026-04-26）

| 开关 | Fail-open 强度 | 故障场景 | 行为 | 副作用 |
|---|---|---|---|---|
| `read-replica.enabled` | 🟡 中 | 从库 SQLException | 失败 3 次 → quarantine 30s → 静默走主库；期满自动探活 | 主库压力上升；read-after-write 一致性同步生效 |
| `worker-cache.enabled` | 🟢 强 | Redis 异常 / 反序列化失败 | `catch Exception` → 直通 DB loader + WARN | 派发延迟略增（DB query），业务不受影响 |
| `mq.routing.mode` | — | 无故障场景 | — | 切换需走灰度 SOP（[`mq-topic-routing-rollout.md`](./mq-topic-routing-rollout.md)） |
| `quota.runtime-store=redis` | 🟡 中 | Redis `DataAccessException` | `catch` → `ResourceCheck.allow()`（**放行**）+ WARN | **限流功能等同关闭**：长期 Redis 故障会让大租户吃掉小租户配额 |
| `quota.snapshot.enabled` | 🟢 强（局部） | 单租户 snapshot 失败 | per-tenant `catch` → 跳过该租户继续下一个 | 该租户审计数据漏一个周期，下次自然恢复 |
| `report-outbox.enabled` | — | Outbox 写入失败（磁盘/PG） | `enqueue` 失败 → `report` 继续抛异常（与未开 outbox 相同） | **`PLATFORM_PG`**：依赖 orchestrator 已迁移 V96；**`SQLITE`**：须持久卷 |

> "强 fail-open"=故障时业务完全不受影响；"中 fail-open"=故障时业务继续但行为/语义变化，需运维监控。

---

## 2. 默认开关状态 + 开启建议（按部署形态）

> **原则**：仓库默认值是当前推荐基线，但并非所有开关都能无状态热切换，也并非全部 fail-open。涉及消息路由、数据库分片或持久状态后端的开关，必须按对应 SOP 迁移并重启；普通部署优先保持默认值，下表只列需要显式覆盖的场景。

| 部署形态 | 业务量级 | 推荐覆盖（在 .env 显式设） | 理由 |
|---|---|---|---|
| **本地 IDE 直跑** | 极小 | 无（全默认） | 未起 replica 时 read-replica fail-open 仅前几次 WARN 后静默；嫌噪音可设 `BATCH_CONSOLE_READ_REPLICA_ENABLED=false` |
| **本地 docker-compose** | < 1 万/天 | 无（全默认） | compose 默认起 PG / Redis / Kafka；可 `--profile replica` 起从库让 read-replica 真路由 |
| **单机生产** | < 100 万/天 | `BATCH_CONSOLE_READ_REPLICA_ENABLED=false` | 单机 PG 不起 replica 时应显式关闭读副本路由，避免启动后反复探测从库 |
| **中等生产** | 100 万 ~ 1000 万/天 | 无（全默认） | 主要公共开关默认值即为本量级目标配置 |
| **海量** | > 1000 万/天 | `BATCH_MQ_ROUTING_MODE=PRIORITY` | TENANT topic 数随租户线性膨胀；切 PRIORITY 收敛到 HIGH/NORMAL/LOW 三 topic（详见 §3.3 切换灰度） |
| **测试 / E2E** | — | `application-test.yml` 已覆盖 `read-replica=false` + `worker-cache=false` + `file-governance.*=false` + 后台调度全关 | 测试不起 replica；关后台 scheduler 防 timing flake |

**何时关某个开关**：

| 想关 | 设置 | 何时这么干 |
|---|---|---|
| read-replica | `BATCH_CONSOLE_READ_REPLICA_ENABLED=false` | 没起 PG 从库 / 想避免 WARN 日志 |
| worker-cache | `BATCH_SCHEDULER_WORKER_CACHE_ENABLED=false` | Redis 抖动期间想完全直通 DB / 调试派发延迟问题 |
| quota Redis | `BATCH_QUOTA_RUNTIME_STORE=database` | Redis 长期故障 / 想看 PG 行锁瓶颈复现 |
| quota snapshot | `BATCH_QUOTA_SNAPSHOT_ENABLED=false` | 不需要审计 quota 历史时减 PG 写压力 |
| mq routing | `BATCH_MQ_ROUTING_MODE=SINGLE` | 单租户场景 / 回退老 worker 兼容期 |

---

## 3. 逐项详述

### 3.1 `batch.console.read-replica.enabled`

**作用**：console-api 的 `@Transactional(readOnly = true)` 查询路由到从库 Hikari 连接池；写事务和无事务调用走主库。

**默认**：
- application.yml fallback：`true`
- docker/compose/app.yml：`true`（`${BATCH_CONSOLE_READ_REPLICA_ENABLED:-true}`）
- `.env.example`：`true`
- 测试 `application-test.yml`：`false`（测试容器不起 replica）

**配套依赖**：
- 必须启动 PG 从库（`docker compose --profile replica up -d postgres-replica`）
- 必须配 `BATCH_CONSOLE_PRIMARY_URL` / `BATCH_CONSOLE_REPLICA_URL` 等 6 项 DB 凭证

**风险**：
- 从库异常退出 → 🟡 **中 fail-open**：`ReadReplicaRoutingDataSource` 在从库 SQLException 时降级走主库；连续失败 ≥ `failureThreshold`（默认 3）后进入 `quarantineSeconds`（默认 30s）隔离期，期内静默走主库；期满下次请求自动探测，成功即解除。**副作用：主库压力上升**，长期 replica 故障要扩容主库
- 主从延迟 → "提交后立即读"场景会读到旧数据；用 `@RouteToPrimary` 注解强制走主库（`RouteToPrimaryAspect` 已就位）
- 多从库扩展 → 当前 routing map 硬编码 PRIMARY/REPLICA，多从库需改 `determineCurrentLookupKey` 加轮询

**可调参数**：
- `batch.console.read-replica.failure-threshold`（默认 3）：进入 quarantine 的连续失败阈值
- `batch.console.read-replica.quarantine-seconds`（默认 30）：quarantine 持续时间
- `batch.console.read-replica.{primary,replica}.{connection-timeout-millis,validation-timeout-millis,idle-timeout-millis,max-lifetime-millis,leak-detection-threshold-millis}`：完整 Hikari 调参

**指标**（micrometer，可在 Grafana 看板观察）：
- `batch.console.replica.failover.count`：每次降级 +1
- `batch.console.replica.connection.failure`：每次从库 SQLException +1（按 SQLState 打 tag）

**验证**：见 `docs/runbook/read-replica.md` §四（停从库 → 调 GET /api/console/queries 不再 500，自动 fail-open 到主库；指标 `batch.console.replica.failover.count` 同步上升）。

**回滚**：`BATCH_CONSOLE_READ_REPLICA_ENABLED=false` → 重启 console-api → 走 Spring Boot 默认主 DataSource。

---

### 3.2 `batch.scheduler.worker-cache.enabled`

**作用**：`DefaultWorkerSelector.findCandidates` 按 `(tenantId, workerGroup)` 缓存 ONLINE worker 列表，TTL 5s；高频派发不再每次查 DB `worker_registry`。

**默认**：`true`（yml fallback、docker、env 三层一致）。

**配套依赖**：Redis 已就位（项目本来就依赖）。

**风险**：🟢 低
- Redis 故障 → 🟢 **强 fail-open**：`WorkerRegistryCache` `catch Exception`（涵盖 Redis 异常 + JSON 反序列化失败）→ 直通 DB loader + WARN，业务完全不受影响
- TTL 内 worker offline → 最多 5s 内可能选到已下线 worker，`DefaultWorkerSelector` 后续 dispatch 会被 worker 拒绝，下次 tick 重试

**验证**：
```bash
# Redis 命中观察
docker exec batch-redis redis-cli --scan --pattern "batch:worker-registry:*" | head
# 应看到 batch:worker-registry:{tenant}:{group} 形式的 key
```

**回滚**：`BATCH_SCHEDULER_WORKER_CACHE_ENABLED=false` → 重启 orchestrator → 直通 DB（行为同历史）。

**TTL 调优**：`batch.scheduler.worker-cache.ttl-millis`（默认 5000）；高峰期 worker 上下线频繁可调到 2000-3000；稳定期可放大到 10000 减压。

---

### 3.3 `batch.mq.routing.mode`

**作用**：派发 Kafka topic 后缀策略。

| 模式 | 行为 | 适用 |
|---|---|---|
| `SINGLE` | 所有租户共用 base topic（如 `batch.task.dispatch.import`） | 单租户 / 历史行为 |
| `TENANT`（**默认**） | base topic 后追加 `.<tenantId>` | 多租户隔离，避免大租户挤占 |
| `PRIORITY` | base topic 后追加 `.<priorityBand>`（HIGH/NORMAL/LOW） | 高优独立 consumer group |

**默认**：`TENANT`。

**配套依赖**（**关键，启用 TENANT/PRIORITY 前必读**）：
- Kafka topic **必须预创建**或开启 broker auto-create（生产强烈不建议依赖 auto-create）
- worker 端 `topicPattern` 必须订阅 `batch.task.dispatch.import.*` 这种通配符模式才能收到分流后的 topic
- 切换 mode 不能在线滚动 → 老消费者订阅 base topic，新生产者写到 `base.{tenant}`，老 worker 收不到 → 任务积压
- 正确切换姿势：先全量升级 worker（同时订阅 base 和 base.*）→ 再切 producer mode → 等老 base topic 消费完 → 老 worker 下线

**风险**：🟡 中（**无 fail-open**——这是配置开关，不是故障降级开关）
- 切换不当 → 任务静默积压（producer 在新 topic、consumer 在老 topic）；走灰度 SOP [`mq-topic-routing-rollout.md`](./mq-topic-routing-rollout.md)
- topic 数膨胀 → 多租户场景一千个租户 = 一千个 topic，broker 分区元数据膨胀，需提前评估 broker 容量
- BatchTopicResolver 仅在 `workerType` 字段无效时返回 null（业务数据异常，**不是基础设施故障**），由调用方走 fallback 路径

**验证**：
```bash
# 观察实际写入的 topic
docker exec batch-kafka kafka-topics --bootstrap-server localhost:9092 --list | grep batch.task.dispatch
# TENANT 模式应看到 batch.task.dispatch.import.tenant-a, batch.task.dispatch.import.tenant-b ...
```

**回滚**：`BATCH_MQ_ROUTING_MODE=SINGLE` → 重启 producer/consumer 全部实例 → 回到单 topic。回滚也要先升 consumer 再切 producer。

---

### 3.4 ~~`batch.trigger.quartz-datasource.enabled`~~（已移除，2026-04-25）

**移除原因**：Phase 2 半成品 — 代码层 wire 了独立 DataSource，但配套基础设施（独立 PG 容器 / QRTZ_* 建表 SQL / 数据迁移）从未交付，且即使补完也只能解 Quartz 共库 5% 的问题（WAL 隔离），不能解 95% 的协调瓶颈（QRTZ_LOCKS 行锁、polling 模型、单一全副本拓扑）。

**Quartz 当前部署形态**：JobStore 表（11 张 `QRTZ_*`）落在 `batch_platform.quartz` schema，与业务表共享主 PG 实例。当前业务量级（< 100 万 fire/天）下完全够用。

调度器不再作为功能开关暴露。`batch-trigger` 统一使用 Quartz JDBC JobStore；变更调度实现必须通过新的 ADR 和数据迁移方案实施。

---

### 3.5 `batch.quota.runtime-store`

**作用**：tenant quota 限流的运行时状态后端。

| 值 | 实现 | 适用 |
|---|---|---|
| `redis`（**默认**） | `RedisQuotaRuntimeStateService` Lua 原子脚本，单条 Lua 完成"窗口判定 + peakBorrowed CAS + TTL 续命" | 海量并发，去除 PG 行锁瓶颈 |
| `database` | `DatabaseQuotaRuntimeStateService` PG `@Version` 乐观锁 | 故障降级 / 短期回退 |

**默认**：`redis`。

**配套依赖**：
- `redis` 模式：Redis 必须就位；`QuotaRuntimeStateSnapshotScheduler` 按 `batch.quota.snapshot.interval-millis`（默认 5 分钟）把 Redis 状态 upsert 回 PG `quota_runtime_state` 保留审计能力
- `database` 模式：`QuotaRuntimeResetScheduler` 启用，按时间窗口重置 PG 行；Redis 模式下该 scheduler 不启动（`@ConditionalOnProperty(havingValue=database)`）

**风险**：🟡 中（状态后端切换与故障策略会改变配额语义）
- Redis 抛 `DataAccessException` 时由 `batch.quota.redis.failure-mode` 决定：默认 **FAIL_CLOSED** 返回 `QUOTA_BACKEND_UNAVAILABLE` 并让请求等待/重试，避免 Redis 故障绕过租户配额；仅显式设为 **FAIL_OPEN** 时才放行。FAIL_OPEN 只适用于隔离的本地兼容场景，生产禁止使用。
- Redis → database 前必须先完成一次 Redis → PG snapshot 并核对时间戳；database → Redis 前必须显式准备 Redis 初始状态或接受新窗口重置。仅改环境变量且没有新的 `BATCH_QUOTA_BACKEND_CUTOVER_ID` 时，启动守护会拒绝切换

**故障策略配置**：

```yaml
batch:
  quota:
    redis:
      failure-mode: FAIL_CLOSED
```

对应环境变量为 `BATCH_QUOTA_REDIS_FAILURE_MODE`。该项不是后端切换，不需要 `BATCH_QUOTA_BACKEND_CUTOVER_ID`；变更后需重启 orchestrator，并观察 quota backend unavailable 告警和等待队列是否恢复。

**验证**：
```bash
# Redis 模式下应看到 quota Lua 操作的 key
docker exec batch-redis redis-cli --scan --pattern "batch:quota:*" | head
```

**回滚**：先暂停写入并确认 snapshot 已落 PG，设置 `BATCH_QUOTA_RUNTIME_STORE=database` 和新的唯一 `BATCH_QUOTA_BACKEND_CUTOVER_ID`，再重启 orchestrator。切换成功后清除 ID；Redis 中残留 key 按 TTL 过期。

---

### 3.7 ~~`batch.trigger.async-launch.enabled`~~（ADR-010，已移除）

> **2026-05-02 已删除**：trigger → orchestrator 异步链路（outbox + Kafka）已固化为唯一路径，开关和同步 HTTP 桥（`HttpOrchestratorTriggerAdapter`）同步删除。无需配置此参数。
>
> 链路详情见 `docs/architecture/system-flow-overview.md §1.4`。运维观察指标（outbox GIVE_UP 告警等）仍有效，见 `docker/observability/prometheus-batch-rules.yml`。

---

### 3.6 `batch.quota.snapshot.enabled` / `interval-millis`

**作用**：Redis quota 状态 → PG `quota_runtime_state` 周期 snapshot；为审计 / 故障降级到 database 模式时提供数据起点。

**默认**：`enabled=true`，`interval-millis=300000`（5 分钟）。

**仅 `runtime-store=redis` 时生效**。`database` 模式 PG 本身就是权威源，snapshot 自动跳过。

**配套依赖**：无新增。

**风险**：🟢 低
- snapshot 失败 → 🟢 **强 fail-open（局部）**：per-tenant `catch DataAccessException`，单个租户失败仅跳过该租户日志 WARN，其他租户继续；该租户漏一周期，下次自然恢复；**不阻塞限流业务**
- 频率太高 → PG 写压力（每 5 min 全量 upsert 所有 owner）；万级 owner 时建议放大到 600000+

**验证**：
```sql
-- snapshot 命中后行的 updated_at 周期性更新
SELECT owner_type, owner_id, peak_borrowed, updated_at
  FROM batch.quota_runtime_state
  ORDER BY updated_at DESC LIMIT 10;
```

**回滚**：`BATCH_QUOTA_SNAPSHOT_ENABLED=false` → 重启 orchestrator → 不再 snapshot；Redis 仍是限流权威源，但故障切回 database 模式时 PG 数据停在最后一次 snapshot 时刻。

---

### 3.8 `batch.datasource.business.routing.*`（biz 租户分片路由 / Tiered）

应用层把租户路由到不同 biz PG 实例(自研,非 Citus)。**默认 `enabled=false` = 单片无损**(全租户落 shard-0=现库,零行为变更)。开启后:

- `placement-source=CONFIG`(默认):hash 池化 + `silo-overrides`;`=TABLE`:读 `batch.business_tenant_placement`(在线维护,console `/api/console/ops/tenant-placements`,表命中优先 hash 回退)。
- `shards[*]`(key+url+账密)凭据走 secrets,**不入表**;`shard-max-pool-size` 控每片池;`placement-cache-ttl-ms` 默认 5s(**0=每次查库仅测试用**)。
- `enabled=true` 但 `shards[*]` 为空时启动直接失败，禁止静默退回单库造成“配置看似启用”的假象。
- 仅 import/export/process 三 worker 持有 biz 数据源;dispatch/atomic/SDK 不涉及。
- **Fail-open**:placement 表读失败时已有缓存保留 stale(silo 路由仍对),冷启动退 hash;未知 placement key **硬失败**(关 lenientFallback,防静默落 shard-0 污染)。

完整设计 / 开片 / 账户 / 凭据注入见 [`biz-tenant-routing.md`](./biz-tenant-routing.md)。验证:`scripts/local/sim-harness.sh verify-data`(两片真实 PG 活体) + 单测 `*PlacementResolver*Test` / `BusinessRoutingDataSource*Test`。

### 3.9 `batch.security.bypass-mode`（认证/CSRF 旁路 — 仅本地/联调/E2E）

总旁路开关(认证/加解密/审批),**prod profile 强制拒绝**。本地 yml 默认 `true`,但 `.env.local` 的 `BATCH_SECURITY_BYPASS_MODE` 会**覆盖** yml。

> ⚠️ **sim/本地遇到问题(2026-06-14)**:`BATCH_SECURITY_BYPASS_MODE=false` 时 CSRF(double-submit cookie)对**所有写请求**生效;sim 的 curl 脚本不带 `X-XSRF-TOKEN` → **全部 403「访问被拒绝」**(租户导入/迁片/上传全挂)。`bypass-mode=true` 时 `BYPASS_MODE_CSRF_IGNORED_MATCHERS={"/**"}` 放行。**跑 sim 必须 `BATCH_SECURITY_BYPASS_MODE=true`**(已纳入 `sim-harness.sh preflight` 检查项)。FE/真实部署走正常 CSRF(axios 回传 XSRF-TOKEN),不受影响。

## 4. 翻开关的统一流程

无论开哪个开关，按以下五步：

1. **预检**：基础设施就位（PG 从库 / Redis / Kafka topic / Quartz 库）；环境变量在部署平台 / `.env.local` 配齐
2. **滚动重启**：按 `console-api → orchestrator → trigger → workers` 顺序，**不要并发重启所有模块**
3. **启动日志验证**：搜启动 INFO 行，例如 `console read-replica enabled: primary=...,replica=...`、`worker registry cache enabled, ttl=5000ms`
4. **行为验证**：本文档每节给的 verify 命令（curl / redis-cli / psql / kafka-topics）
5. **监控观察**：1 小时内观察 Grafana 三块板（P99 latency / outbox 积压 / DL 量）+ 业务核心指标，无回归再认为完成

回滚同样按反序：`workers → trigger → orchestrator → console-api`。

---

## 5. 与文档同步的更新清单

修改任何开关默认值或新增开关时，必须同步：

| 文件 | 改什么 |
|---|---|
| 对应模块 `application.yml` | fallback 值 + 注释 |
| 对应 `@ConfigurationProperties` 类 | Java 字段默认 + javadoc |
| `docker/compose/app.yml` | 公共开关必须显式透传并提供与应用一致的 `:-xxx` 默认 |
| `.env.example` | 列出该开关 + 默认值 + 一行作用说明 |
| 本文档（`feature-switches.md`） | §1 索引表 + §3 详述节 |
| `docs/architecture/rework-classification.md` | Phase 2 表格的"开关"列 |
| `docs/changelog.md` | **仅当**改的是 CLAUDE.md 已有规范条款时记一条 |

---

## 6. 已知待办（基于本次梳理 — 2026-04-25 处理结果）

| # | 待办 | 状态 |
|---|---|---|
| 1 | `docker/compose/app.yml` 给 `quartz-datasource` 加显式 `:-false` 回退 | ✅ 完成 → 后于 2026-04-25 进一步**整体移除**该开关（Phase 2 半成品清理），新方案见 `docs/architecture/quartz-replacement-evaluation.md` |
| 2 | `rework-classification.md` 第 81 行更新为实际默认表 | ✅ 完成（替换为 5 项开关默认值表 + 引用 `feature-switches.md`） |
| 3 | `read-replica` 应用层 fail-open | ✅ **本次梳理前已落地**（`ReadReplicaRoutingDataSource` C-3.1：失败计数 + quarantine + micrometer 指标 + `@RouteToPrimary` 注解）；本文档 §3.1 已校准 |
| 4 | `mq.routing` 切换灰度发布 runbook | ✅ 完成（新增 `docs/runbook/mq-topic-routing-rollout.md`） |
