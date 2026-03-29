package com.example.batch.console.web.query;

import com.example.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class BatchDayWindowQueryRequest {

    @ValidTenantId
    private String tenantId;

    @NotBlank
    @Size(max = 128, message = "calendarCode too long (max 128)")
    private String calendarCode;
}
