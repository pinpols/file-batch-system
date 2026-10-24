-- 只处理本轮独占租户，解除失败清理时仍在热表中的真实父子链。
update batch.job_instance set parent_instance_id = null where tenant_id = :tenantId
