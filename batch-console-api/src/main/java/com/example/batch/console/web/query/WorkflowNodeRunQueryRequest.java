package com.example.batch.console.web.query;

import lombok.Data;

@Data
public class WorkflowNodeRunQueryRequest extends PageQueryRequest {

    private Long workflowRunId;
    private String nodeCode;
    private String nodeStatus;
}
