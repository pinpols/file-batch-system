# 配置治理与生效契约

## 1. 分类模型

配置用三个正交维度描述，禁止把敏感级别与生效方式混成一个枚举：

| 维度 | 值 | 含义 |
|---|---|---|
| 来源 | `STATIC` | Spring Boot 启动期绑定，来源为 yml、环境变量、ConfigMap 或 Secret |
| 来源 | `DYNAMIC_DB` | 以平台数据库为权威源，写后通过共享缓存失效传播 |
| 生效方式 | `RESTART_REQUIRED` | Bean 在启动期绑定，变更后必须滚动重启 |
| 生效方式 | `IMMEDIATE_AFTER_CONFIRMATION` | 数据提交后仍须等待目标实例确认，不能把 `PUBLISHED` 当作已全量生效 |
| 敏感级别 | `PUBLIC` / `SECRET` | 决定是否只能经 Secret/外部密钥系统注入，与是否重启无关 |

机器登记源是 [config-governance-registry.yml](./config-governance-registry.yml)。生产
`@ConfigurationProperties` 绑定点以登记表与源码校验结果为准；包含
`@ConfigurationPropertiesScan`、测试启动类或注释的文本命中数不作为验收数字。

## 2. 启动期配置

- 所有生产 `@ConfigurationProperties` 均登记为 `STATIC + RESTART_REQUIRED`，其中含密码、密钥、令牌或凭据的配置额外标为 `SECRET`。
- Helm 工作负载的 Pod template 同时携带 `checksum/config` 与 `checksum/secret`。共享 ConfigMap 或 Secret
  渲染结果变化会改变 template hash，由 Kubernetes 自动滚动更新 Pod。
- 不支持运行时重绑定。`@RefreshScope`、`ConfigurationPropertiesRebinder`、Spring Cloud Config、
  Nacos Config 和 Apollo 客户端由 CI 禁止，避免部分 Bean 刷新、连接池未重建和多实例漂移。
- 配置的可执行边界用 `@Validated` 和字段约束在绑定期校验。副本采样周期、Worker 并发与续租
  阈值必须为正数；首次采样延迟、Kafka 最小拉取字节数和等待时间允许零。已有跨字段背压检查
  保留在容器工厂；批量领取大小的退化规则和已有零值禁用语义不因本次治理改变。
- Worker Kafka consumer 使用 `WorkerKafkaProperties` 绑定原有 `spring.kafka` 配置键，保留
  `max-poll-interval-ms`、`metadata-max-age-ms` 等现有键和毫秒单位；不新增 Spring Boot Kafka
  自动配置依赖。producer 继续复用现有集中配置，手动 ACK、观测与批量背压行为保持不变。
- Spring 管理的异步线程池由初始化回调创建底层线程池，Bean 工厂方法不再手动调用
  `initialize()`；资源关闭仍交给 Spring 生命周期。

### Worker 注册端口

五类 Worker 仅使用主 WebServer 绑定后写入的 `local.server.port` 注册，不使用 `server.port`
配置值或固定类型端口兜底。`server.port=0` 可以使用实际随机端口；独立 management 服务的
`local.management.port` 不参与 Worker 注册。服务尚未绑定或运行时端口无效时拒绝注册，心跳沿既有
重试路径等待绑定完成；配置端口不代表监听成功。修改 `server.port` 的部署覆盖入口保持不变。

定向验证包括配置绑定成功/失败、Kafka 参数传递与跨字段容量限制、Spring 线程池初始化/销毁、
未绑定时拒绝注册，以及主服务和管理服务均使用真实随机端口时的注册结果。加载完整 Worker 应用的
集成测试使用 `RANDOM_PORT`，不能使用非 Web 上下文再依赖固定端口完成注册；不加载 Worker 注册循环的
纯组件测试仍可使用非 Web 上下文。上述验证不替代整套 sim 或 staging 验证。

### 本地验证记录（2026-10-08）

| 层级 | 范围 | 结果 |
|---|---|---|
| 定向测试 | 配置真实绑定、线程池初始化/关闭、Kafka 参数与背压、注册/心跳/租约及主服务与管理服务随机端口 | 104 个用例通过，失败和跳过均为 0 |
| Worker 集成测试 | 五类 Worker 的 21 个完整应用及相关集成测试类；包括业务库路由、导出分片、对象存储、SFTP/邮件、Atomic 执行器 | 70 个用例通过，失败和跳过均为 0 |
| 端口复验 | 空值校验收敛后重新执行 `AbstractWorkerLoopTest` 与 `WorkerPortBindingTest` | 20 个用例通过，失败和跳过均为 0 |
| Worker core 完整测试 | `./mvnw -ntp -pl batch-worker/core -am test` | core 235 个用例通过，失败和跳过均为 0 |
| 静态治理 | 配置登记、默认值同步、环境变量、Helm/生产覆盖入口、模块依赖和空值判断规范 | 通过；配置绑定登记共 134 处 |
| Sonar 本地增量报告 | 全仓分析后按 `origin/main` 至工作树的 Java 变更行过滤；报告目录 `reports/sonar/2026-10-08_00-31-04/` | 36 个包含新增行的 Java 文件，OPEN issue 0、待审安全热点 0 |

集成测试复用仓库 Testcontainers 基础设施；Orchestrator 注册接口使用既有 WireMock 支撑，
不代表真实控制面故障恢复或生产容量验收。未重启本地常驻应用，未执行整套 sim、Full Gate 或 staging 验收。
完整 core 测试的上游模块也无失败；common 的 2 个外部分片用例与 test-support 的 3 个 S3 活体契约
用例因未启用对应外部环境而跳过，不作为本轮通过证据。
Sonar 本轮未采集覆盖率；增量无问题不等于全仓历史问题清零，也不替代线上 required checks。
运行日志分别为 `/tmp/bfs-spring-config-tests.log`、`/tmp/bfs-spring-config-integration.log` 和
`/tmp/bfs-spring-config-port-recheck.log`；这些临时文件仅作本轮本机取证，不是仓库测试入口。

## 3. 动态数据库配置

`business-calendar`、`batch-window`、`job-definition`、`workflow-definition`、
`tenant-quota-policy` 与配置发布单登记为 `DYNAMIC_DB`。现有写链路必须保持：

```text
Console 事务写 DB -> afterCommit 删除共享 Redis 缓存 -> 各实例下一次读取回源 DB
```

配置发布动作必须提交 `expectedVersionNo`。服务端依次校验：

1. 请求版本等于目标发布记录版本；
2. 目标版本是同租户、同类型、同 key 的最新版本；
3. SQL 使用 `version_no + config_status` 做 CAS；
4. CAS 更新行数必须等于 `1`，否则返回 `STATE_CONFLICT`。

审批提交、批准和拒绝复用相同 CAS 约束，不能绕过旧版本门禁。

## 4. Console 展示语义

配置发布列表详情明确展示：

- `configSource`：配置来源；
- `activationMode`：生效方式；
- `restartRequired`：是否需要滚动重启；
- `applyConfirmationStatus`：实例确认状态。

当前 `config_release` 是发布治理记录，不是通用配置执行引擎。因此发布后的状态明确返回
`CONFIRMATION_REQUIRED`，不能声称所有实例已经采用 `config_payload`。具体业务配置仍以对应业务表写入和
Redis `afterCommit` 失效为生效边界。后续若引入配置中心，必须先实现“目标实例快照、实例 ACK、超时失败、
版本水位指标”完整闭环，再允许返回 `CONFIRMED`。

## 5. 多实例一致性

动态数据库配置变更后，Console 不只删除共享 Redis key，还会在事务 `afterCommit` 后发布
`batch:config:invalidation` 事件。事件包含：

- `tenantId`、`type`、`code`：定位要失效的配置；`code=*` 表示该类型整租户批量失效；
- `revision`：全局单调递增版本，用于跨实例比较处理水位；
- `keyRevision`：单 key 单调递增版本，用于拒绝乱序旧事件；
- `changedAt`：用于计算事件传播延迟。

Orchestrator 每个实例都订阅该频道并清理本机 Caffeine 近缓存，避免仅删除共享 Redis 后本地缓存继续返回旧配置。
同时每 10 秒读取 Redis 全局 revision；若发现本机 `applied_revision` 落后，会清空全部本地配置缓存并推进水位，
作为 Pub/Sub 瞬断或实例重连期间漏事件的兜底。

关键指标：

| 指标 | 位置 | 含义 |
|---|---|---|
| `batch.console.config.invalidation.published_revision` | Console | Console 已发布的全局配置失效版本 |
| `batch.console.config.invalidation.publish.total{result}` | Console | 发布成功 / 失败次数 |
| `batch.orchestrator.config.invalidation.applied_revision` | Orchestrator | 单实例已应用的最高配置失效版本 |
| `batch.orchestrator.config.invalidation.event.total{result}` | Orchestrator | 事件应用、拒绝旧事件、失败次数 |
| `batch.orchestrator.config.invalidation.reconcile.total` | Orchestrator | revision 落后后触发全量本地失效的次数 |
| `batch.orchestrator.config.invalidation.event_lag` | Orchestrator | 事件从 Console 发布到本实例处理的延迟 |

生产告警建议：

- `max(batch.console.config.invalidation.published_revision) - min(batch.orchestrator.config.invalidation.applied_revision) > 0`
  且持续超过 1 分钟，说明至少一个 Orchestrator 未跟上动态配置失效；
- `batch.console.config.invalidation.publish.total{result="failure"}` 或
  `batch.orchestrator.config.invalidation.event.total{result="failure"}` 增长，说明 Redis 发布或消费异常；
- `reconcile.total` 持续增长，说明 Pub/Sub 不稳定，虽然缓存会被兜底清理，但应排查 Redis 连接质量。

## 6. 校验命令

```bash
python3 scripts/ci/check-config-governance.py
helm lint helm/batch-platform
helm template test helm/batch-platform | rg 'checksum/(config|secret)'
```

新增或删除配置绑定点后执行：

```bash
python3 scripts/ci/check-config-governance.py --write
```

登记表必须与源码一同提交，PR Gate 和 Full CI Gate 都会阻断遗漏或未经评审的热刷新机制。
