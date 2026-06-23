package io.github.pinpols.batch.console.domain.rbac.web.request;

import io.github.pinpols.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建账号请求体（POST /api/console/users）。username 与 password 用 V34 / CreateTenantRequest 同款约束， 保证密码至少 8
 * 位、username 仅含字母数字 / 点 / 下划线 / 短横线。
 */
@Data
public class CreateUserAccountRequest {

  @ValidTenantId private String tenantId;

  @NotBlank
  @Size(min = 2, max = 128)
  @Pattern(
      regexp = "^[a-zA-Z0-9][a-zA-Z0-9._\\-]*$",
      message =
          "username must start with alphanumeric and contain only letters, digits, '.', '_', '-'")
  private String username;

  @NotBlank
  @Size(min = 8, max = 256)
  private String password;

  @Size(max = 256)
  private String displayName;

  /** CSV (e.g. "ROLE_ADMIN,ROLE_TENANT_USER")。为空时 service 落 USER 默认权限。 */
  @Size(max = 512)
  private String authoritiesCsv;
}
