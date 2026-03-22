package com.example.batch.console.domain.query;

import lombok.Data;

@Data
public class WorkflowEdgeQueryRequest {

    private String tenantId;
    private Long workflowDefinitionId;
    private String workflowCode;
    private String fromNodeCode;
    private String toNodeCode;
    private String edgeType;
    private Boolean enabled;
}
