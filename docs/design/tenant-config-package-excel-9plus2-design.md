# 租户配置包 Excel 9+2 设计

> 状态：方案设计 v3（决定不兼容旧 8-sheet 格式，硬切到新格式）。
>
> **新格式 = 8 业务核心 sheet + 3 可选基础依赖 sheet** = 总计 11 个数据 sheet。
> 旧 8-sheet 格式（含 `alert_routing_config`）**直接停用**，不做过渡兼容。
>
> 相对历史 8 sheet 的变化：
> - **新增** 4 个 sheet：`file_template_config`（必填）+ `resource_queue / business_calendar / batch_window`（均可选，引用时必填且允许 DB fallback）
> - **移除** 1 个 sheet：`alert_routing_config` —— 改走独立 Excel 入口 `/config/excel?domain=alert-routings`（已落地）

## 背景

现有整合式租户配置包能导入作业、流水线、工作流、通道和告警路由，但缺少 `file_template_config`。
这会导致 Import / Export 的关键运行配置不在同一个包内：

- Import 目标表、字段映射、冲突键在 `file_template_config.query_param_schema.jdbcMappedImport`。
- Export 查询 SQL 在 `file_template_config.default_query_sql`。
- Export 的表结构式配置在 `file_template_config.query_param_schema.jdbcMappedExport`。

因此当前 8-Sheet 包适合“已有基础模板的环境内维护”，不适合“跨环境迁移、压测复现、从零导入一条可运行链路”。

## 设计目标

租户配置包应聚焦“让一条作业链路可迁移、可审计、可运行”，不替代所有前端独立配置页面。

目标：

1. 把 Import / Export 的表名、字段映射、导出 SQL 纳入同一个配置包。
2. 把资源队列、日历、批次窗口作为**可选基础依赖** sheet，避免默认模板过重；引用时必须存在（本包或 DB 二选一）。
3. 在 Excel 内显式说明跨 sheet 依赖，降低填写和审核成本。
4. preview 阶段一次性暴露字段错误、JSON/SQL 错误、跨 sheet 引用错误。
5. **标识环境特定（environment-specific）字段**，让跨环境迁移时使用方明确知道哪些字段需 review，不会照搬出错。

非目标：

- 不把用户、角色、菜单、权限放入配置包。
- 不把 `tenant_quota_policy` 放入配置包；它是租户级治理策略，不应被业务作业包顺手修改。
- 不把 `worker_registry / step_registry` 放入配置包；它们来自运行时注册。
- 不通过配置包创建业务表结构；只校验业务表和列存在。
- 不把归档、巡检、观测、告警事件等环境治理配置放入配置包。
- **不把 `alert_routing_config` 放入配置包**：告警路由（team / receiver / severity → channel 映射）是**环境侧治理资产**，prod / staging / dev 各有自己的 oncall 拓扑，跨环境照搬反而是反模式。`alert_routing` 已有独立 Excel 入口（前端路由 `/config/excel?domain=alert-routings`，后端 `/api/console/config/alert-routings/excel/*`），用户改去那里维护。
- **不解决环境参数 / 配置骨架分层**：本设计接受"配置包混合了部分环境特定字段（如 `resource_queue.max_running_jobs` / `file_channel_config.endpoint` / `business_calendar.holidays`）"，通过字段标识 + preview warning 提示用户 review；彻底的 logical skeleton + per-env overlay 分层属于另一份 ADR。

## 9+2 Sheet 范围

> **范围口径**：9 = 业务核心 sheet（作业链路自身定义）；+2 = 可选基础依赖 sheet（队列 / 日历 / 窗口 ─ 引用时必填，允许 DB fallback）。
>
> 实际可选 sheet 是 **3 个**（resource_queue / business_calendar / batch_window），名字保留 "9+2" 是因为 resource_queue 历史上被算作核心；本次设计统一降到可选区。

### 业务核心 9 Sheet（作业链路自身）

Excel 展示顺序优先服务“人怎么填”：先放作业主入口，再放作业依赖的模板、通道，最后执行 / 编排定义。

| 顺序 | Sheet | 定位 | 是否可空 |
|---:|---|---|---|
| 1 | `job_definition` | 作业入口；调度、worker 分组、默认参数 | 建议非空 |
| 2 | `file_template_config` | 文件模板；Import 目标表、字段映射、Export SQL 的核心配置 | 建议非空 |
| 3 | `file_channel_config` | 文件 / 派发通道；Dispatch 和文件投递运行依赖 | 依赖 Dispatch 场景 |
| 4 | `pipeline_definition` | Worker 执行流水线定义 | 依赖 worker 场景 |
| 5 | `pipeline_step_definition` | Worker 执行步骤；`stage_code / impl_code / step_params` | 依赖 worker 场景 |
| 6 | `workflow_definition` | 工作流定义 | 依赖 workflow 场景 |
| 7 | `workflow_node` | 工作流节点，关联 job / pipeline | 依赖 workflow 场景 |
| 8 | `workflow_edge` | 工作流边，定义 DAG / 条件流转 | 依赖 workflow 场景 |

> 历史 8 sheet 里的 `alert_routing_config` 已剔除（见 §设计目标·非目标），1-8 是真正属于"作业链路自身"的核心定义。

### 可选基础依赖 3 Sheet（引用时必填，允许 DB fallback）

三者语义对称：包内不存在但被引用时，会先在 DB 兜底查找；DB 也没有才报错。

| 顺序 | Sheet | 定位 | 触发要求 |
|---:|---|---|---|
| 9 | `resource_queue` | 调度资源队列，支撑 `job_definition.queue_code` / 调度压测 / waiting dispatch 复现 | 任意 `job_definition.queue_code` 有值时，必须在本包或库中存在 |
| 10 | `business_calendar` | 业务日历 | 任意 `job_definition.calendar_code` 有值时，必须在本包或库中存在 |
| 11 | `batch_window` | 批次窗口 | 任意 `job_definition.window_code` 或 `workflow_node.window_code` 有值时，必须在本包或库中存在 |

说明：

- Excel 模板**固定生成 11 个数据 sheet**，但 9-11 在填写说明里明确为"可留空，仅引用时需要"。
- Excel 展示顺序不等于后端落库顺序。后端导入顺序不依赖用户调整后的 sheet 顺序，必须按依赖拓扑顺序落库（见 §导入执行顺序）。
- **不出现在配置包内的相关 sheet**：`alert_routing_config`（已迁独立 Excel 入口）、`tenant_quota_policy`（租户治理）、`worker_registry / step_registry`（运行时上报）。

### Environment-specific 字段标识

下表字段在跨环境迁移时**不能照搬**，preview 时按 sheet 弹 WARNING（不阻断 apply）让用户 review：

| Sheet | 字段 | 为什么是 environment-specific |
|---|---|---|
| `resource_queue` | `max_running_jobs / max_running_partitions / max_qps / worker_group` | 各环境容量 / worker 拓扑不同 |
| `file_channel_config` | `endpoint / sftp_host / sftp_port / oss_bucket / credentials_ref` | 各环境对接的外部系统 endpoint 不同 |
| `business_calendar` | `holidays / timezone` | 跨地区环境节假日 / 时区可能不同 |
| `batch_window` | `start_time / end_time / timezone` | 各环境批跑时段不同 |
| `file_template_config` | `query_param_schema.jdbcMappedImport.schema` | 业务库 schema 名各环境可能不同 |

> 字段说明 sheet 必须给这些字段加 `[env-specific]` 前缀标识，前端 preview 时统一对它们弹"跨环境迁移请 review"提示。

## Workbook 辅助 Sheet

除 9+2 数据 sheet 外，保留并优化辅助 sheet：

| Sheet | 用途 | 要求 |
|---|---|---|
| `填写说明` | 面向用户的填写入口说明 | 说明 9+2 范围、导入流程、可选 sheet、注意事项 |
| `依赖说明` | 单独说明跨 sheet / 数据库依赖关系 | 新增；按依赖来源、字段、目标、是否允许库中已有、错误示例组织 |
| `字段说明` | 每个字段的必填、类型、枚举、示例、填写示例、关联关系 | 第一列按 sheet 合并单元格；末尾新增“填写示例”和“关联关系说明”列 |
| `四类Worker示例` | 展示 IMPORT / EXPORT / PROCESS / DISPATCH 的典型配置组合 | 新增；仅说明用途，不参与导入解析 |
| `校验` | preview 时输出错误 | 保持现有错误输出能力，建议增加错误级别和关联目标 |

### `依赖说明` Sheet 结构

建议列：

| 列名 | 说明 | 示例 |
|---|---|---|
| `source_sheet` | 引用来源 sheet | `job_definition` |
| `source_field` | 引用字段 | `queue_code` |
| `target_sheet` | 目标 sheet | `resource_queue` |
| `target_key` | 目标键 | `queue_code` |
| `db_fallback` | 是否允许数据库已有配置满足依赖 | `TRUE` |
| `required_when` | 何时必须校验 | `queue_code 非空` |
| `description` | 说明 | `作业绑定的资源队列必须存在` |
| `example` | 示例 | `import_queue -> resource_queue.queue_code` |

建议内置依赖行：

| source_sheet | source_field | target_sheet | target_key | db_fallback | required_when |
|---|---|---|---|---|---|
| `job_definition` | `queue_code` | `resource_queue` | `queue_code` | `TRUE` | `queue_code 非空` |
| `job_definition` | `calendar_code` | `business_calendar` | `calendar_code` | `TRUE` | `calendar_code 非空` |
| `job_definition` | `window_code` | `batch_window` | `window_code` | `TRUE` | `window_code 非空` |
| `job_definition` | `default_params.templateCode` | `file_template_config` | `template_code + version` | `TRUE` | `templateCode 非空` |
| `pipeline_definition` | `job_code` | `job_definition` | `job_code` | `TRUE` | `总是` |
| `pipeline_step_definition` | `job_code + version` | `pipeline_definition` | `job_code + version` | `FALSE` | `总是` |
| `pipeline_step_definition` | `step_params.templateCode` | `file_template_config` | `template_code + version` | `TRUE` | `templateCode 非空` |
| `pipeline_step_definition` | `step_params.channelCode / fileChannelCode` | `file_channel_config` | `channel_code` | `TRUE` | `channelCode 非空` |
| `workflow_node` | `workflow_code + workflow_version` | `workflow_definition` | `workflow_code + version` | `FALSE` | `总是` |
| `workflow_node` | `related_job_code` | `job_definition` | `job_code` | `TRUE` | `related_job_code 非空` |
| `workflow_node` | `related_pipeline_code` | `pipeline_definition` | `job_code` | `TRUE` | `related_pipeline_code 非空` |
| `workflow_node` | `window_code` | `batch_window` | `window_code` | `TRUE` | `window_code 非空` |
| `workflow_edge` | `workflow_code + workflow_version` | `workflow_definition` | `workflow_code + version` | `FALSE` | `总是` |
| `workflow_edge` | `from_node_code / to_node_code` | `workflow_node` | `node_code` | `FALSE` | `总是` |

## `字段说明` Sheet 优化

现有 `字段说明` 的第一列每行重复 sheet 名，阅读成本高。优化如下：

1. 第一列 `所属 Sheet` 按连续 sheet 分组纵向合并单元格。
2. 合并单元格应垂直居中、顶部加粗边框，用于区分 sheet 分段。
3. 末尾新增两列：`填写示例`、`关联关系说明`。
4. `示例` 用短值展示格式，`填写示例` 用贴近真实业务的完整值或 JSON 片段。
5. 非关联字段的 `关联关系说明` 留空；关联字段写清来源和目标。
6. **字段说明中的枚举值必须以真实 validator / enum 为准**（详见 §当前模板审计与体验优化）。CI 单测必须断言"字段说明 sheet 行内容 == 后端 enum 集合"，否则文档漂移即编译挂。
7. **environment-specific 字段加 `[env-specific]` 前缀**（参见 §Environment-specific 字段标识表）。

建议列顺序：

| 列 | 名称 |
|---:|---|
| 1 | `所属 Sheet` |
| 2 | `列名` |
| 3 | `必填` |
| 4 | `类型` |
| 5 | `可选值` |
| 6 | `说明` |
| 7 | `示例` |
| 8 | `填写示例` |
| 9 | `关联关系说明` |

示例：

| 所属 Sheet | 列名 | 必填 | 类型 | 可选值 | 说明 | 示例 | 填写示例 | 关联关系说明 |
|---|---|---|---|---|---|---|---|---|
| `job_definition` | `queue_code` | 选填 | 字符串 |  | 资源队列编码 | `import_queue` | `import_heavy_queue` | 引用 `resource_queue.queue_code`；本包或库中必须存在 |
| `job_definition` | `default_params` | 选填 | JSON |  | 默认参数 | `{"templateCode":"import_customer_v1"}` | `{"templateCode":"import_customer_v1","bizDate":"${bizDate}"}` | `templateCode` 引用 `file_template_config.template_code` |
| `file_template_config` | `query_param_schema` | 选填 | JSON |  | 模板运行参数 | `{...}` | `{"jdbcMappedImport":{"schema":"biz","table":"customer_account"}}` | Import 使用 `jdbcMappedImport.table`；Export 可使用 `jdbcMappedExport.*` |
| `file_template_config` | `default_query_sql` | 选填 | SQL | SELECT | 导出查询 SQL | `select id,...` | `select id, tenant_id, settlement_no from biz.settlement_detail where tenant_id = :tenantId order by id asc` | Export SQL；仅允许安全 SELECT |

## `四类Worker示例` Sheet

建议新增只读说明 sheet `四类Worker示例`，用于给配置人员提供“最小可运行组合”的参考。该 sheet 不参与上传解析和 apply，避免示例行被误当成真实配置。

建议列：

| 列名 | 说明 |
|---|---|
| `worker_type` | `IMPORT / EXPORT / PROCESS / DISPATCH` |
| `job_type` | 对应 `job_definition.job_type` |
| `pipeline_type` | 对应 `pipeline_definition.pipeline_type` |
| `stage_chain` | 典型 stage 顺序 |
| `required_sheets` | 最少需要填写的数据 sheet |
| `template_requirement` | 是否需要 `file_template_config` |
| `channel_requirement` | 是否需要 `file_channel_config` |
| `key_params` | 关键 JSON 参数 |
| `demo_description` | 场景说明 |

建议内置 4 行：

| worker_type | job_type | pipeline_type | stage_chain | required_sheets | template_requirement | channel_requirement | key_params | demo_description |
|---|---|---|---|---|---|---|---|---|
| `IMPORT` | `IMPORT` | `IMPORT` | `RECEIVE -> PREPROCESS -> PARSE -> VALIDATE -> LOAD -> FEEDBACK` | `job_definition, file_template_config, pipeline_definition, pipeline_step_definition` | `query_param_schema.jdbcMappedImport` 配 `schema/table/columnMappings/conflictColumns` | 可选，取决于 RECEIVE 来源 | `default_params.templateCode` / `step_params.templateCode` | 文件导入到业务表 |
| `EXPORT` | `EXPORT` | `EXPORT` | `PREPARE -> GENERATE -> STORE -> REGISTER -> COMPLETE` | `job_definition, file_template_config, pipeline_definition, pipeline_step_definition` | `default_query_sql` 或 `query_param_schema.jdbcMappedExport` | STORE 可依赖对象存储配置；派发另配 DISPATCH | `default_params.templateCode` / `query_param_schema.sqlTemplateExport.cursorColumn` | 查询业务表生成文件 |
| `PROCESS` | `PROCESS` | `PROCESS` | `PREPARE -> COMPUTE -> VALIDATE -> COMMIT -> FEEDBACK` | `job_definition, pipeline_definition, pipeline_step_definition` | 通常不需要文件模板 | 不需要 | `step_params.targetSchema/targetTable/sql/columnMappings` | SQL transform / 加工落表 |
| `DISPATCH` | `DISPATCH` | `DISPATCH` | `PREPARE -> DISPATCH -> ACK -> RETRY -> COMPENSATE -> COMPLETE` | `job_definition, file_channel_config, pipeline_definition, pipeline_step_definition` | 通常不需要文件模板；若派发导出文件则引用 export 产物 | 必须，`channelCode` 指向 `file_channel_config.channel_code` | `step_params.channelCode/fileChannelCode` | 推送文件或消息到外部系统 |

实现要求：

- 该 sheet 应冻结首行、开启筛选、设置只读风格。
- 行内容必须来自同一份常量或测试快照，避免与真实 enum / stage 配置漂移。
- 明确标注"不参与导入；请复制到对应数据 sheet 后修改"。
- **CI 防漂移**：必须有单测 `ConfigPackageFourWorkerExamplesDriftTest` 断言示例 sheet 内容与 `PipelineType` / `JobType` / `StageCode` enum 集合一致。enum 新增成员而示例 sheet 未同步即编译挂。这条单测和 §字段说明 enum 一致性单测使用同一份 fixture，避免双线漂移。

## 关键字段设计

### `file_template_config`

建议沿用独立文件模板 Excel 的字段，并纳入合包：

```text
tenant_id
template_code
template_name
template_type
biz_type
file_format_type
charset
target_charset
with_bom
line_separator
delimiter
quote_char
escape_char
record_length
header_rows
footer_rows
header_template
trailer_template
checksum_type
compress_type
encrypt_type
naming_rule
field_mappings
validation_rule_set
default_query_code
default_query_sql
query_param_schema
streaming_enabled
page_size
fetch_size
chunk_size
preview_masking_enabled
error_line_masking_enabled
log_masking_enabled
content_encryption_enabled
encryption_key_ref
download_requires_approval
masking_rule_set
enabled
version
description
```

Import 目标表配置在：

```json
{
  "jdbcMappedImport": {
    "schema": "biz",
    "table": "customer_account",
    "tenantColumn": "tenant_id",
    "columnMappings": [
      { "from": "customerNo", "to": "customer_no" }
    ],
    "conflictColumns": ["tenant_id", "customer_no"]
  }
}
```

Export SQL 配置在：

```text
default_query_sql
```

Export 表结构式配置在：

```json
{
  "export_data_ref": "jdbc_mapped_export",
  "jdbcMappedExport": {
    "schema": "biz",
    "batchTable": "settlement_batch",
    "detailTable": "settlement_detail",
    "batchTenantColumn": "tenant_id",
    "batchNoColumn": "batch_no",
    "detailFkColumn": "batch_id",
    "detailOrderByColumn": "id"
  }
}
```

### `resource_queue`

建议字段：

```text
tenant_id
queue_code
queue_name
queue_type
max_running_jobs
max_running_partitions
max_qps
worker_group
resource_tag
priority_policy
fair_share_weight
enabled
description
```

`queue_code` 被 `job_definition.queue_code` 引用。资源队列进入合包的原因是：它直接决定调度、队列积压和压测复现能力。

### `business_calendar`

可选 sheet。建议字段：

```text
tenant_id
calendar_code
calendar_name
timezone
holiday_roll_rule
catch_up_policy
catch_up_max_days
holidays
enabled
description
```

`holidays` 可用 JSON 数组或逗号分隔日期；实现时应选择一种并在字段说明中固定。

### `batch_window`

可选 sheet。建议字段：

```text
tenant_id
window_code
window_name
timezone
start_time
end_time
end_strategy
out_of_window_action
allow_cross_day
enabled
description
```

被 `job_definition.window_code` 和 `workflow_node.window_code` 引用。

## 校验规则

### 单 Sheet 校验

- 表头必须完整，允许空数据 sheet。
- 必填字段、字段长度、整数范围、布尔值、枚举值校验。
- JSON 字段必须是合法 JSON。
- `default_query_sql` 非空时必须是安全 SELECT。
- `query_param_schema.jdbcMappedImport` / `jdbcMappedExport` 出现时必须符合结构。
- Excel 公式注入防护继续保留：导出预览 workbook 时文本型单元格需要 escape。

### 跨 Sheet / 数据库校验

校验模型：

1. 先读取所有 9+2 sheet，建立本包内索引。
2. 再对引用字段检查“本包存在或数据库已存在”。
3. 对不允许 DB fallback 的关系，只能在本包内存在。
4. 输出所有问题到 preview，而不是遇到第一条就中断。

必须校验：

- `job_definition.queue_code` → `resource_queue.queue_code`
- `job_definition.calendar_code` → `business_calendar.calendar_code`
- `job_definition.window_code` → `batch_window.window_code`
- `job_definition.default_params.templateCode` → `file_template_config.template_code`
- `pipeline_definition.job_code` → `job_definition.job_code`
- `pipeline_step_definition.job_code + version` → `pipeline_definition.job_code + version`
- `pipeline_step_definition.step_params.templateCode` → `file_template_config.template_code`
- `pipeline_step_definition.step_params.channelCode / fileChannelCode` → `file_channel_config.channel_code`
- `workflow_node.related_job_code` → `job_definition.job_code`
- `workflow_node.related_pipeline_code` → `pipeline_definition.job_code`
- `workflow_node.window_code` → `batch_window.window_code`
- `workflow_edge.from_node_code / to_node_code` → `workflow_node.node_code`

### SQL / 表结构安全校验

#### 业务表 schema 白名单的存放点

`jdbcMappedImport.schema` 和 `default_query_sql` 引用 schema 必须在允许白名单内。**白名单存哪**：

- 白名单是**部署侧配置**（per-env），不是租户级配置；写在 `batch.security.allowed-schemas` Spring property，env 不同值不同（dev: `biz, biz_test`；prod: `biz` 严格）。
- **不进配置包**：白名单和业务表存在性属于"目标环境状态"，不应跨环境照搬。
- 跨环境迁移配置包时，若目标 env 白名单缺少源 env 用到的 schema，preview 必须报错，引导运维补 property 后重试。

#### Import 校验

- `jdbcMappedImport.schema` 必须在允许 schema 白名单内。
- `jdbcMappedImport.table` 必须是合法标识符（`^[a-zA-Z_][a-zA-Z0-9_]{0,62}$`），且业务表存在。
- `jdbcMappedImport.columnMappings.to` 必须是目标表列。
- `jdbcMappedImport.conflictColumns` 必须是目标表列。

#### Export SQL 校验（white-list 不是 black-list）

`default_query_sql` 解析后必须满足**所有**条件：

| 维度 | 允许 | 拒绝 |
|---|---|---|
| 语句类型 | `SELECT` (单条) | DDL / DML / `WITH RECURSIVE` / 多语句（`;` 分隔） |
| 子句 | `WITH` 普通 CTE / `JOIN` / `WHERE` / `GROUP BY` / `HAVING` / `ORDER BY` / `LIMIT` / `OFFSET` | `INTO` / `FOR UPDATE` / `FOR SHARE` / locking hints |
| 表引用 | 显式 `schema.table` 且 schema 在白名单内 | 未限定 schema 的表名 / schema 不在白名单 |
| 函数 | 标准 ANSI 聚合 / 字符串 / 日期函数 | PL/pgSQL 调用 / `pg_*` 系统函数 / 自定义函数 |
| 跨 schema | 允许若两端 schema 都在白名单 | — |
| 参数占位符 | 命名参数 `:tenantId / :bizDate` 等 | 位置参数 `?` |

> 实现层用 JSqlParser 或 PG 原生 SQL parser 做 AST 校验，不允许 substring / regex hack。

#### Export 结构式校验

- `sqlTemplateExport.cursorColumn` 必须出现在 SELECT 列中。
- `jdbcMappedExport.batchTable / detailTable` 必须是合法标识符，且业务表存在。
- `jdbcMappedExport.*Column / *SelectColumns` 必须是对应表列。

## 导入执行顺序

Excel 展示顺序按业务主线组织；后端 apply 必须按依赖拓扑执行。即使用户调整 Excel sheet 顺序，后端也必须按以下顺序落库：

```text
1. resource_queue        (可选，引用方在 6 之后)
2. business_calendar     (可选)
3. batch_window          (可选)
4. file_template_config
5. file_channel_config
6. job_definition
7. pipeline_definition
8. pipeline_step_definition
9. workflow_definition
10. workflow_node
11. workflow_edge
```

> `alert_routing_config` 不在此列：从配置包剔除，由独立 Excel 入口落库。

执行策略：

- **单事务原子提交**：跨 11 张配置表的 upsert 必须在同一个 `@Transactional` 里，preview 有任何 invalid 行或跨引用错误时禁止 apply。
- **业务表存在性检查（用于 jdbcMappedImport / SQL 校验）走独立连接 / 独立事务**：业务表通常在 routing datasource 的"业务库"端，校验仅 read-only metadata 查询，不影响配置表事务边界。注意 `@Transactional(propagation = NOT_SUPPORTED)` 标注，避免被外层事务卷入。
- `file_template_config` 与 `pipeline_definition` 这类父子结构必须先父后子。
- `pipeline_step_definition` 对同一 pipeline 的步骤建议采用"删除后重建"或显式差异更新，但必须保持幂等。
- 工作流节点和边必须在 definition upsert 后重建或差异更新。
- **事务监控**：长事务期间会锁住 11 张配置表的行，建议加 metric `config_package_apply_duration_seconds`，p95 > 5s 报警。

## 前端呈现建议

- 租户配置包页面标题仍保留"租户配置包"。
- 下载模板提示"8 个业务核心 sheet + 3 个可选基础依赖 sheet"。
- preview 结果按数据 sheet 分组，并额外显示"依赖错误"分组和"environment-specific 字段需 review"分组。
- 对 `resource_queue / business_calendar / batch_window` 标记"可选；仅引用时需要"。
- 对 environment-specific 字段（见 §Environment-specific 字段标识表）：preview 时无条件弹 WARNING 分组，列出该字段在源包的值，引导跨环境迁移的用户 review。
- 对 `file_template_config.default_query_sql` 和 `query_param_schema` 提供大文本预览，避免在表格里横向撑爆。建议复用 workflow designer 的 DSL 编辑器（CodeMirror）做只读高亮。
- **拒绝旧 8-sheet 包上传**：上传时若检测到 `alert_routing_config` sheet 或缺少 `file_template_config` sheet，preview 直接报错 `LEGACY_8_SHEET_FORMAT`，提示用户「请下载新模板（11 个数据 sheet）后重新填写；告警路由请走 `/config/excel?domain=alert-routings` 独立入口」。不做容忍式忽略。

## 当前模板审计与体验优化

审计对象：`tenant-package-template.xlsx` 当前导出模板。

现状：

- Workbook 共 11 个 sheet，其中 8 个数据 sheet + `填写说明 / 字段说明 / 校验` 3 个辅助 sheet。
- 数据 sheet 均只有表头，无示例数据行。
- 数据 sheet 已冻结首行并加表头筛选。
- 多数列宽固定为 18，JSON / SQL / 长文本列不够宽。
- `字段说明` 第一列未合并单元格，且没有“关联关系说明”列。
- 当前没有单独的 `依赖说明` sheet。

必须修正的问题：

| 问题 | 影响 | 优化要求 |
|---|---|---|
| `字段说明.job_type` 未列 `PROCESS`，但实际下拉包含 `PROCESS` | 用户按字段说明填写会漏掉 PROCESS | 字段说明必须从真实 enum / validator 派生 |
| `字段说明.schedule_type` 列了 `EVENT / ONE_TIME`，但实际下拉只允许 `CRON / FIXED_RATE / MANUAL` | 用户填 EVENT 会 preview 失败 | 删除文档侧历史值，确保说明与 validator 一致 |
| `字段说明.pipeline_type` 未列 `PROCESS`，但实际下拉包含 `PROCESS` | PROCESS worker 配置不透明 | 字段说明补 `PROCESS` |
| `字段说明.stage_code` 含 `TRANSFER`，但 validator 不接受；同时未完整解释四类 worker stage 集合 | 用户容易填非法 stage | 按 pipeline_type 分组展示 stage：IMPORT / EXPORT / PROCESS / DISPATCH |
| 数据校验下拉来自 Set，展示顺序不稳定 | 下拉项顺序不符合业务认知 | 用固定业务顺序输出，不直接用无序 Set |
| `impl_code` 无注册数据时没有任何提示 | 用户不知道填 bean name、`MODULE:bean` 还是留空 | 即使无法下拉，也应在字段说明和单元格 prompt 写明规则 |

建议优化：

| 优化项 | 说明 |
|---|---|
| 增加 `依赖说明` sheet | 独立承载跨 sheet 引用关系，避免把依赖说明塞进填写说明长文本 |
| `字段说明` 第一列按 sheet 合并 | 降低重复文本，便于快速定位字段组 |
| `字段说明` 末尾加 `关联关系说明` | 对 `queue_code / templateCode / channelCode / job_code / workflow_code` 等引用字段写清目标 |
| `字段说明` 增加 `填写示例` | 区分短示例和可直接参考的真实业务填写片段 |
| 增加 `四类Worker示例` sheet | 展示 IMPORT / EXPORT / PROCESS / DISPATCH 的最小可运行配置组合，降低新用户理解成本 |
| JSON / SQL 列加宽并启用换行 | `default_params / step_params / config_json / query_param_schema / default_query_sql` 不应只有 18 宽 |
| 为 JSON / SQL 字段加输入提示 | 写明必须是合法 JSON、SQL 仅允许 SELECT |
| 增加 `示例` 或 `样例行` 策略 | 可选做法：模板保持空白，但另导出 example workbook；或者在每个 sheet 第 2 行放示例并标识可删除 |
| `校验` sheet 增加列 | 建议扩展为 `severity / sheet_name / row_no / column_name / error_code / error_reason / reference_target` |
| 辅助 sheet 冻结首行并加筛选 | `字段说明 / 依赖说明 / 校验` 都应该可筛选 |
| sheet tab 颜色分组 | 核心业务 sheet、可选依赖 sheet、辅助说明 sheet 分不同 tab 色 |
| 输出文件去除 macOS/Excel 兼容警告 | 当前用 openpyxl 读取有 “Workbook contains no default style” 警告，建议检查 POI 生成的默认样式/主题，避免部分客户端打开体验差 |

## 实施步骤

按优先级排序，**P0 必须在扩 sheet 之前完成**，否则文档侧旧漂移 + 实现侧新双线漂移叠加。

### P0 — 修当下用户必踩坑（先做）

1. **字段说明 enum 一致性修正**：按 §当前模板审计与体验优化"必须修正的问题"表逐条修字段说明 sheet 内容。
2. **CI 防漂移单测**：新增 `ConfigPackageFieldDescriptionEnumConsistencyTest`，断言字段说明 sheet 行内容 == 后端 `JobType / ScheduleType / PipelineType / StageCode / RetryPolicyType` 等 enum 集合。文档新加值 / 删除值即编译挂。

### P0 — 抽 common parser/upsert helper（先做）

3. **重构抽离**：把独立 Excel service（`DefaultConsoleFileTemplateExcelApplicationService` / `DefaultConsoleResourceQueueExcelApplicationService` / `DefaultConsoleBusinessCalendarExcelApplicationService` / `DefaultConsoleBatchWindowExcelApplicationService`）里的**行解析 + upsert** 逻辑抽到 `Workbook*RowParser` + `Workbook*UpsertHelper`，独立 service 和 config-package service 各自调同一份。
4. 抽离后立即在独立 Excel service 验证零回归（跑现有 IT），再让 config-package 复用。

### P1 — 扩 sheet 主体

5. 扩展 `ConfigPackageExcelWorkbookWriter`：新增 `file_template_config / resource_queue / business_calendar / batch_window` 数据 sheet（共 11 个数据 sheet）。**移除 `alert_routing_config` sheet 输出**。
6. 新增 `依赖说明` sheet。
7. 改造 `字段说明` sheet：第一列按 sheet 合并单元格，末尾新增 `填写示例` 和 `关联关系说明`；environment-specific 字段加 `[env-specific]` 前缀。
8. 新增 `四类Worker示例` sheet：只做说明，不参与导入解析。
9. 扩展 `ConfigPackageExcelValidator`：调用 P0 步骤抽出的 `Workbook*RowParser`，增加 4 类 sheet 的单表校验与跨引用索引。
10. 扩展 `DefaultConsoleTenantConfigPackageExcelApplicationService`：解析、preview、apply、export 支持 11 sheet。
11. **业务表存在性检查独立连接**：声明 `@Transactional(propagation = NOT_SUPPORTED)` 标注，避免卷入配置表事务。

### P2 — 周边整合

12. 更新 OpenAPI 描述、前端 `excelDomains.ts` 注释、测试数据 README。
13. 添加 metric `config_package_apply_duration_seconds`，p95 > 5s 报警。

### 测试清单

- 模板 sheet 顺序和表头测试（断言 11 个数据 sheet + 5 个辅助 sheet）。
- 字段说明合并单元格测试。
- 字段说明 `填写示例` / `关联关系说明` / `[env-specific]` 前缀测试。
- `依赖说明` 内容测试。
- `四类Worker示例` 内容测试 + enum 一致性 CI 断言。
- Import 表名 / Export SQL 随合包导入测试。
- 引用不存在时 preview 报错测试。
- 11 sheet 数据全空（仅表头）的解析测试，确认不抛异常。
- **旧 8-sheet 包上传拒绝测试**：含 `alert_routing_config` 或缺 `file_template_config` 时 preview 报 `LEGACY_8_SHEET_FORMAT`，引导跳转独立入口。
- Environment-specific 字段 preview WARNING 测试。

## 硬切策略（不兼容旧 8-sheet）

- 新格式（11 sheet）是**唯一格式**，旧 8-sheet 不再被接受。
- 上传时按以下规则识别旧包并直接拒绝：
  - 含 `alert_routing_config` sheet → 报 `LEGACY_8_SHEET_FORMAT_ALERT_ROUTING`，提示去 `/config/excel?domain=alert-routings` 独立入口。
  - 缺 `file_template_config` sheet → 报 `LEGACY_8_SHEET_FORMAT_MISSING_FILE_TEMPLATE`，提示下载新模板。
- 后端 export / 模板下载一律产出 11 sheet。
- 已存在的旧包**由使用方负责手工迁移**（导出现有配置 → 调整为新模板 → 上传），不提供自动转换脚本。

### 跨环境迁移流程建议

1. 源环境导出 11 sheet 包。
2. 目标环境运维 review 包内所有 `[env-specific]` 字段，按目标 env 调整（resource_queue 容量 / file_channel endpoint / calendar holidays / batch_window 时段 / schema 名）。
3. 目标环境 preview，确认无 ERROR / WARNING 后 apply。
4. 单独走 `/config/excel?domain=alert-routings` 入口配置目标 env 的告警路由。

> 长期方案：见 ADR-TBD「配置包 logical skeleton + per-env overlay」（待立项）。
