package com.example.batch.console.web.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

@Data
public class ConfigSyncImportRequest {

  @NotBlank
  @Size(max = 64)
  private String tenantId;

  @NotBlank
  @Size(max = 64)
  private String sourceEnv;

  @NotBlank
  @Size(max = 64)
  private String targetEnv;

  @NotEmpty
  @Size(max = 50)
  private List<@Size(min = 1, max = 64) String> targetTenantIds;

  private TenantConfigBatchInitRequest.InitMode mode =
      TenantConfigBatchInitRequest.InitMode.SKIP_EXISTING;

  private boolean dryRun;

  @Valid private ConfigSyncBundlePayload bundle;
}
