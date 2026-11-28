package io.github.pinpols.batch.console.domain.workflow.application.contract.query;

import lombok.Data;
import lombok.EqualsAndHashCode;

import io.github.pinpols.batch.console.application.contract.query.PageQueryRequest;

@EqualsAndHashCode(callSuper = false)
@Data
public class WorkflowNodeRunQueryRequest extends PageQueryRequest {

    /** 租户 ID；由 ConsoleTenantGuard 在 service 层 resolve 后传入 mapper，强制隔离。 */
    private String tenantId;

    private Long workflowRunId;
    private String nodeCode;
    private String nodeStatus;
    private String traceId;
}
