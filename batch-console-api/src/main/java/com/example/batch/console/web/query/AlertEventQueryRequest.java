package com.example.batch.console.web.query;

import com.example.batch.common.validation.ValidTenantId;

import jakarta.validation.constraints.Size;

import lombok.Data;

@Data
public class AlertEventQueryRequest extends PageQueryRequest {

    @ValidTenantId private String tenantId;

    @Size(max = 16, message = "severity too long (max 16)")
    private String severity;

    @Size(max = 32, message = "status too long (max 32)")
    private String status;

    @Size(max = 64, message = "alertType too long (max 64)")
    private String alertType;
}
