package com.example.batch.console.domain.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ConfigReleaseUpsertRequest {

    @NotBlank
    private String tenantId;
    @NotBlank
    private String configType;
    @NotBlank
    private String configKey;
    @NotBlank
    private String configName;
    private String configPayloadJson;
    private String grayScopeJson;
    private String effectiveFromAt;
    private String effectiveToAt;
    private String operatorId;
    private String traceId;
    private String reason;
}
