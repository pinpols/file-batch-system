package com.example.batch.orchestrator.domain.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class WorkflowNodeRunEntity {

    private Long id;
    private Long workflowRunId;
    private String nodeCode;
    private String nodeType;
    /**
     * Execution sequence for the same workflow node.
     *
     * <p>This is not a retry counter; it distinguishes multiple runs of the same
     * node under the same workflow instance.
     */
    private Integer runSeq;
    private String nodeStatus;
    /**
     * Retry counter for the node execution lifecycle.
     */
    private Integer retryCount;
    private String errorCode;
    private String errorMessage;
    private Instant startedAt;
    private Instant finishedAt;
    private Long durationMs;
}
