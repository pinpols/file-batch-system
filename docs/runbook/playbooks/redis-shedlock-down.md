# Redis 全断,ShedLock 切 jdbc fallback

> 优先级 P0 · 最后核对版本:2026-05 · 配套 chaos IT:`RedisShedLockFallbackChaosIT`(TODO 与 Plan #1 联调)

## TL;DR

**症状**:所有 `@SchedulerLock` 任务报 Redis 连不上,`OutboxPollScheduler` / `BatchDaySettleScheduler` 等全部空转;调度停顿。
**一行修复**:把 `batch.shedlock.provider` 从 `redis`(默认)切 `jdbc`,滚动重启 orchestrator/trigger/worker/console-api,10 分钟内恢复。

---

## 怎么发现

- **Prometheus alert**:TODO(待 ops 团队补 `BatchRedisDown` / `BatchShedLockAcquireFail`)
- **Grafana**:TODO。临时看:
  - `lettuce_command_completion_seconds_count{command="SET"}` 不再增长
  - orchestrator 日志中 `RedisConnectionFailureException` 出现频率
- **日志关键字**:
  - `org.springframework.data.redis.RedisConnectionFailureException`
  - `io.lettuce.core.RedisCommandTimeoutException`
  - `Unable to acquire JedisConnection` / `Connection refused`
  - `Outbox 投递熔断已打开` 后续轮持续打印(因为 advance 拿不到锁)
- **用户反馈**:
  - "Job 准时窗口过了还没起" — `BatchDaySettleScheduler` / `TriggerLaunchScheduler` 都靠 ShedLock 抢锁
  - console-api 的 quota 检查异常 — `RedisQuotaRuntimeStateService` 也用 Redis

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
     - 实际 key 格式是 `job-lock:<env>:shedlock:<env>:<lockName>`(`<env>` 默认取 `spring.application.name`,见 `batch-defaults.yml` 注释与 `ShedLockProviderFactory#redisLockProvider`),所以 scan pattern 要以 `*shedlock:` 起头才能命中
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

### 方案 A:切 ShedLock 到 jdbc(2-5 min,推荐)

ShedLock 抽象了 provider,业务代码无需改动 — 见 `BatchShedLockAutoConfiguration` 注释。

1. **改配置**(每个服务都要,共 6 个:orchestrator / trigger / 4 个 worker / console-api 任选有 `@SchedulerLock` 的)
   ```yaml
   # application.yml 或环境变量覆盖
   batch:
     shedlock:
       provider: jdbc          # 默认 redis,这里显式切
       auto-create: false      # 生产 Flyway 已建表;dev 才开 true
   ```
   或环境变量:`BATCH_SHEDLOCK_PROVIDER=jdbc`

2. **确认 `batch.shedlock` 表存在**(正常情况下早就由 Flyway 建好)
   ```sql
   \d batch.shedlock
   -- 期望:name PK / lock_until / locked_at / locked_by
   ```
   不存在 → 临时打开 `batch.shedlock.auto-create=true` 让启动期 `ShedLockProviderFactory#ensureShedLockTable` 建表。

3. **滚动重启**
   ```bash
   docker compose restart batch-orchestrator
   sleep 30 && curl -sSf http://localhost:18082/actuator/health | jq .status
   docker compose restart batch-trigger
   docker compose restart batch-worker-import batch-worker-export \
     batch-worker-process batch-worker-dispatch
   docker compose restart batch-console-api
   ```

4. **验证锁正常工作**
   - 看启动日志,期望:`ShedLock LockProvider auto-configured: type=JDBC (JdbcTemplateLockProvider), autoCreate=false`
   - 等一个调度周期(`OutboxPollScheduler` 默认几百 ms,`BatchDaySettleScheduler` 60s),`select * from batch.shedlock` 应有新行写入

5. **Redis 修好后切回**:把 `provider` 改回 `redis`(或删除该配置项,默认就是 redis)+ 再滚动重启。**切回前必须确认 Redis 健康**,否则又掉进同一个问题。

### 方案 B:短时挂起,等 Redis 自愈(5-10 min)

适用:已确认 Redis 故障可在 5 min 内恢复(例:OOM 重启)。

1. 不改配置,但让上层 chaos 影响最小化:
   - 把熔断器阈值临时调激进,让 `OutboxPublishCircuitBreaker` 早早 open,减少日志噪音(见 `OutboxPublishCircuitBreaker` 的 `failure-threshold` / `cooldown-seconds`)
2. 等 Redis 拉起后:
   ```bash
   docker compose restart redis
   docker compose exec redis redis-cli ping  # PONG
   ```
3. `OutboxPollScheduler` / `BatchDaySettleScheduler` 会自动恢复(下一个 tick 抢锁成功)。

### 方案 C:最后手段(破坏性操作)— 回滚到上一版(15+ min)

仅当方案 A 失败(jdbc fallback 也起不来,例如 `batch.shedlock` schema drift)。

1. 停所有业务:`docker compose stop batch-orchestrator batch-trigger batch-worker-* batch-console-api`
2. 回滚镜像 tag 到上个已知版本:`git checkout <last-known-good-tag>`,重新 build / pull
3. `docker compose up -d`,逐个服务验证 `actuator/health`
4. Redis / PG 都没救 → 升级到全平台不可用应急流程(超出本剧本)

---

## 事后

- **写 incident-response 关联本剧本**:在 `docs/runbook/incident-response.md` 表里追加 P1 行。
- **思考默认 provider 选择**:本仓 2026-05-28 默认切 `redis`(批注见 `BatchShedLockAutoConfiguration`),如果半年内 Redis 已 down 过 2 次 → 考虑默认回 `jdbc`,把 redis 当性能优化的可选项。
- **alert 缺失**:`BatchRedisDown` / `BatchShedLockAcquireFail` 必须补;`BatchShedLockJdbcFallbackActive`(告知 ops 当前正在降级)更佳。
- **剧本走不通**:Redis 又活了但锁没释放(`job-lock:<env>:shedlock:<env>:<lockName>` key 有残留 TTL),手动 `DEL` 该 key,补一篇 `redis-shedlock-stuck-lock.md`。

## 关联

- 代码:`batch-common/.../config/BatchShedLockAutoConfiguration.java`(provider 切换),`ShedLockProviderFactory.java`(jdbc / redis 实现)
- 业务调度:`OutboxPollScheduler`,`BatchDaySettleScheduler`,`TriggerLaunchScheduler`,`WebhookDeliveryRelay` 等共 48 处 `@SchedulerLock`
- 上一级:[`docs/runbook/incident-response.md`](../incident-response.md)
