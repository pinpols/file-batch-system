package io.github.pinpols.batch.console.domain.job.web.request;

import io.github.pinpols.batch.common.validation.ValidTenantId;
import io.github.pinpols.batch.console.web.request.config.ConfigSyncBundlePayload;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.InitMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class JobBundleCreateRequest {

  @ValidTenantId private String tenantId;

  private InitMode mode = InitMode.SKIP_EXISTING;

  private boolean dryRun;

  @Valid @NotNull private ConfigSyncBundlePayload bundle;
}
