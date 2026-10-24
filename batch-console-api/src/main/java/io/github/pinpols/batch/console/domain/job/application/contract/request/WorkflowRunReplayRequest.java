package io.github.pinpols.batch.console.domain.job.application.contract.request;

import io.github.pinpols.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 整 Workflow 重放请求；操作者身份始终取自已认证的 Console 上下文。 */
@Data
public class WorkflowRunReplayRequest {

  @ValidTenantId
  private String tenantId;

  @NotNull
  private Long workflowRunId;

  @NotBlank
  @Size(max = 512, message = "reason too long (max 512)")
  private String reason;

  @Size(max = 64)
  private String resultPolicy;

  @Size(max = 64)
  private String configVersionPolicy;

  private Integer configVersion;

  @Pattern(regexp = "[0-9a-fA-F-]{36}")
  private String operationKey;

  @Size(max = 128)
  private String previewToken;
}
