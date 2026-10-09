package io.github.pinpols.batch.console.domain.rbac.entity;

import java.time.Instant;
import lombok.Data;

/** 本地账号与 OIDC 稳定主体标识的显式绑定记录。 */
@Data
public class ConsoleOidcIdentityEntity {

  private Long id;
  private String tenantId;
  private Long accountId;
  private String username;
  private String issuer;
  private String subject;
  private String linkedBy;
  private Instant linkedAt;
}
