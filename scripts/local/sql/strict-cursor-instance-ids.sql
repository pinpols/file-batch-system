SELECT string_agg(id::text, ',' ORDER BY id DESC)
FROM (
    SELECT id FROM batch.job_instance
    WHERE tenant_id = :'tenant_id'
      AND (nullif(:'last_id', '') IS NULL OR id < nullif(:'last_id', '')::bigint)
    ORDER BY id DESC
    LIMIT :'page_size'::integer
) instances;
