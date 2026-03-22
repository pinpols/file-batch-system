package com.example.batch.console.web.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SecretVersionRotateRequest {

    @NotBlank
    private String tenantId;
    @NotBlank
    private String secretRef;
    @NotBlank
    private String secretName;
    private String secretPayloadJson;
    private String secretStatus;
    private String rotationWindowStartAt;
    private String rotationWindowEndAt;
    private String effectiveFromAt;
    private String effectiveToAt;
    private String operatorId;
    private String traceId;
    private String reason;
}
