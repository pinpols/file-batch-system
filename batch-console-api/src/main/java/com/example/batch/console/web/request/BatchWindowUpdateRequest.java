package com.example.batch.console.web.request;

import com.example.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class BatchWindowUpdateRequest {
  @ValidTenantId private String tenantId;

  @Size(max = 256)
  private String windowName;

  @Size(max = 64)
  private String timezone;

  private String startTime;
  private String endTime;

  @Size(max = 32)
  private String endStrategy;

  @Size(max = 32)
  private String outOfWindowAction;

  private Boolean allowCrossDay;
  private Boolean enabled;

  @Size(max = 512)
  private String description;
}
