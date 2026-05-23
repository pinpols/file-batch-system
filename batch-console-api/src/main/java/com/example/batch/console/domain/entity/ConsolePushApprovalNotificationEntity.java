package com.example.batch.console.domain.entity;

import java.time.Instant;
import lombok.Data;

/**
 * 审批结果推送去重记录。
 *
 * <p>对应表 {@code batch.console_push_approval_notification}(V153)。 ConsolePushApprovalNotifier 周期写入,确保同一
 * (tenant, approval_no) 仅推送一次。
 */
@Data
public class ConsolePushApprovalNotificationEntity {

  private Long id;
  private String tenantId;
  private String approvalNo;
  private Instant notifiedAt;
}
