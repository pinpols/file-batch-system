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
