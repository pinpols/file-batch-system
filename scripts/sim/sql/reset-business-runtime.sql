DO $$
BEGIN
    IF current_setting('batch.destructive_ops', true) IS DISTINCT FROM 'sim-reset' THEN
        RAISE EXCEPTION '拒绝重置业务运行态:需通过本地 sim reset 入口显式授权';
    END IF;
END
$$;

SELECT format('TRUNCATE TABLE %s CASCADE', to_regclass(btrim(table_name)))
FROM unnest(string_to_array(:'reset_tables', ',')) AS tables(table_name)
WHERE to_regclass(btrim(table_name)) IS NOT NULL
\gexec
