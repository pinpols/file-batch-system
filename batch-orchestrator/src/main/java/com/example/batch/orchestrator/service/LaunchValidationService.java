package com.example.batch.orchestrator.service;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.orchestrator.domain.entity.JobDefinitionRecord;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowDefinitionRecord;
import com.example.batch.common.persistence.entity.TriggerRequestEntity;

/**
 * Validates a launch request and loads the required definitions.
 * All operations are read-only; no transaction required.
 * Extracted from {@link DefaultLaunchService} to isolate validation concerns.
 */
public interface LaunchValidationService {

    /**
     * Validates the request fields (throws BizException on failure),
     * loads the trigger request entity, loads job definition and workflow definition
     * (updates trigger request to REJECTED and throws if not found),
     * and checks for an existing dedup instance.
     *
     * @return a {@link LaunchLoadResult} holding the loaded data
     */
    LaunchLoadResult load(LaunchRequest request);

    record LaunchLoadResult(
            TriggerRequestEntity triggerRequest,
            JobDefinitionRecord jobDefinition,
            WorkflowDefinitionRecord workflowDefinition,
            JobInstanceEntity existingInstance  // non-null means duplicate
    ) {}
}
