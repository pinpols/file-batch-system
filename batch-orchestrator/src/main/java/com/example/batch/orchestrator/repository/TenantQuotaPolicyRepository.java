package com.example.batch.orchestrator.repository;

import com.example.batch.orchestrator.domain.entity.TenantQuotaPolicyRecord;
import java.util.List;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

public interface TenantQuotaPolicyRepository extends CrudRepository<TenantQuotaPolicyRecord, Long> {

    List<TenantQuotaPolicyRecord> findByTenantIdAndEnabled(String tenantId, Boolean enabled);

    @Query("select distinct tenant_id from batch.tenant_quota_policy where enabled = true")
    List<String> findDistinctTenantIdsEnabled();
}
