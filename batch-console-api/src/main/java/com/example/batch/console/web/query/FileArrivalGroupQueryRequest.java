package com.example.batch.console.web.query;

import com.example.batch.common.validation.ValidTenantId;

import jakarta.validation.constraints.Size;

import lombok.Data;

@Data
public class FileArrivalGroupQueryRequest extends PageQueryRequest {

    @ValidTenantId private String tenantId;

    @Size(max = 128, message = "fileGroupCode too long (max 128)")
    private String fileGroupCode;

    @Size(max = 32, message = "arrivalState too long (max 32)")
    private String arrivalState;
}
