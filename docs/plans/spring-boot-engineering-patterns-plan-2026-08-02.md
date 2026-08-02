# Spring Boot 工程化样板借鉴计划（2026-08-02）

> 本文回答:Spring Boot 的代码设计和工程化样板里,BFS 后端最值得借什么、落到哪些模块、优先级怎么排、验收标准是什么。
> 结论先行:BFS 不应照搬 Spring Boot 的业务组织方式,而应借鉴它在「自动装配可解释、配置强类型、启动失败诊断、生命周期顺序、Actuator 可观测、测试切片」上的工程化体系。

## 1. 当前判断

BFS 的核心复杂度在批量调度控制面:DB -> Outbox -> Kafka -> CLAIM -> EXECUTE -> REPORT、五类 worker、文件存储、多租隔离、租约与终态 CAS。Spring Boot 不能替代这些领域模型,但它的工程化样板能明显提升「可启动、可诊断、可演进、可测试」水平。

当前项目已经具备一部分基础:

| 能力 | BFS 现状 | 判断 |
|---|---|---|
| AutoConfiguration | `batch-common` 和 `sdk/java/spring` 已有 `AutoConfiguration.imports` | 基础已在,但诊断与条件测试还不够系统 |
| ConfigurationProperties | 各模块大量使用 `@ConfigurationProperties` | 需要继续收敛为配置唯一入口,并补元数据/文档同步 |
| SmartLifecycle | SDK lifecycle、部分 relay 已使用 | 需要形成全局 phase 台账,避免优雅停机顺序漂移 |
| HealthIndicator | RLS、存储、运行时检查已有雏形 | 需要升级为 health group + domain readiness |
| ApplicationContextRunner | Java Spring SDK 已使用 | 应推广到 common/orchestrator/worker 自动装配矩阵 |
| Feature switch 文档 | 已有开关治理方向 | 需要和 Spring Boot 配置元数据/CI 校验打通 |

## 2. 最值得借鉴的 Spring Boot 样板

### 2.1 FailureAnalyzer:把启动失败变成可执行诊断（P0）

Spring Boot 成熟点不是「失败时抛异常」,而是把失败转成结构化的 `Description` 和 `Action`。BFS 目前很多 fail-close 是正确的,但错误信息对上线排障仍偏散。

适用场景:

| 场景 | 当前风险 | 建议 |
|---|---|---|
| 生产密钥缺失 | prod profile 会 fail-close,但 Helm/env/文档错位时定位成本高 | 为内部 API key、console JWT、对象加密 key 增加 FailureAnalyzer |
| 存储后端配置错误 | filesystem/S3/OSS/NAS 路由、bucket、endpoint、size/checksum 契约容易配错 | 启动期输出实际选择的 backend、bucket、endpoint、加密状态、校验策略 |
| Kafka topic/bootstrap 错误 | 控制面启动成功但后续积压或消费失败 | 启动失败或 readiness failed 时给出缺失 topic 和修复动作 |
| RLS/多租隔离检查失败 | 已有 fail-fast,但行动建议可读性还可提升 | FailureAnalyzer 指向具体表、策略、豁免配置和修复脚本 |
| Redis/ShedLock 配置错误 | 优雅停机和锁后端问题容易表现成运行期噪声 | 启动诊断明确 lock provider、redis namespace、ttl 风险 |

验收标准:

- 每个生产 fail-close 异常都能给出「哪里错、为什么错、怎么修、关联文档」。
- `prod` profile 下缺关键密钥、缺关键 topic、存储后端不合法时,日志里不只是一段堆栈。
- 单测覆盖 FailureAnalyzer 输出,避免以后变成无效文案。

### 2.2 AutoConfiguration 纪律:让平台能力像 Starter 一样可装配（P0/P1）

Spring Boot 的可维护性来自清晰的自动装配边界:条件、顺序、默认 bean、用户覆盖点都可解释。BFS 当前已有自动装配,但 common 里平台能力较多,还缺一套统一规则。

建议规则:

| 规则 | BFS 落地 |
|---|---|
| 所有平台级装配放到 `autoconfigure` 包 | common/logging/i18n/storage/lock/observability/rls/security 分层整理 |
| 每个自动装配类必须声明条件 | `@ConditionalOnClass`、`@ConditionalOnProperty`、`@ConditionalOnMissingBean` 明确启停与覆盖 |
| 用户可替换的 bean 必须 `@ConditionalOnMissingBean` | 对象存储、锁、时区、内部认证、观察器、worker sdk client |
| 装配顺序显式表达 | storage -> crypto -> health, scheduler -> lifecycle -> shutdown guard |
| 每个自动装配类至少一组 `ApplicationContextRunner` 测试 | 覆盖 enabled/disabled、缺依赖、用户自定义 bean、prod fail-close |

优先落点:

1. `batch-common`: object store、crypto、ShedLock、observability、timezone、RLS。
2. `sdk/java/spring`: worker SDK auto-config 已有基础,继续补 transport/lifecycle 条件矩阵。
3. `batch-orchestrator`: 控制面内部 client、dispatcher、lease/reconciler 不急于做 starter,先做窄上下文配置测试。

### 2.3 ConfigurationProperties + Metadata:配置必须强类型、可发现、可生成文档（P0/P1）

Spring Boot 的配置成熟度来自 `@ConfigurationProperties`、Bean Validation、configuration metadata。BFS 现在配置键多、开关多、环境多,仅靠 yml/README/Helm 手工同步会继续漂。

建议做法:

- 所有新配置只能进 `@ConfigurationProperties`,避免服务类里直接读散落的 `Environment`。
- 生产必填项用 `@NotBlank`、`@DurationMin`、`@Min`、`@AssertTrue` 等校验表达。
- 增加 `spring-boot-configuration-processor`,生成配置 metadata。
- `feature-switch-registry.yml` 作为开关登记源,生成:
  - `docs/dict/config-keys.md`
  - Helm required values 白名单
  - Compose 示例环境变量
  - CI 配置一致性检查输入

验收标准:

- 新增配置没有 metadata 或没有文档登记时 CI 失败。
- `prod` profile 没有未登记的 `BATCH_*` 必填环境变量。
- yml、Helm、Compose、文档对同一开关的默认值一致。

### 2.4 Actuator 端点与 Health Group:把系统状态对齐运维语言（P1）

Spring Boot Actuator 的价值是把健康、指标、配置、线程、日志级别用统一协议暴露。BFS 目前有一些 health/metrics,但可以更贴合批调度域。

建议新增或整理:

| 端点/分组 | 内容 | 目的 |
|---|---|---|
| `health/liveness` | JVM、主进程、基础 Spring Context | 不因下游短故障被重启 |
| `health/readiness` | PG、Kafka、Redis、对象存储、内部 API key、RLS | 决定是否接流量 |
| `health/startup` | Flyway、topic、bucket、RLS、feature switch 自检 | 上线前 fail-fast |
| `batchStorage` | backend、bucket/root、encryption、checksum、last probe | 文件链路排障 |
| `batchOutbox` | lag、oldest age、relay state、circuit state | 控制面积压排障 |
| `batchWorker` | worker type、lease renew、claim/report、backpressure | 五类 worker 运行态 |
| `batchFeatureSwitches` | 开关 effective value、来源、prod 是否允许 | 配置漂移排查 |

注意:端点不得泄露密钥、租户业务数据、完整文件路径、下游 token。对外只暴露脱敏值、摘要、状态和建议动作。

### 2.5 ApplicationAvailability:接流量状态要由系统主动表达（P1）

Spring Boot 提供 `ApplicationAvailability` 与 readiness/liveness 状态。BFS 目前更依赖组件自己失败或定时任务兜底,可以把关键状态显式发布给平台。

建议触发 readiness refuse 的场景:

- 优雅停机 drain 已开始。
- Kafka producer/consumer 连续失败,已无法保证 outbox 转发。
- 对象存储启动探测失败或 checksum/sidecar 契约不可用。
- Orchestrator 内部认证配置不完整。
- 多租 RLS 闭世界检查失败。
- Worker lease/report 后端不可达且超过短路窗口。

验收标准:

- drain 期间先停止 intake/claim,再等待在途任务,最后关闭 Redis/Kafka/DB。
- readiness 不再只表示 HTTP 端口可用,而是表示「可以安全接批调度流量」。

### 2.6 SmartLifecycle phase 台账:优雅停机必须可验证（P0）

之前出现过 Redis/Lettuce 已 STOPPING,调度线程还在拿 ShedLock 的问题。这类问题不能靠记忆维护,需要像 Spring Boot 自身生命周期那样有全局 phase 规则。

建议新增统一常量:

```java
public final class BatchLifecyclePhases {
  public static final int INTAKE = 1000;
  public static final int SCHEDULER = 500;
  public static final int WORKER_LEASE = 300;
  public static final int RELAY_DRAIN = 100;
  public static final int INFRASTRUCTURE_CLIENT = -100;
}
```

实际数值可按当前代码调整,但必须满足:

- 停机时入口流量最先停。
- 定时调度、轮询、lease renew 早于 Redis/Kafka/DB 停。
- outbox/report drain 有短等待窗口。
- 基础设施 client 最后停。

验收标准:

- 每个 `SmartLifecycle` 都引用 phase 常量,禁止散落魔法数字。
- 增加一个架构测试或单测,校验关键 bean 的 phase 顺序。
- 优雅停机日志里不再出现连接工厂 STOPPING 后业务线程继续使用它的错误。

### 2.7 ConditionEvaluationReport 思路:解释「为什么选了这个实现」（P2）

Spring Boot 的条件报告能解释某个 auto-config 为什么生效/没生效。BFS 的存储、锁、worker adapter、dispatch channel、feature switch 也需要类似能力。

建议提供内部诊断服务:

- 对象存储实际选中 `filesystem/s3/oss/nas` 哪个实现。
- dispatch channel adapter 命中顺序与是否有 stub 抢占风险。
- quota/lock/redis/kafka 后端的 effective source。
- feature switch 的默认值、环境覆盖、tenant 覆盖。

这不一定要直接复用 Spring Boot 的 `ConditionEvaluationReport`,可以做 BFS domain diagnostic,但理念一致:让系统能解释自己的装配结果。

### 2.8 ApplicationStartup / StartupStep:启动链路可观测（P2）

对大系统来说,启动慢、探测慢、迁移慢都需要数据。Spring Boot 的 startup step 可以用于记录关键启动阶段。

建议埋点:

- Flyway migration 耗时。
- RLS 闭世界检查耗时。
- Kafka topic 检查耗时。
- 对象存储探测耗时。
- 内部 API key / JWT / encryption key 自检耗时。
- trigger/orchestrator/worker scheduler 初始化耗时。

验收标准:

- staging 启动慢时能定位到具体阶段。
- 启动检查超时有明确阈值和日志字段。

## 3. 建议分阶段实施

### P0:上线前最值得做

| 项 | 产出 | 验收 |
|---|---|---|
| FailureAnalyzer 最小框架 | `batch-common` 提供基础 analyzer,各模块补关键异常 | prod 缺密钥/存储配置错/Kafka topic 缺失时输出可执行动作 |
| SmartLifecycle phase 台账 | 统一 phase 常量 + 关键组件改引用 | 单测验证顺序,停机日志无 STOPPING 后业务访问 |
| 自动装配条件测试 | common/storage/lock/observability + SDK auto-config | `ApplicationContextRunner` 覆盖 enabled/disabled/user bean/prod fail-close |
| 配置强类型校验 | 关键 `BATCH_*` 与 storage/security/worker 配置补 Validation | 配错启动失败且有明确错误 |

### P1:生产可运维成熟度

| 项 | 产出 | 验收 |
|---|---|---|
| Health group 重整 | liveness/readiness/startup 分组 | K8s/Helm 与 readiness 语义一致 |
| Domain Actuator 端点 | storage/outbox/worker/feature-switch 诊断端点 | 不泄露敏感信息,能用于排障 |
| 配置 metadata + 文档同步 | metadata + registry 生成文档/CI 输入 | 新配置未登记 CI 失败 |
| ApplicationAvailability 接入 | drain/下游不可用时发布 readiness 状态 | 压测/停机/下游故障下状态变化符合预期 |

### P2:长期工程化收益

| 项 | 产出 | 验收 |
|---|---|---|
| Starter 化拆分 | storage/observability/security/worker-core starter | 只拆平台能力,不拆业务状态机 |
| 装配解释报告 | BFS diagnostic endpoint | 能解释 backend/adapter/switch 选择原因 |
| StartupStep 埋点 | 启动阶段耗时指标 | 慢启动可定位 |
| 配置生成链路 | registry -> docs/Helm/Compose/CI | 配置源单一化 |

## 4. 不建议照搬的 Spring Boot 做法

| 不建议项 | 原因 |
|---|---|
| 全面改 Spring Data/JPA | BFS 状态机依赖显式 SQL、CAS、ON CONFLICT、分区和批量语义,MyBatis 更可控 |
| 为了 starter 化拆过多模块 | 当前复杂度在领域状态机,过度拆模块会增加依赖和发布成本 |
| 全面 WebFlux 化 | 控制面瓶颈主要在 DB/Kafka/锁/状态推进,不是 servlet 线程模型 |
| 现在追 AOT/native image | 反射、MyBatis、SDK、多模块测试成本高,收益不如配置/生命周期/诊断 |
| 用 Spring Batch 替代 worker | BFS 已有五类 worker 和平台协议,更适合借 checkpoint/restart 理念而非换引擎 |

## 5. 建议包结构

建议先不大拆 Maven 模块,先在包级收敛:

```text
batch-common/
  src/main/java/io/github/pinpols/batch/common/autoconfigure/
    storage/
    crypto/
    lock/
    observability/
    rls/
    security/
    lifecycle/
    diagnostics/

  src/main/java/io/github/pinpols/batch/common/diagnostics/
    failure/
    actuator/
    condition/

  src/main/java/io/github/pinpols/batch/common/lifecycle/
    BatchLifecyclePhases.java
```

SDK 侧继续沿用:

```text
sdk/java/spring/
  src/main/java/io/github/pinpols/batch/sdk/autoconfigure/
  src/test/java/io/github/pinpols/batch/sdk/autoconfigure/
```

等包级边界稳定后,再考虑 Maven starter:

- `batch-storage-spring-boot-starter`
- `batch-observability-spring-boot-starter`
- `batch-internal-security-spring-boot-starter`
- `batch-worker-sdk-spring-boot-starter`

## 6. 第一批 PR 建议

### PR-1:启动失败诊断

范围:

- 增加 FailureAnalyzer 基础工具。
- 覆盖生产密钥、对象存储、Kafka topic、RLS、Redis/ShedLock 五类错误。
- 文档链接到 runbook 或 config keys。

不做:

- 不改业务状态机。
- 不新增外部依赖。

### PR-2:生命周期 phase 台账

范围:

- 新增 `BatchLifecyclePhases`。
- 改造调度器、relay、worker lease、SDK lifecycle 引用常量。
- 增加 phase 顺序测试。

不做:

- 不改变任务执行语义。
- 不调整线程池容量。

### PR-3:配置元数据和开关登记源

范围:

- 接入 configuration processor。
- 引入或完善 `feature-switch-registry.yml`。
- 生成配置文档和 CI 校验输入。

不做:

- 不一次性重命名所有历史配置键。

### PR-4:Actuator domain diagnostics

范围:

- health group 对齐 liveness/readiness/startup。
- 增加 storage/outbox/worker/feature-switch 诊断端点。
- 脱敏与权限控制。

不做:

- 不暴露业务数据。
- 不暴露密钥、完整路径、token。

## 7. 最终目标

BFS 要学 Spring Boot 的不是「更多注解」,而是这套成熟工程化闭环:

```text
强类型配置 -> 条件装配 -> 启动自检 -> 失败诊断 -> 生命周期顺序 -> readiness 表达 -> Actuator 可观测 -> 切片测试防漂移
```

做到这一步后,系统上线时的主要收益是:

- 配错能在启动期 fail-fast,且告诉运维怎么修。
- 优雅停机顺序可测试,不是靠经验。
- 每个环境实际启用了什么能力可解释。
- 配置、Helm、Compose、文档、CI 不再手工漂移。
- 自动装配与开关变更有窄上下文测试兜底。

这比引入一个新框架更适合当前 BFS:不改变核心领域模型,但显著提高生产可控性。

## 8. 全量收口实施计划（2026-08-02）

本节把前面的借鉴建议转成可执行的完整收口计划。本轮 PR 已将八个方向全部落到代码、测试或 CI 门禁：

- `BatchStartupFailureAnalyzer` 已注册并覆盖生产密钥、对象存储和安全旁路等 fail-close 异常。
- `BatchLifecyclePhases` 已用于共享调度器、Trigger Outbox scheduler、Webhook relay 和 worker drain。
- 调度、Trigger relay、存储、文件系统、加密和启动自检配置已增加边界校验。
- startup self-check 已接入 `ApplicationStartup` 与 Micrometer duration 指标。
- trigger/worker drain 已发布 Spring Boot readiness 状态；新增只读、脱敏的 `batchruntime` Actuator 端点。
- feature-switch registry 已由 CI 校验，防止开关和生产环境变量遗漏登记。
- 全量 reactor 编译、受影响模块完整测试、配置同步、Helm 同步、SQL 边界和 workflow lint 均已通过。

本节剩余的是上线前环境证据，不是未实现代码：远端 full-gate、生产 Helm 实际 Secret 注入、滚动发布/强杀恢复演练，以及按系统边界明确“不拆业务 starter”。这些必须在目标环境执行，不能用本地单测替代。

### 8.1 执行顺序

| 阶段 | 工作包 | 主要代码落点 | 完成门槛 |
|---|---|---|---|
| P0-A | 自动装配条件矩阵 | `batch-common/src/test`、`sdk/java/spring/src/test` | enabled/disabled、缺依赖、自定义 bean、生产 fail-close 四类上下文测试通过 |
| P0-B | 配置边界统一校验 | `batch-common` 存储、安全、Kafka、RLS、调度配置类 | 非法值绑定阶段失败；正常默认配置和 local/prod 配置均能绑定 |
| P1-A | 健康组与 readiness | `batch-defaults.yml`、各服务管理配置、`BatchHealthAutoConfiguration` | liveness 只表达进程存活；readiness 表达可安全接收调度流量；startup 自检不泄露敏感信息 |
| P1-B | 可用性状态接入 | `OrchestratorGracefulShutdown`、worker/trigger/console 生命周期组件 | drain、关键依赖持续不可达、RLS 自检失败时发布 `REFUSING_TRAFFIC`；恢复路径有测试 |
| P1-C | 领域诊断端点 | `batch-common` diagnostics/actuator、orchestrator health | storage、outbox、worker、feature switch 能返回脱敏状态、原因和建议动作 |
| P1-D | 配置登记源治理 | `feature-switch-registry.yml`、配置 metadata、Helm/Compose/CI 脚本 | 新增开关或生产必填变量未登记时 CI 失败；文档和部署模板由登记源校验 |
| P2-A | 装配解释能力 | `batch-common` diagnostics/condition | 能解释实际 storage backend、dispatch adapter、lock provider 和开关来源 |
| P2-B | 启动阶段观测 | 各服务 Application 配置、startup self-check | Flyway、RLS、Kafka、storage、scheduler 初始化有耗时和结果字段 |
| P2-C | Starter 边界收口 | `batch-common`、`sdk/java/spring` 及必要 Maven 模块 | 只拆平台能力；业务状态机、CAS、MyBatis SQL 不因 starter 化迁移 |

### 8.2 每个工作包的实现要求

#### P0-A：自动装配条件矩阵

为每个已有 `@AutoConfiguration` 建立最小 `ApplicationContextRunner` 测试矩阵：

1. 依赖存在且开关开启时，验证 bean 数量、bean 类型和装配顺序。
2. 依赖缺失或开关关闭时，验证不会误装配，也不会触发无关连接探测。
3. 用户提供同类型 bean 时，验证 `@ConditionalOnMissingBean` 生效。
4. `prod` profile 缺密钥、弱 KMS、错误存储后端时，验证 fail-close 和 FailureAnalyzer 文案。

重点覆盖 `BatchObjectStoreAutoConfiguration`、`BatchObjectCryptoAutoConfiguration`、`S3AutoConfiguration`、`BatchShedLockAutoConfiguration`、`BatchHealthAutoConfiguration` 和 Java SDK starter。测试必须使用 fake bean，不依赖真实 PG、Redis、Kafka 或 S3。

#### P0-B：配置边界统一校验

优先处理会造成资源失控、错误路由或安全降级的配置：

- 存储：endpoint、bucket、超时、multipart threshold/part size、filesystem root 和扫描上限。
- 安全：bypass、internal secret、JWT/KMS 引用、生产数据库凭据。
- Kafka：bootstrap、topic、group、poll/timeout 和重试次数。
- RLS/租户：schema、租户上下文开关和豁免表白名单。
- worker/lease：并发数、续租周期、lease TTL、report/outbox 批量大小。

校验分为两层：通用数值/格式约束使用 Bean Validation；涉及 profile、多个字段关系或外部资源的约束保留显式 fail-fast 检查。不得把生产凭据写入 `ConstraintViolation`、metadata 或诊断响应。

#### P1-A/P1-B：健康组与可用性

统一三种状态语义：

- `liveness`：只判断 JVM/Spring 上下文是否还能工作，不因 Redis、Kafka、PG 或 S3 短暂故障触发重启。
- `readiness`：判断是否能安全接收 launch、claim、relay、lease/report 等新流量；进入 drain 时必须为拒绝流量。
- `startup`：表达启动期迁移、RLS、topic、bucket 和关键安全检查结果，不替代 liveness。

实现时必须复核每个服务的管理端口、Helm probe、Docker healthcheck 和本地脚本，保证路径与端口一致。对 readiness 的依赖探测使用超时和缓存，不能让健康探针本身打满连接池。

#### P1-C：领域诊断端点

诊断响应只允许包含：

- effective backend/provider 名称；
- enabled/disabled、状态、最近检查时间和耗时；
- 脱敏后的 endpoint host、bucket/root 摘要、配置来源；
- 可执行的修复建议和关联 metric 名称。

禁止返回 secret、JWT/KMS 原文、API key、完整本地文件路径、租户业务数据和下游响应体。端点默认只在管理端口暴露，并沿用现有管理端认证和网络隔离规则。

#### P1-D：登记源和部署一致性

以 `feature-switch-registry.yml` 作为开关的唯一登记源，至少登记：key、默认值、允许 profile、是否允许运行期变更、敏感级别、负责人和回滚方式。由脚本生成或校验：

- configuration metadata 与配置键字典；
- Helm required values、Secret/ConfigMap 注入项；
- Compose 示例环境变量；
- CI required-vars 和生产安全门禁。

历史配置键暂不重命名；新增键必须登记，废弃键必须标注迁移截止版本，避免一次性改动造成部署回滚困难。

#### P2-A/P2-B：解释与启动观测

装配解释和启动观测都必须是只读能力。推荐字段：`component`、`selectedImplementation`、`selectionReason`、`source`、`durationMs`、`status`、`failureCode`。实现优先复用 Spring Boot 的 `ConditionEvaluationReport`、`ApplicationStartup` 和现有 startup self-check，不重复造一套容器生命周期。

启动阶段只记录阶段名称、结果、耗时和错误码；禁止把完整异常请求、密钥或大段下游响应写入 startup step 或结构化日志。

#### P2-C：Starter 边界

只有在 P0/P1 的条件测试、配置 metadata 和诊断协议稳定后才拆 starter。允许拆出的内容是 storage、observability、internal-security、worker SDK 等平台能力；禁止把 job instance、batch day、task CAS、outbox 状态推进和 worker 业务插件拆成通用 starter。

### 8.3 测试与验收矩阵

每个阶段都必须同时具备代码测试和部署配置验证：

| 层级 | 必测内容 |
|---|---|
| 单元测试 | properties 边界、FailureAnalyzer、phase 顺序、脱敏、状态转换 |
| Context 测试 | auto-config 条件、用户覆盖、profile fail-close、无关依赖不装配 |
| 集成测试 | PG/RLS、Redis/ShedLock、Kafka outbox、S3/MinIO、管理端 health/readiness |
| E2E/Sim | 启动失败、手工 drain、依赖不可达、恢复、优雅停机、五类 worker 主链路 |
| 静态门禁 | FQN、危险 API、日志敏感字段、配置未登记、Helm required vars、依赖安全扫描 |
| 生产演练 | 滚动发布、readiness 摘流量、长任务 drain、强杀后恢复、旧配置回滚 |

最终验收必须满足：

- `mvn verify` 与仓库 `full-gate` 全绿；
- 所有新增配置在 metadata、默认 yml、Helm/Compose、文档和 CI 中可追溯；
- 管理端健康端点不泄露凭据，且 probe 在下游短暂故障时不会错误重启服务；
- 关闭期间不再出现连接工厂已 STOPPING 后 relay、scheduler、lease 或 report 继续访问；
- 五类 worker 的 startup、readiness、drain、恢复和失败路径均有证据；
- 任何单项失败都能按工作包回滚，不需要回滚 job/task 核心状态机。

### 8.4 PR 拆分与回滚

建议按以下顺序拆 PR，避免一个大 PR 同时改变配置、探针和生命周期：

1. `P0-A/P0-B`：自动装配测试与配置边界校验。
2. `P1-A/P1-B`：health group、readiness 和 drain 状态。
3. `P1-C/P1-D`：领域诊断端点和 registry/CI 一致性。
4. `P2-A/P2-B`：装配解释和启动阶段观测。
5. `P2-C`：在前四个 PR 稳定后再做 starter 边界拆分。

每个 PR 必须先在本地完成定向测试，再等待 full-gate；未通过的 PR 不合并。回滚优先关闭新增诊断/观测开关，配置校验只允许回退到上一版已验证约束，不得通过重新开启生产 bypass 或弱默认凭据来“恢复服务”。

### 8.5 完成定义

本 PR 的代码实现和本地验证已完成；上线验收仍以远端 full-gate、目标环境部署和恢复演练证据为准。P2-C 不拆分业务 starter 是经过边界评估后的明确结论：平台能力继续留在 `batch-common`/SDK，job instance、batch day、task CAS、outbox 和插件状态机不抽成通用 starter。
