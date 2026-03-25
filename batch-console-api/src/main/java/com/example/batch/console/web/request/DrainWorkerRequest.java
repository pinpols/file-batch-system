package com.example.batch.console.web.request;

import com.example.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class DrainWorkerRequest {

    @ValidTenantId
    private String tenantId;
    /** Seconds until orchestrator takeover; omit to use server default (batch.worker.drain.default-timeout-seconds). */
    @Min(value = 1, message = "timeoutSeconds must be positive")
    private Integer timeoutSeconds;
}
