package com.example.batch.orchestrator.domain.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class WorkflowNodeEntity {

    private Long id;
    private Long workflowDefinitionId;
    private String nodeCode;
    private String nodeName;
    private String nodeType;
    private String relatedJobCode;
    /**
     * Workflow-to-pipeline linkage code for node-level orchestration.
     *
     * <p>Do not confuse this with the canonical job code used by
     * pipeline definitions and runtime context.
     */
    private String relatedPipelineCode;
    private String workerGroup;
    private String windowCode;
    private Integer nodeOrder;
    private String retryPolicy;
    private Integer retryMaxCount;
    private Integer timeoutSeconds;
    private String nodeParams;
    private Boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;
}
