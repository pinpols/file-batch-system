# 深度分析报告

## 1. 范围与方法

- **分析对象**：`file-batch-system` 全仓库，重点覆盖 `batch-trigger` / `batch-orchestrator` / `batch-worker-*` / `batch-console-api` / 数据库迁移脚本与架构文档
- **分析方式**：基于当前代码树的静态阅读、模块边界梳理、关键链路追踪、设计一致性审查
- **不覆盖**：动态压测、渗透测试、全量回归 — 因此对运行时容量、极端并发、真实生产流量下的表现仅做基于代码的判断
- **结论分类**：
  - §5 **当前仍然成立的问题**（含 🟢 / 🟡 已部分闭环的状态标注）
  - §7 **当前已修掉、不要再误报的问题**（避免历史结论被新读者重复引用）

## 2. 执行摘要

这个项目不是“玩具型批处理 Demo”，而是一套边界清晰、模块分工明确、设计意图比较成熟的批处理平台。主链路采用 `DB -> Outbox -> Kafka -> CLAIM -> EXECUTE -> REPORT`，运行态事实源统一收敛到 `batch-orchestrator`，这条主线是对的，很多地方也已经体现出比较强的工程意识，比如：

- `launch` 的 T1/T2 两段式事务拆分
- `outbox` 事务内落盘
- `worker-core` 抽象出统一消费骨架和执行模板
- Testcontainers 驱动的集成/E2E 测试基础设施
- 架构文档、运行链路文档、测试文档覆盖度较高

但“设计成熟”不等于“系统已经收敛”。当前代码里仍然有几类明显问题：

1. `batch-trigger` 作为调度入口和运维入口，暴露了大量写操作接口，但模块内没有 Spring Security 保护。这不是风格问题，而是系统边界问题。
2. Console 的鉴权仍保留了旧式 `X-Console-Token` 共享密钥直通路径，而且默认配置、默认权限和默认账号仍然偏“开发便利优先”，与“生产就绪”的表述不一致。
3. 幂等设计不统一。Trigger、Console、数据库迁移三者对“幂等”的责任边界并不完全一致，导致接口契约和实际行为有偏差。
4. `batch-trigger` 到 `batch-orchestrator` 的转发仍是同步 HTTP 桥接，不是异步解耦，这使调度器的鲁棒性弱于主链路本身。
5. Console 承担了过多职责，`DefaultConsoleJobApplicationService` 已经呈现出明显的 God Service 倾向。
6. Webhook、审批补跑等“外围能力”里仍然存在 durability、事务边界和失败语义不够硬的问题。

结论很明确：这个项目的“核心主链路”基础是好的，但外围入口、控制平面和运维平面的硬化程度落后于主链路本身。下一阶段最该做的，不是继续堆功能，而是收边界、收安全、收幂等、收失败语义。

## 3. 项目全景

### 3.1 模块划分

根 `pom.xml` 当前声明了 9 个 Maven 模块：

- `batch-common`
- `batch-trigger`
- `batch-orchestrator`
- `batch-worker-core`
- `batch-worker-import`
- `batch-worker-export`
- `batch-worker-dispatch`
- `batch-console-api`
- `batch-e2e-tests`

技术栈选择相对激进：

- Java `25`
- Spring Boot `4.0.3`
- MyBatis `4.0.0`
- Spring AI `2.0.0-M3`
- Testcontainers `1.21.4`

这意味着项目在“语言和框架前沿性”上走得很靠前，但也意味着兼容性、生态稳定性、测试成本和升级风险会更高。

### 3.2 主运行链路

从 `README.md` 和架构文档到代码实现，主链路是一致的：

1. Trigger 或 Console 发起启动请求
2. Orchestrator 负责落地运行态
3. Outbox 写入并异步投递到 Kafka
4. Worker 消费任务
5. Worker 回报执行结果
6. Orchestrator 推进任务、分片、实例、工作流状态

核心设计目标是“编排事实源唯一、执行链路异步化、worker 侧尽量无状态”。

### 3.3 架构文档成熟度

项目文档覆盖度较高，尤其是以下文档：

- `README.md`
- `docs/architecture/architecture-truth.md`
- `docs/architecture/runtime-module-communication.md`
- `docs/architecture/core-model.md`

这是优点。但文档表述中存在一个治理问题：`docs/architecture/architecture-truth.md` 已经写到“主要技术债已清零”“反模式全部清零”，而当前代码里仍然存在明显的控制平面安全缺口和幂等缺口。也就是说，文档成熟度高，但“文档结论的保守性”不够。

## 4. 核心设计优点

### 4.1 Orchestrator 作为唯一运行态事实源

这是当前项目最正确的一条架构决策。

在 `batch-orchestrator` 里，`DefaultLaunchService` 明确把 `launch` 拆成两段：

- T1：创建 `job_instance` / `workflow_run`
- T2：创建 partition/task/outbox 并推进状态

这避免了长事务同时持有高竞争表锁，是标准的“事实先落地，后续异步推进”的思路。这里不是表面上的分层，而是有明确并发考虑的设计。

### 4.2 Outbox 设计方向正确

`TaskDispatchOutboxService` 明确要求在事务内写 `outbox_event`，这保证了“运行态变更”和“消息待发布”至少在数据库层是一致的。对一个批处理平台来说，这是比“直接发 Kafka”高一个层级的设计。

### 4.3 Worker 抽象层做得比较好

`batch-worker-core` 把大量共性收到了抽象层：

- `AbstractWorkerLoop`
- `AbstractTaskConsumer`
- `AbstractPipelineStepExecutionAdapter`
- `AbstractStageExecutor`

这类抽象不是空架子，而是把 worker 注册、心跳、消费、背压、MDC 注入、DLQ、执行模板统一了。这样 import/export/dispatch 三条链路虽然业务不同，但运行骨架一致，后续治理能力才有机会真正统一。

### 4.4 背压意识是存在的

`AbstractTaskConsumer` 不是简单地“收到消息就处理”，而是用信号量控制并发，并在 permit 耗尽时 pause listener container，释放后再 resume。这说明系统不是只考虑“能跑”，而是考虑了 worker 进程内的稳定性。

### 4.5 测试基础设施是强项

`IntegrationTestInfrastructure` 把 PostgreSQL、Kafka、MinIO、Redis 的动态属性注册统一封装起来，E2E 配置也把 orchestrator/trigger/worker/console 对齐到了同一个测试进程。这个基础设施质量不低，说明项目具备“可验证”的工程基础，而不是只靠手工联调。

## 5. 当前仍然成立的问题清单

下面只列当前代码树里仍然成立的问题，不重复已经修掉的历史问题。

### 5.1 P0: Trigger 模块缺少真正的入口保护

> 🟢 **[已修,2026-04-30 校正]** `cd389a0b`(2026-04-22 v4 闭环)加 `batch-trigger/.../config/TriggerSecurityConfiguration.java:42-46` 真起 `SecurityFilterChain` 把 `/actuator/**` 之外的请求强制 `authenticated()`。本节"问题"描述适用于修复前。后续动作见 [`project-assessment-2026-04-29.md`](./project-assessment-2026-04-29.md) §8 S5-c(补 SecurityIntegrationTest 守护)。

#### 问题

`batch-trigger` 暴露了多组写接口和运维接口：

- `/api/triggers/launch`
- `/api/triggers/catch-up/approve`
- `/api/triggers/management/register`
- `/api/triggers/management/unregister`
- `/api/triggers/management/pause`
- `/api/triggers/management/resume`
- `/api/triggers/management/pause-all`
- `/api/triggers/management/resume-all`
- `/api/triggers/management/drain/**`

但 `batch-trigger/pom.xml` 当前没有引入 Spring Security 相关依赖，控制器本身也没有任何接口级鉴权保护。

#### 证据

- `batch-trigger/src/main/java/com/example/batch/trigger/web/TriggerController.java`
- `batch-trigger/src/main/java/com/example/batch/trigger/web/TriggerManagementController.java`
- `batch-trigger/pom.xml`

#### 影响

- 任何能访问 Trigger HTTP 端口的人都可以发起作业、审批补跑、暂停或恢复调度、切换 drain 状态。
- 这会把 Trigger 变成“可被远程直接操纵的调度面板”，属于控制平面暴露问题。

#### 判断

这是当前最严重的设计缺口之一。即使生产依赖内网隔离，也不应该把安全边界外包给网络拓扑。

### 5.2 P0: Console 仍保留共享密钥直通后台的旧鉴权路径

> 🟡 **[部分修,2026-04-30 校正]** `legacyHeaderAuthEnabled` 在 `application.yml:67` 用 env `BATCH_CONSOLE_LEGACY_HEADER_AUTH_ENABLED:false` 覆盖默认关闭,注释明确"5.2 默认关闭旧式 X-Console-Token 鉴权";实际部署不再走 X-Console-Token compat,仅 opt-in 兼容。**真删动作排期** —— 见 [`project-assessment-2026-04-29.md`](./project-assessment-2026-04-29.md) §8 S5-d(从 `ConsoleSecurityProperties` / yaml / OpenAPI 物理删除 legacy header 分支)。

#### 问题

`ConsoleAuthenticationFilter` 现在有两套入口：

1. JWT
2. `X-Console-Token` 共享密钥

只要请求头携带 `X-Console-Token` 且值匹配 `shared-secret`，就直接给请求注入服务端默认权限：

- `ROLE_ADMIN`
- `ROLE_AUDITOR`
- `ROLE_CONFIG_ADMIN`

而默认配置里：

- `shared-secret` 默认值是 `console-secret`
- `legacy-header-auth-enabled` 默认值是 `true`

#### 证据

- `batch-console-api/src/main/java/com/example/batch/console/support/ConsoleAuthenticationFilter.java`
- `batch-console-api/src/main/resources/application.yml`
- `batch-console-api/src/main/java/com/example/batch/console/config/ConsoleSecurityProperties.java`

#### 影响

- 这不是“兼容路径”那么简单，而是“仍保留的后台旁路入口”。
- 如果部署时没有显式关闭 legacy mode 或替换共享密钥，风险非常直接。

#### 判断

如果系统已经有 JWT 登录和单会话能力，这条 legacy 路径就不应再作为默认开启能力存在。

### 5.3 P1: 默认凭据和内置账号策略仍然偏开发导向

#### 问题

当前系统仍然存在以下默认值或内置账号：

- `batch.console.security.shared-secret = console-secret`
- `batch.console.security.jwt-secret = console-jwt-secret-change-me`
- 数据库迁移直接种入：
  - `admin`
  - `auditor`
  - `config-admin`
  - `tenant-user`
- 迁移注释里明确写的是初始密码 `admin123`

#### 证据

- `batch-console-api/src/main/resources/application.yml`
- `batch-console-api/src/main/java/com/example/batch/console/support/ConsoleJwtService.java`
- `db/migration/V52__seed_system_tenant_and_builtin_accounts.sql`
- `db/migration/V42__add_tenant_user_role.sql`

#### 影响

- 生产 profile 下，`ConsoleJwtService` 会校验默认占位 `jwt-secret`，这一点比以前好。
- 但 `shared-secret` 和 legacy header auth 的默认策略依然宽松。
- 同时，内置账号的存在本身会增加交付侧误配置和默认口令未轮换的风险。

#### 判断

这不是单点 bug，而是安全基线问题。当前控制台的默认姿势仍不够“默认安全”。

### 5.4 P1: Console 接受 URL Query Token，扩大 JWT 泄露面

#### 问题

`ConsoleAuthenticationFilter.resolveBearerToken()` 除了读取 `Authorization: Bearer ...`，还接受 `?token=...` 查询参数。

#### 证据

- `batch-console-api/src/main/java/com/example/batch/console/support/ConsoleAuthenticationFilter.java`

#### 影响

JWT 会暴露在：

- 浏览器历史
- 代理访问日志
- 反向代理缓存
- Referer
- APM 或网关日志

#### 判断

这不是“兼容性增强”，而是扩大泄露面。对管理后台来说没有保留必要。

### 5.5 P1: Console 幂等拦截器的设计不完整

> 🟢 **[已修，2026-04-30]** ADR-011 三层幂等边界定稿 + `ConsoleIdempotencyInterceptor` 全文重写：key 改绑 `(tenant+method+uri+idempotencyKey)`，两阶段占问题（PENDING 30s → 2xx 升 DONE 24h / 非 2xx DELETE），Redis fail-closed 503；Layer 2 `DefaultTriggerService.approvePendingCatchUp` idempotencyKey 短路；Layer 3 `uk_job_instance_tenant_dedup` DB UNIQUE 回退 — §5.5/§5.6/§5.10 三处一并闭环。详见 `ADR-011-idempotency-boundary-alignment.md`。

#### 问题

`ConsoleIdempotencyInterceptor` 当前只做了一个很薄的 Redis 占位：

- Key 仅由原始 `Idempotency-Key` 组成
- 没有绑定 URI
- 没有绑定 tenantId
- 没有绑定 username
- 在控制器执行前就占问题
- 没有根据成功/失败决定是否保留

#### 证据

- `batch-console-api/src/main/java/com/example/batch/console/support/ConsoleIdempotencyInterceptor.java`

#### 影响

会出现两个问题：

1. 不同接口、不同租户、不同操作者只要 header 一样，就会互相冲突。
2. 请求如果因为参数错误、业务异常或 5xx 失败，key 仍会被占 24 小时，调用方无法安全重试。

#### 判断

当前实现更像“重复请求粗暴拦截”，不是严格意义上的幂等。它会制造假冲突，并破坏正确重试。

### 5.6 P1: Trigger 的补跑审批接口要求幂等头，但并未真正使用

#### 问题

`/api/triggers/catch-up/approve` 强制要求 `Idempotency-Key` 请求头，但控制器构造 `PendingCatchUpApprovalCommand` 时没有把这个 header 传下去，后续服务层也没有消费它。

#### 证据

- `batch-trigger/src/main/java/com/example/batch/trigger/web/TriggerController.java`

#### 影响

- 对调用方来说，这是虚假契约。
- API 表面上看“有幂等保护”，实际上没有。

#### 判断

这属于很典型的接口契约 bug：字段存在，但语义没落地。

### 5.7 P1: Trigger 到 Orchestrator 的桥接仍然是同步 HTTP，调度鲁棒性弱于主链路

#### 问题

项目主链路强调 `Outbox -> Kafka`，但 Trigger 发起启动请求时，仍是直接同步调用 Orchestrator：

- 先写 `trigger_request`
- 再同步 HTTP 调用 Orchestrator
- 失败则把状态改成 `REJECTED`

#### 证据

- `batch-trigger/src/main/java/com/example/batch/trigger/service/DefaultTriggerService.java`

#### 影响

- 调度系统的稳定性受 Orchestrator 可用性直接牵制。
- Orchestrator 短暂不可用时，Trigger 不会“排队稍后转发”，而是直接拒绝。
- 这使 Trigger 自己不是一个真正稳态的调度接入层。

#### 判断

这和主链路的异步设计理念不一致。Trigger 仍是一个同步桥，而不是可靠接入层。

### 5.8 P1: Trigger 的补跑审批在事务内做外部调用，事务边界不干净

#### 问题

`approvePendingCatchUp()` 本身是 `@Transactional`，但方法内直接调用了 `orchestratorTriggerAdapter.sendTrigger()`。

#### 证据

- `batch-trigger/src/main/java/com/example/batch/trigger/service/DefaultTriggerService.java`

#### 影响

- 数据库事务与外部 HTTP 副作用耦合在一起。
- 如果外部调用成功、但后续状态更新或事务提交失败，系统会出现“外部已触发，内部状态未完成落地”的不一致。

#### 判断

这是典型的“事务内做外部 I/O”问题，应该拆成状态机推进或 outbox 驱动，而不是直接写入同一事务。

### 5.9 P1: Trigger 的错误语义存在“成功返回 null”的分支

#### 问题

`DefaultTriggerService.persistAndForward()` 在捕获 Orchestrator 的 404 时，会：

- 记录 `REJECTED`
- 打 warn 日志
- 直接 `return null`

而上层 `TriggerController.launch()` 和 `approveCatchUp()` 使用的是 `CommonResponse.success(...)`。

#### 证据

- `batch-trigger/src/main/java/com/example/batch/trigger/service/DefaultTriggerService.java`
- `batch-trigger/src/main/java/com/example/batch/trigger/web/TriggerController.java`

#### 影响

- 调用方可能拿到 HTTP 200，但响应体里 `data=null`。
- 这会把“请求被下游拒绝”伪装成“接口成功但无返回值”，错误语义不清。

#### 判断

这是用户可感知的逻辑问题，不是单纯的代码风格问题。

### 5.10 P1: Trigger 去重策略存在明显的设计漂移

#### 问题

当前代码和数据库迁移对“幂等去重应该放在哪里”并不一致：

- `DefaultTriggerService.persistAndForward()` 注释里写的是：彻底消除竞态需要给 `(tenant_id, dedup_key)` 加唯一约束。
- 但 `V37__fix_trigger_request_dedup_constraint.sql` 明确把 `trigger_request` 上的这个约束删掉了，并说明去重应在 `job_instance` 层完成。

#### 证据

- `batch-trigger/src/main/java/com/example/batch/trigger/service/DefaultTriggerService.java`
- `db/migration/V37__fix_trigger_request_dedup_constraint.sql`

#### 影响

- 开发者对真实幂等边界的理解会分裂。
- 一旦后续有人按注释“补回唯一约束”，就可能和既有数据语义、重试语义冲突。

#### 判断

这不是单个 bug，而是设计事实源不一致。它会让后续改动变得危险。

### 5.11 P1: Webhook 是进程内 best-effort，不是可靠交付

> 🟢 **[已修，2026-04-30]** `b74e0a0c` 落地 V81 migration（delivery_status 加 GIVE_UP check + (status, next_retry_at) 部分索引）+ `WebhookDeliveryRelay` 278 行：`@Scheduled` + ShedLock 互斥 + `FOR UPDATE SKIP LOCKED`，周期扫 EXHAUSTED 行重投，指数退避（5m → 10m → 20m → 30m cap），绝对上限 8 次后标 GIVE_UP 并打 Prometheus counter `batch_webhook_delivery_give_up_total`；7 个 `WebhookDeliveryRelayTest` 单测全部通过。

#### 问题

`WebhookDispatcher` 当前实现为：

- 进程内固定线程池 `newFixedThreadPool(4)`
- daemon 线程
- 内存队列
- 最多 3 次重试
- 关闭时等待 5 秒，不行就 `shutdownNow()`
- 当前没有看到为 webhook 单独配置 HTTP timeout

#### 证据

- `batch-console-api/src/main/java/com/example/batch/console/service/WebhookDispatcher.java`
- `batch-common/src/main/java/org/springframework/boot/autoconfigure/web/client/RestClientAutoConfiguration.java`

#### 影响

- 进程重启时，未消费的 webhook 任务会丢失。
- 慢回调会占满有限线程。
- `Executors.newFixedThreadPool` 默认是无界队列，流量尖峰下会形成内存积压风险。
- 业务方如果把 webhook 理解为“可靠通知”，会出现预期偏差。

#### 判断

这套实现适合低频通知，不适合承担强业务语义。

### 5.12 P1: Console Job 运维服务已经明显过胖

> 🟢 **[已修,2026-04-30 校正]** `DefaultConsoleJobApplicationService` 现 **90 行**(纯 delegate 壳),业务逻辑拆到 6 个兄弟类:`ConsoleJobOpsSupport`(407)、`ConsoleJobQueryService`(226)、`DefaultConsoleJobApprovalService`(192)、`DefaultConsoleJobRecoveryService`(230)、`DefaultConsoleJobTriggerService`(133)。ADR-008 god-class-decomposition 事实上已落。本节"问题"描述适用于修复前(843 行版)。

#### 问题

`DefaultConsoleJobApplicationService` 目前已经有 `843` 行，里面混合了：

- 手工触发
- 补偿
- 重跑
- 死信重放
- 任务重放
- 分区重放
- 补跑审批
- Batch Day Catch Up
- 多个外部服务代理调用

#### 证据

- `batch-console-api/src/main/java/com/example/batch/console/infrastructure/DefaultConsoleJobApplicationService.java`

#### 影响

- 职责边界不再清晰。
- 改动一个运维动作容易牵动别的路径。
- 单元测试、代码审查和后续拆分成本都会上升。

#### 判断

这已经是 God Service 信号，不应继续向这个类里堆行为。

### 5.13 P2: 文档表述与真实硬化程度存在落差

#### 问题

架构文档已经写到：

- “生产就绪状态”
- “主要技术债已清零”
- “反模式全部清零”

但当前代码里仍然存在：

- Trigger 入口缺失安全保护
- Console legacy auth 默认开启
- 幂等设计不统一
- Webhook 仍是 best-effort

#### 证据

- `docs/architecture/architecture-truth.md`
- 上述代码证据

#### 影响

- 文档会给团队错误的安全感。
- 后续评审容易以为“剩下的只是小问题”。

#### 判断

文档不应该比系统更乐观。否则文档本身会变成治理风险。

### 5.14 P2: 技术栈过于激进，增加维护和兼容性成本

#### 问题

当前根 POM 选择了：

- Java `25`
- Spring Boot `4.0.3`
- Spring AI `2.0.0-M3`

#### 证据

- `pom.xml`

#### 影响

- 与插件、监控、测试库、字节码工具、mock 工具的兼容风险更高。
- 对 CI、开发机 JDK、容器镜像、AOT 或 agent 兼容性要求更高。

#### 判断

如果团队很强、测试足够硬，这不是问题；但如果团队目标是“稳”，当前版本策略偏激进。

## 6. 业务设计层面的缺陷和隐患

### 6.1 控制平面和执行平面的硬化程度不一致

主链路已经明显偏“平台化设计”，但控制平面仍然保留了很多“开发便利式入口”：

- Trigger 缺少入口鉴权
- Console 有 legacy shared-secret 直通
- 默认账号和默认口令仍在迁移里种入

这会导致一个很不好的结构：执行面越来越正规，控制面却还是半开放的。

### 6.2 “幂等”在不同模块中的语义不统一

当前至少存在三种幂等形态：

- Trigger 的 `dedupKey`
- Orchestrator 的 `job_instance` 唯一键
- Console 的 Redis 占位式拦截

问题不在于有三层，而在于三层的责任边界没有被表达清楚：

- 哪一层是最终语义幂等
- 哪一层只是快速防抖
- 哪一层失败后允许重试

这会让接口调用方和维护者都难以推导“重复请求到底会怎样”。

### 6.3 EVENT / 触发模型边界仍然不够清楚

当前系统里：

- `scheduleType` 已经收敛到 `CRON/FIXED_RATE/MANUAL`
- `triggerMode` 仍有 `EVENT/MIXED`
- Quartz 注册只覆盖 `CRON/FIXED_RATE`

这本身未必是 bug，但容易让使用者误解“事件驱动”到底是：

- 外部通用消息触发
- 文件到达专用触发
- 工作流内部事件

建模边界不清，会引发配置和产品语义的误解。

## 7. 已修掉、不再现行的问题

下列结论曾出现在历史评估中，但在当前代码树里已经不是现行缺陷，避免被新读者重复引用：

### 7.1 Orchestrator 内部接口并非完全裸露

当前已经有：

- `InternalAuthFilter`
- `InternalSecurityConfiguration`

说明 `/internal/**` 不是完全不设防。

### 7.2 Task report 已校验 worker 归属

`DefaultTaskOutcomeService.applyTaskOutcome()` 现在会校验：

- `command.workerId()`
- `task.getAssignedWorkerCode()`

不再是“知道 taskId 就能伪造回报”。

### 7.3 Webhook callbackUrl 不再只是字符串校验

`CallbackUrlValidator` 已经做了：

- 仅允许 HTTPS
- 主机名存在性校验
- DNS 解析后地址校验

所以早期那类“只校验字符串、不做解析”的 SSRF 结论，当前不再准确。

### 7.4 Excel 公式注入问题已经被处理

当前 Excel 导出/预览相关公共层已经加入公式转义逻辑，不能再按旧版本去误报。

## 8. 优先级修复路线

### 8.1 第一阶段：先收 P0

#### 目标

- 先把控制平面补到“默认安全”

#### 建议

1. 给 `batch-trigger` 正式接入鉴权
   - 至少要有内部共享密钥或 JWT 保护
   - `launch`、`catch-up/approve`、`management/**` 必须受保护

2. 下线 Console legacy header auth 的默认开启策略
   - `legacy-header-auth-enabled` 默认改成 `false`
   - 兼容模式只允许显式开启

3. 取消 URL query token
   - 只接受标准 `Authorization` header

### 8.2 第二阶段：收幂等和失败语义

#### 目标

- 让调用方能稳定推导“重复请求、失败请求、重试请求”会发生什么

#### 建议

1. 统一定义幂等语义
   - Trigger：接入层防抖还是最终幂等
   - Orchestrator：最终事实幂等
   - Console：只做接口级去重还是强幂等

2. 重写 `ConsoleIdempotencyInterceptor`
   - Redis key 至少绑定：`tenant + method + uri + idempotencyKey`
   - 只在业务成功提交后固化
   - 失败请求允许安全重试

3. 修复 Trigger 补跑审批接口的伪幂等契约
   - 要么真正使用 `Idempotency-Key`
   - 要么从接口契约里删除

4. 修复 Trigger 的错误返回语义
   - 下游 404/拒绝不应返回 `success(null)`

### 8.3 第三阶段：收事务边界和外围能力

#### 目标

- 清理“事务内外部调用”和“best-effort 被当成可靠能力”的问题

#### 建议

1. 重构 `approvePendingCatchUp`
   - 不要在事务里直接发外部 HTTP
   - 改成状态推进 + outbox/异步触发

2. 升级 webhook 为明确语义
   - 如果它只是通知，就在文档里明确“best-effort”
   - 如果它承担业务责任，就必须持久化待投递记录并支持补偿重放

3. 给 webhook HTTP 客户端补 timeout 和限流配置

### 8.4 第四阶段：收控制台服务边界

#### 目标

- 降低 Console 的 God Service 趋势

#### 建议

按行为而不是按 controller 拆分：

- Triggering Service
- Recovery/Replay Service
- Approval Service
- Batch Day Catch-Up Service
- Realtime Refresh Publisher

这样可以把 `DefaultConsoleJobApplicationService` 拆成更清晰的协作对象。

### 8.5 第五阶段：收文档与事实的一致性

#### 目标

- 文档不要比系统更乐观

#### 建议

1. 改写 `architecture-truth.md` 的成熟度表述
2. 单列“已解决问题”和“仍开放问题”
3. 每一项安全和幂等结论都要绑定代码事实，不要只写结果

## 9. 总结

**核心价值**：项目已形成一条结构正确的批处理平台主干 — Orchestrator 作为事实源、Outbox 解耦、Worker 执行模板化、测试基础设施较完整。

**主要短板**：控制平面的安全硬化落后、幂等语义不统一、Trigger 接入鲁棒性不够强、Console 服务边界开始失控、外围能力的失败语义不够硬。

**优先级**：按 `安全 → 幂等 → 事务边界 → 服务拆分` 顺序收敛，比继续叠加功能的长期可维护性收益更高。这四件事的具体计划见 §8。

---

## 10. 修复记录（2026-04-17）

| 编号 | 修复内容 |
|------|----------|
| 5.1 | Trigger 引入 Spring Security + InternalSecretFilter，所有端点需 X-Internal-Secret |
| 5.2 | Console `legacy-header-auth-enabled` 默认改为 `false` |
| 5.3 | Console shared-secret 加 prod profile 校验，默认值会阻止启动 |
| 5.4 | 移除 URL query token（`?token=`），仅接受 Authorization header |
| 5.5 | 幂等拦截器重写：key 绑定 tenant+method+uri；失败删占位允许重试 |
| 5.6 | Trigger catch-up 接口将 idempotencyKey 传入服务层并做去重 |
| 5.7 | Trigger→Orch 失败不再立即 REJECTED，改为 FORWARD_FAILED + 定时重试调度器 |
| 5.8 | `approvePendingCatchUp` 事务拆分：DB 操作用 TransactionTemplate，HTTP 在事务外 |
| 5.9 | `persistAndForward` 不再 return null，4xx 抛 BizException |
| 5.10 | 去重注释对齐：明确 dedup 在 job_instance 层，非 trigger_request 层 |
| 5.11 | ①(2026-04-17) Webhook 线程池改有界队列(1024)；加 HTTP 超时(connect 5s, read 10s)；Javadoc 明确 best-effort ②(2026-04-30) V81 + `WebhookDeliveryRelay`：持久化重试全栈，GIVE_UP Prometheus 告警，完整闭环 |
| 5.12 | Console Job 服务拆分：843 行 → 91 行 facade + Trigger/Recovery/Approval 三个专项服务 |
| 5.13 | architecture-truth.md 移除”已清零”过于乐观表述，标注控制平面仍有开放项 |
