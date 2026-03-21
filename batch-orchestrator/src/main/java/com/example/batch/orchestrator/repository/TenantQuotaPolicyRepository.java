package com.example.batch.orchestrator.repository;

import com.example.batch.orchestrator.domain.entity.TenantQuotaPolicyRecord;
import java.util.List;
import org.springframework.data.repository.CrudRepository;

public interface TenantQuotaPolicyRepository extends CrudRepository<TenantQuotaPolicyRecord, Long> {

    List<TenantQuotaPolicyRecord> findByTenantIdAndEnabled(String tenantId, Boolean enabled);
}
