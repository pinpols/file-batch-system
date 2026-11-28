package io.github.pinpols.batch.console.domain.config.application.contract.request;

import lombok.Data;

import io.github.pinpols.batch.common.validation.ValidTenantId;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Data
public class SecretVersionRevokeRequest {

    @ValidTenantId
    private String tenantId;

    @NotBlank
    @Size(max = 512)
    private String reason;
}
