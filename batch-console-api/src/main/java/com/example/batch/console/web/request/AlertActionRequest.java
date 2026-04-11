package com.example.batch.console.web.request;

import com.example.batch.common.validation.ValidTenantId;

import jakarta.validation.constraints.Size;

import lombok.Data;

@Data
public class AlertActionRequest {

    @ValidTenantId private String tenantId;

    @Size(max = 64, message = "operatorId too long (max 64)")
    private String operatorId;

    @Size(max = 512, message = "reason too long (max 512)")
    private String reason;
}
