package com.example.batch.console.domain.query;

import lombok.Data;

@Data
public class WorkflowDefinitionQueryRequest {

    private String tenantId;
    private String workflowCode;
    private String workflowType;
    private Integer version;
    private Boolean enabled;
}
