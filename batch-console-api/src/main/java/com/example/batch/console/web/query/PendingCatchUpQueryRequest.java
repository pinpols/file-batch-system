package com.example.batch.console.web.query;

import com.example.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PendingCatchUpQueryRequest {

    @ValidTenantId
    private String tenantId;
    @Size(max = 128, message = "jobCode too long (max 128)")
    private String jobCode;
    @Size(max = 128, message = "requestId too long (max 128)")
    private String requestId;
}
