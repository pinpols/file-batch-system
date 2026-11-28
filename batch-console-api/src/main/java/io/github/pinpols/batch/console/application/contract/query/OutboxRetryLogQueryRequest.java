package io.github.pinpols.batch.console.application.contract.query;

import lombok.Data;
import lombok.EqualsAndHashCode;

import io.github.pinpols.batch.common.validation.ValidTenantId;

import jakarta.validation.constraints.Size;

@Data
@EqualsAndHashCode(callSuper = true)
public class OutboxRetryLogQueryRequest extends PageQueryRequest {

    @ValidTenantId
    private String tenantId;

    @Size(max = 64, message = "eventType too long (max 64)")
    private String eventType;

    @Size(max = 32, message = "retryStatus too long (max 32)")
    private String retryStatus;

    @Size(max = 512, message = "eventKey too long (max 512)")
    private String eventKey;
}
