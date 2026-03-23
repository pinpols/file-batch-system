package com.example.batch.console.web.query;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PendingCatchUpQueryRequest {

    @NotBlank
    private String tenantId;
    private String jobCode;
    private String requestId;
}
