## 14. 数据模型与 PostgreSQL 表结构设计
### 14.1 设计目标与落地原则

本章将前文中的任务调度、DAG 编排、文件链路、资源调度、补偿与审计模型统一落成 **可执行 DDL 终版**。目标不是停留在“表清单 + 字段建议”，而是直接给出可用于 Flyway 写入数据库的 PostgreSQL 基线脚本。

**本章落地原则**：

- 采用 `batch` 与 `quartz` 分 schema，业务运行态与 Quartz 元数据严格隔离
- 配置态、运行态、审计态、补偿态分表设计，不混用职责
- 所有业务主表默认携带 `tenant_id`、`created_at`、`updated_at`
- 所有高频运行表补齐唯一约束、检查约束、时间索引、状态索引
- 一致性按“DB 先写入数据库 + Outbox + MQ 至少一次 + Worker 幂等”建模
- 全文统一使用 `job_partition`，不再使用 `task_partition`
- 文件链路补齐版本字段、分发表、审计表、模板表和运行态表，形成完整闭环

### 14.2 逻辑分层与核心表清单

建议采用单库多 schema：

- `batch`：业务配置、运行态、审计、补偿、文件资产
- `quartz`：Quartz JDBC JobStore 元数据表

本章 DDL 终版覆盖以下表：

| 分层 | 表名 |
|---|---|
| 任务定义 | `job_definition` |
| 任务运行 | `job_instance`、`job_partition`、`job_task`、`job_execution_log` |
| 流程定义 | `workflow_definition`、`workflow_node`、`workflow_edge` |
| 流程运行 | `workflow_run`、`workflow_node_run` |
| 资源调度 | `resource_queue`、`tenant_quota_policy`、`worker_registry`、`batch_window` |
| 日历 | `business_calendar`、`calendar_holiday` |
| 触发与一致性 | `trigger_request`、`outbox_event` |
| 重试与死信 | `retry_schedule`、`dead_letter_task` |
| 文件资产 | `file_record`、`file_dispatch_record`、`file_audit_log` |
| 文件链路 | `pipeline_definition`、`pipeline_step_definition`、`pipeline_instance`、`pipeline_step_run` |
| 文件配置 | `file_channel_config`、`file_template_config` |

### 14.3 DDL 使用说明

建议按 Flyway 迁移顺序拆分：

- `V1__create_schema.sql`
- `V2__create_config_tables.sql`
- `V3__create_runtime_tables.sql`
- `V4__create_file_tables.sql`
- `V5__create_ops_tables.sql`
- `V6__create_indexes.sql`

下面给出的 SQL 已按“**可直接执行**”标准整理。若生产环境采用分区表、逻辑复制或对象存储扩展，可在此基线上继续演进。

### 14.4 PostgreSQL 可执行 DDL 终版

```sql
-- =========================================================
-- 批量调度系统 PostgreSQL DDL 终版
-- 说明：
-- 1) 建议使用 PostgreSQL 14+。
-- 2) Quartz 自身 qrtz_* 表建议使用官方脚本初始化到 quartz schema。
-- 3) 本脚本聚焦 batch schema 业务表。
-- =========================================================

CREATE SCHEMA IF NOT EXISTS batch;
CREATE SCHEMA IF NOT EXISTS quartz;

-- =========================================================
-- 1. 资源调度与基础配置表
-- =========================================================

CREATE TABLE IF NOT EXISTS batch.resource_queue (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    queue_code               VARCHAR(128) NOT NULL,
    queue_name               VARCHAR(256) NOT NULL,
    queue_type               VARCHAR(32)  NOT NULL,
    max_running_jobs         INTEGER      NOT NULL DEFAULT 0,
    max_running_partitions   INTEGER      NOT NULL DEFAULT 0,
    max_qps                  INTEGER      NOT NULL DEFAULT 0,
    worker_group             VARCHAR(128),
    resource_tag             VARCHAR(64),
    priority_policy          VARCHAR(32)  NOT NULL DEFAULT 'FIFO',
    fair_share_weight        INTEGER      NOT NULL DEFAULT 1,
    enabled                  BOOLEAN      NOT NULL DEFAULT TRUE,
    description              VARCHAR(512),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_resource_queue_tenant_code UNIQUE (tenant_id, queue_code),
    CONSTRAINT ck_resource_queue_type CHECK (queue_type IN ('IMPORT', 'EXPORT', 'DISPATCH', 'MIXED')),
    CONSTRAINT ck_resource_queue_priority_policy CHECK (priority_policy IN ('FIFO', 'PRIORITY', 'FAIR_SHARE')),
    CONSTRAINT ck_resource_queue_max_running_jobs CHECK (max_running_jobs >= 0),
    CONSTRAINT ck_resource_queue_max_running_partitions CHECK (max_running_partitions >= 0),
    CONSTRAINT ck_resource_queue_max_qps CHECK (max_qps >= 0),
    CONSTRAINT ck_resource_queue_fair_share_weight CHECK (fair_share_weight > 0)
);

CREATE INDEX IF NOT EXISTS idx_resource_queue_type_enabled
    ON batch.resource_queue (queue_type, enabled);
CREATE INDEX IF NOT EXISTS idx_resource_queue_worker_group
    ON batch.resource_queue (worker_group);
CREATE INDEX IF NOT EXISTS idx_resource_queue_resource_tag
    ON batch.resource_queue (resource_tag);

CREATE TABLE IF NOT EXISTS batch.tenant_quota_policy (
    id                             BIGSERIAL PRIMARY KEY,
    tenant_id                      VARCHAR(64) NOT NULL,
    policy_code                    VARCHAR(128) NOT NULL,
    max_running_jobs_per_tenant    INTEGER NOT NULL DEFAULT 0,
    max_partitions_per_tenant      INTEGER NOT NULL DEFAULT 0,
    max_qps_per_tenant             INTEGER NOT NULL DEFAULT 0,
    fair_share_weight              INTEGER NOT NULL DEFAULT 1,
    enabled                        BOOLEAN NOT NULL DEFAULT TRUE,
    description                    VARCHAR(512),
    created_at                     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_tenant_quota_policy UNIQUE (tenant_id, policy_code),
    CONSTRAINT ck_tenant_quota_jobs CHECK (max_running_jobs_per_tenant >= 0),
    CONSTRAINT ck_tenant_quota_partitions CHECK (max_partitions_per_tenant >= 0),
    CONSTRAINT ck_tenant_quota_qps CHECK (max_qps_per_tenant >= 0),
    CONSTRAINT ck_tenant_quota_weight CHECK (fair_share_weight > 0)
);

CREATE INDEX IF NOT EXISTS idx_tenant_quota_enabled
    ON batch.tenant_quota_policy (tenant_id, enabled);

CREATE TABLE IF NOT EXISTS batch.batch_window (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    window_code              VARCHAR(128) NOT NULL,
    window_name              VARCHAR(256) NOT NULL,
    timezone                 VARCHAR(64)  NOT NULL,
    start_time               TIME         NOT NULL,
    end_time                 TIME         NOT NULL,
    end_strategy             VARCHAR(32)  NOT NULL DEFAULT 'FINISH_RUNNING',
    out_of_window_action     VARCHAR(32)  NOT NULL DEFAULT 'WAIT',
    allow_cross_day          BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled                  BOOLEAN      NOT NULL DEFAULT TRUE,
    description              VARCHAR(512),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_batch_window_tenant_code UNIQUE (tenant_id, window_code),
    CONSTRAINT ck_batch_window_end_strategy CHECK (end_strategy IN ('STOP', 'FINISH_RUNNING', 'CONTINUE')),
    CONSTRAINT ck_batch_window_action CHECK (out_of_window_action IN ('WAIT', 'FAIL'))
);

CREATE INDEX IF NOT EXISTS idx_batch_window_enabled
    ON batch.batch_window (tenant_id, enabled);

CREATE TABLE IF NOT EXISTS batch.business_calendar (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    calendar_code            VARCHAR(128) NOT NULL,
    calendar_name            VARCHAR(256) NOT NULL,
    timezone                 VARCHAR(64)  NOT NULL,
    holiday_roll_rule        VARCHAR(32)  NOT NULL DEFAULT 'SKIP',
    catch_up_policy          VARCHAR(32)  NOT NULL DEFAULT 'NONE',
    catch_up_max_days        INTEGER      NOT NULL DEFAULT 0,
    enabled                  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_business_calendar_tenant_code UNIQUE (tenant_id, calendar_code),
    CONSTRAINT ck_business_calendar_roll_rule CHECK (holiday_roll_rule IN ('SKIP', 'NEXT_WORKDAY', 'PREV_WORKDAY')),
    CONSTRAINT ck_business_calendar_catchup_policy CHECK (catch_up_policy IN ('NONE', 'AUTO', 'MANUAL_APPROVAL')),
    CONSTRAINT ck_business_calendar_catchup_days CHECK (catch_up_max_days >= 0)
);

CREATE INDEX IF NOT EXISTS idx_business_calendar_enabled
    ON batch.business_calendar (tenant_id, enabled);

CREATE TABLE IF NOT EXISTS batch.calendar_holiday (
    id                       BIGSERIAL PRIMARY KEY,
    calendar_id              BIGINT       NOT NULL REFERENCES batch.business_calendar(id),
    biz_date                 DATE         NOT NULL,
    day_type                 VARCHAR(32)  NOT NULL,
    holiday_name             VARCHAR(128),
    description              VARCHAR(512),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_calendar_holiday UNIQUE (calendar_id, biz_date),
    CONSTRAINT ck_calendar_holiday_day_type CHECK (day_type IN ('HOLIDAY', 'WORKDAY_OVERRIDE'))
);

CREATE INDEX IF NOT EXISTS idx_calendar_holiday_biz_date
    ON batch.calendar_holiday (biz_date, day_type);

CREATE TABLE IF NOT EXISTS batch.worker_registry (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    worker_code              VARCHAR(128) NOT NULL,
    worker_group             VARCHAR(128) NOT NULL,
    host_name                VARCHAR(256),
    host_ip                  VARCHAR(64),
    process_id               VARCHAR(64),
    capability_tags          JSONB,
    resource_tag             VARCHAR(64),
    status                   VARCHAR(32)  NOT NULL DEFAULT 'ONLINE',
    heartbeat_at             TIMESTAMPTZ  NOT NULL,
    last_start_at            TIMESTAMPTZ,
    version                  VARCHAR(64),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_worker_registry_tenant_worker UNIQUE (tenant_id, worker_code),
    CONSTRAINT ck_worker_registry_status CHECK (status IN ('ONLINE', 'OFFLINE', 'DRAINING', 'DECOMMISSIONED'))
);

CREATE INDEX IF NOT EXISTS idx_worker_registry_group_status
    ON batch.worker_registry (worker_group, status);
CREATE INDEX IF NOT EXISTS idx_worker_registry_heartbeat_at
    ON batch.worker_registry (heartbeat_at);

-- =========================================================
-- 2. 任务定义与流程定义表
-- =========================================================

CREATE TABLE IF NOT EXISTS batch.job_definition (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    job_code                 VARCHAR(128) NOT NULL,
    job_name                 VARCHAR(256) NOT NULL,
    job_type                 VARCHAR(32)  NOT NULL,
    biz_type                 VARCHAR(64),
    schedule_type            VARCHAR(32)  NOT NULL,
    schedule_expr            VARCHAR(256),
    timezone                 VARCHAR(64)  NOT NULL,
    priority                 INTEGER      NOT NULL DEFAULT 5,
    queue_code               VARCHAR(128),
    worker_group             VARCHAR(128),
    calendar_code            VARCHAR(128),
    window_code              VARCHAR(128),
    trigger_mode             VARCHAR(32)  NOT NULL DEFAULT 'SCHEDULED',
    dag_enabled              BOOLEAN      NOT NULL DEFAULT FALSE,
    shard_strategy           VARCHAR(32)  NOT NULL DEFAULT 'NONE',
    retry_policy             VARCHAR(32)  NOT NULL DEFAULT 'NONE',
    retry_max_count          INTEGER      NOT NULL DEFAULT 0,
    timeout_seconds          INTEGER      NOT NULL DEFAULT 0,
    execution_handler        VARCHAR(256),
    param_schema             JSONB,
    default_params           JSONB,
    version                  INTEGER      NOT NULL DEFAULT 1,
    enabled                  BOOLEAN      NOT NULL DEFAULT TRUE,
    description              VARCHAR(1024),
    created_by               VARCHAR(64),
    updated_by               VARCHAR(64),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_job_definition_tenant_code UNIQUE (tenant_id, job_code),
    CONSTRAINT ck_job_definition_job_type CHECK (job_type IN ('GENERAL', 'IMPORT', 'EXPORT', 'DISPATCH', 'WORKFLOW')),
    CONSTRAINT ck_job_definition_schedule_type CHECK (schedule_type IN ('CRON', 'FIXED_RATE', 'MANUAL', 'EVENT', 'ONE_TIME')),
    CONSTRAINT ck_job_definition_trigger_mode CHECK (trigger_mode IN ('SCHEDULED', 'API', 'MANUAL', 'EVENT', 'MIXED')),
    CONSTRAINT ck_job_definition_shard_strategy CHECK (shard_strategy IN ('NONE', 'STATIC', 'DYNAMIC', 'AUTO')),
    CONSTRAINT ck_job_definition_retry_policy CHECK (retry_policy IN ('NONE', 'FIXED', 'EXPONENTIAL')),
    CONSTRAINT ck_job_definition_retry_max_count CHECK (retry_max_count >= 0),
    CONSTRAINT ck_job_definition_timeout_seconds CHECK (timeout_seconds >= 0),
    CONSTRAINT ck_job_definition_priority CHECK (priority BETWEEN 1 AND 9)
);

CREATE INDEX IF NOT EXISTS idx_job_definition_enabled
    ON batch.job_definition (tenant_id, enabled);
CREATE INDEX IF NOT EXISTS idx_job_definition_queue
    ON batch.job_definition (tenant_id, queue_code, enabled);
CREATE INDEX IF NOT EXISTS idx_job_definition_worker_group
    ON batch.job_definition (worker_group, enabled);

CREATE TABLE IF NOT EXISTS batch.workflow_definition (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    workflow_code            VARCHAR(128) NOT NULL,
    workflow_name            VARCHAR(256) NOT NULL,
    workflow_type            VARCHAR(32)  NOT NULL DEFAULT 'DAG',
    version                  INTEGER      NOT NULL DEFAULT 1,
    enabled                  BOOLEAN      NOT NULL DEFAULT TRUE,
    description              VARCHAR(1024),
    created_by               VARCHAR(64),
    updated_by               VARCHAR(64),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_workflow_definition_tenant_code_version UNIQUE (tenant_id, workflow_code, version),
    CONSTRAINT ck_workflow_definition_type CHECK (workflow_type IN ('DAG', 'PIPELINE', 'MIXED'))
);

CREATE INDEX IF NOT EXISTS idx_workflow_definition_enabled
    ON batch.workflow_definition (tenant_id, enabled);

CREATE TABLE IF NOT EXISTS batch.workflow_node (
    id                       BIGSERIAL PRIMARY KEY,
    workflow_definition_id   BIGINT       NOT NULL REFERENCES batch.workflow_definition(id),
    node_code                VARCHAR(128) NOT NULL,
    node_name                VARCHAR(256) NOT NULL,
    node_type                VARCHAR(32)  NOT NULL,
    related_job_code         VARCHAR(128),
    related_pipeline_code    VARCHAR(128),
    worker_group             VARCHAR(128),
    window_code              VARCHAR(128),
    node_order               INTEGER      NOT NULL DEFAULT 0,
    retry_policy             VARCHAR(32)  NOT NULL DEFAULT 'NONE',
    retry_max_count          INTEGER      NOT NULL DEFAULT 0,
    timeout_seconds          INTEGER      NOT NULL DEFAULT 0,
    node_params              JSONB,
    enabled                  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_workflow_node_def_code UNIQUE (workflow_definition_id, node_code),
    CONSTRAINT ck_workflow_node_type CHECK (node_type IN ('TASK', 'GATEWAY', 'FILE_STEP', 'START', 'END')),
    CONSTRAINT ck_workflow_node_retry_policy CHECK (retry_policy IN ('NONE', 'FIXED', 'EXPONENTIAL')),
    CONSTRAINT ck_workflow_node_retry_max_count CHECK (retry_max_count >= 0),
    CONSTRAINT ck_workflow_node_timeout_seconds CHECK (timeout_seconds >= 0),
    CONSTRAINT ck_workflow_node_order CHECK (node_order >= 0)
);

> `related_pipeline_code` is a node-level linkage field for workflow topology. It is not the same concept as the canonical `job_code` used by pipeline definitions and runtime context.

CREATE INDEX IF NOT EXISTS idx_workflow_node_type_enabled
    ON batch.workflow_node (node_type, enabled);

CREATE TABLE IF NOT EXISTS batch.workflow_edge (
    id                       BIGSERIAL PRIMARY KEY,
    workflow_definition_id   BIGINT       NOT NULL REFERENCES batch.workflow_definition(id),
    from_node_code           VARCHAR(128) NOT NULL,
    to_node_code             VARCHAR(128) NOT NULL,
    edge_type                VARCHAR(32)  NOT NULL DEFAULT 'SUCCESS',
    condition_expr           VARCHAR(1024),
    enabled                  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_workflow_edge UNIQUE (workflow_definition_id, from_node_code, to_node_code, edge_type),
    CONSTRAINT ck_workflow_edge_type CHECK (edge_type IN ('SUCCESS', 'FAILURE', 'CONDITION', 'ALWAYS'))
);

CREATE INDEX IF NOT EXISTS idx_workflow_edge_from
    ON batch.workflow_edge (workflow_definition_id, from_node_code);
CREATE INDEX IF NOT EXISTS idx_workflow_edge_to
    ON batch.workflow_edge (workflow_definition_id, to_node_code);

-- =========================================================
-- 3. 任务运行态与流程运行态表
-- =========================================================

CREATE TABLE IF NOT EXISTS batch.trigger_request (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    request_id               VARCHAR(128) NOT NULL,
    trigger_type             VARCHAR(32)  NOT NULL,
    job_code                 VARCHAR(128) NOT NULL,
    biz_date                 DATE,
    dedup_key                VARCHAR(256) NOT NULL,
    request_payload_hash     VARCHAR(128),
    request_status           VARCHAR(32)  NOT NULL,
    related_job_instance_id  BIGINT,
    trace_id                 VARCHAR(128),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_trigger_request_tenant_request UNIQUE (tenant_id, request_id),
    CONSTRAINT uk_trigger_request_tenant_dedup UNIQUE (tenant_id, dedup_key),
    CONSTRAINT ck_trigger_request_type CHECK (trigger_type IN ('API', 'MANUAL', 'EVENT', 'CATCH_UP', 'SCHEDULED')),
    CONSTRAINT ck_trigger_request_status CHECK (request_status IN ('ACCEPTED', 'DUPLICATE', 'REJECTED', 'LAUNCHED'))
);

CREATE INDEX IF NOT EXISTS idx_trigger_request_job_biz_date
    ON batch.trigger_request (tenant_id, job_code, biz_date);
CREATE INDEX IF NOT EXISTS idx_trigger_request_created_at
    ON batch.trigger_request (created_at);

CREATE TABLE IF NOT EXISTS batch.job_instance (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    job_definition_id        BIGINT       NOT NULL REFERENCES batch.job_definition(id),
    trigger_request_id       BIGINT       REFERENCES batch.trigger_request(id),
    job_code                 VARCHAR(128) NOT NULL,
    instance_no              VARCHAR(128) NOT NULL,
    biz_date                 DATE,
    trigger_type             VARCHAR(32)  NOT NULL,
    instance_status          VARCHAR(32)  NOT NULL,
    queue_code               VARCHAR(128),
    worker_group             VARCHAR(128),
    priority                 INTEGER      NOT NULL DEFAULT 5,
    dedup_key                VARCHAR(256) NOT NULL,
    version                  BIGINT       NOT NULL DEFAULT 0,
    expected_partition_count INTEGER      NOT NULL DEFAULT 0,
    success_partition_count  INTEGER      NOT NULL DEFAULT 0,
    failed_partition_count   INTEGER      NOT NULL DEFAULT 0,
    trace_id                 VARCHAR(128),
    params_snapshot          JSONB,
    started_at               TIMESTAMPTZ,
    finished_at              TIMESTAMPTZ,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_job_instance_tenant_instance_no UNIQUE (tenant_id, instance_no),
    CONSTRAINT uk_job_instance_tenant_dedup UNIQUE (tenant_id, dedup_key),
    CONSTRAINT ck_job_instance_trigger_type CHECK (trigger_type IN ('SCHEDULED', 'API', 'MANUAL', 'EVENT', 'CATCH_UP')),
    CONSTRAINT ck_job_instance_status CHECK (instance_status IN ('CREATED', 'WAITING', 'READY', 'RUNNING', 'PARTIAL_FAILED', 'SUCCESS', 'FAILED', 'CANCELLED', 'TERMINATED')),
    CONSTRAINT ck_job_instance_priority CHECK (priority BETWEEN 1 AND 9),
    CONSTRAINT ck_job_instance_expected_partition_count CHECK (expected_partition_count >= 0),
    CONSTRAINT ck_job_instance_success_partition_count CHECK (success_partition_count >= 0),
    CONSTRAINT ck_job_instance_failed_partition_count CHECK (failed_partition_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_job_instance_job_status
    ON batch.job_instance (tenant_id, job_code, instance_status);
CREATE INDEX IF NOT EXISTS idx_job_instance_biz_date
    ON batch.job_instance (tenant_id, biz_date, instance_status);
CREATE INDEX IF NOT EXISTS idx_job_instance_created_at
    ON batch.job_instance (created_at);
CREATE INDEX IF NOT EXISTS idx_job_instance_trace_id
    ON batch.job_instance (trace_id);

CREATE TABLE IF NOT EXISTS batch.job_partition (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    job_instance_id          BIGINT       NOT NULL REFERENCES batch.job_instance(id),
    partition_no             INTEGER      NOT NULL,
    partition_key            VARCHAR(256),
    partition_status         VARCHAR(32)  NOT NULL,
    worker_group             VARCHAR(128),
    worker_code              VARCHAR(128),
    lease_expire_at          TIMESTAMPTZ,
    retry_count              INTEGER      NOT NULL DEFAULT 0,
    business_key             VARCHAR(256),
    idempotency_key          VARCHAR(512),
    input_snapshot           JSONB,
    output_summary           JSONB,
    started_at               TIMESTAMPTZ,
    finished_at              TIMESTAMPTZ,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_job_partition_instance_no UNIQUE (job_instance_id, partition_no),
    CONSTRAINT uk_job_partition_idempotency_key UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT ck_job_partition_status CHECK (partition_status IN ('CREATED', 'WAITING', 'READY', 'RUNNING', 'SUCCESS', 'FAILED', 'RETRYING', 'CANCELLED', 'TERMINATED')),
    CONSTRAINT ck_job_partition_no CHECK (partition_no >= 0),
    CONSTRAINT ck_job_partition_retry_count CHECK (retry_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_job_partition_status_worker_group
    ON batch.job_partition (partition_status, worker_group);
CREATE INDEX IF NOT EXISTS idx_job_partition_lease_expire_at
    ON batch.job_partition (lease_expire_at);
CREATE INDEX IF NOT EXISTS idx_job_partition_business_key
    ON batch.job_partition (business_key);

CREATE TABLE IF NOT EXISTS batch.job_task (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    job_instance_id          BIGINT       NOT NULL REFERENCES batch.job_instance(id),
    job_partition_id         BIGINT       REFERENCES batch.job_partition(id),
    task_type                VARCHAR(32)  NOT NULL DEFAULT 'EXECUTION',
    task_seq                 INTEGER      NOT NULL DEFAULT 1,
    task_status              VARCHAR(32)  NOT NULL,
    assigned_worker_code     VARCHAR(128),
    task_payload             JSONB,
    result_summary           JSONB,
    error_code               VARCHAR(64),
    error_message            VARCHAR(2048),
    started_at               TIMESTAMPTZ,
    finished_at              TIMESTAMPTZ,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_job_task_partition_seq UNIQUE (job_partition_id, task_seq),
    CONSTRAINT ck_job_task_type CHECK (task_type IN ('EXECUTION', 'COMPENSATION', 'REPLAY')),
    CONSTRAINT ck_job_task_status CHECK (task_status IN ('CREATED', 'READY', 'RUNNING', 'SUCCESS', 'FAILED', 'CANCELLED', 'TERMINATED')),
    CONSTRAINT ck_job_task_seq CHECK (task_seq > 0)
);

CREATE INDEX IF NOT EXISTS idx_job_task_status_worker
    ON batch.job_task (task_status, assigned_worker_code);
CREATE INDEX IF NOT EXISTS idx_job_task_instance
    ON batch.job_task (job_instance_id, created_at);

CREATE TABLE IF NOT EXISTS batch.workflow_run (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    workflow_definition_id   BIGINT       NOT NULL REFERENCES batch.workflow_definition(id),
    related_job_instance_id  BIGINT       REFERENCES batch.job_instance(id),
    biz_date                 DATE,
    run_status               VARCHAR(32)  NOT NULL,
    current_node_code        VARCHAR(128),
    trace_id                 VARCHAR(128),
    started_at               TIMESTAMPTZ,
    finished_at              TIMESTAMPTZ,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_workflow_run_status CHECK (run_status IN ('CREATED', 'RUNNING', 'SUCCESS', 'FAILED', 'TERMINATED'))
);

CREATE INDEX IF NOT EXISTS idx_workflow_run_status
    ON batch.workflow_run (tenant_id, run_status, started_at);
CREATE INDEX IF NOT EXISTS idx_workflow_run_job_instance
    ON batch.workflow_run (related_job_instance_id);

CREATE TABLE IF NOT EXISTS batch.workflow_node_run (
    id                       BIGSERIAL PRIMARY KEY,
    workflow_run_id          BIGINT       NOT NULL REFERENCES batch.workflow_run(id),
    node_code                VARCHAR(128) NOT NULL,
    node_type                VARCHAR(32)  NOT NULL,
    run_seq                  INTEGER      NOT NULL DEFAULT 1,
    node_status              VARCHAR(32)  NOT NULL,
    retry_count              INTEGER      NOT NULL DEFAULT 0,
    error_code               VARCHAR(64),
    error_message            VARCHAR(1024),
    started_at               TIMESTAMPTZ,
    finished_at              TIMESTAMPTZ,
    duration_ms              BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_workflow_node_run UNIQUE (workflow_run_id, node_code, run_seq),
    CONSTRAINT ck_workflow_node_run_type CHECK (node_type IN ('TASK', 'GATEWAY', 'FILE_STEP', 'START', 'END')),
    CONSTRAINT ck_workflow_node_run_status CHECK (node_status IN ('READY', 'RUNNING', 'SUCCESS', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_workflow_node_run_retry CHECK (retry_count >= 0),
    CONSTRAINT ck_workflow_node_run_duration CHECK (duration_ms >= 0)
);

CREATE INDEX IF NOT EXISTS idx_workflow_node_run_status_started
    ON batch.workflow_node_run (node_status, started_at);

-- =========================================================
-- 4. 文件资产、链路模板与链路运行表
-- =========================================================

CREATE TABLE IF NOT EXISTS batch.file_record (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    file_code                VARCHAR(128),
    biz_type                 VARCHAR(64),
    file_category            VARCHAR(32)  NOT NULL,
    file_name                VARCHAR(512) NOT NULL,
    original_file_name       VARCHAR(512),
    file_ext                 VARCHAR(32),
    file_format_type         VARCHAR(32)  NOT NULL,
    charset                  VARCHAR(32),
    mime_type                VARCHAR(128),
    file_size_bytes          BIGINT       NOT NULL DEFAULT 0,
    checksum_type            VARCHAR(32)  NOT NULL DEFAULT 'NONE',
    checksum_value           VARCHAR(256),
    storage_type             VARCHAR(32)  NOT NULL,
    storage_path             VARCHAR(1024) NOT NULL,
    storage_bucket           VARCHAR(256),
    file_version             VARCHAR(64),
    file_generation_no       INTEGER      NOT NULL DEFAULT 1,
    is_latest                BOOLEAN      NOT NULL DEFAULT TRUE,
    source_type              VARCHAR(32)  NOT NULL,
    source_ref               VARCHAR(256),
    file_status              VARCHAR(32)  NOT NULL,
    biz_date                 DATE,
    trace_id                 VARCHAR(128),
    metadata_json            JSONB,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_file_record_tenant_checksum_path UNIQUE (tenant_id, checksum_value, storage_path),
    CONSTRAINT ck_file_record_category CHECK (file_category IN ('INPUT', 'OUTPUT', 'INTERMEDIATE', 'ARCHIVE')),
    CONSTRAINT ck_file_record_format CHECK (file_format_type IN ('DELIMITED', 'FIXED_WIDTH', 'EXCEL', 'XML', 'JSON', 'BINARY')),
    CONSTRAINT ck_file_record_checksum_type CHECK (checksum_type IN ('NONE', 'MD5', 'SHA-1', 'SHA-256')),
    CONSTRAINT ck_file_record_storage_type CHECK (storage_type IN ('LOCAL', 'NAS', 'S3', 'OSS', 'HDFS', 'DB_BLOB')),
    CONSTRAINT ck_file_record_source_type CHECK (source_type IN ('UPLOAD', 'SFTP', 'API', 'GENERATED', 'SYSTEM')),
    CONSTRAINT ck_file_record_status CHECK (file_status IN ('RECEIVED', 'PARSING', 'PARSED', 'VALIDATED', 'LOADED', 'GENERATED', 'DISPATCHING', 'DISPATCHED', 'ARCHIVED', 'FAILED', 'DELETED')),
    CONSTRAINT ck_file_record_file_size CHECK (file_size_bytes >= 0),
    CONSTRAINT ck_file_record_generation_no CHECK (file_generation_no > 0)
);

CREATE INDEX IF NOT EXISTS idx_file_record_biz_type_status
    ON batch.file_record (tenant_id, biz_type, file_status);
CREATE INDEX IF NOT EXISTS idx_file_record_biz_date
    ON batch.file_record (tenant_id, biz_date, created_at);
CREATE INDEX IF NOT EXISTS idx_file_record_latest
    ON batch.file_record (tenant_id, is_latest, file_name);
CREATE INDEX IF NOT EXISTS idx_file_record_trace_id
    ON batch.file_record (trace_id);

CREATE TABLE IF NOT EXISTS batch.pipeline_definition (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    job_code                 VARCHAR(128) NOT NULL,
    pipeline_name            VARCHAR(256) NOT NULL,
    pipeline_type            VARCHAR(32)  NOT NULL,
    biz_type                 VARCHAR(64),
    worker_group             VARCHAR(128),
    version                  INTEGER      NOT NULL DEFAULT 1,
    enabled                  BOOLEAN      NOT NULL DEFAULT TRUE,
    description              VARCHAR(512),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_pipeline_definition_tenant_code_version UNIQUE (tenant_id, job_code, version),
    CONSTRAINT ck_pipeline_definition_type CHECK (pipeline_type IN ('IMPORT', 'EXPORT', 'DISPATCH')),
    CONSTRAINT ck_pipeline_definition_version CHECK (version > 0)
);

CREATE INDEX IF NOT EXISTS idx_pipeline_definition_type_enabled
    ON batch.pipeline_definition (tenant_id, pipeline_type, enabled);

CREATE TABLE IF NOT EXISTS batch.pipeline_step_definition (
    id                       BIGSERIAL PRIMARY KEY,
    pipeline_definition_id   BIGINT       NOT NULL REFERENCES batch.pipeline_definition(id),
    step_code                VARCHAR(128) NOT NULL,
    step_name                VARCHAR(256) NOT NULL,
    stage_code               VARCHAR(64)  NOT NULL,
    step_order               INTEGER      NOT NULL,
    impl_code                VARCHAR(128) NOT NULL,
    step_params              JSONB,
    timeout_seconds          INTEGER      NOT NULL DEFAULT 0,
    retry_policy             VARCHAR(32)  NOT NULL DEFAULT 'NONE',
    retry_max_count          INTEGER      NOT NULL DEFAULT 0,
    enabled                  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_pipeline_step_definition UNIQUE (pipeline_definition_id, step_code),
    CONSTRAINT ck_pipeline_step_stage CHECK (stage_code IN ('RECEIVE', 'PREPROCESS', 'PARSE', 'VALIDATE', 'LOAD', 'GENERATE', 'TRANSFER', 'DISPATCH', 'ACK')),
    CONSTRAINT ck_pipeline_step_order CHECK (step_order >= 0),
    CONSTRAINT ck_pipeline_step_timeout CHECK (timeout_seconds >= 0),
    CONSTRAINT ck_pipeline_step_retry_policy CHECK (retry_policy IN ('NONE', 'FIXED', 'EXPONENTIAL')),
    CONSTRAINT ck_pipeline_step_retry_max_count CHECK (retry_max_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_pipeline_step_definition_order
    ON batch.pipeline_step_definition (pipeline_definition_id, step_order);

CREATE TABLE IF NOT EXISTS batch.pipeline_instance (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    pipeline_definition_id   BIGINT       NOT NULL REFERENCES batch.pipeline_definition(id),
    job_code                 VARCHAR(128) NOT NULL,
    pipeline_type            VARCHAR(32)  NOT NULL,
    file_id                  BIGINT       REFERENCES batch.file_record(id),
    related_job_instance_id  BIGINT       REFERENCES batch.job_instance(id),
    current_stage            VARCHAR(64),
    last_success_stage       VARCHAR(64),
    run_status               VARCHAR(32)  NOT NULL,
    trace_id                 VARCHAR(128),
    started_at               TIMESTAMPTZ,
    finished_at              TIMESTAMPTZ,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_pipeline_instance_type CHECK (pipeline_type IN ('IMPORT', 'EXPORT', 'DISPATCH')),
    CONSTRAINT ck_pipeline_instance_status CHECK (run_status IN ('CREATED', 'RUNNING', 'SUCCESS', 'FAILED', 'COMPENSATING', 'TERMINATED'))
);

CREATE INDEX IF NOT EXISTS idx_pipeline_instance_type_status
    ON batch.pipeline_instance (tenant_id, pipeline_type, run_status);
CREATE INDEX IF NOT EXISTS idx_pipeline_instance_file_id
    ON batch.pipeline_instance (file_id);
CREATE INDEX IF NOT EXISTS idx_pipeline_instance_trace_id
    ON batch.pipeline_instance (trace_id);

CREATE TABLE IF NOT EXISTS batch.pipeline_step_run (
    id                       BIGSERIAL PRIMARY KEY,
    pipeline_instance_id     BIGINT       NOT NULL REFERENCES batch.pipeline_instance(id),
    step_code                VARCHAR(128) NOT NULL,
    stage_code               VARCHAR(64)  NOT NULL,
    run_seq                  INTEGER      NOT NULL DEFAULT 1,
    step_status              VARCHAR(32)  NOT NULL,
    input_summary            JSONB,
    output_summary           JSONB,
    error_code               VARCHAR(64),
    error_message            VARCHAR(1024),
    retry_count              INTEGER      NOT NULL DEFAULT 0,
    duration_ms              BIGINT       NOT NULL DEFAULT 0,
    started_at               TIMESTAMPTZ,
    finished_at              TIMESTAMPTZ,
    CONSTRAINT uk_pipeline_step_run UNIQUE (pipeline_instance_id, step_code, run_seq),
    CONSTRAINT ck_pipeline_step_run_status CHECK (step_status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'RETRYING', 'SKIPPED')),
    CONSTRAINT ck_pipeline_step_run_retry_count CHECK (retry_count >= 0),
    CONSTRAINT ck_pipeline_step_run_duration CHECK (duration_ms >= 0)
);

CREATE INDEX IF NOT EXISTS idx_pipeline_step_run_status_started
    ON batch.pipeline_step_run (step_status, started_at);

CREATE TABLE IF NOT EXISTS batch.file_channel_config (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    channel_code             VARCHAR(128) NOT NULL,
    channel_name             VARCHAR(256) NOT NULL,
    channel_type             VARCHAR(32)  NOT NULL,
    target_endpoint          VARCHAR(1024),
    auth_type                VARCHAR(32)  NOT NULL DEFAULT 'NONE',
    config_json              JSONB        NOT NULL,
    receipt_policy           VARCHAR(32)  NOT NULL DEFAULT 'NONE',
    timeout_seconds          INTEGER      NOT NULL DEFAULT 0,
    enabled                  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_file_channel_config UNIQUE (tenant_id, channel_code),
    CONSTRAINT ck_file_channel_type CHECK (channel_type IN ('SFTP', 'API', 'EMAIL', 'NAS', 'OSS', 'LOCAL')),
    CONSTRAINT ck_file_channel_auth_type CHECK (auth_type IN ('NONE', 'PASSWORD', 'KEY_PAIR', 'TOKEN', 'OAUTH2', 'CUSTOM')),
    CONSTRAINT ck_file_channel_receipt_policy CHECK (receipt_policy IN ('NONE', 'SYNC', 'ASYNC', 'POLLING')),
    CONSTRAINT ck_file_channel_timeout CHECK (timeout_seconds >= 0)
);

CREATE INDEX IF NOT EXISTS idx_file_channel_enabled
    ON batch.file_channel_config (tenant_id, channel_type, enabled);

CREATE TABLE IF NOT EXISTS batch.file_template_config (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    template_code            VARCHAR(128) NOT NULL,
    template_name            VARCHAR(256) NOT NULL,
    template_type            VARCHAR(32)  NOT NULL,
    biz_type                 VARCHAR(64),
    file_format_type         VARCHAR(32)  NOT NULL,
    charset                  VARCHAR(32),
    target_charset           VARCHAR(32),
    with_bom                 BOOLEAN      NOT NULL DEFAULT FALSE,
    line_separator           VARCHAR(16),
    delimiter                VARCHAR(8),
    quote_char               VARCHAR(8),
    escape_char              VARCHAR(8),
    record_length            INTEGER      NOT NULL DEFAULT 0,
    header_rows              INTEGER      NOT NULL DEFAULT 0,
    footer_rows              INTEGER      NOT NULL DEFAULT 0,
    header_template          JSONB,
    trailer_template         JSONB,
    checksum_type            VARCHAR(32)  NOT NULL DEFAULT 'NONE',
    compress_type            VARCHAR(32)  NOT NULL DEFAULT 'NONE',
    encrypt_type             VARCHAR(32)  NOT NULL DEFAULT 'NONE',
    naming_rule              VARCHAR(512),
    field_mappings           JSONB,
    validation_rule_set      JSONB,
    default_query_code       VARCHAR(128),
    default_query_sql        TEXT,
    query_param_schema       JSONB,
    streaming_enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    page_size                INTEGER      NOT NULL DEFAULT 1000,
    fetch_size               INTEGER      NOT NULL DEFAULT 1000,
    enabled                  BOOLEAN      NOT NULL DEFAULT TRUE,
    version                  INTEGER      NOT NULL DEFAULT 1,
    description              VARCHAR(1024),
    created_by               VARCHAR(64),
    updated_by               VARCHAR(64),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_file_template_tenant_code_version UNIQUE (tenant_id, template_code, version),
    CONSTRAINT ck_file_template_type CHECK (template_type IN ('IMPORT', 'EXPORT', 'SHARED')),
    CONSTRAINT ck_file_template_format CHECK (file_format_type IN ('DELIMITED', 'FIXED_WIDTH', 'EXCEL', 'XML', 'JSON', 'BINARY')),
    CONSTRAINT ck_file_template_checksum_type CHECK (checksum_type IN ('NONE', 'MD5', 'SHA-256')),
    CONSTRAINT ck_file_template_compress_type CHECK (compress_type IN ('NONE', 'ZIP', 'GZIP')),
    CONSTRAINT ck_file_template_encrypt_type CHECK (encrypt_type IN ('NONE', 'AES', 'PGP', 'CUSTOM')),
    CONSTRAINT ck_file_template_record_length CHECK (record_length >= 0),
    CONSTRAINT ck_file_template_header_rows CHECK (header_rows >= 0),
    CONSTRAINT ck_file_template_footer_rows CHECK (footer_rows >= 0),
    CONSTRAINT ck_file_template_page_size CHECK (page_size > 0),
    CONSTRAINT ck_file_template_fetch_size CHECK (fetch_size > 0),
    CONSTRAINT ck_file_template_version CHECK (version > 0)
);

CREATE INDEX IF NOT EXISTS idx_file_template_type_biz
    ON batch.file_template_config (tenant_id, template_type, biz_type);
CREATE INDEX IF NOT EXISTS idx_file_template_enabled_updated
    ON batch.file_template_config (enabled, updated_at);

CREATE TABLE IF NOT EXISTS batch.file_dispatch_record (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    file_id                  BIGINT       NOT NULL REFERENCES batch.file_record(id),
    pipeline_instance_id     BIGINT       REFERENCES batch.pipeline_instance(id),
    channel_code             VARCHAR(128) NOT NULL,
    dispatch_target          VARCHAR(256),
    dispatch_status          VARCHAR(32)  NOT NULL,
    dispatch_attempt         INTEGER      NOT NULL DEFAULT 1,
    receipt_code             VARCHAR(128),
    receipt_status           VARCHAR(32)  NOT NULL DEFAULT 'NONE',
    external_request_id      VARCHAR(128),
    error_code               VARCHAR(64),
    error_message            VARCHAR(1024),
    dispatched_at            TIMESTAMPTZ,
    ack_at                   TIMESTAMPTZ,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_file_dispatch_status CHECK (dispatch_status IN ('CREATED', 'SENT', 'ACKED', 'FAILED', 'COMPENSATED')),
    CONSTRAINT ck_file_dispatch_attempt CHECK (dispatch_attempt > 0),
    CONSTRAINT ck_file_dispatch_receipt_status CHECK (receipt_status IN ('NONE', 'PENDING', 'SUCCESS', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_file_dispatch_status
    ON batch.file_dispatch_record (tenant_id, dispatch_status, dispatched_at);
CREATE INDEX IF NOT EXISTS idx_file_dispatch_external_request
    ON batch.file_dispatch_record (external_request_id);

CREATE TABLE IF NOT EXISTS batch.file_audit_log (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    file_id                  BIGINT       NOT NULL REFERENCES batch.file_record(id),
    operation_type           VARCHAR(64)  NOT NULL,
    operation_result         VARCHAR(32)  NOT NULL,
    operator_type            VARCHAR(32)  NOT NULL,
    operator_id              VARCHAR(64),
    trace_id                 VARCHAR(128),
    evidence_ref             VARCHAR(512),
    detail_summary           JSONB,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_file_audit_result CHECK (operation_result IN ('SUCCESS', 'FAILED')),
    CONSTRAINT ck_file_audit_operator_type CHECK (operator_type IN ('SYSTEM', 'USER', 'API'))
);

CREATE INDEX IF NOT EXISTS idx_file_audit_operation
    ON batch.file_audit_log (tenant_id, operation_type, created_at);
CREATE INDEX IF NOT EXISTS idx_file_audit_trace_id
    ON batch.file_audit_log (trace_id);

-- =========================================================
-- 5. 日志、重试、死信与一致性表
-- =========================================================

CREATE TABLE IF NOT EXISTS batch.job_execution_log (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    job_instance_id          BIGINT       REFERENCES batch.job_instance(id),
    job_partition_id         BIGINT       REFERENCES batch.job_partition(id),
    log_level                VARCHAR(16)  NOT NULL,
    log_type                 VARCHAR(32)  NOT NULL,
    trace_id                 VARCHAR(128),
    message                  VARCHAR(2048) NOT NULL,
    detail_ref               VARCHAR(512),
    extra_json               JSONB,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_job_execution_log_level CHECK (log_level IN ('DEBUG', 'INFO', 'WARN', 'ERROR')),
    CONSTRAINT ck_job_execution_log_type CHECK (log_type IN ('SYSTEM', 'BUSINESS', 'RETRY', 'ALARM', 'AUDIT'))
);

CREATE INDEX IF NOT EXISTS idx_job_execution_log_instance_created
    ON batch.job_execution_log (job_instance_id, created_at);
CREATE INDEX IF NOT EXISTS idx_job_execution_log_partition_created
    ON batch.job_execution_log (job_partition_id, created_at);
CREATE INDEX IF NOT EXISTS idx_job_execution_log_trace_id
    ON batch.job_execution_log (trace_id);

CREATE TABLE IF NOT EXISTS batch.retry_schedule (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    related_type             VARCHAR(32)  NOT NULL,
    related_id               BIGINT       NOT NULL,
    retry_policy             VARCHAR(32)  NOT NULL,
    retry_count              INTEGER      NOT NULL DEFAULT 0,
    max_retry_count          INTEGER      NOT NULL DEFAULT 0,
    next_retry_at            TIMESTAMPTZ  NOT NULL,
    retry_status             VARCHAR(32)  NOT NULL,
    dedup_key                VARCHAR(256) NOT NULL,
    last_error_code          VARCHAR(64),
    last_error_message       VARCHAR(1024),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_retry_schedule_tenant_dedup UNIQUE (tenant_id, dedup_key),
    CONSTRAINT ck_retry_schedule_related_type CHECK (related_type IN ('JOB_INSTANCE', 'JOB_PARTITION', 'JOB_TASK', 'PIPELINE_INSTANCE', 'FILE_DISPATCH')),
    CONSTRAINT ck_retry_schedule_policy CHECK (retry_policy IN ('FIXED', 'EXPONENTIAL')),
    CONSTRAINT ck_retry_schedule_status CHECK (retry_status IN ('WAITING', 'RUNNING', 'SUCCESS', 'FAILED', 'EXHAUSTED', 'CANCELLED')),
    CONSTRAINT ck_retry_schedule_retry_count CHECK (retry_count >= 0),
    CONSTRAINT ck_retry_schedule_max_retry_count CHECK (max_retry_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_retry_schedule_next_retry
    ON batch.retry_schedule (retry_status, next_retry_at);

CREATE TABLE IF NOT EXISTS batch.dead_letter_task (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    source_type              VARCHAR(32)  NOT NULL,
    source_id                BIGINT       NOT NULL,
    dead_letter_reason       VARCHAR(1024),
    payload_ref              VARCHAR(512),
    replay_status            VARCHAR(32)  NOT NULL DEFAULT 'NEW',
    replay_count             INTEGER      NOT NULL DEFAULT 0,
    last_replay_at           TIMESTAMPTZ,
    last_replay_result       VARCHAR(32),
    trace_id                 VARCHAR(128),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_dead_letter_source_type CHECK (source_type IN ('JOB_INSTANCE', 'JOB_PARTITION', 'JOB_TASK', 'PIPELINE_INSTANCE', 'FILE_DISPATCH', 'MQ_MESSAGE')),
    CONSTRAINT ck_dead_letter_replay_status CHECK (replay_status IN ('NEW', 'REPLAYING', 'SUCCESS', 'FAILED', 'GIVE_UP')),
    CONSTRAINT ck_dead_letter_replay_count CHECK (replay_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_dead_letter_replay_status
    ON batch.dead_letter_task (replay_status, created_at);
CREATE INDEX IF NOT EXISTS idx_dead_letter_trace_id
    ON batch.dead_letter_task (trace_id);

CREATE TABLE IF NOT EXISTS batch.outbox_event (
    id                       BIGSERIAL PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL,
    aggregate_type           VARCHAR(64)  NOT NULL,
    aggregate_id             BIGINT       NOT NULL,
    event_type               VARCHAR(64)  NOT NULL,
    event_key                VARCHAR(256) NOT NULL,
    payload_json             JSONB        NOT NULL,
    publish_status           VARCHAR(32)  NOT NULL DEFAULT 'NEW',
    publish_attempt          INTEGER      NOT NULL DEFAULT 0,
    next_publish_at          TIMESTAMPTZ,
    trace_id                 VARCHAR(128),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_outbox_event_key UNIQUE (tenant_id, event_key),
    CONSTRAINT ck_outbox_publish_status CHECK (publish_status IN ('NEW', 'PUBLISHING', 'PUBLISHED', 'FAILED', 'GIVE_UP')),
    CONSTRAINT ck_outbox_publish_attempt CHECK (publish_attempt >= 0)
);

CREATE INDEX IF NOT EXISTS idx_outbox_publish_status
    ON batch.outbox_event (publish_status, next_publish_at);
CREATE INDEX IF NOT EXISTS idx_outbox_aggregate
    ON batch.outbox_event (aggregate_type, aggregate_id);
```

### 14.5 关键落地说明

#### 一致性与幂等边界

本版 DDL 已把一致性模型所需的关键表全部补齐：

- `trigger_request`：外部触发幂等入口
- `job_instance.dedup_key`：实例级去重
- `job_partition.idempotency_key`：分片级幂等键
- `retry_schedule`：统一重试计划
- `dead_letter_task`：失败终态回退
- `outbox_event`：DB 与 MQ 的最终一致性桥梁

建议统一口径：

- Orchestrator：先写 `job_instance / job_partition / outbox_event`，再异步投递 MQ
- MQ：至少一次投递
- Worker：必须保证业务幂等，不依赖中间件 exactly-once
- 状态推进：通过 `version`、唯一索引和条件更新控制并发写入

#### 文件版本与分发闭环

本版 DDL 已将文件版本模型正式落地到 `file_record`：

- `file_version`
- `file_generation_no`
- `is_latest`

并通过以下表完成文件闭环：

- `pipeline_instance / pipeline_step_run`：链路运行过程
- `file_dispatch_record`：分发、回执、补发、补偿
- `file_audit_log`：关键动作审计与证据定位

#### 多租户公平调度

资源调度不再只停留在 `priority + queue + worker_group`，本版增加：

- `resource_queue.fair_share_weight`
- `tenant_quota_policy.max_running_jobs_per_tenant`
- `tenant_quota_policy.max_partitions_per_tenant`
- `tenant_quota_policy.max_qps_per_tenant`

这使得资源调度章节中的 `Tenant Quota / Fair Share` 模型可以直接写入数据库实现。

#### Batch Window 与 DAG 冲突处理

窗口控制与 DAG 推进在表结构层通过以下字段落地：

- `job_definition.window_code`
- `workflow_node.window_code`
- `batch_window.out_of_window_action`
- `batch_window.end_strategy`

执行建议：

- 节点级 `window_code` 优先于任务级 `window_code`
- 不在窗口内时按 `WAIT / FAIL` 执行
- 窗口结束后按 `STOP / FINISH_RUNNING / CONTINUE` 处理

### 14.6 Flyway 写入数据库建议

建议按以下顺序建表：

1. schema：`batch`、`quartz`
2. 配置表：`resource_queue`、`tenant_quota_policy`、`batch_window`、`business_calendar`、`worker_registry`
3. 定义表：`job_definition`、`workflow_definition`、`workflow_node`、`workflow_edge`
4. 运行表：`trigger_request`、`job_instance`、`job_partition`、`job_task`、`workflow_run`、`workflow_node_run`
5. 文件表：`file_record`、`pipeline_definition`、`pipeline_step_definition`、`pipeline_instance`、`pipeline_step_run`、`file_channel_config`、`file_template_config`、`file_dispatch_record`、`file_audit_log`
6. 运维表：`job_execution_log`、`retry_schedule`、`dead_letter_task`、`outbox_event`
7. 最后执行索引优化、分区扩展、归档策略

### 14.7 术语统一与表结构交叉引用校验结论

本轮校验后，统一采用以下口径：

- 运行态主对象统一使用 `job_instance / job_partition / job_task`，不再使用 `task_partition` 等旧称谓。
- 事件最终一致性桥梁统一使用 `outbox_event`，不再混用 `event_outbox`。
- `job_instance` 与 `job_partition` 的主状态值统一严格以 14 章 DDL 为准；等待类细分语义通过 `WAITING` 结合调度原因、租约字段和审计日志表达。
- 文件资产主表统一使用 `file_record`，图示与正文不再引用未写入数据库的 `file_receive_record`。

本章交叉引用校验结论如下：

- 14 章关键落地说明中引用的 `trigger_request`、`workflow_run`、`workflow_node_run`、`job_partition`、`pipeline_step_run`、`retry_schedule`、`dead_letter_task`、`outbox_event` 均已在本章 DDL 中有对应定义。
- `Tenant Quota / Fair Share` 在 DDL 中由 `resource_queue` 与 `tenant_quota_policy` 承接，和 8 章资源调度口径一致。
- 文件版本模型由 `file_record.file_version / file_generation_no / is_latest` 承接，和 9、10、14 章口径一致。

仍建议在实施阶段补两条数据库级增强约束：

- `workflow_edge.from_node_code / to_node_code` 当前基于节点编码建模，建议后续补充到 `workflow_node(workflow_definition_id, node_code)` 的复合引用约束，进一步收紧 DAG 引用完整性。
- 若后续要求“同一逻辑文件多版本并存”成为硬需求，建议把 `file_record` 的版本唯一性从文档口径继续下沉为更明确的逻辑文件键 + 版本号约束。

### 14.8 本章结论

至此，14 章已从“表结构设计说明”升级为“**可执行 DDL 终版**”。

这一版解决了此前评审中最关键的落地缺口：

- 补齐了流程运行态表
- 补齐了文件分发与审计闭环
- 补齐了资源公平调度与租户配额模型
- 补齐了触发幂等、分片幂等、重试、死信和 Outbox 一致性模型
- 统一了 `job_partition` 命名，消除了 `task_partition` 混用问题

后续如果进入实施阶段，建议直接基于本章拆分 Flyway SQL 脚本，并在开发联调阶段再补两类增强能力：

- 大表按月分区或冷热分层
- 对象存储、审计中心、告警平台的外部集成脚本


