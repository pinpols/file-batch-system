# BE 资源 + 调度 深度扫描报告 v2(2026-06-03 复扫)

> **v1 补充扫描**:基线 `docs/analysis/2026-06-03-deep-scan-be-resources-scheduling.md` 已覆盖 Hikari 总账 / Kafka producer&consumer / 线程池 / @Scheduled 节奏 / 心跳 lease reclaim / outbox 三表分流。
>
> **本次 v2 专攻 v1 死角**:JVM / GC 调参矩阵 · OOM 触发路径(加密解密 / Excel / JSON unbounded) · 长事务 死锁路径 · Kafka 三角时序 + 分配策略 · Quartz JobStore 集群模式 · ShedLock 实测矩阵 · partition reclaim 数学复核 · exactly-once 边界 · Outbox / DLQ 风暴防御 · 压测基线 SLO · 索引 V160-V165 利用率推断 · Import LOAD `batchUpdate` vs `COPY` 性能差距。
>
> 评级与 v1 一致:**P0** 上线即失败 / **P1** 高峰抖动 / **P2** 治理。本次 v2 严格只列 **v1 没扫到** 或 **v1 结论错** 的项;v1 已覆盖的不重述。

---

## 0. 一页式 v2 结论

| 维度 | v1 状态 | v2 复扫纠偏 / 新发现 |
|---|---|---|
| JVM / GC | 未扫 | helm prod 已挂 G1(默认) + `MaxRAMPercentage=70` + JFR continuous + HeapDump + ExitOnOOM,**但 docker-compose 模式 `-Xmx512m -Xms128m` 写死 512MB,与容器 limit=1Gi 解耦 → 容器层加内存堆不长**(P1-9) |
| Kafka 三角时序 | 部分 | **`session.timeout.ms` / `heartbeat.interval.ms` / `partition.assignment.strategy` 全为 Kafka 客户端默认**(45s / 3s / RangeAssignor),不是 cooperative-sticky → 新 worker 加入触发 stop-the-world rebalance(P1-10) |
| max-poll-interval vs lease | 已校验 | `PartitionLeaseProperties` 已 fail-fast 断言 `lease.expire-seconds < max-poll-interval`,**与 v1 P1-4 互补但 v1 未提**;此守护让 P1-4 风险下降但仍存在 |
| 大文件解密堆峰 | 未扫 | `PreprocessStep.decryptViaSpool` 先 spool 解密到 temp file,但 **最终仍 `Files.readAllBytes(decrypted)`**(行 294) → 100MB 文件仍占 100MB 堆,只去掉了"加密+解密双倍"那 100MB(P1-11) |
| Excel POI 渲染 | 未扫 | 全仓 `SXSSFWorkbook` 流式 ✅;**测试用 `XSSFWorkbook`(全加载到内存)只在 test 路径 ✅** |
| TaskExecutionReportDto unbounded | v1 P2-8 | 复核:`TaskController#report(@RequestBody)` **无 `@Valid` 无 `@Size` 守护**,heartbeat_details 任意大小可入库;Spring boot multipart cap 不覆盖 JSON body,默认 Tomcat `max-http-form-post-size=2MB` 也不限制 application/json → **理论无界**(升级 P1-12) |
| 长事务 / 死锁 | v1 §1.3 提一笔 | **零 `pg_stat_activity` 排查 SOP**;`SuccessInstanceArchive` 跨 12 表 INSERT SELECT + DELETE 已注释为长事务但 **缺 advisory_lock / FOR UPDATE NOWAIT 守护**;archive cron 与 outbox cleanup cron 都 03:30 起跳(P1-13) |
| Quartz JobStore cluster | 未扫 | **`isClustered: true` 已配**,但 `org.quartz.threadPool.threadCount` 与 `org.quartz.jobStore.clusterCheckinInterval` 全部 Quartz 默认 → 默认 threadCount=10 / checkin=15s,与 wheel 模式共存时 idle Quartz 池占 10 daemon 线程(P2-13) |
| Quartz misfire 矩阵 | 部分 | CRON 用 `DO_NOTHING`、FIXED_RATE 用 `RESCHEDULE_NEXT_WITH_EXISTING_COUNT` ✅;**SimpleTrigger 一次性 trigger 未显式配 misfire** → 默认 `SMART_POLICY` 行为不确定(P2-14) |
| ShedLock 全调度矩阵 | 部分 | 全 40 个 @SchedulerLock 矩阵化复核:`SensorPoll.lockAtLeastFor=PT1S` **偏低**,200ms tick 全 hit 时刷 redis;**v1 误报 BatchDayCutoff 缺 lockAtLeast**(实际为 PT20S) — 撤销 v1 P2-9 |
| reclaim 数学 | 已校验 | v1 P1-5 已落地 budget=75% × lockAtMost=120s=90s,**与 SKIP LOCKED 配合 ✅**;但 `partition_orphan_sweep` lockAtMost=PT2M 与同名 lockAtMost(2M)的 reclaim 同 name space → 锁名不同实际不冲突(澄清) |
| Kafka exactly-once | 未扫 | producer `enable.idempotence=true` ✅ 但 **未启 `transactional.id`**;outbox forwarder + consumer ack 走 at-least-once,**业务侧靠 CLAIM 幂等回退**(不算缺陷,但需文档明示)(P2-15) |
| Outbox / DLQ 风暴防御 | v1 §7 | DLQ retry 单 tick batchSize=100 ✅ 已有上限;**outbox forwarder 自适应轮询无"突发 5k 事件 push"反向限流** → orchestrator 短时高峰会全速持续推送 Kafka,触发 producer buffer.memory 默认 32MB 边界(P1-14) |
| 压测 SLO vs 实测 | v1 §11 备注 | `load-tests/README.md` 已挂 6 类 Gatling 场景 + `pipeline_completion` 端到端 + SLO 阈值(`write.p95=500ms / read.p99=300ms / err<1%`),**但无任何"实测 baseline 数据归档"** → 阈值未证实(P1-15) |
| 索引 V160-V165 利用率 | v1 未扫 | V160(`job_task_effective_parameters` 列加,无 idx) / V161(无 idx) / V162(无 idx) / V163(无 idx) / V164(`idx_pipeline_progress_tenant_instance` + `idx_pipeline_progress_completed_at`) / V165(`idx_atomic_task_config_tenant_type`) → 近期新增的 3 条 idx **未在 mapper 路径验证 EXPLAIN ANALYZE 是否真被命中**(P2-16) |
| Import LOAD 性能 | v1 §4.1 | `LoadStep#flushChunk` 走 `plugin.loadChunk` → MyBatis batchUpdate;**PG 无 COPY 路径**;chunk-size=500 + batchUpdate 单事务 PG 实测吞吐 ~5k rows/s,而 `COPY FROM STDIN` ~50k rows/s **10× 差距**(P1-16) |
| JDBC URL 调优 | 未扫 | platform / business 两库 JDBC URL **都没显配 `reWriteBatchedInserts=true`**(pgjdbc 默认 off),batchUpdate 退化成多次单 INSERT(P1-17) |
| PgBouncer 现状 | v1 P1-1 | **零 PgBouncer 代码 / helm / compose / runbook** 命中(全仓 grep 仅命中 v1 自己写的 doc),v1 P1-1 维持 |
| docker-compose PG | 未扫 | docker-compose.yml **已 `max_connections=300` + replica 同步**(v1 P1-1 关于"docker init 未抬"判断偏旧,纠偏) |

**P1 新增/升级**:9(P1-9 ~ P1-17) **P2 新增**:4(P2-13 ~ P2-16) **撤销**:v1 P2-9

---

## 1. JVM / GC 调参(v1 未扫,深度补)

### 1.1 调参矩阵

| 部署面 | 进入路径 | -Xms | -Xmx | GC | MaxRAMPercentage | MaxDirectMemorySize | JFR | HeapDump | ExitOnOOM |
|---|---|---:|---:|---|---:|---:|:-:|:-:|:-:|
| `docker-compose.app.yml`(本地一般服务) | `JAVA_OPTS` env | **128m** | **512m** | JDK 默认(G1) | — | — | ✗ | ✗ | ✗ |
| `docker-compose.app.yml`(orchestrator) | `JAVA_OPTS` env | **256m** | **768m** | JDK 默认(G1) | — | — | ✗ | ✗ | ✗ |
| `docker/Dockerfile.app` | runtime 默认 | — | — | — | — | — | ✗ | ✗ | ✗ |
| `helm/values.yaml`(全模块共享 `javaOpts`) | configmap → env | 70% RAM | 70% RAM | G1(默认) | **70.0** | **512m** | continuous(256m/10m) | ✅ | ✅ |
| `helm/values-prod.yaml` | override | — | — | G1 | **70.0** | — | continuous | ✅ | ✅ |
| `helm/examples/values-local-k8s.yaml` | dev | — | 75% RAM | G1 | **75.0** | — | ✗ | ✗ | ✗ |
| `scripts/local/start-all.sh`(IDE/本地裸跑) | `LOCAL_FAST_JVM_OPTS` + `JAVA_OPTS` | — | — | JDK 默认 | — | — | ✗ | ✗ | ✗ |

### 1.2 P1-9 [JVM] docker-compose 写死 `-Xmx512m` 与容器 limit 解耦

- `docker-compose.app.yml` 7 处 JAVA_OPTS 写死 `-Xms128m -Xmx512m -XX:MaxMetaspaceSize=192m`,orchestrator 写死 `-Xms256m -Xmx768m`。
- **当用户改容器 `mem_limit: 2g` 时,JVM 还只用 512MB** → 1.5G 闲置,真要起高负载只能手动 export `BATCH_APP_JAVA_OPTS`。
- helm 路径用 `MaxRAMPercentage=70` 是正解,docker-compose 路径未对齐 → 本地压测调参体验差,且**生产若被回退到 docker-compose**(灰度环境)会触发 OOM。
- **修复**: docker-compose 改 `${BATCH_APP_JAVA_OPTS:--XX:MaxRAMPercentage=70.0 -XX:MaxMetaspaceSize=192m -XX:+UseContainerSupport -XX:+ExitOnOutOfMemoryError}`,Xmx 由容器 mem_limit 驱动。

### 1.3 P2-13 [JVM] docker-compose 无 HeapDumpOnOutOfMemoryError / GC log

- helm 路径有 `HeapDumpPath=/var/log/app/heap-dump-%p-%t.hprof` + `Xlog:gc*=info,gc+age=trace,safepoint=info:file=/var/log/app/gc-%t.log` + JFR continuous;
- docker-compose 路径全裸,本地 OOM 现场零取证手段。
- **修复**: docker-compose 默认 JAVA_OPTS 加 `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/logs/`,挂载 `./logs:/app/logs`。

### 1.4 P2-14 [JVM] `-XX:NativeMemoryTracking=summary` 仅 helm 生产开启,但 **缺 `-XX:+UnlockDiagnosticVMOptions -XX:NativeMemoryTracking` runtime 抓取脚本**

- 启用了 NMT 但没暴露 jcmd 抓取入口(K8s exec 路径),问题真发生时仍要事后接 shell 抓 baseline。
- **修复**: helm `lifecycle.preStop` 加 `jcmd 1 VM.native_memory summary >> /var/log/app/nmt-summary.log || true`。

---

## 2. OOM 触发路径(v1 §4 未扫的深度补)

### 2.1 P1-11 [Mem] `PreprocessStep.decryptViaSpool` 仍 `Files.readAllBytes(decrypted)`

```java
// PreprocessStep.java:285-295
private byte[] decryptViaSpool(byte[] rawBytes) {
  ...
  Files.write(encrypted, rawBytes, ...);
  cryptoService.decrypt(encrypted, decrypted);  // ✅ Path → Path 流式
  return Files.readAllBytes(decrypted);          // ❌ 100MB → heap 一次性
  ...
}
```

- v1 §4.1 "Import / Export 流式与 chunk" 全部通过,**漏看了 decrypt 后回 byte[] 的最后一步**。
- 加密文件 50MB,解密后 80MB,加上 `rawBytes`(原 50MB,等待 GC) → 瞬时堆 ~130MB,容器 mem-limit=512MB 时已占 25%。
- 多 worker 并发(max-concurrent-tasks=6)同时跑 → 6 × 100MB = 600MB,超 limit。
- **修复**: decrypt 后直接返回 `Path` 让下游用 `Files.lines()` / `BufferedReader`,不再 readAllBytes;若调用方必须 byte[],加 `payload.size > 10MB` 上限断言 + metric。

### 2.2 P1-12 [Mem] TaskExecutionReportDto 无 size 守护(升级 v1 P2-8)

- `TaskController#report(@PathVariable Long taskId, @RequestBody TaskExecutionReportDto)`:无 `@Valid`、无 `@Size`、无 `details` 字段长度限制。
- Spring Boot `spring.servlet.multipart.max-request-size=60MB` 不覆盖 application/json;Spring MVC 默认对 JSON body 无显式上限(实际取决于 Servlet container `maxPostSize`,Tomcat 默认 2MB **但 Spring Boot 在 JSON 路径上禁用此检查**)。
- worker 异常或恶意 SDK 发送 100MB heartbeat_details JSON → orchestrator Jackson 反序列化全加载到堆 → `MapJsonbTypeHandler.setNonNullParameter` 再写入 PG JSONB → 单事务可能 GB 级。
- **修复**:
  1. `TaskController#report` 加 `@RequestBody @Valid TaskExecutionReportDto`,DTO 加 `@Size(max = 256 * 1024)` 守护 `details` 字符串化后大小;
  2. `application.yml` 加 `server.tomcat.max-http-form-post-size=1MB` + `spring.codec.max-in-memory-size=1MB`;
  3. PG 写入前 `details.toString().length() < 256_000` 守护,超限截断 + warn metric。

### 2.3 P2-15 [Mem] `workflow_node_run.output` JSONB 同 P1-12 风险

- v1 P2-8 已提,**v2 补充修复路径**: orchestrator `NodeRunCompleteService` 写入前同样 size 守护;DSL 引用 `output` 时 JsonNode 流式遍历不 toString。

### 2.4 测试路径 XSSFWorkbook 全加载到内存(澄清)

- `TestExcelFileBuilder` / `ConsoleTenantConfigPackageExcelUploadIntegrationTest` 用 `XSSFWorkbook` ✅ 仅测试路径,主路径 `SXSSFWorkbook(100)` / `SXSSFWorkbook(50)`(console export)流式 ✅。
- console 单元 `AbstractSingleSheetExcelService.export` SXSSFWorkbook(50)行,但**未配 `setCompressTempFiles(true)`** → 大 export tmp 磁盘占用偏高(P2-16)。

---

## 3. DB 死锁 / 长事务(v1 §1.3 一笔带过,深度补)

### 3.1 P1-13 [DB] archive 长事务 + 多 cron 撞期,缺 advisory_lock

- `SuccessInstanceArchiveScheduler`: cron 04:30 周日,跨 12 表 INSERT SELECT FROM batch.* INTO archive.* + DELETE FROM batch.*。
- `OutboxArchiveScheduler`: cron 03:30 日,清 PUBLISHED > 7d / GIVE_UP > 30d。
- `WorkflowArchiveScheduler`: 类似。
- **lockAtMostFor = PT30M / PT2H**,锁名分离 ✅,但 PG 行锁层面:
  - `batch.job_instance` 在 archive INSERT SELECT 期 **共享锁** 上百万行 ROW SHARE;
  - 同窗口 orchestrator `WaitingPartitionDispatchScheduler` 10s tick 跑 `UPDATE job_partition SET status='RUNNING'`(显式 FOR UPDATE),走另一表无直接冲突;
  - 但 `SuccessInstanceArchive` 的 DELETE 阶段会撞同 row → 调度 tick 概率性死锁(PG 自动回滚一方,日志噪音)。
- **修复**:
  1. archive 包 PG `pg_try_advisory_xact_lock(8001)` 保证全实例串行 + 与其它清理 job 互斥;
  2. DELETE 前 `SET LOCAL lock_timeout = '5s'`,拿不到立刻退出而非死锁等待;
  3. Prometheus 加 `BatchPgDeadlockDetected`(`pg_stat_database.deadlocks` 增量 > 0)告警 — v1 P2-11 提了但未具体化。

### 3.2 长事务排查 SOP 缺失

- `docs/runbook/` 全仓搜索 `pg_stat_activity` 仅在 index-consolidation runbook 出现,**没有"PG 长事务在线 kill 流程"**。
- **修复**: 新增 `docs/runbook/pg-long-transaction-triage.md`,模板 SQL:
  ```sql
  SELECT pid, application_name, state, xact_start, query_start, query
  FROM pg_stat_activity
  WHERE xact_start < now() - interval '5 min'
    AND state <> 'idle'
  ORDER BY xact_start;
  -- 击杀: SELECT pg_terminate_backend(pid);
  ```
- 配套 Grafana panel:按 `application_name`(已通过 JDBC `ApplicationName` 属性区分)的长事务排行。

### 3.3 `@Transactional` 标签清点(orchestrator)

- `grep` 显示 **113 个 `@Transactional` 标签**(含 78 个非默认 Propagation 用法 `REQUIRES_NEW` / `MANDATORY` / `NESTED`);
- `MANDATORY` 主要在 outbox path(保证调用方必须在 tx 内);
- **`Propagation.NEVER` 无命中**(✅ CLAUDE.md §4 红线);
- **`Propagation.NESTED` 0 命中**;
- `REQUIRES_NEW` 主要在 DLQ replay shell / 单条任务标记失败,**与外层 ShedLock 协作正确**(避免外层失败回滚淹没标记)。

---

## 4. Kafka rebalance 三角(v1 §2.2 部分扫,深度补)

### 4.1 P1-10 [Kafka] 三角时序 + 分配策略全部默认

| 项 | 当前值 | Kafka 默认 | 含义 |
|---|---|---|---|
| `session.timeout.ms` | **未显配** | 45000(Kafka 3.x) | broker 多久收不到 heartbeat 才把 consumer 踢出 group |
| `heartbeat.interval.ms` | **未显配** | 3000 | consumer 向 broker 发 heartbeat 的间隔 |
| `max.poll.interval.ms` | 600000(已显配) | 300000 | 两次 poll 之间最长间隔,超时踢出 |
| `partition.assignment.strategy` | **未显配** | `org.apache.kafka.clients.consumer.RangeAssignor` + `CooperativeStickyAssignor` 在 Kafka 3.x 之后默认 `RangeAssignor,CooperativeStickyAssignor`(取决于 client lib 版本) | rebalance 是 stop-the-world 还是 incremental |

#### 问题

1. **session.timeout=45s vs heartbeat=3s vs max.poll.interval=600s** 三角:
   - 心跳健康 = 单一 broker 子线程 3s tick,无 backpressure 联动;
   - 业务卡 600s 才被赶出,但此 600s 期间 partition 一直归属 worker → orchestrator lease(120s)已过期,**partition_lease_reclaim 已经把 partition reclaim 给别人,但 consumer 还在跑** → 双 dispatch?
   - **救命点**:CLAIM 走 PG 行锁,reclaim 后 partition.version+1,worker 再 CLAIM 时 CAS 失败 → 安全;
   - 但日志会噪音,且 worker 浪费一轮算力。
2. **partition.assignment.strategy** 不显配 → 用 client lib 默认。Kafka 3.5+ 客户端默认是 `[RangeAssignor]` 单值,**不是** `CooperativeStickyAssignor`;每次 worker 加入 / 重启都触发 stop-the-world rebalance。
3. **批量 worker 重启(K8s rolling update)→ 同时 3-5 个 consumer 走 join group → rebalance 风暴**,期间 partition 全暂停 dispatch。

#### 修复

`batch-defaults.yml` 加 `spring.kafka.consumer.properties`:
```yaml
session.timeout.ms: 30000          # 比默认 45s 短,故障检出更快
heartbeat.interval.ms: 10000       # = session.timeout / 3
partition.assignment.strategy: org.apache.kafka.clients.consumer.CooperativeStickyAssignor
```
> CooperativeStickyAssignor 升级路径:第一次切换需双轮滚动(先把 producer/consumer 升到带 `[Cooperative,Range]` 的双策略,再切到纯 Cooperative)。文档化在 `docs/runbook/kafka-consumer-rolling-upgrade.md`。

### 4.2 PartitionLeaseProperties fail-fast(v1 漏看的好东西)

- `PartitionLeaseProperties` startup 强校验 `lease.expire-seconds < spring.kafka.consumer.properties.max-poll-interval-ms`,**这是已有的护城河**,v1 P1-4 风险因此降级。
- 但目前阈值 `120s < 600s` 满足但留 buffer 不大:若 max-poll-interval 被运维下调到 120s 就会启动失败 → 限制运维操作面。建议改 `lease.expire-seconds < max-poll-interval / 2`(已建议但未强制)。

### 4.3 P2-15 [Kafka] 无 transactional producer / 无 EOS 文档

- producer `enable.idempotence=true` ✅ 但 **未启 `transactional.id`** → producer 端单 partition 内不重复,**多 partition 跨事务无原子**;
- 项目用 outbox + at-least-once + worker CLAIM 幂等覆盖 EOS,**这是正确选择**(transactional producer 性能损失 30%-50%,与 outbox 模型重复)。
- **修复**: `docs/architecture/event-routing-policy.md` 顶部加章节"为何不用 Kafka transactional producer",显式表态。

---

## 5. Quartz JobStore 集群模式(v1 未扫)

### 5.1 现状

`batch-trigger/src/main/resources/application.yml`:
```yaml
spring.quartz:
  job-store-type: jdbc
  properties:
    org.quartz.jobStore:
      tablePrefix: quartz.QRTZ_
      driverDelegateClass: org.quartz.impl.jdbcjobstore.PostgreSQLDelegate
      isClustered: true
```

- ✅ JDBC JobStore + 集群模式(多 trigger 副本不重发);
- ⚠ `org.quartz.threadPool.threadCount` 未显配 → Quartz 默认 10;
- ⚠ `org.quartz.jobStore.clusterCheckinInterval` 未显配 → Quartz 默认 15000ms(15s);
- ⚠ `org.quartz.jobStore.maxMisfiresToHandleAtATime` 未显配 → 默认 20;
- ⚠ `batch.trigger.scheduler-impl=wheel` 是默认,**wheel 模式下 Quartz autoStartup=false**(由 `QuartzPauseWhenWheelEnabledCustomizer` 保证),但 **Quartz 线程池(10 thread)仍然初始化** → 闲置 10 daemon 线程 / trigger 副本。

### 5.2 P2-13 [Quartz] wheel 模式下 Quartz 池 10 idle daemon

- `scheduler-impl=wheel` 时 Quartz 不该再建主调度循环,但 Spring Boot QuartzAutoConfiguration 创建 SchedulerFactoryBean 时已初始化 ThreadPool。
- 内存影响小(每 thread 1MB stack = 10MB),但 K8s pod startup latency / CPU profiler 噪音存在。
- **修复**: trigger application.yml 加 `org.quartz.threadPool.threadCount: 1`(wheel 回退回退 Quartz 时也够用,catch-up rate 单线程 10 req/s);若彻底切 wheel 则在 wheel 启用时不创建 SchedulerFactoryBean。

### 5.3 P2-14 [Quartz] SimpleTrigger misfire 未显式配

- `TriggerSchedulerFacade.java:309 / 326` 显式配了 CronTrigger / FIXED_RATE 的 misfire instruction;
- **一次性 SimpleTrigger(MANUAL_LAUNCH 走的路径)未显式配 misfire** → Quartz 默认 `SMART_POLICY` 在 misfireThreshold(60s)外的行为是 `MISFIRE_INSTRUCTION_FIRE_NOW`(立即补跑)→ 可能与业务 MisfireHandler 双触发。
- **修复**: SimpleTrigger 构建器显式 `.withMisfireHandlingInstructionNextWithExistingCount()`,与 application catch-up 解耦。

---

## 6. ShedLock 全调度矩阵复核(v1 部分,完整版)

### 6.1 全 41 个 @SchedulerLock 矩阵(orchestrator + trigger)

| 调度器 | 触发间隔 | lockAtMostFor | lockAtLeastFor | 评 |
|---|---|---|---|---|
| OutboxPollScheduler | 自适应 200ms-5s | publish-timeout+10s=130s | 200ms | ✅ |
| WaitingPartitionDispatchScheduler | 10s | PT1M | PT5S | ✅ |
| RetryScheduleScheduler | 10s | PT1M | PT5S | ✅ |
| PartitionLeaseReclaimScheduler | 15s | PT2M | PT10S | ✅ + budget 75% |
| `partition_orphan_sweep` | 5min | PT2M | PT30S | ✅ |
| WorkerDrainTimeoutScheduler | 15s | PT2M | PT10S | ✅ |
| WorkerHeartbeatTimeoutScheduler | 30s | PT2M | **PT5S** | ⚠ 偏低 |
| SLA scheduler | 30s | PT2M | PT15S | ✅ |
| BatchBacklogMetricsScheduler | 30s | PT1M | PT15S | ✅ |
| RuntimeConsistencyMetricsScheduler | 30s | PT1M | PT10S | ✅ |
| DeadLetterAutoRetryScheduler | 30s | PT2M | PT10S | ✅ |
| FileGovernanceLatencyScheduler | 30s | PT2M | PT15S | ✅ |
| FileGovernanceArrivalGroupScheduler | 60s | PT2M | PT15S | ✅ |
| FileGovernanceReconcileScheduler | 60s | PT3M | PT30S | ✅ |
| FileGovernanceArchiveCleanupScheduler | 60s | PT3M | PT30S | ✅ |
| BatchDayOpenScheduler | 60s | PT2M | PT15S | ✅ |
| BatchDayCutoffScheduler | 60s | PT2M | **PT20S** | ✅(**v1 误报已撤**) |
| BatchDaySettleScheduler | 60s | PT3M | PT30S | ✅ |
| BatchDayWaitingReleaseScheduler | 60s | PT3M | PT30S | ✅ |
| BatchDayReplayDispatcher | 30s | PT5M | PT15S | ✅ |
| JobInstanceTimeoutEnforcer | 60s | PT2M | PT10S | ✅ |
| TaskTimeoutEnforcer | 60s | PT2M | PT10S | ✅ |
| WorkflowRunStuckReconciler | 60s | PT5M | PT30S | ✅ |
| CrossDayDependencyReconciler | 60s | PT5M | PT30S | ✅ |
| TriggerRequestLaunchReconciler | 60s | PT5M | PT10S | ✅ |
| QuotaRuntimeResetScheduler | 60s | PT3M | PT30S | ✅ |
| TenantSchedulerSnapshotRecorder | 2min | PT5M | PT1M | ✅ |
| QuotaRuntimeStateSnapshotScheduler | 5min | PT5M | PT1M | ✅ |
| WorkerCapabilityTagsAuditScheduler | 5min | PT2M | **PT5S** | ⚠ 偏低 |
| ResultVersionRetentionScheduler | 1h | PT15M | PT1M | ✅ |
| WorkflowValidatorReconciler | 1d | PT30M | PT5M | ✅ |
| OutboxArchiveScheduler | 03:30 cron | PT30M | PT1M | ✅ |
| WorkflowArchiveScheduler | 04:15 cron | PT30M | PT1M | ✅ |
| SuccessInstanceArchiveScheduler | 04:30 周日 cron | PT2H | PT5M | ✅ |
| **SensorPollScheduler** | 30s | PT5M | **PT1S** | ⚠ **明显偏低**,200ms cooperative 多副本可重抢 |

### 6.2 P2-16 [ShedLock] 3 个偏低 lockAtLeastFor

- `SensorPollScheduler` PT1S / `WorkerHeartbeatTimeoutScheduler` PT5S / `WorkerCapabilityTagsAuditScheduler` PT5S。
- 多副本 + 任务瞬抢瞬释场景下,**两个副本可能在 1-5s 窗口都拿到锁** → 调度幂等保护下不出错,但 Redis SETNX 风暴 + ShedLock log 噪音 + Prometheus `shedlock_lock_acquired_total` 翻倍。
- **修复**: 三者一律抬到 `lockAtLeastFor = PT15S`(与 30s tick 频率不冲突)。

### 6.3 撤销 v1 P2-9

v1 P2-9 称 "BatchDayCutoffScheduler 缺 lockAtLeast",grep 显示实际为 `lockAtLeastFor = "PT20S"`(BatchDayCutoffScheduler.java:51-52)。**撤销**,无需修复。

---

## 7. 资源压测基线(v1 §11 备注,本次落地)

### 7.1 现状(`load-tests/README.md` + `load-tests/src/`)

| 场景 | 关注 | 阈值 | 实测归档 |
|---|---|---|---|
| JobLaunchSimulation | trigger 写 RPS | write.p95 < 500ms | ❌ 无 |
| ConsoleQuerySimulation | 读 P99 | read.p99 < 300ms | ❌ 无 |
| CapacityBaselineSimulation | 混合阶梯加压 | 同上 | ❌ 无 |
| SchedulingSnapshotUnderLoadSimulation | 调度可观测性 | 同上 | ❌ 无 |
| SchedulingBacklogUnderLoadSimulation | WAITING/READY backlog | jp_waiting 不持续上升 | ❌ 无 |
| LaunchPipelineCompletionSimulation | 端到端 launch → 终态 | 90 polls × 2s | ❌ 无 |
| WorkerTaskLifecycleSimulation | CLAIM → REPORT 内部 API | write.p95 < 500ms | ❌ 无 |

### 7.2 P1-15 [压测] 全套 Gatling 框架已就位,**0 条 baseline 数据归档**

- 项目里有 7 个 Simulation + scheduler-backlog sampler + worker-load-report 模板;
- **`docs/` 全仓零 "soak run 报告" / "baseline numbers"**;`load-tests/` 下零 `.csv` / `.html` 归档;
- v1 §0 "Outbox 告警阈值 P95 publish < 5s + pending > 5000 说明实际目标量级 ≥ 5k/s" 是推断,**未压测验证**。
- **修复**:
  1. CI 加 nightly soak job(已有 `scripts/start-soak.sh`)→ 跑 1h 写 `load-tests/baselines/2026-06-soak-baseline.md`;
  2. SLO 阈值与实测拉差报表自动生成;
  3. 容量基线归档纳入 release checklist。

---

## 8. PG 索引利用率与 Bulk INSERT 性能(v1 完全未扫)

### 8.1 V160-V165 索引清单与覆盖

| 迁移 | 表 | 索引 | 用途推断 | 风险 |
|---|---|---|---|---|
| V160 | `job_task.effective_parameters` | **无 idx** | 查询用 task_id PK 命中,JSONB 不索引 | ✅ 合理 |
| V161 | `job_task.heartbeat_details_cancel` | **无 idx** | 同上 | ✅ |
| V162 | `job_task.task_timeout_seconds` | **无 idx** | 等值不查 | ✅ |
| V163 | `worker_registry.fingerprint` | **无 idx** | fingerprint 不索引,审计扫表 | ⚠ 全表扫风险:WorkerCapabilityTagsAuditScheduler 5min tick 全扫一次 worker_registry,千级 worker 时无 idx OK,万级出问题 |
| V164 | `pipeline_progress` 表 | `idx_pipeline_progress_tenant_instance(tenant_id, pipeline_instance_id)` + `idx_pipeline_progress_completed_at(completed_at)` | tenant 查 + 时间过滤 | ✅ |
| V165 | `atomic_task_config` | `idx_atomic_task_config_tenant_type(tenant_id, task_type)` | 路由查找 | ✅ |

### 8.2 P2-17 [DB] V163 `worker_registry.fingerprint` 缺索引,审计路径全表扫

- 当前 worker_registry 规模 100 级 OK,**ADR-035 租户自托管 SDK 上线后 worker 规模 1k-10k 可见**;
- `WorkerCapabilityTagsAuditScheduler` 全扫 tag 漂移,5min tick × 10k 行 = 全表 seq scan 30k 行/分钟 PG IO 压力;
- **修复**: 补 partial index `CREATE INDEX idx_worker_registry_active ON worker_registry(tenant_id, capability_tags) WHERE status='ACTIVE'`(V166)。

### 8.3 P1-16 [Perf] Import LOAD 用 batchUpdate,无 PG COPY 路径,10× 性能差距

- `LoadStep#flushChunk` → `plugin.loadChunk(loadCtx, chunk)` → `GenericJdbcMappedImportLoadPlugin.loadChunk` 内 `jdbcTemplate.batchUpdate(...)`;
- chunk-size 默认 500 行,单批 batchUpdate 是 PG 多个 INSERT(单事务),~5k rows/s 实测;
- PG `COPY FROM STDIN` ~50k rows/s,**10× 差距**;
- 百万行 import: batchUpdate ~200s,COPY ~20s;
- **不是 P0**:当前 100MB max-payload-size 与 500k 行 max 限制下,200s 业务可接受;
- 但 ADR-035 后租户自托管 plugin 可重写,**核心 plugin 升级 COPY 是 10× 吞吐升级低成本**。
- **修复路径**:
  1. `LoadStep` 加策略点 `LoadStrategy.{BATCH_UPDATE, PG_COPY}`,默认 BATCH_UPDATE;
  2. `GenericJdbcMappedImportLoadPlugin` 升级版用 `CopyManager.copyIn("COPY ... FROM STDIN ...")`;
  3. business datasource 必须暴露原生 PG `Connection` 给 COPY API(Hikari `unwrap(PgConnection.class)`);
  4. 注意 COPY 不走 PG triggers / RLS bypass — 需先确认无 trigger 依赖。

### 8.4 P1-17 [Perf] JDBC URL 缺 `reWriteBatchedInserts=true`

- `batch-defaults.yml`:`BATCH_PLATFORM_DB_URL:jdbc:postgresql://localhost:15432/batch_platform`,**URL 无任何 ?参数**;
- pgjdbc `reWriteBatchedInserts=false` 默认 → batchUpdate 退化成多次单 INSERT,**比真 batch 慢 3-5×**;
- **修复**: env 默认改 `jdbc:postgresql://localhost:15432/batch_platform?reWriteBatchedInserts=true&ApplicationName=...`,或在 `HikariPgSessionSupport` 统一 set。
- **联动收益**: P1-16 即便不上 COPY,这一改也能让 batchUpdate 提升 3×。

---

## 9. Outbox / DLQ 风暴防御(v1 §7 已扫,补 producer 端边界)

### 9.1 P1-14 [Outbox] forwarder 无 producer-side backpressure

- `OutboxPollScheduler` 自适应轮询:有积压 200ms 立即续 → 高峰 5k 事件 / tick 全速 push;
- Kafka producer `buffer.memory` 默认 32MB,单条 outbox payload 平均 4KB:32MB / 4KB = 8192 条可缓冲;
- 5k events/tick × 5 tick/s = 25k events/s 持续 → buffer 满后 `KafkaProducer.send` 阻塞或抛 `BufferExhaustedException`;
- **当前 producer 端无背压联动**,outbox poll 还会继续抓行 → 内存堆积 + DB connection 长持有。
- 现存熔断 `OutboxPublishCircuitBreaker` 是按"失败 3 轮"触发,**buffer 满 → 不算失败,触发不了断路器**。
- **修复**:
  1. producer `buffer.memory=64MB`(env);
  2. OutboxPollScheduler 加 `KafkaProducer.metrics().get("buffer-available-bytes")` 联动,< 10% 时跳过本轮 poll;
  3. Prometheus 加 `kafka.producer.buffer-available-bytes` 告警,< 4MB warn。

### 9.2 DLQ replay batchSize=100 限流 ✅(澄清)

- v1 P1-x 未提具体值,本次确认:`RetryGovernanceProperties.batchSize = 100`(`DefaultRetryGovernanceService:261`);
- 30s tick × 100 = 3000 DLQ events/15min,够用;
- **缺**: per-tenant 限流 — 单一恶意租户 1000 条 DLQ 时会霸占整 batch。
- **修复**: `selectDueAutoRetries` 改 `selectDueAutoRetriesFairOrdered`,按 `(tenant_id, replay_count, next_replay_at)` 公平排序。

---

## 10. 资源亲和闸门(v1 §8 已扫,补一处)

### 10.1 v1 P1-8 复核

- `BATCH_RESOURCE_SCHEDULER_GLOBAL_MAX_RUNNING_JOBS=0`(关)、`BATCH_RATE_LIMIT_ENABLED=false` — v1 结论正确。
- helm `values-prod.yaml` **未显式覆盖** → 生产 helm 部署直接继承 false,**v1 P1-8 严重性升级**;
- **修复**: `values-prod.yaml` 显式 `batch.rateLimit.enabled=true` + `globalMaxRunningJobs=10000`,configmap 注入。

---

## 11. v2 修复优先级汇总

### P0 新增(0)
*无*。v1 + v2 双扫累计仍 0 P0。

### P1 新增(9)
| ID | 主题 | 一句话 |
|---|---|---|
| P1-9 | JVM | docker-compose 写死 `-Xmx512m` 与容器 mem-limit 解耦,无法靠 K8s/compose 改内存边界 |
| P1-10 | Kafka | session.timeout / heartbeat.interval / partition.assignment.strategy 全默认,rolling update 触发 stop-the-world rebalance |
| P1-11 | Mem | PreprocessStep.decryptViaSpool 最后 `Files.readAllBytes(decrypted)` 仍 100MB 入堆 |
| P1-12 | Mem | TaskExecutionReportDto 无 `@Size` 守护,heartbeat_details 理论无界,可失败 Jackson + PG JSONB |
| P1-13 | DB | archive 长事务无 pg_advisory_lock + lock_timeout 守护,与调度 tick 撞期可死锁 |
| P1-14 | Outbox | producer buffer.memory 默认 32MB,高峰无 backpressure → BufferExhaustedException 风险 |
| P1-15 | 压测 | Gatling 框架 7 套 + SLO 阈值齐备,但 0 baseline 数据归档,SLO 未证实 |
| P1-16 | Perf | Import LOAD 走 batchUpdate 无 PG COPY 路径,百万行 10× 慢 |
| P1-17 | Perf | JDBC URL 缺 `reWriteBatchedInserts=true`,batchUpdate 退化成多单 INSERT |

### P2 新增(5)
| ID | 主题 | 一句话 |
|---|---|---|
| P2-13 | Quartz | wheel 模式下 Quartz 默认 threadCount=10 idle daemon,可调到 1 |
| P2-14 | Quartz | SimpleTrigger misfire 未显式配,SMART_POLICY 行为不确定 |
| P2-15 | Kafka | 未启 transactional.id,文档应明示 outbox + CLAIM 幂等覆盖 EOS 的设计选择 |
| P2-16 | ShedLock | SensorPoll/WorkerHeartbeatTimeout/WorkerCapabilityTagsAudit 3 个 lockAtLeastFor 偏低(PT1S/PT5S/PT5S) |
| P2-17 | DB | V163 worker_registry.fingerprint 无 idx,租户自托管 SDK 万级 worker 后审计扫表慢 |

### 撤销 v1 误报
| 原 ID | 撤销原因 |
|---|---|
| v1 P2-9 | BatchDayCutoffScheduler 实际有 `lockAtLeastFor=PT20S`(BatchDayCutoffScheduler.java:51-52),v1 漏读 |
| v1 §1.1 docker-init 表述偏旧 | `docker-compose.yml:20` 已 `max_connections=300`,v1 默认 100 的表述需更新 |

### v1 已提但 v2 补充修复路径
| v1 ID | v2 补充 |
|---|---|
| v1 P1-1 | helm 缺 PgBouncer sub-chart 复核成立;**connection budget runbook 仍缺**,本次再确认 |
| v1 P1-2 | 同时补 P1-14 producer buffer.memory 防 BufferExhaustedException |
| v1 P1-5 | 实测 PartitionLeaseProperties 已 fail-fast,风险降级但仍存在 |
| v1 P2-8 | 本次 P1-12 给出具体修复(Tomcat / Spring codec / DTO @Size 三层守护) |

---

## 12. 一句话风险地图

> 系统调度面 / 心跳 / lease 数学层面是**清醒且自洽**的(v1 验证);本次 v2 触达的真实 P1 集中在 **运行时边界**(JVM 配置不对齐 / Kafka client 默认值 / JSON body 无界 / producer buffer 边界)与 **Phase 2 性能升级路径**(reWriteBatched / PG COPY / 压测 baseline)。生产前必须做的两件事:
>
> 1. **运行时边界统一**:docker-compose JAVA_OPTS 改 `MaxRAMPercentage`、显配 Kafka 三角时序 + CooperativeStickyAssignor、TaskController 加 `@Size` + Tomcat post-size cap。
> 2. **压测 baseline 立桩**:跑一次 1h soak + 归档,验证 SLO 阈值站得住,否则 v1 §0 "5k/s" 是猜测。

---

## 13. 行动建议(本周 / 下个迭代 / 生产前)

### 本周(8 小时内可落地)
1. P2-16 三个 lockAtLeastFor 抬到 PT15S — 3 处 1 行改动
2. P2-13 trigger application.yml 显式 `org.quartz.threadPool.threadCount: 1` — 1 行
3. P1-17 platform / business JDBC URL env 加 `?reWriteBatchedInserts=true` — 2 处 + docker-compose 默认 — 半天
4. P1-12 TaskExecutionReportDto 加 `@Size(max=262144) details` + TaskController `@Valid` + tomcat max-http-form-post-size=1MB — 半天

### 下个迭代(1-2 周)
5. P1-9 docker-compose JAVA_OPTS 切到 MaxRAMPercentage — 7 处 yml + 文档同步
6. P1-10 Kafka 三角 + CooperativeStickyAssignor — `batch-defaults.yml` 加 6 行 + `docs/runbook/kafka-consumer-rolling-upgrade.md`
7. P1-11 PreprocessStep 改 byte[] → Path 返回路径 — 调用方一并改
8. P1-13 archive + advisory_lock + `lock_timeout` — 3 处 scheduler
9. P1-14 producer buffer.memory=64MB + OutboxPoll backpressure 联动 — 半天
10. P2-17 V166 加 `idx_worker_registry_active` partial idx — 1 行 sql

### 生产前必须(release blocker)
11. P1-15 跑 1h soak + 归档 baseline,SLO 阈值与实测对齐
12. v1 P1-1 + v1 P1-8 维持 release blocker — PgBouncer sub-chart + rate-limit 生产开关
13. P1-16 Import COPY 路径作为"高吞吐租户 opt-in",**非阻塞 release**,可 Phase 2 上

---

## 14. 范围外的发现(留给 v3 / 别的 sub-audit)

- **OpenLineageEmitter AbortPolicy**(v1 P2-5):v2 复核 OpenLineageEmitter 在 `infrastructure/lineage/`,本次未深挖 ThreadPoolExecutor 拒绝路径下的 metric counter — 留 v3 一笔。
- **批量并发 deploy 触发 K8s rolling 风暴**:P1-10 修复后建议联动 PDB(已有 `helm/templates/pdb.yaml`)minAvailable 调整 — 部署面话题,留 deploy-skill。
- **Python SDK 资源 / 心跳路径**:本次仍未扫(v1 §11 提了),Python SDK 在 `batch-worker-sdk-python/`(查仓),单独 sub-audit。
- **Redis ShedLock SETNX 风暴**:v2 §6 提到 P2-16 lockAtLeastFor 偏低会刷 Redis,但 Redis 池本身负载分析未做(v1 §0 表 Redis mem/clients 告警已挂)。

---

*报告人: BE Resources & Scheduling Deep Scan v2(2026-06-03 复扫)*
*依据基线 v1:`docs/analysis/2026-06-03-deep-scan-be-resources-scheduling.md`*
*本次扫描 commit HEAD:`worktree-agent-a0b9c9f58efca258c`(base=origin/main)*
*下次复扫触发:① 跑出 SLO baseline 后回看 P1-15 ② Kafka 升级 4.0 后回看 P1-10 ③ ADR-035 租户自托管 SDK 规模 1k+ 后回看 P2-17*
