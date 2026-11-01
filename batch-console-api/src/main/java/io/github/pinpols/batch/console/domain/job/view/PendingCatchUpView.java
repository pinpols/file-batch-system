package io.github.pinpols.batch.console.domain.job.view;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Data;

@Data
public class PendingCatchUpView {

  private Long id;
  private String tenantId;
  private String requestId;
  private String workflowCode;
  private LocalDate bizDate;
  private String requestStatus;
  private String traceId;
  private Instant createdAt;
  private Instant updatedAt;

  /** 同租户、同请求的最新 CATCH_UP 审批单号；没有关联审批单时为 null。 */
  private String approvalNo;

  /** 关联 approval_command.approval_status: PENDING / APPROVED / REJECTED / EXECUTED, 可空。 */
  private String approvalStatus;
}
