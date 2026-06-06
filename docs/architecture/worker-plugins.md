# Worker 平台 + 插件（导入 / 导出 / 分发）

## 约定

- **平台**：固定 Pipeline 阶段、模板、`file_record`、审计、流式文件形态（NDJSON 等）。
- **插件**：在稳定接口下对接**不同上下游表或渠道**，通过 **Spring Bean** 注册，用 **id** 或 **channel_type** 路由。

## 导入 LOAD — `ImportLoadPlugin`

- **契约**：`batch-common` → `com.example.batch.common.plugin.ImportLoadPlugin`
- **默认实现**：`jdbc_mapped` → `GenericJdbcMappedImportLoadPlugin`（通用 JDBC UPSERT）
- **通用 JDBC（少写类）**：`jdbc_mapped` → `GenericJdbcMappedImportLoadPlugin`
  - 模板 **`query_param_schema.jdbcMappedImport`**（或 **`jdbc_mapped_import`**）里声明：`schema`、`table`、`tenantColumn`、`columnMappings`（`from`/`to`）、可选 `conflictColumns`（UPSERT）、`systemBindings`（`${traceId}` 等占位符）。  
  - **schema 白名单**：`batch.worker.import.jdbc-mapped.allowed-schemas`（默认仅 `biz`）。  
  - 标识符仅允许 `[a-z][a-z0-9_]*`，禁止拼接任意 SQL。
- **路由**（优先级从高到低）：
  1. `batch.file_template_config` 行内字段 **`load_target_ref`**
  2. 默认 `jdbc_mapped`（`WorkerPluginIds.IMPORT_LOAD_JDBC_MAPPED`）

新增业务：**优先**用 `jdbc_mapped` 加配置行；仅当规则复杂时再实现独立 `ImportLoadPlugin`。

## 导出 GENERATE — `ExportDataPlugin`

- **契约**：`com.example.batch.common.plugin.ExportDataPlugin`
- **通用 JDBC**：`jdbc_mapped_export` → `GenericJdbcMappedExportDataPlugin`  
  - 模板 **`query_param_schema.jdbcMappedExport`**（或 **`jdbc_mapped_export`**）：`batchTable`、`batchTenantColumn`、`batchNoColumn`、`batchSelectColumns`（须含 **`id`**）、`detailTable`、`detailFkColumn`、`detailOrderByColumn`、`detailSelectColumns`。  
  - 明细分页通过 **`detailOrderByColumn` keyset/cursor** 推进，不再依赖 `LIMIT/OFFSET`。  
  - DELIMITED 时默认表头为 `detailSelectColumns` 顺序；也可由模板 `csv_columns` / `csvColumns` 或插件 `describeDelimitedColumns()` 提供布局。模板还能配置 `delimiter`、`quote_policy`、`escape_policy`、`quote_char`、`header_rows`；JSON 仍为 snapshot+batch+details。  
  - **schema 白名单**：`batch.worker.export.jdbc-mapped.allowed-schemas`（默认 `biz`）。
- **SQL 模板（更灵活，需治理）**：`sql_template_export` → `SqlTemplateExportDataPlugin`
  - SQL 来源：模板 `default_query_sql`（命名参数：`:tenantId`、`:batchNo` 等）
  - 游标分页：`query_param_schema.sqlTemplateExport.cursorColumn`（默认 `id`）
  - SQL 治理（JSqlParser AST）：仅允许 `SELECT/WITH`；禁止 `SELECT *`（`forbidSelectStar=true`）；schema 白名单（`allowedSchemas`）；必须包含 `:tenantId` / `:batchNo` 等命名参数
  - EXPLAIN 前置检查（`explainCheckEnabled=true`）：首页前执行 `EXPLAIN (FORMAT JSON)`，超过 `maxEstimatedRows` / `maxPlanCost` 则拒绝执行
  - 每页大小受 `batch.worker.export.sql-template.max-page-size` 限制
- **路由**：模板 **`export_data_ref`** 字段（必须显式配置，无默认值）

新增业务：**优先**用 `jdbc_mapped_export`；复杂 join/存储过程再写专用 `ExportDataPlugin`。

**导入中间形态**：默认业务示例仍可落到 **`CustomerImportPayload`**；当模板走 **`jdbc_mapped`** 时，PARSE / VALIDATE / LOAD 的流式主路径已保留 **`Map<String,Object>`** 逻辑行，减少示例实体字段对通用导入的约束。

## 分发 DISPATCH — `DispatchChannelAdapter`

- **路由**：`DispatchChannelGateway`（熔断 + 健康短路 + Micrometer）按 **`channel_type`**：`API` / `API_PUSH` → HTTP；`LOCAL` → 本地目录；**`NAS`** → 共享目录写入；**`OSS`** → MinIO / S3 兼容对象存储；**`SFTP`** → JSch；**`EMAIL`** → SMTP。
- **渠道配置**：表字段 + **`config_json`** 合并（**`ChannelConfigMerge`**），密钥与 `sftp_*` / `smtp_*` / `receipt_poll_url` 等放在 **`config_json`**；`channel_type` / `channel_code` 等治理保留键不允许被 JSON 覆盖。
- **NAS / OSS 配置**：`nas_remote_directory` / `nas_remote_file_name` 用于共享目录落盘；`oss_bucket` / `oss_object_prefix` 用于对象存储落盘，兼容 `batch.storage.s3` 默认连接信息。
- **异步回执**：**`DispatchReceiptPollScheduler`** GET **`receipt_poll_url?externalRequestId=`**（`SENT`+`PENDING` 行）。
- **健康治理**：`DispatchChannelHealthService` 维护 `batch.file_channel_health`，对 NAS/OSS 做主动探测和失败退避，网关在 dispatch 前会做健康短路。
- **扩展**：替换 NAS/OSS = 新 `DispatchChannelAdapter` 独占类型。

## 配置表

- 模板与路由字段：`batch.file_template_config`（如 `load_target_ref`、`export_data_ref`）
- 渠道：`batch.file_channel_config`
- 文件实例：`batch.file_record`

---

## 与设计说明书 §9.3 / §9.4 的对应关系

设计文档中的阶段命名与本仓库 **import / export Worker** 的 Step 一一对应（实现类在 `batch-worker-import` / `batch-worker-export` 的 `stage` 包下）。

### 导入（§9.3 文件导入链路）

| 设计阶段 | 实现 | 插件或路由 |
|----------|------|------------|
| RECEIVE | `ReceiveStep` | 平台步骤，非 `ImportLoadPlugin` |
| PREPROCESS | `PreprocessStep` | 同上 |
| PARSE | `ParseStep` | **JSON / DELIMITED / EXCEL(XLSX) / XML / FIXED_WIDTH** → 常落 NDJSON 临时文件 |
| VALIDATE | `ValidateStep` | 同上 |
| **LOAD** | `LoadStep` | **`ImportLoadPlugin`**，由 **`load_target_ref`** 路由 |
| FEEDBACK | `FeedbackStep` | 同上 |

### 导出（§9.4 文件导出链路）

| 设计阶段 | 实现 | 插件或路由 |
|----------|------|------------|
| PREPARE | `PrepareStep` | 准备上下文与模板 |
| **GENERATE** | `GenerateStep` | **`ExportDataPlugin`**，由 **`export_data_ref`** 路由 |
| STORE | `StoreStep` | MinIO：**`.part` 上传 → 摘要校验 → 晋升正式对象** |
| REGISTER | `RegisterStep` | 登记导出结果 |
| COMPLETE | `CompleteStep` | 收尾 |

---

## 相对设计说明书的补充与差异（摘要）

以下内容是对《批量调度系统设计说明书》**第 9 章文件链路**的**对齐说明**，避免把「插件层能力」和「整条流水线是否已做到设计全文」混为一谈。

### 与设计一致、或方向一致的部分

- **扩展点位置**：设计中的 LOAD「入库器」、GENERATE「文件生成器」在代码里对应 **`ImportLoadPlugin` / `ExportDataPlugin`**，通过模板字段 **路由到具体 Bean**，符合「稳定接口 + 可替换实现」。
- **导出分隔符与转义**：§9.4 要求 CSV 类输出避免裸拼接、需处理引号与换行等；当前 **`GenerateStep`** 已把 **`DELIMITED / EXCEL / FIXED_WIDTH / JSON`** 拆开，`DELIMITED` 使用模板级 **`delimiter` / `quotePolicy` / `escapePolicy` / `headerRows`** 生成文件，与说明书方向一致。
- **幂等与分批**：设计中的「分批写入」在 **`jdbc_mapped`** 路径上体现为 **按 chunk 调用 `JdbcTemplate.batchUpdate`**；`conflictColumns` 时 **PostgreSQL `ON CONFLICT`** 与 §9.9「upsert / 批次维度幂等」兼容（具体键仍由表 DDL 与业务主键决定）。

### 实现侧**补充**（说明书未规定必须用 Java 类扩展）

- **Generic JDBC 插件**（`jdbc_mapped` / `jdbc_mapped_export`）：在同一插件接口下，用**模板里的表名、列映射、schema 白名单**减少「每表一个类」；属于对设计「多上下游主要靠配置」的**工程补充**，**不违背**插件模型。
- **PARSE 落地文件**：解析阶段可将中间结果落到**本地 NDJSON 等**（流式形态由平台约定），与 §9.3 中「解析 → 再 LOAD」拆分一致；细节以各 Step 实现为准。

### 与说明书仍有差距（非本页插件能单独补齐）

- **LOAD 策略**：说明书允许 **中间表导入、异步合并**（§9.3 扩展点表、§9.9 入库级幂等）。当前 **`jdbc_mapped`** 为 **直连业务表**的批量 **INSERT / UPSERT**，**不**等同于「仅 staging + 下游 `INSERT…SELECT` / 异步合并 job」；若要走该模式，需要 **staging 表 + 合并作业** 或 **专用 `ImportLoadPlugin`**。
- **格式与能力**：GENERATE 已支持 **JSON/DELIMITED/EXCEL/FIXED_WIDTH**；导出 **snapshotMode** 等全文参数见 **`design-gap-audit.md`** §9。导入 PARSE 已含 Excel/XML/定长，当前两侧能力已基本对齐，剩余差距主要在更细的产品化和样式能力。
- **边查边写 / 禁止全量加载**：设计说明书 §9.12 类约束与当前 **Parse / 部分校验 / 部分导出路径** 仍有差距，以 gap 文档为准；**分页导出明细**在 `ExportDataPlugin.loadDetailPage` + `GenerateStep` 循环中已改为 **cursor/keyset** 推进，减少大偏移页扫描，但这仍不等价于全链路已完全达标。

以上摘要的权威对照仍以仓库 **`design-gap-audit.md`** 与 **`批量调度系统设计说明书（完整版）-20260321.md`** 原文为准；本文件仅服务 **Worker 插件与路由**说明。
