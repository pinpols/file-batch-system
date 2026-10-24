# 后端项目验收报告

日期：2026-05-14  
范围：`file-batch-system` 后端全仓，覆盖架构、设计、接口、文档、产品闭环、安全、测试、运维与部署。

## 验收结论

后端核心链路可以通过开发/联调验收。原 P0/P1 阻断项已按本报告完成整改，生产放行仍需以 Full CI、staging gate、deploy smoke、load smoke 的实际通过记录为准。

本次整改重点覆盖生产部署密钥注入、发布安全门禁、分发适配器路由、KEDA 查询、文档索引和 JDK 未来兼容风险。

## 整改复核

已整改：

- Helm Chart 增加 `security.internalSecret` 与 `security.consoleJwtSecret`，Secret 渲染 `BATCH_INTERNAL_SECRET`、`BATCH_CONSOLE_JWT_SECRET`，prod overlay 示例明确要求注入。
- 生产启动校验补强：`BATCH_INTERNAL_SECRET`、`BATCH_CONSOLE_JWT_SECRET` 为空或默认值均 fail-close。
- NAS/OSS 存根适配器限制为 `local/test` profile，并通过 `@Order` 让真实 NAS/OSS 适配器优先。
- Full CI 与 staging gate 中 secret、dependency、hadolint、Trivy、DAST、Checkov 由软失败改为硬门禁。
- KEDA Orchestrator backlog 查询改为 `batch.outbox_event`。
- `architecture-truth.md` Flyway 当前版本更新为 V122，README 中失效文档链接已修正。
- Surefire 显式配置 Mockito javaagent，降低未来 JDK 禁用动态 agent 后测试门禁失效的风险。

## 总体评价

架构方向成立：

- 多模块边界清晰：`batch-orchestrator`、`batch-console-api`、`batch-trigger`、`batch-worker-*`、`batch-e2e-tests` 等职责明确。
- Orchestrator 作为唯一状态主机，Worker 通过内部接口 claim/report，不直接改核心运行态。
- 主链路 `DB -> Outbox -> Kafka -> CLAIM -> EXECUTE -> REPORT` 在代码中有事务、CAS、租约、Outbox 支撑。
- Console API 具备 JWT、Argon2、租户守卫、幂等、限流、安全响应头等基础安全能力。
- 文档、OpenAPI、Runbook、观测性材料较完整，具备团队协作与交付基础。

## 实测结果

已执行并通过：

```bash
mvn -q -DskipTests compile
python3 scripts/ci/check-console-openapi-paths.py
mvn -q -pl batch-console-api -Dtest=ConsoleSecurityConfigurationTest,ConsoleTenantGuardTest,ConsoleIdempotencyInterceptorTest test
mvn -q -pl batch-worker-dispatch -Dtest=DispatchChannelGatewayTest,RemoteFilesystemNasPathTest,DispatchExternalChannelIntegrationTest test
mvn -q -pl batch-orchestrator -Dtest=TaskDispatchOutboxServiceMandatoryTest,ConcurrentTaskFinishIntegrationTest,SqlConsistencyIntegrationTest test
```

OpenAPI 校验结果：

- 299 条 `/api/console` 路由与控制器一致。

## 架构验收

### 通过项

- 核心业务链路遵循 README 中定义的主链约束。
- 任务创建、分区释放、Outbox 写入处于同一事务边界。
- Worker claim 使用状态条件和 version CAS，避免重复认领。
- partition lease 有 `current_invocation_id` 和续租机制。
- `TaskDispatchOutboxService` 使用 `MANDATORY` 事务传播，避免孤立 Outbox 写入。
- 多租户字段在主要运行态、配置态、查询和测试中有持续覆盖。
- MyBatis Mapper/XML 为主要持久层入口，符合项目约束。

### 风险项

- 已整改：`architecture-truth.md` 已更新 Flyway 当前版本 V122，README 文档索引中已知失效链接已修正。

## 产品与接口验收

### 通过项

- Console API 覆盖面较完整，包含认证、查询、任务操作、审批、配置、文件治理、观测、实时 SSE、运维入口等。
- OpenAPI 与控制器路径一致性有脚本和 CI 前置校验。
- 前后端联调约定明确，`AGENTS.md` 已指出 `../batch-console` 的 API 生成类型、调用层、store、菜单同步点。
- 写接口普遍有 `Idempotency-Key` 约束和拦截器守护。

### 风险项

- OpenAPI 只验证路径一致性，schema 语义漂移仍需要加强测试或生成物比对。
- README 中部分文档路径指向旧目录或旧文件名。

## 安全验收

### 通过项

- Console 使用 JWT Bearer 作为主认证方式。
- 密码使用 Argon2id 哈希。
- 生产 profile 下会拒绝默认 JWT secret 和默认 internal secret。
- 内部 `/internal/**` 接口通过 `X-Internal-Secret` 校验。
- Console 租户守卫会区分全局角色与租户角色，跨租户访问会拒绝。
- SSE 使用一次性 ticket，已移除 query token 方式。
- 路径遍历、XXE、SFTP host key、密钥脱敏、AI prompt guard 等有明确实现或文档说明。

### P0 阻断项整改

#### 1. 生产 Helm 未注入关键安全密钥

`helm/values-prod.yaml` 启用了：

```yaml
-Dspring.profiles.active=prod
```

原 Helm Secret/ConfigMap 没有注入：

- `BATCH_INTERNAL_SECRET`
- `BATCH_CONSOLE_JWT_SECRET`

代码在生产 profile 下会拒绝默认密钥，这是正确的 fail-close；Chart 缺少注入入口会导致生产部署启动失败。

相关位置：

- `helm/values-prod.yaml`
- `helm/batch-platform/templates/secret.yaml`
- `helm/batch-platform/templates/configmap.yaml`
- `batch-config-defaults/src/main/resources/batch-defaults.yml`
- `batch-console-api/src/main/java/io/github/pinpols/batch/console/support/auth/ConsoleJwtService.java`

整改结果：

- `helm/batch-platform/values.yaml` 增加 `security.internalSecret`、`security.consoleJwtSecret`。
- `helm/batch-platform/templates/secret.yaml` 输出 `BATCH_INTERNAL_SECRET`、`BATCH_CONSOLE_JWT_SECRET`。
- `helm/values-prod.yaml` 明确生产通过 `--set security.internalSecret` 与 `--set security.consoleJwtSecret` 注入。
- `BatchSecurityProperties` 与 `ConsoleJwtService` 对生产空值和默认值均 fail-close。

#### 2. CI 安全扫描为软失败

`full-ci-gate.yml` 中 secrets/deps/hadolint/trivy 等扫描原使用 `continue-on-error` 或 `exit-code: 0`，会记录风险但不阻断合并。

整改结果：已将以下检查改为硬门禁：

- secret 扫描
- Critical/High dependency 漏洞
- 容器镜像 Critical/High
- Helm/K8s 高危配置

## 运行与分发验收

### 通过项

- Import/Export/Dispatch/Process Worker 的 stage 模型清晰。
- Worker core 抽象出注册、心跳、claim、执行、report、lease renew 等通用能力。
- Dispatch 外部通道已有 API、LOCAL、NAS、OSS、SFTP、EMAIL 等适配器。
- Dispatch 渠道健康、熔断、指标记录有统一 gateway。

### P0/P1 风险项整改

#### NAS/OSS 分发适配器存在路由歧义

`DispatchChannelGateway` 对 `List<DispatchChannelAdapter>` 取第一个 `supports()` 命中的 bean。

当前同时存在：

- `NasDispatchChannelAdapter`
- `OssDispatchChannelAdapter`
- `StubRemoteFilesystemDispatchChannelAdapter`

其中 `StubRemoteFilesystemDispatchChannelAdapter` 也支持 `NAS/OSS`。如果 Spring bean 顺序变化，生产可能被存根接管，只写本地 envelope 而不做真实远端传输。

整改结果：

- `StubRemoteFilesystemDispatchChannelAdapter` 增加 `@Profile({"local", "test"})`，生产 profile 下不注册。
- `NasDispatchChannelAdapter`、`OssDispatchChannelAdapter` 增加 `@Order(0)`。
- `StubRemoteFilesystemDispatchChannelAdapter` 增加 `@Order(1000)`，local/test 环境中真实适配器优先。

## 部署与运维验收

### 通过项

- Docker Compose、Helm、观测栈、Prometheus/Grafana/OTel/Loki/Tempo 配套完整。
- readiness/liveness、资源 requests/limits、graceful shutdown、preStop、HPA/KEDA 设计都有配置。
- 有 staging gate、capacity gate、load smoke、deploy smoke、巡检脚本等材料。

### 风险项

#### KEDA backlog 查询 schema 错误

`helm/batch-platform/values.yaml` 中 Orchestrator KEDA backlog 查询为：

```sql
SELECT COUNT(*) FROM biz.outbox_event WHERE publish_status IN ('NEW','FAILED')
```

但平台 Outbox 在 `batch` schema，应改为：

```sql
SELECT COUNT(*) FROM batch.outbox_event WHERE publish_status IN ('NEW','FAILED')
```

该问题只在启用 KEDA 时暴露，但属于生产扩缩容风险。已整改为 `batch.outbox_event`。

## 测试验收

### 通过项

- 仓库测试规模较充分：主代码约 1476 个 Java 文件，测试 Java 文件约 402 个。
- 单元、集成、E2E 分层明确。
- Testcontainers 覆盖 PostgreSQL、Kafka、MinIO、Redis、SFTP 等基础设施。
- 有多租户并发、Outbox retry、Worker drain、失败链路、流程重启恢复等 E2E 用例。
- 关键 SQL/约束有 `SqlConsistencyIntegrationTest` 等守护。

### 风险项

- JDK 25 下 Mockito inline mock 出现动态 agent 未来兼容警告。已在 Surefire 中显式配置 Mockito javaagent。
- 全量 E2E 未在本次验收中完整执行，本次为核心局部验证。

## 文档验收

### 通过项

- 文档体系完整：architecture、design、runbook、testing、api、compliance、dict、analysis 均有内容。
- API 协议 + OpenAPI 双轨维护，有路径一致性脚本。
- ADR 数量充足，核心设计决策可追溯。
- SECURITY.md 对已落地安全能力有说明。

### 风险项

- README 文档索引中已知失效路径已修正。
- `architecture-truth.md` 的 schema 版本已更新到 V122。
- SECURITY.md 联系邮箱仍为占位符 `security@your-domain.example`，公开或正式交付前需替换。

## 整改优先级状态

### P0：生产阻断

1. 已完成：Helm 增加并强制注入 `BATCH_INTERNAL_SECRET`、`BATCH_CONSOLE_JWT_SECRET`。
2. 已完成：解决 NAS/OSS adapter 与 stub adapter 的生产路由歧义。
3. 已完成：将关键安全扫描从软失败改为硬门禁。

### P1：上线前应修

1. 已完成：修正 KEDA backlog 查询 schema。
2. 已完成：修复 README 文档索引失效链接。
3. 已完成：更新 `architecture-truth.md` 的 Flyway 版本和 schema 基线。
4. 已完成：配置 Mockito agent，消除 JDK 未来兼容风险。

### P2：持续改进

1. 增加 OpenAPI schema 语义一致性校验。
2. 增加 Helm 渲染结果断言：密钥、端口、KEDA 查询、prod profile 必备变量。
3. 将 staging 验收结果固化为可追溯报告。
4. SECURITY.md 替换正式漏洞响应渠道。

## 最终判定

| 阶段 | 判定 |
| --- | --- |
| 开发态 | 通过 |
| 联调态 | 基本通过 |
| Staging | 可进入完整验收 |
| 生产 | 需等待 Full CI、staging gate、deploy smoke、load smoke 全部通过后放行 |

生产放行前最低条件：

- P0/P1 整改项保持关闭。
- Full CI + staging gate + deploy smoke + load smoke 有可追溯通过记录。
