# PG 会话治理与超时调优

本仓所有 Hikari 池在连接初始化时执行 `SET statement_timeout` + `SET idle_in_transaction_session_timeout` + JDBC `ApplicationName`。配置入口 `batch.datasource.pg-session.*`（见 `batch-defaults.yml`）。

## 默认值

| 池类型 | `statement_timeout` | `idle_in_transaction_timeout` | 适用场景 |
|---|---|---|---|
| **platform**（调度态/状态机/短事务） | **15m** | **60s** | orchestrator / trigger / console 主库, worker 平台池 |
| **business**（导入/导出/PROCESS 大 SQL） | **30m** | **10m** | worker 业务池 (`batch.datasource.business`) |

## 何时调整

### 1. Flyway 大型迁移撞 `statement_timeout`

V77 那种"大表加列 + 全量回填"或新建大索引可能 > 15m，被 server 端 kill 后 migration 失败留半成品。**迁移前临时抬高或关闭**：

```bash
# 临时抬到 2 小时
export BATCH_PG_PLATFORM_STATEMENT_TIMEOUT=2h
# 或彻底关闭整套 SET (含 ApplicationName)
export BATCH_PG_SESSION_ENABLED=false
```

迁移完成后**记得恢复默认**，否则失去对线上跑死 SQL 的兜底防护。后续如果迁移频繁可考虑给 Flyway 单独 DataSource (业界常见做法，跟运行态池解耦)。

### 2. PROCESS WAP COMMIT 撞 `idle_in_transaction`

PROCESS 5-stage（prepare → compute → validate → commit → feedback）的 COMMIT 步骤如果在 stage 之间停留 > 10m（典型：跨大表 INSERT-SELECT-DELETE），server 端会 kill 持锁连接。监控 `pg_stat_activity` 里 `state='idle in transaction'` 的连续时长，若频繁 > 5m 就需要：

```bash
export BATCH_PG_BUSINESS_IDLE_IN_TX_TIMEOUT=20m
```

不要无脑设 0（关闭）—— 失去对"BEGIN; ... 出去吃饭"场景的防护。

### 3. EXPORT 大查询撞 `statement_timeout=30m`

百万级以上数据导出可能 > 30m。优先做的事是：
- 加分区索引 / WHERE 时间窗 (V94 `data_interval_start/end`)
- 分批导出 (export `chunkSize` 调小)
- 用 `BATCH_PG_BUSINESS_STATEMENT_TIMEOUT=2h` 临时撑住后再优化

## 验证 `ApplicationName` 是否生效

```sql
SELECT application_name, state, count(*)
FROM pg_stat_activity
WHERE datname = current_database()
GROUP BY application_name, state
ORDER BY count(*) DESC;
```

预期看到：
- `batch-orchestrator-platform` / `batch-orchestrator-business`
- `batch-worker-import-platform` / `batch-worker-import-business`
- `batch-console-api-primary` / `batch-console-api-replica`

DBA 据此可一眼定位"哪个应用持哪条慢连接"。

## 关闭机制

`batch.datasource.pg-session.enabled=false`（或 env `BATCH_PG_SESSION_ENABLED=false`）整套退订：
- 不写 `ApplicationName`
- 不下发 `SET statement_timeout`
- 不下发 `SET idle_in_transaction_session_timeout`

仅供本地调试 / 应急 (例如发现 SET 语句本身有 bug 时)。生产**不应长期关闭**。
