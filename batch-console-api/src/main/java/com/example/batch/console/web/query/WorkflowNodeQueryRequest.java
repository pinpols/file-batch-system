package com.example.batch.console.web.query;

import lombok.Data;

@Data
public class WorkflowNodeQueryRequest extends PageQueryRequest {

    private String tenantId;
    private Long workflowDefinitionId;
    private String workflowCode;
    private String nodeCode;
    private String nodeType;
    private Boolean enabled;
}
