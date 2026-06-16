# PostgreSQL 备份 / PITR / 容量护栏 Runbook

> 2026-06-10 审计 P1-7 补。补上 `playbooks/pg-primary-failover.md` 第 §方案C 留的 TODO(「本仓未集成 PITR — TODO ops 团队补」)。
>
> **核心区分:复制 ≠ 备份。** 流复制 standby(`docker compose --profile replica`)是**高可用**——主库挂了切从库;但**误删 / 坏 migration / 逻辑损坏会同步到从库**,无法回到任意时间点。本 runbook 解决的是后者:可恢复到事故前任意时刻。

---

## 0. 适用拓扑与两个库

| | `batch_platform` | `batch_business` |
|---|---|---|
| schema 治理 | Flyway 全程纳管(V1–V169+) | **手工脚本**(`scripts/db/business/`),不走 Flyway |
| 内容 | 状态机 / outbox / 归档冷表 / 配置 | 业务源数据 + process_staging 分区 + RLS |
| 重建难点 | Flyway 可重放 schema,但**数据**仍需备份 | schema + 数据都靠备份/脚本,重建路径更弱 |

两库同住一个 `postgres-primary` 实例(docker volume `batch_postgres-primary-data`,容器内 `5432`,宿主映射 `${POSTGRES_PORT}`,默认 15432)。**备份必须同时覆盖两个库**——只备 platform 会丢全部业务数据。

> ⚠️ **Kafka 不在备份范围且 RF=1**:单 broker、`*_REPLICATION_FACTOR=1`。topic 数据丢失后,在途但未消费的派发/回报事件无法从备份恢复,只能靠 outbox(`PUBLISHED` 保留 7 天内可 republish)+ lease 超时重派兜底。详见 `playbooks/kafka-rebalance-stuck.md`。生产建议 broker ≥3 + RF=3,但那是 HA 不是本 runbook 范畴。

---

## 1. 备份策略(三层,缺一不可)

| 层 | 工具 | 频率 | 作用 | RPO |
|---|---|---|---|---|
| **A. 物理基准备份** | `pg_basebackup` | 每日 | PITR 的起点(base + 之后的 WAL = 任意时间点) | — |
| **B. WAL 连续归档** | `archive_command` → 对象存储/NFS | 实时(WAL 写满即归档) | base 之后的增量,支撑 PITR | ≤ 1 个 WAL 段(默认 16MB)/ `archive_timeout` |
| **C. 逻辑导出** | `pg_dump -Fc`(两库各一份) | 每日 | 跨大版本恢复 / 单表恢复 / 离线归档 | 24h |

**为什么三层都要**:物理备份(A+B)恢复快、支持 PITR,但绑定 PG 大版本、不能只恢一张表;逻辑备份(C)慢但跨版本、可单表/单库粒度恢复、可校验。生产事故里两种都会用到。

### 1.1 启用 WAL 归档(一次性,主库改配置)

主库需 `wal_level >= replica`(本仓 docker-compose 已是 `replica`,满足)+ 打开归档:

```ini
# postgresql.conf(或 docker command 追加 -c)
archive_mode = on
archive_command = 'test ! -f /wal-archive/%f && cp %p /wal-archive/%f'   # 本地盘示例
archive_timeout = 300        # 低峰也至少每 5min 切一个 WAL,封顶 RPO
```

> 生产应把 `archive_command` 指向**独立故障域**(S3 `aws s3 cp` / 专用 NFS),**绝不能**和 PGDATA 同一块盘——否则盘坏时 base 和 WAL 一起没。归档目录要监控可写性(见 §备份新鲜度监控)。

### 1.2 每日基准备份 + 逻辑导出(cron 脚本骨架)

放 `scripts/db/backup/pg-backup.sh`(ops 落地;以下为骨架,变量按 `.env` 注入):

```bash
#!/usr/bin/env bash
set -euo pipefail
TS=$(date -u +%Y%m%dT%H%M%SZ)
DEST=${BACKUP_DEST:?}            # 例:s3://batch-backups 或 /mnt/backup
PGHOST=${PGHOST:?}; PGPORT=${PGPORT:-5432}
export PGPASSWORD=${POSTGRES_PASSWORD:?}

# A. 物理基准备份(tar + gzip,含 PITR 所需 backup_label)
pg_basebackup -h "$PGHOST" -p "$PGPORT" -U "${POSTGRES_REPLICATION_USER:-replicator}" \
  -D - -Ft -z -Xs -P -l "base-$TS" > "/tmp/base-$TS.tar.gz"
upload "/tmp/base-$TS.tar.gz" "$DEST/base/base-$TS.tar.gz"

# C. 逻辑导出 —— 两个库各一份(custom format,支持并行恢复 + 单表 -t)
for DB in batch_platform batch_business; do
  pg_dump -h "$PGHOST" -p "$PGPORT" -U "${POSTGRES_USER:-batch_user}" \
    -Fc -Z6 -f "/tmp/$DB-$TS.dump" "$DB"
  upload "/tmp/$DB-$TS.dump" "$DEST/logical/$DB-$TS.dump"
done

# 备份成功 → push 新鲜度指标到 Pushgateway(见 §备份新鲜度监控)
push_backup_freshness_metric "$(date +%s)"

# 保留策略:物理/WAL 保留 14 天,逻辑保留 35 天(按合规要求调)
prune_older_than "$DEST/base" 14d
prune_older_than "$DEST/logical" 35d
```

> `pg_basebackup` 用 replication 用户(`replicator`,docker init `003-create-replication-user.sh` 已建)。`-Xs`(stream WAL)保证 base 自洽。逻辑导出走业务用户即可。

### 1.3 cron 编排

```cron
# 每日 02:00 UTC base + 逻辑导出(避开 03:30 outbox 归档 / 04:x run 表归档,减少锁竞争)
0 2 * * *  /opt/batch/scripts/db/backup/pg-backup.sh >> /var/log/batch/pg-backup.log 2>&1
```

WAL 归档是 `archive_command` 实时触发的,不进 cron。

> **docker-compose 里的 archive 配置(dev 默认关)**:`docker-compose.yml` 的 `postgres-primary` 已带注释版 `archive_mode=on` / `archive_command` / `archive_timeout=300` + `wal-archive` 卷示例,**默认注释关闭**(dev 开了无意义且占盘)。生产开 PITR:解开这 3 行 + 卷挂载,并把 `archive_command` 目标改成独立故障域(S3/专用 NFS),见 §1.1。

---

## 1.4 RTO / RPO SLO(量化目标)

容灾不是"有备份"就行,得有**可度量的恢复目标**,否则无法判断备份策略是否达标、演练是否退化。

| 指标 | 定义 | **SLO 目标** | 依据 / 由什么保证 |
|---|---|---|---|
| **RPO**(可容忍数据丢失) | 灾难时点 → 最近可恢复点 的时间差 | **≤ 5 min** | WAL 连续归档 `archive_timeout=300`(§1.1)封顶:低峰也每 5min 切一个 WAL 段归档,故最坏丢 5min。逻辑 dump(C 层)RPO=24h,仅作跨版本/单表兜底,不是主 RPO。 |
| **RTO**(恢复耗时) | 开始恢复 → 应用可服务 的耗时 | **≤ 30 min** | 单实例两库:base 解包 + WAL replay 到目标点 + 应用拉起健康。`pg_restore -j4` 并行 + base streamed(`-Xs`)。`dr-drill.sh` 实测本地逻辑恢复远小于此(秒级),生产真实 RTO 受 dump 体积/网络/盘速影响,30min 是含人工介入的保守上限。 |

**SLO 选值依据**:本系统是批量控制面,非 7×24 在线交易;主链 `DB→Outbox→Kafka→CLAIM→EXECUTE→REPORT` 在恢复后靠 lease 超时重派 + outbox republish **自愈收敛**(见 `ha-readiness.md` P0-5),允许分钟级恢复窗口,无需亚分钟 RTO/RPO(那需要同步多副本 + 自动 failover,成本与收益不匹配)。若未来 SLA 收紧:RPO 靠缩短 `archive_timeout` + 流复制 standby 同步提交;RTO 靠 Patroni 自动 failover(P0-2)+ 预热待命实例。

**达标守护**:`dr-drill.sh` 演练对实测 RTO 做阈值断言(默认 `RTO_SLO_SECONDS=1800`=30min;`--strict-rto` 时超阈值直接 fail,否则 WARN)。把 SLO 从"文档数字"变成"演练里会红的断言",防恢复链路悄悄退化(dump 膨胀、并行度丢失)。

---

## 2. 恢复演练(**上线前必须至少跑一次**)

> 审计结论:有 failover playbook ≠ 有备份。**没演练过的备份等于没有备份**——归档断了、base 不自洽、恢复步骤错都只有演练才暴露。在**非生产**实例上完整跑一遍并记录 RTO。
>
> **本地一键演练(逻辑恢复 §2.1 + 校验 §2.3 自动化)**:`bash scripts/db/backup/dr-drill.sh`
> ——对本地 docker compose 栈做 备份→恢复到旁路库 `*_dr`→按事故前快照比对关键表/Flyway/RLS→量 RTO,默认不动现有数据;`--in-place --yes` 做破坏性真实演练。这把"演练过"从口头变成可重复执行的证据。覆盖灾备的"数据可恢复"半,"应用重放收敛"半见 chaos `*ToxicIT` + 起服务连恢复库。

### 2.1 演练 A:逻辑全量恢复(最常用,跨版本安全)

```bash
# 1. 起一个干净的目标 PG(空实例,版本 ≥ 源)
# 2. 建空库
createdb -h $TARGET -U postgres batch_platform
createdb -h $TARGET -U postgres batch_business
# 3. 并行恢复(-j 加速;custom format 才支持)
pg_restore -h $TARGET -U postgres -d batch_platform -j4 --clean --if-exists batch_platform-<TS>.dump
pg_restore -h $TARGET -U postgres -d batch_business -j4 --clean --if-exists batch_business-<TS>.dump
# 4. business 库恢复后必须重跑 RLS(逻辑 dump 不带 role/policy 的完整保证)
psql -h $TARGET -d batch_business -f scripts/db/business/rls-phase-a.sql
# 5. 校验(见 §2.3)
```

### 2.2 演练 B:PITR 恢复到事故前某时刻(物理 base + WAL)

适用「14:32 误删了一批数据,要回到 14:31」:

```bash
# 1. 解开最近一份 base 到目标 PGDATA
mkdir -p /restore/pgdata && tar xzf base-<TS>.tar.gz -C /restore/pgdata
# 2. 配 recovery(PG12+ 用 postgresql.auto.conf + recovery.signal)
cat >> /restore/pgdata/postgresql.auto.conf <<EOF
restore_command = 'cp /wal-archive/%f %p'
recovery_target_time = '2026-06-10 14:31:00+00'
recovery_target_action = 'promote'
EOF
touch /restore/pgdata/recovery.signal
# 3. 用同版本 PG 镜像挂这个 PGDATA 启动 → 自动 replay WAL 到目标时刻后 promote
# 4. 起来后是恢复到 14:31 的完整实例(platform + business 都在)。校验后再切流量。
```

> PITR 恢复出的是**整实例**(两库一起回到该时刻),不能只回滚单库到某时刻。若只想救单表,优先用逻辑 dump 的 `pg_restore -t <table>` 到旁路库再 `INSERT ... SELECT` 捞回。

### 2.3 恢复校验清单(演练 / 真实恢复都要过)

- [ ] 行数比对:关键表(`job_instance` / `outbox_event` / `biz.*`)与事故前快照/预期一致
- [ ] Flyway 状态:`SELECT max(version) FROM flyway_schema_history` 与代码 `db/migration` 最高版本一致
- [ ] business 库:RLS policy 已恢复(`SELECT count(*) FROM pg_policies WHERE schemaname='biz'` ≥ 10),`ArchiveSchemaDriftCheck` 启动不 fail
- [ ] 应用启动:console-api / orchestrator 起得来、health UP、能登录
- [ ] 记录 **RTO**(从开始恢复到应用可服务的耗时)进演练记录

---

## 3. 备份新鲜度监控

备份脚本成功后 push 一个 unix 时间戳指标,Prometheus 据此告警「备份断了」(见 `prometheus-batch-rules.yml` 的 `PostgresBackupStale`):

```bash
# pg-backup.sh 末尾,成功后:
push_backup_freshness_metric() {
  cat <<EOF | curl --data-binary @- "${PUSHGATEWAY_URL}/metrics/job/pg-backup"
# TYPE batch_pg_last_successful_backup_timestamp_seconds gauge
batch_pg_last_successful_backup_timestamp_seconds $1
EOF
}
```

`PostgresBackupStale` 在指标 > 26h 未更新**或缺失(absent)**时 critical 告警——备份 cron 死了 / 归档目标不可写都会触发。这是数据安全底线告警,severity=critical。

---

## 4. 磁盘水位与容量护栏

(`prometheus-batch-rules.yml` group `batch.capacity`,2026-06-10 P2-7 补。)

| 告警 | 触发 | 处置 |
|---|---|---|
| `NodeDiskSpaceLow` | 数据盘可用 < 15% / 10m | 查 §库体积增长治理,确认归档/清理在跑 |
| `NodeDiskSpaceCritical` | 可用 < 7% / 5m | 立刻:DROP 过期分区 / 清归档表 / 扩盘(PG 写不了 WAL 就停服) |
| `NodeDiskWillFillIn4Hours` | 近 1h 斜率外推 4h 写满 | 争取处置窗口,定位无界增长源 |

**应急腾空间**(critical 时按风险从低到高):
1. DROP 过期 `process_staging` 日分区(DDL 瞬间还盘给 OS;DELETE 不缩文件):见 `ProcessStagingOrphanCleaner` / `pg-table-partitioning.md`。
2. 手动跑归档调度补归档 + 清 outbox `PUBLISHED` 旧数据(走 console → orchestrator `/internal/outbox/*`,**禁直接 DELETE**)。
3. WAL 归档目录积压(slot inactive 导致):见 `PostgresReplicationSlotInactive` 告警 + failover playbook。
4. 扩盘 / 迁移卷。

> 历史教训:`process_staging` 曾积压 118GB dead、Docker.raw 147G bloat 才被发现——就是因为缺这层兜底。

---

## 5. 库体积增长治理

`PostgresDatabaseGrowthHigh`(6h 净增 >10GB)/ `PostgresDatabaseSizeHigh`(绝对 >200GB,按盘容量调阈值)是**归档失效的最早可观测信号**。增长异常时排查顺序:

1. **归档/清理调度是否在跑**(当前无 last-success 指标,看日志 + ShedLock):
   - `OutboxArchiveScheduler`(outbox_event PUBLISHED → 归档,默认 03:30,ShedLock `outbox_archive`)
   - `WorkflowArchiveScheduler` / job_instance 归档(04:x)
   - `ProcessStagingOrphanCleaner`(process_staging 分区 DROP + 孤儿行清理)
   - `FileGovernanceScheduler`(file_record / upload session TTL 清理)
   排查:对应 `shedlock` 行 `lock_until` 是否卡在过去(持锁进程死了没释放)、scheduler 日志有无异常堆栈。
2. **保留策略是否符合预期**:`batch.outbox.archive.*` / run 表归档保留天数配置。
3. **分区是否漏建/漏 DROP**:`process_staging` 是否按天分区、retentionDays 是否生效。

> **后续改进(本次未做)**:给上述 scheduler 加 `batch_*_archive_last_success_timestamp_seconds` Gauge,即可把「哪个调度停了」从"看日志"升级为精确告警 `time() - batch_outbox_archive_last_success_seconds > 7200`。届时补进 `batch.capacity` group。

---

## 关联

- `playbooks/pg-primary-failover.md` — 主库故障切主(HA;本 runbook 补它 §方案C 的 PITR TODO)
- `pg-table-partitioning.md` — process_staging 分区 DROP 回收空间
- `read-replica.md` — 读写分离 / standby 语义(为何 standby 不是备份)
- `docs/analysis/2026-06-10-full-audit.md` — 本 runbook 的来源(P1-7 / P2-7)
