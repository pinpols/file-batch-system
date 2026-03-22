package com.example.batch.console.domain.query;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class OutboxRetryLogQueryRequest {

    @NotBlank
    private String tenantId;
    private String retryStatus;
    private String eventKey;
}
