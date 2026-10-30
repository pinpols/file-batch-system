package io.github.pinpols.batch.orchestrator.domain.param;

import java.util.Objects;
import lombok.Builder;

/** 审批拒绝的单次条件写入，保留原来源与目标状态。 */
@Builder
public record RejectCommandDecisionParam(
    String tenantId,
    String approvalNo,
    String approverId,
    String approvalReason,
    String rejectedStatus,
    String pendingStatus) {

  public RejectCommandDecisionParam {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(approvalNo, "approvalNo");
  }
}
