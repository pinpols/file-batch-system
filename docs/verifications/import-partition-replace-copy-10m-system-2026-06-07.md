# Import PARTITION_REPLACE_COPY 1000w 系统链路验证（2026-06-07）

## 结论

本次验证走真实系统链路：

`POST /api/triggers/launch` → trigger → orchestrator → Kafka task dispatch → worker-import → MinIO `storagePath` → PREPROCESS spool → PARSE → VALIDATE → LOAD `PARTITION_REPLACE_COPY` → PG 分区表。

最终成功导入近 1GiB CSV：

- 源文件：`batch-dev/ingress/ta/import-10m-near-g.csv`
- 对象大小：`1,008,890,025 bytes`
- 数据行数：`10,000,000`
- 目标表：`batch_business.biz.wide_10m_copy`
- 物理分区：`biz.wide_10m_copy_20260607`
- 系统实例：`job_instance.id=4006`
- traceId：`ed8dda76e86944c683e67898bd7521cc`
- pipeline 状态：`SUCCESS`
- 业务表校验：`count=10,000,000`，`count(distinct row_key)=10,000,000`

## 关键配置

本地验证补了一个临时 import 模板 `WIDE_10M_COPY_TPL`：

- `load_target_ref = jdbc_mapped`
- `chunk_size = 10000`
- `query_param_schema.jdbcMappedImport.loadStrategy = PARTITION_REPLACE_COPY`
- `replacePartitionColumns = ["tenant_id", "biz_date"]`
- `systemBindings.biz_date = ${bizDate}`

目标表使用 PG 原生分区表：

```sql
CREATE TABLE biz.wide_10m_copy (
  tenant_id varchar(64) NOT NULL,
  row_key varchar(32) NOT NULL,
  c01 text,
  n01 numeric(12,2),
  biz_date date NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id,biz_date,row_key)
) PARTITION BY LIST (biz_date);

CREATE TABLE biz.wide_10m_copy_20260607
  PARTITION OF biz.wide_10m_copy FOR VALUES IN ('2026-06-07');
```

## 最终成功结果

阶段耗时：

| Stage | Status | Duration |
|---|---:|---:|
| RECEIVE | SUCCESS | 47 ms |
| PREPROCESS | SUCCESS | 6,535 ms |
| PARSE | SUCCESS | 67,774 ms |
| VALIDATE | SUCCESS | 39,437 ms |
| LOAD | SUCCESS | 123,595 ms |
| FEEDBACK | SUCCESS | 24 ms |

端到端时间：

- job：`15:18:07.486` → `15:22:07.928`，约 `240.4s`
- pipeline：`15:18:10.364` → `15:22:07.852`，约 `237.5s`

流式证据：

```text
import preprocess streamed object to spool (no heap buffering):
bucket=batch-dev, object=ingress/ta/import-10m-near-g.csv,
bytes=1008890025,
spool=/var/folders/.../batch-preprocess-obj-13797254203766878630.raw
```

LOAD 证据：

```text
jdbc-mapped-import partition replace prepared:
tenantId=default-tenant, template=WIDE_10M_COPY_TPL,
schema=biz, table=wide_10m_copy,
replacePartitionColumns=[tenant_id, biz_date], deletedRows=0

IMPORT task processed: taskId=4209, success=true,
message=imported 10000000 row(s)
```

落库校验：

| Metric | Value |
|---|---:|
| rows | 10,000,000 |
| distinct row_key | 10,000,000 |
| min row_key | RK00000001 |
| max row_key | RK10000000 |
| min n01 | 0.00 |
| max n01 | 9999.99 |
| c01 length min/max | 70 / 70 |

分区校验：

| partition | rows |
|---|---:|
| `biz.wide_10m_copy_20260607` | 10,000,000 |

抽样：

| row_key | n01 | biz_date |
|---|---:|---|
| RK00000001 | 1.01 | 2026-06-07 |
| RK05000000 | 0.00 | 2026-06-07 |
| RK10000000 | 0.00 | 2026-06-07 |

## 过程中暴露的问题

### 1. 首轮被默认 DYNAMIC 分片

首次触发复用 `import_customer_job`，该 job 的 `shard_strategy=DYNAMIC`，orchestrator 自动生成：

- `expected_partition_count=2`
- worker 日志：`partition=1/2, kept=5000000/10000000`

这不适合单大文件 COPY 压测。单文件分片会让每个分片重复读取和解析同一个大文件，只保留自己那部分行，造成重复 IO/CPU。本轮最终结果已改为 `shard_strategy=NONE` 后重跑，成功实例 `4006` 的 `expected_partition_count=1`。

补测 2 分片后确认该组合不是“性能差”这么简单，而是语义不兼容：

- traceId：`be1d46fe36694297b5d0280e6c6d2687`
- job_instance：`4007`
- `expected_partition_count=2`
- 分片 2/2 成功：`partition=2/2, kept=5000000/10000000`，`imported 5000000 row(s)`
- 分片 1/2 多次在 PREPROCESS 后更新共享 `file_record` 时失败：`error.common.state_conflict_detail`
- 最终业务表只剩 `5,000,000` 行，实例收敛为 `FAILED`

这说明 `PARTITION_REPLACE_COPY` 不能和 worker 单文件分片一起使用：每个 worker 分片都会面向同一 `(tenant_id,biz_date)` 目标逻辑分区执行“清分区 + COPY”，轻则共享 `file_record` 状态冲突，重则后到分片清掉先到分片已写数据。代码侧已补 fail-fast：`PARTITION_REPLACE_COPY + partitionCount > 1` 直接返回 `IMPORT_LOAD_CONFIG_INVALID`，避免进入 `preparePartitionReplace()`。

### 2. `${bizDate}` 没有透传到 worker execution context

首轮 LOAD 报错：

```text
DELETE FROM "biz"."wide_10m_copy" WHERE "tenant_id"=? AND "biz_date"=?
ERROR: invalid input syntax for type date: ""
```

根因是 `TaskDispatchMessage.schedulingContext.bizDate` 没有下沉到 `PulledTask`，`DefaultTaskExecutionWrapper` 也没有把 `bizDate` 放进 execution context，导致 `ImportStepExecutionAdapter` 构造 `ImportJobContext.bizDate` 时读到空字符串。

已补修复：

- `PulledTask` 增加 `bizDate`
- `TaskDispatchExecutor` 从 `message.schedulingContext().bizDate()` 写入 `PulledTask`
- `DefaultTaskExecutionWrapper` 写入 `executionContext["bizDate"]`
- `TaskDispatchExecutorTest` 增加断言

### 3. 内联 CSV 触发参数必须用 `DELIMITED`

用 `import_customer_v1` 跑内联 CSV 小文件时，首次传 `fileFormatType=CSV`，RECEIVE 阶段插入 `file_record` 被 DB check 拒绝：

```text
ERROR: new row for relation "file_record" violates check constraint "ck_file_record_format"
```

原因是 `file_record.file_format_type` 枚举值是 `DELIMITED/FIXED_WIDTH/EXCEL/XML/JSON/BINARY`，不是 `CSV`。改成 `fileFormatType=DELIMITED` 后系统链路成功：

- traceId：`dc80aa8d4b4848cd8a52943f3e4b02be`
- template：`import_customer_v1`
- task：`4214`
- result：`SUCCESS`
- message：`imported 2 row(s)`

JSON 内联导入也成功：

- traceId：`8c50ce1fb03046299e5d0f306cc243ac`
- template：`import_customer_json_v1`
- task：`4213`
- result：`SUCCESS`
- message：`imported 2 row(s)`

落库校验：

| customer_no | customer_name | customer_type |
|---|---|---|
| CODEX-CSV-001 | Codex CSV One | PERSONAL |
| CODEX-CSV-002 | Codex CSV Two | ENTERPRISE |
| CODEX-JSON-001 | Codex JSON One | PERSONAL |
| CODEX-JSON-002 | Codex JSON Two | ENTERPRISE |

## 覆盖范围

本轮真实系统验证覆盖：

- `storagePath` 大对象导入，不走内联 `content`
- MinIO 对象流式 PREPROCESS spool
- CSV parse / validate
- `jdbc_mapped` load target
- `PARTITION_REPLACE_COPY`
- PG 原生分区表目标
- `systemBindings ${bizDate}`
- 单流 1000w 行 COPY 落库
- `PARTITION_REPLACE_COPY` 单文件 2 分片负向验证
- 默认 `BATCH_UPSERT` 内联 CSV 小文件导入
- 默认 `BATCH_UPSERT` 内联 JSON 小文件导入

此前 PR 内代码级验证已覆盖：

- `JdbcMappedImportSpec` 对 `loadStrategy`、`replacePartitionColumns`、`systemBindings` 的解析和非法配置拒绝
- `GenericJdbcMappedImportLoadPlugin` 的 `PARTITION_REPLACE_COPY` prepare + COPY 路径
- Testcontainers PostgreSQL 真实落库：替换目标逻辑分区、保留其他分区、NULL/quote/newline CSV 转义
- `LoadStep` 对 `PARTITION_REPLACE_COPY` 禁止 checkpoint 的 precheck

本轮未重新跑的导入分支：

- XML / FIXED_WIDTH 格式导入
- Excel multipart 上传链路

原因：当前本地 seed 里只有 `import_customer_v1`、`import_customer_json_v1`、`WIDE_10M_COPY_TPL` 这 3 个 import 模板；没有 XML/FIXED_WIDTH 可触发模板。Excel multipart 是 console 上传配置包链路，不是本轮 worker-import trigger 模板链路。
