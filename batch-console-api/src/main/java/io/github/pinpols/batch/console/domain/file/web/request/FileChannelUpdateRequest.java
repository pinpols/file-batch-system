package io.github.pinpols.batch.console.domain.file.web.request;

import io.github.pinpols.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class FileChannelUpdateRequest {
  @ValidTenantId private String tenantId;

  @Size(max = 256)
  private String channelName;

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
