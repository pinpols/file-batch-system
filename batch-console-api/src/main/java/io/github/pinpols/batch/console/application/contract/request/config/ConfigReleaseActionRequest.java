package io.github.pinpols.batch.console.application.contract.request.config;

import lombok.Data;

import jakarta.validation.constraints.Positive;

@Data
public class ConfigReleaseActionRequest {

    private String tenantId;
    private String reason;

    @Positive
    private Integer expectedVersionNo;
}
