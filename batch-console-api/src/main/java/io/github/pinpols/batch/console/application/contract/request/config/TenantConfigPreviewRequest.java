package io.github.pinpols.batch.console.application.contract.request.config;

import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigCopyRequest.ConfigType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import lombok.Data;

/** 用于只读预览租户配置差异及覆盖项的请求。 */
@Data
public class TenantConfigPreviewRequest {

  @NotBlank(message = "sourceTenantId must not be blank")
  @Size(max = 64)
  private String sourceTenantId;

  @NotEmpty(message = "targetTenantIds must not be empty")
  @Size(max = 50, message = "targetTenantIds must not exceed 50")
  private List<@NotBlank @Size(min = 1, max = 64) String> targetTenantIds;

  /** 为空时表示包含所有配置类型。 */
  private Set<@NotNull ConfigType> configTypes;

  /** 非空时表示仅预览所列作业所需的最小依赖集合。 */
  @Size(max = 100, message = "jobCodes must not exceed 100")
  private List<@NotBlank @Size(min = 1, max = 128) String> jobCodes;

  private boolean includeUnchanged;

  private boolean includeDeleteCandidates;
}
