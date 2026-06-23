package io.github.pinpols.batch.console.web.request.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Set;
import lombok.Data;

@Data
public class ConfigSyncExportRequest {

  @NotBlank
  @Size(max = 64)
  private String sourceTenantId;

  @NotBlank
  @Size(max = 64)
  private String sourceEnv;

  @NotBlank
  @Size(max = 64)
  private String targetEnv;

  private Set<TenantConfigCopyRequest.ConfigType> configTypes;
}
