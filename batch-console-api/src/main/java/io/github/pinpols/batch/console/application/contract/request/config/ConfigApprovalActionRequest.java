package io.github.pinpols.batch.console.application.contract.request.config;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Data
public class ConfigApprovalActionRequest {

    @NotBlank
    @Size(max = 64)
    private String tenantId;

    @Size(max = 512)
    private String reason;
}
