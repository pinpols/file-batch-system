package com.example.batch.console.domain.query;

import lombok.Data;

@Data
public class SecretVersionQuery {

  private String tenantId;
  private String secretRef;
  private String secretStatus;
  private Boolean currentVersion;
}
