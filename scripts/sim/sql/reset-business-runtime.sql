SELECT format('TRUNCATE TABLE %s CASCADE', to_regclass(btrim(table_name)))
FROM unnest(string_to_array(:'reset_tables', ',')) AS tables(table_name)
WHERE to_regclass(btrim(table_name)) IS NOT NULL
\gexec
