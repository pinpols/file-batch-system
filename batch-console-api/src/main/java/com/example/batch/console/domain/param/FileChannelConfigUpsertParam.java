package com.example.batch.console.domain.param;

import lombok.Data;

@Data
public class FileChannelConfigUpsertParam {

  // INSERT 后 MyBatis 用 useGeneratedKeys 回写自增 id 到此字段;字段缺失 → "No setter found"
  private Long id;
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
