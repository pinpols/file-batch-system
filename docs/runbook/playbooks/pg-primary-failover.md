# PG 主库故障切主(postgres-primary → postgres-replica)

> 优先级 P0 · 最后核对版本:2026-05 · 配套 chaos IT:`PgPrimaryFailoverChaosIT`(TODO 与 Plan #1 联调)

## TL;DR

**症状**:orchestrator/trigger/worker 大量 `DataAccessException` + `pg_isready` 不通,业务停顿。
**一行修复**:在 replica 上 `select pg_promote();` 把从库切主,改 `.env.local` 的 `POSTGRES_PORT` 指向新主,重启 orchestrator/trigger/worker。

---

## 怎么发现

- **Prometheus alert**:TODO(待 ops 团队补 `BatchPgPrimaryDown` / `HikariCpConnectionTimeout` 告警)
- **Grafana 面板**:TODO(待补)。临时看 `actuator/prometheus`:
  - `hikaricp_connections_pending{datasource="platform"}` 持续 > 0
  - `hikaricp_connections_timeout_total` 单分钟内陡增
- **日志关键字**:
  - `org.postgresql.util.PSQLException: The connection attempt failed`
  - `Connection is not available, request timed out after`
  - `Outbox 轮询数据库瞬时异常,下轮重试`(`OutboxPollScheduler` 已降级为 WARN)
- **用户反馈**:console-api `POST /api/console/job/run` 直接 500;调度全部停摆。

---

## 怎么定位

1. **确认主库异常退出,不是网络抖动**
   ```bash
   # 在 orchestrator 容器宿主上
   docker compose ps postgres-primary
   docker compose logs --tail=200 postgres-primary | grep -iE "fatal|panic|shutdown"
   pg_isready -h localhost -p ${POSTGRES_PORT:-15432} -U ${POSTGRES_USER:-batch_user}
   ```
   - 容器 `Exited (xxx)` 或日志含 `PANIC` / `database system is shut down` → 走方案 B/C
   - 容器 healthy 但 `pg_isready` 不通 → 网络问题,先看 `docker network inspect batch-network`,不在本剧本范围

2. **确认 replica 健康、能接管**
   ```bash
   pg_isready -h localhost -p ${POSTGRES_REPLICA_PORT:-15433} -U ${POSTGRES_USER:-batch_user}
   psql -h localhost -p ${POSTGRES_REPLICA_PORT:-15433} -U ${POSTGRES_USER:-batch_user} \
        -d ${POSTGRES_DB:-batch_platform} \
        -c "select pg_is_in_recovery(), pg_last_wal_receive_lsn(), pg_last_wal_replay_lsn();"
   ```
   - `pg_is_in_recovery` = `t`(还在 standby)
   - receive_lsn 与 replay_lsn 差距 < 1MB → 复制延迟小,可以切
   - 差距巨大或 receive_lsn 为 NULL → replica 已脱节,**不能切**,走方案 C

3. **统计未发出的 outbox 事件(评估 RPO)**
   ```sql
   -- 在 replica 上(只读 OK)
   select publish_status, count(*)
     from batch.outbox_event
    where created_at > now() - interval '10 minutes'
    group by publish_status;
   ```
   - `PUBLISHING` 多 → 切主后需手动重置(见方案 A 的事后步骤)
   - `NEW` 多 → 切主后 `OutboxPollScheduler` 自然续上,不用管

4. **关键决策点**:
   - replica lag < 1s 且业务可容忍丢 < 1s 数据 → **方案 A**
   - replica lag 高或不确定 → **方案 B**(只读降级,等主库回来)
   - 主库数据卷损坏 / replica 也挂 → **方案 C**(回滚版本 + 重建)

---

## 怎么恢复

### 方案 A:promote replica 切主(2-5 min,最常用)

1. **冻结写入**:把 orchestrator/trigger/worker 停掉,避免 split-brain(主库一会儿可能回来)
   ```bash
   docker compose stop batch-orchestrator batch-trigger \
     batch-worker-import batch-worker-export batch-worker-process batch-worker-dispatch
   # console-api 可暂留(读 replica 即可)
   ```

2. **在 replica 上 promote**
   ```bash
   docker exec -it batch-postgres-replica \
     psql -U ${POSTGRES_USER:-batch_user} -d ${POSTGRES_DB:-batch_platform} \
     -c "select pg_promote(wait => true, wait_seconds => 30);"
   # 返回 t 即成功;此时 pg_is_in_recovery() 应变 f
   docker exec -it batch-postgres-replica \
     psql -U ${POSTGRES_USER:-batch_user} -d ${POSTGRES_DB:-batch_platform} \
     -c "select pg_is_in_recovery();"
   ```

3. **切流量**:改 `.env.local`
   ```bash
   # 原:POSTGRES_PORT=15432(指 primary)
   # 改:POSTGRES_PORT=15433(指原 replica,现已是新主)
   # 同步:POSTGRES_REPLICA_PORT 留空或指向某个新建 standby(暂时无 replica 也能跑)
   ```

4. **重启业务模块**
   ```bash
   docker compose up -d batch-orchestrator batch-trigger \
     batch-worker-import batch-worker-export batch-worker-process batch-worker-dispatch
   # 等 60s 确认
   curl -sSf http://localhost:18082/actuator/health | jq .status   # 期望 UP
   ```

5. **事后清理 stale PUBLISHING**
   - 调度自动调 `OutboxEventMapper.resetStalePublishing` 重置(默认 `batch.outbox.publishing-timeout-seconds`,见 `OutboxProperties`),正常 1-2 轮后回 `FAILED` 重投。
   - 若想立即清:
     ```sql
     update batch.outbox_event
        set publish_status='FAILED',
            next_publish_at=current_timestamp,
            updated_at=current_timestamp
      where publish_status='PUBLISHING'
        and updated_at < current_timestamp - interval '60 seconds';
     ```
     **必须**走 orchestrator 的 `/internal/outbox/*` 治理接口(CLAUDE.md 红线:console-api 禁直接 UPDATE/DELETE `outbox_event`)。

### 方案 B:有损降级 — 只读模式撑过去(10 min)

适用:replica lag 高 / 主库预计 5-10 min 能拉起 / 业务可容忍调度暂停但需保留查询。

1. 停掉所有写路径:`batch-orchestrator` / `batch-trigger` / `batch-worker-*`
2. 把 console-api 切到 replica 读:`batch.console.read-replica.enabled=true`(见 `ReadReplicaRoutingDataSource`),允许 `GET /api/console/query/*` 继续服务
3. 等主库 ops 抢救:常见原因是磁盘满 / OOM kill / WAL 损坏,看 `docker logs batch-postgres-primary`
4. 主库回来后:**不要直接重启业务**,先确认 `pg_is_in_recovery()` = `f`,再按方案 A 步骤 4 重启

### 方案 C:最后手段(破坏性操作)— 回滚 + 重建(30+ min)

触发条件:主库数据卷损坏、replica 同步早就断、上一版 PG migration 把 schema 破坏。

1. 停所有服务:`docker compose down`(不带 `-v`,**别清卷**)
2. 备份现有卷:`docker run --rm -v batch_postgres-primary-data:/data -v $(pwd)/backup:/backup alpine tar czf /backup/pg-primary-$(date +%s).tgz /data`
3. 回滚到上一个已知好版本:`git checkout <last-known-good-tag>`(`docs/runbook/releasing.md` 有 tag 规则)
4. 从最近一份 WAL 备份或 logical dump 恢复 → 完整步骤见 [`../backup-and-pitr.md`](../backup-and-pitr.md)(§2 恢复演练:PITR 物理恢复 / 逻辑全量恢复)
5. `docker compose up -d`,人工验证 `batch.job_instance` / `batch.outbox_event` 最近 1h 数据完整性

---

## 事后

- **写 incident-response 关联本剧本**:在 `docs/runbook/incident-response.md` 表里追加一行(级别 P1,链接到本文件)。
- **看是否要调阈值**:
  - 切主后 `OutboxPollScheduler` 出现大量 stale PUBLISHING → 把 `publishing-timeout-seconds` 调小,加快自愈
  - Hikari 池等待大量超时 → 调 `spring.datasource.platform.hikari.connection-timeout`(默认 30s 偏长,P0 故障期希望 fail-fast)
- **alert 缺失**:本剧本 TODO 的 `BatchPgPrimaryDown` / `BatchReplicaLag` alert 必须补;补完后回填 alert 名到本文档。
- **剧本走不通**:如果遇到「promote 成功但业务连不上新主」「replica lag 永远收不敛」→ 补一篇 `pg-replica-rebuild.md`。

## 关联

- 代码:`docker-compose.yml`(postgres-primary / postgres-replica),`docker/postgres-replica/entrypoint.sh`
- 配置:`ReadReplicaRoutingDataSource`(console-api 唯一读写分离落点),`docs/runbook/read-replica.md`
- 上一级:[`docs/runbook/incident-response.md`](../incident-response.md)
