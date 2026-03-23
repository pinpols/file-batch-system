package com.example.batch.console.web.query;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DeadLetterQueryRequest {

    @NotBlank
    private String tenantId;
    private String sourceType;
    private String replayStatus;
    private String traceId;
}
