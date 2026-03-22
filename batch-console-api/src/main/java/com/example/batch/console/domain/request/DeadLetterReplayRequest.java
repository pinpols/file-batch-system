package com.example.batch.console.domain.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DeadLetterReplayRequest {

    @NotBlank
    private String tenantId;
    @NotNull
    private Long deadLetterId;
    private String reason;
    private String operatorId;
    private String approvalId;
    private String strategy;
}
