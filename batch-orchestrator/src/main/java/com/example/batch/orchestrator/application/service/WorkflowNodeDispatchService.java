package com.example.batch.orchestrator.application.service;

import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;

public interface WorkflowNodeDispatchService {

    int dispatchNode(JobInstanceEntity jobInstance,
                     WorkflowRunEntity workflowRun,
                     WorkflowDagService.DagNodeResolution node,
                     String sourcePayload,
                     String traceId);
}
