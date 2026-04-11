package com.example.batch.console.web.query;

import com.example.batch.common.validation.ValidBizDate;
import com.example.batch.common.validation.ValidTenantId;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;

@Data
public class BatchDayQueryRequest extends PageQueryRequest {

    @ValidTenantId private String tenantId;

    @NotBlank
    @Size(max = 128, message = "calendarCode too long (max 128)")
    private String calendarCode;

    @ValidBizDate private String from;

    @ValidBizDate private String to;
}
