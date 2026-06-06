# 批量调度平台深度问题分析报告 V2

> 分析日期：2026-04-15
> 分析范围：全模块（8 个 Java 模块 + 前端 + 基础设施）
> 分析维度：安全漏洞 / 并发与数据一致性 / 架构与设计 / 测试与可靠性

---

## 汇总

| 维度 | Critical | High | Medium | Low | 小计 |
|------|:--------:|:----:|:------:|:---:|:----:|
| 安全漏洞 | 3 | 6 | 5 | 2 | 16 |
| 并发与数据一致性 | 4 | 6 | 9 | 1 | 20 |
| 架构与设计 | 0 | 2 | 17 | 4 | 23 |
| 测试与可靠性 | 1 | 4 | 5 | 0 | 10 |
| **合计** | **8** | **18** | **36** | **7** | **69** |

---

## 优先级清单（需立即修复）

| 级别 | 编号 | 问题概述 | 关键文件 |
|------|------|----------|----------|
| :red_circle: Critical | S-1.1 | JWT 密钥默认弱值可伪造任意 token | `application.yml` |
| :red_circle: Critical | S-1.2 | 内部 API 共享密钥默认值 `internal-secret` | `BatchSecurityProperties.java` |
| :red_circle: Critical | S-1.3 | Docker Compose 硬编码数据库密码 | `docker/compose/app.yml` |
| :red_circle: Critical | C-2.1 | MANDATORY 传播无编译期保证，未来重构可能丢事务 | `TaskDispatchOutboxService.java` |
| :red_circle: Critical | C-4.1 | job_instance 无级联删除，孤儿记录无限增长 | DB schema |
| :red_circle: Critical | C-9.1 | idempotencyKey 含 version 导致 crash 后重复执行 | `TaskDispatchOutboxService.java` |
| :red_circle: Critical | T-1 | OutboxPublishCircuitBreaker 零测试 | 无测试文件 |
| :red_circle: Critical | C-2.3 | releaseForDispatch 回滚意图与实际时序不一致 | `DefaultPartitionLifecycleService.java` |

---

## 一、安全漏洞（16 项）

### Critical（3 项）

#### S-1.1 JWT 密钥默认弱值

**文件**：`batch-console-api/src/main/resources/application.yml:71`

```yaml
jwt-secret: ${BATCH_CONSOLE_JWT_SECRET:console-jwt-secret-change-me}
```

**影响**：攻击者可用默认密钥伪造任意用户（含 admin）的 JWT token，完全控制控制台。

**修复**：在 `@PostConstruct` 中校验 JWT secret 不含 `change-me`；生产 profile 下必须通过环境变量注入。

---

#### S-1.2 内部 API 共享密钥默认值

**文件**：`batch-common/.../BatchSecurityProperties.java:22`

```java
private String internalSecret = "internal-secret";
```

**影响**：`/internal/**` 端点保护 task claim / report / heartbeat 等关键操作。默认值可被猜测。

**修复**：已有 `validateNotPlaceholder` 校验 `CHANGE_ME` 前缀，需扩展为也拒绝 `internal-secret` 默认值。

---

#### S-1.3 Docker Compose 硬编码数据库密码

**文件**：`docker/compose/app.yml:24-25,79`

```yaml
BATCH_PLATFORM_DB_PASSWORD: ${BATCH_PLATFORM_DB_PASSWORD:-batch_pass_123}
BATCH_S3_SECRET_KEY: ${BATCH_S3_SECRET_KEY:-minioadmin123}
```

**修复**：移除默认密码，改为无默认值（未设置则启动失败）。使用 Docker Secrets 或外部密钥管理。

---

### High（6 项）

#### S-2.1 KMS 默认弱密钥

**文件**：`batch-console-api/src/main/resources/application-local.yml:52-54`

```yaml
keys:
  DEFAULT_TEST: AAAAAAAAAAAAAAAAAAAAAA==   # 16 字节全零
```

**修复**：local profile 使用随机生成的测试密钥；生产 profile 集成 KMS 服务。

#### S-2.2 共享 Token 认证绕过

**文件**：`ConsoleAuthenticationFilter.java:97-100`

```java
} else if (StringUtils.hasText(sharedToken)) {
  filterChain.doFilter(request, response);  // legacyHeaderAuthEnabled=false 时仍放行
  return;
}
```

**修复**：当 `legacyHeaderAuthEnabled=false` 时，不应检查 sharedToken；直接进入 JWT 验证。

#### S-2.3 内部密钥时序攻击

**文件**：`InternalAuthFilter.java:40`

```java
if (securityProperties.getInternalSecret().equals(header)) {
```

**修复**：改用 `MessageDigest.isEqual()` 常量时间比较。

#### S-2.4 SSRF IPv6 绕过

**文件**：`CallbackUrlValidator.java:54-86`

IPv4-mapped IPv6（`::ffff:127.0.0.1`）、link-local（`fe80::`）等地址绕过内网检测。

**修复**：解析 IPv6 地址并阻断 loopback/link-local/unique-local 范围。

#### S-2.5 限流非分布式

**文件**：`ConsoleRateLimitFilter`（内存 sliding window）

多实例部署时各实例独立计数，限流形同虚设。

**修复**：改用 Redis INCR/EXPIRE 原子操作实现分布式限流。

#### S-2.6 Webhook DNS Rebinding

**文件**：`CallbackUrlValidator.java:38`

URL 校验只在注册时执行，实际调度时 DNS 可能已被篡改指向内网。

**修复**：调度时二次 DNS 解析并校验非内网 IP。

---

### Medium（5 项）

| 编号 | 问题 | 文件 |
|------|------|------|
| S-3.1 | JWT 撤销后 8h 窗口内仍有效 | `ConsoleJwtService.java` |
| S-3.2 | 文件下载无限流限制 | `ConsoleFileDownloadController.java` |
| S-3.3 | 异常消息直接返回客户端 | `ConsoleApiExceptionHandler.java` |
| S-3.4 | Actuator 端点未隔离到管理端口 | `application.yml` |
| S-3.5 | approvalId 无格式校验 | `ConsoleFileDownloadController.java` |

### Low（2 项）

| 编号 | 问题 |
|------|------|
| S-4.1 | Webhook payload 未脱敏入库 |
| S-4.2 | DEBUG 日志可能泄露 Authorization header |

---

## 二、并发与数据一致性（20 项）

### Critical（4 项）

#### C-2.1 MANDATORY 传播无编译期保护

**文件**：`TaskDispatchOutboxService.java:51,61`

`@Transactional(propagation = Propagation.MANDATORY)` 仅运行时校验。未来重构若移除调用方事务注解，outbox 写入将抛 `IllegalTransactionStateException` 而非编译错误。

**修复**：在方法 Javadoc 中强标注事务要求；添加架构测试（ArchUnit）断言调用方必须有 `@Transactional`。

#### C-2.3 releaseForDispatch 回滚时序不一致

**文件**：`DefaultPartitionLifecycleService.java:134-156`

partition promoteStatus 成功后 task promoteStatus 失败时调用 `setRollbackOnly()`，但 **DB 写入可能已在同一事务内生效**。事务最终回滚依赖 Spring TX 管理器的正确行为，但 `partition.setPartitionStatus(READY)` 等内存状态已被修改。

**修复**：失败时显式还原内存对象状态，或改用 `REQUIRES_NEW` 子事务 + 补偿。

#### C-4.1 孤儿记录无级联删除

`job_instance` 删除后，`job_partition`、`job_task`、`job_step_instance` 等子表记录无 `ON DELETE CASCADE`，导致孤儿记录无限增长。

**修复**：添加 Flyway 迁移，对 `job_partition.job_instance_id`、`job_task.job_partition_id` 等外键添加 `ON DELETE CASCADE`。

#### C-9.1 idempotencyKey 含 version 导致 crash 后重复执行

**文件**：`TaskDispatchOutboxService.java:150-157`

```java
return task.getTenantId() + ":task:" + task.getId() + ":v:" + version;
```

若 Kafka publish 成功但 DB 事务回滚，下次重试 version 不同 → idempotencyKey 变化 → Worker 视为新消息 → 重复执行。

**修复**：idempotencyKey 不应包含 version；改用 `tenantId:task:{taskId}:instance:{jobInstanceId}` 作为固定幂等键。

---

### High（6 项）

| 编号 | 问题 | 文件 |
|------|------|------|
| C-1.1 | ActiveTaskLeaseRegistry 读写锁语义反转（register 应持写锁） | `ActiveTaskLeaseRegistry.java` |
| C-2.2 | advancePartitionAndInstance 重读 instance 后仍用旧对象做状态机转换 | `DefaultTaskOutcomeService.java:382` |
| C-2.4 | dispatch() 中 markRunning 使用旧 version，并发 launch 冲突 | `DefaultPartitionDispatchService.java:141` |
| C-3.3 | ParseStep stagingFile 为 null 时 deleteQuietly 可能 NPE | `ParseStep.java:124-133` |
| C-4.2 | 双线程同时回报同一 task，第二个静默丢弃而非报错 | `DefaultTaskOutcomeService.java:198-201` |
| C-6.1 | QuotaRuntimeState 无乐观锁，并发写入丢失计数 | `QuotaRuntimeStateService.java:73-96` |

### Medium（9 项）

| 编号 | 问题 |
|------|------|
| C-1.2 | CAS miss counter 本身线程安全但文档缺失 |
| C-1.3 | AbstractWorkerLoop 双重检查锁多余 |
| C-3.1 | MinIO listObjects 提前 break 未关闭底层流 |
| C-3.2 | releaseForDispatch 失败仅 debug 日志，不可见 |
| C-5.1 | 熔断器 halfOpenProbing 用 volatile 非 CAS，多线程可同时探测 |
| C-8.1 | WorkflowDagService joinMode 为 null 时 NPE |
| C-8.2 | 节点参数 Map 未做类型安全检查 |
| C-10.1 | @PostConstruct 验证 ObjectProvider 仅启动时一次 |
| C-10.2 | ShedLock 初始化 Thread.sleep 阻塞启动线程 |

### Low（1 项）

| C-7.1 | version Long 理论上可溢出（实际概率极低） |

---

## 三、架构与设计（23 项）

### High（2 项）

#### A-5.1 batch-defaults.yml 硬编码弱密码

**文件**：`batch-common/src/main/resources/batch-defaults.yml:22-27`

```yaml
password: ${BATCH_PLATFORM_DB_PASSWORD:batch_pass_123}
```

平台库、业务库、MinIO 三处默认弱密码写在共享配置中，所有模块继承。

#### A-6.1 God Class：2207 行

**文件**：`DefaultConsoleTenantConfigPackageExcelApplicationService.java`（2207 行）

单一类处理 8 种 Excel sheet 的解析、校验、upsert、错误收集。圈复杂度极高，无法有效测试。

**修复**：按 sheet 类型拆分为独立的 Parser + Validator + Upserter。

---

### Medium（17 项）

| 编号 | 问题 | 影响 |
|------|------|------|
| A-1.1 | console-api 直接依赖 orchestrator jar | 无法独立部署 |
| A-1.2 | scanBasePackages 扫描整个 `com.example.batch` | 加载无关 Bean |
| A-1.3 | ParseStep 静态 TypeReference 共享可变状态 | 并发初始化风险 |
| A-2.1 | JobInstanceMapper.selectByQuery 无分页 | OOM 风险 |
| A-2.2 | TaskOutcomeService N+1 查询 | 高负载时 DB 过载 |qua
| A-2.4 | /internal/tasks/{taskId}/claim 无限流 | Worker 可 DoS |
| A-3.1 | Outbox 默认单实例处理 | 吞吐瓶颈 |
| A-3.2 | ParseStep 无列数上限 | 恶意宽表 OOM |
| A-3.3 | Worker 业务库无 HikariCP 配置 | 连接耗尽 |
| A-3.4 | TaskAssignment 事务内多次乐观锁 | 高并发锁争用 |
| A-4.1 | 无运行时健康检查（仅启动时） | 依赖故障不可见 |
| A-4.2 | 状态变更缺结构化日志 | 难以追踪 |
| A-4.3 | Kafka 消息无 trace ID 传播 | 跨服务链路断裂 |
| A-5.2 | Flyway 大表 DDL 可能锁表 | 零停机部署风险 |
| A-5.3 | 无 prod/staging profile 配置文件 | 易遗漏环境变量 |
| A-5.4 | 优雅关闭默认 30s 不够 | Worker 任务被中断 |
| A-6.2 | ParseStep 1085 行，4 种格式混在一起 | 难以扩展新格式 |

### Low（4 项）

| 编号 | 问题 |
|------|------|
| A-2.3 | assignWorker 返回完整实体（含大 payload） |
| A-6.3 | KEY_RECORDS 等常量跨模块重复定义 |
| A-6.4 | Worker stage 缺统一基类 |
| A-6.5 | 魔法数字散落在配置和代码中 |

---

## 四、测试与可靠性（10 项）

### Critical（1 项）

#### T-1 OutboxPublishCircuitBreaker 零测试

熔断器是 Outbox 投递的关键保护机制，但 **没有任何测试** 验证：
- 连续失败后是否正确打开
- 冷却后是否进入半开
- 探测成功后是否关闭
- Redis 脚本的正确性

---

### High（4 项）

| 编号 | 问题 | 影响 |
|------|------|------|
| T-2 | console-api 测试覆盖率 15.8%（79 个服务无测试） | 回归风险极高 |
| T-3 | REQUIRES_NEW 事务边界零测试（56 个方法） | 嵌套回滚行为未验证 |
| T-4 | Redis 故障场景零测试 | ShedLock 可能双执行 |
| T-5 | 重试风暴模拟零测试 | 万级 partition 同时失败可能耗尽连接池 |

### Medium（5 项）

| 编号 | 问题 |
|------|------|
| T-6 | 连接池耗尽场景零测试 |
| T-7 | 并发 outbox 推进零测试 |
| T-8 | 混合版本滚动部署兼容性零测试 |
| T-9 | 分区租约过期在部署期间可能导致重复派发 |
| T-10 | 多数测试过度 mock，不验证真实 DB 行为 |

---

## 正面发现（做得好的方面）

| 方面 | 评价 |
|------|------|
| Argon2id 密码哈希 | 业界最佳实践 |
| AES-256/GCM 文件加密 | 加密算法选型正确（密钥管理需加强） |
| MyBatis 全量 `#{}` 参数化 | 无 SQL 注入风险 |
| Outbox 模式 | DB + Kafka 事务解耦设计正确 |
| ShedLock 分布式锁 | 避免定时任务重复执行 |
| 幂等键追踪 | POST 端点要求 Idempotency-Key header |
| 租户隔离 | ConsoleTenantGuard 一致性好 |
| 优雅关闭 + Drain | 停机流程完整 |
| 死信队列 | 毒丸消息不阻塞主队列 |
| 结构化日志 + MDC | 可观测性基础良好 |

---

## 修复优先级建议

### 第一优先级：立即修复（安全 + 数据丢失风险）

1. **S-1.1** JWT 密钥默认值启动校验
2. **S-1.3** Docker Compose 移除硬编码密码
3. **C-9.1** idempotencyKey 去除 version（防重复执行）
4. **S-2.2** 共享 Token 认证绕过修复
5. **S-2.3** 内部密钥改用常量时间比较
6. **C-4.1** 添加级联删除（孤儿记录）

### 第二优先级：紧急（High 级别中影响最大的）

7. **C-2.2** advancePartitionAndInstance 使用 freshInstance 做状态转换
8. **C-6.1** QuotaRuntimeState 添加乐观锁
9. **S-2.4** SSRF IPv6 绕过修复
10. **T-1** OutboxPublishCircuitBreaker 补充测试
11. **A-5.1** 移除 batch-defaults.yml 中的默认密码

### 第三优先级：计划（Medium 级别系统性改进）

12. **A-2.1** 所有列表查询添加分页
13. **A-4.1** 运行时健康检查（DB/Kafka/Redis/MinIO）
14. **A-6.1** 拆分 2207 行 God Class
15. **A-5.4** 优雅关闭超时调大（Worker 300s）
16. **T-2** console-api 测试覆盖率提升到 50%

---

## 变更记录

| 日期 | 变更内容 |
|------|----------|
| 2026-04-15 | V2 初版，四维度 69 项问题全量录入 |
| 2026-04-17 | 核查校准：S-2.5 标注为误报（已有 Redis 分布式限流）；S-2.6 已修复（DnsResolveGuard）；A-6.1 已拆分（2207→783 行）；A-6.2 已拆分（1085→274 行 + 5 FormatParser）；A-4.3 已完成（Kafka observation）；部分文件路径因重构变更，以 fix-report-v2.md 为准 |
