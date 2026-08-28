# Import Mainline Scenario Closure（2026-08-28）

## 范围

本轮只收口 worker-import 触发链路，不把 export / dispatch / workflow 的下游失败混入导入结论。

验证入口保持真实系统触发：

```text
POST /api/triggers/launch
  -> trigger
  -> orchestrator
  -> Kafka task
  -> worker-import
  -> PREPROCESS / PARSE / VALIDATE / LOAD
  -> biz.* 业务表
```

## 发现的问题

三租户主线导入中，`ta/TA_IMPORT_CUSTOMER` 成功，但 `tb/TC` 失败。定位后确认不是 RLS、不是触发入口，也不是业务表唯一索引问题，而是 JDBC 参数类型漂移：

- `TB_IMPORT_TRANSACTION_TPL` 的 `amount` / `txn_date` 按字符串绑定到 `NUMERIC` / `DATE` 列。
- `TC_IMPORT_RISK_SCORE_TPL` 的 `score_value` / `score_date` 按字符串绑定到 `NUMERIC` / `DATE` 列。
- PostgreSQL 默认 JDBC URL 不会把 `varchar` 参数隐式转成 `numeric/date`，因此报 `bad SQL grammar`。

另一个同源隐患是 `PARTITION_REPLACE_COPY` 的清理语句：

```sql
DELETE FROM ... WHERE biz_date = ?
```

当 `biz_date` 来自 `${bizDate}` system binding 时，旧实现同样按字符串绑定，默认 JDBC URL 下会出现 `date = character varying`。

## 修复

- `JdbcMappedImportSpec.ColumnMapping` 保留 `field_mappings.type` 和 `field_mappings.format`。
- 当 `query_param_schema.jdbcMappedImport.columnMappings` 显式覆盖字段映射时，继续继承同名 `field_mappings` 的类型和格式，避免配置方重复填写。
- `GenericJdbcMappedImportLoadPlugin` 在 `BATCH_UPSERT` 参数绑定前按模板类型转换：
  - `DECIMAL/NUMBER/NUMERIC/INTEGER/INT/LONG/BIGINT` -> `BigDecimal`
  - `DATE/LOCAL_DATE` -> `LocalDate`
  - `TIMESTAMP/DATETIME/LOCAL_DATE_TIME` -> `LocalDateTime`
  - `OFFSET_DATE_TIME/TIMESTAMPTZ` -> `OffsetDateTime`
  - `BOOLEAN/BOOL` -> `Boolean`
- `${bizDate}` system binding 转为 `LocalDate`，覆盖分区清理和系统日期列写入。
- `scripts/sim/17-import-stage2c.sh` 明确 `PARTITION_REPLACE_COPY` 与 line-based checkpoint 互斥；Stage 2c 作为正向 load-mode 矩阵会临时用 checkpoint=false 启动 import worker，checkpoint=true 下的拒跑由 Stage 2b 覆盖。

## 验证结果

### Focused Test

命令：

```bash
./mvnw -pl batch-worker/import -Dtest=JdbcMappedImportSpecTest,GenericJdbcMappedImportLoadPluginTest,JdbcMappedImportCopyIntegrationTest test
```

结果：

```text
Tests run: 31, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

覆盖点：

- spec 解析时类型/格式能从 `field_mappings` 继承到显式 `columnMappings`。
- 默认 PostgreSQL JDBC URL 下，`BATCH_UPSERT` 可写入并二次更新 `numeric/date` 列。
- `PARTITION_REPLACE_COPY` 的 `biz_date` 清理参数可正确绑定为 `LocalDate`。

### 本地系统 API 复验

服务状态：

```text
trigger      18081 UP
orchestrator 18082 UP
worker-import 18083 UP
```

本轮 requestId 前缀：

```text
prod-like-import-mainline-20260828T102005Z
```

结果：

| 租户 | Job | 终态 | 业务表效果 |
|---|---|---|---|
| ta | `TA_IMPORT_CUSTOMER` | SUCCESS | `biz.customer_account` 落入 5 行 |
| tb | `TB_IMPORT_TRANSACTION` | SUCCESS | `biz.transaction` 落入 5 行 |
| tc | `TC_IMPORT_RISK_SCORE` | SUCCESS | `biz.risk_score` 落入 5 行 |

任务结果：

```text
ta/IMPORT/SUCCESS
tb/IMPORT/SUCCESS
tc/IMPORT/SUCCESS
```

复跑脚本已固化：

```bash
STAGES=1i bash load-tests/scripts/run-worker-business-scenario-matrix.sh
```

也可直接运行：

```bash
bash scripts/sim/28-import-mainline.sh
```

脚本自验证：

```text
RUN_ID=prod-like-import-mainline-script-20260828222650 STAGES=1i
batchNo=sim-import-mainline-20260828222652
ta/TA_IMPORT_CUSTOMER       SUCCESS 5 rows
tb/TB_IMPORT_TRANSACTION    SUCCESS 5 rows
tc/TC_IMPORT_RISK_SCORE     SUCCESS 5 rows
```

## 已覆盖的导入业务场景

- ta/tb/tc 三租户主线导入。
- DELIMITED 主路径。
- XML / FIXED_WIDTH 正向和解析失败。
- 字段校验失败。
- APPEND / UPSERT / PARTITION_REPLACE_COPY 小矩阵。
- UPSERT 重跑幂等。
- LOAD failure。
- PARTITION_REPLACE_COPY 多分片 fail-fast。
- bad-record skip 阈值内继续与超阈值阻断。
- checkpoint=true 与不兼容 load mode 的拒跑保护。

## 未纳入本轮结论

- `scripts/sim/05-load.sh` 的 Stage 1 同时触发 export / dispatch / workflow。当前 TB/TC dispatch 的 mock callback 补偿失败会导致 Stage 1 整体失败，但这不是 import 失败，不纳入本轮导入结论。
- 真实云 S3/OSS、PG/Kafka 断链、worker kill after chunk 等故障注入属于已有 P1/P2 容量和恢复专项，不在本轮三租户主线导入收口内。
