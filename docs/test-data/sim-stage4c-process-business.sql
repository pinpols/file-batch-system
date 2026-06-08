-- Stage 4c Process source data for sharded process and cancel profile.
-- Required psql variable: biz_date

CREATE TABLE IF NOT EXISTS biz.process_stage4_source (
    id BIGSERIAL PRIMARY KEY,
    tenant_id TEXT NOT NULL,
    scenario TEXT NOT NULL,
    account_id TEXT NOT NULL,
    biz_date DATE NOT NULL,
    event_id BIGINT NOT NULL,
    amount NUMERIC(18,2) NOT NULL
);

CREATE TABLE IF NOT EXISTS biz.process_stage4_target (
    tenant_id TEXT NOT NULL,
    scenario TEXT NOT NULL,
    account_id TEXT NOT NULL,
    biz_date DATE NOT NULL,
    total_amount NUMERIC(18,2) NOT NULL,
    event_count BIGINT NOT NULL,
    high_water_mark BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, scenario, account_id, biz_date)
);

DELETE FROM biz.process_stage4_source
WHERE tenant_id = 'ta'
  AND scenario IN ('SHARDED', 'CANCEL', 'TIMEOUT');

DELETE FROM biz.process_stage4_target
WHERE tenant_id = 'ta'
  AND scenario IN ('SHARDED', 'CANCEL', 'TIMEOUT');

DELETE FROM batch.process_staging
WHERE tenant_id = 'ta'
  AND target_schema = 'biz'
  AND target_table = 'process_stage4_target'
  AND batch_key LIKE 'sim-process-stage4c-%';

INSERT INTO biz.process_stage4_source (
    tenant_id, scenario, account_id, biz_date, event_id, amount
)
SELECT 'ta',
       'SHARDED',
       'S4C' || lpad(gs::text, 3, '0'),
       :'biz_date'::date,
       gs,
       (10 + gs)::numeric(18, 2)
FROM generate_series(1, 16) AS gs;

INSERT INTO biz.process_stage4_source (
    tenant_id, scenario, account_id, biz_date, event_id, amount
)
VALUES
    ('ta', 'CANCEL', 'S4C-CANCEL', :'biz_date'::date, 9001, 1.00),
    ('ta', 'TIMEOUT', 'S4C-TIMEOUT', :'biz_date'::date, 9002, 1.00);
