# 幂等 dedup ledger 留存治理（outbox_event_dedup_key / job_instance_dedup_key）

> 适用版本:V182 起(global idempotency ledgers)。
> 关联表:`batch.outbox_event_dedup_key`、`batch.job_instance_dedup_key`。
> 关联 runbook:[pg-table-partitioning.md](./pg-table-partitioning.md)(大表分区)、[backup-and-pitr.md](./backup-and-pitr.md)(容量护栏)。

## 1. 现状:双表只增不删

V182 引入两张全局幂等台账,防止 outbox 事件重发 / job instance 重复创建(防复活):

| 表 | 主键 | 写入时机 |
|---|---|---|
| `batch.outbox_event_dedup_key` | `(tenant_id, event_key)` | 每次 outbox 事件首次落库(`OutboxEventMapper` insert 同事务写入) |
| `batch.job_instance_dedup_key` | `(tenant_id, dedup_key, run_attempt)` | 每次 job instance 创建(`JobInstanceMapper` insert 同事务写入) |

**当前没有任何 retention / 自动清理机制**:两表随业务量线性增长,永不收缩。这是有意为之——
幂等判定窗口理论上无上界(极端场景:数月后重放同 key 的消息),自动删除会打开"删完即可复活"的窗口,
所以 v1 不做自动删除,靠本 runbook 的人工/半自动归档策略兜底。

## 2. 增长量级估算

按主键行宽估算(VARCHAR(64)+VARCHAR(256)+BIGINT+2×TIMESTAMPTZ ≈ 数百字节/行,含索引放大按 ~500B/行计):

| 业务量级 | outbox 事件/天 | instance/天 | 双表年增行数 | 年增磁盘(含索引) |
|---|---|---|---|---|
| 小(单租户) | 1 万 | 2 千 | ~440 万 | ~2 GB |
| 中(压测基线 20 jobs/s 峰值折算日均) | 50 万 | 10 万 | ~2.2 亿 | ~100 GB |
| 大(多租户洪峰) | 200 万 | 50 万 | ~9 亿 | ~450 GB |

结论:小量级三五年内无感;中量级起一年内 PK btree 深度和 VACUUM 成本开始可见,**建议中量级以上按季度执行第 3 节清理**。

## 3. 建议策略:随业务行归档,N 个月后批量清

原则:**dedup 行的生命周期跟随其守护的业务行**。业务行(outbox_event / job_instance)已被归档/清理超过
保留期后,对应 dedup 行不再有幂等价值——重放同 key 的上游此时早已超时终止。

- 推荐保留期 `N = 6 个月`(≥ 2× 业务表归档周期,留足审计回溯余量;可按租户合规要求上调)。
- 清理前置条件:确认对应业务表(`batch.outbox_event` / `batch.job_instance`)中早于截止日的行已完成归档。
- 节奏:每季度一次,业务低峰窗口执行;单批 LIMIT 分批删,避免长事务和大量 WAL 突发。

## 4. 清理 SQL 模板

```sql
-- ========== 0) 评估:先看要删多少 ==========
SELECT count(*) FROM batch.outbox_event_dedup_key
 WHERE created_at < current_date - INTERVAL '6 months';
SELECT count(*) FROM batch.job_instance_dedup_key
 WHERE created_at < current_date - INTERVAL '6 months';

-- ========== 1) 分批删除(每批 5 万行,循环执行至删净) ==========
-- outbox 事件 dedup:
WITH victims AS (
    SELECT tenant_id, event_key
      FROM batch.outbox_event_dedup_key
     WHERE created_at < current_date - INTERVAL '6 months'
     LIMIT 50000
)
DELETE FROM batch.outbox_event_dedup_key k
 USING victims v
 WHERE k.tenant_id = v.tenant_id
   AND k.event_key = v.event_key;

-- job instance dedup:
WITH victims AS (
    SELECT tenant_id, dedup_key, run_attempt
      FROM batch.job_instance_dedup_key
     WHERE created_at < current_date - INTERVAL '6 months'
     LIMIT 50000
)
DELETE FROM batch.job_instance_dedup_key k
 USING victims v
 WHERE k.tenant_id = v.tenant_id
   AND k.dedup_key = v.dedup_key
   AND k.run_attempt = v.run_attempt;

-- ========== 2) 批间提交 + 观测 ==========
-- 每批之间 COMMIT 并 sleep 数秒;观察 pg_stat_activity 无长锁、复制延迟正常再继续。

-- ========== 3) 收尾:回收空间 ==========
VACUUM (ANALYZE) batch.outbox_event_dedup_key;
VACUUM (ANALYZE) batch.job_instance_dedup_key;
-- 若删除量占表比例 > 30% 且需要立刻还盘,评估 pg_repack(不锁表);普通 VACUUM 不还 OS 磁盘。
```

## 5. 风险与回退

- **误删仍在幂等窗口内的 key** → 同 key 消息重放会重新落库(重复事件/实例)。因此截止期必须
  ≥ 上游最长重试/重放窗口;拿不准就加大 N,清理宁晚勿早。
- 清理只删 dedup 台账,不动业务表;误删无数据丢失,只是失去该 key 的防重保护。
- 双表均为独立小事务写入路径,清理与在线写入无锁冲突(PK 点写 vs 老区间删),低峰执行即可。
