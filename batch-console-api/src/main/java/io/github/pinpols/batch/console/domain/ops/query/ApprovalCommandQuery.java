package io.github.pinpols.batch.console.domain.ops.query;

import io.github.pinpols.batch.common.model.PageRequest;
import lombok.Data;

@Data
public class ApprovalCommandQuery {

  private String tenantId;
  private String approvalNo;
  private String approvalType;
  private String actionType;
  private String approvalStatus;
  private String requesterId;
  private String keyword;
  private PageRequest pageRequest;
}
