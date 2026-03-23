package com.example.batch.console.web.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TriggerRequest {

    @NotBlank
    private String tenantId;
    @NotBlank
    private String jobCode;
    @NotBlank
    private String bizDate;
    private String triggerType;
    private String payload;
}
