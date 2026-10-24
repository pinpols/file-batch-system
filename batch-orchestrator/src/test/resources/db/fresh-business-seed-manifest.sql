WITH seed_rows AS (
    SELECT
        'biz.customer_account'::text AS table_name,
        a.tenant_id || '/' || a.customer_no AS natural_key,
        to_jsonb(a) AS row_values
    FROM biz.customer_account a

    UNION ALL

    SELECT
        'biz.settlement_batch',
        b.tenant_id || '/' || b.batch_no,
        to_jsonb(b)
    FROM biz.settlement_batch b

    UNION ALL

    SELECT
        'biz.settlement_detail',
        d.tenant_id || '/' || d.settlement_no,
        to_jsonb(d)
    FROM biz.settlement_detail d
)
SELECT jsonb_build_array(table_name, natural_key, row_values)::text
FROM seed_rows
ORDER BY table_name, natural_key;
