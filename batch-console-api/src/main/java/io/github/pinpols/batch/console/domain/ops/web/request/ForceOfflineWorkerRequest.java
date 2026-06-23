package io.github.pinpols.batch.console.domain.ops.web.request;

import io.github.pinpols.batch.common.validation.ValidTenantId;
import lombok.Data;

@Data
public class ForceOfflineWorkerRequest {

  @ValidTenantId private String tenantId;
}
