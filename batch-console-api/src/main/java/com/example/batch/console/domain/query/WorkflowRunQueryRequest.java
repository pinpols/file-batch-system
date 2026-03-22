package com.example.batch.console.domain.query;

import lombok.Data;

@Data
public class WorkflowRunQueryRequest {

    private String tenantId;
    private Long workflowDefinitionId;
    private Long relatedJobInstanceId;
    private String runStatus;
    private String currentNodeCode;
    private String traceId;
}
