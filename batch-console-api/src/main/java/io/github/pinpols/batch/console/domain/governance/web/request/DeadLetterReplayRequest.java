package io.github.pinpols.batch.console.domain.governance.web.request;

import io.github.pinpols.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DeadLetterReplayRequest {

  @ValidTenantId private String tenantId;
  @NotNull private Long deadLetterId;

  @Size(max = 512, message = "reason too long (max 512)")
  private String reason;

  @Size(max = 64, message = "operatorId too long (max 64)")
  private String operatorId;

  @Size(max = 64, message = "approvalId too long (max 64)")
  private String approvalId;

  @Size(max = 32, message = "strategy too long (max 32)")
  private String strategy;
}
