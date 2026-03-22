# Flyway 首版脚本说明

## 范围

本轮脚本只覆盖平台库 `batch_platform` 的 PostgreSQL 首版结构，不覆盖业务库 `batch_business`。

原因：

- 设计文档 14 章给出的 DDL 范围是平台调度、编排、文件资产、审计、补偿与一致性模型
- 导入/导出目标业务表仍取决于具体业务字段模型，当前文档未给出可执行 DDL

## Schema 规划

- `batch`
  - 平台业务 schema
  - 包含配置态、定义态、运行态、文件态、审计态、补偿态表
- `quartz`
  - Quartz JDBC JobStore 元数据 schema
  - 只允许放 Quartz 官方 `QRTZ_*` 表
  - 不承载任何平台业务表

## 表清单

### 配置态

- `batch.resource_queue`
- `batch.tenant_quota_policy`
- `batch.batch_window`
- `batch.business_calendar`
- `batch.calendar_holiday`
- `batch.worker_registry`

### 定义态

- `batch.job_definition`
- `batch.workflow_definition`
- `batch.workflow_node`
- `batch.workflow_edge`

### 运行态

- `batch.trigger_request`
- `batch.job_instance`
- `batch.job_partition`
- `batch.job_task`
- `batch.workflow_run`
- `batch.workflow_node_run`

### 文件态

- `batch.file_record`
- `batch.pipeline_definition`
- `batch.pipeline_step_definition`
- `batch.pipeline_instance`
- `batch.pipeline_step_run`
- `batch.file_channel_config`
- `batch.file_template_config`
- `batch.file_dispatch_record`
- `batch.file_audit_log`

### 运维与一致性

- `batch.job_execution_log`
- `batch.retry_schedule`
- `batch.dead_letter_task`
- `batch.outbox_event`

## 建模假设

1. 当前采用单库多 schema，平台表全部落在 `batch_platform` 数据库中。
2. `tenant_id` 作为多租户隔离键，当前不对接外部租户主数据表，因此不加外键。
3. 所有主键统一采用 `BIGSERIAL`，便于首版快速落地；后续如需雪花 ID 或序列段，可平滑替换。
4. 可变结构参数统一使用 `JSONB`，包括参数快照、模板配置、事件载荷、执行摘要。
5. 运行态一致性采用“先落库，后 outbox，异步投递 MQ”的模式，MQ 不是唯一事实来源。
6. `workflow_edge` 当前仍按 `workflow_definition_id + node_code` 建模，不在本轮补复合外键，保持与设计文档一致。
7. 文件资产、模板和链路实例都归平台统一治理，因此落在 `batch` schema，不进入 `batch_business`。

## 审计字段规范

### 全局约定

- 业务主表默认携带 `tenant_id`
- 主数据/定义/配置类表默认携带 `created_at`、`updated_at`
- 运行态表默认携带 `created_at`、`updated_at`，并按需要补 `started_at`、`finished_at`
- 审计与日志类表至少携带 `created_at`
- 需要追踪创建/修改操作者的定义态表补 `created_by`、`updated_by`

### 具体口径

- 定义态：`tenant_id + created_at + updated_at + created_by + updated_by`
- 配置态：`tenant_id + created_at + updated_at`
- 运行态：`tenant_id + created_at + updated_at + started_at/finished_at`
- 审计态：`tenant_id + created_at + trace_id`

## Quartz 边界

- 本轮 Flyway 只创建 `quartz` schema，不直接生成 `QRTZ_*` 表
- Quartz 表应使用官方 PostgreSQL JDBC JobStore 脚本初始化，并显式落到 `quartz` schema
- `batch` schema 中任何业务表都不得引用 `quartz.QRTZ_*`
- Quartz 只负责触发元数据，`trigger_request`、`job_instance` 等平台业务状态一律保留在 `batch`

## 脚本拆分

- `V1__create_schema.sql`
- `V2__create_config_tables.sql`
- `V3__create_definition_tables.sql`
- `V4__create_runtime_tables.sql`
- `V5__create_file_tables.sql`
- `V6__create_ops_tables.sql`
- `V7__create_indexes.sql`
- `V8`～`V10`：阶段扩展、文件错误表、AI 审计等（见目录内文件名）
- `V11__add_sla_and_arrival_governance.sql`
- `V12__create_compensation_and_step_runtime.sql`
- `V13__add_chunk_size_to_file_template_config.sql`
- `V14__add_import_preprocess_pipeline.sql`
- `V15__scheduler_fair_share_snapshot_load.sql`
- `V16__compensation_running_target_unique.sql`
- `V17__file_template_security_flags.sql`
- `V18__alert_event.sql`
- `V19__worker_registry_drain.sql`
- `V20__create_event_outbox_logs.sql`（由原重复 `V11` 重编号）
- `V21__create_config_release_and_secret_version.sql`（由原重复 `V12` 重编号）
- `V22__file_channel_type_api_push.sql`（由原重复 `V14` 重编号）
- `V23__batch_runtime_default_parameter.sql`：默认运行参数目录表与种子数据

参数说明见 [runtime-default-parameters.md](../architecture/runtime-default-parameters.md)。
