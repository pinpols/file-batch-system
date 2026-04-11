package com.example.batch.console.web.query;

import com.example.batch.common.validation.ValidTenantId;

import jakarta.validation.constraints.Size;

import lombok.Data;

@Data
public class DeadLetterQueryRequest extends PageQueryRequest {

    @ValidTenantId private String tenantId;

    @Size(max = 64, message = "sourceType too long (max 64)")
    private String sourceType;

    @Size(max = 32, message = "replayStatus too long (max 32)")
    private String replayStatus;

    @Size(max = 128, message = "traceId too long (max 128)")
    private String traceId;
}
