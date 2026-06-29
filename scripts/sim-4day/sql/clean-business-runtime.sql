DO $$
DECLARE
  existing_tables text[];
  candidate_tables text[] := ARRAY[
    'biz.customer_account',
    'biz.transaction',
    'biz.risk_score',
    'biz.settlement_batch',
    'biz.settlement_detail',
    'biz.risk_alert',
    'biz.process_order_event',
    'biz.process_account_summary',
    'biz.process_event_copy',
    'batch.process_staging'
  ];
BEGIN
  SELECT array_agg(t.name)
    INTO existing_tables
    FROM unnest(candidate_tables) AS t(name)
   WHERE to_regclass(t.name) IS NOT NULL;

  IF existing_tables IS NOT NULL THEN
    EXECUTE 'TRUNCATE TABLE ' || array_to_string(existing_tables, ', ') || ' RESTART IDENTITY CASCADE';
  END IF;
END $$;
