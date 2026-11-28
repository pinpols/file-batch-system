package io.github.pinpols.batch.console.domain.ops.application.contract.request;

import lombok.Data;

import io.github.pinpols.batch.common.validation.ValidTenantId;

@Data
public class ForceOfflineWorkerRequest {

    @ValidTenantId
    private String tenantId;
}
