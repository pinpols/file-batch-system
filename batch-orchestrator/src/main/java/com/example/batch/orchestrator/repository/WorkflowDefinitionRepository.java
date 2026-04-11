package com.example.batch.orchestrator.repository;

import com.example.batch.orchestrator.domain.entity.WorkflowDefinitionRecord;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface WorkflowDefinitionRepository
        extends CrudRepository<WorkflowDefinitionRecord, Long> {

    List<WorkflowDefinitionRecord> findByTenantIdAndEnabled(String tenantId, Boolean enabled);

    WorkflowDefinitionRecord findFirstByTenantIdAndWorkflowCodeAndEnabled(
            String tenantId, String workflowCode, Boolean enabled);
}
