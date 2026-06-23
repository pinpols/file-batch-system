package io.github.pinpols.batch.console.domain.job.entity;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Data;

@Data
public class PendingCatchUpEntity {

  private Long id;
  private String tenantId;
  private String requestId;
  private String jobCode;
  private LocalDate bizDate;
  private String requestStatus;
  private String traceId;
  private Instant createdAt;
  private Instant updatedAt;

  /**
   * 关联 approval_command.approval_no(LEFT JOIN: approval_command.target_id =
   * trigger_request.request_id AND approval_type='CATCH_UP')。未走统一审批的旧记录可为 null,前端据此判断是否可操作审批按钮。
   */
  private String approvalNo;

  /** 关联 approval_command.approval_status: PENDING / APPROVED / REJECTED / EXECUTED, 可空。 */
  private String approvalStatus;
}
