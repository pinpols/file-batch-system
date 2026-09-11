SELECT tenant_id, source_ref, file_format_type, file_status, count(*) AS files,
       coalesce(sum((metadata_json->>'recordCount')::int), 0) AS rows
FROM batch.file_record
WHERE source_ref = ANY(string_to_array(:'source_refs', ',')) AND source_type = 'GENERATED'
GROUP BY tenant_id, source_ref, file_format_type, file_status
ORDER BY tenant_id, source_ref, file_format_type;
