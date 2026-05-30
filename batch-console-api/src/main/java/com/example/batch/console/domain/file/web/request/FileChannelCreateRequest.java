package com.example.batch.console.domain.file.web.request;

import com.example.batch.common.validation.ValidResourceCode;
import com.example.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class FileChannelCreateRequest {
  @ValidTenantId private String tenantId;

  @ValidResourceCode private String channelCode;

  @Size(max = 256)
  private String channelName;

  @NotBlank
  @Size(max = 32)
  private String channelType;

  @Size(max = 512)
  private String targetEndpoint;

  @Size(max = 32)
  private String authType;

  private String configJson;

  @Size(max = 32)
  private String receiptPolicy;

  private Integer timeoutSeconds;
  private Boolean enabled;
}
