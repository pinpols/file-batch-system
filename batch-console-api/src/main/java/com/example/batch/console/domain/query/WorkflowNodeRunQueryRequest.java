package com.example.batch.console.domain.query;

import lombok.Data;

@Data
public class WorkflowNodeRunQueryRequest {

    private Long workflowRunId;
    private String nodeCode;
    private String nodeStatus;
}
