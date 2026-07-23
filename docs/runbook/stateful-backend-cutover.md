# Stateful Backend Cutover

适用于以下有状态后端切换：

- Quota runtime state：Redis / database
- Worker report outbox：disabled / PLATFORM_PG / SQLITE
- Object storage：S3 / filesystem，以及 endpoint、bucket、region、root 变化

## 1. Guard 语义

Flyway V193 创建：

- `batch.stateful_backend_binding`：每项能力当前启用的 backend、定位信息和 generation
- `batch.stateful_backend_cutover_history`：首次基线和历次切换审计

首次部署 V193 后，服务按当前配置自动登记 `BASELINE`，不要求 token。后续 backend
或关键定位发生变化时，服务启动前执行以下规则：

1. 当前配置与登记完全一致：正常启动。
2. 配置变化且未提供 `cutover-id`：拒绝启动。
3. 配置变化且 token 已用于该能力：拒绝启动。
4. 配置变化且 token 是新值：登记新 generation 和 `CUTOVER` 历史，然后启动。

同一批多实例使用相同 token：第一个实例登记切换，其余实例看到目标状态已一致后正常启动。
回滚也属于新切换，必须使用另一个新 token。

> `cutover-id` 只是“运维已完成停写、迁移或排空并接受切换结果”的显式标记。
> Guard 不复制 Quota 状态、不搬运 Outbox 记录，也不迁移对象。只设置 token 而没有执行本
> SOP，仍可能丢失状态。

## 2. 通用切换步骤

1. 确认 V193 已应用，并检查当前登记：

```sql
SELECT feature_key, backend, backend_identity, generation, last_cutover_id, updated_at
FROM batch.stateful_backend_binding
ORDER BY feature_key;
```

2. 停止写入方并等待在途请求完成。
3. 按下文执行迁移或排空，记录校验结果和变更单号。
4. 同时修改目标 backend 配置和新的 `cutover-id`。
5. 先启动一个实例，确认日志出现 `CUTOVER_RECORDED`。
6. 检查审计后再启动其余实例：

```sql
SELECT feature_key, generation, action, previous_backend, target_backend,
       cutover_id, changed_by, changed_at
FROM batch.stateful_backend_cutover_history
ORDER BY id DESC;
```

7. 全部实例稳定后可移除 token。已登记目标状态不会因 token 留空而失败。

## 3. Quota

变量：

- `BATCH_QUOTA_RUNTIME_STORE=redis|database`
- `BATCH_QUOTA_BACKEND_CUTOVER_ID=<change-id>`

Redis → database：

1. 保持 Redis 模式，确认 `batch.quota.snapshot.enabled=true`。
2. 等待至少一个 snapshot 周期。
3. 检查 `batch.quota_runtime_state.updated_at` 已覆盖当前活跃窗口。
4. 停止 orchestrator，再切 database 并设置新 token。

database → Redis 当前没有自动反向导入器。保守做法是停写后等待活跃 quota window
结束，再切 Redis；若业务不能等待，必须先提供专项迁移工具并核对 Redis Hash 后才能设置
token。禁止把 token 当作迁移工具。

Redis host/port/database、Sentinel master/nodes 或平台 JDBC URL 变化也视为有状态切换。

## 4. Worker Report Outbox

变量：

- `BATCH_WORKER_REPORT_OUTBOX_ENABLED=true|false`
- `BATCH_WORKER_REPORT_OUTBOX_STORAGE=PLATFORM_PG|SQLITE`
- `BATCH_WORKER_REPORT_OUTBOX_SQLITE_PATH=<absolute-path>`
- `BATCH_WORKER_REPORT_OUTBOX_CUTOVER_ID=<change-id>`

每个 `spring.application.name` 独立登记。`disabled` 是明确 backend 状态，因此启用、禁用、
PG/SQLite 互切都必须使用新 token。

切换或关闭前先停止对应 Worker 写入，确保源 Outbox 没有待发送记录。

PLATFORM_PG：

```sql
SELECT publish_status, count(*)
FROM batch.worker_report_outbox
GROUP BY publish_status;
```

SQLITE：

```bash
sqlite3 /absolute/path/worker-report-outbox.db \
  "SELECT publish_status, count(*) FROM worker_report_outbox GROUP BY publish_status;"
```

`NEW`、`PUBLISHING`、`GIVE_UP` 均须处理到 0。当前没有 PG/SQLite 自动搬运器；如无法排空，
应取消切换。完成排空后，再同时修改 enabled/storage/path 和新 token。

## 5. Object Storage

变量：

- `BATCH_STORAGE_BACKEND=s3|filesystem`
- `BATCH_STORAGE_BACKEND_CUTOVER_ID=<change-id>`
- S3 定位：`BATCH_S3_ENDPOINT`、`BATCH_S3_REGION`、`BATCH_S3_BUCKET`
- Filesystem 定位：`BATCH_STORAGE_FILESYSTEM_ROOT`

切换前停止 orchestrator、console 和全部 Worker 的对象写入。将历史对象复制到目标，
至少按 `batch.file_record.storage_path` 核对对象数、总字节数和 checksum 抽样；确认目标端
可读后，再为所有服务同时设置目标配置和同一个新 token。

所有服务共享 `object-storage` 登记，配置不一致的实例会被拒绝启动。S3 endpoint、region、
bucket 或 filesystem root 变化即使 backend 名未变，也属于切换。

Filesystem 的部署和 NAS 约束见
[object-storage-filesystem.md](./object-storage-filesystem.md)。

## 6. Compose 透传清单

Compose 必须把以下 guard 相关变量传入对应容器：

| 变量 | 容器 |
|---|---|
| `BATCH_QUOTA_RUNTIME_STORE` | orchestrator |
| `BATCH_QUOTA_BACKEND_CUTOVER_ID` | orchestrator |
| `BATCH_WORKER_REPORT_OUTBOX_ENABLED` | 全部 Worker |
| `BATCH_WORKER_REPORT_OUTBOX_STORAGE` | 全部 Worker |
| `BATCH_WORKER_REPORT_OUTBOX_SQLITE_PATH` | 使用 SQLite 的 Worker |
| `BATCH_WORKER_REPORT_OUTBOX_CUTOVER_ID` | 全部 Worker |
| `BATCH_STORAGE_BACKEND` | 使用对象存储的全部服务 |
| `BATCH_STORAGE_BACKEND_CUTOVER_ID` | 使用对象存储的全部服务 |
| `BATCH_STORAGE_FILESYSTEM_ROOT` | filesystem 模式的全部服务 |
| `BATCH_S3_ENDPOINT` / `BATCH_S3_REGION` / `BATCH_S3_BUCKET` | S3 模式的全部服务 |

本 runbook 不负责修改 Compose；透传应由部署配置治理任务统一完成。
