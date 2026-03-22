# 默认运行参数基线（§20.11）

本文档与 Flyway **`V23__batch_runtime_default_parameter.sql`** 中的 `batch.batch_runtime_default_parameter` 表保持一致，用于说明各模块 **YAML 默认值**、**环境变量覆盖** 与 **数据库侧模板默认值** 的来源。

**约定**：应用进程仍以 Spring `application.yml` / `import-worker.yml` / `export-worker.yml` 等为实际生效配置；本表为**可审计的基线目录**，变更默认时应同时改 YAML、文档与本表种子数据（新迁移或运维脚本）。

## 快速索引

| 域 | 主配置位置 |
|----|------------|
| Orchestrator 调度 / Outbox / 重试 / 租约 / 排空 / SLA / 文件治理 | `batch-orchestrator/src/main/resources/application.yml` |
| Import Worker 流式与分页 | `batch-worker-import/src/main/resources/application.yml`、`import-worker.yml` |
| Export Worker 流式与分页 | `batch-worker-export/src/main/resources/application.yml`、`export-worker.yml` |
| Dispatch 熔断与回执轮询 | `batch-worker-dispatch/src/main/resources/application.yml` |
| 模板级 chunk | `batch.file_template_config.chunk_size`（Flyway V13，默认 500） |

## Import / Export 文件处理（chunk / page / fetch）

| 参数 | 默认 | 环境变量（节选） |
|------|------|------------------|
| `streaming-enabled` | `true` | `BATCH_WORKER_IMPORT_STREAMING_ENABLED` / `BATCH_WORKER_EXPORT_STREAMING_ENABLED` |
| `page-size` | `1000` | `BATCH_WORKER_IMPORT_PAGE_SIZE` / `BATCH_WORKER_EXPORT_PAGE_SIZE` |
| `fetch-size` | `1000` | `BATCH_WORKER_IMPORT_FETCH_SIZE` / `BATCH_WORKER_EXPORT_FETCH_SIZE` |
| `chunk-size` | `500` | `BATCH_WORKER_IMPORT_CHUNK_SIZE` / `BATCH_WORKER_EXPORT_CHUNK_SIZE` |

模板若配置 `chunk_size`，导入 `LoadStep` / 导出 `GenerateStep` 会优先使用模板值。

## Orchestrator 摘录

| 域 | 默认（节选） | 环境变量前缀 |
|----|----------------|--------------|
| Outbox `batch-size` / `poll-interval-millis` | `100` / `5000` | `BATCH_OUTBOX_*` |
| Worker drain `default-timeout-seconds` / `check-interval-millis` | `600` / `15000` | `BATCH_WORKER_DRAIN_*` |
| Retry `poll-interval-millis` / `default-max-retry-count` | `10000` / `3` | `BATCH_RETRY_*` |
| Partition lease `expire-seconds` / `reclaim-interval-millis` | `60` / `15000` | `BATCH_PARTITION_LEASE_*` |

完整列表见数据库表或 `V23` 脚本内 `INSERT`。

## Dispatch 熔断与回执

| 参数 | 默认 | 环境变量 |
|------|------|----------|
| `circuit-breaker.failure-threshold` | `5` | — |
| `circuit-breaker.cooldown-millis` | `60000` | `BATCH_DISPATCH_CB_COOLDOWN_MS` |
| `receipt-poll.interval-millis` | `60000` | `BATCH_DISPATCH_RECEIPT_POLL_MS` |
| `receipt-poll.batch-size` | `50` | `BATCH_DISPATCH_RECEIPT_POLL_BATCH` |

## 上传文件大小上限

当前仓库**未**在 Spring 层统一配置全局 `multipart` 最大文件大小；生产环境建议在 **API 网关** 或 **batch-console-api** 显式配置，并将最终口径补入本表（可选 `MODULE=CONSOLE` 行）。

## Flyway 版本说明（第 22 轮）

原 `V11` / `V12` / `V14` 重复脚本已重编号为 **`V20` / `V21` / `V22`**，避免 Flyway 校验冲突。若已有环境在旧命名下执行过其中一条脚本，升级到本仓库脚本集前需对照 `flyway_schema_history` 做一次性治理（或自空库重装）。

## 数据库查询示例

```sql
SELECT module, parameter_key, default_value, value_type, yaml_path, env_var
FROM batch.batch_runtime_default_parameter
ORDER BY module, parameter_key;
```
