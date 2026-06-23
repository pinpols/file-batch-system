package io.github.pinpols.batch.console.domain.notification.support;

import java.time.Instant;
import lombok.Data;

/** {@link ConsolePushApprovalNotifier} 扫描结果 DTO,仅在推送链路内部传递。 */
@Data
public class PendingApprovalNotification {
  private String tenantId;
  private String approvalNo;
  private String approvalType;
  private String approvalStatus;
  private String requesterId;
  private String approverId;
  private String approvalReason;
  private String rejectionReason;
  private Instant approvedAt;
}
