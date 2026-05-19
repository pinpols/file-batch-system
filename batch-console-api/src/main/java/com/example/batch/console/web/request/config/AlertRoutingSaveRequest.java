package com.example.batch.console.web.request.config;

import com.example.batch.common.validation.ValidResourceCode;
import com.example.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AlertRoutingSaveRequest {
  @ValidTenantId private String tenantId;

  @ValidResourceCode private String routeCode;

  @Size(max = 128)
  private String routeName;

  @NotBlank
  @Size(max = 64)
  private String team;

  @Size(max = 64)
  private String alertGroup;

  @NotBlank
  @Size(max = 32)
  private String severity;

  @NotBlank
  @Size(max = 256)
  private String receiver;

  @Size(max = 128)
  private String groupBy;

  @Min(0)
  private Integer groupWaitSeconds;

  @Min(0)
  private Integer groupIntervalSeconds;

  @Min(0)
  private Integer repeatIntervalSeconds;

  private Boolean enabled;

  @Size(max = 512)
  private String description;
}
