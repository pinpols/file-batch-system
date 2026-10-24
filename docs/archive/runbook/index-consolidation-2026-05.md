# 索引整合执行手册（DBA-2026-05-20 P1-1 / P1-2）

> V143 已完成"加新"；本手册指引"取证 → 灰度 → DROP"。**禁止在没有 `pg_stat_user_indexes` 数据的情况下直接 DROP**。

## 1. 背景

DBA 审查报告 §5.1 指出 `job_instance` 与 `workflow_run` 在 V8 / V124 / V133 之间累积了重叠索引。报告建议合并，但合并的前提是有生产侧索引使用率证据。

直接 DROP 风险：
- 单 PR ADD + DROP 一旦 DROP 错索引，必须再发一版 V144 重建，期间慢查询。
- Postgres 不支持索引 rollback；DROP 是即时生效。

正确路径：先在 V143 加新索引，灰度 1-2 周观察生产取证，确认老索引零使用后再发 V144 DROP。

## 2. 取证：导出 idx_scan 统计

在生产 read-replica 上执行：

```sql
SELECT
    schemaname,
    relname  AS table,
    indexrelname AS index,
    idx_scan,
    idx_tup_read,
    idx_tup_fetch,
    pg_size_pretty(pg_relation_size(indexrelid)) AS size
FROM pg_stat_user_indexes
WHERE schemaname = 'batch'
  AND relname IN ('job_instance','workflow_run','file_record')
ORDER BY relname, idx_scan ASC;
```

**判定标准**（基于灰度窗口 ≥ 7 天）：
- `idx_scan = 0` 且 `idx_tup_read = 0` → 强 DROP 候选
- `idx_scan < 100/天` → 弱 DROP 候选，结合查询日志确认场景
- 其他 → 保留

## 3. DROP 候选清单

### 3.1 job_instance

| 索引 | V版本 | 列 | 是否被 V143/V133 覆盖 | DROP 条件 |
|---|---|---|---|---|
| `idx_job_instance_job_status` | V8 | (tenant_id, job_code, instance_status) | 否（job_code 维度独有） | **不建议 DROP** |
| `idx_job_instance_biz_date` | V8 | (tenant_id, biz_date, instance_status) | 部分被 V143 `idx_job_instance_tenant_bizdate_status` 覆盖（DESC 差异允许 backward scan） | idx_scan=0 → DROP |
| `idx_job_instance_created_at` | V8 | (created_at) | 否 | **不建议 DROP** |
| `idx_job_instance_trace_id` | V8 | (trace_id) | 否 | **不建议 DROP** |
| `idx_job_instance_tenant_status_started` | V133 | (tenant_id, instance_status, started_at DESC) | 否 | 保留 |

### 3.2 workflow_run

| 索引 | V版本 | 列 | 覆盖关系 | DROP 条件 |
|---|---|---|---|---|
| `idx_workflow_run_status` | V8 | (tenant_id, run_status, started_at) | 与 V133 `idx_workflow_run_tenant_status_bizdate` 高度重叠 | idx_scan=0 → DROP |
| `idx_workflow_run_job_instance` | V8 | (related_job_instance_id) | 否（被 selectByRelatedJobInstanceId 使用） | **不建议 DROP** |

## 4. DROP 模板（确认取证后再执行）

```sql
-- V144__drop_redundant_indexes_after_witness.sql (示例, 待取证后填空)
-- 前提:
--   1. V143 部署 ≥ 7 天
--   2. pg_stat_user_indexes 显示候选索引 idx_scan = 0
--   3. ops 在维护窗口或低峰执行

BEGIN;

DROP INDEX IF EXISTS batch.idx_job_instance_biz_date;
DROP INDEX IF EXISTS batch.idx_workflow_run_status;

COMMIT;
```

⚠️ **不要带其它逻辑**。DROP 失败 / 错删时, 单纯 CREATE INDEX 即可恢复（无数据风险, 只是构建期写入慢）。

## 5. 异常处理

- **DROP 后慢查询激增**：立刻 CREATE INDEX 恢复（CONCURRENTLY 可在线建，避免再次锁表）。
- **idx_scan 突然为零但实际仍被使用**：可能 query plan 缓存或 pg_stat_user_indexes 被 reset。延长观察窗口到 14 天再判定。
- **partial 索引使用 EXPLAIN 没命中**：检查 WHERE 谓词是否完全包含在索引 partial 条件内, Postgres 要求完全匹配。

## 6. 状态追踪

| 日期 | 阶段 | 操作 |
|---|---|---|
| 2026-05-21 | V143 加新 | 完成 |
| TBD | 取证窗口开启 | 部署到 staging, 跑业务流量 |
| TBD | DROP 决策 | 基于 idx_scan 数据填空 §4 模板 |
| TBD | V144 落地 | DROP 旧索引 |

## 7. 取证查询模板（粘贴即用）

针对 `job_instance` / `workflow_run` 候选索引,直接在生产 read-replica 粘贴执行:

```sql
SELECT relname,
       indexrelname,
       idx_scan,
       idx_tup_read,
       pg_size_pretty(pg_relation_size(indexrelid)) AS size
  FROM pg_stat_user_indexes
 WHERE schemaname = 'batch'
   AND relname IN ('job_instance','workflow_run')
 ORDER BY idx_scan ASC
 LIMIT 30;
```

按 `idx_scan ASC` 排序,前几行就是最少被使用的索引 — 大概率出现在候选 DROP 列表里。

附加查询(看索引体积 vs 表体积比,辅助判断 DROP 收益):

```sql
SELECT i.indexrelname,
       pg_size_pretty(pg_relation_size(i.indexrelid))                 AS idx_size,
       pg_size_pretty(pg_relation_size(c.oid))                        AS table_size,
       round(100.0 * pg_relation_size(i.indexrelid)
                   / NULLIF(pg_relation_size(c.oid), 0), 2)           AS pct_of_table
  FROM pg_stat_user_indexes i
  JOIN pg_class c ON c.oid = i.relid
 WHERE i.schemaname = 'batch'
   AND i.relname IN ('job_instance','workflow_run')
 ORDER BY pg_relation_size(i.indexrelid) DESC;
```

## 8. 判定矩阵

| 信号 | 观察窗口 | 强 DROP | 弱候选(需复核) | 保留 |
|---|---|---|---|---|
| `idx_scan = 0` 且 `idx_tup_read = 0` | ≥ 7 天 | ✓ | — | — |
| `idx_scan < 100/天` | ≥ 7 天 | — | ✓(查 slow log 找剩余调用方) | — |
| `idx_scan` 持续增长 | 任意 | — | — | ✓ |
| 被 UNIQUE 约束依赖 | — | — | — | ✓(DROP 会破坏约束) |
| 被外键支持(referencing FK column) | — | — | — | ✓(DROP 会引发 FK 锁升级) |

**弱候选 → 强 DROP 的升级路径**:延长窗口到 14 天后 idx_scan 仍 < 100/天 + slow log 无相关查询 → 升级到强 DROP。

**红线**:`pg_index.indisunique = true` 或被外键约束引用的索引,任何情况下都不在 DROP 表里。先在取证 SQL 上加过滤:

```sql
SELECT i.indexrelname
  FROM pg_stat_user_indexes i
  JOIN pg_index x ON x.indexrelid = i.indexrelid
 WHERE i.schemaname = 'batch'
   AND i.relname IN ('job_instance','workflow_run')
   AND x.indisunique = false
   AND x.indisprimary = false
   AND i.indexrelname NOT IN (
       SELECT conname FROM pg_constraint WHERE contype IN ('p','u','f')
   )
 ORDER BY i.idx_scan ASC;
```

## 9. V151 DROP 模板（待取证后填空）

> V148 / V149 / V150 已被其它 migration 占用(`V148__cleanup_orphan_default_tenant.sql` /
> `V149__role_redesign_config_admin_upgrade.sql` / `V150__jsonb_partial_gin_index.sql`),
> 本轮 DROP migration 占用下一个空闲号 V151。

**文件**:`db/migration/V151__drop_redundant_indexes_after_witness.sql`

```sql
-- =============================================================================
-- V151: DROP 灰度后零使用的冗余索引 (DBA-2026-05 索引整合 收尾)
--
-- 前提:
--   1. V143 已部署 ≥ 7 天
--   2. §7 取证查询确认下列索引 idx_scan = 0
--   3. §8 判定矩阵确认非 UNIQUE / 非 FK 依赖
--   4. ops 在维护窗口或低峰执行(DROP INDEX 是 ACCESS EXCLUSIVE 锁,瞬时但需 wait_lock)
--
-- 回滚:DROP 后慢查询若激增,CONCURRENTLY 重建:
--   CREATE INDEX CONCURRENTLY idx_xxx ON batch.xxx (...);
-- =============================================================================

BEGIN;

-- 取证后填入实际 DROP 列表 — 以下为占位,生产前必须按 §7 输出替换:
-- DROP INDEX IF EXISTS batch.idx_job_instance_biz_date;
-- DROP INDEX IF EXISTS batch.idx_workflow_run_status;

-- ANALYZE 让 planner 立即放弃缓存的 plan,避免 stale plan 误用 DROP 掉的索引
-- ANALYZE batch.job_instance;
-- ANALYZE batch.workflow_run;

COMMIT;
```

**填空步骤**:
1. 运行 §7 第一段 SQL,导出 `idx_scan = 0` 的索引名清单
2. 对每个候选,跑 §8 红线 SQL 排除 UNIQUE / FK 依赖
3. 把幸存清单粘到本模板 `DROP INDEX IF EXISTS ...` 行,去掉注释
4. 同 PR 更新 §6 状态追踪表 + 触发 V151 落地
