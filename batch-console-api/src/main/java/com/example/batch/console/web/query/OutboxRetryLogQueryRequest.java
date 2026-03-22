package com.example.batch.console.web.query;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class OutboxRetryLogQueryRequest {

    @NotBlank
    private String tenantId;
    private String retryStatus;
    private String eventKey;
}
