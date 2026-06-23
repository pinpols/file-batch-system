package io.github.pinpols.batch.console.domain.file.param;

import lombok.Data;

@Data
public class FileChannelConfigUpdateParam {

  private String tenantId;
  private Long id;
  private String channelName;
  private String channelType;
  private String targetEndpoint;
  private String authType;
  private String configJson;
  private String receiptPolicy;
  private Integer timeoutSeconds;
  private Boolean enabled;
  private String updatedBy;
}
