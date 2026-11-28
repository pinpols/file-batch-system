package io.github.pinpols.batch.console.domain.job.application.contract.query;

import lombok.Data;

import io.github.pinpols.batch.common.validation.ValidTenantId;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Data
public class BatchDayWindowQueryRequest {

    @ValidTenantId
    private String tenantId;

    @NotBlank
    @Size(max = 128, message = "calendarCode too long (max 128)")
    private String calendarCode;
}
