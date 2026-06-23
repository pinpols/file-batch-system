package io.github.pinpols.batch.console.domain.workflow.web.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class WorkflowExcelApplyRequest {

  @Size(max = 512, message = "reason too long (max 512)")
  private String reason;
}
