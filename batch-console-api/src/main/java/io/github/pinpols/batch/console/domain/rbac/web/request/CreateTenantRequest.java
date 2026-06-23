package io.github.pinpols.batch.console.domain.rbac.web.request;

import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.InitMode;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateTenantRequest {

  @NotBlank
  @Size(min = 2, max = 64)
  @Pattern(
      regexp = "^[a-z0-9][a-z0-9\\-]*[a-z0-9]$",
      message = "tenant_id must be lowercase alphanumeric with hyphens, e.g. my-tenant")
  private String tenantId;

  @NotBlank
  @Size(max = 256)
  private String tenantName;

  @Size(max = 512)
  private String description;

  @NotBlank
  @Size(min = 2, max = 128)
  @Pattern(
      regexp = "^[a-zA-Z0-9][a-zA-Z0-9._\\-]*$",
      message =
          "username must start with alphanumeric and contain only letters, digits,"
              + " '.', '_', '-'")
  private String username;

  @NotBlank
  @Size(min = 8, max = 256)
  private String password;

  /**
   * 可选:配置初始化源租户 ID。非空时建完租户后自动将该租户的配置复制到新租户(复用 batch 路径的 configCopyService)。 默认 null =
   * 不复制,保持现行为(向后兼容)。
   */
  @Nullable
  @Size(max = 64)
  private String initConfigFrom;

  /** 配置初始化模式,默认 SKIP_EXISTING。仅 initConfigFrom 非空时生效。 */
  @Nullable private InitMode initMode;
}
