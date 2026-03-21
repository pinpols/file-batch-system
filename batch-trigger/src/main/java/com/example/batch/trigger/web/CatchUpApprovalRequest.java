package com.example.batch.trigger.web;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CatchUpApprovalRequest {

    @NotBlank
    private String tenantId;
    @NotBlank
    private String requestId;
    private String reason;
}
