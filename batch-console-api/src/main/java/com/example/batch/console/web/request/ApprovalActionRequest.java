package com.example.batch.console.web.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ApprovalActionRequest {

    @NotBlank
    private String tenantId;
    private String operatorId;
    private String reason;
}
