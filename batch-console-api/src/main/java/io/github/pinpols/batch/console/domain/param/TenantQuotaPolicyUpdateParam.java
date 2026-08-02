package io.github.pinpols.batch.console.domain.param;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
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
