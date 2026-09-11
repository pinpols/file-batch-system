INSERT INTO batch.file_record (
    tenant_id, file_code, biz_type, file_category, file_name, original_file_name,
    file_ext, file_format_type, charset, mime_type, file_size_bytes, checksum_type,
    storage_type, storage_path, source_type, file_status, biz_date, trace_id
) VALUES (
    :'tenant_id', :'file_code', 'OUTPUT', 'OUTPUT', :'file_code' || '.json', :'file_code' || '.json',
    'json', 'JSON', 'UTF-8', 'application/json', 32, 'NONE', 'LOCAL', :'storage_path',
    'SYSTEM', 'GENERATED', :'biz_date'::date, 'sim-bundle-dispatch'
) RETURNING id;
