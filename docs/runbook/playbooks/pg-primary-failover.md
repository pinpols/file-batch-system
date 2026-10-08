# PG 主库故障切主(postgres-primary → postgres-replica)

> **适用范围：**仓库提供的 Docker Compose 主从演练拓扑，不是生产切主授权或生产操作 SOP。生产切换必须先按目标 HA 产品的受支持流程隔离旧主并由 DBA/值班负责人确认，参见 [`HA readiness`](../ha-readiness.md)。
> 最后复核：2026-10-08（代码/文档核对；未执行真实切主）。生产主备切换仍需 staging 演练。

## TL;DR

**症状**:orchestrator/trigger/worker 大量 `DataAccessException` + `pg_isready` 不通,业务停顿。
**本地演练摘要**:仅在隔离的 Compose 演练环境中，确认并隔离旧主后，再按演练步骤 promote replica。生产环境不得直接照抄以下 Docker 命令或仅凭连接失败执行 promote。

---

## 怎么发现

- **Prometheus alert**:`HikariCpAcquireTimeout` / `HikariCpConnectionExhausted` 覆盖应用连接池故障；`PostgresReplicationStopped`、`PostgresReplicationLagHigh`、`PostgresReplicationLagCritical` 覆盖复制链路。主库网络/进程是否真正 down 仍必须用 `pg_isready` 与容器状态确认，不能把连接池超时直接等同于主库故障。
- **Grafana 面板**:当前使用 `actuator/prometheus` 指标；专用面板由目标监控环境落地:
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
   - 已确认旧主被隔离、复制状态与可接受 RPO 经 DBA/值班负责人确认 → **方案 A**（仅 Compose 演练）
   - 主库归属、复制状态或可接受 RPO 不确定 → **方案 B**(只读降级,等待确认)
   - 主库数据卷损坏 / replica 也挂 → **方案 C**(回滚版本 + 重建)

---

## 怎么恢复

### 方案 A:promote replica 切主(2-5 min,最常用)

1. **隔离旧主并冻结写入**:确认旧主已从写流量和网络中隔离，阻止其恢复后继续接受写入；仅停止应用容器不足以防止 split-brain。若无法确认隔离，停止切主并升级 DBA/值班负责人。
   ```bash
   docker compose stop batch-orchestrator batch-trigger \
     batch-worker-import batch-worker-export batch-worker-process batch-worker-dispatch \
     batch-worker-atomic
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
     batch-worker-import batch-worker-export batch-worker-process batch-worker-dispatch \
     batch-worker-atomic
   # 等 60s 确认
   curl -sSf http://localhost:18082/actuator/health | jq .status   # 期望 UP
   ```

5. **核对 Outbox 恢复**：让 `OutboxPollScheduler` 按配置回收超时的 `PUBLISHING` 记录，并核实事件重投与下游幂等结果。不要直接 UPDATE `batch.outbox_event`；stale 回收没有人工 reset 接口时，保留证据并走 incident/开发支持流程。

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
- **观测边界**:现有应用与 postgres-exporter 告警已覆盖连接池和复制滞后；主库 down 的最终裁定仍依赖 `pg_isready`，后续只有引入稳定的 `pg_up`/服务发现标签后才新增主库可用性告警。
- **剧本走不通**:如果遇到「promote 成功但业务连不上新主」「replica lag 永远收不敛」→ 补一篇 `pg-replica-rebuild.md`。

## 关联

- 代码:`docker-compose.yml`(postgres-primary / postgres-replica),`deploy/docker/postgres-replica/entrypoint.sh`
- 配置:`ReadReplicaRoutingDataSource`(console-api 唯一读写分离落点),`docs/runbook/read-replica.md`
- 上一级:[`docs/runbook/incident-response.md`](../incident-response.md)
