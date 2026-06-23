package io.github.pinpols.batch.console.domain.rbac.web.request;

import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.InitMode;
import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

@Data
public class BatchCreateTenantRequest {

  @NotEmpty
  @Size(max = 50, message = "tenants must not exceed 50")
  @Valid
  private List<TenantSpec> tenants;

  /** 账号用户名前缀，最终用户名为 {prefix}{tenantId}，默认 op- */
  @Size(max = 32)
  @Pattern(
      regexp = "^[a-zA-Z0-9][a-zA-Z0-9._\\-]*$",
      message = "usernamePrefix must start with alphanumeric")
  private String usernamePrefix = "op-";

  /** 批量初始密码（高强度，≥12位），首次登录后应立即修改。 */
  @NotBlank
  @Size(min = 12, max = 256)
  private String password;

  /** 可选：配置初始化源租户 ID。非空时自动将该租户的配置复制到新建的租户。 */
  @Nullable
  @Size(max = 64)
  private String initConfigFrom;

  /** 配置初始化模式，默认 SKIP_EXISTING。仅 initConfigFrom 非空时生效。 */
  @Nullable private InitMode initMode;

  @Data
  public static class TenantSpec {

    @NotBlank
    @Size(min = 2, max = 64)
    @Pattern(
        regexp = "^[a-z0-9][a-z0-9\\-]*[a-z0-9]$",
        message = "tenant_id must be lowercase alphanumeric with hyphens")
    private String tenantId;

    @NotBlank
    @Size(max = 256)
    private String tenantName;

    @Size(max = 512)
    private String description;
  }
}
