package io.github.pinpols.batch.console.domain.job.web.request;

import io.github.pinpols.batch.common.validation.ValidTenantId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import lombok.Data;

/**
 * 批量日治理动作入参（POST /api/console/batch-days/operate）。
 *
 * <p>动作集 = `BatchDayOperationService.BatchDayOperation` 枚举：FREEZE / RELEASE / SKIP / REOPEN /
 * CLOSE。orchestrator 推进状态机 + 双写审计（job_execution_log + V105 batch_day_operation_audit）。
 */
@Data
public class BatchDayOperateRequest {

  @ValidTenantId private String tenantId;

  @NotBlank
  @Size(max = 128)
  private String calendarCode;

  @NotNull private LocalDate bizDate;

  /** FREEZE / RELEASE / SKIP / REOPEN / CLOSE。 */
  @NotBlank
  @Pattern(
      regexp = "^(FREEZE|RELEASE|SKIP|REOPEN|CLOSE)$",
      message = "action must be FREEZE / RELEASE / SKIP / REOPEN / CLOSE")
  private String action;

  /** 操作人 ID（审计必填，未填走 console 上下文用户）。 */
  @Size(max = 128)
  private String operatorId;

  /** 操作原因（审计、合规留痕用，建议必填）。 */
  @Size(max = 1024)
  private String reason;
}
