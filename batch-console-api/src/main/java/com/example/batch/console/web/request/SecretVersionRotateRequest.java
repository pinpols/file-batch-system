package com.example.batch.console.web.request;

import com.example.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SecretVersionRotateRequest {

  @ValidTenantId private String tenantId;

  @NotBlank
  @Size(max = 128, message = "secretRef too long (max 128)")
  private String secretRef;

  @NotBlank
  @Size(max = 256, message = "secretName too long (max 256)")
  private String secretName;

  private String secretPayloadJson;
  private String secretStatus;
  private String rotationWindowStartAt;
  private String rotationWindowEndAt;
  private String effectiveFromAt;
  private String effectiveToAt;

  @Size(max = 64, message = "operatorId too long (max 64)")
  private String operatorId;

  @Size(max = 128, message = "traceId too long (max 128)")
  private String traceId;

  @Size(max = 512, message = "reason too long (max 512)")
  private String reason;
}
