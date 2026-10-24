-- 表名是探针内固定 allowlist；不得用作任意租户清库接口。
delete from %s where tenant_id = :tenantId
