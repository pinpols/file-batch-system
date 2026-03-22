package com.example.batch.console.domain.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DrainWorkerRequest {

    @NotBlank
    private String tenantId;
    /** Seconds until orchestrator takeover; omit to use server default (batch.worker.drain.default-timeout-seconds). */
    private Integer timeoutSeconds;
}
