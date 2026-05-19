package com.example.batch.console.web.request.config;

import com.example.batch.common.validation.ValidResourceCode;
import com.example.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class QuotaPolicySaveRequest {
  @ValidTenantId private String tenantId;

  @ValidResourceCode private String policyCode;

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
