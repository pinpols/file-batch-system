package com.example.batch.console.domain.ops.web.request;

import com.example.batch.common.validation.ValidBizDate;
import com.example.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ConsoleCatchUpApprovalRequest {

  @ValidTenantId private String tenantId;

  @NotBlank
  @Size(max = 128, message = "requestId too long (max 128)")
  private String requestId;

  @NotBlank
  @Size(max = 128, message = "jobCode too long (max 128)")
  /** 用于解析补跑目标的业务作业标识。 */
  private String jobCode;

  @NotBlank @ValidBizDate private String bizDate;

  @Size(max = 64, message = "scheduledAt too long (max 64)")
  private String scheduledAt;

  @Size(max = 512, message = "reason too long (max 512)")
  private String reason;

  @Size(max = 64, message = "approvalId too long (max 64)")
  private String approvalId;
}
