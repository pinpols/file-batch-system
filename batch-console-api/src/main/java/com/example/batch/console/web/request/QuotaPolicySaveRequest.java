package com.example.batch.console.web.request;

import com.example.batch.common.validation.ValidTenantId;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;

@Data
public class QuotaPolicySaveRequest {
    @ValidTenantId private String tenantId;

    @NotBlank
    @Size(max = 128)
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
