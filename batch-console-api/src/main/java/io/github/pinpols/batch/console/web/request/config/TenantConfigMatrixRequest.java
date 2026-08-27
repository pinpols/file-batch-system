package io.github.pinpols.batch.console.web.request.config;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

/** Request for comparing the same job configuration across tenants. */
@Data
public class TenantConfigMatrixRequest {

  @NotEmpty(message = "tenantIds must not be empty")
  @Size(max = 50, message = "tenantIds must not exceed 50")
  private List<@Size(min = 1, max = 64) String> tenantIds;

  @NotEmpty(message = "jobCodes must not be empty")
  @Size(max = 100, message = "jobCodes must not exceed 100")
  private List<@Size(min = 1, max = 128) String> jobCodes;

  /**
   * Tenant used as the drift baseline. When omitted, the first tenant in tenantIds is used.
   */
  @Size(max = 64)
  private String baselineTenantId;
}
