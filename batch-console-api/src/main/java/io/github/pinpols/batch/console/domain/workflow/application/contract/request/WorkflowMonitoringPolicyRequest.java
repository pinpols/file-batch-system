package io.github.pinpols.batch.console.domain.workflow.application.contract.request;

import io.github.pinpols.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalTime;
import lombok.Data;

/** 完整替换根级软时限策略；零阈值及空截止钟点分别关闭对应规则。 */
@Data
public class WorkflowMonitoringPolicyRequest {
  @ValidTenantId
  private String tenantId;

  @Min(0)
  private int softRuntimeSeconds;

  @NotNull
  @Pattern(regexp = "^(WARN|ERROR|CRITICAL)$")
  private String softRuntimeSeverity = "WARN";

  @Min(0)
  private int startGraceSeconds;

  @NotNull
  @Pattern(regexp = "^(WARN|ERROR|CRITICAL)$")
  private String startGraceSeverity = "WARN";

  private LocalTime completionDeadlineLocalTime;

  @Min(0)
  @Max(1)
  private int completionDeadlineDayOffset;

  @NotNull
  @Pattern(regexp = "^(WARN|ERROR|CRITICAL)$")
  private String completionDeadlineSeverity = "WARN";
}
