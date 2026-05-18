# 上线前后端深度排查报告 — 2026-05-18

> 4 路并行审计:**安全/认证/多租** + **数据/migration/事务** + **异步/Kafka/并发** + **配置/部署/可观测**。合计 21 项 finding(P0×6 / P1×10 / P2×5),P0 全部需在上线 cutover 前修复。

## 摘要

| 严重度 | 数量 | 是否阻断上线 |
|---|---|---|
| **P0** | 6 | ✅ 阻断,必须 cutover 前修 |
| **P1** | 10 | 强烈建议本 release 前修 |
| **P2** | 5 | 后续 sprint 跟进 |

---

## P0 — 上线阻断

### P0-1 · 认证 filter 存在 `enabled=false` 静默绕过(prod 守护未覆盖)

- **位置**:`batch-console-api/src/main/java/com/example/batch/console/support/auth/ConsoleAuthenticationFilter.java:89`
- **现状**:`if (!properties.isEnabled() && !batchSecurityProperties.isBypassMode())` —— 只要 `batch.console.security.enabled=false` 即放行所有 `/api/console/**`,JWT / bypass / SSE ticket 全跳过
- **风险**:`@PostConstruct` prod 守护只校验 `bypass-mode`,**不覆盖** `enabled` 字段。staging/preprod 误改 Helm values 即裸奔
- **修复**:删该开关统一走 `bypass-mode`;或在 prod profile 守护内补一行 `enabled=false` 同样抛 `IllegalStateException`

### P0-2 · V124 在大表上 CREATE UNIQUE INDEX 未用 CONCURRENTLY

- **位置**:`db/migration/V124__r3_constraint_hardening.sql` 第 24/33/73/82/87/93/99/104 行
- **现状**:Flyway 默认事务模式建唯一索引,对 `job_partition` / `file_record` / `job_task` / `workflow_run` 持 ShareLock 阻塞写
- **风险**:`file_record` 是 IMPORT 每次写入的热表,`job_partition` 是 CLAIM 链路核心表。迁移期内并发写排队 → 连接池耗尽
- **修复**:拆 `V124_1__r3_indexes_nontransactional.sql`,头部加 `-- flyway:executeInTransaction=false`,内部用 `CREATE UNIQUE INDEX CONCURRENTLY`。V124 仅保留 DDL 部分

### P0-3 · V127 `VALIDATE CONSTRAINT` 在 Flyway 事务内串行 7 张表

- **位置**:`db/migration/V127__validate_pending_constraints.sql:21-42`
- **现状**:同一事务连续 `VALIDATE` 7 张表,持 SHARE UPDATE EXCLUSIVE 全扫,持锁时间叠加
- **风险**:Flyway 在服务启动时自动触发,无法选低峰窗口;表越大持锁越长
- **修复**:V127 加 `-- flyway:executeInTransaction=false`,或拆为独立 DBA 运维步骤

### P0-4 · `@Async` 走 Spring 默认 `SimpleAsyncTaskExecutor`(无界线程)

- **位置**:`batch-console-api/.../support/push/ConsolePushSender.java:82,90`(以及任何其他 `@Async` 调用方)
- **现状**:`batch-console-api` + `batch-common` 全模块**无** `@EnableAsync` / `AsyncConfigurer` / 命名 `taskExecutor` Bean
- **风险**:每次 `@Async` 调用新建线程,告警/批量推送风暴时 OS 线程耗尽 → OOM
- **修复**:加 `@EnableAsync` + 注册有界 `ThreadPoolTaskExecutor`(corePoolSize=4 / max=16 / queueCapacity=200);所有 `@Async` 显式指定 `@Async("pushTaskExecutor")`

### P0-5 · `values-prod.yaml` 缺 `startupProbe`(冷启动 CrashLoop 风险)

- **位置**:`helm/values-prod.yaml` 全文无 `startupProbe`
- **现状**:`values.yaml:460` 注释已警告 Spring lazy-init 端口 bind 晚于 `livenessProbe initialDelaySeconds:40`。`examples/values-startup-probes.yaml` 备有 overlay 模板但 prod 未引入
- **风险**:prod 含 HPA(workerImport maxReplicas=10),扩容冷启动频率高,直接暴露误杀风险
- **修复**:把 `examples/values-startup-probes.yaml` 内容合并进 `helm/values-prod.yaml`(20s initial + 18 failureThreshold = 200s 窗口)

### P0-6 · `staging-gate.yml` observability 探活端口错配(8080/8082 vs 实际 18080/18082)

- **位置**:`.github/workflows/staging-gate.yml:46`(`BATCH_OBSERVABILITY_BASE_URLS`)
- **现状**:URL 写 `:8080` / `:8082`,但 Helm Service 实际暴露 `consoleApi.port:18080` / `orchestrator.port:18082`
- **风险**:staging 巡检脚本静默跳过或健康检查永远失败,门禁形同虚设
- **修复**:URL 改为 `:18080` / `:18082`,或改用 Service 名称走 cluster DNS

---

## P1 — 强烈建议本 release 前修

### P1-1 · 登录响应 body 仍明文返回 `accessToken`,抵消 HttpOnly cookie 防 XSS

- **位置**:`batch-console-api/.../web/ConsoleAuthController.java:45-53` + `ConsoleAuthTokenResponse.java:7`
- **风险**:前端任何 JS 缓存到 localStorage → D7 Stage B 改造作废
- **修复**:置 `accessToken=null` 或删字段;若仍兼容老客户端,设明确截止版本登记 ADR

### P1-2 · Helm `internalSecret` / `consoleJwtSecret` 空串可绕过 `required`

- **位置**:`helm/batch-platform/values.yaml:54-56` + `helm/values-prod.yaml:37-38`
- **风险**:CI 用 `--set security.internalSecret=` 传空串 `required` 不拦截;运行时 `validateNotPlaceholder` fail-fast,但 Pod 全崩才告警晚
- **修复**:CI deploy pipeline 加预检脚本;或 `staging-gate.yml` 显式注入 `BATCH_INTERNAL_SECRET` / `BATCH_CONSOLE_JWT_SECRET` 并 `--set`

### P1-3 · V124 DROP 旧 UNIQUE → CREATE 新索引存在唯一性空窗

- **位置**:`db/migration/V124__r3_constraint_hardening.sql:22-26,30-35,79-89`
- **风险**:P0-2 修复(拆非事务)后空窗变真实,期间允许重复 `idempotency_key`
- **修复**:非事务模式下**先建后删** —— 先 `CREATE UNIQUE INDEX CONCURRENTLY`,确认建好再 `DROP CONSTRAINT`

### P1-4 · V124 `file_record` 自连接 DELETE 无 `LIMIT`,大表长事务风险

- **位置**:`db/migration/V124:64-70`
- **风险**:`DELETE ... USING ... WHERE a.id<b.id` 全表自连接,行数百万级时长事务持锁、无法中断
- **修复**:迁移前 DBA 预检 `SELECT COUNT(*) FROM batch.file_record WHERE checksum_value IS NULL`,>10 万则分批 DELETE LOOP 在维护窗口跑;migration 仅保留索引创建

### P1-5 · SSE 订阅无连接数上限,内存与心跳任务无界增长

- **位置**:`batch-console-api/.../infrastructure/realtime/ConsoleRealtimeEventHub.java:57,70`
- **风险**:`SseEmitter` timeout=0(无限);proxy 中断/浏览器崩溃不触发 FIN 时连接永驻;`CopyOnWriteArrayList` 每次 `publish` snapshot 全量,GC 压力随连接数上升
- **修复**:`subscribe` 入口加全局上限(例 1000)→ 超出 503;`SseEmitter` 设 5 min timeout 让客户端重连;或同 `(tenantId, stream)` 限并发

### P1-6 · `TriggerOutboxRelay` GIVE_UP 仅 counter 无 ERROR 日志/Alert 规则

- **位置**:`batch-trigger/.../application/TriggerOutboxRelay.java:271,293`
- **风险**:GIVE_UP = 调度请求**永久丢失**,P0 级业务损失,但只有 Prometheus counter 被动监控
- **修复**:`markFailed(...GIVE_UP...)` 后补 `log.error`;`docs/runbook` 登记告警规则 `increase(batch_trigger_outbox_give_up_total[5m]) > 0`

### P1-7 · `TriggerLaunchConsumer` 业务异常 rethrow → `DefaultErrorHandler` 10 次后静默跳过

- **位置**:`batch-orchestrator/.../application/trigger/TriggerLaunchConsumer.java:133,154`
- **风险**:代码注释假设 SeekToCurrentErrorHandler,但 Spring Kafka 2.9+(Boot 3.x+)默认已变 `DefaultErrorHandler`,10 次后跳过且无告警 → 业务数据丢失
- **修复**:在 `OrchestratorKafkaConsumerConfiguration.TRIGGER_LISTENER_FACTORY` 显式配 `DefaultErrorHandler`,分类可重试/不可重试,配 DLQ topic 或 `DeadLetterPublishingRecoverer`

### P1-8 · `application-prod.yml` 缺 Hikari `maximum-pool-size` 配置

- **位置**:`batch-common/src/main/resources/application-prod.yml` 全文无 `hikari` 块
- **风险**:`values.yaml:223` 配了 `platformDb.maxPoolSize:50` 并注入 ConfigMap env,但 `batch-defaults.yml` 中 `spring.datasource.hikari.maximum-pool-size` 未引用该 env → 走 HikariCP 默认 10
- **修复**:`batch-defaults.yml` 加 `spring.datasource.hikari.maximum-pool-size: ${BATCH_PLATFORM_DB_MAX_POOL_SIZE:10}`,与 ConfigMap 变量名对齐

### P1-9 · REPORT 路径无 `@Timed` 埋点,Grafana 延迟 panel 无数据

- **位置**:`batch-orchestrator/.../application/service/task/DefaultTaskOutcomeService.java`
- **现状**:仅有 Counter 记录 CAS miss,无端到端计时;CLAIM 路径 `TaskControllerApplicationService:39-43` 有 `@Timed`,不对称
- **修复**:REPORT 入口加 `@Timed("batch.task.report")` + tag(tenant_id / job_type)

### P1-10 · Mapper `tenant_id` 用可选 `<if>` 块,防御深度不足

- **位置**:`batch-console-api/src/main/resources/mapper/WorkflowDefinitionMapper.xml:21-23,49-51`
- **现状**:`tenantId=null` 时 SQL 无 `tenant_id` 过滤 → 跨租户全表扫(当前调用链经 `ConsoleTenantGuard` 兜底)
- **修复**:`<if>` 改强制 `and tenant_id = #{tenantId}`,query record 工厂方法断言 tenantId 非空,与 `JobDefinitionMapper.xml` 对齐

---

## P2 — 后续 sprint 跟进

- **P2-1** · V123 索引掩盖 `DefaultWorkflowDagService` N+1 根因,高并发 DAG(>20 节点 + >50 run)仍累积往返 — 登记技术债
- **P2-2** · `ConsoleRealtimeEventHub` 心跳走单线程 ScheduledThreadPool,SSE 阻塞会拖延全部心跳 — 改为统一全局批量扫描或扩 2-4 线程
- **P2-3** · `OutboxPollScheduler` DYNAMIC 分片 rebalance 短暂重叠依赖业务幂等兜底 — 文档化,确认消费端有 `(tenant_id, dedup_key)` 唯一约束
- **P2-4** · prod `otelCollector.enabled=false` 但 `batch-defaults.yml` 默认 OTLP endpoint 指向 `otel-collector:4318` — `values-prod.yaml` 显式覆盖或确认外部 Collector
- **P2-5** · `staging-gate.yml` 已通过验证,无 `-Dmaven.test.skip=true` 残留,`partial`/`full` 分支逻辑正确

---

## 修复执行记录(2026-05-18)

| # | 状态 | commit / 说明 |
|---|---|---|
| P0-1 | ✅ 修 | ConsoleSecurityProperties + @PostConstruct prod 守护拦截 enabled=false |
| P0-2 | ⏭ 接受 | V124 已部署 + fresh prod 空表 → runbook §9 规则已存在 |
| P0-3 | ⏭ 接受 | V127 同上,空表 VALIDATE 瞬时 → runbook §1 |
| P0-4 | ✅ 修 | ConsoleAsyncConfiguration + ThreadPoolTaskExecutor "pushTaskExecutor",@Async 全部显式指定 |
| P0-5 | ✅ 修 | values-prod.yaml 6 个模块加 startupProbe(20s+18×10s = 200s 窗口) |
| P0-6 | ✅ 修 | staging-gate.yml 端口 8080/8082 → 18080/18082 |
| P1-1 | ✅ 修 | ConsoleAuthTokenResponse#withoutToken() + @JsonInclude(NON_NULL),body 不再带 token |
| P1-2 | ✅ 修 | secret.yaml 加 length ≥ 16 守卫(required + len 双重) |
| P1-3/4 | ⏭ 接受 | 同 P0-2/P0-3 |
| P1-5 | ✅ 修 | ConsoleRealtimeProperties.maxSubscriptions=1000 + emitterTimeout=30m,hub 加上限守卫 |
| P1-6 | ✅ 修 | TriggerOutboxRelay GIVE_UP 补 ERROR 日志(含 attempts/topic/error) |
| P1-7 | ⏭ 误报 | OrchestratorKafkaConsumerConfiguration:97 已显式配 DefaultErrorHandler + not-retryable |
| P1-8 | ✅ 修 | configmap 加 BATCH_CONSOLE_PRIMARY_POOL/REPLICA_POOL,values 暴露 primaryPool/replicaPool |
| P1-9 | ✅ 修 | DefaultTaskOutcomeService.applyTaskOutcome 加 @Timed("batch.task.report") |
| P1-10 | ✅ 修 | WorkflowDefinitionMapper.xml tenant_id 改强制条件 + WorkflowDefinitionQuery canonical 拒绝 null/blank |
| P2-1 | 📝 技术债 | DefaultWorkflowDagService N+1 登记,等 DAG ≥ 20 节点 + 50 并发 run 时治理 |
| P2-2 | 📝 技术债 | ConsoleRealtimeEventHub 心跳单线程,等连接数 > 200 改为统一全局批扫 |
| P2-3 | 📝 文档化 | OutboxPollScheduler DYNAMIC rebalance 重叠依赖业务幂等兜底,已注释 |
| P2-4 | ✅ 修 | values-prod.yaml 显式 otelCollector.enabled=false + 文档注释 |
| P2-5 | ⏭ 误报 | staging-gate 验证 OK,无 maven.test.skip 残留 |

## 上线 cutover 检查表

- [ ] P0-1 删 `enabled` 开关或补 prod 守护
- [ ] P0-2 拆 V124 索引到非事务 migration + CONCURRENTLY
- [ ] P0-3 V127 加 `executeInTransaction=false`
- [ ] P0-4 `@EnableAsync` + 有界线程池,所有 `@Async` 显式 executor
- [ ] P0-5 合并 startup-probes overlay 到 `values-prod.yaml`
- [ ] P0-6 修 staging-gate observability 端口 8080/8082 → 18080/18082
- [ ] 跑 `validate-seed-scenarios.sh` 全量 + IT + e2e
- [ ] 跑 `dependency-check` / `gitleaks` 确认无新增 CVE/secret
- [ ] DBA 在 staging 灰度跑 V124/V127 验证锁等待
- [ ] Grafana 看板对照新增 `@Timed` / counter 指标名
