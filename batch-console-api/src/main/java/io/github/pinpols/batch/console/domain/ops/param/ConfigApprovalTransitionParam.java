package io.github.pinpols.batch.console.domain.ops.param;

import io.github.pinpols.batch.common.enums.ApprovalCommandStatus;
import lombok.Getter;

/** 配置审批状态迁移参数；目标状态由具名工厂固定。 */
@Getter
public final class ConfigApprovalTransitionParam {

  private final String tenantId;
  private final Long id;
  private final String reviewedBy;
  private final String reviewComment;
  private final String expectedStatus;
  private final String targetStatus;

  private ConfigApprovalTransitionParam(
      String tenantId,
      Long id,
      String reviewedBy,
      String reviewComment,
      ApprovalCommandStatus targetStatus) {
    this.tenantId = tenantId;
    this.id = id;
    this.reviewedBy = reviewedBy;
    this.reviewComment = reviewComment;
    this.expectedStatus = ApprovalCommandStatus.PENDING.code();
    this.targetStatus = targetStatus.code();
  }

  public static ConfigApprovalTransitionParam approved(
      String tenantId, Long id, String reviewedBy, String reviewComment) {
    return new ConfigApprovalTransitionParam(
        tenantId, id, reviewedBy, reviewComment, ApprovalCommandStatus.APPROVED);
  }

  public static ConfigApprovalTransitionParam rejected(
      String tenantId, Long id, String reviewedBy, String reviewComment) {
    return new ConfigApprovalTransitionParam(
        tenantId, id, reviewedBy, reviewComment, ApprovalCommandStatus.REJECTED);
  }
}
