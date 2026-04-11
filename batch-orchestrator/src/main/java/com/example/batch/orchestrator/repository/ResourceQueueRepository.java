package com.example.batch.orchestrator.repository;

import com.example.batch.orchestrator.domain.entity.ResourceQueueRecord;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface ResourceQueueRepository extends CrudRepository<ResourceQueueRecord, Long> {

    List<ResourceQueueRecord> findByTenantIdAndEnabled(String tenantId, Boolean enabled);
}
