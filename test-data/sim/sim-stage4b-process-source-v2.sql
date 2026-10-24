-- Stage 4b Process 源数据,幂等重跑更新。
-- 必需的 psql 变量:biz_date

DELETE FROM biz.process_stage4_source
WHERE tenant_id = 'ta'
  AND scenario = 'JSONB'
  AND biz_date = :'biz_date'::date;

INSERT INTO biz.process_stage4_source (
    tenant_id, scenario, account_id, biz_date, event_id, amount
)
VALUES
    ('ta', 'JSONB', 'A001', :'biz_date'::date, 201, 300.00),
    ('ta', 'JSONB', 'A002', :'biz_date'::date, 202,  40.00),
    ('ta', 'JSONB', 'A002', :'biz_date'::date, 203,  60.00);
