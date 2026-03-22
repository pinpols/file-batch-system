package com.example.batch.console.domain.query;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class WorkerRegistryQueryRequest {

    @NotBlank
    private String tenantId;
    private String workerGroup;
    private String status;
}
