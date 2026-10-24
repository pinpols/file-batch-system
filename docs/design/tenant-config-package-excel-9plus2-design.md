# 租户配置包 Excel 9+2 设计

> **当前规格**：完整配置包导出 **10 个数据 sheet**：7 个业务定义 + 3 个可选基础依赖。历史文件名 `9plus2` 保留以维持引用，不代表当前数量。
>
> `ConfigPackageSheetSpecs` 仅保留当前 10 张数据 sheet 的规格，旧 Pipeline sheet 规格及示例已删除。解析器显式拒绝 `pipeline_definition`、`pipeline_step_definition` 旧 sheet，不做转换。新运行按 Job 类型选择系统 Pipeline，Job 保存阶段业务参数，调度归属 Workflow。
>
> 字典、填写说明、填写顺序和校验结果等辅助 sheet 不计入数据 sheet。场景专用模板按场景投影，不一定包含完整包的 10 个数据 sheet；空表/必需表规则以当前解析器和模板为准。旧 Pipeline/FILE_STEP 配置数据不得静默转换或导入。

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
- **不把 `alert_routing_config` 放入配置包**：告警路由（team / receiver / severity → channel 映射）是**环境侧治理资产**，prod / staging / dev 各有自己的 oncall 拓扑，跨环境照搬反而是反模式。`alert_routing` 已有前端独立 CRUD 页 `/observability/alert-routings`（`AlertRoutingPanel.vue`）+ 后端 `ConsoleAlertRoutingController` 单条 CRUD API，**不再提供 Excel 批量入口**（规模小、强环境相关，Excel 批量没价值）。
- **Excel 不拆成 logical skeleton + per-env overlay 双文件格式**：本设计仍保持 单工作簿，避免让一线租户维护两份 Excel。轻量的 base package + tenant overlay 已在 Console REST 层落地：`/api/console/config/tenant-overlay/preview` 以基础租户为 base，按目标租户生成只含 add/update 的 `overlayBundle`，并对 `file_channel`、`resource_queue`、`batch_window`、`business_calendar` 等环境特定配置给出 review 提示。

## 当前 Sheet 范围

### 业务定义 7 Sheet

| Sheet | 定位 |
|---|---|
| `job_definition` | 类型、Worker 路由、执行策略与业务参数；不是顶层日程 |
| `job_monitoring_policy` | 作业启动、完成、耗时阈值及告警级别；与执行定义分离 |
| `file_template_config` | Import/Export 模板、字段映射与查询参数 |
| `file_channel_config` | 文件来源与外部投递渠道引用 |
| `workflow_definition` | 顶层运行图、日程、日历、时区与启停 |
| `workflow_node` | START/END/JOB/GATEWAY/WAIT；JOB 引用 `related_job_code` |
| `workflow_edge` | 同图依赖、条件分支与汇聚 |

### 可选基础依赖 3 Sheet

| Sheet | 引用规则 |
|---|---|
| `resource_queue` | 被 Job 引用时须在本包或目标租户库存在 |
| `business_calendar` | 被 Workflow 引用时须存在；是批量日解析依据 |
| `batch_window` | Job/节点的资源准入窗口引用，不扩张为全图运行窗口 |

Excel 展示顺序由当前模板生成器确定，不作为服务端事务写入顺序。不能导入 `pipeline_definition`、`pipeline_step_definition`、`FILE_STEP.related_pipeline_code` 或 `job_type=WORKFLOW`。告警路由、租户配额、Worker/Step 注册继续走各自治理入口，不混入业务配置包。

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

除当前数据 sheet 外，保留并优化辅助 sheet：

| Sheet | 用途 | 要求 |
|---|---|---|
| `填写说明` | 面向用户的填写入口说明 | 说明当前范围、导入流程、可选 sheet、注意事项 |
| `依赖说明` | 单独说明跨 sheet / 数据库依赖关系 | 新增；按依赖来源、字段、目标、是否允许库中已有、错误示例组织 |
| `字段说明` | 每个字段的必填、填写层级、类型、枚举、示例、填写示例 | 第一列按 sheet 合并单元格；用“填写层级”区分必填/常用/高级；跨 sheet 关联关系放到 `依赖说明` |
| `五类Worker示例` | 展示 IMPORT / EXPORT / PROCESS / DISPATCH / ATOMIC 的典型配置组合 | 新增；仅说明用途，不参与导入解析；ATOMIC 无 Pipeline |
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
| `workflow_node` | `workflow_code + workflow_version` | `workflow_definition` | `workflow_code + version` | `FALSE` | `总是` |
| `workflow_node` | `related_job_code` | `job_definition` | `job_code` | `TRUE` | `related_job_code 非空` |
| `workflow_node` | `window_code` | `batch_window` | `window_code` | `TRUE` | `window_code 非空` |
| `workflow_edge` | `workflow_code + workflow_version` | `workflow_definition` | `workflow_code + version` | `FALSE` | `总是` |
| `workflow_edge` | `from_node_code / to_node_code` | `workflow_node` | `node_code` | `FALSE` | `总是` |

## `字段说明` Sheet 优化

现有 `字段说明` 的第一列每行重复 sheet 名，阅读成本高。优化如下：

1. 第一列 `所属 Sheet` 按连续 sheet 分组纵向合并单元格。
2. 合并单元格应垂直居中、顶部加粗边框，用于区分 sheet 分段。
3. 新增 `填写层级` 列：`必填 / 常用 / 高级 / 可选`，其中 `file_template_config` 的低频安全、压缩、加密、固定宽度、导出专用字段标为高级。
4. `示例` 用短值展示格式，`填写示例` 用贴近真实业务的完整值或 JSON 片段。
5. 跨 sheet 引用关系不再挤进字段说明，统一放到 `依赖说明` sheet。
6. **字段说明中的枚举值必须以真实 validator / enum 为准**（详见 §当前模板审计与体验优化）。CI 单测必须断言"字段说明 sheet 行内容 == 后端 enum 集合"，否则文档漂移即编译挂。
7. **environment-specific 字段加 `[env-specific]` 前缀**（参见 §Environment-specific 字段标识表）。

建议列顺序：

| 列 | 名称 |
|---:|---|
| 1 | `所属 Sheet` |
| 2 | `列名` |
| 3 | `必填` |
| 4 | `填写层级` |
| 5 | `类型` |
| 6 | `可选值` |
| 7 | `说明` |
| 8 | `示例` |
| 9 | `适用 Worker` |
| 10 | `填写示例` |

示例：

| 所属 Sheet | 列名 | 必填 | 填写层级 | 类型 | 可选值 | 说明 | 示例 | 适用 Worker | 填写示例 |
|---|---|---|---|---|---|---|---|---|---|
| `job_definition` | `queue_code` | 选填 | 可选 | 字符串 |  | 资源队列编码 | `import_queue` | ALL | `import_heavy_queue` |
| `job_definition` | `default_params` | 选填 | 可选 | JSON |  | 默认参数 | `{"templateCode":"import_customer_v1"}` | IMPORT/EXPORT | `{"templateCode":"import_customer_v1","bizDate":"${bizDate}"}` |
| `file_template_config` | `query_param_schema` | 选填 | 常用 | JSON |  | 模板运行参数 | `{...}` | IMPORT/EXPORT | `{"jdbcMappedImport":{"schema":"biz","table":"customer_account"}}` |
| `file_template_config` | `default_query_sql` | 选填 | 高级 | SQL | SELECT | 导出查询 SQL | `select id,...` | EXPORT | `select id, tenant_id, settlement_no from biz.settlement_detail where tenant_id = :tenantId order by id asc` |

## `五类Worker示例` Sheet

建议新增只读说明 sheet `五类Worker示例`，用于给配置人员提供“最小可运行组合”的参考。该 sheet 不参与上传解析和 apply，避免示例行被误当成真实配置。IMPORT / EXPORT / PROCESS / DISPATCH 使用 Pipeline；ATOMIC 仅配置 `job_definition`，不得伪造 Pipeline stage。

建议列：

| 列名 | 说明 |
|---|---|
| `worker_type` | `IMPORT / EXPORT / PROCESS / DISPATCH / ATOMIC` |
| `job_type` | 对应 `job_definition.job_type` |
| `pipeline_type` | 由 Job 类型映射的系统 Pipeline 类型（不是导入字段） |
| `stage_chain` | 典型 stage 顺序 |
| `required_sheets` | 最少需要填写的数据 sheet |
| `template_requirement` | 是否需要 `file_template_config` |
| `channel_requirement` | 是否需要 `file_channel_config` |
| `key_params` | 关键 JSON 参数 |
| `demo_description` | 场景说明 |

建议内置 4 行：

| worker_type | job_type | pipeline_type | stage_chain | required_sheets | template_requirement | channel_requirement | key_params | demo_description |
|---|---|---|---|---|---|---|---|---|
| `IMPORT` | `IMPORT` | `IMPORT` | `RECEIVE -> PREPROCESS -> PARSE -> VALIDATE -> LOAD -> FEEDBACK` | `job_definition, file_template_config, workflow_definition, workflow_node, workflow_edge` | `query_param_schema.jdbcMappedImport` 配 `schema/table/columnMappings/conflictColumns` | 可选，取决于 RECEIVE 来源 | `default_params.templateCode` 与对应阶段业务参数 | 文件导入到业务表 |
| `EXPORT` | `EXPORT` | `EXPORT` | `PREPARE -> GENERATE -> STORE -> REGISTER -> COMPLETE` | `job_definition, file_template_config, workflow_definition, workflow_node, workflow_edge` | `default_query_sql` 或 `query_param_schema.jdbcMappedExport` | STORE 可依赖对象存储配置；派发另配 DISPATCH | `default_params.templateCode` / `query_param_schema.sqlTemplateExport.cursorColumn` | 查询业务表生成文件 |
| `PROCESS` | `PROCESS` | `PROCESS` | `PREPARE -> COMPUTE -> VALIDATE -> COMMIT -> FEEDBACK` | `job_definition, workflow_definition, workflow_node, workflow_edge` | 通常不需要文件模板 | 不需要 | COMPUTE 阶段业务参数（冻结于Job配置） | SQL transform / 加工落表 |
| `DISPATCH` | `DISPATCH` | `DISPATCH` | `PREPARE -> DISPATCH -> ACK -> RETRY -> COMPENSATE -> COMPLETE` | `job_definition, file_channel_config, workflow_definition, workflow_node, workflow_edge` | 通常不需要文件模板；若派发导出文件则引用 export 产物 | 必须，`channelCode` 指向 `file_channel_config.channel_code` | 渠道引用与DISPATCH阶段业务参数 | 推送文件或消息到外部系统 |

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

1. 先读取全部当前数据 sheet，建立本包内索引。
2. 再对引用字段检查“本包存在或数据库已存在”。
3. 对不允许 DB fallback 的关系，只能在本包内存在。
4. 输出所有问题到 preview，而不是遇到第一条就中断。

必须校验：

- `job_definition.queue_code` → `resource_queue.queue_code`
- `workflow_definition.calendar_code` → `business_calendar.calendar_code`
- `job_definition.window_code` → `batch_window.window_code`
- `job_definition.default_params.templateCode` → `file_template_config.template_code`
- Job有效参数中的模板引用 → `file_template_config.template_code`
- Job有效参数中的渠道引用 → `file_channel_config.channel_code`
- `workflow_node.related_job_code` → `job_definition.job_code`
- `workflow_node.related_job_code` → `job_definition.job_code`
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

> 实现层用 JSqlParser 或 PG 原生 SQL parser 做 AST 校验，不允许 substring / regex shortcut。

#### Export 结构式校验

- `sqlTemplateExport.cursorColumn` 必须出现在 SELECT 列中。
- `jdbcMappedExport.batchTable / detailTable` 必须是合法标识符，且业务表存在。
- `jdbcMappedExport.*Column / *SelectColumns` 必须是对应表列。

## 导入执行顺序

Excel 展示顺序按业务主线组织；后端 apply 必须按依赖拓扑执行。即使用户调整 Excel sheet 顺序，后端也必须按以下顺序写入数据库：

```text
1. resource_queue        (可选，引用方在 6 之后)
2. business_calendar     (可选)
3. batch_window          (可选)
4. file_template_config
5. file_channel_config
6. job_definition
7. job_monitoring_policy
8. workflow_definition
9. workflow_node
10. workflow_edge
```

> `alert_routing_config` 不在此列：从配置包剔除，由独立 CRUD 页写入数据库。

执行策略：

- **单事务原子提交**：跨当前业务配置对象的 upsert 必须在同一个 `@Transactional` 里，preview 有任何 invalid 行或跨引用错误时禁止 apply。
- **业务表存在性检查（用于 jdbcMappedImport / SQL 校验）走独立连接 / 独立事务**：业务表通常在 routing datasource 的"业务库"端，校验仅 read-only metadata 查询，不影响配置表事务边界。注意 `@Transactional(propagation = NOT_SUPPORTED)` 标注，避免被外层事务卷入。
- 模板、渠道等被引用配置先校验，Job 与其监控策略关联校验，Workflow definition 先于节点和边写入。系统 Pipeline 不参与配置写入。
- 工作流节点和边必须在 definition upsert 后重建或差异更新。
- **事务监控**：长事务可能持有多个配置对象的行锁，建议加 metric `config_package_apply_duration_seconds`，p95 > 5s 报警。

## 前端呈现建议

- 租户配置包页面标题仍保留"租户配置包"。
- 下载模板提示"7 个业务定义 sheet + 3 个可选基础依赖 sheet"。
- preview 结果按数据 sheet 分组，并额外显示"依赖错误"分组和"environment-specific 字段需 review"分组。
- 对 `resource_queue / business_calendar / batch_window` 标记"可选；仅引用时需要"。
- 对 environment-specific 字段（见 §Environment-specific 字段标识表）：preview 时无条件弹 WARNING 分组，列出该字段在源包的值，引导跨环境迁移的用户 review。
- 对 `file_template_config.default_query_sql` 和 `query_param_schema` 提供大文本预览，避免在表格里横向超过。建议复用 workflow designer 的 DSL 编辑器（CodeMirror）做只读高亮。
- **拒绝旧 8-sheet 包上传**：上传时若检测到 `alert_routing_config` sheet 或缺少 `file_template_config` sheet，preview 直接报错 `LEGACY_8_SHEET_FORMAT`，提示用户「请下载当前模板后重新填写；告警路由请走 `/observability/alert-routings` CRUD 页单条维护」。不做容忍式忽略。

## 历史模板体验审计（原模板时点，非当前规格）

以下保留原11-sheet时期模板审计与体验建议，数量、缺口和排期不代表当前状态；当前数据表范围与导出事实见本文前部。

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
| 增加 `依赖说明` sheet | 独立承载跨 sheet 引用关系，避免把依赖说明写入填写说明长文本 |
| `字段说明` 第一列按 sheet 合并 | 降低重复文本，便于快速定位字段组 |
| `字段说明` 末尾加 `关联关系说明` | 对 `queue_code / templateCode / channelCode / job_code / workflow_code` 等引用字段写清目标 |
| `字段说明` 增加 `填写示例` | 区分短示例和可直接参考的真实业务填写片段 |
| 增加 `五类Worker示例` sheet | 展示 IMPORT / EXPORT / PROCESS / DISPATCH / ATOMIC 的最小可运行配置组合，降低新用户理解成本 |
| JSON / SQL 列加宽并启用换行 | `default_params / step_params / config_json / query_param_schema / default_query_sql` 不应只有 18 宽 |
| 为 JSON / SQL 字段加输入提示 | 写明必须是合法 JSON、SQL 仅允许 SELECT |
| 增加 `示例` 或 `样例行` 策略 | 可选做法：模板保持空白，但另导出 example workbook；或者在每个 sheet 第 2 行放示例并标识可删除 |
| `校验` sheet 增加列 | 建议扩展为 `severity / sheet_name / row_no / column_name / error_code / error_reason / reference_target` |
| 辅助 sheet 冻结首行并加筛选 | `字段说明 / 依赖说明 / 校验` 都应该可筛选 |
| sheet tab 颜色分组 | 核心业务 sheet、可选依赖 sheet、辅助说明 sheet 分不同 tab 色 |
| 输出文件去除 macOS/Excel 兼容警告 | 当前用 openpyxl 读取有 “Workbook contains no default style” 警告，建议检查 POI 生成的默认样式/主题，避免部分客户端打开体验差 |

## 实施步骤

按优先级排序，**P0 必须在扩 sheet 之前完成**，否则文档侧旧漂移 + 实现侧新双线漂移叠加。

### P0 — 修当下用户必遇到问题（先做）

1. **字段说明 enum 一致性修正**：按 §当前模板审计与体验优化"必须修正的问题"表逐条修字段说明 sheet 内容。
2. **CI 防漂移单测**：新增 `ConfigPackageFieldDescriptionEnumConsistencyTest`，断言字段说明 sheet 行内容 == 后端 `JobType / ScheduleType / PipelineType / StageCode / RetryPolicyType` 等 enum 集合。文档新加值 / 删除值即编译挂。

### P0 — 抽 common parser/upsert helper（先做）

3. **重构抽离**：把独立 Excel service（`DefaultConsoleFileTemplateExcelApplicationService` / `DefaultConsoleResourceQueueExcelApplicationService` / `DefaultConsoleBusinessCalendarExcelApplicationService` / `DefaultConsoleBatchWindowExcelApplicationService`）里的**行解析 + upsert** 逻辑抽到 `Workbook*RowParser` + `Workbook*UpsertHelper`，独立 service 和 config-package service 各自调同一份。
4. 抽离后立即在独立 Excel service 验证零回归（跑现有 IT），再让 config-package 复用。

### P1 — 扩 sheet 主体

5. 扩展 `ConfigPackageExcelWorkbookWriter`：新增 `file_template_config / resource_queue / business_calendar / batch_window` 数据 sheet（共 11 个数据 sheet）。**移除 `alert_routing_config` sheet 输出**。
6. 新增 `依赖说明` sheet。
7. 改造 `字段说明` sheet：第一列按 sheet 合并单元格，末尾新增 `填写示例` 和 `关联关系说明`；environment-specific 字段加 `[env-specific]` 前缀。
8. 新增 `五类Worker示例` sheet：只做说明，不参与导入解析。
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
- `五类Worker示例` 内容测试 + enum 一致性 CI 断言。
- Import 表名 / Export SQL 随合包导入测试。
- 引用不存在时 preview 报错测试。
- 11 sheet 数据全空（仅表头）的解析测试，确认不抛异常。
- **旧 8-sheet 包上传拒绝测试**：含 `alert_routing_config` 或缺 `file_template_config` 时 preview 报 `LEGACY_8_SHEET_FORMAT`，引导跳转CRUD 页。
- Environment-specific 字段 preview WARNING 测试。

## 当前格式与跨环境迁移

- 完整模板/导出为10数据sheet，辅助sheet另计，场景模板按投影生成；没有租户Pipeline定义数据。
- 上传先做逐行与跨引用校验。旧Pipeline配置行被标记为退役错误，不自动转换；旧FILE_STEP引用与WORKFLOW包装类型不进入新执行图。
- 旧工作簿应由使用方按当前导出模板重建业务配置，不能照搬历史11/12-sheet数量或历史错误码作为当前判断。
- 源环境导出后，对目标队列容量、渠道端点、日历/时区/窗口、业务schema和Secret引用做审核，再preview；错误清除、警告逐项复核后按权限apply。告警路由和其它环境治理仍走独立入口。

### Base Package + Tenant Overlay 轻量方案

- 基础包仍由一个基准租户承载，包含 job / workflow / template / channel / queue / calendar / window 等完整骨架；系统Pipeline不复制成租户定义。
- 租户差异不通过第二份 Excel 表达，而由 `POST /api/console/config/tenant-overlay/preview` 计算：目标租户缺失项为 `ADD`，内容不同为 `UPDATE`，相同项默认不展示，目标租户独有项只作为 `DELETE_CANDIDATE` 提示且不自动删除。
- job 粒度复制和预览使用 dependency-aware bundle：先取 job，再取关联 workflow / queue / window / calendar，并从 job default params、系统阶段业务参数、workflow node params 递归识别 `templateCode` / `fileTemplateCode` / `channelCode` / `fileChannelCode` 等显式引用；无显式引用时才保留旧 `bizType` template/channel 兜底。
- 同一 job 多租户配置漂移由 `POST /api/console/config/tenant-config-matrix` 展示，重点看 schedule、queue、calendar、window、workerGroup、template、channel。
- 边界：不做通用配置中心，不做跨租户自动删除，不把 alert routing / quota / worker registry 重新塞回 Excel。
