package io.github.pinpols.batch.console.web.request.config;

import io.github.pinpols.batch.console.web.request.config.TenantConfigCopyRequest.ConfigType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import lombok.Data;

/** Request for read-only tenant config diff and overlay preview. */
@Data
public class TenantConfigPreviewRequest {

  @NotBlank(message = "sourceTenantId must not be blank")
  @Size(max = 64)
  private String sourceTenantId;

  @NotEmpty(message = "targetTenantIds must not be empty")
  @Size(max = 50, message = "targetTenantIds must not exceed 50")
  private List<@Size(min = 1, max = 64) String> targetTenantIds;

  /** Empty means all config types. */
  private Set<ConfigType> configTypes;

  /** Non-empty means preview the minimal dependency bundle for the listed jobs. */
  @Size(max = 100, message = "jobCodes must not exceed 100")
  private List<@Size(min = 1, max = 128) String> jobCodes;

  private boolean includeUnchanged;

  private boolean includeDeleteCandidates;
}
