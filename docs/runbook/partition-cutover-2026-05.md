# 大表分区 cutover 执行手册（DBA-2026-05-20 P0-1 / P0-2）

> 目标：把 `outbox_event` 与 `job_instance` 从普通表改造为按月分区（RANGE on `created_at` / `biz_date`），解除"无界增长 → 单 SELECT 退化 → 调度延迟"的最后一道增长红线。
>
> 本手册串起 `scripts/db/partition-migration/01-*.sql` / `02-*.sql` / `03-add-future-partitions.sql`，给出**完整的预演 → cutover → 校验 → 回滚**流程。

---

## 1. 总体策略

| 阶段 | 环境 | 时长 | 风险 | 谁来做 |
|---|---|---|---|---|
| Phase 0 — 本地预演 | dev | 0.5 天 | 低 | 工程师 |
| Phase 1 — staging 复盘 | staging（生产快照） | 1 天 | 中 | 工程师 + DBA |
| Phase 2 — 生产 cutover | prod | 维护窗口 1-2 小时 | 高 | DBA + ops + on-call |
| Phase 3 — 验证 + 24h 观察 | prod | 1 天 | 中 | on-call |
| Phase 4 — 物理 DROP legacy | prod | 5 min | 低 | DBA |

**严禁**：跳过 Phase 1 直接在 prod 跑 cutover。脚本是 DDL + 数据迁移混合，错误回滚成本高。

---

## 2. 前置条件

- [ ] 通信：维护窗口已与业务方对齐，trigger / orchestrator / worker 全部进入 maintenance-mode（参考 `runbook/maintenance-mode.md`），暂停所有写入路径。
- [ ] 容量：磁盘可用空间 ≥ 当前 `outbox_event` + `job_instance` 表合计大小的 **2.5 倍**（迁移期间双份数据 + WAL 翻倍）。
- [ ] 备份：执行前 `pg_dump --schema=batch -t outbox_event -t job_instance` 留存到独立卷。
- [ ] Flyway 暂停：本批改造**不走 Flyway**（它的事务包裹 + 顺序模型与 cutover 冲突），需手动设 `spring.flyway.enabled=false` 临时启动一次确认无 pending 迁移，或在 ops 工具里挂"维护模式"标记。
- [ ] `ArchiveSchemaDriftCheck` 已扩展（PR-B 已加 `trigger_outbox_event` / `dead_letter_task`，确认无残留漂移）。
- [ ] 监控：Grafana `batch.outbox.pending.events` / `batch.trigger.outbox.pending.events` / `batch.scheduler.*` 看板就位。

---

## 3. Phase 0 — 本地预演

目标：跑通完整脚本，记录每步耗时。

```bash
# 1. 起干净 PG（避免与日常 dev 冲突）
docker run -d --name pg-cutover -p 15500:5432 \
    -e POSTGRES_PASSWORD=test postgres:17

# 2. 装最新 schema（V1-V143）
psql -h localhost -p 15500 -U postgres -f docker/postgres/init/000-create-business-db.sql
# 跑全部 Flyway migration 到 V143
mvn -pl batch-orchestrator -am flyway:migrate \
    -Dflyway.url=jdbc:postgresql://localhost:15500/batch_platform

# 3. 灌测试数据（每张表 1-10 万行模拟）
psql ... -f scripts/db/test-seed/seed-outbox-events.sql  # 若无,可用 generate_series
psql ... -f scripts/db/test-seed/seed-job-instances.sql

# 4. 执行 cutover
psql ... -v ON_ERROR_STOP=1 -f scripts/db/partition-migration/01-outbox-event-partitioned.sql
psql ... -v ON_ERROR_STOP=1 -f scripts/db/partition-migration/02-job-instance-partitioned.sql

# 5. 校验
psql ... -c "SELECT count(*) FROM batch.outbox_event"
psql ... -c "SELECT count(*) FROM batch.outbox_event_legacy"   -- 必须一致

# 6. 启 orchestrator 跑 IT
mvn -pl batch-orchestrator verify -Dspring.profiles.active=cutover-test
```

记录指标到 `partition-cutover-runbook-log.md`：
- 01 脚本 INSERT INTO ... SELECT 耗时（行数 × 时长）
- 02 脚本同上
- 重建索引耗时
- IT 全部通过耗时

---

## 4. Phase 1 — staging 复盘

用生产快照（最新一次 `pg_dump`）灌进 staging：

```bash
# 1. 灌生产快照
pg_restore -h staging-pg -U batch_user -d batch_platform_staging \
    /backup/prod-2026-05-20.dump

# 2. 关闭 staging 业务流量(或维护模式)
kubectl scale --replicas=0 deploy/batch-trigger -n staging
kubectl scale --replicas=0 deploy/batch-orchestrator -n staging
kubectl scale --replicas=0 deploy/batch-worker-* -n staging

# 3. 跑 cutover 同 Phase 0
psql ... -f scripts/db/partition-migration/01-outbox-event-partitioned.sql
psql ... -f scripts/db/partition-migration/02-job-instance-partitioned.sql

# 4. 重启业务,跑端到端冒烟
kubectl scale --replicas=1 deploy/batch-trigger -n staging
# ... 触发完整 trigger → orchestrator → worker → 报告链路
```

验证清单：
- [ ] 所有 IT (`batch-orchestrator verify`) 通过
- [ ] Grafana 上看 outbox poll latency 与 cutover 前持平或更优
- [ ] `EXPLAIN ANALYZE` 抽 5 个 console 列表查询，确认 partition pruning 生效
- [ ] ArchiveSchemaDriftCheck 启动期无 fail
- [ ] 24 小时灰度无 ERROR 日志告警

**Phase 1 失败**：staging 直接 rollback（`DROP TABLE outbox_event CASCADE; ALTER TABLE outbox_event_legacy RENAME TO outbox_event`），分析根因后从 Phase 0 重启。

---

## 5. Phase 2 — 生产 cutover

### 5.1 维护窗口启动

```bash
# 1. 维护模式 banner
ops-tool maintenance enable --window=120m \
    --reason="DB partition cutover, see RFC-XYZ"

# 2. 业务流量停（与运维同步）
kubectl scale --replicas=0 deploy/batch-trigger -n prod
# orchestrator / worker 保留 1 副本以 drain 在途请求
kubectl rollout restart deploy/batch-orchestrator -n prod  # 走 graceful drain
sleep 60
kubectl scale --replicas=0 deploy/batch-orchestrator -n prod
kubectl scale --replicas=0 deploy/batch-worker-* -n prod

# 3. 确认无写入
psql ... -c "
SELECT
    count(*) FILTER (WHERE updated_at > now() - interval '30 seconds') AS recent_writes
FROM batch.outbox_event;
"
# 必须为 0
```

### 5.2 备份 + 跑 cutover

```bash
# 1. 双重备份（schema 级 + 表级）
pg_dump -h prod-pg -U batch_user -d batch_platform \
    --schema=batch -t outbox_event -t job_instance \
    -f /backup/cutover-${DATE}.dump --format=custom

# 2. 跑 01 + 02
psql ... -v ON_ERROR_STOP=1 -f scripts/db/partition-migration/01-outbox-event-partitioned.sql \
    | tee /var/log/batch/cutover-01-${DATE}.log

psql ... -v ON_ERROR_STOP=1 -f scripts/db/partition-migration/02-job-instance-partitioned.sql \
    | tee /var/log/batch/cutover-02-${DATE}.log

# 3. 即时校验
psql ... <<SQL
SELECT 'outbox' AS t, count(*) FROM batch.outbox_event
UNION ALL
SELECT 'outbox_legacy', count(*) FROM batch.outbox_event_legacy
UNION ALL
SELECT 'job_instance', count(*) FROM batch.job_instance
UNION ALL
SELECT 'job_instance_legacy', count(*) FROM batch.job_instance_legacy;
SQL
# new vs legacy 必须一致
```

### 5.3 恢复业务

```bash
# 1. 重启业务
kubectl scale --replicas=N deploy/batch-orchestrator -n prod
kubectl scale --replicas=N deploy/batch-worker-* -n prod
kubectl scale --replicas=N deploy/batch-trigger -n prod

# 2. 解除维护模式
ops-tool maintenance disable

# 3. 前 30 分钟密切观察
watch -n 5 'psql ... -c "
SELECT publish_status, count(*) FROM batch.outbox_event GROUP BY 1
UNION ALL
SELECT instance_status, count(*) FROM batch.job_instance GROUP BY 1"'
```

---

## 6. Phase 3 — 验证 + 24h 观察

24 小时内监控：

| 指标 | 阈值 | 异常处理 |
|---|---|---|
| `batch.outbox.pending.events` | 与 cutover 前对比 ±20% | 超出 → 检查 partition pruning 是否生效 |
| `batch.scheduler.*` p99 latency | ≤ cutover 前 110% | 超出 → 查 EXPLAIN 确认走分区 |
| `batch.trigger.outbox.publish.latency` p99 | ≤ cutover 前 110% | 同上 |
| ERROR 日志告警数 | 0 | 任何 schema 错误 → 立即回滚 |

24h 后无异常 → 进入 Phase 4。

---

## 7. Phase 4 — 物理 DROP legacy

```sql
BEGIN;
DROP TABLE batch.outbox_event_legacy CASCADE;
DROP TABLE batch.job_instance_legacy CASCADE;
COMMIT;
```

DROP 后释放 ~ 原表大小的磁盘（PG 不会自动 shrink 文件系统，但 autovacuum 会回收页面）。

---

## 8. 回滚预案

任意 phase 失败：

```sql
BEGIN;
-- 1. 解除 FK 等任何 cutover 后建的依赖
-- 2. 反向 RENAME
DROP TABLE batch.outbox_event CASCADE;
ALTER TABLE batch.outbox_event_legacy RENAME TO outbox_event;
-- 3. 重建索引
\i scripts/db/partition-migration/rollback-restore-indexes.sql  -- 待补
COMMIT;
```

⚠️ **rollback 假设 legacy 表还在**。Phase 4 DROP legacy 后再失败已无回滚路径，只能从 `/backup/cutover-${DATE}.dump` 全表 restore，预计 ≥ 30 min 停机。

---

## 9. 配套自动化

- **未来分区自动建**：`scripts/db/partition-migration/03-add-future-partitions.sql` 已就绪，cutover 后挂 cron 每月 15 号执行（提前一个月建下月分区，预防 DEFAULT 分区接负担）。
- **Flyway V134-V143**：cutover 不影响 Flyway，新表名（outbox_event）与旧一致，后续 ALTER 走正常 Flyway 流程。
- **ArchiveSchemaDriftCheck**：注意 archive.outbox_event_archive 仍是普通表（不分区），cutover 后 hot 表 schema 不变，drift check 仍 pass。

---

## 10. 时间表（建议）

| 周次 | 阶段 |
|---|---|
| W+0 | Phase 0 本地预演 |
| W+1 | Phase 1 staging 复盘 |
| W+2 | RFC 评审 + 维护窗口审批 |
| W+3 周末 | Phase 2 生产 cutover |
| W+3+1d | Phase 3 验证 |
| W+4 | Phase 4 DROP legacy |

总计 4-5 周从决策到清账。
