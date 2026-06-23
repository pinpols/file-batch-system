package io.github.pinpols.batch.console.domain.param;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
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
