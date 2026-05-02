package com.example.batch.console.domain.param;

import lombok.Data;

@Data
public class FileChannelConfigUpsertParam {

  private String tenantId;
  private String channelCode;
  private String channelName;
  private String channelType;
  private String targetEndpoint;
  private String authType;
  private String configJson;
  private String receiptPolicy;
  private Integer timeoutSeconds;
  private Boolean enabled;
  private String createdBy;
  private String updatedBy;
}
