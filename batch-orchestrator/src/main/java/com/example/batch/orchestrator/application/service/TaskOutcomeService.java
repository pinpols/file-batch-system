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

    record NodeRunKey(
            Long workflowRunId,
            String nodeCode,
            String nodeType
    ) {
    }

    record NodeRunOutcome(
            boolean success,
            String errorCode,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt
    ) {
    }

    final class NodeRunFinishCommand {

        private final NodeRunKey key;
        private final NodeRunOutcome outcome;

        private NodeRunFinishCommand(NodeRunKey key, NodeRunOutcome outcome) {
            this.key = key;
            this.outcome = outcome;
        }

        public static NodeRunFinishCommand of(NodeRunKey key,
                                              NodeRunOutcome outcome) {
            return new NodeRunFinishCommand(
                    key,
                    outcome
            );
        }

        public static NodeRunFinishCommand success(NodeRunKey key,
                                                   Instant startedAt,
                                                   Instant finishedAt) {
            return of(key, new NodeRunOutcome(true, null, null, startedAt, finishedAt));
        }

        public Long workflowRunId() {
            return key.workflowRunId();
        }

        public String nodeCode() {
            return key.nodeCode();
        }

        public String nodeType() {
            return key.nodeType();
        }

        public boolean success() {
            return outcome.success();
        }

        public String errorCode() {
            return outcome.errorCode();
        }

        public String errorMessage() {
            return outcome.errorMessage();
        }

        public Instant startedAt() {
            return outcome.startedAt();
        }

        public Instant finishedAt() {
            return outcome.finishedAt();
        }
    }

    WorkflowNodeRunEntity recordNodeRunReady(Long workflowRunId, String nodeCode, String nodeType);

    WorkflowNodeRunEntity recordNodeRunStart(Long workflowRunId, String nodeCode, String nodeType, Instant startedAt);

    WorkflowNodeRunEntity recordNodeRunFinish(NodeRunFinishCommand command);

    JobTaskEntity applyTaskOutcome(TaskOutcomeCommand command);
}
