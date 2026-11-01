package io.github.pinpols.batch.console.domain.rbac.view;

import java.time.Instant;
import lombok.Data;

/** 本地账号与 OIDC 主体绑定及账号名称的查询视图。 */
@Data
public class ConsoleOidcIdentityView {

  private Long id;
  private String tenantId;
  private Long accountId;
  private String username;
  private String issuer;
  private String subject;
  private String linkedBy;
  private Instant linkedAt;
}
