package com.example.batch.console.web.query;

import com.example.batch.common.validation.ValidTenantId;

import jakarta.validation.constraints.Size;

import lombok.Data;

@Data
public class FileErrorRecordQueryRequest extends PageQueryRequest {

    @ValidTenantId private String tenantId;
    private Long fileId;

    @Size(max = 64, message = "errorStage too long (max 64)")
    private String errorStage;

    @Size(max = 128, message = "errorCode too long (max 128)")
    private String errorCode;

    private Boolean skipped;
}
