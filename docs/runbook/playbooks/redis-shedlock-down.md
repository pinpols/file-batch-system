# Redis 全断,ShedLock 切 jdbc fallback

> 优先级 P0 · 最后复核：2026-10-08（代码/配置核对；真实 Redis 全断仍需 staging 演练）

## TL;DR

**症状**:所有 `@SchedulerLock` 任务报 Redis 连不上,`OutboxPollScheduler` / `BatchDaySettleScheduler` 等全部空转;调度停顿。
**处置摘要**:若确认 Redis 故障无法快速恢复，按变更流程停止所有使用 ShedLock 的实例，统一切到 `jdbc` 后再启动。预计中断时长取决于业务排空和部署方式，不承诺固定恢复时间。

---

## 怎么发现

- **Prometheus**:`batch_shedlock_acquire_failed_total{provider="redis"}` 记录 provider 调用异常，`batch_shedlock_provider_healthy{provider="redis"}` 表示最近一次 provider 调用状态；`BatchOutboxCircuitBreakerFailOpen` 与 Redis 内存/连接数告警是相关但不同的信号。锁竞争返回空不计为故障。
- **Grafana**:当前使用下列 Prometheus/日志查询；专用面板由目标监控环境落地:
  - `lettuce_command_completion_seconds_count{command="SET"}` 不再增长
  - orchestrator 日志中 `RedisConnectionFailureException` 出现频率
- **日志关键字**:
  - `org.springframework.data.redis.RedisConnectionFailureException`
  - `io.lettuce.core.RedisCommandTimeoutException`
  - `Unable to acquire JedisConnection` / `Connection refused`
  - Outbox 调度持续异常时，同时核对 ShedLock 指标和 Redis 连接日志；熔断日志本身不能证明锁获取失败
- **用户反馈**:
  - "Job 准时窗口过了还没起" — `BatchDaySettleScheduler` / `TriggerLaunchScheduler` 都靠 ShedLock 抢锁
  - console-api quota 检查异常属于另一条 Redis 依赖链，不代表 ShedLock 获取失败；按 quota 告警单独排查

---

## 怎么定位

1. **确认 Redis 真的全断,不是单机断连**
   ```bash
   docker compose ps redis
   docker compose exec redis redis-cli ping             # 期望 PONG
   redis-cli -h localhost -p ${REDIS_PORT:-16379} ping  # 宿主机视角
   ```
   - 容器 Exited → 走方案 A
   - 容器 healthy 但 `ping` 不通 → 网络问题,先排查 docker network,不在本剧本范围

2. **看 ShedLock 锁实际状态**
   - 当前 provider = `redis`(默认):锁 key 在 Redis 里,Redis 挂 = 所有锁拿不到
     ```bash
     # Redis 还活着时可以列锁,看是不是被某 instance 长期抓住没释放
     redis-cli -h localhost -p ${REDIS_PORT:-16379} \
       --scan --pattern '*shedlock:*:outbox_poll*'
     redis-cli -h localhost -p ${REDIS_PORT:-16379} \
       --scan --pattern '*shedlock:*:batch_day_settle'
     ```
     - 实际 key 为 `job-lock:shedlock:<env>:<lockName>`，`<env>` 默认取 `spring.application.name`，跨环境共 Redis 时应由 `BATCH_SHEDLOCK_REDIS_ENV` 显式覆盖；scan pattern 以 `*shedlock:` 起头即可兼容 provider 前缀差异
   - 切到 `jdbc` 后,锁在 `batch.shedlock` 表
     ```sql
     select name, lock_until, locked_at, locked_by
       from batch.shedlock
      order by lock_until desc;
     ```

3. **确认 PG 健康**(jdbc fallback 依赖 PG)
   ```bash
   pg_isready -h localhost -p ${POSTGRES_PORT:-15432} -U ${POSTGRES_USER:-batch_user}
   ```
   PG 也挂 → 这是双故障,优先按 `pg-primary-failover.md` 救 PG,Redis 故障是次要问题。

4. **关键决策点**:
   - Redis 容器挂 / 数据卷损坏,预计恢复 > 10 min → **方案 A**(切 jdbc)
   - 预计 < 5 min 能拉起 → **方案 B**(短时挂起调度,等 Redis 回)
   - Redis + PG 都挂 → **方案 C**

---

## 怎么恢复

### 方案 A:统一切换 ShedLock 到 jdbc

ShedLock 抽象了 provider,业务代码无需改动 — 见 `BatchShedLockAutoConfiguration` 注释。

1. **准备配置**：当前代码中使用 `@SchedulerLock` 的运行服务为 orchestrator、trigger、worker-import、worker-process、worker-dispatch。部署前应重新核对该清单；同一组调度任务的 provider 必须保持一致。
   ```yaml
   # application.yml 或环境变量覆盖
   batch:
     shedlock:
       provider: jdbc          # 默认 redis,这里显式切
       auto-create: false      # 生产 Flyway 已建表;dev 才开 true
   ```
   或环境变量:`BATCH_SHEDLOCK_PROVIDER=jdbc`

2. **确认 `batch.shedlock` 表存在**(生产环境由 Flyway 管理)
   ```sql
   \d batch.shedlock
   -- 期望:name PK / lock_until / locked_at / locked_by
   ```
   不存在 → 不要在生产临时启用自动建表；按数据库迁移流程核实 Flyway 状态并补齐迁移。

3. **全停、统一切换、再全起**：按当前部署编排停止所有执行这些 ShedLock 任务的实例，确认旧实例均已停止后统一设置 `BATCH_SHEDLOCK_PROVIDER=jdbc`，再启动全部实例。禁止逐个滚动切换；两种 provider 的锁互不知情，可能造成重复调度。

4. **验证锁正常工作**
   - 看启动日志,期望:`ShedLock LockProvider auto-configured: type=JDBC (JdbcTemplateLockProvider), autoCreate=false`
   - 等一个调度周期(`OutboxPollScheduler` 默认几百 ms,`BatchDaySettleScheduler` 60s),`select * from batch.shedlock` 应有新行写入

5. **Redis 修复后切回**：确认 Redis 健康后，仍按“全停、统一切换、再全起”切回 `redis`；不可滚动切换，也不要手工删除 Redis key。

### 方案 B:短时等待 Redis 恢复

适用:已确认 Redis 故障可快速恢复，且业务可接受调度短暂停顿。

1. 不要为降低日志量临时修改熔断阈值；记录故障时间、受影响服务和相关指标。
2. Redis 恢复后:
   ```bash
   docker compose restart redis
   docker compose exec redis redis-cli ping  # PONG
   ```
3. `OutboxPollScheduler` / `BatchDaySettleScheduler` 会自动恢复(下一个 tick 抢锁成功)。

### 方案 C:Redis 与 PostgreSQL 同时不可用

JDBC provider 依赖 PostgreSQL；双故障时不要尝试切换 provider 或回滚应用代码。停止会产生调度副作用的服务，按 PostgreSQL 与 Redis 各自的灾备/恢复流程处理；依赖恢复后统一核对锁后端配置，再恢复业务服务。

---

## 事后

- **写 incident-response 关联本剧本**:在 `docs/runbook/incident-response.md` 表里追加 P1 行。
- **思考默认 provider 选择**:本仓 2026-05-28 默认切 `redis`(批注见 `BatchShedLockAutoConfiguration`),如果半年内 Redis 已 down 过 2 次 → 考虑默认回 `jdbc`,把 redis 当性能优化的可选项。
- **观测边界**:`batch_shedlock_acquire_failed_total` / `batch_shedlock_provider_healthy` 观测 provider 调用异常和最近状态；锁竞争不计失败。
- **锁状态异常**：不要手工 `DEL` 锁 key。保留 key 名、TTL、provider 指标、实例状态和任务日志，交由维护者按 ShedLock 租约语义分析。

## 关联

- 代码:`batch-common/.../config/BatchShedLockAutoConfiguration.java`(provider 切换),`ShedLockProviderFactory.java`(jdbc / redis 实现)
- 当前涉及的主要运行服务：orchestrator、trigger、worker-import、worker-process、worker-dispatch；具体调度点以各模块中的 `@SchedulerLock` 为准
- 上一级:[`docs/runbook/incident-response.md`](../incident-response.md)
