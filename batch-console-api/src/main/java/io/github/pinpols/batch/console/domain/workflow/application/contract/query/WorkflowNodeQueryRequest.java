package io.github.pinpols.batch.console.domain.workflow.application.contract.query;

import lombok.Data;
import lombok.EqualsAndHashCode;

import io.github.pinpols.batch.console.application.contract.query.PageQueryRequest;

@EqualsAndHashCode(callSuper = false)
@Data
public class WorkflowNodeQueryRequest extends PageQueryRequest {

    private String tenantId;
    private Long workflowDefinitionId;
    private String workflowCode;
    private String nodeCode;
    private String nodeType;
    private Boolean enabled;
}
