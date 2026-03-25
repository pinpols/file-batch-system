package com.example.batch.console.web.query;

import com.example.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class WorkerRegistryQueryRequest {

    @ValidTenantId
    private String tenantId;
    @Size(max = 128, message = "workerGroup too long (max 128)")
    private String workerGroup;
    @Size(max = 32, message = "status too long (max 32)")
    private String status;
}
