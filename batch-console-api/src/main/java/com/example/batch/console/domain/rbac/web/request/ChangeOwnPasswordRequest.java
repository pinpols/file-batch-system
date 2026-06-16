package com.example.batch.console.domain.rbac.web.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 本人改密请求(首登强制改密落地路径):校验旧密码后写新密码。username 由已认证 principal 注入,不在 body 中。 */
@Data
public class ChangeOwnPasswordRequest {

  @NotBlank
  @Size(min = 8, max = 256)
  private String currentPassword;

  @NotBlank
  @Size(min = 8, max = 256)
  private String newPassword;
}
