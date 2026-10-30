-- 用途：查询 SIM 分片导出生成文件的对象存储位置。
-- 用法：由 Stage 3b/3c 传入 tenant_id 与 source_ref，供产物内容核对。
SELECT coalesce(
    json_agg(json_build_object('bucket', storage_bucket, 'path', storage_path) ORDER BY file_name),
    '[]'::json
)::text
FROM batch.file_record
WHERE tenant_id = :'tenant_id'
  AND source_ref = :'source_ref'
  AND source_type = 'GENERATED';
