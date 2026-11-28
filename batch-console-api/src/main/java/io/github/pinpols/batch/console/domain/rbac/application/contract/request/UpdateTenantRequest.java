package io.github.pinpols.batch.console.domain.rbac.application.contract.request;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Data
public class UpdateTenantRequest {

    @NotBlank
    @Size(max = 256)
    private String tenantName;

    @Size(max = 512)
    private String description;
}
