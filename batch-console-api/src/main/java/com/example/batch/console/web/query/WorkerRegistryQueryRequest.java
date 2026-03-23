package com.example.batch.console.web.query;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class WorkerRegistryQueryRequest {

    @NotBlank
    private String tenantId;
    private String workerGroup;
    private String status;
}
