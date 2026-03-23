package com.example.batch.console.web.query;

import lombok.Data;

@Data
public class WorkflowTopologyQueryRequest {

    private String tenantId;
    private String workflowCode;
    private Integer version;
    private Long workflowRunId;
}
