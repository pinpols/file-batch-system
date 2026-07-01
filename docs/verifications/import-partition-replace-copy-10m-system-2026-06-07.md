# Import PARTITION_REPLACE_COPY 1000w 系统链路验证（2026-06-07）

吞吐优化主文档：[`single-node-throughput-optimization-2026-06-06.md`](../backlog/single-node-throughput-optimization-2026-06-06.md)。

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
spool=<os-temp-dir>/batch-preprocess-obj-13797254203766878630.raw
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

写入数据库校验：

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

写入数据库校验：

| customer_no | customer_name | customer_type |
|---|---|---|
| CODEX-CSV-001 | Codex CSV One | PERSONAL |
| CODEX-CSV-002 | Codex CSV Two | ENTERPRISE |
| CODEX-JSON-001 | Codex JSON One | PERSONAL |
| CODEX-JSON-002 | Codex JSON Two | ENTERPRISE |

## 2026-06-07 复验矩阵

复验继续走真实系统链路：

`POST /api/triggers/launch` -> trigger -> orchestrator -> Kafka -> worker-import -> PG。

复验前动作：

- 重新构建并替换 `build/runtime-jars/worker-import.jar`
- 前台启动 `worker-import`，确认注册到 orchestrator 且 Kafka consumer assigned
- 清理 `config:default-tenant:job-definition:import_customer_job` Redis 配置缓存，避免 5 分钟 TTL 读到旧 shard 策略

有效复验结果：

| 场景 | requestId | traceId | job_instance | expected_partition_count | 结果 |
|---|---|---|---:|---:|---|
| CSV `DELIMITED` inline `BATCH_UPSERT` | `codex-reval-csv-delimited-upsert-1780818625` | `b73c7d72ba864268b9ff83c96aca5dff` | 4011 | 1 | `SUCCESS`，`imported 2 row(s)` |
| JSON inline `BATCH_UPSERT` | `codex-reval-json-upsert-1780818625` | `0cede710876b4589b41208a6565b2206` | 4012 | 1 | `SUCCESS`，`imported 2 row(s)` |
| `PARTITION_REPLACE_COPY` + MinIO `storagePath` + 单分区 | `codex-reval-copy-storagepath-single-1780818625` | `17cb6621933942059994c381159866f8` | 4013 | 1 | `SUCCESS`，`imported 12 row(s)` |
| `PARTITION_REPLACE_COPY` + MinIO `storagePath` + 2 分片 | `codex-reval-copy-shard2-guard-1780818998` | `2e9eff6b5ae3487da02d2638cc4c6054` | 4016 | 2 | `FAILED`，两个分片均 `IMPORT_LOAD_CONFIG_INVALID` |

负向 guard 证据：

```text
job_instance 4016:
instance_status=FAILED
expected_partition_count=2
success_partition_count=0
failed_partition_count=2
current_stage=LOAD
last_success_stage=VALIDATE
run_status=FAILED
lastErrorCode=IMPORT_LOAD_CONFIG_INVALID
```

两个 `job_task` 均失败在同一配置保护：

```text
PARTITION_REPLACE_COPY cannot run with partitionCount=2:
each worker partition would clear the same target partition before COPY,
which can leave partial data. Use shard_strategy=NONE for this template,
or split input into independent files with distinct logical partitions.
```

业务表保护结果：

| 表 / 条件 | rows | distinct_rows |
|---|---:|---:|
| `biz.customer_account` where `customer_no like 'CODEX-REVAL-%'` | 4 | 4 |
| `biz.wide_10m_copy` default-tenant / `2026-06-07` | 12 | 12 |

这说明修复后 `PARTITION_REPLACE_COPY + partitionCount > 1` 不会进入清分区写入；单分区 COPY 已写入的 12 行在负向分片验证后保持不变。

无效尝试（不计入业务结论）：

| traceId | 原因 | 处理 |
|---|---|---|
| `440d00b15fd04189afbd00300e71fceb` | orchestrator 配置缓存仍是 `shard_strategy=NONE`，且复用已处理对象路径，实际 `expected_partition_count=1` 并在 PREPROCESS 去重冲突 | 标记为 `CODEX_INVALID_NEGATIVE_SETUP` |
| `5a5a0514480147c39b8637539bec9922` | 已命中 `expected_partition_count=2`，但请求参数 `sourceType=OBJECT_STORAGE` 不满足 `file_record.source_type` 约束，失败在 RECEIVE，未进入 LOAD guard | 标记为 `CODEX_INVALID_NEGATIVE_SETUP` |

复验结束后环境恢复：

- `batch.job_definition(id=2001).shard_strategy` 已恢复为 `NONE`
- Redis `config:default-tenant:job-definition:import_customer_job` 已删除，后续会按 DB 当前值回填

## XML / FIXED_WIDTH / Excel multipart 补充覆盖

### XML import

为 `default-tenant` 临时补了 `CODEX_CUSTOMER_XML_TPL`，目标表复用 `biz.customer_account`，通过真实 trigger 链路验证：

| requestId | traceId | job_instance | expected_partition_count | result |
|---|---|---:|---:|---|
| `codex-reval-xml-1780819472` | `5516865b0d0645448770f4fb300a3c35` | 4018 | 1 | `SUCCESS`，`imported 2 row(s)` |

写入数据库校验：

| customer_no | customer_name | customer_type |
|---|---|---|
| `CODEX-XML-001` | Codex XML One | PERSONAL |
| `CODEX-XML-002` | Codex XML Two | ENTERPRISE |

### FIXED_WIDTH import

为 `default-tenant` 临时补了 `CODEX_CUSTOMER_FIXED_WIDTH_TPL`，字段布局：

- `customerNo`: start `0`, length `16`
- `customerName`: start `16`, length `20`
- `customerType`: start `36`, length `12`

系统链路首次触发：

| requestId | traceId | job_instance | result |
|---|---|---:|---|
| `codex-reval-fixed-width-1780819472` | `75054da191244fdab694877bbb96d78c` | 4017 | `FAILED`，`IMPORT_PARSE_FAILED` |

失败原因不是模板缺字段，而是运行时缺陷：`batch.file_template_config.field_mappings` 是 PostgreSQL `jsonb`，worker 运行时拿到的是 PGobject/jsonb 形态；`ParseSupport.templateFieldMappings()` 只处理了 `String/List`，没有像 `query_param_schema` 一样走 `PostgresqlJsonbTexts.tryExtract()`，导致 FIXED_WIDTH parser 读不到 `start/length/target`。

已修复代码：

- `ParseSupport.templateFieldMappings()` 对 `field_mappings` 统一走 `jsonText()`，支持 `String`、PGobject/jsonb 等形态
- `ParseStepFixedWidthAndXmlTest` 增加 PGobject/jsonb 形态回归用例

验证命令：

```bash
mvn -pl batch-worker-import -am -Dtest=ParseStepFixedWidthAndXmlTest -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：`BUILD SUCCESS`，`Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`。

按“不再重启服务”的约束，本次没有把当前运行中的 worker 热换成新 jar 复跑系统级 FIXED_WIDTH；该项需要随下一次正常部署 / worker reload 后用同一 API 请求复验。

### Excel multipart upload

走 console 真实 multipart 接口：

```text
POST /api/console/config/tenant-package/excel/upload?tenantId=default-tenant
Content-Type: multipart/form-data
file=@docs/test-data/test-full-coverage-import-suite/default-tenant-config-package-test.xlsx
```

认证：`POST /api/console/auth/login` 使用本地 seed 管理员 `admin/admin123`，通过 `batch_console_token` cookie 调用上传。

结果：

| item | value |
|---|---|
| HTTP | 200 |
| code | `SUCCESS` |
| uploadToken | `15a58db8b7bf44acb46158eb62c613e1` |
| jobRows | 7 |
| fileChannelRows | 4 |
| workflowDefinitionRows | 3 |
| workflowNodeRows | 13 |
| workflowEdgeRows | 11 |

这条路径已覆盖 `@RequestParam("file") MultipartFile` 的 console Excel multipart 上传和 11 sheet workbook 解析入口。该 fixture 本身 `fileTemplateRows=0`，所以本次 multipart 验证覆盖的是上传/解析入口，不覆盖新增 FileTemplate 行 apply。

## 覆盖范围

本轮真实系统验证覆盖：

- `storagePath` 大对象导入，不走内联 `content`
- MinIO 对象流式 PREPROCESS spool
- CSV parse / validate
- `jdbc_mapped` load target
- `PARTITION_REPLACE_COPY`
- PG 原生分区表目标
- `systemBindings ${bizDate}`
- 单流 1000w 行 COPY 写入数据库
- `PARTITION_REPLACE_COPY` 单文件 2 分片负向验证
- 默认 `BATCH_UPSERT` 内联 CSV 小文件导入
- 默认 `BATCH_UPSERT` 内联 JSON 小文件导入
- 默认 `BATCH_UPSERT` 内联 XML 小文件导入
- Console Excel multipart 上传入口

此前 PR 内代码级验证已覆盖：

- `JdbcMappedImportSpec` 对 `loadStrategy`、`replacePartitionColumns`、`systemBindings` 的解析和非法配置拒绝
- `GenericJdbcMappedImportLoadPlugin` 的 `PARTITION_REPLACE_COPY` prepare + COPY 路径
- Testcontainers PostgreSQL 真实写入数据库：替换目标逻辑分区、保留其他分区、NULL/quote/newline CSV 转义
- `LoadStep` 对 `PARTITION_REPLACE_COPY` 禁止 checkpoint 的 precheck
- `FIXED_WIDTH field_mappings` 的 PostgreSQL jsonb / PGobject 解析回归

仍需正常部署后复验：

- FIXED_WIDTH 系统链路成功态：代码已修、单测已过；当前运行 worker 未热换新 jar，按本轮约束不再重启。
- Excel 配置包 apply：本次只验证 upload multipart 和 workbook 解析入口；是否 apply 取决于是否要写入当前本地租户配置。
