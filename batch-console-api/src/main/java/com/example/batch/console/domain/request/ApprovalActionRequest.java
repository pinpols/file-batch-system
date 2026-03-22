package com.example.batch.console.domain.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ApprovalActionRequest {

    @NotBlank
    private String tenantId;
    private String operatorId;
    private String reason;
}
