package io.github.pinpols.batch.console.domain.workflow.mapper;

import org.apache.ibatis.annotations.Param;

import io.github.pinpols.batch.console.domain.workflow.application.contract.response.WorkflowTriggerStatusResponse;

/** 只按原租户、Workflow 及客户端持有的幂等身份核对请求。 */
public interface WorkflowTriggerReceiptMapper {
    WorkflowTriggerStatusResponse selectByIdentity(
            @Param("tenantId") String tenantId,
            @Param("workflowDefinitionId") Long workflowDefinitionId,
            @Param("idempotencyKey") String idempotencyKey);
}
