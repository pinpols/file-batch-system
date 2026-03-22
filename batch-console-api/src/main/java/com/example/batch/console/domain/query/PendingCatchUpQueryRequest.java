package com.example.batch.console.domain.query;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PendingCatchUpQueryRequest {

    @NotBlank
    private String tenantId;
    private String jobCode;
    private String requestId;
}
