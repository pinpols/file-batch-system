package com.example.batch.console.web.query;

import com.example.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AlertEventQueryRequest {

    private static final int DEFAULT_LIMIT = 100;

    @ValidTenantId
    private String tenantId;
    @Size(max = 16, message = "severity too long (max 16)")
    private String severity;
    @Size(max = 32, message = "status too long (max 32)")
    private String status;
    @Size(max = 64, message = "alertType too long (max 64)")
    private String alertType;
    @Min(1)
    @Max(500)
    private Integer limit = DEFAULT_LIMIT;
}
