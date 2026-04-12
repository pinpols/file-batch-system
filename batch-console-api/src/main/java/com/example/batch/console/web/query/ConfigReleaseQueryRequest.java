package com.example.batch.console.web.query;

import lombok.Data;

@Data
public class ConfigReleaseQueryRequest {

  private String tenantId;
  private String configType;
  private String configKey;
  private String configStatus;
  private Integer versionNo;
}
