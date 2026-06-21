# PG 表分区运维手册

针对千万级以上数据量场景：把 `outbox_event`（按 `created_at` 月分区）和 `job_instance`
（按 `biz_date` 月分区）改造为 PG 原生 PARTITION BY RANGE 表。

## 何时做这件事

**不要预先做**。这条改造**风险较高且不可逆**（PK 改了、FK 全部去掉），只有真撞瓶颈才动：

- 单表行数 > 5000 万
- DELETE 老数据要几小时
- VACUUM 跟不上 dead tuple
- 索引大小 > 内存能放下的程度

如果只是"防患于未然"，先用 `archive` 调度器（已交付：commit `18577c0c`）控制表大小。
分区表的最大收益是 **DROP 老分区毫秒级** vs **DELETE 老数据慢 + 锁表**，archive 调度器能把数据控制在合理范围下时分区不是必需。

## 核心权衡（决策点）

| 项 | 不分区 | 分区 |
|---|---|---|
| PK | 单列 `id` | 复合 `(id, created_at)` 或 `(id, biz_date)` |
| FK 约束 | DB 强制（FK 一致性） | **必须去掉** PG 不支持跨分区 FK；改应用层守护 |
| 老数据清理 | DELETE（慢、锁、产生 dead tuple） | DROP PARTITION（毫秒、无 dead tuple） |
| 跨期间查询 | 全表扫 | 自动分区裁剪（命中相关分区） |
| 单分区维护 | 不可单独 VACUUM | 单独 VACUUM/REINDEX |
| 查询计划复杂度 | 简单 | partition pruning 失败时全分区扫，慢 |

## 操作流程

### 一、前置准备

1. **数据备份**：
   ```bash
   pg_dump -U batch_user -d batch_platform -t 'batch.outbox_event' \
     -t 'batch.job_instance' -t 'batch.event_delivery_log' \
     -F custom -f /tmp/pre-partition-backup.dump
   ```

2. **业务停机或只读**（必需）：
   - 修改业务网关：trigger / orchestrator / console-api 进入维护模式
   - 等所有 in-flight 任务收尾（ShedLock 释放、worker 心跳归零）
   - 或 `BATCH_SECURITY_BYPASS_MODE=true` + 业务侧停止 launch

3. **staging 完整跑一遍**：用同样的脚本在 staging 预演，包括所有 e2e 测试。

### 二、执行迁移

```bash
# 1) outbox_event 改造
PGPASSWORD=... psql -h <prod-host> -U batch_user -d batch_platform \
  -v ON_ERROR_STOP=1 \
  -f scripts/db/partition-migration/01-outbox-event-partitioned.sql

# 2) job_instance 改造（更复杂，FK 多，先确认 outbox 改造完无异常再做）
PGPASSWORD=... psql -h <prod-host> -U batch_user -d batch_platform \
  -v ON_ERROR_STOP=1 \
  -f scripts/db/partition-migration/02-job-instance-partitioned.sql
```

每个脚本包含：
1. 建分区父表（PK 含分区键）
2. 建近 24 月 + 后 12 月 + DEFAULT 分区
3. 复制数据 `INSERT INTO ... SELECT`
4. 解 FK / 改名 / 切换

执行完应该看到：
```
=== 总行数 vs legacy 行数（应一致） ===
 new_count | legacy_count
-----------+--------------
   12345678|     12345678
```

### 三、验证

```bash
# 业务侧端到端：launch 一个测试任务，看 outbox 和 job_instance 落对分区
docker exec batch-postgres psql -U batch_user -d batch_platform -c "
SELECT inhrelid::regclass AS partition, pg_size_pretty(pg_relation_size(inhrelid)) AS size
FROM pg_inherits WHERE inhparent='batch.outbox_event'::regclass
ORDER BY partition LIMIT 5;
"

# 检查 archive 调度器还能正常跑（PG cron 等）
docker exec batch-postgres psql -U batch_user -d batch_platform -c "
SELECT count(*) FROM batch.outbox_event WHERE publish_status='PUBLISHED';
"
```

主链路 e2e 通过后：

```sql
-- 删 legacy 表（释放磁盘）
DROP TABLE batch.outbox_event_legacy CASCADE;
DROP TABLE batch.job_instance_legacy CASCADE;
```

### 四、长期维护

每月 1 号 02:00 自动建未来分区（防 DEFAULT 分区接到数据后无法回退）：

```bash
# crontab
0 2 1 * *  PGPASSWORD=$PGPW psql -U batch_user -d batch_platform \
             -v ON_ERROR_STOP=1 -v months_ahead=6 \
             -f /opt/batch/scripts/db/partition-migration/03-add-future-partitions.sql
```

或用 `pg_partman` 扩展自动化（推荐，避免手工 cron）。

### 五、与 archive 调度器配合

分区改造后，`OutboxArchiveScheduler` / `SuccessInstanceArchiveScheduler` 仍然工作（DELETE 还能跑）。但**额外可以做**：

```sql
-- 直接 DROP 老分区（比 DELETE 快几个数量级）
ALTER TABLE batch.outbox_event DETACH PARTITION batch.outbox_event_p_2025_01;
DROP TABLE batch.outbox_event_p_2025_01;
```

这部分自动化未实现，本期改造仅交付分区结构 + 手工运维脚本。如需 detach 调度器：

```java
// 后续可加 PartitionDropScheduler，月初跑一次：
//   1. 找出 created_at < cutoff 的所有分区名
//   2. ALTER TABLE ... DETACH PARTITION
//   3. DROP TABLE
// 同 P3-3 archive 调度器模式
```

## 回滚

**步骤二完成后基本不可逆**——只能从 legacy 表手工重建：

```sql
ALTER TABLE batch.outbox_event RENAME TO outbox_event_partitioned_failed;
ALTER TABLE batch.outbox_event_legacy RENAME TO outbox_event;
```

但 outbox_event_partitioned_failed 期间产生的新数据会丢（除非手工 INSERT 回去）。
**强烈建议**步骤二在维护窗口完成 + 跑完全套 e2e 后再删 legacy 表，留几天观察期。

## 相关文件

- `scripts/db/partition-migration/01-outbox-event-partitioned.sql` — outbox 迁移
- `scripts/db/partition-migration/02-job-instance-partitioned.sql` — job_instance 迁移
- `scripts/db/partition-migration/03-add-future-partitions.sql` — 月度维护
- `OutboxArchiveScheduler` / `SuccessInstanceArchiveScheduler` — archive 自动化（commit `18577c0c`）
- `cleanup-outbox-events.sql` / `cleanup-success-instances.sql` — 手工回退
