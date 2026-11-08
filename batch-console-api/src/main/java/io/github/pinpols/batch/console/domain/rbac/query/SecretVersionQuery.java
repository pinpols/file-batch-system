package io.github.pinpols.batch.console.domain.rbac.query;

import lombok.Data;

@Data
public class SecretVersionQuery {

  private String tenantId;
  private String secretRef;
  private String secretStatus;
  private Boolean currentVersion;
}
