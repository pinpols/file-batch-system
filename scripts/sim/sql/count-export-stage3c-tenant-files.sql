SELECT count(*) FROM (
    SELECT tenant_id, source_ref FROM batch.file_record
    WHERE source_ref = ANY(string_to_array(:'source_refs', ','))
      AND source_type = 'GENERATED' AND file_status = 'GENERATED'
    GROUP BY tenant_id, source_ref
) grouped_files;
