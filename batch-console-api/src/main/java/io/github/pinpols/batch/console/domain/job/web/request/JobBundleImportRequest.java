package io.github.pinpols.batch.console.domain.job.web.request;

import io.github.pinpols.batch.common.validation.ValidTenantId;
import io.github.pinpols.batch.console.web.request.config.ConfigSyncBundlePayload;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.InitMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

@Data
public class JobBundleImportRequest {

  @ValidTenantId private String tenantId;

  @NotEmpty
  @Size(max = 50)
  private List<@Size(min = 1, max = 64) String> targetTenantIds;

  private InitMode mode = InitMode.UPSERT;

  private boolean dryRun;

  @Valid @NotNull private ConfigSyncBundlePayload bundle;
}
