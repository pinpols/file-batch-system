package com.example.batch.console.domain.query;

import lombok.Data;

@Data
public class WorkflowTopologyQueryRequest {

    private String tenantId;
    private String workflowCode;
    private Integer version;
    private Long workflowRunId;
}
