-- V182: Restore global idempotency keys weakened by monthly partitioning.
--
-- V172/V173 had to include the partition key in UNIQUE constraints, which made
-- outbox_event and job_instance idempotency per-partition. These compact ledger
-- tables hold the original global keys and are written in the same transaction
-- as the business row via INSERT ... ON CONFLICT DO NOTHING.

CREATE TABLE IF NOT EXISTS batch.outbox_event_dedup_key (
    tenant_id    VARCHAR(64)  NOT NULL,
    event_key    VARCHAR(256) NOT NULL,
    first_event_id BIGINT,
    first_created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, event_key)
);

INSERT INTO batch.outbox_event_dedup_key (
    tenant_id, event_key, first_event_id, first_created_at, created_at
)
SELECT tenant_id, event_key, id, created_at, current_timestamp
FROM (
    SELECT
        tenant_id,
        event_key,
        id,
        created_at,
        row_number() OVER (
            PARTITION BY tenant_id, event_key
            ORDER BY created_at ASC, id ASC
        ) AS rn
    FROM batch.outbox_event
) s
WHERE rn = 1
ON CONFLICT (tenant_id, event_key) DO NOTHING;

CREATE TABLE IF NOT EXISTS batch.job_instance_dedup_key (
    tenant_id      VARCHAR(64)  NOT NULL,
    dedup_key      VARCHAR(256) NOT NULL,
    run_attempt    INTEGER      NOT NULL,
    first_instance_id BIGINT,
    first_biz_date DATE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, dedup_key, run_attempt)
);

INSERT INTO batch.job_instance_dedup_key (
    tenant_id, dedup_key, run_attempt, first_instance_id, first_biz_date, created_at
)
SELECT tenant_id, dedup_key, run_attempt, id, biz_date, current_timestamp
FROM (
    SELECT
        tenant_id,
        dedup_key,
        run_attempt,
        id,
        biz_date,
        row_number() OVER (
            PARTITION BY tenant_id, dedup_key, run_attempt
            ORDER BY biz_date ASC, id ASC
        ) AS rn
    FROM batch.job_instance
) s
WHERE rn = 1
ON CONFLICT (tenant_id, dedup_key, run_attempt) DO NOTHING;
