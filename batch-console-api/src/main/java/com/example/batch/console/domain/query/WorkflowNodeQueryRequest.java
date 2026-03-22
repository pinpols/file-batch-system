package com.example.batch.console.domain.query;

import lombok.Data;

@Data
public class WorkflowNodeQueryRequest {

    private String tenantId;
    private Long workflowDefinitionId;
    private String workflowCode;
    private String nodeCode;
    private String nodeType;
    private Boolean enabled;
}
