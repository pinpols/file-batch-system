package com.example.batch.console.domain.query;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RetryScheduleQueryRequest {

    @NotBlank
    private String tenantId;
    private String relatedType;
    private String retryPolicy;
    private String retryStatus;
}
