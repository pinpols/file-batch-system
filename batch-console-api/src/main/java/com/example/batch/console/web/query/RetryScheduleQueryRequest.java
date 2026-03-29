package com.example.batch.console.web.query;

import com.example.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RetryScheduleQueryRequest extends PageQueryRequest {

    @ValidTenantId
    private String tenantId;
    @Size(max = 32, message = "relatedType too long (max 32)")
    private String relatedType;
    @Size(max = 32, message = "retryPolicy too long (max 32)")
    private String retryPolicy;
    @Size(max = 32, message = "retryStatus too long (max 32)")
    private String retryStatus;
}
