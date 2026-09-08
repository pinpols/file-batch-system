SELECT format(
    'SELECT count(*) FROM biz.%I WHERE tenant_id = %L',
    :'table_name',
    :'tenant_id'
) \gexec
