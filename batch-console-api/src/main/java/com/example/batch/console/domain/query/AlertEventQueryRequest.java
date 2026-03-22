package com.example.batch.console.domain.query;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AlertEventQueryRequest {

    @NotBlank
    private String tenantId;
    private String severity;
    private String status;
    private String alertType;
    @Min(1)
    @Max(500)
    private Integer limit = 100;
}
