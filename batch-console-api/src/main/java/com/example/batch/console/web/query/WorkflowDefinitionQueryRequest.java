package com.example.batch.console.web.query;

import lombok.Data;

@Data
public class WorkflowDefinitionQueryRequest extends PageQueryRequest {

    private String tenantId;
    private String workflowCode;
    private String workflowName;
    private String workflowType;
    private Integer version;
    private Boolean enabled = true;
}
