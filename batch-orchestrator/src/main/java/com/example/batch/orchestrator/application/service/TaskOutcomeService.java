package com.example.batch.orchestrator.application.service;

import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import java.time.Instant;

/**
 * Handles task completion reporting and workflow node lifecycle tracking.
 * Extracted from {@link DefaultTaskExecutionService} to isolate the high-complexity
 * outcome processing logic (retry scheduling, partition/instance progress, DAG continuation).
 */
public interface TaskOutcomeService {

    WorkflowNodeRunEntity recordNodeRunReady(Long workflowRunId, String nodeCode, String nodeType);

    WorkflowNodeRunEntity recordNodeRunStart(Long workflowRunId, String nodeCode, String nodeType, Instant startedAt);

    WorkflowNodeRunEntity recordNodeRunFinish(Long workflowRunId,
                                              String nodeCode,
                                              String nodeType,
                                              boolean success,
                                              String errorCode,
                                              String errorMessage,
                                              Instant startedAt,
                                              Instant finishedAt);

    JobTaskEntity applyTaskOutcome(TaskOutcomeCommand command);
}
