package io.github.pinpols.batch.console.domain.ops.web.request;

import io.github.pinpols.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.Data;

@Data
public class SecretVersionRotateRequest {

  @ValidTenantId
  private String tenantId;

  @NotBlank
  @Size(max = 128, message = "secretRef too long (max 128)")
  private String secretRef;

  @NotBlank
  @Size(max = 256, message = "secretName too long (max 256)")
  private String secretName;

  @Size(max = 65535, message = "secretPayloadJson too long (max 65535)")
  private String secretPayloadJson;

  /** 在 API 弃用过渡期内保留的旧版请求结构。 */
  private Map<String, Object> secretPayload;

  private String secretStatus;
  private String rotationWindowStartAt;
  private String rotationWindowEndAt;
  private String effectiveFromAt;
  private String effectiveToAt;

  @Size(max = 512, message = "reason too long (max 512)")
  private String reason;
}
