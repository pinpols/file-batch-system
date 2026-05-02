package com.example.batch.console.domain.param;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TenantQuotaPolicyUpdateParam {

  private String tenantId;
  private Long id;
  private Integer maxRunningJobsPerTenant;
  private Integer maxPartitionsPerTenant;
  private Integer maxQpsPerTenant;
  private Integer fairShareWeight;
  private Boolean enabled;
  private String description;
}
