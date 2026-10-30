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

DELETE FROM biz.process_stage4_source WHERE tenant_id = 'ta';
DELETE FROM biz.process_stage4_target WHERE tenant_id = 'ta';

INSERT INTO biz.process_stage4_source (
    tenant_id, scenario, account_id, biz_date, event_id, amount
)
SELECT *
FROM (VALUES
    ('ta', 'JSONB',         'A001', :'biz_date'::date, 1, 100.00::numeric),
    ('ta', 'JSONB',         'A001', :'biz_date'::date, 2,  50.00::numeric),
    ('ta', 'JSONB',         'A002', :'biz_date'::date, 3,  25.50::numeric),
    ('ta', 'DIRECT',        'D001', :'biz_date'::date, 4, 200.00::numeric),
    ('ta', 'DIRECT',        'D002', :'biz_date'::date, 5, 300.00::numeric),
    ('ta', 'VALIDATE_FAIL', 'V001', :'biz_date'::date, 6,  10.00::numeric)
) AS v(tenant_id, scenario, account_id, biz_date, event_id, amount);
