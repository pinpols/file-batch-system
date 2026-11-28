package io.github.pinpols.batch.console.application.contract.request.config;

import lombok.Data;

import io.github.pinpols.batch.common.validation.ValidResourceCode;
import io.github.pinpols.batch.common.validation.ValidTenantId;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@Data
public class QuotaPolicySaveRequest {
    @ValidTenantId
    private String tenantId;

    @ValidResourceCode
    private String policyCode;

    @Min(0)
    private Integer maxRunningJobsPerTenant;

    @Min(0)
    private Integer maxPartitionsPerTenant;

    @Min(0)
    private Integer maxQpsPerTenant;

    @Min(1)
    private Integer fairShareWeight;

    private Boolean enabled;

    @Size(max = 512)
    private String description;
}
