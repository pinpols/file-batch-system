package com.example.batch.orchestrator.repository;

import com.example.batch.orchestrator.domain.entity.BatchWindowRecord;
import java.util.List;
import org.springframework.data.repository.CrudRepository;

public interface BatchWindowRepository extends CrudRepository<BatchWindowRecord, Long> {

    List<BatchWindowRecord> findByTenantIdAndEnabled(String tenantId, Boolean enabled);
}
