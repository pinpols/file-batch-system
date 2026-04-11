package com.example.batch.orchestrator.repository;

import com.example.batch.orchestrator.domain.entity.JobDefinitionRecord;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface JobDefinitionRepository extends CrudRepository<JobDefinitionRecord, Long> {

    List<JobDefinitionRecord> findByTenantIdAndEnabled(String tenantId, Boolean enabled);

    JobDefinitionRecord findFirstByTenantIdAndJobCodeAndEnabled(
            String tenantId, String jobCode, Boolean enabled);
}
