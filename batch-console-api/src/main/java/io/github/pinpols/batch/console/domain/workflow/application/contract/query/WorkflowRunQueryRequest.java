package io.github.pinpols.batch.console.domain.workflow.application.contract.query;

import lombok.Data;
import lombok.EqualsAndHashCode;

import io.github.pinpols.batch.console.application.contract.query.PageQueryRequest;

@EqualsAndHashCode(callSuper = false)
@Data
public class WorkflowRunQueryRequest extends PageQueryRequest {

    private String tenantId;
    private Long workflowDefinitionId;
    private String workflowCode;
    private Long relatedJobInstanceId;
    private String runStatus;
    private String currentNodeCode;
    private String traceId;
    private String requestId;
}
