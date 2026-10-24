-- 表名仅来自探针内固定的三张表；租户是本轮运行时创建的 UUID 身份。
select id, to_jsonb(row_data)::text as snapshot
from %s row_data where tenant_id = :tenantId order by id
