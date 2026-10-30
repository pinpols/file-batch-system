package io.github.pinpols.batch.orchestrator.domain.param;

import java.util.Objects;
import lombok.Builder;

/** 审批同意的单次条件写入，保留原来源与目标状态。 */
@Builder
public record ApproveCommandDecisionParam(
    String tenantId,
    String approvalNo,
    String approverId,
    String approvalReason,
    String approvedStatus,
    String pendingStatus) {

  public ApproveCommandDecisionParam {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(approvalNo, "approvalNo");
  }
}
