package io.github.pinpols.batch.console.web.query;

import lombok.Data;

@Data
public class SecretVersionQueryRequest {

  private String tenantId;
  private String secretRef;
  private String secretStatus;
  private Boolean currentVersion;
}
