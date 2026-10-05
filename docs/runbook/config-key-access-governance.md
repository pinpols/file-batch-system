# 程序内配置 Key 读取治理

本文约束生产代码中通过字符串 key 读取配置的写法，包括直接读取和 `@Value` 注入，例如：

```java
environment.getProperty("batch.xxx")
environment.getRequiredProperty("spring.xxx")
System.getProperty("batch.xxx")
@Value("${batch.xxx:default}")
```

它不替代 [配置治理与生效契约](./config-governance.md)，而是补充说明：哪些字符串 key 读取场景应该收敛，哪些允许保留，以及后续 CI 如何逐步拦截。

## 1. 治理目标

目标不是消灭所有 `getProperty` 或 `@Value`，而是减少以下风险：

- 配置 key 分散在业务代码里，改名时无法统一发现；
- 默认值散落，文档、Helm、Compose 与代码口径不一致；
- 敏感配置通过字符串 key 多处读取，审计和轮换边界不清；
- 新增配置绕过 `@ConfigurationProperties` 登记表，CI 无法识别生命周期与敏感级别。

## 2. 适用范围

纳入治理：

- `src/main/java` 生产代码中的 `Environment#getProperty(...)`、`Environment#getRequiredProperty(...)`、`System#getProperty(...)`；
- `src/main/java` 生产代码中的项目自定义 `@Value("${batch.*}")`；
- 读取项目自定义配置的字符串 key，尤其是 `batch.*`；
- 读取敏感配置、生产守护配置、功能开关、容量阈值、调度间隔、批大小和后端类型选择。

暂不纳入治理：

- `src/test`、`batch-test-support`、`batch-e2e-tests`、`load-tests` 中用于测试或压测的 `-Dxxx` 参数；
- `java.io.tmpdir`、`user.home` 等 JVM 系统属性；
- `local.server.port` 这类 Spring Boot 测试运行期属性；
- 框架桥接场景，例如仅用于诊断标签、连接池名或 actor 名的 `spring.application.name`；
- 需要读取 Spring 基础设施实际生效值的启动守护，例如 `spring.datasource.url`、`spring.data.redis.*`。这类可以后续封装 runtime inspector，但不作为第一批阻断项。

## 3. 分类规则

| 分类 | 示例 | 建议 |
|---|---|---|
| 项目自定义静态配置 | `batch.storage.backend`、`batch.console.instance-id` | 优先收敛到 `@ConfigurationProperties` |
| 项目自定义敏感配置 | `batch.console.read-replica.*.password` | 通过 properties 或专用 secret resolver 读取，避免散落 key |
| 生产守护条件 | `batch.worker.executors.guard.enforce-profiles` | 收敛到守护配置类，并登记生命周期 |
| 多字段同前缀 `@Value` | `batch.console.pipeline-progress-dirty.*` | 提取成独立 properties 类，避免默认值散落 |
| 单字段低风险 `@Value` | 单个内部实现开关或历史桥接字段 | 可以暂缓；新增时优先 properties |
| Spring 基础设施配置 | `spring.datasource.url`、`spring.data.redis.host` | 允许保留；如多处重复再封装只读 inspector |
| 运行期测试端口 | `local.server.port` | 允许保留，仅用于测试或懒解析客户端 |
| JVM 系统路径 | `java.io.tmpdir`、`user.home` | 允许保留，不强行 Spring 化 |
| 测试/压测参数 | `batch.test.*`、`users.peak` | 允许保留在测试和压测模块 |

## 4. 当前优先治理清单

第一批治理生产代码里项目自定义配置的直接 key 读取，以及高风险或多字段同前缀的 `@Value`。不扩大到 `spring.*`、JVM 系统属性和测试/压测参数。

| 优先级 | 文件 | key | 处理建议 |
|---|---|---|---|
| P1 | `batch-worker/atomic/.../AtomicExecutorProductionGuard.java` | `batch.worker.executors.guard.enforce-profiles` | 新增或扩展 executor guard properties |
| P1 | `batch-console-api/.../ConsoleRealtimeInstanceIdProvider.java` | `batch.console.instance-id` | 收敛到 Console realtime / observability properties |
| P1 | `batch-console-api/.../ConsolePipelineProgressDirtyPublisher.java` | `batch.console.pipeline-progress-dirty.*` | 5 个同前缀 `@Value` 提取为 properties 类 |
| P1 | `batch-console-api/.../ReplicaLagMonitor.java` | `batch.console.replica.*` | 2 个同功能 `@Value` 提取为 properties 类 |
| P1 | `batch-common/.../ProductionRuntimeConfigurationGuard.java` | `batch.storage.backend` | 注入 storage backend properties |
| P1 | `batch-common/.../ProductionRuntimeConfigurationGuard.java` | `batch.console.read-replica.enabled` | 注入 read-replica properties 或专用只读配置对象 |
| P1 | `batch-common/.../BatchSecurityProperties.java` | `batch.console.read-replica.primary.password` / `replica.password` | 通过 read-replica secret/properties 统一读取 |
| P2 | `batch-orchestrator/.../StaleCompensationCommandReconciler.java` | `batch.compensation.stale-running-reconciler.*` | 2 个同前缀 `@Value` 提取为 properties 类 |
| P2 | `batch-orchestrator/.../StaleCreatedLaunchRecoveryScheduler.java` | `batch.trigger.launch.created-recovery.*` | 2 个同前缀 `@Value` 提取为 properties 类 |
| P2 | `batch-common/.../BatchRuntimeStatusEndpoint.java` | `batch.storage.backend` | 复用 storage properties，避免端点内散读 |

第二批再评估：

- `spring.data.redis.*`、`spring.datasource.url` 是否需要封装为只读 runtime inspector；
- `spring.application.name` 是否统一通过 `ApplicationNameProvider` 读取；
- worker `maxConcurrentTasks` 的重复 `@Value(WorkerRuntimeConfiguration.MAX_CONCURRENT_TASKS_PLACEHOLDER)` 是否收敛到统一 runtime properties 注入路径；
- worker 本地路径默认值是否需要集中成临时目录策略组件。

## 5. 推荐改法

### 5.1 静态配置

优先写成类型安全配置：

```java
@ConfigurationProperties(prefix = "batch.console.realtime")
public record ConsoleRealtimeProperties(String instanceId) {}
```

消费方注入配置对象：

```java
public ConsoleRealtimeInstanceIdProvider(ConsoleRealtimeProperties properties) {
  this.instanceId = properties.instanceId();
}
```

配置类必须进入现有配置治理登记表，由 `scripts/ci/check-config-governance.py` 维护。

### 5.2 敏感配置

敏感配置不要在多个类中直接写 key。优先通过已绑定的 properties 或专用 resolver 读取，并确保：

- 生产环境缺失时 fail-close；
- 文档说明 Secret / 环境变量 / Helm value 注入路径；
- 不在日志、异常和诊断端点输出明文。

### 5.3 框架配置读取

如果必须读取 `spring.*`，保持用途窄且只读：

- 用于启动守护、诊断、连接池命名、运行时实际值检查；
- 不把读取结果当作业务规则；
- 多处重复时封装为 `RuntimeInfrastructureProperties` 或 inspector，但不要把 Spring 内置配置完整复制一份。

### 5.4 `@Value` 使用边界

`@Value` 不是推荐的新配置入口。新增项目自定义配置时，默认使用 `@ConfigurationProperties`。

以下 `@Value` 应提取配置类：

- 同一个类中存在 2 个及以上同前缀配置；
- key 属于 `batch.*` 且是功能开关、容量阈值、调度间隔、批大小、超时或生产守护参数；
- key 含 `password`、`secret`、`token`、`credential`、`private-key`、`kms`、`signing`；
- 同一个 key 被多个类注入；
- 默认值需要被文档、Helm、Compose 或 Console 展示复用。

以下 `@Value` 可以暂时保留：

- `spring.*` 框架配置桥接；
- `local.server.port` 或测试运行期属性；
- 仅用于极窄范围、没有复用需求的历史单字段内部开关；但新增代码不应继续采用这种方式。

## 6. CI 拦截策略

需要做 CI 控制，但治理应分阶段推进，避免一次性阻断历史技术债。当前推荐策略是“先报告、再增量拦截、最后按清理进度收紧”，不做全仓立即失败。

不建议在第一版 CI 中直接全量失败，原因是历史代码里同时存在合理例外和待治理项：`spring.*` 框架桥接、`local.server.port`、JVM 系统属性、测试/压测 `-Dxxx` 参数不应与生产 `batch.*` 业务配置散读混为一谈。

### 阶段 0：报告模式

新增脚本只输出 inventory，不失败：

```bash
python3 scripts/ci/check-direct-config-key-access.py --report
```

报告至少包含：

- 文件、行号、读取方式、key；
- 读取类型：`DIRECT_GET_PROPERTY`、`SYSTEM_PROPERTY`、`VALUE_INJECTION`；
- 分类建议：`PROJECT_CONFIG`、`SECRET_CONFIG`、`SPRING_INFRA`、`JVM_SYSTEM`、`TEST_ONLY`；
- 是否命中白名单。

适合先接入 Full Gate 或 nightly，作为治理看板。

本阶段目标是让团队看到新增趋势和分类，不要求立刻清空存量。报告输出应稳定、机器可读，便于后续生成 baseline。

### 阶段 1：新增增量拦截

PR Gate 只拦截新增违规，不要求一次清完历史：

- 新增 `environment.getProperty("batch.*")` 或 `System.getProperty("batch.*")`，且不在白名单，失败；
- 新增 `@Value("${batch.*}")` 时，如果是敏感 key、同前缀多字段或高风险生产守护配置，失败；
- 新增敏感 key 直接读取，失败；
- 新增 `spring.*` 读取只告警，除非命中密码、secret、token。

历史存量放入基线文件，例如：

```text
docs/governance/direct-config-key-access-baseline.txt
```

CI 比对当前扫描结果与基线，只对新增项失败。

本阶段的失败条件应保持克制：只拦新增高风险项，不阻断合理例外，也不因历史存量让无关 PR 失败。

### 阶段 2：P1 存量清零

清理本文 P1 清单后，将脚本升级为：

- `batch.*` 直接读取默认失败；
- `batch.*` 高风险 `@Value` 默认失败，低风险历史单字段必须登记白名单；
- 明确允许项必须在白名单中写明原因、owner 和复查条件；
- `spring.*`、JVM 系统属性继续采用报告模式。

### 阶段 3：文档治理联动

当新增或删除 `@ConfigurationProperties` 时，继续由现有脚本维护：

```bash
python3 scripts/ci/check-config-governance.py --write
```

当新增直接 key 读取白名单时，PR 描述必须说明：

- 为什么不能用 `@ConfigurationProperties`；
- 是否涉及敏感信息；
- 是否影响生产启动、滚动重启或多实例一致性；
- 后续是否要清理。

## 7. 不建议的做法

- 不建议把所有 `getProperty` 一刀切改成 `@Value`。`@Value` 仍然是字符串 key，只是换了注入方式。
- 不建议继续新增项目自定义 `@Value("${batch.*}")`。确实要保留时必须说明为什么不用 properties。
- 不建议把 `spring.*` 全部复制成自定义 properties，会制造第二份事实来源。
- 不建议把测试、压测和本地调试 `-Dxxx` 参数纳入生产配置治理。
- 不建议为了消除扫描数字，把 `java.io.tmpdir`、`user.home` 这类 JVM 属性包装成业务配置。

## 8. 本地复查命令

快速查看生产代码直接 key 读取：

```bash
rg -n '(System\.getProperty\("|environment\.getProperty\("|environment\.getRequiredProperty\(")' \
  --glob '!target/**' \
  --glob '!**/src/test/**' \
  --glob '!load-tests/**' \
  --glob '!batch-e2e-tests/**' \
  --glob '*.java'
```

查看生产代码中的项目自定义 `@Value`：

```bash
rg -n '@Value\("\$\{batch\.' \
  --glob '!target/**' \
  --glob '!**/src/test/**' \
  --glob '!load-tests/**' \
  --glob '!batch-e2e-tests/**' \
  --glob '*.java'
```

查看项目自定义 `batch.*` 直接读取：

```bash
rg -n '(System\.getProperty\("batch\.|environment\.getProperty\("batch\.|environment\.getRequiredProperty\("batch\.)' \
  --glob '!target/**' \
  --glob '!**/src/test/**' \
  --glob '!load-tests/**' \
  --glob '!batch-e2e-tests/**' \
  --glob '*.java'
```

## 9. 验收口径

一次治理 PR 通过以下条件即可认为收口：

- 新增或修改的配置读取不再散落字符串 key；
- 新增 `@ConfigurationProperties` 已进入配置治理登记表；
- 敏感配置没有新增明文日志或诊断输出；
- CI 至少能报告直接 key 读取清单；若已进入阶段 1，则新增违规可被拦截；
- 文档说明允许保留的例外，不把例外包装成已治理。
