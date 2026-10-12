package io.github.pinpols.batch.console.domain.ops.application.contract.request;

import io.github.pinpols.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SecretVersionRevokeRequest {

  @ValidTenantId
  private String tenantId;

  @NotBlank
  @Size(max = 512)
  private String reason;
}
