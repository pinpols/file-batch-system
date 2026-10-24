package io.github.pinpols.batch.console.domain.workflow.application.contract.request;

import io.github.pinpols.batch.common.validation.ValidBizDate;
import io.github.pinpols.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Manual launch request for an explicit top-level Workflow target. */
public record WorkflowTriggerRequest(
    @ValidTenantId String tenantId,
    @NotBlank @ValidBizDate String bizDate,
    @Size(max = 32) String triggerType,
    @Size(max = 16_384) String payload) {}
