-- =========================================================
-- V2 - Create configuration tables
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
