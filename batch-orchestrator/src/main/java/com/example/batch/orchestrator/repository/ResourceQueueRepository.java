package com.example.batch.orchestrator.repository;

import com.example.batch.orchestrator.domain.entity.ResourceQueueRecord;
import java.util.List;
import org.springframework.data.repository.CrudRepository;

public interface ResourceQueueRepository extends CrudRepository<ResourceQueueRecord, Long> {

    List<ResourceQueueRecord> findByTenantIdAndEnabled(String tenantId, Boolean enabled);
}
