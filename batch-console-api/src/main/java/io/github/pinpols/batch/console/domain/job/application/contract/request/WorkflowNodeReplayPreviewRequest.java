package io.github.pinpols.batch.console.domain.job.application.contract.request;

import io.github.pinpols.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Exact failed Workflow JOB node selected for preview before approval. */
@Data
public class WorkflowNodeReplayPreviewRequest {

  @ValidTenantId
  private String tenantId;

  @NotNull
  private Long workflowRunId;

  @NotNull
  private Long workflowNodeRunId;

  @NotBlank
  @Size(max = 512, message = "reason too long (max 512)")
  private String reason;
}
