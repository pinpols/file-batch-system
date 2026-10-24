package io.github.pinpols.batch.console.domain.job.application.contract.request;

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
 * CLOSE / SETTLE_EMPTY。orchestrator 推进状态机 + 双写审计（job_execution_log + V105 batch_day_operation_audit）。
 */
@Data
public class BatchDayOperateRequest {

  @ValidTenantId
  private String tenantId;

  @NotBlank
  @Size(max = 128)
  private String calendarCode;

  @NotNull
  private LocalDate bizDate;

  /** FREEZE / RELEASE / SKIP / REOPEN / CLOSE / SETTLE_EMPTY。 */
  @NotBlank
  @Pattern(
      regexp = "^(FREEZE|RELEASE|SKIP|REOPEN|CLOSE|SETTLE_EMPTY)$",
      message = "action must be FREEZE / RELEASE / SKIP / REOPEN / CLOSE / SETTLE_EMPTY")
  private String action;

  /** 兼容旧客户端保留；服务端忽略此值，审计操作者始终取认证上下文。 */
  @Size(max = 128)
  private String operatorId;

  /** 操作原因（SKIP、REOPEN、SETTLE_EMPTY 必填；写入批量日操作审计）。 */
  @Size(max = 1024)
  private String reason;
}
