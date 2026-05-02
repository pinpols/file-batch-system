package com.example.batch.console.mapper.param;

import lombok.Data;

@Data
public class TenantQuotaPolicyUpsertParam {

  private String tenantId;
  private String policyCode;
  private Integer maxRunningJobsPerTenant;
  private Integer maxPartitionsPerTenant;
  private Integer maxQpsPerTenant;
  private Integer fairShareWeight;
  private Boolean enabled;
  private String description;
}
